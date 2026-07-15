package dev.xuexue.platform

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
private const val DEFAULT_METRIC_LATENCY_WINDOW = 512
private const val P95 = 0.95
private const val MAX_FRAME_SAMPLE_NANOS = 250_000_000L
private const val DEFAULT_FRAME_BUDGET_NANOS = 16_666_667L
private const val MIN_PLAUSIBLE_FRAME_NANOS = 4_000_000L
private const val MAX_PLAUSIBLE_FRAME_NANOS = 20_000_000L

data class PdfPerformanceSnapshot(
    val renderAttempts: Long,
    val completedRenders: Long,
    val cancelledRenders: Long,
    val failedRenders: Long,
    val renderP95Millis: Double,
    val visibleTileP95Millis: Double,
    val firstViewportTileMillis: Double?,
    val cacheHits: Long,
    val cacheMisses: Long,
    val coalescedRequests: Long,
    val cacheEvictions: Long,
    val cacheBytes: Long,
    val peakCacheBytes: Long,
    val rasterBytesProduced: Long,
    val texturePreparations: Long,
    val textureBytesPrepared: Long,
    val texturePreparationP95Millis: Double,
    val texturePreparationBandwidthMiBPerSecond: Double,
    val textureCacheEvictions: Long,
    val textureCacheBytes: Long,
    val peakTextureCacheBytes: Long,
    val sampledFrameSlots: Long,
    val droppedFrames: Long,
) {
    val cacheHitRate: Double
        get() {
            val lookups = cacheHits + cacheMisses + coalescedRequests
            return if (lookups == 0L) 0.0 else cacheHits.toDouble() / lookups
        }

    val cancellationRate: Double
        get() = if (renderAttempts == 0L) 0.0 else cancelledRenders.toDouble() / renderAttempts

    val droppedFrameRate: Double
        get() = if (sampledFrameSlots == 0L) 0.0 else droppedFrames.toDouble() / sampledFrameSlots
}

class PdfPerformanceMetrics(
    latencyWindowSize: Int = DEFAULT_METRIC_LATENCY_WINDOW,
) {
    private val renderAttempts = AtomicLong()
    private val completedRenders = AtomicLong()
    private val cancelledRenders = AtomicLong()
    private val failedRenders = AtomicLong()
    private val firstViewportStartedNanos = AtomicLong()
    private val firstViewportTileNanos = AtomicLong()
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val coalescedRequests = AtomicLong()
    private val cacheEvictions = AtomicLong()
    private val cacheBytes = AtomicLong()
    private val peakCacheBytes = AtomicLong()
    private val rasterBytesProduced = AtomicLong()
    private val renderLatencies = MetricLatencyWindow(latencyWindowSize)
    private val visibleTileLatencies = MetricLatencyWindow(latencyWindowSize)
    internal val textures = PdfTexturePerformanceMetrics(latencyWindowSize)
    internal val frames = PdfFramePerformanceMetrics()

    init {
        require(latencyWindowSize > 0) { "PDF latency window must be positive." }
    }

    fun recordRenderStarted() {
        renderAttempts.incrementAndGet()
    }

    fun beginFirstViewportMeasurement() {
        firstViewportTileNanos.set(0L)
        firstViewportStartedNanos.set(System.nanoTime())
    }

    fun recordRenderCompleted(
        durationNanos: Long,
        rasterBytes: Int,
        firstViewportCandidate: Boolean,
    ) {
        completedRenders.incrementAndGet()
        rasterBytesProduced.addAndGet(rasterBytes.toLong())
        renderLatencies.add(durationNanos)
        if (firstViewportCandidate) {
            visibleTileLatencies.add(durationNanos)
            val startedAt = firstViewportStartedNanos.get()
            val firstTileDuration =
                if (startedAt > 0L) System.nanoTime() - startedAt else durationNanos
            firstViewportTileNanos.compareAndSet(0L, firstTileDuration.coerceAtLeast(1L))
        }
    }

    fun recordRenderCancelled(durationNanos: Long) {
        cancelledRenders.incrementAndGet()
        renderLatencies.add(durationNanos)
    }

    fun recordRenderFailed(durationNanos: Long) {
        failedRenders.incrementAndGet()
        renderLatencies.add(durationNanos)
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
    }

    fun recordCoalescedRequest() {
        coalescedRequests.incrementAndGet()
    }

    fun recordCacheEviction() {
        cacheEvictions.incrementAndGet()
    }

    fun updateCacheBytes(bytes: Long) {
        cacheBytes.set(bytes)
        peakCacheBytes.accumulateAndGet(bytes, ::maxOf)
    }

    fun snapshot(): PdfPerformanceSnapshot {
        val texture = textures.snapshot()
        val frame = frames.snapshot()
        return PdfPerformanceSnapshot(
            renderAttempts = renderAttempts.get(),
            completedRenders = completedRenders.get(),
            cancelledRenders = cancelledRenders.get(),
            failedRenders = failedRenders.get(),
            renderP95Millis = renderLatencies.percentile(P95).toMillis(),
            visibleTileP95Millis = visibleTileLatencies.percentile(P95).toMillis(),
            firstViewportTileMillis = firstViewportTileNanos.get().takeIf { it > 0L }?.toMillis(),
            cacheHits = cacheHits.get(),
            cacheMisses = cacheMisses.get(),
            coalescedRequests = coalescedRequests.get(),
            cacheEvictions = cacheEvictions.get(),
            cacheBytes = cacheBytes.get(),
            peakCacheBytes = peakCacheBytes.get(),
            rasterBytesProduced = rasterBytesProduced.get(),
            texturePreparations = texture.preparations,
            textureBytesPrepared = texture.bytesPrepared,
            texturePreparationP95Millis = texture.preparationP95Nanos.toMillis(),
            texturePreparationBandwidthMiBPerSecond = texture.bandwidthMiBPerSecond,
            textureCacheEvictions = texture.cacheEvictions,
            textureCacheBytes = texture.cacheBytes,
            peakTextureCacheBytes = texture.peakCacheBytes,
            sampledFrameSlots = frame.sampledSlots,
            droppedFrames = frame.droppedFrames,
        )
    }
}

