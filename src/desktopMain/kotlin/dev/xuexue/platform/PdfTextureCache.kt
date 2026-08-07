package dev.xuexue.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val onClose: () -> Unit = {},
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
        fun <T> uncached(
            value: T,
            onClose: () -> Unit = {},
        ): PdfTextureLease<T> = PdfTextureLease(value, onClose)
    }
}

@Suppress("TooManyFunctions") // Admission, coalescing, lease lifetime, and cache eviction share one lock boundary.
internal class PdfTextureCache<T>(
    private val maxBytes: Long = PdfMemoryBudget.TEXTURE_CACHE_BYTES,
    private val onBytesChanged: (currentBytes: Long, peakBytes: Long) -> Unit = { _, _ -> },
    private val onEviction: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val entries = LinkedHashMap<PdfTextureKey, TextureEntry<T>>(16, 0.75f, true)
    private val pending = mutableMapOf<PdfTextureKey, PendingTexture>()
    private val capacityWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var currentBytes = 0L
    private var reservedBytes = 0L
    private var peakBytes = 0L
    private var closed = false

    init {
        require(maxBytes >= 0L) { "PDF texture cache budget cannot be negative." }
    }

    fun acquireIfPresent(
        key: PdfTextureKey,
        priority: PdfRasterPriority,
    ): PdfTextureLease<T>? =
        synchronized(lock) {
            checkOpen()
            entries[key]?.let { entry -> lease(entry, priority) }
        }

    @Suppress(
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
        "ReturnCount",
    ) // These branches keep capacity reservation and pending-owner handoff atomic.
    suspend fun acquire(
        key: PdfTextureKey,
        priority: PdfRasterPriority,
        expectedBytes: Int,
        prepare: suspend () -> PreparedPdfTexture<T>,
    ): PdfTextureLease<T> {
        validateByteSize(expectedBytes)
        while (true) {
            val lookup =
                synchronized(lock) {
                    checkOpen()
                    entries[key]?.let { entry -> TextureLookup(lease = lease(entry, priority)) }
                        ?: pending[key]
                            ?.also { it.promote(priority) }
                            ?.let { TextureLookup<T>(pending = it) }
                }
            lookup?.lease?.let { return it }
            lookup?.pending?.let {
                it.completed.await()
                currentCoroutineContext().ensureActive()
                continue
            }

            reserve(expectedBytes)
            val ownerLookup =
                try {
                    synchronized(lock) {
                        checkOpen()
                        entries[key]?.let { entry ->
                            TextureLookup<T>(lease = lease(entry, priority), releaseReservation = true)
                        } ?: pending[key]
                            ?.also { it.promote(priority) }
                            ?.let {
                                TextureLookup<T>(
                                    pending = it,
                                    releaseReservation = true,
                                )
                            } ?: PendingTexture(
                            completed = CompletableDeferred(),
                            priority = priority,
                            expectedBytes = expectedBytes,
                        ).also { pending[key] = it }
                            .let { TextureLookup<T>(pending = it, owner = true) }
                    }
                } catch (failure: IllegalStateException) {
                    releaseReservation(expectedBytes)
                    throw failure
                }
            if (ownerLookup.releaseReservation) releaseReservation(expectedBytes)
            ownerLookup.lease?.let { return it }
            ownerLookup.pending?.let {
                if (!ownerLookup.owner) {
                    it.completed.await()
                    currentCoroutineContext().ensureActive()
                    continue
                }
                return prepareAndCache(key, it, prepare)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A caller-supplied texture factory must never strand its reservation.
    suspend fun acquireUncached(
        byteSize: Int,
        prepare: suspend () -> T,
        onClose: (T) -> Unit = {},
    ): PdfTextureLease<T> {
        validateByteSize(byteSize)
        reserve(byteSize)
        var value: T? = null
        try {
            value = prepare()
            currentCoroutineContext().ensureActive()
            val prepared = checkNotNull(value)
            return PdfTextureLease(prepared) {
                try {
                    onClose(prepared)
                } finally {
                    releaseReservation(byteSize)
                }
            }
        } catch (cancelled: CancellationException) {
            try {
                value?.let(onClose)
            } finally {
                releaseReservation(byteSize)
            }
            throw cancelled
        } catch (failure: Exception) {
            try {
                value?.let(onClose)
            } finally {
                releaseReservation(byteSize)
            }
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught") // Native bitmap preparation needs cleanup for every factory failure.
    private suspend fun prepareAndCache(
        key: PdfTextureKey,
        requested: PendingTexture,
        prepare: suspend () -> PreparedPdfTexture<T>,
    ): PdfTextureLease<T> {
        var prepared: PreparedPdfTexture<T>? = null
        try {
            prepared = prepare()
            currentCoroutineContext().ensureActive()
            require(prepared.byteSize == requested.expectedBytes) {
                "Prepared PDF texture size changed while it was being admitted."
            }
            val entry =
                synchronized(lock) {
                    checkOpen()
                    check(pending[key] === requested) { "PDF texture admission was superseded." }
                    pending.remove(key)
                    releaseReservedBytesLocked(requested.expectedBytes)
                    requested.reserved = false
                    val texture = checkNotNull(prepared)
                    TextureEntry(
                        key = key,
                        value = texture.value,
                        byteSize = texture.byteSize,
                        priority = requested.priority,
                        leases = 1,
                        onClose = texture.onClose,
                    ).also {
                        entries[key] = it
                        currentBytes += it.byteSize
                        peakBytes = maxOf(peakBytes, currentBytes + reservedBytes)
                        requested.completed.complete(Unit)
                        reportBytes()
                    }
                }
            prepared = null
            return PdfTextureLease(entry.value) { release(entry) }
        } catch (failure: Exception) {
            prepared?.onClose?.invoke()
            abandonPending(key, requested, failure)
            throw failure
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toList().forEach { entry ->
                if (entry.leases == 0) {
                    entries.remove(entry.key)
                    currentBytes -= entry.byteSize
                    entry.cached = false
                    entry.onClose()
                } else {
                    entry.cached = false
                }
            }
            pending.values.forEach { request ->
                if (request.reserved) {
                    releaseReservedBytesLocked(request.expectedBytes)
                    request.reserved = false
                }
                request.completed.completeExceptionally(CacheClosedException())
            }
            pending.clear()
            capacityWaiters.forEach { it.completeExceptionally(CacheClosedException()) }
            capacityWaiters.clear()
            reportBytes()
        }
    }

    internal fun trimIdle() {
        synchronized(lock) {
            entries.values
                .filter { entry -> entry.leases == 0 }
                .toList()
                .forEach { entry ->
                    entries.remove(entry.key)
                    currentBytes -= entry.byteSize
                    entry.cached = false
                    entry.onClose()
                }
            reportBytes()
            wakeCapacityWaiters()
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
            if (entry.leases == 0 && (!entry.cached || closed)) {
                entries.remove(entry.key)
                currentBytes -= entry.byteSize
                entry.onClose()
            }
            reportBytes()
            wakeCapacityWaiters()
        }
    }

    private suspend fun reserve(byteSize: Int) {
        while (true) {
            val waiter =
                synchronized(lock) {
                    checkOpen()
                    evictForReservationLocked(byteSize)
                    if (currentBytes + reservedBytes + byteSize <= maxBytes) {
                        reservedBytes += byteSize
                        peakBytes = maxOf(peakBytes, currentBytes + reservedBytes)
                        reportBytes()
                        null
                    } else {
                        reportBytes()
                        CompletableDeferred<Unit>().also { capacityWaiters += it }
                    }
                }
            if (waiter == null) return
            try {
                waiter.await()
            } finally {
                synchronized(lock) { capacityWaiters.remove(waiter) }
            }
        }
    }

    private fun releaseReservation(byteSize: Int) {
        synchronized(lock) {
            releaseReservedBytesLocked(byteSize)
            reportBytes()
            wakeCapacityWaiters()
        }
    }

    private fun releaseReservedBytesLocked(byteSize: Int) {
        reservedBytes -= byteSize
        check(reservedBytes >= 0L) { "PDF texture reservation underflow." }
    }

    private fun evictForReservationLocked(requiredBytes: Int) {
        while (currentBytes + reservedBytes + requiredBytes > maxBytes) {
            val candidate =
                entries.entries
                    .asSequence()
                    .filter { (_, entry) -> entry.leases == 0 }
                    .minByOrNull { (_, entry) -> entry.priority.ordinal }
                    ?: break
            entries.remove(candidate.key)
            currentBytes -= candidate.value.byteSize
            candidate.value.cached = false
            candidate.value.onClose()
            onEviction()
        }
    }

    private fun abandonPending(
        key: PdfTextureKey,
        requested: PendingTexture,
        failure: Throwable,
    ) {
        synchronized(lock) {
            if (pending[key] === requested) {
                pending.remove(key)
                if (requested.reserved) {
                    releaseReservedBytesLocked(requested.expectedBytes)
                    requested.reserved = false
                }
                requested.completed.completeExceptionally(failure)
                reportBytes()
                wakeCapacityWaiters()
            }
        }
    }

    private fun validateByteSize(byteSize: Int) {
        require(byteSize > 0) { "PDF texture size must be positive." }
        require(byteSize.toLong() <= maxBytes) {
            "PDF texture size $byteSize exceeds the $maxBytes-byte hard budget."
        }
    }

    private fun checkOpen() {
        check(!closed) { "PDF texture cache is closed." }
    }

    private fun reportBytes() {
        onBytesChanged(currentBytes + reservedBytes, peakBytes)
    }

    private fun wakeCapacityWaiters() {
        if (capacityWaiters.isEmpty()) return
        val waiters = capacityWaiters.toList()
        capacityWaiters.clear()
        waiters.forEach { it.complete(Unit) }
    }

    private data class TextureLookup<T>(
        val lease: PdfTextureLease<T>? = null,
        val pending: PendingTexture? = null,
        val owner: Boolean = false,
        val releaseReservation: Boolean = false,
    )

    private data class PendingTexture(
        val completed: CompletableDeferred<Unit>,
        var priority: PdfRasterPriority,
        val expectedBytes: Int,
        var reserved: Boolean = true,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }

    private class TextureEntry<T>(
        val key: PdfTextureKey,
        val value: T,
        val byteSize: Int,
        var priority: PdfRasterPriority,
        var leases: Int,
        var cached: Boolean = true,
        val onClose: () -> Unit,
    ) {
        fun promote(requestedPriority: PdfRasterPriority) {
            if (requestedPriority > priority) priority = requestedPriority
        }
    }

    private class CacheClosedException : IllegalStateException("PDF texture cache is closed.")
}
