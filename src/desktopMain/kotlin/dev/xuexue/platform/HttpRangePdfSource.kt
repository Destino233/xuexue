package dev.xuexue.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class HttpRangePdfSource private constructor(
    private val httpClient: HttpClient,
    val remoteUri: URI,
    val cachePath: Path,
    override val length: Long,
    private val validator: String?,
    private val blockSize: Int,
    private val ioDispatcher: CoroutineDispatcher,
    override val revision: Long,
    private val cached: BooleanArray,
    private val manifestStore: HttpRangePdfManifestStore,
) : ProgressivePdfSource {
    private val blockCount = ((length + blockSize - 1L) / blockSize).toInt()
    private val inFlight = mutableMapOf<Int, CompletableDeferred<ByteArray>>()
    private val mutex = Mutex()
    private val loadedBytes = AtomicLong(cachedBytes(cached, length, blockSize))
    private val closed = AtomicBoolean()

    val bytesLoaded: Long
        get() = loadedBytes.get()

    override val availableBytes: Long
        get() = bytesLoaded

    val complete: Boolean
        get() = bytesLoaded == length

    override suspend fun read(range: PdfByteRange): ByteArray {
        check(!closed.get()) { "Remote PDF range source is closed." }
        val blockIndex = blockIndexFor(range, blockSize, blockCount)
        require(range.length == pdfBlockLength(blockIndex, length, blockSize)) {
            "Remote PDF range length does not match its block."
        }
        return loadBlock(blockIndex)
    }

    suspend fun cacheAll(onProgress: suspend (downloaded: Long, total: Long) -> Unit) {
        val last = blockCount - 1
        val priorityOrder =
            buildList {
                add(0)
                if (last > 0) add(last)
                for (index in 1 until last) add(index)
            }
        for (blockIndex in priorityOrder) {
            currentCoroutineContext().ensureActive()
            loadBlock(blockIndex)
            onProgress(bytesLoaded, length)
        }
    }

    suspend fun finalizeTo(target: Path) {
        check(complete) { "Remote PDF cannot be finalized before every range is cached." }
        withContext(ioDispatcher) {
            Files.copy(cachePath, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        if (complete) {
            runCatching { Files.deleteIfExists(cachePath) }
            manifestStore.delete(cachePath)
        }
    }

    private suspend fun loadBlock(blockIndex: Int): ByteArray {
        val lookup =
            mutex.withLock {
                when {
                    cached[blockIndex] -> {
                        BlockLookup(cached = true)
                    }

                    inFlight[blockIndex] != null -> {
                        BlockLookup(pending = inFlight.getValue(blockIndex))
                    }

                    else -> {
                        val pending = CompletableDeferred<ByteArray>()
                        inFlight[blockIndex] = pending
                        BlockLookup(pending = pending, owner = true)
                    }
                }
            }
        return when {
            lookup.cached -> {
                readCachedBlock(blockIndex)
            }

            !lookup.owner -> {
                checkNotNull(lookup.pending).await()
            }

            else -> {
                val pending = checkNotNull(lookup.pending)
                var cachedSnapshot: BooleanArray? = null
                val result =
                    runCatching {
                        fetchBlock(blockIndex).also { bytes -> writeBlock(blockIndex, bytes) }
                    }
                mutex.withLock {
                    inFlight.remove(blockIndex)
                    result.fold(
                        onSuccess = { bytes ->
                            cached[blockIndex] = true
                            loadedBytes.addAndGet(bytes.size.toLong())
                            cachedSnapshot = cached.copyOf()
                            pending.complete(bytes)
                        },
                        onFailure = pending::completeExceptionally,
                    )
                }
                cachedSnapshot?.let { blocks ->
                    manifestStore.write(
                        cachePath,
                        rangeManifest(remoteUri, length, validator, blockSize, revision, blocks),
                    )
                }
                result.getOrThrow()
            }
        }
    }

    private suspend fun fetchBlock(blockIndex: Int): ByteArray {
        val range = pdfBlockRange(blockIndex, length, blockSize)
        val requestBuilder =
            HttpRequest
                .newBuilder(remoteUri)
                .header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
                .GET()
        validator?.let { requestBuilder.header("If-Range", it) }
        val response = httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray()).await()
        check(response.statusCode() == HTTP_PARTIAL_CONTENT) {
            "Remote PDF changed or stopped supporting byte ranges (HTTP ${response.statusCode()})."
        }
        check(response.body().size == range.length) { "Remote PDF returned an incomplete byte range." }
        val contentRange = parseContentRange(response.headers().firstValue("content-range").orElse(null))
        check(
            contentRange?.start == range.offset &&
                contentRange.endInclusive == range.offset + range.length - 1 &&
                contentRange.total == length,
        ) {
            "Remote PDF returned a mismatched Content-Range."
        }
        return response.body()
    }

    private suspend fun readCachedBlock(blockIndex: Int): ByteArray =
        withContext(ioDispatcher) {
            val bytes = ByteArray(pdfBlockLength(blockIndex, length, blockSize))
            FileChannel.open(cachePath, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                var position = blockIndex.toLong() * blockSize
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer, position)
                    check(count > 0) { "Cached remote PDF block is truncated." }
                    position += count
                }
            }
            bytes
        }

    private suspend fun writeBlock(
        blockIndex: Int,
        bytes: ByteArray,
    ) = withContext(ioDispatcher) {
        FileChannel.open(cachePath, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            var position = blockIndex.toLong() * blockSize
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position)
            }
        }
    }

    private suspend fun seedFirstBlock(bytes: ByteArray) {
        check(bytes.size == pdfBlockLength(0, length, blockSize)) {
            "Remote PDF probe returned an incomplete first block."
        }
        writeBlock(0, bytes)
        mutex.withLock {
            cached[0] = true
            loadedBytes.set(bytes.size.toLong())
        }
        manifestStore.write(
            cachePath,
            rangeManifest(remoteUri, length, validator, blockSize, revision, cached.copyOf()),
        )
    }

    private data class BlockLookup(
        val cached: Boolean = false,
        val pending: CompletableDeferred<ByteArray>? = null,
        val owner: Boolean = false,
    )

    companion object {
        suspend fun openOrNull(
            httpClient: HttpClient,
            uri: URI,
            cachePath: Path,
            ioDispatcher: CoroutineDispatcher,
            blockSize: Int = PROGRESSIVE_PDF_BLOCK_BYTES,
        ): HttpRangePdfSource? {
            require(blockSize > 0) { "Remote PDF block size must be positive." }
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .header("Range", "bytes=0-${blockSize - 1}")
                    .GET()
                    .build()
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            return response.body().use { input ->
                if (response.statusCode() != HTTP_PARTIAL_CONTENT) return@use null
                val contentRange =
                    checkNotNull(parseContentRange(response.headers().firstValue("content-range").orElse(null))) {
                        "Remote PDF response omitted Content-Range."
                    }
                check(contentRange.start == 0L && contentRange.total > 0L) {
                    "Remote PDF returned an invalid Content-Range."
                }
                val expected = min(blockSize.toLong(), contentRange.total).toInt()
                check(contentRange.endInclusive == expected - 1L) { "Remote PDF probe returned a mismatched range." }
                val firstBlock = withContext(ioDispatcher) { input.readNBytes(expected) }
                check(firstBlock.size == expected) { "Remote PDF probe returned an incomplete first block." }
                val validator =
                    response.headers().firstValue("etag").orElseGet {
                        response.headers().firstValue("last-modified").orElse(null)
                    }
                withContext(ioDispatcher) {
                    Files.createDirectories(cachePath.parent)
                    RandomAccessFile(cachePath.toFile(), "rw").use { it.setLength(contentRange.total) }
                }
                val manifestStore = HttpRangePdfManifestStore(ioDispatcher)
                HttpRangePdfSource(
                    httpClient = httpClient,
                    remoteUri = uri,
                    cachePath = cachePath,
                    length = contentRange.total,
                    validator = validator,
                    blockSize = blockSize,
                    ioDispatcher = ioDispatcher,
                    revision = System.nanoTime(),
                    cached = BooleanArray(((contentRange.total + blockSize - 1L) / blockSize).toInt()),
                    manifestStore = manifestStore,
                ).also { it.seedFirstBlock(firstBlock) }
            }
        }

        suspend fun resumeOrNull(
            httpClient: HttpClient,
            cachePath: Path,
            ioDispatcher: CoroutineDispatcher,
        ): HttpRangePdfSource? {
            val manifestStore = HttpRangePdfManifestStore(ioDispatcher)
            val manifest = manifestStore.read(cachePath) ?: return null
            val validLength = withContext(ioDispatcher) { Files.size(cachePath) == manifest.length }
            return if (validLength) {
                HttpRangePdfSource(
                    httpClient = httpClient,
                    remoteUri = URI(manifest.uri),
                    cachePath = cachePath,
                    length = manifest.length,
                    validator = manifest.validator,
                    blockSize = manifest.blockSize,
                    ioDispatcher = ioDispatcher,
                    revision = manifest.revision,
                    cached = manifest.cachedBlocks,
                    manifestStore = manifestStore,
                )
            } else {
                null
            }
        }
    }
}

