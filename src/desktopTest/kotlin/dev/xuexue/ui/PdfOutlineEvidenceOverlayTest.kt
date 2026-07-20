package dev.xuexue.ui

import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfOutlineDestination
import dev.xuexue.platform.PdfOutlineEvidence
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfOutlineEvidenceOverlayTest {
    @Test
    fun onlyCandidateAndRejectedEvidenceForTheVisiblePageIsShown() {
        val nodes =
            listOf(
                node("candidate", PdfOutlineReviewState.CANDIDATE, page = 1),
                node("rejected", PdfOutlineReviewState.REJECTED, page = 1),
                node("confirmed", PdfOutlineReviewState.CONFIRMED, page = 1),
                node("other-page", PdfOutlineReviewState.CANDIDATE, page = 2),
            )

        val regions = outlineEvidenceForPage(nodes, page = 1)

        assertEquals(
            listOf(PdfOutlineReviewState.CANDIDATE, PdfOutlineReviewState.REJECTED),
            regions.map(PdfOutlineEvidenceRegion::reviewState),
        )
    }

    private fun node(
        id: String,
        state: PdfOutlineReviewState,
        page: Int,
    ): PdfOutlineNode =
        PdfOutlineNode(
            id = id,
            title = id,
            destination = PdfOutlineDestination(page),
            source = PdfOutlineSource.OCR,
            confidence = 0.5f,
            reviewState = state,
            evidence =
                PdfOutlineEvidence(
                    pageIndex = page,
                    bounds = PdfNormalizedRect(0.1f, 0.1f, 0.9f, 0.2f),
                ),
        )
}
