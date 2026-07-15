package dev.xuexue.platform

import kotlin.math.roundToInt

internal data class PdfRasterRegion(
    val renderBounds: MuPdfPixelBounds,
    val contentBounds: MuPdfPixelBounds,
) {
    fun crop(renderedRgba: ByteArray): ByteArray {
        val expectedBytes = renderBounds.width * renderBounds.height * RGBA_BYTES_PER_PIXEL
        check(renderedRgba.size == expectedBytes) { "Padded PDF tile has an invalid RGBA payload." }
        if (renderBounds == contentBounds) return renderedRgba
        val cropped = ByteArray(contentBounds.width * contentBounds.height * RGBA_BYTES_PER_PIXEL)
        val sourceX = contentBounds.x - renderBounds.x
        val sourceY = contentBounds.y - renderBounds.y
        val rowBytes = contentBounds.width * RGBA_BYTES_PER_PIXEL
        repeat(contentBounds.height) { row ->
            val sourceOffset = ((sourceY + row) * renderBounds.width + sourceX) * RGBA_BYTES_PER_PIXEL
            renderedRgba.copyInto(cropped, row * rowBytes, sourceOffset, sourceOffset + rowBytes)
        }
        return cropped
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}

internal fun pdfRasterRegion(
    scaledPageBounds: MuPdfRect,
    viewport: PdfNormalizedRect?,
): PdfRasterRegion {
    val pageBounds = scaledPageBounds.pixelBounds(FULL_PAGE_VIEWPORT)
    val contentBounds = scaledPageBounds.pixelBounds(viewport ?: FULL_PAGE_VIEWPORT)
    if (viewport == null) return PdfRasterRegion(pageBounds, contentBounds)
    val left = (contentBounds.x - TILE_PADDING_PIXELS).coerceAtLeast(pageBounds.x)
    val top = (contentBounds.y - TILE_PADDING_PIXELS).coerceAtLeast(pageBounds.y)
    val right =
        (contentBounds.x + contentBounds.width + TILE_PADDING_PIXELS)
            .coerceAtMost(pageBounds.x + pageBounds.width)
    val bottom =
        (contentBounds.y + contentBounds.height + TILE_PADDING_PIXELS)
            .coerceAtMost(pageBounds.y + pageBounds.height)
    return PdfRasterRegion(
        renderBounds = MuPdfPixelBounds(left, top, right - left, bottom - top),
        contentBounds = contentBounds,
    )
}

private fun MuPdfRect.pixelBounds(area: PdfNormalizedRect): MuPdfPixelBounds {
    val fullWidth = width.coerceAtLeast(1f)
    val fullHeight = height.coerceAtLeast(1f)
    val left = (x0 + area.left * fullWidth).roundToInt()
    val top = (y0 + area.top * fullHeight).roundToInt()
    val right = (x0 + area.right * fullWidth).roundToInt().coerceAtLeast(left + 1)
    val bottom = (y0 + area.bottom * fullHeight).roundToInt().coerceAtLeast(top + 1)
    return MuPdfPixelBounds(left, top, right - left, bottom - top)
}

private val FULL_PAGE_VIEWPORT = PdfNormalizedRect(0f, 0f, 1f, 1f)
private const val TILE_PADDING_PIXELS = 2
