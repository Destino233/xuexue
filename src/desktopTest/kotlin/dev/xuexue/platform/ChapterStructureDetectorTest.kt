package dev.xuexue.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Keep the fixture builders beside the assertions so page-evidence cases stay readable.
@Suppress("TooManyFunctions")
class ChapterStructureDetectorTest {
    @Test
    fun numberedHeadingsBuildLegalHierarchyAndSuppressRepeatedHeaders() {
        val layouts =
            (0..3).map { page ->
                PdfPageLayout(
                    pageIndex = page,
                    width = 612,
                    height = 792,
                    engine = AnalysisProvenance("native-pdf-layout", "test"),
                    blocks =
                        listOf(
                            block("header", "XueXue textbook", 0.04f, 0.08f, 10f),
                            block(
                                "title-$page",
                                if (page == 0) "1 Introduction" else "1.$page Topic",
                                0.18f,
                                0.25f,
                                22f,
                                bold = true,
                            ),
                            block(
                                "body-$page",
                                "ordinary body text that should not become a chapter",
                                0.3f,
                                0.85f,
                                10f,
                            ),
                        ),
                )
            }

        val result = ChapterStructureDetector().detect(layouts, "book.pdf", "0".repeat(64), null)

        assertEquals(4, result.candidates.size)
        assertEquals(1, result.outline.nodes.size)
        assertEquals(
            3,
            result.outline.nodes
                .single()
                .children.size,
        )
        assertTrue(
            result.outline.nodes
                .flatMap { it.children }
                .all { node -> node.destination.pageIndex >= 1 },
        )
    }

    @Test
    fun lowConfidenceCandidatesRemainCandidates() {
        val layout =
            PdfPageLayout(
                pageIndex = 0,
                width = 612,
                height = 792,
                engine = AnalysisProvenance("pp-structure-v3", "test"),
                blocks =
                    listOf(
                        PdfLayoutBlock(
                            id = "candidate",
                            order = 0,
                            type = PdfLayoutBlockType.SECTION_TITLE,
                            text = "复杂版面标题",
                            bounds = PdfNormalizedRect(0.2f, 0.2f, 0.8f, 0.24f),
                            recognitionConfidence = 0.4f,
                        ),
                    ),
            )

        val result = ChapterStructureDetector().detect(listOf(layout), "scan.pdf", "1".repeat(64), null)

        assertEquals(
            PdfOutlineReviewState.CANDIDATE,
            result.outline.nodes
                .single()
                .reviewState,
        )
    }

    @Test
    fun unmatchedVlCandidatesRemainCandidatesEvenWithHighConfidence() {
        val layout =
            PdfPageLayout(
                pageIndex = 0,
                width = 612,
                height = 792,
                engine = AnalysisProvenance("paddleocr-vl", "test"),
                blocks =
                    listOf(
                        PdfLayoutBlock(
                            id = "unmatched-vl",
                            order = 0,
                            type = PdfLayoutBlockType.SECTION_TITLE,
                            text = "VL 提议标题",
                            bounds = PdfNormalizedRect(0.2f, 0.2f, 0.8f, 0.24f),
                            recognitionConfidence = 1f,
                            requiresReview = true,
                        ),
                    ),
            )

        val result = ChapterStructureDetector().detect(listOf(layout), "scan.pdf", "1".repeat(64), null)

        assertEquals(
            PdfOutlineReviewState.CANDIDATE,
            result.outline.nodes
                .single()
                .reviewState,
        )
        assertTrue(
            result.outline.nodes
                .single()
                .evidence
                ?.reasons
                .orEmpty()
                .contains("VL 结果无法回对，待审核"),
        )
    }

