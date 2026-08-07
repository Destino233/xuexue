package dev.xuexue.ui

import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfRenderLod
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun loadPdfRasterWithRefinement(
    initialDelayMillis: Long = INITIAL_REFINEMENT_DELAY_MILLIS,
    render: suspend () -> PdfPageRaster,
    onRaster: suspend (PdfPageRaster) -> Unit,
) {
    var retryDelay = initialDelayMillis
    while (true) {
        currentCoroutineContext().ensureActive()
        val raster = render()
        onRaster(raster)
        if (!raster.incomplete) return
        delay(retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(MAX_REFINEMENT_DELAY_MILLIS)
    }
}

internal fun pdfRenderDpi(
    requestedDpi: Float,
    purpose: PdfRasterPurpose = PdfRasterPurpose.VIEWPORT,
): Float = PdfRenderLod.quantize(requestedDpi, purpose)

private const val INITIAL_REFINEMENT_DELAY_MILLIS = 120L
private const val MAX_REFINEMENT_DELAY_MILLIS = 1_500L
