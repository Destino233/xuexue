package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

data class PdfDocumentMetadata(
    val pageCount: Int,
)

data class PdfPageDimensions(
    val widthPoints: Float,
    val heightPoints: Float,
)

@Serializable
data class PdfNormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "PDF viewport coordinates must be finite."
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "PDF viewport coordinates must be normalized."
        }
        require(left < right && top < bottom) { "PDF viewport must have a positive area." }
    }
}

enum class PdfRasterPurpose {
    THUMBNAIL,
    VIEWPORT,
    ANALYSIS,
}

enum class PdfRasterLayer {
    CONTENT,
    NATIVE_OVERLAY,
}

enum class PdfRasterPriority {
    BACKGROUND,
    ADJACENT,
    VISIBLE,
}

object PdfRenderLod {
    private val viewportDpiLevels =
        floatArrayOf(
            24f,
            36f,
            48f,
            60f,
            72f,
            84f,
            96f,
            120f,
            132f,
            144f,
            156f,
            168f,
            180f,
            192f,
            216f,
            240f,
            288f,
            336f,
            384f,
            432f,
            480f,
            576f,
            720f,
            960f,
        )

    fun quantize(
        requestedDpi: Float,
        purpose: PdfRasterPurpose,
    ): Float {
        require(requestedDpi > 0f) { "PDF render DPI must be positive." }
        return when (purpose) {
            PdfRasterPurpose.THUMBNAIL -> {
                requestedDpi.coerceIn(18f, 48f)
            }

            PdfRasterPurpose.ANALYSIS -> {
                requestedDpi
            }

            PdfRasterPurpose.VIEWPORT -> {
                viewportDpiLevels.firstOrNull { it >= requestedDpi }
                    ?: viewportDpiLevels.last()
            }
        }
    }

    fun quantizeWithHysteresis(
        requestedDpi: Float,
        purpose: PdfRasterPurpose,
        currentDpi: Float,
        hysteresisFraction: Float = DEFAULT_LOD_HYSTERESIS,
    ): Float {
        require(requestedDpi > 0f) { "PDF render DPI must be positive." }
        require(hysteresisFraction in 0f..<1f) { "PDF LOD hysteresis must be between zero and one." }
        if (purpose != PdfRasterPurpose.VIEWPORT) {
            return if (purpose == PdfRasterPurpose.THUMBNAIL) quantize(requestedDpi, purpose) else requestedDpi
        }
        var index = viewportDpiLevels.indexOfFirst { level -> level == currentDpi }
        if (index >= 0) {
            while (
                index < viewportDpiLevels.lastIndex &&
                requestedDpi > viewportDpiLevels[index] * (1f + hysteresisFraction)
            ) {
                index++
            }
            while (
                index > 0 &&
                requestedDpi < viewportDpiLevels[index - 1] * (1f - hysteresisFraction)
            ) {
                index--
            }
        }
        return if (index >= 0) viewportDpiLevels[index] else quantize(requestedDpi, purpose)
    }

    private const val DEFAULT_LOD_HYSTERESIS = 0.08f
}

data class PdfRasterRequest(
    val path: Path,
    val pageIndex: Int,
    val dpi: Float,
    val purpose: PdfRasterPurpose,
    val viewport: PdfNormalizedRect? = null,
    val priority: PdfRasterPriority = PdfRasterPriority.VISIBLE,
    val layer: PdfRasterLayer = PdfRasterLayer.CONTENT,
)

data class PdfPageRaster(
    val pageIndex: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val rgbaBytes: ByteArray,
    val viewport: PdfNormalizedRect? = null,
    val incomplete: Boolean = false,
    val empty: Boolean = false,
) {
    init {
        require(pixelWidth > 0 && pixelHeight > 0) { "PDF raster dimensions must be positive." }
        val expectedBytes = pixelWidth.toLong() * pixelHeight * RGBA_BYTES_PER_PIXEL
        require(expectedBytes <= Int.MAX_VALUE && rgbaBytes.size == expectedBytes.toInt()) {
            "PDF raster must contain exactly four RGBA bytes per pixel."
        }
    }

    val byteSize: Int
        get() = rgbaBytes.size

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}

data class PdfSearchMatch(
    val pageIndex: Int,
    val preview: String,
    val regions: List<PdfNormalizedRect> = emptyList(),
)

data class PdfPagePoint(
    val x: Float,
    val y: Float,
)

data class PdfTextSelection(
    val text: String,
    val regions: List<PdfNormalizedRect>,
)

data class PdfLink(
    val region: PdfNormalizedRect,
    val uri: String,
    val external: Boolean,
    val targetPage: Int? = null,
)

