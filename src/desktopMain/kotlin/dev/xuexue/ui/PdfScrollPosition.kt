package dev.xuexue.ui

internal data class PdfScrollPosition(
    val firstVisiblePage: Int,
    val firstVisibleOffset: Int,
    val lastVisiblePage: Int,
)

internal enum class PdfScrollDirection {
    FORWARD,
    BACKWARD,
}

internal data class PdfPrefetchSnapshot(
    val position: PdfScrollPosition,
    val isScrollInProgress: Boolean,
)

internal fun pdfScrollDirection(
    previous: PdfScrollPosition?,
    current: PdfScrollPosition,
): PdfScrollDirection? =
    when {
        previous == null -> PdfScrollDirection.FORWARD
        current.firstVisiblePage > previous.firstVisiblePage -> PdfScrollDirection.FORWARD
        current.firstVisiblePage < previous.firstVisiblePage -> PdfScrollDirection.BACKWARD
        current.firstVisibleOffset > previous.firstVisibleOffset -> PdfScrollDirection.FORWARD
        current.firstVisibleOffset < previous.firstVisibleOffset -> PdfScrollDirection.BACKWARD
        else -> null
    }

internal fun pdfPrefetchTarget(
    previous: PdfScrollPosition?,
    current: PdfScrollPosition,
    pageCount: Int,
): Int? {
    val direction = pdfScrollDirection(previous, current)
    return if (pageCount > 0 && direction != null) {
        pdfPrefetchTargetForDirection(current, direction, pageCount)
    } else {
        null
    }
}

internal fun pdfPrefetchTargetForDirection(
    current: PdfScrollPosition,
    direction: PdfScrollDirection,
    pageCount: Int,
): Int? {
    if (pageCount <= 0) return null
    val target =
        when (direction) {
            PdfScrollDirection.FORWARD -> current.lastVisiblePage + 1
            PdfScrollDirection.BACKWARD -> current.firstVisiblePage - 1
        }
    return target.takeIf { it in 0 until pageCount }
}

internal const val PDF_PREVIEW_DPI = 36f
internal const val PDF_PREFETCH_DELAY_MILLIS = 140L
internal const val PDF_SCROLL_PERSISTENCE_DELAY_MILLIS = 320L
