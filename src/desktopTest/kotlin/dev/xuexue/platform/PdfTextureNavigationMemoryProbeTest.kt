package dev.xuexue.platform

import androidx.compose.ui.graphics.ImageBitmap
import dev.xuexue.ui.releaseSkiaResources
import dev.xuexue.ui.toComposeBitmap
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.test.Test

/**
 * Optional local probe for the full interactive texture path: raster cache,
 * Skia bitmap preparation, texture cache leases, and tile lifecycle churn
 * across zoom levels and scrolling (mirrors rememberPdfTexture).
 *
 * Environment: XUEXUE_PDF_TEXTURE_PROBE_PATH / MARKER / ROUNDS / ZOOMS.
 */
class PdfTextureNavigationMemoryProbeTest {
    private var textureBytes = 0L
    private var texturePeak = 0L
    private var metrics = PdfPerformanceMetrics()

    @Test
    // One linear pass mirrors the interactive tile lifecycle; splitting would hide the churn pattern.
    @Suppress("LongMethod")
    fun repeatedZoomAndScrollKeepsTheInteractiveTexturePathBounded() =
        runBlocking {
            val path =
                probeSetting("PATH")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: return@runBlocking
            if (!Files.isRegularFile(path)) return@runBlocking

            val marker = probeSetting("MARKER")?.takeIf(String::isNotBlank)?.let(Path::of)
            val rounds = (probeSetting("ROUNDS", "2") ?: "2").toInt().coerceIn(1, 10)
            val zooms =
                (probeSetting("ZOOMS", "1.0,2.0,4.0") ?: "1.0,2.0,4.0")
                    .split(',')
                    .mapNotNull { value -> value.trim().toFloatOrNull() }
                    .filter { it > 0f }
            metrics = PdfPerformanceMetrics()
            val backend =
                CachingPdfReaderBackend(
                    MuPdfReaderBackend(metrics),
                    metrics = metrics,
                )
            val textureCache =
                PdfTextureCache<ImageBitmap>(
                    onBytesChanged = { current, peak ->
                        textureBytes = current
                        texturePeak = maxOf(texturePeak, peak)
                    },
                )
            try {
                val pageCount = backend.inspect(path).pageCount
                val dimensions = List(pageCount) { page -> backend.pageDimensions(path, page) }
                writeMarker(marker, "started", pageCount)
                repeat(rounds) { round ->
                    zooms.forEach { zoom ->
                        val held = mutableMapOf<LeaseKey, PdfTextureLease<ImageBitmap>>()
                        repeat(pageCount) { page ->
                            val pageDimensions = dimensions[page]
                            val displayedWidth = 680f * zoom
                            val dpi =
                                PdfRenderLod.quantize(
                                    displayedWidth * POINTS_PER_INCH / pageDimensions.widthPoints,
                                    PdfRasterPurpose.VIEWPORT,
                                )
                            val columns = tileCount(pageDimensions.widthPoints, dpi)
                            val rows = tileCount(pageDimensions.heightPoints, dpi)
                            val firstRow = (rows / 2 - 1).coerceAtLeast(0)
                            val lastRow = (rows / 2 + 1).coerceAtMost(rows - 1)
                            for (row in firstRow..lastRow) {
                                for (column in 0 until columns) {
                                    val viewport = viewport(column, row, columns, rows)
                                    val key =
                                        PdfTextureKey(
                                            documentPath = path.toString(),
                                            revision = 0L,
                                            pageIndex = page,
                                            dpi = dpi,
                                            purpose = PdfRasterPurpose.VIEWPORT,
                                            viewport = viewport,
                                        )
                                    if (held.remove(LeaseKey(page, column, row)) == null) {
                                        val raster =
                                            backend.render(
                                                PdfRasterRequest(
                                                    path = path,
                                                    pageIndex = page,
                                                    dpi = dpi,
                                                    purpose = PdfRasterPurpose.VIEWPORT,
                                                    viewport = viewport,
                                                ),
                                            )
                                        val lease =
                                            textureCache.acquire(
                                                key = key,
                                                priority = PdfRasterPriority.VISIBLE,
                                                expectedBytes = raster.byteSize,
                                            ) {
                                                val bitmap = raster.toComposeBitmap(metrics)
                                                PreparedPdfTexture(
                                                    value = bitmap,
                                                    byteSize = raster.byteSize,
                                                    onClose = bitmap::releaseSkiaResources,
                                                )
                                            }
                                        held[LeaseKey(page, column, row)] = lease
                                    }
                                }
                            }
                            held.keys
                                .filter { candidate -> candidate.page < page - 1 }
                                .forEach { candidate -> held.remove(candidate)?.close() }
                            if (page % 4 == 0) writeMarker(marker, "round-$round-page-$page", pageCount)
                        }
                        held.values.forEach { lease -> lease.close() }
                    }
                    writeMarker(marker, "round-$round", pageCount)
                }
                writeMarker(marker, "finished", pageCount)
            } finally {
                textureCache.close()
                backend.close()
            }
        }

    private fun writeMarker(
        marker: Path?,
        phase: String,
        pageCount: Int,
    ) {
        marker ?: return
        Files.createDirectories(checkNotNull(marker.parent))
        val runtime = Runtime.getRuntime()
        Files.writeString(
            marker,
            "phase=$phase\npid=" + ProcessHandle.current().pid() + "\npages=$pageCount\n" +
                "heapUsed=" + (runtime.totalMemory() - runtime.freeMemory()) + "\n" +
                "heapCommitted=" + runtime.totalMemory() + "\n" +
                "heapMax=" + runtime.maxMemory() + "\n" +
                "textureBytes=$textureBytes\ntexturePeak=$texturePeak\n" +
                "rasterCacheBytes=" + metrics.snapshot().cacheBytes + "\n" +
                "texturePreparations=" + metrics.snapshot().texturePreparations + "\n" +
                "textureEvictions=" + metrics.snapshot().textureCacheEvictions + "\n",
        )
    }

    private fun probeSetting(
        name: String,
        default: String? = null,
    ): String? = System.getenv("XUEXUE_PDF_TEXTURE_PROBE_$name") ?: default

    private fun tileCount(
        points: Float,
        dpi: Float,
    ): Int = ceil(points * dpi / POINTS_PER_INCH / TARGET_TILE_PIXELS).toInt().coerceIn(1, MAX_TILES_PER_AXIS)

    private fun viewport(
        column: Int,
        row: Int,
        columns: Int,
        rows: Int,
    ): PdfNormalizedRect =
        PdfNormalizedRect(
            left = column.toFloat() / columns,
            top = row.toFloat() / rows,
            right = (column + 1).toFloat() / columns,
            bottom = (row + 1).toFloat() / rows,
        )

    private data class LeaseKey(
        val page: Int,
        val column: Int,
        val row: Int,
    )

    private companion object {
        const val MAX_TILES_PER_AXIS = 16
        const val POINTS_PER_INCH = 72f
        const val TARGET_TILE_PIXELS = 1_024f
    }
}
