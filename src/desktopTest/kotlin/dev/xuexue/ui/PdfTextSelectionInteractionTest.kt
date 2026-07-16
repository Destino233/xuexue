package dev.xuexue.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfTextSelectionInteractionTest {
    @Test
    fun firstVisibleTextSelectionPreviewIsImmediate() {
        assertTrue(
            shouldDispatchPdfSelectionPreview(
                dragDistance = 8f,
                eventUptimeMillis = 100L,
                lastDispatchUptimeMillis = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun liveSelectionUpdatesAreFrameRateLimited() {
        assertFalse(
            shouldDispatchPdfSelectionPreview(
                dragDistance = 8f,
                eventUptimeMillis = 110L,
                lastDispatchUptimeMillis = 100L,
            ),
        )
        assertTrue(
            shouldDispatchPdfSelectionPreview(
                dragDistance = 8f,
                eventUptimeMillis = 124L,
                lastDispatchUptimeMillis = 100L,
            ),
        )
    }

    @Test
    fun pointerNoiseDoesNotStartTextSelection() {
        assertFalse(
            shouldDispatchPdfSelectionPreview(
                dragDistance = 2f,
                eventUptimeMillis = 200L,
                lastDispatchUptimeMillis = Long.MIN_VALUE,
            ),
        )
    }
}
