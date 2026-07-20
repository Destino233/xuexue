package dev.xuexue

import dev.xuexue.platform.PdfNativeOutlineNode
import dev.xuexue.platform.PdfOutlineDestination
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfOutlineTreeEditorTest {
    @Test
    fun highConfidenceConfirmationAndConfirmedExportKeepManualNodes() {
        val nodes =
            listOf(
                node(
                    "root",
                    "Root",
                    0.9f,
                    PdfOutlineReviewState.CANDIDATE,
                    children = listOf(node("child", "Child", 0.4f)),
                ),
                node("manual", "Manual", null, PdfOutlineReviewState.MANUAL),
            )

        val confirmed = PdfOutlineTreeEditor.confirmHighConfidence(nodes, 0.86f)
        val exportable = PdfOutlineTreeEditor.confirmedOnly(confirmed)

        assertEquals(PdfOutlineReviewState.CONFIRMED, confirmed.first().reviewState)
        assertEquals(
            PdfOutlineReviewState.CANDIDATE,
            confirmed
                .first()
                .children
                .single()
                .reviewState,
        )
        assertEquals(listOf("Root", "Manual"), exportable.map(PdfOutlineNode::title))
        assertEquals(emptyList(), exportable.first().children)
    }

    @Test
    fun exportValidationTreatsDuplicateOutlineEntriesAsAMultiset() {
        val expected = listOf("Chapter" to 1, "Chapter" to 1, "Topic" to 2)

        assertFalse(
            outlineExportMatches(
                expected,
                listOf("Chapter" to 1, "Topic" to 2),
                PdfOutlineExportStrategy.REPLACE,
            ),
        )
        assertTrue(
            outlineExportMatches(
                expected,
                listOf("Chapter" to 1, "Chapter" to 1, "Topic" to 2),
                PdfOutlineExportStrategy.REPLACE,
            ),
        )
        assertTrue(
            outlineExportMatches(
                expected,
                listOf("Original" to 0, "Chapter" to 1, "Chapter" to 1, "Topic" to 2),
                PdfOutlineExportStrategy.APPEND,
            ),
        )
    }

    @Test
    fun exportValidationRejectsAFlattenedTreeWithTheWrongHierarchy() {
        val expected =
            listOf(
                node(
                    "root",
                    "Root",
                    null,
                    PdfOutlineReviewState.CONFIRMED,
                    children = listOf(node("child", "Child", null)),
                ),
            )
        val actual =
            listOf(
                PdfNativeOutlineNode("Root", 0, null, null),
                PdfNativeOutlineNode("Child", 0, null, null),
            )

        assertFalse(outlineTreeExportMatches(expected, actual, PdfOutlineExportStrategy.REPLACE))
    }

    @Test
    fun exportableOutlineNeverIncludesRejectedNodes() {
        val nodes =
            listOf(
                node("candidate", "Candidate", 0.5f),
                node("rejected", "Rejected", 0.9f, PdfOutlineReviewState.REJECTED),
                node("manual", "Manual", null, PdfOutlineReviewState.MANUAL),
            )

        assertEquals(
            listOf("Manual"),
            PdfOutlineTreeEditor.exportable(nodes, confirmedOnly = true).map(PdfOutlineNode::title),
        )
        assertEquals(
            listOf("Candidate", "Manual"),
            PdfOutlineTreeEditor.exportable(nodes, confirmedOnly = false).map(PdfOutlineNode::title),
        )
    }

    @Test
    fun movingANodeMarksOnlyTheMovedNodeAsManual() {
        val nodes =
            listOf(
                node("first", "First", 0.9f, PdfOutlineReviewState.CONFIRMED),
                node("second", "Second", 0.9f, PdfOutlineReviewState.CONFIRMED),
                node("third", "Third", 0.9f, PdfOutlineReviewState.CONFIRMED),
            )

        val moved = PdfOutlineTreeEditor.moveNode(nodes, "second", 1)

        assertEquals(listOf("First", "Third", "Second"), moved.map(PdfOutlineNode::title))
        assertEquals(
            listOf(PdfOutlineReviewState.CONFIRMED, PdfOutlineReviewState.CONFIRMED, PdfOutlineReviewState.MANUAL),
            moved.map(PdfOutlineNode::reviewState),
        )
    }

    @Test
    fun exportFailureDiagnosticsExplainProtectedAndDamagedPdfs() {
        assertTrue(
            pdfOutlineExportFailureMessage(IllegalStateException("cannot authenticate encrypted PDF"))
                .contains("密码"),
        )
        assertTrue(
            pdfOutlineExportFailureMessage(IllegalStateException("invalid PDF format"))
                .contains("结构损坏"),
        )
        assertTrue(
            pdfOutlineExportFailureMessage(IllegalStateException("PDF contains digital signatures"))
                .contains("数字签名"),
        )
    }

    private fun node(
        id: String,
        title: String,
        confidence: Float?,
        reviewState: PdfOutlineReviewState = PdfOutlineReviewState.CANDIDATE,
        children: List<PdfOutlineNode> = emptyList(),
    ): PdfOutlineNode =
        PdfOutlineNode(
            id = id,
            title = title,
            destination = PdfOutlineDestination(0),
            source = if (reviewState == PdfOutlineReviewState.MANUAL) PdfOutlineSource.MANUAL else PdfOutlineSource.OCR,
            confidence = confidence,
            reviewState = reviewState,
            children = children,
        )
}
