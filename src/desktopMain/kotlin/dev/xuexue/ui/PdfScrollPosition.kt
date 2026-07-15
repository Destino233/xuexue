package dev.xuexue.ui

internal data class PdfScrollPosition(
    val firstVisiblePage: Int,
    val firstVisibleOffset: Int,
    val lastVisiblePage: Int,
)

internal fun pdfPrefetchTarget(
    previous: PdfScrollPosition?,
    current: PdfScrollPosition,
    pageCount: Int,
): Int? {
    if (pageCount <= 0) return null
    val direction =
        when {
            previous == null -> 1
            current.firstVisiblePage > previous.firstVisiblePage -> 1
            current.firstVisiblePage < previous.firstVisiblePage -> -1
            current.firstVisibleOffset > previous.firstVisibleOffset -> 1
            current.firstVisibleOffset < previous.firstVisibleOffset -> -1
            else -> 0
        }
    val target =
        when (direction) {
            1 -> current.lastVisiblePage + 1
            -1 -> current.firstVisiblePage - 1
            else -> null
        }
    return target?.takeIf { it in 0 until pageCount }
}

internal const val PDF_PREVIEW_DPI = 36f
internal const val PDF_PREFETCH_DELAY_MILLIS = 90L
internal const val PDF_SCROLL_PERSISTENCE_DELAY_MILLIS = 320L
