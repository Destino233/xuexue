package dev.xuexue.platform

import androidx.compose.ui.graphics.toPixelMap
import dev.xuexue.ui.toComposeBitmap
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the zero-copy native raster path produces exactly the same
 * pixels as the byte-array path for padded tiles and full-page renders.
 */
class PdfZeroCopyRenderTest {
    @Test
    fun zeroCopyRasterMatchesByteArrayRaster() =
        runBlocking {
            val path = fixturePdf()
            val backend = MuPdfReaderBackend()
            try {
                assertTrue(MuPdfNative.zeroCopyRenderingAvailable, "zero-copy library probe failed")
                val cases =
                    listOf<Pair<Float, PdfNormalizedRect?>>(
                        96f to null,
                        144f to PdfNormalizedRect(0f, 0f, 0.5f, 0.5f),
                    )
                for ((dpi, viewport) in cases) {
                    val request =
                        PdfRasterRequest(
                            path = path,
                            pageIndex = 0,
                            dpi = dpi,
                            purpose = PdfRasterPurpose.VIEWPORT,
                            viewport = viewport,
                        )
                    val zeroCopy = backend.render(request)
                    assertTrue(zeroCopy.nativeAddress != 0L, "expected the zero-copy path for $viewport")
                    PdfZeroCopyGate.enabled = false
                    val legacy =
                        try {
                            backend.render(request)
                        } finally {
                            PdfZeroCopyGate.enabled = true
                        }
                    assertEquals(zeroCopy.pixelWidth, legacy.pixelWidth)
                    assertEquals(zeroCopy.pixelHeight, legacy.pixelHeight)
                    val zeroCopyPixels = zeroCopy.toComposeBitmap().toPixelMap()
                    val legacyPixels = legacy.toComposeBitmap().toPixelMap()
                    val width = zeroCopy.pixelWidth
                    val height = zeroCopy.pixelHeight
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val expected = legacyPixels[x, y]
                            val actual = zeroCopyPixels[x, y]
                            assertEquals(expected.red, actual.red, absoluteTolerance = 0.01f, message = "red at ($x,$y)")
                            assertEquals(expected.green, actual.green, absoluteTolerance = 0.01f, message = "green at ($x,$y)")
                            assertEquals(expected.blue, actual.blue, absoluteTolerance = 0.01f, message = "blue at ($x,$y)")
                            assertEquals(expected.alpha, actual.alpha, absoluteTolerance = 0.01f, message = "alpha at ($x,$y)")
                        }
                    }
                }
            } finally {
                backend.close()
            }
        }

    private fun fixturePdf(): Path {
        val path = Path.of(System.getProperty("java.io.tmpdir"), "xuexue-zero-copy-fixture.pdf")
        if (!Files.isRegularFile(path)) {
            Files.writeString(
                path,
                "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
                    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
                    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n" +
                    "4 0 obj<</Length 60>>stream\n0 0 200 200 re f\nBT /F1 24 Tf 20 100 Td (Hello) Tj ET\nendstream\nendobj\n" +
                    "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n" +
                    "xref\n0 6\n0000000000 65535 f \ntrailer<</Size 6/Root 1 0 R>>\nstartxref\n0\n%%EOF\n",
            )
        }
        return path
    }
}
