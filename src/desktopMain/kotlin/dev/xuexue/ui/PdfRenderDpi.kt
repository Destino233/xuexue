package dev.xuexue.ui

import dev.xuexue.platform.PdfPageDimensions
import kotlin.math.max

/**
 * Calculates the raster resolution needed for the page pixels currently exposed by the UI.
 * PDF points are 1/72 inch, so the larger axis requirement keeps either axis from being
 * undersampled when layout constraints are temporarily not proportional to the page.
 */
internal fun pdfTargetRenderDpi(
    pageDimensions: PdfPageDimensions,
    displayedWidthPixels: Float,
    displayedHeightPixels: Float,
    rotationDegrees: Float = 0f,
): Float {
    require(pageDimensions.widthPoints > 0f && pageDimensions.heightPoints > 0f) {
        "PDF page dimensions must be positive."
    }
    require(displayedWidthPixels > 0f && displayedHeightPixels > 0f) {
        "Displayed PDF dimensions must be positive."
    }
    require(
        pageDimensions.widthPoints.isFinite() &&
            pageDimensions.heightPoints.isFinite() &&
            displayedWidthPixels.isFinite() &&
            displayedHeightPixels.isFinite() &&
            rotationDegrees.isFinite(),
    ) {
        "PDF render dimensions must be finite."
    }

    val quarterTurn = rotationDegrees % 180f != 0f
    val pageWidthPixels = if (quarterTurn) displayedHeightPixels else displayedWidthPixels
    val pageHeightPixels = if (quarterTurn) displayedWidthPixels else displayedHeightPixels
    val widthDpi = pageWidthPixels * POINTS_PER_INCH / pageDimensions.widthPoints
    val heightDpi = pageHeightPixels * POINTS_PER_INCH / pageDimensions.heightPoints
    return max(widthDpi, heightDpi)
}

internal fun pdfTargetRenderDpiOrNull(
    pageDimensions: PdfPageDimensions,
    displayedWidthPixels: Float,
    displayedHeightPixels: Float,
    rotationDegrees: Float = 0f,
): Float? =
    if (hasValidPdfRenderInput(pageDimensions, displayedWidthPixels, displayedHeightPixels, rotationDegrees)) {
        pdfTargetRenderDpi(
            pageDimensions = pageDimensions,
            displayedWidthPixels = displayedWidthPixels,
            displayedHeightPixels = displayedHeightPixels,
            rotationDegrees = rotationDegrees,
        )
    } else {
        null
    }

internal fun pdfDisplayedPageAspectRatio(
    pageDimensions: PdfPageDimensions,
    previewWidthPixels: Int?,
    previewHeightPixels: Int?,
    renderViewport: Boolean,
    rotationDegrees: Float,
): Float {
    val previewAspectRatio =
        previewWidthPixels
            ?.takeIf { width -> width > 0 }
            ?.let { width ->
                previewHeightPixels
                    ?.takeIf { height -> height > 0 }
                    ?.let { height -> width.toFloat() / height.toFloat() }
            }
    val sourceAspectRatio =
        if (renderViewport) {
            pageDimensions.widthPoints / pageDimensions.heightPoints
        } else {
            previewAspectRatio ?: DEFAULT_UNKNOWN_PDF_ASPECT_RATIO
        }
    return if (rotationDegrees % 180f != 0f) 1f / sourceAspectRatio else sourceAspectRatio
}

internal fun pdfPageRenderDpi(
    pageDimensions: PdfPageDimensions,
    displayedWidthPixels: Float,
    displayedHeightPixels: Float,
    rotationDegrees: Float,
    fallbackDpi: Float,
): Float {
    val targetDpi =
        pdfTargetRenderDpiOrNull(
            pageDimensions = pageDimensions,
            displayedWidthPixels = displayedWidthPixels,
            displayedHeightPixels = displayedHeightPixels,
            rotationDegrees = rotationDegrees,
        ) ?: fallbackDpi
    return pdfRenderDpi(targetDpi)
}

private fun hasValidPdfRenderInput(
    pageDimensions: PdfPageDimensions,
    displayedWidthPixels: Float,
    displayedHeightPixels: Float,
    rotationDegrees: Float,
): Boolean =
    pageDimensions.hasPositiveFiniteDimensions() &&
        hasPositiveFiniteDisplayedDimensions(displayedWidthPixels, displayedHeightPixels) &&
        rotationDegrees.isFinite()

private fun hasPositiveFiniteDisplayedDimensions(
    widthPixels: Float,
    heightPixels: Float,
): Boolean = widthPixels.isPositiveFinite() && heightPixels.isPositiveFinite()

private fun PdfPageDimensions.hasPositiveFiniteDimensions(): Boolean =
    if (!widthPoints.isPositiveFinite()) {
        false
    } else {
        heightPoints.isPositiveFinite()
    }

private fun Float.isPositiveFinite(): Boolean = isFinite() && this > 0f

internal const val DEFAULT_PDF_FALLBACK_DPI = 96f
private const val POINTS_PER_INCH = 72f
private const val DEFAULT_UNKNOWN_PDF_ASPECT_RATIO = 1f
