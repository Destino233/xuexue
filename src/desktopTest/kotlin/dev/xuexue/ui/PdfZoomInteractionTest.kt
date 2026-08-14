package dev.xuexue.ui

import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfZoomInteractionTest {
    @Test
    fun ctrlWheelIsConsumedBeforeScrollableChildren() {
        assertEquals(PointerEventPass.Initial, PDF_CTRL_WHEEL_EVENT_PASS)
    }

    @Test
    fun ctrlWheelZoomUsesSmoothDirectionalSteps() {
        val zoomedIn = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f)
        val fineZoom = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -0.25f)
        val zoomedOut = pdfZoomForScroll(currentZoom = 1f, verticalScroll = 1f)

        assertTrue(zoomedIn > 1.1f)
        assertTrue(fineZoom in 1f..<zoomedIn)
        assertTrue(zoomedOut < 0.9f)
    }

    @Test
    fun wheelZoomMultiplierScalesTheAdaptiveStep() {
        val precise = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f, wheelZoomMultiplier = 0.5f)
        val normal = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f, wheelZoomMultiplier = 1f)
        val fast = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f, wheelZoomMultiplier = 2f)

        assertTrue(precise in 1f..<normal)
        assertTrue(fast > normal)
    }

    @Test
    fun adaptiveZoomBecomesFinerAtHighMagnification() {
        val lowZoomRatio = pdfZoomForScroll(currentZoom = 0.5f, verticalScroll = -1f) / 0.5f
        val highZoomRatio = pdfZoomForScroll(currentZoom = 3f, verticalScroll = -1f) / 3f

        assertTrue(lowZoomRatio > highZoomRatio)
    }

    @Test
    fun acceleratedWheelBurstsAreCompressed() {
        val singleStepRatio = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -1f)
        val burstRatio = pdfZoomForScroll(currentZoom = 1f, verticalScroll = -4f)

        assertTrue(burstRatio < singleStepRatio * singleStepRatio * singleStepRatio * singleStepRatio)
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

    @Test
    fun centeredPageZoomKeepsThePointUnderThePointerStable() {
        assertEquals(
            40,
            zoomedPageScrollOffset(
                currentOffset = 0,
                anchorPosition = 700f,
                sourcePageWidth = 800f,
                targetPageWidth = 960f,
                viewportWidth = 1_000f,
            ),
        )
    }
}