/** UI-facing boundary implemented by the MuPDF document actor and display-list render pool. */
@Suppress("TooManyFunctions") // The backend contract keeps PDF operations and lifecycle trimming in one boundary.
interface PdfReaderBackend : AutoCloseable {
    suspend fun inspect(path: Path): PdfDocumentMetadata

    suspend fun pageDimensions(
        path: Path,
        pageIndex: Int,
    ): PdfPageDimensions

    suspend fun render(request: PdfRasterRequest): PdfPageRaster

    suspend fun trimIdleResources() = Unit

    suspend fun search(
        path: Path,
        query: String,
    ): List<PdfSearchMatch>

    suspend fun extractPageText(
        path: Path,
        pageIndex: Int,
    ): String

    suspend fun nativeOutline(path: Path): List<PdfNativeOutlineNode> = emptyList()

    suspend fun structuredText(
        path: Path,
        pageIndex: Int,
    ): PdfStructuredTextPage? = null

    suspend fun exportOutline(
        path: Path,
        outputPath: Path,
        payload: ByteArray,
        strategy: PdfOutlineExportStrategy,
    ) {
        error("This PDF backend does not support Outline export.")
    }

    suspend fun selectText(
        path: Path,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection = PdfTextSelection("", emptyList())

    suspend fun links(
        path: Path,
        pageIndex: Int,
    ): List<PdfLink> = emptyList()

    override fun close() = Unit
}

enum class PdfOutlineExportStrategy {
    APPEND,
    REPLACE,
}

/**
 * Bounded application raster cache. MuPDF's display-list and GPU caches remain separate layers;
 * this cache keeps raster work from repeating while the same LOD is reused during zoom and navigation.
 */
@Suppress("TooManyFunctions") // This adapter forwards the full backend contract while adding bounded raster caching.
class CachingPdfReaderBackend(
    private val delegate: PdfReaderBackend,
    private val maxRasterBytes: Long = DEFAULT_CACHE_BYTES,
    private val metrics: PdfPerformanceMetrics = PdfPerformanceMetrics(),
    cacheablePurposes: Set<PdfRasterPurpose> = DEFAULT_CACHEABLE_PURPOSES,
) : PdfReaderBackend {
    private val mutex = Mutex()
    private val rasters = LinkedHashMap<PdfRasterCacheKey, CachedRaster>(16, 0.75f, true)
    private val inFlight = mutableMapOf<PdfRasterCacheKey, PendingRaster>()
    private val retainedPurposes = cacheablePurposes.toSet()
    private var rasterBytes = 0L

    init {
        require(maxRasterBytes >= 0) { "PDF raster cache budget cannot be negative." }
        require(PdfRasterPurpose.ANALYSIS !in retainedPurposes) {
            "Analysis rasters must not enter the interactive PDF cache."
        }
    }

    override suspend fun inspect(path: Path): PdfDocumentMetadata = delegate.inspect(path)

    override suspend fun pageDimensions(
        path: Path,
        pageIndex: Int,
    ): PdfPageDimensions = delegate.pageDimensions(path, pageIndex)

    override suspend fun render(request: PdfRasterRequest): PdfPageRaster {
        val normalizedRequest = request.copy(dpi = PdfRenderLod.quantize(request.dpi, request.purpose))
        if (normalizedRequest.purpose == PdfRasterPurpose.ANALYSIS) {
            return delegate.render(normalizedRequest)
        }
        val key = normalizedRequest.cacheKey()
        val lookup =
            mutex.withLock {
                val cached = rasters[key]
                val pending = inFlight[key]
                when {
                    cached != null -> {
                        metrics.recordCacheHit()
                        cached.promote(normalizedRequest.priority)
                        RenderLookup(cached = cached.raster)
                    }

                    pending != null -> {
                        metrics.recordCoalescedRequest()
                        pending.promote(normalizedRequest.priority)
                        RenderLookup(pending = pending)
                    }

                    else -> {
                        metrics.recordCacheMiss()
                        val newRequest =
                            PendingRaster(
                                deferred = kotlinx.coroutines.CompletableDeferred(),
                                priority = normalizedRequest.priority,
                            )
                        inFlight[key] = newRequest
                        RenderLookup(pending = newRequest, owner = true)
                    }
                }
            }
        return when {
            lookup.cached != null -> {
                lookup.cached
            }

            !lookup.owner -> {
                checkNotNull(lookup.pending).deferred.await()
            }

            else -> {
                val pending = checkNotNull(lookup.pending)
                val result = runCatching { delegate.render(normalizedRequest) }
                mutex.withLock {
                    val completedRequest = inFlight.remove(key) ?: pending
                    result.onSuccess { raster -> putRaster(key, raster, completedRequest.priority) }
                    result.fold(
                        completedRequest.deferred::complete,
                        completedRequest.deferred::completeExceptionally,
                    )
                }
                result.getOrThrow()
            }
        }
    }

    override suspend fun search(
        path: Path,
        query: String,
    ): List<PdfSearchMatch> = delegate.search(path, query)

    override suspend fun extractPageText(
        path: Path,
        pageIndex: Int,
    ): String = delegate.extractPageText(path, pageIndex)

    override suspend fun nativeOutline(path: Path): List<PdfNativeOutlineNode> = delegate.nativeOutline(path)

    override suspend fun structuredText(
        path: Path,
        pageIndex: Int,
    ): PdfStructuredTextPage? = delegate.structuredText(path, pageIndex)

    override suspend fun exportOutline(
        path: Path,
        outputPath: Path,
        payload: ByteArray,
        strategy: PdfOutlineExportStrategy,
    ) = delegate.exportOutline(path, outputPath, payload, strategy)

    override suspend fun selectText(
        path: Path,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection = delegate.selectText(path, pageIndex, start, end)

    override suspend fun links(
        path: Path,
        pageIndex: Int,
    ): List<PdfLink> = delegate.links(path, pageIndex)

    override suspend fun trimIdleResources() {
        mutex.withLock {
            rasters.clear()
            rasterBytes = 0L
            metrics.updateCacheBytes(0L)
        }
        delegate.trimIdleResources()
    }

    override fun close() {
        runBlocking { trimIdleResources() }
        delegate.close()
    }

    private fun putRaster(
        key: PdfRasterCacheKey,
        raster: PdfPageRaster,
        priority: PdfRasterPriority,
    ) {
        if (key.purpose !in retainedPurposes || raster.incomplete) return
        val newRasterBytes = raster.byteSize.toLong()
        if (maxRasterBytes > 0L && newRasterBytes <= maxRasterBytes) {
            rasters.entries.removeAll { entry ->
                val stale = entry.key.path == key.path && entry.key.revision != key.revision
                if (stale) {
                    rasterBytes -= entry.value.raster.byteSize
                    metrics.recordCacheEviction()
                }
                stale
            }
            rasters.put(key, CachedRaster(raster, priority))?.let { replaced ->
                rasterBytes -= replaced.raster.byteSize
            }
            rasterBytes += newRasterBytes
            while (rasterBytes > maxRasterBytes && rasters.isNotEmpty()) {
                val candidate = rasters.entries.minBy { entry -> entry.value.priority.ordinal }
                rasterBytes -= candidate.value.raster.byteSize
                rasters.remove(candidate.key)
                metrics.recordCacheEviction()
            }
            metrics.updateCacheBytes(rasterBytes)
        }
    }

    private fun PdfRasterRequest.cacheKey(): PdfRasterCacheKey {
        val normalizedPath = path.toAbsolutePath().normalize()
        val revision =
            PdfFileRevision(
                size = Files.size(normalizedPath),
                modifiedAtMillis = Files.getLastModifiedTime(normalizedPath).toMillis(),
            )
        return PdfRasterCacheKey(
            path = normalizedPath,
            revision = revision,
            pageIndex = pageIndex,
            dpi = dpi,
            purpose = purpose,
            viewport = viewport,
            layer = layer,
        )
    }

    private data class RenderLookup(
        val cached: PdfPageRaster? = null,
        val pending: PendingRaster? = null,
        val owner: Boolean = false,
    )

    private data class CachedRaster(
        val raster: PdfPageRaster,
        var priority: PdfRasterPriority,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }

    private data class PendingRaster(
        val deferred: kotlinx.coroutines.CompletableDeferred<PdfPageRaster>,
        var priority: PdfRasterPriority,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }

    private data class PdfFileRevision(
        val size: Long,
        val modifiedAtMillis: Long,
    )

    private data class PdfRasterCacheKey(
        val path: Path,
        val revision: PdfFileRevision,
        val pageIndex: Int,
        val dpi: Float,
        val purpose: PdfRasterPurpose,
        val viewport: PdfNormalizedRect?,
        val layer: PdfRasterLayer,
    )

    private companion object {
        const val DEFAULT_CACHE_BYTES = PdfMemoryBudget.RASTER_CACHE_BYTES
        val DEFAULT_CACHEABLE_PURPOSES =
            setOf(
                PdfRasterPurpose.THUMBNAIL,
                PdfRasterPurpose.VIEWPORT,
            )
    }
}