private fun rangeManifest(
    uri: URI,
    length: Long,
    validator: String?,
    blockSize: Int,
    revision: Long,
    cached: BooleanArray,
): HttpRangePdfManifest =
    HttpRangePdfManifest(
        uri = uri.toString(),
        length = length,
        validator = validator,
        blockSize = blockSize,
        revision = revision,
        cachedBlocks = cached,
    )

private fun cachedBytes(
    cached: BooleanArray,
    length: Long,
    blockSize: Int,
): Long =
    cached.indices
        .filter { cached[it] }
        .sumOf { blockIndex -> pdfBlockLength(blockIndex, length, blockSize).toLong() }

private fun blockIndexFor(
    range: PdfByteRange,
    blockSize: Int,
    blockCount: Int,
): Int {
    require(range.offset >= 0L && range.offset % blockSize == 0L) { "Remote PDF range must be block-aligned." }
    val index = range.offset / blockSize
    require(index in 0 until blockCount) { "Remote PDF range starts outside the document." }
    return index.toInt()
}

private fun pdfBlockRange(
    blockIndex: Int,
    length: Long,
    blockSize: Int,
): PdfByteRange =
    PdfByteRange(
        offset = blockIndex.toLong() * blockSize,
        length = pdfBlockLength(blockIndex, length, blockSize),
    )

private fun pdfBlockLength(
    blockIndex: Int,
    length: Long,
    blockSize: Int,
): Int = min(blockSize.toLong(), length - blockIndex.toLong() * blockSize).toInt()

private data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private fun parseContentRange(value: String?): ContentRange? {
    val match = value?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
    return ContentRange(
        start = match.groupValues[1].toLong(),
        endInclusive = match.groupValues[2].toLong(),
        total = match.groupValues[3].toLong(),
    )
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, failure ->
            if (failure == null) {
                continuation.resume(value)
            } else {
                val cause = (failure as? CompletionException)?.cause ?: failure
                continuation.resumeWithException(cause)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }

private val CONTENT_RANGE_REGEX =
    Regex(
        pattern = "bytes (\\d+)-(\\d+)/(\\d+)",
        option = RegexOption.IGNORE_CASE,
    )
private const val HTTP_PARTIAL_CONTENT = 206
