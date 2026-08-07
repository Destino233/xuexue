package dev.xuexue.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdfPrefetchTest {
    @Test
    fun initialAndForwardScrollPrefetchAfterTheVisibleRange() {
        val initial = PdfScrollPosition(firstVisiblePage = 2, firstVisibleOffset = 0, lastVisiblePage = 4)
        val scrolled = initial.copy(firstVisibleOffset = 120)

        assertEquals(5, pdfPrefetchTarget(previous = null, current = initial, pageCount = 10))
        assertEquals(5, pdfPrefetchTarget(previous = initial, current = scrolled, pageCount = 10))
        assertEquals(PdfScrollDirection.FORWARD, pdfScrollDirection(initial, scrolled))
        assertEquals(
            5,
            pdfPrefetchTargetForDirection(initial, PdfScrollDirection.FORWARD, pageCount = 10),
        )
    }

    @Test
    fun backwardScrollPrefetchesBeforeTheVisibleRange() {
        val previous = PdfScrollPosition(firstVisiblePage = 3, firstVisibleOffset = 200, lastVisiblePage = 5)
        val current = PdfScrollPosition(firstVisiblePage = 3, firstVisibleOffset = 80, lastVisiblePage = 5)

        assertEquals(2, pdfPrefetchTarget(previous, current, pageCount = 10))
        assertEquals(PdfScrollDirection.BACKWARD, pdfScrollDirection(previous, current))
        assertEquals(
            2,
            pdfPrefetchTargetForDirection(current, PdfScrollDirection.BACKWARD, pageCount = 10),
        )
    }

    @Test
    fun unchangedOrBoundaryPositionsDoNotScheduleInvalidPages() {
        val first = PdfScrollPosition(firstVisiblePage = 0, firstVisibleOffset = 0, lastVisiblePage = 0)
        val last = PdfScrollPosition(firstVisiblePage = 9, firstVisibleOffset = 40, lastVisiblePage = 9)

        assertNull(pdfPrefetchTarget(first, first, pageCount = 10))
        assertNull(pdfPrefetchTarget(last.copy(firstVisibleOffset = 0), last, pageCount = 10))
        assertNull(pdfPrefetchTarget(previous = null, current = last, pageCount = 10))
        assertNull(pdfScrollDirection(first, first))
    }
}
