package dev.xuexue.ui

import dev.xuexue.platform.PdfPageRaster
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRasterRefinementTest {
    @Test
    fun incompleteRasterIsReplacedUntilTheResultIsAuthoritative() =
        runBlocking {
            var attempts = 0
            val results = mutableListOf<Boolean>()

            loadPdfRasterWithRefinement(
                initialDelayMillis = 0L,
                render = {
                    attempts++
                    testRaster(incomplete = attempts < 3)
                },
                onRaster = { raster -> results += raster.incomplete },
            )

            assertEquals(3, attempts)
            assertEquals(listOf(true, true, false), results)
        }

    private fun testRaster(incomplete: Boolean): PdfPageRaster =
        PdfPageRaster(
            pageIndex = 0,
            pixelWidth = 1,
            pixelHeight = 1,
            rgbaBytes = byteArrayOf(0, 0, 0, 0),
            incomplete = incomplete,
        )
}
