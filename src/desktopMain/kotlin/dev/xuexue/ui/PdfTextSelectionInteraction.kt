@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint

@Composable
internal fun rememberPdfSelectionGesture(
    page: Int,
    pageDimensions: PdfPageDimensions,
    pageWidthPixels: Float,
    pageHeightPixels: Float,
    onTextSelection: ((PdfPagePoint, PdfPagePoint) -> Unit)?,
): PdfTextSelectionInteraction {
    var dragStart by remember(page) { mutableStateOf<Offset?>(null) }
    var dragEnd by remember(page) { mutableStateOf<Offset?>(null) }
    val modifier =
        if (onTextSelection == null) {
            Modifier
        } else {
            Modifier.pointerInput(page, pageWidthPixels, pageHeightPixels) {
                detectDragGestures(
                    onDragStart = { position ->
                        dragStart = position
                        dragEnd = position
                    },
                    onDragCancel = {
                        dragStart = null
                        dragEnd = null
                    },
                    onDragEnd = {
                        dispatchSelection(
                            start = dragStart,
                            end = dragEnd,
                            pageWidthPixels = pageWidthPixels,
                            pageHeightPixels = pageHeightPixels,
                            pageDimensions = pageDimensions,
                            onTextSelection = onTextSelection,
                        )
                        dragStart = null
                        dragEnd = null
                    },
                    onDrag = { change, _ ->
                        dragEnd = change.position
                        change.consume()
                    },
                )
            }
        }
    return PdfTextSelectionInteraction(
        modifier = modifier,
        region = normalizedDragRegion(dragStart, dragEnd, pageWidthPixels, pageHeightPixels),
    )
}

private fun dispatchSelection(
    start: Offset?,
    end: Offset?,
    pageWidthPixels: Float,
    pageHeightPixels: Float,
    pageDimensions: PdfPageDimensions,
    onTextSelection: (PdfPagePoint, PdfPagePoint) -> Unit,
) {
    if (start == null || end == null || (end - start).getDistance() < MIN_SELECTION_DRAG_PIXELS) return
    onTextSelection(
        start.toPdfPagePoint(pageWidthPixels, pageHeightPixels, pageDimensions),
        end.toPdfPagePoint(pageWidthPixels, pageHeightPixels, pageDimensions),
    )
}

private fun Offset.toPdfPagePoint(
    pageWidthPixels: Float,
    pageHeightPixels: Float,
    pageDimensions: PdfPageDimensions,
): PdfPagePoint =
    PdfPagePoint(
        x = (x / pageWidthPixels).coerceIn(0f, 1f) * pageDimensions.widthPoints,
        y = (y / pageHeightPixels).coerceIn(0f, 1f) * pageDimensions.heightPoints,
    )

private fun normalizedDragRegion(
    start: Offset?,
    end: Offset?,
    pageWidthPixels: Float,
    pageHeightPixels: Float,
): PdfNormalizedRect? =
    if (start == null || end == null) {
        null
    } else if (!hasDrawableArea(pageWidthPixels, pageHeightPixels)) {
        null
    } else {
        val left = minOf(start.x, end.x).coerceIn(0f, pageWidthPixels) / pageWidthPixels
        val top = minOf(start.y, end.y).coerceIn(0f, pageHeightPixels) / pageHeightPixels
        val right = maxOf(start.x, end.x).coerceIn(0f, pageWidthPixels) / pageWidthPixels
        val bottom = maxOf(start.y, end.y).coerceIn(0f, pageHeightPixels) / pageHeightPixels
        if (left < right && top < bottom) PdfNormalizedRect(left, top, right, bottom) else null
    }

private fun hasDrawableArea(
    pageWidthPixels: Float,
    pageHeightPixels: Float,
): Boolean = pageWidthPixels > 0f && pageHeightPixels > 0f

internal data class PdfTextSelectionInteraction(
    val modifier: Modifier,
    val region: PdfNormalizedRect?,
)

private const val MIN_SELECTION_DRAG_PIXELS = 4f
