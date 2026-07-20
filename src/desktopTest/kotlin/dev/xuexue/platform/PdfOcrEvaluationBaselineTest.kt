package dev.xuexue.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class PdfOcrEvaluationBaselineTest {
    @Test
    fun deterministicFixtureBaselineMeetsFirstVersionTargets() {
        val cases =
            listOf(
                evaluateCase(
                    name = "native-numbered",
                    titles = listOf("1 Introduction", "1.1 Scope", "2 Methods"),
                    expected =
                        listOf(
                            node("root", "1 Introduction", 0, node("scope", "1.1 Scope", 1)),
                            node("methods", "2 Methods", 2),
                        ),
                ),
                evaluateCase(
                    name = "cjk-hierarchy",
                    titles = listOf("第一章 基础", "第一节 字形", "第二章 实践"),
                    expected =
                        listOf(
                            node("chapter-1", "第一章 基础", 0, node("section", "第一节 字形", 1)),
                            node("chapter-2", "第二章 实践", 2),
                        ),
                ),
                evaluateCase(
                    name = "english-numbered",
                    titles = listOf("Chapter 2 Methods", "2.1 Scope"),
                    expected =
                        listOf(
                            node("chapter", "Chapter 2 Methods", 0, node("scope", "2.1 Scope", 1)),
                        ),
                ),
            )

        cases.forEach { (name, metrics) ->
            assertTrue(metrics.titlePrecision >= 0.95f, "$name title precision")
            assertTrue(metrics.titleRecall >= 0.90f, "$name title recall")
            assertTrue(metrics.hierarchyEdgeAccuracy >= 0.90f, "$name hierarchy accuracy")
            assertTrue(metrics.targetPageAccuracy >= 0.98f, "$name page accuracy")
        }
    }

    private fun evaluateCase(
        name: String,
        titles: List<String>,
        expected: List<PdfOutlineNode>,
    ): Pair<String, ChapterEvaluationMetrics> {
        val layouts = titles.mapIndexed(::headingLayout)
        val actual =
            ChapterStructureDetector()
                .detect(
                    layouts = layouts,
                    relativePath = "$name.pdf",
                    documentFingerprint = "0".repeat(64),
                    analysis = AnalysisProvenance("native-pdf-layout", "fixture"),
                ).outline
        return name to ChapterEvaluator.evaluate(outline(expected), actual)
    }

    private fun headingLayout(
        pageIndex: Int,
        title: String,
    ): PdfPageLayout =
        PdfPageLayout(
            pageIndex = pageIndex,
            width = 612,
            height = 792,
            blocks =
                listOf(
                    PdfLayoutBlock(
                        id = "heading-$pageIndex",
                        order = 0,
                        type = PdfLayoutBlockType.SECTION_TITLE,
                        text = title,
                        bounds = PdfNormalizedRect(0.12f, 0.18f, 0.88f, 0.24f),
                        recognitionConfidence = 1f,
                        style = PdfTextStyle(fontSize = 22f, bold = true),
                    ),
                ),
            engine = AnalysisProvenance("native-pdf-layout", "fixture"),
        )

    private fun outline(nodes: List<PdfOutlineNode>): PdfOutlineDocument =
        PdfOutlineDocument(
            relativePath = "expected.pdf",
            documentFingerprint = "0".repeat(64),
            nodes = nodes,
        )

    private fun node(
        id: String,
        title: String,
        pageIndex: Int,
        vararg children: PdfOutlineNode,
    ): PdfOutlineNode =
        PdfOutlineNode(
            id = id,
            title = title,
            destination = PdfOutlineDestination(pageIndex),
            source = PdfOutlineSource.NATIVE_TEXT,
            confidence = 1f,
            reviewState = PdfOutlineReviewState.CONFIRMED,
            children = children.toList(),
        )
}
