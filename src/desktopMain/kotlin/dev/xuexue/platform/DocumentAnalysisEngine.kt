package dev.xuexue.platform

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
enum class PdfLayoutBlockType {
    DOCUMENT_TITLE,
    SECTION_TITLE,
    TEXT,
    TOC,
    PAGE_NUMBER,
    HEADER,
    FOOTER,
    LIST,
    FIGURE,
    TABLE,
    UNKNOWN,
}

@Serializable
enum class PdfPageClassification {
    UNKNOWN,
    EMPTY,
    NATIVE_TEXT,
    SCANNED,
    MIXED,
    UNTRUSTED_TEXT,
}

@Serializable
data class PdfPageClassificationEvidence(
    val nativeCharacterCount: Int = 0,
    val nativeBlockCount: Int = 0,
    val imageBlockCount: Int = 0,
    val nativeCoverage: Float = 0f,
    val hiddenCharacterRatio: Float = 0f,
    val renderedInkCoverage: Float? = null,
    val signals: List<String> = emptyList(),
) {
    init {
        require(nativeCharacterCount >= 0 && nativeBlockCount >= 0 && imageBlockCount >= 0) {
            "PDF page classification counts must not be negative."
        }
        listOf(nativeCoverage, hiddenCharacterRatio, renderedInkCoverage)
            .filterNotNull()
            .forEach { value ->
                require(value in 0f..1f) { "PDF page classification ratios must be normalized." }
            }
    }
}

@Serializable
data class PdfTextStyle(
    val fontSize: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val centered: Boolean = false,
    val vertical: Boolean = false,
)

@Serializable
data class AnalysisProvenance(
    val engineId: String,
    val engineVersion: String,
    val modelVersion: String? = null,
    val language: String? = null,
    val dpi: Int? = null,
    val optionsFingerprint: String? = null,
)

@Serializable
data class PdfPageLayout(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val blocks: List<PdfLayoutBlock>,
    val engine: AnalysisProvenance,
    val classification: PdfPageClassification = PdfPageClassification.UNKNOWN,
    val classificationEvidence: PdfPageClassificationEvidence? = null,
) {
    init {
        require(pageIndex >= 0) { "PDF page index must not be negative." }
        require(width > 0 && height > 0) { "PDF layout dimensions must be positive." }
    }
}

@Serializable
data class PdfLayoutBlock(
    val id: String,
    val order: Int,
    val type: PdfLayoutBlockType,
    val text: String,
    val bounds: PdfNormalizedRect,
    val detectionConfidence: Float? = null,
    val recognitionConfidence: Float? = null,
    val style: PdfTextStyle? = null,
    val requiresReview: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "PDF layout block IDs must not be blank." }
        require(order >= 0) { "PDF layout block order must not be negative." }
        listOf(detectionConfidence, recognitionConfidence).filterNotNull().forEach { confidence ->
            require(confidence in 0f..1f) { "PDF layout confidence must be between zero and one." }
        }
    }
}

@Serializable
data class PdfStructuredTextPage(
    val widthPoints: Float,
    val heightPoints: Float,
    val blocks: List<PdfStructuredTextBlock>,
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    init {
        require(widthPoints > 0f && heightPoints > 0f) { "Structured-text page dimensions must be positive." }
        require(originX.isFinite() && originY.isFinite()) { "Structured-text page origin must be finite." }
    }
}

@Serializable
data class PdfStructuredTextBlock(
    val type: Int,
    val bounds: PdfAbsoluteRect,
    val lines: List<PdfStructuredTextLine> = emptyList(),
)

@Serializable
data class PdfStructuredTextLine(
    val bounds: PdfAbsoluteRect,
    val vertical: Boolean,
    val chars: List<PdfStructuredTextChar>,
)

@Serializable
data class PdfStructuredTextChar(
    val codePoint: Int,
    val bounds: PdfAbsoluteRect,
    val size: Float,
    val flags: Int,
    val argb: Int,
)

