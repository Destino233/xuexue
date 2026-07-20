package dev.xuexue.platform

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ChapterCandidate(
    val pageIndex: Int,
    val blockId: String,
    val title: String,
    val level: Int,
    val confidence: Float,
    val destination: PdfOutlineDestination,
    val bounds: PdfNormalizedRect,
    val source: PdfOutlineSource,
    val reasons: List<String>,
    val signals: Map<String, Float>,
    val requiresReview: Boolean = false,
    val printedPage: String? = null,
    val matchedTocTitle: String? = null,
)

data class PdfTocEntry(
    val title: String,
    val printedPage: String,
    val level: Int,
    val sourcePageIndex: Int,
    val indentation: Int = 0,
)

data class PrintedPageMapping(
    val printedPage: String,
    val pdfPageIndex: Int,
    val confidence: Float,
    val source: String,
)

data class ChapterDetectionResult(
    val outline: PdfOutlineDocument,
    val candidates: List<ChapterCandidate>,
    val tocEntries: List<PdfTocEntry>,
    val printedPageMappings: List<PrintedPageMapping>,
)

private enum class PrintedPageKind {
    ARABIC,
    ROMAN,
}

private data class ParsedPrintedPage(
    val kind: PrintedPageKind,
    val number: Int,
)

private data class PrintedPageObservation(
    val key: String,
    val kind: PrintedPageKind,
    val number: Int,
    val pageIndex: Int,
)

private data class ParsedTocLine(
    val title: String,
    val printedPage: String,
    val level: Int,
    val indentation: Int,
)

