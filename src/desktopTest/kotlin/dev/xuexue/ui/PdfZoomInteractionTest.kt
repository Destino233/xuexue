package dev.xuexue.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfZoomInteractionTest {
    @Test
    fun ctrlWheelZoomUsesSmoothDirectionalSteps() {
        val zoomedIn = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f)
        val fineZoom = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -0.25f)
        val zoomedOut = pdfZoomForScroll(currentZoom = 1f, verticalScroll = 1f)

        assertTrue(zoomedIn > 1f)
        assertTrue(fineZoom in 1f..<zoomedIn)
        assertTrue(zoomedOut < 1f)
    }

    @Test
    fun ctrlWheelZoomRespectsReaderLimits() {
        assertEquals(4f, pdfZoomForScroll(currentZoom = 4f, verticalScroll = -100f))
        assertEquals(0.4f, pdfZoomForScroll(currentZoom = 0.4f, verticalScroll = 100f))
    }

    @Test
    fun zoomAnchorKeepsThePointUnderThePointerStable() {
        assertEquals(
            400,
            zoomedScrollOffset(
                currentOffset = 100,
                anchorPosition = 200f,
                zoomRatio = 2f,
            ),
        )
    }
}
