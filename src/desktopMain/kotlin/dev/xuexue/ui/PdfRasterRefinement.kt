package dev.xuexue.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
internal fun rememberPdfRenderDpi(
    resetKey: Any?,
    requestedDpi: Float,
    purpose: PdfRasterPurpose = PdfRasterPurpose.VIEWPORT,
): Float {
    var renderDpi by remember(resetKey, purpose) { mutableStateOf(PdfRenderLod.quantize(requestedDpi, purpose)) }
    LaunchedEffect(resetKey, requestedDpi, purpose) {
        delay(PDF_LOD_SETTLE_DELAY_MILLIS)
        renderDpi = PdfRenderLod.quantizeWithHysteresis(requestedDpi, purpose, renderDpi)
    }
    return renderDpi
}

private const val INITIAL_REFINEMENT_DELAY_MILLIS = 120L
private const val MAX_REFINEMENT_DELAY_MILLIS = 1_500L
private const val PDF_LOD_SETTLE_DELAY_MILLIS = 160L
