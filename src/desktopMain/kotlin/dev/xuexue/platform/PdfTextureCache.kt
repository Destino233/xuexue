package dev.xuexue.platform

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

internal data class PdfTextureKey(
    val documentPath: String,
    val revision: Long,
    val pageIndex: Int,
    val dpi: Float,
    val purpose: PdfRasterPurpose,
    val viewport: PdfNormalizedRect?,
    val layer: PdfRasterLayer = PdfRasterLayer.CONTENT,
)

internal data class PreparedPdfTexture<T>(
    val value: T,
    val byteSize: Int,
) {
    init {
        require(byteSize > 0) { "PDF texture size must be positive." }
    }
}

internal class PdfTextureLease<T> internal constructor(
    val value: T,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }

    internal companion object {
        fun <T> uncached(value: T): PdfTextureLease<T> = PdfTextureLease(value) {}
    }
}

internal class PdfTextureCache<T>(
    private val maxBytes: Long = PdfMemoryBudget.TEXTURE_CACHE_BYTES,
    private val onBytesChanged: (currentBytes: Long, peakBytes: Long) -> Unit = { _, _ -> },
    private val onEviction: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val entries = LinkedHashMap<PdfTextureKey, TextureEntry<T>>(16, 0.75f, true)
    private val pending = mutableMapOf<PdfTextureKey, PendingTexture>()
    private var currentBytes = 0L
    private var peakBytes = 0L

    init {
        require(maxBytes >= 0L) { "PDF texture cache budget cannot be negative." }
    }

    fun acquireIfPresent(
        key: PdfTextureKey,
        priority: PdfRasterPriority,
    ): PdfTextureLease<T>? =
        synchronized(lock) {
            entries[key]?.let { entry -> lease(entry, priority) }
        }

    suspend fun acquire(
        key: PdfTextureKey,
        priority: PdfRasterPriority,
        prepare: suspend () -> PreparedPdfTexture<T>,
    ): PdfTextureLease<T> {
        val lookup =
            synchronized(lock) {
                entries[key]?.let { entry -> TextureLookup(lease = lease(entry, priority)) }
                    ?: pending[key]
                        ?.also { it.promote(priority) }
                        ?.let { TextureLookup<T>(pending = it) }
                    ?: PendingTexture(CompletableDeferred(), priority)
                        .also { pending[key] = it }
                        .let { TextureLookup<T>(pending = it, owner = true) }
            }
        return when {
            lookup.lease != null -> {
                lookup.lease
            }

            !lookup.owner -> {
                checkNotNull(lookup.pending).completed.await()
                acquire(key, priority, prepare)
            }

            else -> {
                prepareAndCache(key, checkNotNull(lookup.pending), prepare)
            }
        }
    }

    private suspend fun prepareAndCache(
        key: PdfTextureKey,
        requested: PendingTexture,
        prepare: suspend () -> PreparedPdfTexture<T>,
    ): PdfTextureLease<T> {
        val prepared = runCatching { prepare() }
        return synchronized(lock) {
            val completed = pending.remove(key) ?: requested
            prepared.fold(
                onSuccess = { texture ->
                    val entry = TextureEntry(texture.value, texture.byteSize, completed.priority, leases = 1)
                    entries[key] = entry
                    currentBytes += texture.byteSize
                    peakBytes = maxOf(peakBytes, currentBytes)
                    evictIfNeeded()
                    completed.completed.complete(Unit)
                    PdfTextureLease(entry.value) { release(entry) }
                },
                onFailure = { failure ->
                    completed.completed.completeExceptionally(failure)
                    throw failure
                },
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            entries.clear()
            pending.values.forEach { it.completed.cancel() }
            pending.clear()
            currentBytes = 0L
            reportBytes()
        }
    }

    private fun lease(
        entry: TextureEntry<T>,
        priority: PdfRasterPriority,
    ): PdfTextureLease<T> {
        entry.promote(priority)
        entry.leases++
        return PdfTextureLease(entry.value) { release(entry) }
    }

    private fun release(entry: TextureEntry<T>) {
        synchronized(lock) {
            check(entry.leases > 0) { "PDF texture lease count underflow." }
            entry.leases--
            evictIfNeeded()
        }
    }

    private fun evictIfNeeded() {
        while (currentBytes > maxBytes) {
            val candidate =
                entries.entries
                    .asSequence()
                    .filter { (_, entry) -> entry.leases == 0 }
                    .minByOrNull { (_, entry) -> entry.priority.ordinal }
                    ?: break
            entries.remove(candidate.key)
            currentBytes -= candidate.value.byteSize
            onEviction()
        }
        reportBytes()
    }

    private fun reportBytes() = onBytesChanged(currentBytes, peakBytes)

    private data class TextureLookup<T>(
        val lease: PdfTextureLease<T>? = null,
        val pending: PendingTexture? = null,
        val owner: Boolean = false,
    )

    private data class PendingTexture(
        val completed: CompletableDeferred<Unit>,
        var priority: PdfRasterPriority,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }

    private data class TextureEntry<T>(
        val value: T,
        val byteSize: Int,
        var priority: PdfRasterPriority,
        var leases: Int,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }
}
