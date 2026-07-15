package dev.xuexue.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PdfTileRasterTest {
    @Test
    fun interiorTileUsesStableIntegerBoundariesAndTwoPixelPadding() {
        val region =
            pdfRasterRegion(
                scaledPageBounds = MuPdfRect(0f, 0f, 1_001f, 799f),
                viewport = PdfNormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
            )

        assertEquals(MuPdfPixelBounds(250, 200, 501, 399), region.contentBounds)
        assertEquals(MuPdfPixelBounds(248, 198, 505, 403), region.renderBounds)
    }

    @Test
    fun edgeTilePaddingNeverEscapesThePage() {
        val region =
            pdfRasterRegion(
                scaledPageBounds = MuPdfRect(0f, 0f, 1_000f, 800f),
                viewport = PdfNormalizedRect(0f, 0f, 0.5f, 0.5f),
            )

        assertEquals(MuPdfPixelBounds(0, 0, 500, 400), region.contentBounds)
        assertEquals(MuPdfPixelBounds(0, 0, 502, 402), region.renderBounds)
    }

    @Test
    fun paddedRgbaIsCroppedBackToTheLogicalTile() {
        val region =
            PdfRasterRegion(
                renderBounds = MuPdfPixelBounds(10, 20, 4, 3),
                contentBounds = MuPdfPixelBounds(11, 21, 2, 1),
            )
        val rendered = ByteArray(4 * 3 * RGBA_BYTES_PER_PIXEL)
        setPixel(rendered, width = 4, x = 1, y = 1, marker = 7)
        setPixel(rendered, width = 4, x = 2, y = 1, marker = 9)

        assertContentEquals(
            byteArrayOf(7, 7, 7, -1, 9, 9, 9, -1),
            region.crop(rendered),
        )
    }

    private fun setPixel(
        rgba: ByteArray,
        width: Int,
        x: Int,
        y: Int,
        marker: Byte,
    ) {
        val offset = (y * width + x) * RGBA_BYTES_PER_PIXEL
        repeat(RGBA_BYTES_PER_PIXEL - 1) { channel -> rgba[offset + channel] = marker }
        rgba[offset + RGBA_BYTES_PER_PIXEL - 1] = -1
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
