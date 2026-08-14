@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.xuexue.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.xuexue.platform.DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER
import dev.xuexue.platform.normalizedDocumentWheelZoomMultiplier
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

internal const val MIN_PDF_ZOOM = 0.4f
internal const val MAX_PDF_ZOOM = 4f
internal const val PDF_ZOOM_PERSISTENCE_DELAY_MILLIS = 220L
internal val PDF_CTRL_WHEEL_EVENT_PASS = PointerEventPass.Initial
private const val MIN_ADAPTIVE_ZOOM_SENSITIVITY = 0.085f
private const val MAX_ADAPTIVE_ZOOM_SENSITIVITY = 0.14f
private const val ACCELERATED_SCROLL_COMPRESSION = 0.35f
private const val MAX_WHEEL_ZOOM_EXPONENT = 0.4f

internal fun Modifier.pdfCtrlWheelZoom(onZoom: (verticalScroll: Float, position: Offset) -> Unit): Modifier =
    composed {
        val currentOnZoom by rememberUpdatedState(onZoom)
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PDF_CTRL_WHEEL_EVENT_PASS)
                    if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                        event.changes.firstOrNull { it.scrollDelta != Offset.Zero }?.let { change ->
                            if (change.scrollDelta.y != 0f) {
                                currentOnZoom(change.scrollDelta.y, change.position)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
    }

internal fun pdfZoomForScroll(
    currentZoom: Float,
    verticalScroll: Float,
    wheelZoomMultiplier: Float = DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER,
): Float {
    val zoom = currentZoom.coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
    if (!verticalScroll.isFinite() || verticalScroll == 0f) return zoom
    val zoomProgress = ((zoom - MIN_PDF_ZOOM) / (MAX_PDF_ZOOM - MIN_PDF_ZOOM)).coerceIn(0f, 1f)
    val sensitivity =
        MAX_ADAPTIVE_ZOOM_SENSITIVITY -
            (MAX_ADAPTIVE_ZOOM_SENSITIVITY - MIN_ADAPTIVE_ZOOM_SENSITIVITY) * sqrt(zoomProgress)
    val scrollMagnitude = abs(verticalScroll)
    val adaptiveScrollUnits =
        if (scrollMagnitude <= 1f) {
            scrollMagnitude
        } else {
            1f + ln(scrollMagnitude.toDouble()).toFloat() * ACCELERATED_SCROLL_COMPRESSION
        }
    val exponent =
        (
            -sign(verticalScroll) *
                adaptiveScrollUnits *
                sensitivity *
                normalizedDocumentWheelZoomMultiplier(wheelZoomMultiplier)
        ).coerceIn(-MAX_WHEEL_ZOOM_EXPONENT, MAX_WHEEL_ZOOM_EXPONENT)
    return (zoom * exp(exponent.toDouble()).toFloat()).coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
}

internal fun zoomedScrollOffset(
    currentOffset: Int,
    anchorPosition: Float,
    zoomRatio: Float,
): Int = ((currentOffset + anchorPosition) * zoomRatio - anchorPosition).roundToInt().coerceAtLeast(0)

internal fun zoomedPageScrollOffset(
    currentOffset: Int,
    anchorPosition: Float,
    sourcePageWidth: Float,
    targetPageWidth: Float,
    viewportWidth: Float,
    contentPadding: Float = 0f,
): Int {
    if (
        !sourcePageWidth.isValidZoomExtent() ||
        !targetPageWidth.isValidZoomExtent() ||
        !viewportWidth.isValidZoomExtent()
    ) {
        return currentOffset.coerceAtLeast(0)
    }
    val availableViewportWidth = (viewportWidth - contentPadding * 2f).coerceAtLeast(0f)
    val sourcePageOrigin = contentPadding + (availableViewportWidth - sourcePageWidth).coerceAtLeast(0f) / 2f
    val targetPageOrigin = contentPadding + (availableViewportWidth - targetPageWidth).coerceAtLeast(0f) / 2f
    val sourcePagePosition = currentOffset + anchorPosition - sourcePageOrigin
    return (
        sourcePagePosition * targetPageWidth / sourcePageWidth +
            targetPageOrigin -
            anchorPosition
    ).roundToInt().coerceAtLeast(0)
}

private fun Float.isValidZoomExtent(): Boolean = isFinite() && this > 0f
