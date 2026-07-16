@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint
import kotlin.math.abs

internal data class PdfTextSelectionInteraction(
    val onStart: () -> Unit,
    val onChange: (PdfPagePoint, PdfPagePoint, isFinal: Boolean) -> Unit,
    val onCancel: () -> Unit,
)

@Composable
internal fun rememberPdfSelectionGesture(
    page: Int,
    pageDimensions: PdfPageDimensions,
    pageWidthPixels: Float,
    pageHeightPixels: Float,
    interaction: PdfTextSelectionInteraction?,
): Modifier {
    val currentInteraction by rememberUpdatedState(interaction)
    if (interaction == null) return Modifier
    return Modifier
        .pointerHoverIcon(PointerIcon.Text)
        .pointerInput(page, pageWidthPixels, pageHeightPixels) {
            var dragStart: Offset? = null
            var dragEnd: Offset? = null
            var lastPreviewUptimeMillis = Long.MIN_VALUE
            detectDragGestures(
                onDragStart = { position ->
                    dragStart = position
                    dragEnd = position
                    lastPreviewUptimeMillis = Long.MIN_VALUE
                    currentInteraction?.onStart?.invoke()
                },
                onDragCancel = {
                    currentInteraction?.onCancel?.invoke()
                    dragStart = null
                    dragEnd = null
                },
                onDragEnd = {
                    val dispatched =
                        dispatchSelection(
                            start = dragStart,
                            end = dragEnd,
                            pageWidthPixels = pageWidthPixels,
                            pageHeightPixels = pageHeightPixels,
                            pageDimensions = pageDimensions,
                        ) { start, end -> currentInteraction?.onChange?.invoke(start, end, true) }
                    if (!dispatched) currentInteraction?.onCancel?.invoke()
                    dragStart = null
                    dragEnd = null
                },
                onDrag = { change, _ ->
                    val start = dragStart
                    dragEnd = change.position
                    if (
                        start != null &&
                        shouldDispatchPdfSelectionPreview(
                            dragDistance = (change.position - start).getDistance(),
                            eventUptimeMillis = change.uptimeMillis,
                            lastDispatchUptimeMillis = lastPreviewUptimeMillis,
                        )
                    ) {
                        dispatchSelection(
                            start = start,
                            end = change.position,
                            pageWidthPixels = pageWidthPixels,
                            pageHeightPixels = pageHeightPixels,
                            pageDimensions = pageDimensions,
                        ) { selectionStart, selectionEnd ->
                            currentInteraction?.onChange?.invoke(selectionStart, selectionEnd, false)
                        }
                        lastPreviewUptimeMillis = change.uptimeMillis
                    }
                    change.consume()
                },
            )
        }
}

private fun dispatchSelection(
    start: Offset?,
    end: Offset?,
    pageWidthPixels: Float,
    pageHeightPixels: Float,
    pageDimensions: PdfPageDimensions,
    onSelection: (PdfPagePoint, PdfPagePoint) -> Unit,
): Boolean {
    if (start == null || end == null || (end - start).getDistance() < MIN_SELECTION_DRAG_PIXELS) return false
    onSelection(
        start.toPdfPagePoint(pageWidthPixels, pageHeightPixels, pageDimensions),
        end.toPdfPagePoint(pageWidthPixels, pageHeightPixels, pageDimensions),
    )
    return true
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

internal fun shouldDispatchPdfSelectionPreview(
    dragDistance: Float,
    eventUptimeMillis: Long,
    lastDispatchUptimeMillis: Long,
): Boolean =
    when {
        dragDistance < MIN_SELECTION_DRAG_PIXELS -> false
        lastDispatchUptimeMillis == Long.MIN_VALUE -> true
        else -> abs(eventUptimeMillis - lastDispatchUptimeMillis) >= LIVE_SELECTION_INTERVAL_MILLIS
    }

private const val MIN_SELECTION_DRAG_PIXELS = 4f
private const val LIVE_SELECTION_INTERVAL_MILLIS = 24L