// Keep the deterministic detection stages together so their scoring and tree invariants remain local.
@Suppress("TooManyFunctions")
class ChapterStructureDetector(
    private val weights: ChapterScoringWeights = ChapterScoringWeights(),
) {
    // The detector intentionally keeps its ordered evidence stages together; splitting them would hide
    // the confidence and review-state handoff that must remain auditable.
    @Suppress("LongMethod")
    fun detect(
        layouts: List<PdfPageLayout>,
        relativePath: String,
        documentFingerprint: String,
        analysis: AnalysisProvenance?,
    ): ChapterDetectionResult =
        detect(
            source =
                PdfPageLayoutSource { action ->
                    layouts.sortedBy(PdfPageLayout::pageIndex).forEach(action)
                },
            relativePath = relativePath,
            documentFingerprint = documentFingerprint,
            analysis = analysis,
        )

    @Suppress("LongMethod")
    fun detect(
        source: PdfPageLayoutSource,
        relativePath: String,
        documentFingerprint: String,
        analysis: AnalysisProvenance?,
    ): ChapterDetectionResult {
        val evidence = collectEvidence(source)
        val repeatedEdgeText = repeatedEdgeText(evidence)
        val styleEvidence = styleEvidence(evidence)
        val tocEntries = evidence.tocEntries
        val initialCandidates =
            buildList {
                source.forEachPage { layout ->
                    layout.blocks
                        .sortedBy(PdfLayoutBlock::order)
                        .mapNotNull { block -> candidateFor(layout, block, repeatedEdgeText, styleEvidence) }
                        .forEach(::add)
                }
            }.sortedWith(
                compareBy<ChapterCandidate>(
                    { it.pageIndex },
                    { it.destination.topNormalized ?: 0f },
                    { it.blockId },
                ),
            )
        val mappings = reconcilePrintedPages(initialCandidates, tocEntries, evidence.printedPageObservations)
        val candidates =
            initialCandidates.map { candidate ->
                val match =
                    tocEntries
                        .asSequence()
                        .map { entry -> entry to titleSimilarity(candidate.title, entry.title) }
                        .filter { (_, similarity) -> similarity >= TOC_MATCH_THRESHOLD }
                        .maxByOrNull { (_, similarity) -> similarity }
                val printedPage = match?.first?.printedPage
                val printedPageMapping =
                    printedPage?.let { value ->
                        mappings.firstOrNull { mapping ->
                            mapping.printedPage == value && mapping.pdfPageIndex == candidate.pageIndex
                        }
                    }
                val mappingConfidence =
                    when {
                        match == null -> 1f
                        printedPageMapping == null -> 0f
                        else -> printedPageMapping.confidence
                    }
                val confidenceBoost = if (match != null) weights.tocMatch else 0f
                candidate.copy(
                    confidence = (candidate.confidence + confidenceBoost).coerceIn(0f, 1f),
                    level =
                        parseNumbering(candidate.title)?.level
                            ?: match?.first?.level
                            ?: candidate.level,
                    printedPage = printedPage,
                    matchedTocTitle = match?.first?.title,
                    reasons =
                        candidate.reasons +
                            listOfNotNull(
                                match?.let { "目录条目匹配" },
                                match?.let {
                                    if (mappingConfidence >= PAGE_MAPPING_AUTO_CONFIRM_THRESHOLD) {
                                        "印刷页码已对齐"
                                    } else {
                                        "印刷页码待审核"
                                    }
                                },
                            ),
                    signals =
                        candidate.signals +
                            mapOf(
                                "tocMatch" to (match?.second ?: 0f),
                                "printedPageMapping" to mappingConfidence,
                            ),
                )
            }
        val leveled = enforceLegalLevels(candidates)
        val nodes = buildTree(leveled)
        val outline =
            PdfOutlineDocument(
                relativePath = relativePath,
                documentFingerprint = documentFingerprint,
                analysis = analysis,
                nodes = nodes,
            )
        return ChapterDetectionResult(outline, leveled, tocEntries, mappings)
    }

    private fun collectEvidence(source: PdfPageLayoutSource): LayoutEvidence {
        val pageIndexes = mutableSetOf<Int>()
        val repeatedEdgeCounts = mutableMapOf<String, Int>()
        val bodyFontSizes = mutableListOf<Float>()
        val pagesByStyle = mutableMapOf<StyleKey, MutableSet<Int>>()
        val tocEntries = mutableListOf<PdfTocEntry>()
        val printedPageObservations = mutableListOf<PrintedPageObservation>()
        source.forEachPage { layout ->
            require(pageIndexes.add(layout.pageIndex)) {
                "Chapter detection requires at most one layout per page."
            }
            layout.blocks
                .filter { block -> block.bounds.top <= EDGE_BAND || block.bounds.bottom >= 1f - EDGE_BAND }
                .map(PdfLayoutBlock::text)
                .map(::normalizeTitle)
                .filter(String::isNotBlank)
                .distinct()
                .forEach { text -> repeatedEdgeCounts[text] = (repeatedEdgeCounts[text] ?: 0) + 1 }
            layout.blocks
                .filter { block -> block.style?.fontSize?.isFinite() == true }
                .forEach { block ->
                    val style = checkNotNull(block.style)
                    pagesByStyle.getOrPut(styleKey(block)) { mutableSetOf() } += layout.pageIndex
                    if (block.type == PdfLayoutBlockType.TEXT) {
                        style.fontSize?.let(bodyFontSizes::add)
                    }
                }
            tocEntries += tocEntriesForPage(layout)
            printedPageObservations += printedPageObservationsForPage(layout)
        }
        return LayoutEvidence(
            pageCount = pageIndexes.size,
            repeatedEdgeCounts = repeatedEdgeCounts,
            bodyFontSizes = bodyFontSizes,
            pagesByStyle = pagesByStyle.mapValues { (_, pages) -> pages.toSet() },
            tocEntries = tocEntries.distinctBy { "${normalizeTitle(it.title)}|${it.printedPage}" },
            printedPageObservations = printedPageObservations,
        )
    }

    private fun candidateFor(
        layout: PdfPageLayout,
        block: PdfLayoutBlock,
        repeatedEdgeText: Set<String>,
        styleEvidence: StyleEvidence,
    ): ChapterCandidate? {
        val rawTitle = normalizeTitle(block.text)
        val title = cleanHeadingTitle(rawTitle)
        return if (title.length !in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH || isPageNumber(title)) {
            null
        } else {
            val numbered = parseNumbering(title)
            val edge = block.bounds.top <= EDGE_BAND || block.bounds.bottom >= 1f - EDGE_BAND
            val repeated = title in repeatedEdgeText
            val explicitType =
                block.type == PdfLayoutBlockType.DOCUMENT_TITLE ||
                    block.type == PdfLayoutBlockType.SECTION_TITLE
            val signals = candidateSignals(block, title, numbered, edge, repeated, explicitType, styleEvidence)
            val confidence = sigmoid(candidateScore(block, signals) + signals.getValue("fontSize") * weights.fontSize)
            val shouldKeep = explicitType || numbered != null || confidence >= MIN_CANDIDATE_CONFIDENCE
            if (!shouldKeep || repeated) {
                null
            } else {
                ChapterCandidate(
                    pageIndex = layout.pageIndex,
                    blockId = block.id,
                    title = title,
                    level = numbered?.level ?: 1,
                    confidence = confidence,
                    destination = PdfOutlineDestination(layout.pageIndex, block.bounds.top),
                    bounds = block.bounds,
                    source = sourceFor(layout.engine.engineId),
                    reasons = candidateReasons(block, numbered, edge, signals.getValue("fontSize"), explicitType),
                    signals = signals,
                    requiresReview = block.requiresReview,
                )
            }
        }
    }

    private fun candidateSignals(
        block: PdfLayoutBlock,
        title: String,
        numbered: Numbering?,
        edge: Boolean,
        repeated: Boolean,
        explicitType: Boolean,
        styleEvidence: StyleEvidence,
    ): LinkedHashMap<String, Float> {
        val style = block.style
        val fontScore = style?.fontSize?.let { size -> (size / 18f).coerceIn(0f, 1f) } ?: 0f
        val styleKey = styleKey(block)
        val stylePages = styleEvidence.pagesByStyle[styleKey].orEmpty().size
        val styleConsistency =
            if (styleEvidence.pageCount <= 1) 0f else (stylePages.toFloat() / styleEvidence.pageCount).coerceIn(0f, 1f)
        val sizeContrast =
            if (style?.fontSize == null || styleEvidence.bodyFontSize <= 0f) {
                0f
            } else {
                ((style.fontSize / styleEvidence.bodyFontSize) - 1f).coerceIn(0f, 1f)
            }
        return linkedMapOf(
            "layoutLabel" to if (explicitType) 1f else 0f,
            "numbering" to if (numbered != null) 1f else 0f,
            "bold" to if (style?.bold == true) 1f else 0f,
            "fontSize" to fontScore,
            "shortText" to if (title.length <= 80) 1f else 0f,
            "edgePenalty" to if (edge) 1f else 0f,
            "repeatPenalty" to if (repeated) 1f else 0f,
            "styleConsistency" to styleConsistency,
            "sizeContrast" to sizeContrast,
        )
    }

    private fun candidateScore(
        block: PdfLayoutBlock,
        signals: Map<String, Float>,
    ): Float =
        weights.base +
            signals.getValue("layoutLabel") * weights.layoutLabel +
            signals.getValue("numbering") * weights.numbering +
            signals.getValue("bold") * weights.bold +
            signals.getValue("shortText") * weights.shortText -
            signals.getValue("edgePenalty") * weights.edgePenalty -
            signals.getValue("repeatPenalty") * weights.repeatPenalty +
            signals.getValue("styleConsistency") * weights.styleConsistency +
            signals.getValue("sizeContrast") * weights.sizeContrast +
            ((block.recognitionConfidence ?: 1f) - weights.ocrPenalty)

    private fun candidateReasons(
        block: PdfLayoutBlock,
        numbered: Numbering?,
        edge: Boolean,
        fontScore: Float,
        explicitType: Boolean,
    ): List<String> =
        buildList {
            if (explicitType) add("版面标题标签")
            if (numbered != null) add("可解析章节编号")
            if (block.style?.bold == true) add("粗体样式")
            if (fontScore >= 0.7f) add("字号显著")
            if (edge) add("靠近页边缘")
            if ((block.recognitionConfidence ?: 1f) < 0.8f) add("识别置信度偏低")
            if (block.requiresReview) add("VL 结果无法回对，待审核")
        }

    private fun tocEntriesForPage(layout: PdfPageLayout): List<PdfTocEntry> {
        val result = mutableListOf<PdfTocEntry>()
        val pageText = layout.blocks.joinToString("\n") { block -> block.text }
        val tocPage =
            layout.blocks.any { block -> block.type == PdfLayoutBlockType.TOC } ||
                pageText.contains("目录") ||
                pageText.contains("contents", ignoreCase = true)
        if (!tocPage) return result
        layout.blocks.forEach { block ->
            block.text.lineSequence().forEach { line ->
                parseTocLine(line)?.let { parsed ->
                    result +=
                        PdfTocEntry(
                            title = parsed.title,
                            printedPage = parsed.printedPage,
                            level = parsed.level,
                            sourcePageIndex = layout.pageIndex,
                            indentation = parsed.indentation,
                        )
                }
            }
        }
        return result
    }

    private fun reconcilePrintedPages(
        candidates: List<ChapterCandidate>,
        entries: List<PdfTocEntry>,
        observations: List<PrintedPageObservation>,
    ): List<PrintedPageMapping> =
        entries
            .mapNotNull { entry ->
                val match =
                    candidates
                        .asSequence()
                        .map { candidate -> candidate to titleSimilarity(candidate.title, entry.title) }
                        .filter { (_, similarity) -> similarity >= TOC_MATCH_THRESHOLD }
                        .minByOrNull { (candidate, similarity) ->
                            abs(candidate.pageIndex - entry.sourcePageIndex) * 0.01f - similarity
                        }
                match?.let { (candidate, similarity) ->
                    val evidence = pageEvidence(entry, candidate.pageIndex, observations)
                    PrintedPageMapping(
                        printedPage = entry.printedPage,
                        pdfPageIndex = candidate.pageIndex,
                        confidence = similarity * evidence.confidence,
                        source = evidence.source,
                    )
                }
                // A printed page number can legitimately occur in more than one numbering
                // segment. Keep one mapping per physical destination instead of collapsing
                // a reset (for example, both printed page "1" entries) into one result.
            }.groupBy { mapping -> "${printedPageKey(mapping.printedPage)}|${mapping.pdfPageIndex}" }
            .values
            .mapNotNull { mappings -> mappings.maxByOrNull(PrintedPageMapping::confidence) }

    @Suppress("ReturnCount")
    private fun pageEvidence(
        entry: PdfTocEntry,
        candidatePageIndex: Int,
        observations: List<PrintedPageObservation>,
    ): PrintedPageEvidence {
        val parsed = parsePrintedPage(entry.printedPage) ?: return PrintedPageEvidence.missing()
        val exact = observations.filter { observation -> observation.key == printedPageKey(entry.printedPage) }
        if (exact.isNotEmpty()) {
            if (exact.any { observation -> observation.pageIndex == candidatePageIndex }) {
                return PrintedPageEvidence(1f, "toc-and-page-number")
            }
        }
        val exactConflict = exact.isNotEmpty()

        val anchors =
            observations
                .filter { observation -> observation.kind == parsed.kind }
                .sortedBy { observation -> abs(observation.pageIndex - candidatePageIndex) }
                .take(PAGE_ANCHOR_LIMIT)
        if (anchors.isEmpty()) {
            return if (exactConflict) {
                PrintedPageEvidence(0f, "toc-page-conflict")
            } else {
                PrintedPageEvidence.missing()
            }
        }

        val nearby =
            anchors.filter { observation ->
                abs(observation.pageIndex - candidatePageIndex) <= PAGE_ANCHOR_WINDOW
            }
        val usable = nearby.ifEmpty { anchors }
        val nearestDistance = usable.minOf { observation -> abs(observation.pageIndex - candidatePageIndex) }
        val nearest =
            usable.filter { observation ->
                abs(observation.pageIndex - candidatePageIndex) == nearestDistance
            }
        val nearestMatching =
            nearest.filter { observation ->
                parsed.number + observation.pageIndex - observation.number == candidatePageIndex
            }
        if (nearestMatching.isNotEmpty()) {
            return PrintedPageEvidence(PAGE_MAPPING_OFFSET_EVIDENCE_FACTOR, "toc-and-page-offset")
        }
        val matching =
            usable.filter { observation ->
                parsed.number + observation.pageIndex - observation.number == candidatePageIndex
            }
        if (matching.isNotEmpty()) {
            val offsets = usable.map { observation -> observation.pageIndex - observation.number }.distinct()
            return if (offsets.size == 1) {
                PrintedPageEvidence(PAGE_MAPPING_OFFSET_EVIDENCE_FACTOR, "toc-and-page-offset")
            } else {
                PrintedPageEvidence(0f, "toc-page-conflict")
            }
        }

        return if (exactConflict ||
            usable.any { observation ->
                abs(observation.pageIndex - candidatePageIndex) <= PAGE_ANCHOR_WINDOW
            }
        ) {
            PrintedPageEvidence(0f, "toc-page-conflict")
        } else {
            PrintedPageEvidence.missing()
        }
    }

    private fun printedPageObservationsForPage(layout: PdfPageLayout): List<PrintedPageObservation> =
        buildList {
            layout.blocks.forEach { block ->
                if (block.type == PdfLayoutBlockType.PAGE_NUMBER ||
                    block.text.lineSequence().any { line -> isPageNumber(normalizeTitle(line)) }
                ) {
                    block.text
                        .lineSequence()
                        .mapNotNull { line ->
                            parsePrintedPage(line)?.let { parsed ->
                                PrintedPageObservation(
                                    key = printedPageKey(line),
                                    kind = parsed.kind,
                                    number = parsed.number,
                                    pageIndex = layout.pageIndex,
                                )
                            }
                        }.forEach(::add)
                }
            }
        }

    private data class PrintedPageEvidence(
        val confidence: Float,
        val source: String,
    ) {
        companion object {
            fun missing(): PrintedPageEvidence =
                PrintedPageEvidence(
                    PAGE_MAPPING_MISSING_EVIDENCE_FACTOR,
                    "toc-title-only",
                )
        }
    }

    private fun printedPageKey(value: String): String =
        parsePrintedPage(value)?.let { parsed -> "${parsed.kind.name}:${parsed.number}" }
            ?: normalizeTitle(value).lowercase()

    private fun enforceLegalLevels(candidates: List<ChapterCandidate>): List<ChapterCandidate> {
        var previous = 0
        return candidates.map { candidate ->
            val level = candidate.level.coerceIn(1, min(MAX_LEVEL, previous + 1))
            previous = level
            if (level == candidate.level) {
                candidate
            } else {
                candidate.copy(
                    level = level,
                    confidence = (candidate.confidence - ILLEGAL_LEVEL_PENALTY).coerceIn(0f, 1f),
                    reasons = candidate.reasons + "层级跳跃，已降为最浅合法层级",
                )
            }
        }
    }

    private fun buildTree(candidates: List<ChapterCandidate>): List<PdfOutlineNode> {
        val roots = mutableListOf<MutableNode>()
        val stack = mutableListOf<Pair<Int, MutableNode>>()
        candidates.forEachIndexed { index, candidate ->
            while (stack.lastOrNull()?.first?.let { level -> level >= candidate.level } == true) stack.removeLast()
            val node =
                MutableNode(
                    id = "chapter-${candidate.pageIndex}-$index-${candidate.title.hashCode().toString(16)}",
                    title = candidate.title,
                    destination = candidate.destination,
                    source = candidate.source,
                    confidence = candidate.confidence,
                    reviewState =
                        if (
                            !candidate.requiresReview &&
                            candidate.confidence >= AUTO_CONFIRM_THRESHOLD &&
                            candidate.signals.getOrDefault("printedPageMapping", 1f) >=
                            PAGE_MAPPING_AUTO_CONFIRM_THRESHOLD
                        ) {
                            PdfOutlineReviewState.CONFIRMED
                        } else {
                            PdfOutlineReviewState.CANDIDATE
                        },
                    evidence =
                        PdfOutlineEvidence(
                            pageIndex = candidate.pageIndex,
                            blockId = candidate.blockId,
                            bounds = candidate.bounds,
                            reasons = candidate.reasons,
                            printedPage = candidate.printedPage,
                            matchedTocTitle = candidate.matchedTocTitle,
                            signals = candidate.signals,
                        ),
                )
            val parent = stack.lastOrNull()?.second
            if (parent == null) roots += node else parent.children += node
            stack += candidate.level to node
        }
        return roots.map(MutableNode::toImmutable)
    }

    private fun repeatedEdgeText(evidence: LayoutEvidence): Set<String> {
        val threshold = max(2, (evidence.pageCount * 0.35f).toInt())
        return evidence.repeatedEdgeCounts.filterValues { count -> count >= threshold }.keys
    }

    private fun styleEvidence(evidence: LayoutEvidence): StyleEvidence {
        val bodySizes =
            evidence.bodyFontSizes.sorted()
        val bodyFontSize =
            bodySizes.takeIf(List<Float>::isNotEmpty)?.let { values -> values[(values.size - 1) / 2] } ?: 0f
        return StyleEvidence(
            pageCount = evidence.pageCount.coerceAtLeast(1),
            bodyFontSize = bodyFontSize,
            pagesByStyle = evidence.pagesByStyle,
        )
    }

    private fun styleKey(block: PdfLayoutBlock): StyleKey {
        val style = block.style
        return StyleKey(
            fontBucket = style?.fontSize?.div(STYLE_FONT_BUCKET_SIZE)?.roundToInt() ?: 0,
            bold = style?.bold == true,
            italic = style?.italic == true,
            centered = style?.centered == true,
            vertical = style?.vertical == true,
        )
    }

    private data class StyleKey(
        val fontBucket: Int,
        val bold: Boolean,
        val italic: Boolean,
        val centered: Boolean,
        val vertical: Boolean,
    )

    private data class StyleEvidence(
        val pageCount: Int,
        val bodyFontSize: Float,
        val pagesByStyle: Map<StyleKey, Set<Int>>,
    )

    private data class LayoutEvidence(
        val pageCount: Int,
        val repeatedEdgeCounts: Map<String, Int>,
        val bodyFontSizes: List<Float>,
        val pagesByStyle: Map<StyleKey, Set<Int>>,
        val tocEntries: List<PdfTocEntry>,
        val printedPageObservations: List<PrintedPageObservation>,
    )

    private fun sourceFor(engineId: String): PdfOutlineSource =
        when {
            engineId.contains("vl", ignoreCase = true) -> PdfOutlineSource.VL

            engineId.contains("ocr", ignoreCase = true) ||
                engineId.contains("structure", ignoreCase = true) -> PdfOutlineSource.OCR

            else -> PdfOutlineSource.NATIVE_TEXT
        }

    private data class MutableNode(
        val id: String,
        val title: String,
        val destination: PdfOutlineDestination,
        val source: PdfOutlineSource,
        val confidence: Float,
        val reviewState: PdfOutlineReviewState,
        val evidence: PdfOutlineEvidence,
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        fun toImmutable(): PdfOutlineNode =
            PdfOutlineNode(
                id,
                title,
                destination,
                source,
                confidence,
                reviewState,
                evidence,
                children.map(MutableNode::toImmutable),
            )
    }

    private companion object {
        const val MIN_TITLE_LENGTH = 2
        const val MAX_TITLE_LENGTH = 180
        const val EDGE_BAND = 0.12f
        const val MIN_CANDIDATE_CONFIDENCE = 0.68f
        const val AUTO_CONFIRM_THRESHOLD = 0.86f
        const val TOC_MATCH_THRESHOLD = 0.72f
        const val PAGE_MAPPING_AUTO_CONFIRM_THRESHOLD = 0.9f
        const val PAGE_MAPPING_MISSING_EVIDENCE_FACTOR = 0.75f
        const val PAGE_MAPPING_OFFSET_EVIDENCE_FACTOR = 0.95f
        const val PAGE_ANCHOR_LIMIT = 64
        const val PAGE_ANCHOR_WINDOW = 24
        const val ILLEGAL_LEVEL_PENALTY = 0.12f
        const val MAX_LEVEL = 6
        const val TOC_INDENTATION_PER_LEVEL = 2
        const val STYLE_FONT_BUCKET_SIZE = 1.5f
        val ADVENTURE_MARKER =
            Regex(
                "^[a-z]{1,3}venture\\s+[ivxlcdmty&0-9]{1,4}(?:\\s+[ivxlcdmty&0-9]{2,4})?\\s*",
                RegexOption.IGNORE_CASE,
            )

        fun normalizeTitle(text: String): String =
            text
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.', '。', ':', '：', '、', '-', '_')

        fun cleanHeadingTitle(text: String): String {
            val cleaned = ADVENTURE_MARKER.replace(text, "").trim()
            return if (cleaned.startsWith("HE ") && cleaned == cleaned.uppercase()) {
                "T$cleaned"
            } else {
                cleaned
            }
        }

        fun isPageNumber(text: String): Boolean = parsePrintedPage(text) != null

        @Suppress("ReturnCount")
        fun parsePrintedPage(value: String): ParsedPrintedPage? {
            val token = normalizeTitle(value).replace(Regex("(?i)^page\\s*"), "").trim()
            token.toIntOrNull()?.takeIf { number -> number in 0..9999 }?.let { number ->
                return ParsedPrintedPage(PrintedPageKind.ARABIC, number)
            }
            romanToInt(token)?.let { number ->
                return ParsedPrintedPage(PrintedPageKind.ROMAN, number)
            }
            return null
        }

        private fun romanToInt(value: String): Int? {
            if (value.isBlank() || !value.matches(Regex("(?i)^[ivxlcdm]+$"))) return null
            val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
            var total = 0
            var previous = 0
            value.uppercase().reversed().forEach { symbol ->
                val current = values.getValue(symbol)
                if (current < previous) total -= current else total += current
                previous = current
            }
            return total.takeIf { number -> number in 1..3999 }
        }

        fun parseNumbering(title: String): Numbering? {
            val cjk = Regex("^第\\s*([0-9一二三四五六七八九十百千万零〇]+)\\s*(编|部|卷|章|节)").find(title)
            val dotted = Regex("^(\\d+(?:\\.\\d+){0,5})(?:[\\s:：.-]|$)").find(title)
            val english = Regex("(?i)^chapter\\s+(\\d+)").find(title)
            return cjk?.let { match ->
                Numbering(if (match.groupValues[2] == "节") 2 else 1)
            } ?: dotted?.let { match ->
                Numbering(match.groupValues[1].count { char -> char == '.' } + 1)
            } ?: english?.let { Numbering(1) }
        }

        fun parseTocLine(line: String): ParsedTocLine? {
            val indentation = line.takeWhile { character -> character == ' ' || character == '\t' }.length
            val clean = line.trim()
            return clean
                .takeIf { it.length >= 4 }
                ?.let {
                    Regex(
                        "^(.+?)(?:\\.{2,}|\\s{2,}|\\u2026+)\\s*([ivxlcdm]+|\\d{1,4})$",
                        RegexOption.IGNORE_CASE,
                    ).find(it)
                }?.let { match ->
                    val title = normalizeTitle(match.groupValues[1])
                    title.takeIf(String::isNotBlank)?.let {
                        ParsedTocLine(
                            title = title,
                            printedPage = match.groupValues[2],
                            level =
                                parseNumbering(title)?.level
                                    ?: (1 + indentation / TOC_INDENTATION_PER_LEVEL).coerceAtMost(MAX_LEVEL),
                            indentation = indentation,
                        )
                    }
                }
        }

        fun titleSimilarity(
            left: String,
            right: String,
        ): Float {
            val a = normalizeTitle(left).lowercase()
            val b = normalizeTitle(right).lowercase()
            return when {
                a == b -> {
                    1f
                }

                a.isBlank() || b.isBlank() -> {
                    0f
                }

                else -> {
                    val common =
                        a
                            .toSet()
                            .intersect(b.toSet())
                            .size
                            .toFloat()
                    val length = max(a.toSet().size, b.toSet().size).coerceAtLeast(1)
                    val containment = if (a.contains(b) || b.contains(a)) 0.35f else 0f
                    ((common / length) * 0.65f + containment).coerceIn(0f, 1f)
                }
            }
        }

        fun sigmoid(value: Float): Float = (1f / (1f + kotlin.math.exp(-value))).coerceIn(0f, 1f)
    }
}

data class ChapterScoringWeights(
    val base: Float = -1.25f,
    val layoutLabel: Float = 2.3f,
    val numbering: Float = 1.35f,
    val bold: Float = 0.55f,
    val fontSize: Float = 0.9f,
    val shortText: Float = 0.25f,
    val edgePenalty: Float = 0.55f,
    val repeatPenalty: Float = 1.8f,
    val ocrPenalty: Float = 0.15f,
    val tocMatch: Float = 0.35f,
    val styleConsistency: Float = 0.35f,
    val sizeContrast: Float = 0.45f,
)

private data class Numbering(
    val level: Int,
)