    @Test
    fun scannedAdventurePrefixIsRemovedAndKeepsChapterAtRootLevel() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        PdfPageLayout(
                            pageIndex = 0,
                            width = 612,
                            height = 792,
                            engine = AnalysisProvenance("pp-structure-v3", "test"),
                            blocks =
                                listOf(
                                    block(
                                        "marker",
                                        "Eloventure 111A CASE OF IDENTITY",
                                        0.18f,
                                        0.3f,
                                        22f,
                                        type = PdfLayoutBlockType.SECTION_TITLE,
                                    ),
                                ),
                        ),
                    ),
                relativePath = "scan.pdf",
                documentFingerprint = "1".repeat(64),
                analysis = null,
            )

        assertEquals(
            "A CASE OF IDENTITY",
            result.outline.nodes
                .single()
                .title,
        )
        assertEquals(1, result.candidates.single().level)
    }

    @Test
    fun uppercaseDroppedTAtHeadingStartIsRepaired() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        PdfPageLayout(
                            pageIndex = 0,
                            width = 612,
                            height = 792,
                            engine = AnalysisProvenance("pp-structure-v3", "test"),
                            blocks =
                                listOf(
                                    block(
                                        "heading",
                                        "HE RED-HEADED LEAGUE",
                                        0.18f,
                                        0.3f,
                                        22f,
                                        type = PdfLayoutBlockType.SECTION_TITLE,
                                    ),
                                ),
                        ),
                    ),
                relativePath = "scan.pdf",
                documentFingerprint = "1".repeat(64),
                analysis = null,
            )

        assertEquals(
            "THE RED-HEADED LEAGUE",
            result.outline.nodes
                .single()
                .title,
        )
    }

    @Test
    fun unnumberedSectionTitlesStayAtRootWithoutHierarchyEvidence() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        headingLayout(1, "First Chapter"),
                        headingLayout(2, "Second Chapter"),
                    ),
                relativePath = "scan.pdf",
                documentFingerprint = "1".repeat(64),
                analysis = null,
            )

        assertEquals(2, result.outline.nodes.size)
        assertTrue(result.outline.nodes.all { node -> node.children.isEmpty() })
    }

    @Test
    fun tableOfContentsReconcilesPrintedPagesWithPhysicalPages() {
        val toc =
            PdfPageLayout(
                pageIndex = 0,
                width = 612,
                height = 792,
                engine = AnalysisProvenance("native-pdf-layout", "test"),
                blocks =
                    listOf(
                        block(
                            "toc",
                            "目录\n1 Introduction ........ 1\n1.1 Topic ........ 3",
                            0.12f,
                            0.5f,
                            12f,
                            type = PdfLayoutBlockType.TOC,
                        ),
                    ),
            )
        val layouts =
            listOf(
                toc,
                headingLayout(1, "1 Introduction"),
                headingLayout(2, "1.1 Topic"),
            )

        val result = ChapterStructureDetector().detect(layouts, "book.pdf", "0".repeat(64), null)

        assertEquals(listOf("1", "3"), result.tocEntries.map(PdfTocEntry::printedPage))
        assertEquals(listOf(1, 2), result.printedPageMappings.map(PrintedPageMapping::pdfPageIndex))
        assertEquals(listOf("1", "3"), result.candidates.map(ChapterCandidate::printedPage).distinct())
    }

    @Test
    fun matchingPrintedPageEvidenceAutoConfirmsTocCandidates() {
        val toc = tocLayout("1 Introduction ........ 1")
        val layouts =
            listOf(
                toc,
                headingWithPrintedPage(1, "1 Introduction", "1"),
            )

        val result = ChapterStructureDetector().detect(layouts, "book.pdf", "0".repeat(64), null)

        assertEquals(
            PdfOutlineReviewState.CONFIRMED,
            result.outline.nodes
                .single()
                .reviewState,
        )
        assertEquals("toc-and-page-number", result.printedPageMappings.single().source)
    }

    @Test
    fun missingPrintedPageEvidenceKeepsTocCandidateForReview() {
        val layouts =
            listOf(
                tocLayout("1 Introduction ........ 1"),
                headingLayout(1, "1 Introduction"),
            )

        val result = ChapterStructureDetector().detect(layouts, "book.pdf", "0".repeat(64), null)

        assertEquals(
            PdfOutlineReviewState.CANDIDATE,
            result.outline.nodes
                .single()
                .reviewState,
        )
        assertEquals("toc-title-only", result.printedPageMappings.single().source)
    }

    @Test
    fun conflictingPrintedPageEvidenceKeepsTocCandidateForReview() {
        val layouts =
            listOf(
                tocLayout("1 Introduction ........ 1"),
                headingLayout(1, "1 Introduction"),
                pageNumberLayout(2, "1"),
            )

        val result = ChapterStructureDetector().detect(layouts, "book.pdf", "0".repeat(64), null)

        assertEquals(
            PdfOutlineReviewState.CANDIDATE,
            result.outline.nodes
                .single()
                .reviewState,
        )
        assertEquals("toc-page-conflict", result.printedPageMappings.single().source)
        assertEquals(0f, result.printedPageMappings.single().confidence)
    }

    @Test
    fun nearbyPageNumberAnchorsResolveAnArabicOffset() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        tocLayout("1 Introduction ........ 1"),
                        headingLayout(1, "1 Introduction"),
                        pageNumberLayout(2, "2"),
                    ),
                relativePath = "book.pdf",
                documentFingerprint = "0".repeat(64),
                analysis = null,
            )

        assertEquals("toc-and-page-offset", result.printedPageMappings.single().source)
        assertEquals(
            PdfOutlineReviewState.CONFIRMED,
            result.outline.nodes
                .single()
                .reviewState,
        )
    }

    @Test
    fun romanPageNumbersUseTheirOwnPageNumberSystem() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        tocLayout("Preface ........ iii"),
                        pageNumberLayout(1, "ii"),
                        headingLayout(2, "Preface"),
                    ),
                relativePath = "book.pdf",
                documentFingerprint = "0".repeat(64),
                analysis = null,
            )

        assertEquals("toc-and-page-offset", result.printedPageMappings.single().source)
        assertEquals(2, result.printedPageMappings.single().pdfPageIndex)
    }

    @Test
    fun pageNumberResetPrefersTheNearestSectionAnchor() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        tocLayout("1 Introduction ........ 1\n2 Methods ........ 1"),
                        headingWithPrintedPage(1, "1 Introduction", "1"),
                        headingLayout(5, "2 Methods"),
                        pageNumberLayout(6, "2"),
                    ),
                relativePath = "book.pdf",
                documentFingerprint = "0".repeat(64),
                analysis = null,
            )

        assertEquals(
            listOf(1, 5),
            result.printedPageMappings.map(PrintedPageMapping::pdfPageIndex),
        )
        assertTrue(result.printedPageMappings.all { mapping -> mapping.confidence >= 0.9f })
    }

    @Test
    fun tocIndentationBuildsHierarchyForUnnumberedHeadings() {
        val result =
            ChapterStructureDetector().detect(
                layouts =
                    listOf(
                        tocLayout("Root ........ 1\n  Child ........ 2"),
                        headingLayout(1, "Root"),
                        headingLayout(2, "Child"),
                    ),
                relativePath = "book.pdf",
                documentFingerprint = "0".repeat(64),
                analysis = null,
            )

        assertEquals(listOf(1, 2), result.tocEntries.map(PdfTocEntry::level))
        assertEquals(1, result.outline.nodes.size)
        assertEquals(
            "Child",
            result.outline.nodes
                .single()
                .children
                .single()
                .title,
        )
    }

    @Test
    fun headingEvidenceIncludesDocumentStyleSupport() {
        val layouts =
            (0..2).map { page ->
                PdfPageLayout(
                    pageIndex = page,
                    width = 612,
                    height = 792,
                    engine = AnalysisProvenance("native-pdf-layout", "test"),
                    blocks =
                        listOf(
                            block("heading-$page", "Heading $page", 0.2f, 0.25f, 22f, bold = true),
                            block("body-$page", "body text", 0.35f, 0.5f, 10f),
                        ),
                )
            }

        val candidate =
            ChapterStructureDetector()
                .detect(layouts, "book.pdf", "0".repeat(64), null)
                .candidates
                .first()

        assertTrue(candidate.signals.getValue("styleConsistency") > 0.5f)
        assertTrue(candidate.signals.getValue("sizeContrast") > 0.5f)
    }

    @Test
    fun evaluationMetricsSeparateTitlePageAndHierarchyAccuracy() {
        val expected = evaluationOutline("Introduction", 1, "Topic", 3)
        val actual = evaluationOutline("Introduction", 1, "Topic", 4)

        val metrics = ChapterEvaluator.evaluate(expected, actual)

        assertEquals(1f, metrics.titlePrecision)
        assertEquals(1f, metrics.titleRecall)
        assertEquals(1f, metrics.hierarchyEdgeAccuracy)
        assertEquals(0.5f, metrics.targetPageAccuracy)
    }

    @Test
    fun evaluationMetricsPenalizeExtraHierarchyEdges() {
        val expected =
            evaluationOutline("Introduction", 1, "Topic", 3).copy(
                nodes =
                    listOf(
                        PdfOutlineNode(
                            id = "root",
                            title = "Introduction",
                            destination = PdfOutlineDestination(1),
                            source = PdfOutlineSource.NATIVE_TEXT,
                            reviewState = PdfOutlineReviewState.CONFIRMED,
                        ),
                        PdfOutlineNode(
                            id = "topic",
                            title = "Topic",
                            destination = PdfOutlineDestination(3),
                            source = PdfOutlineSource.NATIVE_TEXT,
                            reviewState = PdfOutlineReviewState.CONFIRMED,
                        ),
                    ),
            )
        val actual =
            expected.copy(
                nodes =
                    listOf(
                        expected.nodes.first().copy(children = listOf(expected.nodes[1])),
                    ),
            )

        val metrics = ChapterEvaluator.evaluate(expected, actual)

        assertEquals(0f, metrics.hierarchyEdgeAccuracy)
    }

    private fun block(
        id: String,
        text: String,
        top: Float,
        bottom: Float,
        fontSize: Float,
        bold: Boolean = false,
        type: PdfLayoutBlockType = PdfLayoutBlockType.TEXT,
    ): PdfLayoutBlock =
        PdfLayoutBlock(
            id = id,
            order = 0,
            type = type,
            text = text,
            bounds = PdfNormalizedRect(0.1f, top, 0.9f, bottom),
            style = PdfTextStyle(fontSize = fontSize, bold = bold),
        )

    private fun headingLayout(
        pageIndex: Int,
        title: String,
    ): PdfPageLayout =
        PdfPageLayout(
            pageIndex = pageIndex,
            width = 612,
            height = 792,
            engine = AnalysisProvenance("native-pdf-layout", "test"),
            blocks =
                listOf(
                    block(
                        "heading-$pageIndex",
                        title,
                        0.16f,
                        0.24f,
                        20f,
                        bold = true,
                        type = PdfLayoutBlockType.SECTION_TITLE,
                    ),
                ),
        )

    private fun headingWithPrintedPage(
        pageIndex: Int,
        title: String,
        printedPage: String,
    ): PdfPageLayout =
        headingLayout(pageIndex, title).copy(
            blocks = headingLayout(pageIndex, title).blocks + pageNumberBlock(pageIndex, printedPage),
        )

    private fun tocLayout(line: String): PdfPageLayout =
        PdfPageLayout(
            pageIndex = 0,
            width = 612,
            height = 792,
            engine = AnalysisProvenance("native-pdf-layout", "test"),
            blocks =
                listOf(
                    block(
                        "toc",
                        "目录\n$line",
                        0.12f,
                        0.5f,
                        12f,
                        type = PdfLayoutBlockType.TOC,
                    ),
                ),
        )

    private fun pageNumberLayout(
        pageIndex: Int,
        printedPage: String,
    ): PdfPageLayout =
        PdfPageLayout(
            pageIndex = pageIndex,
            width = 612,
            height = 792,
            engine = AnalysisProvenance("native-pdf-layout", "test"),
            blocks = listOf(pageNumberBlock(pageIndex, printedPage)),
        )

    private fun pageNumberBlock(
        pageIndex: Int,
        printedPage: String,
    ): PdfLayoutBlock =
        block(
            "page-number-$pageIndex",
            printedPage,
            0.92f,
            0.96f,
            10f,
            type = PdfLayoutBlockType.PAGE_NUMBER,
        )

    private fun evaluationOutline(
        rootTitle: String,
        rootPage: Int,
        childTitle: String,
        childPage: Int,
    ): PdfOutlineDocument =
        PdfOutlineDocument(
            relativePath = "book.pdf",
            documentFingerprint = "0".repeat(64),
            nodes =
                listOf(
                    PdfOutlineNode(
                        id = "root",
                        title = rootTitle,
                        destination = PdfOutlineDestination(rootPage),
                        source = PdfOutlineSource.NATIVE_TEXT,
                        reviewState = PdfOutlineReviewState.CONFIRMED,
                        children =
                            listOf(
                                PdfOutlineNode(
                                    id = "child",
                                    title = childTitle,
                                    destination = PdfOutlineDestination(childPage),
                                    source = PdfOutlineSource.NATIVE_TEXT,
                                    reviewState = PdfOutlineReviewState.CONFIRMED,
                                ),
                            ),
                    ),
                ),
        )
}