@Serializable
data class PdfAbsoluteRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "PDF text coordinates must be finite."
        }
        require(left <= right && top <= bottom) { "PDF text rectangle must be ordered." }
    }
}

data class DocumentAnalysisRequest(
    val path: Path,
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val dpi: Int = 300,
    val language: String = "chi_sim,en",
    val imagePng: ByteArray? = null,
    val nativeText: PdfStructuredTextPage? = null,
)

data class DocumentAnalysisEngineDescriptor(
    val engineId: String,
    val displayName: String,
    val engineVersion: String,
    val modelVersion: String? = null,
    val languages: Set<String> = emptySet(),
    val supportsLayout: Boolean = false,
    val supportsVerticalText: Boolean = false,
    val supportsTables: Boolean = false,
    val supportsFormulas: Boolean = false,
    val supportsHandwriting: Boolean = false,
    val backends: Set<AnalysisComputeBackend> = setOf(AnalysisComputeBackend.CPU),
    val privacy: AnalysisPrivacy = AnalysisPrivacy.LOCAL,
    val license: String = "",
    val downloadBytes: Long? = null,
    val estimatedMemoryBytes: Long? = null,
)

enum class AnalysisComputeBackend {
    CPU,
    GPU,
    REMOTE,
}

enum class AnalysisPrivacy {
    LOCAL,
    LOOPBACK,
    REMOTE,
}

interface DocumentAnalysisEngine {
    val descriptor: DocumentAnalysisEngineDescriptor

    suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout
}

class NativePdfLayoutEngine : DocumentAnalysisEngine {
    override val descriptor =
        DocumentAnalysisEngineDescriptor(
            engineId = ENGINE_ID,
            displayName = "PDF 原生文字层",
            engineVersion = "mupdf-structured-text",
            languages = emptySet(),
            supportsLayout = true,
            supportsVerticalText = true,
            backends = setOf(AnalysisComputeBackend.CPU),
            privacy = AnalysisPrivacy.LOCAL,
            license = "MuPDF AGPL/commercial terms",
        )

