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

    @Test
    fun viewportLodSupportsHighDpiDisplaySurfaces() {
        assertEquals(576f, PdfRenderLod.quantize(500f, PdfRasterPurpose.VIEWPORT))
        assertEquals(960f, PdfRenderLod.quantize(900f, PdfRasterPurpose.VIEWPORT))
    }

    @Test
    fun viewportLodAvoidsLargeUpwardJumpsNearCommonDisplayDpis() {
        assertEquals(156f, PdfRenderLod.quantize(145f, PdfRasterPurpose.VIEWPORT))
        assertEquals(216f, PdfRenderLod.quantize(193f, PdfRasterPurpose.VIEWPORT))
    }
}
