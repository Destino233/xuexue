package dev.xuexue.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the page-level persistence boundary with a 1,001-page deterministic layout fixture. */
class PdfLongDocumentBenchmarkTest {
    @Test
    fun pageCacheAndLayoutStoreRemainRecoverableForLongDocuments() {
        val pageCount = System.getProperty("xuexue.pdf.benchmark.pages", "1001").toInt()
        require(pageCount >= 1_000) { "The long-document benchmark must contain at least 1,000 pages." }
        val reportPath =
            System
                .getProperty("xuexue.pdf.benchmark.report")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
        val storeRoot = Files.createTempDirectory("xuexue-long-layout-benchmark")
        val cacheRoot = Files.createTempDirectory("xuexue-long-cache-benchmark")
        val store = PdfPageLayoutStore(storeRoot)
        val cache = PdfAnalysisCache(cacheRoot)
        val key =
            PdfAnalysisCacheKey(
                documentFingerprint = "0".repeat(64),
                fileSize = pageCount.toLong() * 4096,
                pageCount = pageCount,
                engineId = "native-pdf-layout",
                engineVersion = "benchmark",
                modelVersion = null,
                dpi = 300,
                language = "en",
            )
        try {
            val split = pageCount / 2
            val writeStarted = System.nanoTime()
            repeat(split) { page ->
                val value = layout(page)
                store.write(value)
                cache.savePage(key, value)
            }
            val firstWritten = countPages(store.source())
            for (page in split until pageCount) {
                val value = layout(page)
                store.write(value)
                cache.savePage(key, value)
            }
            val writeMs = ((System.nanoTime() - writeStarted) / 1_000_000.0).roundToLong()

            val readStarted = System.nanoTime()
            val restoredPages = countPages(store.source())
            val cacheHits = (0 until pageCount).count { page -> cache.loadPage(key, page) != null }
            val readMs = ((System.nanoTime() - readStarted) / 1_000_000.0).roundToLong()

            assertEquals(split, firstWritten)
            assertEquals(pageCount, restoredPages)
            assertEquals(pageCount, cacheHits)
            assertTrue(Files.isRegularFile(cacheRoot.resolve(key.cacheDirectoryName()).resolve("manifest.json")))
            reportPath?.let { path ->
                Files.createDirectories(checkNotNull(path.parent))
                Files.writeString(
                    path,
                    Json { prettyPrint = true }.encodeToString(
                        LongDocumentBenchmarkReport(
                            fixtureKind = "deterministic-page-layout",
                            pageCount = pageCount,
                            resumedAfterPage = split,
                            restoredPages = restoredPages,
                            cacheHits = cacheHits,
                            writeMs = writeMs,
                            readMs = readMs,
                            peakWorkerRssBytes = peakWorkerRssBytes(),
                        ),
                    ),
                )
            }
        } finally {
            store.close()
            storeRoot.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    private fun countPages(source: PdfPageLayoutSource): Int {
        var count = 0
        source.forEachPage { count++ }
        return count
    }

    private fun layout(pageIndex: Int): PdfPageLayout =
        PdfPageLayout(
            pageIndex = pageIndex,
            width = 612,
            height = 792,
            blocks = emptyList(),
            engine = AnalysisProvenance("native-pdf-layout", "benchmark"),
        )

    private fun peakWorkerRssBytes(): Long {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return 0L
        return Regex("^VmHWM:\\s+(\\d+) kB$", RegexOption.MULTILINE)
            .find(Files.readString(status))
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.times(1024)
            ?: 0L
    }

    @Serializable
    private data class LongDocumentBenchmarkReport(
        val fixtureKind: String,
        val pageCount: Int,
        val resumedAfterPage: Int,
        val restoredPages: Int,
        val cacheHits: Int,
        val writeMs: Long,
        val readMs: Long,
        val peakWorkerRssBytes: Long,
    )
}
