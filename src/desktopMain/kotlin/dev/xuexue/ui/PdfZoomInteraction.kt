@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.xuexue.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlin.math.exp
import kotlin.math.roundToInt

internal const val MIN_PDF_ZOOM = 0.4f
internal const val MAX_PDF_ZOOM = 4f
internal const val PDF_ZOOM_PERSISTENCE_DELAY_MILLIS = 220L
private const val PDF_WHEEL_ZOOM_SENSITIVITY = 0.015f
private const val MAX_WHEEL_ZOOM_EXPONENT = 0.08f

internal fun Modifier.pdfCtrlWheelZoom(onZoom: (verticalScroll: Float, position: Offset) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Scroll) { event ->
        if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        if (change.scrollDelta.y == 0f) return@onPointerEvent
        onZoom(change.scrollDelta.y, change.position)
        change.consume()
    }

internal fun pdfZoomForScroll(
    currentZoom: Float,
    verticalScroll: Float,
): Float {
    if (!verticalScroll.isFinite() || verticalScroll == 0f) return currentZoom.coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
    val exponent =
        (-verticalScroll * PDF_WHEEL_ZOOM_SENSITIVITY)
            .coerceIn(-MAX_WHEEL_ZOOM_EXPONENT, MAX_WHEEL_ZOOM_EXPONENT)
    return (currentZoom * exp(exponent.toDouble()).toFloat()).coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
}

internal fun zoomedScrollOffset(
    currentOffset: Int,
    anchorPosition: Float,
    zoomRatio: Float,
): Int = ((currentOffset + anchorPosition) * zoomRatio - anchorPosition).roundToInt().coerceAtLeast(0)
