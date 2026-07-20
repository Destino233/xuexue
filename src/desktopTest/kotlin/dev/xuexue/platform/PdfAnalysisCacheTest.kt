package dev.xuexue.platform

import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdfAnalysisCacheTest {
    @Test
    fun cacheRoundTripIsPageScopedAndIdentityAware() {
        val root = Files.createTempDirectory("xuexue-analysis-cache")
        try {
            val cache = PdfAnalysisCache(root)
            val key = cacheKey()
            val layout =
                PdfPageLayout(
                    pageIndex = 2,
                    width = 100,
                    height = 100,
                    blocks = emptyList(),
                    engine = AnalysisProvenance("pp-structure-v3", "test", modelVersion = "model-1"),
                )

            cache.savePage(key, layout)

            assertEquals(layout, cache.loadPage(key, 2))
            assertNull(cache.loadPage(key, 1))
            assertNull(cache.loadPage(key.copy(dpi = 301), 2))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun corruptedManifestOrPageIsIgnoredAndCanBeRebuilt() {
        val root = Files.createTempDirectory("xuexue-analysis-cache-recovery")
        try {
            val cache = PdfAnalysisCache(root)
            val key = cacheKey()
            val page =
                PdfPageLayout(
                    pageIndex = 2,
                    width = 100,
                    height = 100,
                    blocks = emptyList(),
                    engine = AnalysisProvenance("pp-structure-v3", "test", modelVersion = "model-1"),
                )
            val directory = root.resolve(key.cacheDirectoryName())
            val manifest = directory.resolve("manifest.json")
            val pageFile = directory.resolve("pages/000002.json")

            cache.savePage(key, page)
            Files.writeString(pageFile, "{not-json")
            assertNull(cache.loadPage(key, page.pageIndex))

            cache.savePage(key, page)
            assertEquals(page, cache.loadPage(key, page.pageIndex))

            Files.writeString(manifest, "{not-json")
            assertNull(cache.loadPage(key, page.pageIndex))
            cache.savePage(key, page)
            assertEquals(page, cache.loadPage(key, page.pageIndex))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun cacheKey(): PdfAnalysisCacheKey =
        PdfAnalysisCacheKey(
            documentFingerprint = "0".repeat(64),
            fileSize = 1024,
            pageCount = 4,
            engineId = "pp-structure-v3",
            engineVersion = "3.x-sidecar",
            modelVersion = "model-1",
            dpi = 300,
            language = "chi_sim,en",
        )
}
