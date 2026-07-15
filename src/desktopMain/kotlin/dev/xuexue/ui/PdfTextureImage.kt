@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.xuexue.DocumentTab
import dev.xuexue.WorkbenchState
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfPerformanceMetrics
import dev.xuexue.platform.PdfRasterLayer
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfTextureKey
import dev.xuexue.platform.PdfTextureLease
import dev.xuexue.platform.PreparedPdfTexture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

internal data class PdfTextureImage(
    val bitmap: ImageBitmap?,
    val failed: Boolean,
)

@Composable
internal fun rememberPdfTexture(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    dpi: Float,
    purpose: PdfRasterPurpose,
    viewport: PdfNormalizedRect? = null,
    priority: PdfRasterPriority,
    layer: PdfRasterLayer = PdfRasterLayer.CONTENT,
): PdfTextureImage {
    val documentPath =
        state.workspaceRoot
            ?.resolve(tab.relativePath)
            ?.normalize()
            ?.toString() ?: tab.relativePath
    val key =
        remember(documentPath, tab.lastKnownModified, page, dpi, purpose, viewport, layer) {
            PdfTextureKey(
                documentPath = documentPath,
                revision = tab.lastKnownModified,
                pageIndex = page,
                dpi = dpi,
                purpose = purpose,
                viewport = viewport,
                layer = layer,
            )
        }
    val activeTexture =
        remember(documentPath, tab.lastKnownModified, page, purpose, viewport, layer) {
            mutableStateOf<ActivePdfTexture?>(null)
        }
    var failed by remember(documentPath, tab.lastKnownModified, page, purpose, viewport, layer) {
        mutableStateOf(false)
    }
    DisposableEffect(activeTexture) {
        onDispose {
            activeTexture.value?.lease?.close()
            activeTexture.value = null
        }
    }
    LaunchedEffect(key, priority) {
        failed = false
        try {
            if (activeTexture.value?.key == key) return@LaunchedEffect
            state.pdfTextureCache.acquireIfPresent(key, priority)?.let { cached ->
                val previous = activeTexture.value
                activeTexture.value = ActivePdfTexture(key, cached)
                previous?.lease?.close()
                return@LaunchedEffect
            }
            loadPdfRasterWithRefinement(
                render = { state.renderPdfPage(tab, page, dpi, purpose, viewport, priority, layer) },
                onRaster = { raster ->
                    val nextLease =
                        if (raster.incomplete) {
                            PdfTextureLease.uncached(raster.toComposeBitmap(state.pdfPerformanceMetrics))
                        } else {
                            state.pdfTextureCache.acquire(key, priority) {
                                PreparedPdfTexture(
                                    value = raster.toComposeBitmap(state.pdfPerformanceMetrics),
                                    byteSize = raster.byteSize,
                                )
                            }
                        }
                    var adopted = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val previous = activeTexture.value
                        activeTexture.value = ActivePdfTexture(key, nextLease)
                        previous?.lease?.close()
                        adopted = true
                    } finally {
                        if (!adopted) nextLease.close()
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failed = activeTexture.value == null
        }
    }
    return PdfTextureImage(activeTexture.value?.lease?.value, failed)
}

private data class ActivePdfTexture(
    val key: PdfTextureKey,
    val lease: PdfTextureLease<ImageBitmap>,
)

internal fun PdfPageRaster.toComposeBitmap(metrics: PdfPerformanceMetrics? = null): ImageBitmap {
    val startedAt = System.nanoTime()
    return SkiaImage
        .makeRaster(
            ImageInfo(pixelWidth, pixelHeight, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
            rgbaBytes,
            pixelWidth * RGBA_BYTES_PER_PIXEL,
        ).toComposeImageBitmap()
        .also { metrics?.textures?.recordPreparation(byteSize, System.nanoTime() - startedAt) }
}

private const val RGBA_BYTES_PER_PIXEL = 4
