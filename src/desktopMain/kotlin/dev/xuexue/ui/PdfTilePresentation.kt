package dev.xuexue.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal enum class PdfColorMode { NORMAL, NIGHT }

internal data class PdfTileWindow(
    val firstColumn: Int,
    val lastColumn: Int,
    val firstRow: Int,
    val lastRow: Int,
) {
    fun contains(
        column: Int,
        row: Int,
    ): Boolean = column in firstColumn..lastColumn && row in firstRow..lastRow

    companion object {
        fun full(
            columns: Int,
            rows: Int,
        ): PdfTileWindow = PdfTileWindow(0, columns - 1, 0, rows - 1)
    }
}

internal fun pdfVisibleTileWindow(
    pageBounds: Rect,
    viewportBounds: Rect,
    columns: Int,
    rows: Int,
    marginTiles: Int = 1,
): PdfTileWindow? {
    require(marginTiles >= 0) { "PDF tile prefetch margin cannot be negative." }
    val validGrid = columns > 0 && rows > 0
    val validViewport = viewportBounds.width > 0f && viewportBounds.height > 0f
    if (!validGrid || !validViewport) return null
    val left = max(pageBounds.left, viewportBounds.left)
    val top = max(pageBounds.top, viewportBounds.top)
    val right = min(pageBounds.right, viewportBounds.right)
    val bottom = min(pageBounds.bottom, viewportBounds.bottom)
    val intersectsViewport = right > left && bottom > top
    val validPage = pageBounds.width > 0f && pageBounds.height > 0f
    return if (!intersectsViewport || !validPage) {
        null
    } else {
        val normalizedLeft = ((left - pageBounds.left) / pageBounds.width).coerceIn(0f, 1f)
        val normalizedTop = ((top - pageBounds.top) / pageBounds.height).coerceIn(0f, 1f)
        val normalizedRight = ((right - pageBounds.left) / pageBounds.width).coerceIn(0f, 1f)
        val normalizedBottom = ((bottom - pageBounds.top) / pageBounds.height).coerceIn(0f, 1f)
        val firstColumn = floor(normalizedLeft * columns).toInt()
        val lastColumn = (ceil(normalizedRight * columns).toInt() - 1).coerceAtLeast(firstColumn)
        val firstRow = floor(normalizedTop * rows).toInt()
        val lastRow = (ceil(normalizedBottom * rows).toInt() - 1).coerceAtLeast(firstRow)
        PdfTileWindow(
            firstColumn = (firstColumn - marginTiles).coerceAtLeast(0),
            lastColumn = (lastColumn + marginTiles).coerceAtMost(columns - 1),
            firstRow = (firstRow - marginTiles).coerceAtLeast(0),
            lastRow = (lastRow + marginTiles).coerceAtMost(rows - 1),
        )
    }
}

internal fun pdfPageColorFilter(mode: String): ColorFilter? =
    when (mode) {
        PdfColorMode.NIGHT.name -> PDF_NIGHT_COLOR_FILTER
        else -> null
    }

internal fun pdfPageBackground(mode: PdfColorMode): Color =
    when (mode) {
        PdfColorMode.NIGHT -> Color(0xFF1B1D1C)
        PdfColorMode.NORMAL -> Color.White
    }

private val PDF_NIGHT_COLOR_FILTER =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.82f,
                0f,
                0f,
                0f,
                235f,
                0f,
                -0.82f,
                0f,
                0f,
                235f,
                0f,
                0f,
                -0.82f,
                0f,
                235f,
                0f,
                0f,
                0f,
                1f,
                0f,
            ),
        ),
    )
