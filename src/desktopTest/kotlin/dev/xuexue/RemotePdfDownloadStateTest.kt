package dev.xuexue

import kotlin.test.Test
import kotlin.test.assertEquals

class RemotePdfDownloadStateTest {
    @Test
    fun progressAndByteLabelReflectTheRetainedRangeCache() {
        val state =
            RemotePdfDownloadState(
                relativePath = "Downloads/remote.pdf",
                sourceUrl = "https://example.test/remote.pdf",
                downloadedBytes = 512,
                totalBytes = 2 * 1024,
                phase = RemotePdfDownloadPhase.FAILED,
            )

        assertEquals(0.25f, state.progress)
        assertEquals("512 B / 2.0 KB", state.byteProgress)
    }

    @Test
    fun progressIsClampedForConcurrentCompletionUpdates() {
        val state =
            RemotePdfDownloadState(
                relativePath = "Downloads/remote.pdf",
                sourceUrl = "https://example.test/remote.pdf",
                downloadedBytes = 3 * 1024,
                totalBytes = 2 * 1024,
                phase = RemotePdfDownloadPhase.DOWNLOADING,
            )

        assertEquals(1f, state.progress)
    }
}
