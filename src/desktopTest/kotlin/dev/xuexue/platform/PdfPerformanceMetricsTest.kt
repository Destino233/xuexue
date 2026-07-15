package dev.xuexue.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfPerformanceMetricsTest {
    @Test
    fun textureMetricsIncludePreparationBandwidthAndCacheAccounting() {
        val metrics = PdfPerformanceMetrics()

        metrics.textures.recordPreparation(bytes = FOUR_MEBIBYTES, durationNanos = TWO_MILLISECONDS)
        metrics.textures.updateCacheBytes(currentBytes = 12L, observedPeakBytes = 16L)
        metrics.textures.recordCacheEviction()

        val snapshot = metrics.snapshot()
        assertEquals(1L, snapshot.texturePreparations)
        assertEquals(FOUR_MEBIBYTES.toLong(), snapshot.textureBytesPrepared)
        assertEquals(2.0, snapshot.texturePreparationP95Millis)
        assertEquals(2_000.0, snapshot.texturePreparationBandwidthMiBPerSecond)
        assertEquals(1L, snapshot.textureCacheEvictions)
        assertEquals(12L, snapshot.textureCacheBytes)
        assertEquals(16L, snapshot.peakTextureCacheBytes)
    }

    @Test
    fun frameMetricsAdaptToHighRefreshDisplaysAndIgnoreBackgroundGaps() {
        val metrics = PdfPerformanceMetrics()
        var frameTime = ONE_SECOND_NANOS

        metrics.frames.recordFrame(frameTime)
        frameTime += FRAME_144_HZ_NANOS
        metrics.frames.recordFrame(frameTime)
        frameTime += FRAME_144_HZ_NANOS * 2
        metrics.frames.recordFrame(frameTime)
        frameTime += BACKGROUND_GAP_NANOS
        metrics.frames.recordFrame(frameTime)

        val snapshot = metrics.snapshot()
        assertEquals(3L, snapshot.sampledFrameSlots)
        assertEquals(1L, snapshot.droppedFrames)
        assertEquals(1.0 / 3.0, snapshot.droppedFrameRate)
    }

    @Test
    fun firstViewportAndVisibleTileMetricsUseTheForegroundRenderPath() {
        val metrics = PdfPerformanceMetrics()

        metrics.beginFirstViewportMeasurement()
        metrics.recordRenderCompleted(
            durationNanos = TWO_MILLISECONDS,
            rasterBytes = FOUR_MEBIBYTES,
            firstViewportCandidate = true,
        )

        val snapshot = metrics.snapshot()
        assertEquals(2.0, snapshot.visibleTileP95Millis)
        checkNotNull(snapshot.firstViewportTileMillis)
    }

    private companion object {
        const val FOUR_MEBIBYTES = 4 * 1024 * 1024
        const val TWO_MILLISECONDS = 2_000_000L
        const val ONE_SECOND_NANOS = 1_000_000_000L
        const val FRAME_144_HZ_NANOS = 6_944_444L
        const val BACKGROUND_GAP_NANOS = 300_000_000L
    }
}
