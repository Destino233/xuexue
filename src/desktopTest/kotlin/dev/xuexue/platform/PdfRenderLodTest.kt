package dev.xuexue.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRenderLodTest {
    @Test
    fun viewportLodUsesHysteresisAroundRasterThresholds() {
        assertEquals(
            96f,
            PdfRenderLod.quantizeWithHysteresis(100f, PdfRasterPurpose.VIEWPORT, currentDpi = 96f),
        )
        assertEquals(
            120f,
            PdfRenderLod.quantizeWithHysteresis(110f, PdfRasterPurpose.VIEWPORT, currentDpi = 96f),
        )
        assertEquals(
            120f,
            PdfRenderLod.quantizeWithHysteresis(94f, PdfRasterPurpose.VIEWPORT, currentDpi = 120f),
        )
        assertEquals(
            96f,
            PdfRenderLod.quantizeWithHysteresis(88f, PdfRasterPurpose.VIEWPORT, currentDpi = 120f),
        )
    }
}
