package dev.xuexue.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTextureCacheTest {
    @Test
    fun pinnedVisibleTextureSurvivesWhileBackgroundTextureIsEvicted() =
        runBlocking {
            val cache = PdfTextureCache<String>(maxBytes = (TEXTURE_BYTES * 2).toLong())
            val visible = cache.acquire(key(page = 0), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) { texture("visible") }
            val background =
                cache.acquire(key(page = 1), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                    texture("background")
                }

            background.close()
            val replacement =
                cache.acquire(key(page = 2), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                    texture("replacement")
                }
            assertEquals("visible", cache.acquireIfPresent(key(page = 0), PdfRasterPriority.VISIBLE)?.use { it.value })
            assertNull(cache.acquireIfPresent(key(page = 1), PdfRasterPriority.BACKGROUND))
            visible.close()
            replacement.close()
            cache.close()
        }

    @Test
    fun concurrentAcquisitionPreparesOneTexture() =
        runBlocking {
            val cache = PdfTextureCache<String>()
            var preparations = 0
            val first =
                async {
                    cache.acquire(key(page = 2), PdfRasterPriority.ADJACENT, TEXTURE_BYTES) {
                        preparations++
                        texture("shared")
                    }
                }
            val second =
                async {
                    cache.acquire(key(page = 2), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) {
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
                    maxBytes = (TEXTURE_BYTES * 2).toLong(),
                    onBytesChanged = { current, peak -> byteReports += current to peak },
                    onEviction = { evictions++ },
                )
            val visible = cache.acquire(key(page = 3), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) { texture("visible") }
            val background =
                cache.acquire(key(page = 4), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                    texture("background")
                }

            assertTrue(byteReports.contains(TEXTURE_BYTES * 2L to TEXTURE_BYTES * 2L))
            background.close()
            assertEquals(TEXTURE_BYTES * 2L to TEXTURE_BYTES * 2L, byteReports.last())
            val replacement =
                cache.acquire(key(page = 5), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                    texture("replacement")
                }
            assertEquals(1, evictions)

            visible.close()
            replacement.close()
            cache.close()
            assertEquals(0L to TEXTURE_BYTES * 2L, byteReports.last())
        }

    @Test
    fun disposesTexturesWhenTheyAreEvictedOrReleasedAfterCacheClose() =
        runBlocking {
            var disposed = 0
            val cache = PdfTextureCache<String>(maxBytes = (TEXTURE_BYTES * 2).toLong())
            val first =
                cache.acquire(key(page = 9), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                    PreparedPdfTexture("first", TEXTURE_BYTES) { disposed++ }
                }
            first.close()
            val second =
                cache.acquire(key(page = 10), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) {
                    PreparedPdfTexture("second", TEXTURE_BYTES) { disposed++ }
                }
            val third =
                cache.acquire(key(page = 11), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) {
                    PreparedPdfTexture("third", TEXTURE_BYTES) { disposed++ }
                }
            assertEquals(1, disposed)

            cache.close()
            assertEquals(1, disposed)
            second.close()
            third.close()
            assertEquals(3, disposed)
        }

    @Test
    fun pendingPreparationReservesCapacityAndUncachedAdmissionReleasesOnCancellation() =
        runBlocking {
            val reports = mutableListOf<Long>()
            val cache =
                PdfTextureCache<String>(
                    maxBytes = (TEXTURE_BYTES * 2).toLong(),
                    onBytesChanged = { current, _ -> reports += current },
                )
            val first = cache.acquire(key(page = 6), PdfRasterPriority.VISIBLE, TEXTURE_BYTES) { texture("first") }
            val preparationStarted = CompletableDeferred<Unit>()
            val preparationRelease = CompletableDeferred<Unit>()
            val second =
                async {
                    cache.acquire(key(page = 7), PdfRasterPriority.ADJACENT, TEXTURE_BYTES) {
                        preparationStarted.complete(Unit)
                        preparationRelease.await()
                        texture("second")
                    }
                }
            withTimeout(1_000) { preparationStarted.await() }

            val thirdStarted = CompletableDeferred<Unit>()
            val third =
                async {
                    cache.acquire(key(page = 8), PdfRasterPriority.BACKGROUND, TEXTURE_BYTES) {
                        thirdStarted.complete(Unit)
                        texture("third")
                    }
                }
            delay(50)
            assertFalse(thirdStarted.isCompleted)
            assertTrue(reports.all { bytes -> bytes <= TEXTURE_BYTES * 2L })

            first.close()
            preparationRelease.complete(Unit)
            val secondLease = second.await()
            secondLease.close()
            val thirdLease = third.await()
            assertEquals("third", thirdLease.value)
            thirdLease.close()

            val cancelled =
                async {
                    cache.acquireUncached(
                        byteSize = TEXTURE_BYTES,
                        prepare = {
                            CompletableDeferred<Unit>().await()
                            "never"
                        },
                    )
                }
            cancelled.cancel()
            cancelled.join()
            val afterCancellation =
                cache.acquireUncached(TEXTURE_BYTES, prepare = { "available" })
            assertEquals("available", afterCancellation.value)
            afterCancellation.close()
            cache.close()
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