    // Native extraction combines text ordering, style evidence, and page classification in one protocol pass.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout {
        val nativeText = request.nativeText ?: error("Native PDF layout requires structured text input.")
        val textBlocks = nativeText.blocks.filter { block -> block.type == TEXT_BLOCK_TYPE }
        val characters = textBlocks.flatMap(PdfStructuredTextBlock::lines).flatMap(PdfStructuredTextLine::chars)
        val nativeCharacterCount = characters.count { character -> !Character.isWhitespace(character.codePoint) }
        val hiddenCharacterCount =
            characters.count { character ->
                !Character.isWhitespace(character.codePoint) && (character.argb ushr 24) == 0
            }
        val imageBlockCount = nativeText.blocks.count { block -> block.type != TEXT_BLOCK_TYPE }
        val pageArea = nativeText.widthPoints * nativeText.heightPoints
        val nativeCoverage =
            if (pageArea <= 0f) {
                0f
            } else {
                textBlocks
                    .sumOf { block ->
                        ((block.bounds.right - block.bounds.left) * (block.bounds.bottom - block.bounds.top)).toDouble()
                    }.toFloat()
                    .div(pageArea)
                    .coerceIn(0f, 1f)
            }
        val hiddenCharacterRatio =
            if (nativeCharacterCount == 0) 0f else hiddenCharacterCount.toFloat() / nativeCharacterCount
        val blocks =
            nativeText.blocks.mapIndexedNotNull { index, block ->
                if (block.lines.isEmpty() && block.type != TEXT_BLOCK_TYPE) return@mapIndexedNotNull null
                val text =
                    block.lines
                        .sortedWith(compareBy<PdfStructuredTextLine>({ it.bounds.top }, { it.bounds.left }))
                        .joinToString("\n") { line ->
                            line.chars.joinToString("") { char -> String(Character.toChars(char.codePoint)) }
                        }.trim()
                if (text.isBlank()) return@mapIndexedNotNull null
                val chars = block.lines.flatMap(PdfStructuredTextLine::chars)
                val size =
                    chars
                        .map(PdfStructuredTextChar::size)
                        .filter { value -> value > 0f }
                        .average()
                        .toFloat()
                val bold = chars.any { char -> char.flags and FZ_STEXT_BOLD != 0 }
                val vertical = block.lines.any(PdfStructuredTextLine::vertical)
                val centered =
                    block.lines.any { line ->
                        val center = (line.bounds.left + line.bounds.right) / 2f
                        kotlin.math.abs(center - nativeText.widthPoints / 2f) <= nativeText.widthPoints * 0.1f
                    }
                PdfLayoutBlock(
                    id = "native-${request.pageIndex}-$index",
                    order = index,
                    type = PdfLayoutBlockType.TEXT,
                    text = text,
                    bounds =
                        block.bounds.toNormalized(
                            width = nativeText.widthPoints,
                            height = nativeText.heightPoints,
                            originX = nativeText.originX,
                            originY = nativeText.originY,
                        ),
                    recognitionConfidence = 1f,
                    style =
                        PdfTextStyle(
                            fontSize = size.takeIf { value -> value.isFinite() && value > 0f },
                            bold = bold,
                            centered = centered,
                            vertical = vertical,
                        ),
                )
            }
        val classification =
            when {
                nativeCharacterCount == 0 && imageBlockCount > 0 -> {
                    PdfPageClassification.SCANNED
                }

                nativeCharacterCount == 0 -> {
                    PdfPageClassification.EMPTY
                }

                nativeCharacterCount < MIN_NATIVE_TEXT_CHARS || hiddenCharacterRatio >= HIDDEN_TEXT_RATIO_LIMIT -> {
                    PdfPageClassification.UNTRUSTED_TEXT
                }

                imageBlockCount > 0 -> {
                    PdfPageClassification.MIXED
                }

                else -> {
                    PdfPageClassification.NATIVE_TEXT
                }
            }
        return PdfPageLayout(
            pageIndex = request.pageIndex,
            width = request.width,
            height = request.height,
            blocks = blocks,
            engine =
                AnalysisProvenance(
                    engineId = descriptor.engineId,
                    engineVersion = descriptor.engineVersion,
                    language = request.language,
                ),
            classification = classification,
            classificationEvidence =
                PdfPageClassificationEvidence(
                    nativeCharacterCount = nativeCharacterCount,
                    nativeBlockCount = textBlocks.count { block -> block.lines.isNotEmpty() },
                    imageBlockCount = imageBlockCount,
                    nativeCoverage = nativeCoverage,
                    hiddenCharacterRatio = hiddenCharacterRatio,
                    signals =
                        buildList {
                            if (imageBlockCount > 0) add("结构化文本包含非文字块")
                            if (hiddenCharacterRatio >= HIDDEN_TEXT_RATIO_LIMIT) add("文字层可能不可见")
                            if (nativeCharacterCount < MIN_NATIVE_TEXT_CHARS) add("文字层字符不足")
                        },
                ),
        )
    }

    private companion object {
        const val ENGINE_ID = "native-pdf-layout"
        const val TEXT_BLOCK_TYPE = 0
        const val MIN_NATIVE_TEXT_CHARS = 24
        const val HIDDEN_TEXT_RATIO_LIMIT = 0.5f
        const val FZ_STEXT_BOLD = 8
    }
}

private fun PdfAbsoluteRect.toNormalized(
    width: Float,
    height: Float,
    originX: Float = 0f,
    originY: Float = 0f,
): PdfNormalizedRect {
    val left = ((left - originX) / width).coerceIn(0f, 1f - MIN_NORMALIZED_SIZE)
    val top = ((top - originY) / height).coerceIn(0f, 1f - MIN_NORMALIZED_SIZE)
    val right = ((right - originX) / width).coerceIn(left + MIN_NORMALIZED_SIZE, 1f)
    val bottom = ((bottom - originY) / height).coerceIn(top + MIN_NORMALIZED_SIZE, 1f)
    return PdfNormalizedRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )
}

