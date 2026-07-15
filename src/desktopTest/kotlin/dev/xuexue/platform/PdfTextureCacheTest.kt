package dev.xuexue.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTextureCacheTest {
    @Test
    fun pinnedVisibleTextureSurvivesWhileBackgroundTextureIsEvicted() =
        runBlocking {
            val cache = PdfTextureCache<String>(maxBytes = TEXTURE_BYTES.toLong())
            val visible = cache.acquire(key(page = 0), PdfRasterPriority.VISIBLE) { texture("visible") }
            val background = cache.acquire(key(page = 1), PdfRasterPriority.BACKGROUND) { texture("background") }

            background.close()
            assertEquals("visible", cache.acquireIfPresent(key(page = 0), PdfRasterPriority.VISIBLE)?.use { it.value })
            assertNull(cache.acquireIfPresent(key(page = 1), PdfRasterPriority.BACKGROUND))
            visible.close()
            cache.close()
        }

    @Test
    fun concurrentAcquisitionPreparesOneTexture() =
        runBlocking {
            val cache = PdfTextureCache<String>()
            var preparations = 0
            val first =
                async {
                    cache.acquire(key(page = 2), PdfRasterPriority.ADJACENT) {
                        preparations++
                        texture("shared")
                    }
                }
            val second =
                async {
                    cache.acquire(key(page = 2), PdfRasterPriority.VISIBLE) {
                        preparations++
                        texture("duplicate")
                    }
                }

            assertEquals("shared", first.await().use { it.value })
            assertEquals("shared", second.await().use { it.value })
            assertEquals(1, preparations)
            cache.close()
        }

    @Test
    fun reportsCurrentAndPeakBytesWhenAnUnpinnedTextureIsEvicted() =
        runBlocking {
            val byteReports = mutableListOf<Pair<Long, Long>>()
            var evictions = 0
            val cache =
                PdfTextureCache<String>(
                    maxBytes = TEXTURE_BYTES.toLong(),
                    onBytesChanged = { current, peak -> byteReports += current to peak },
                    onEviction = { evictions++ },
                )
            val visible = cache.acquire(key(page = 3), PdfRasterPriority.VISIBLE) { texture("visible") }
            val background = cache.acquire(key(page = 4), PdfRasterPriority.BACKGROUND) { texture("background") }

            assertTrue(byteReports.contains(TEXTURE_BYTES * 2L to TEXTURE_BYTES * 2L))
            background.close()
            assertEquals(TEXTURE_BYTES.toLong() to TEXTURE_BYTES * 2L, byteReports.last())
            assertEquals(1, evictions)

            visible.close()
            cache.close()
            assertEquals(0L to TEXTURE_BYTES * 2L, byteReports.last())
        }

    private fun key(page: Int): PdfTextureKey =
        PdfTextureKey(
            documentPath = "notes/test.pdf",
            revision = 1L,
            pageIndex = page,
            dpi = 96f,
            purpose = PdfRasterPurpose.VIEWPORT,
            viewport = null,
        )

    private fun texture(value: String): PreparedPdfTexture<String> = PreparedPdfTexture(value, TEXTURE_BYTES)

    private companion object {
        const val TEXTURE_BYTES = 16
    }
}