internal class PdfTexturePerformanceMetrics(
    latencyWindowSize: Int = DEFAULT_METRIC_LATENCY_WINDOW,
) {
    private val preparations = AtomicLong()
    private val bytesPrepared = AtomicLong()
    private val preparationNanos = AtomicLong()
    private val cacheEvictions = AtomicLong()
    private val cacheBytes = AtomicLong()
    private val peakCacheBytes = AtomicLong()
    private val preparationLatencies = MetricLatencyWindow(latencyWindowSize)

    fun recordPreparation(
        bytes: Int,
        durationNanos: Long,
    ) {
        preparations.incrementAndGet()
        bytesPrepared.addAndGet(bytes.toLong())
        preparationNanos.addAndGet(durationNanos.coerceAtLeast(0L))
        preparationLatencies.add(durationNanos)
    }

    fun updateCacheBytes(
        currentBytes: Long,
        observedPeakBytes: Long,
    ) {
        cacheBytes.set(currentBytes)
        peakCacheBytes.accumulateAndGet(observedPeakBytes, ::maxOf)
    }

    fun recordCacheEviction() {
        cacheEvictions.incrementAndGet()
    }

    fun snapshot(): PdfTextureMetricsSnapshot {
        val duration = preparationNanos.get()
        val bytes = bytesPrepared.get()
        val bandwidth =
            if (duration == 0L) {
                0.0
            } else {
                bytes * NANOS_PER_SECOND / duration / BYTES_PER_MEBIBYTE
            }
        return PdfTextureMetricsSnapshot(
            preparations = preparations.get(),
            bytesPrepared = bytes,
            preparationP95Nanos = preparationLatencies.percentile(P95),
            bandwidthMiBPerSecond = bandwidth,
            cacheEvictions = cacheEvictions.get(),
            cacheBytes = cacheBytes.get(),
            peakCacheBytes = peakCacheBytes.get(),
        )
    }
}

internal class PdfFramePerformanceMetrics {
    private val sampledSlots = AtomicLong()
    private val droppedFrames = AtomicLong()
    private var previousFrameNanos = 0L
    private var frameBudgetNanos = DEFAULT_FRAME_BUDGET_NANOS

    @Synchronized
    fun recordFrame(frameTimeNanos: Long) {
        val previous = previousFrameNanos
        previousFrameNanos = frameTimeNanos
        if (previous == 0L || frameTimeNanos <= previous) return
        val interval = frameTimeNanos - previous
        if (interval in MIN_PLAUSIBLE_FRAME_NANOS..MAX_PLAUSIBLE_FRAME_NANOS) {
            frameBudgetNanos = minOf(frameBudgetNanos, interval)
        }
        recordInterval(interval, frameBudgetNanos)
    }

    fun recordInterval(
        intervalNanos: Long,
        frameBudgetNanos: Long,
    ) {
        if (intervalNanos <= 0L || frameBudgetNanos <= 0L || intervalNanos > MAX_FRAME_SAMPLE_NANOS) return
        val slots = (intervalNanos.toDouble() / frameBudgetNanos).roundToInt().coerceAtLeast(1)
        sampledSlots.addAndGet(slots.toLong())
        droppedFrames.addAndGet((slots - 1).toLong())
    }

    fun snapshot(): PdfFrameMetricsSnapshot =
        PdfFrameMetricsSnapshot(
            sampledSlots = sampledSlots.get(),
            droppedFrames = droppedFrames.get(),
        )
}

private class MetricLatencyWindow(
    capacity: Int,
) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized
    fun add(durationNanos: Long) {
        values[next] = durationNanos.coerceAtLeast(0L)
        next = (next + 1) % values.size
        if (count < values.size) count++
    }

    @Synchronized
    fun percentile(percentile: Double): Long {
        if (count == 0) return 0L
        val sample = values.copyOf(count)
        sample.sort()
        val index = (ceil(percentile * sample.size).toInt() - 1).coerceIn(0, sample.lastIndex)
        return sample[index]
    }
}

internal data class PdfTextureMetricsSnapshot(
    val preparations: Long,
    val bytesPrepared: Long,
    val preparationP95Nanos: Long,
    val bandwidthMiBPerSecond: Double,
    val cacheEvictions: Long,
    val cacheBytes: Long,
    val peakCacheBytes: Long,
)

internal data class PdfFrameMetricsSnapshot(
    val sampledSlots: Long,
    val droppedFrames: Long,
)

private fun Long.toMillis(): Double = this / NANOS_PER_MILLISECOND
