package dev.xuexue.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.test.Test

/** Optional local probe for comparing managed raster bounds with native MuPDF growth. */
class PdfNativeMemoryProbeTest {
    @Test
    fun repeatedTiledNavigationKeepsTheConfiguredRasterPathBounded() =
        runBlocking {
            val path =
                probeSetting("PATH")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: return@runBlocking
            if (!Files.isRegularFile(path)) return@runBlocking

            val marker =
                probeSetting("MARKER")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
            val rounds = (probeSetting("ROUNDS", "2") ?: "2").toInt().coerceIn(1, 10)
            val holdMillis = (probeSetting("HOLD_MILLIS", "0") ?: "0").toLong().coerceAtLeast(0L)
            val backend = CachingPdfReaderBackend(MuPdfReaderBackend())
            try {
                val pageCount = backend.inspect(path).pageCount
                writeMarker(marker, "started", pageCount)
                repeat(rounds) { round ->
                    listOf(96f, 192f, 384f, 576f, 960f).forEach { dpi ->
                        repeat(pageCount) { page ->
                            val dimensions = backend.pageDimensions(path, page)
                            val columns = tileCount(dimensions.widthPoints, dpi)
                            val rows = tileCount(dimensions.heightPoints, dpi)
                            repeat(rows) { row ->
                                repeat(columns) { column ->
                                    backend.render(
                                        PdfRasterRequest(
                                            path = path,
                                            pageIndex = page,
                                            dpi = dpi,
                                            purpose = PdfRasterPurpose.VIEWPORT,
                                            viewport = viewport(column, row, columns, rows),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    writeMarker(marker, "round-$round", pageCount)
                }
                writeMarker(marker, "finished", pageCount)
                if (holdMillis > 0L) delay(holdMillis)
            } finally {
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
        Files.writeString(
            marker,
            "phase=$phase\npid=${ProcessHandle.current().pid()}\npages=$pageCount\n" +
                "heapUsed=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}\n",
        )
    }

    private fun probeSetting(
        name: String,
        default: String? = null,
    ): String? = System.getenv("XUEXUE_PDF_MEMORY_PROBE_$name") ?: default

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

    private companion object {
        const val MAX_TILES_PER_AXIS = 16
        const val POINTS_PER_INCH = 72f
        const val TARGET_TILE_PIXELS = 1_024f
    }
}
