package dev.xuexue.ui

import androidx.compose.ui.graphics.toPixelMap
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfPerformanceMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRasterImageTest {
    @Test
    fun rgbaRasterKeepsDimensionsAndColorChannels() {
        val raster =
            PdfPageRaster(
                pageIndex = 0,
                pixelWidth = 2,
                pixelHeight = 1,
                rgbaBytes =
                    byteArrayOf(
                        255.toByte(),
                        0,
                        0,
                        255.toByte(),
                        0,
                        0,
                        255.toByte(),
                        255.toByte(),
                    ),
            )

        val metrics = PdfPerformanceMetrics()
        val bitmap = raster.toComposeBitmap(metrics)
        val pixels = bitmap.toPixelMap()

        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)
        assertEquals(1f, pixels[0, 0].red, absoluteTolerance = COLOR_TOLERANCE)
        assertEquals(0f, pixels[0, 0].blue, absoluteTolerance = COLOR_TOLERANCE)
        assertEquals(0f, pixels[1, 0].red, absoluteTolerance = COLOR_TOLERANCE)
        assertEquals(1f, pixels[1, 0].blue, absoluteTolerance = COLOR_TOLERANCE)
        assertEquals(1L, metrics.snapshot().texturePreparations)
        assertEquals(raster.byteSize.toLong(), metrics.snapshot().textureBytesPrepared)
    }

    private companion object {
        const val COLOR_TOLERANCE = 0.01f
    }
}