private const val MIN_NORMALIZED_SIZE = 0.0001f

@Serializable
enum class PdfOutlineSource {
    NATIVE,
    NATIVE_TEXT,
    OCR,
    VL,
    MANUAL,
}

@Serializable
enum class PdfOutlineReviewState {
    CANDIDATE,
    CONFIRMED,
    REJECTED,
    MANUAL,
}

@Serializable
data class PdfOutlineDestination(
    val pageIndex: Int,
    val topNormalized: Float? = null,
) {
    init {
        require(pageIndex >= 0) { "PDF outline page index must not be negative." }
        require(topNormalized == null || topNormalized in 0f..1f) {
            "PDF outline vertical position must be normalized."
        }
    }
}

@Serializable
data class PdfOutlineEvidence(
    val pageIndex: Int,
    val blockId: String? = null,
    val bounds: PdfNormalizedRect? = null,
    val reasons: List<String> = emptyList(),
    val printedPage: String? = null,
    val matchedTocTitle: String? = null,
    val signals: Map<String, Float> = emptyMap(),
)

@Serializable
data class PdfOutlineNode(
    val id: String,
    val title: String,
    val destination: PdfOutlineDestination,
    val source: PdfOutlineSource,
    val confidence: Float? = null,
    val reviewState: PdfOutlineReviewState,
    val evidence: PdfOutlineEvidence? = null,
    val children: List<PdfOutlineNode> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "PDF outline node IDs must not be blank." }
        require(title.isNotBlank()) { "PDF outline titles must not be blank." }
        require(confidence == null || confidence in 0f..1f) { "PDF outline confidence must be normalized." }
    }
}

@Serializable
data class PdfOutlineDocument(
    val schemaVersion: Int = CURRENT_PDF_OUTLINE_SCHEMA,
    val relativePath: String,
    val documentFingerprint: String,
    val analysis: AnalysisProvenance? = null,
    val nodes: List<PdfOutlineNode> = emptyList(),
) {
    init {
        require(schemaVersion > 0) { "PDF outline schema version must be positive." }
        require(relativePath.isNotBlank()) { "PDF outline paths must not be blank." }
        require(documentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "PDF outline fingerprints must be SHA-256 hex strings."
        }
    }
}

const val CURRENT_PDF_OUTLINE_SCHEMA = 1

data class PdfNativeOutlineNode(
    val title: String,
    val pageIndex: Int,
    val topNormalized: Float?,
    val uri: String?,
    val flags: Int = 0,
    val children: List<PdfNativeOutlineNode> = emptyList(),
)

class DocumentAnalysisEngineRegistry private constructor(
    private val engines: Map<String, DocumentAnalysisEngine>,
) {
    val descriptors get() = engines.values.map(DocumentAnalysisEngine::descriptor)

    fun engineOrNull(id: String): DocumentAnalysisEngine? = engines[id]

    companion object {
        fun default(): DocumentAnalysisEngineRegistry {
            val sidecarConfig = PaddleOcrSidecarConfig.fromEnvironment()
            val sidecarEndpoint = sidecarConfig.endpoint
            return DocumentAnalysisEngineRegistry(
                listOf(
                    NativePdfLayoutEngine(),
                    PpStructureV3Engine(
                        endpoint = sidecarEndpoint.resolve("/analyze"),
                        engineVersion = sidecarConfig.version,
                    ),
                    PaddleOcrVlEngine(
                        endpoint = sidecarEndpoint.resolve("/analyze-vl"),
                        engineVersion = sidecarConfig.version,
                    ),
                ).associateBy { engine -> engine.descriptor.engineId },
            )
        }
    }
}
