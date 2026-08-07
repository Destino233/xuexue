package dev.xuexue.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

@Suppress("TooManyFunctions") // The backend groups the complete PdfReaderBackend contract around one MuPDF actor.
internal class MuPdfReaderBackend(
    private val metrics: PdfPerformanceMetrics = PdfPerformanceMetrics(),
    private val progressiveSources: ProgressivePdfRegistry = ProgressivePdfRegistry(),
) : PdfReaderBackend {
    private val documentDispatcher = newDaemonDispatcher("document", 1)
    private val renderThreadCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_RENDER_THREADS)
    private val renderDispatcher =
        newDaemonDispatcher(
            name = "render",
            threads = renderThreadCount,
        )
    private val progressiveJob = SupervisorJob()
    private val progressiveScope = CoroutineScope(progressiveJob + renderDispatcher)
    private val sessions = MuPdfSessionManager(documentDispatcher, progressiveSources, progressiveScope)
    private val pageProcessor = MuPdfPageProcessor(renderDispatcher)
    private val renderAdmission = PdfRenderAdmission(renderThreadCount)

    override suspend fun inspect(path: Path): PdfDocumentMetadata =
        withSession(path) { session ->
            PdfDocumentMetadata(session.pageCount())
        }

    override suspend fun pageDimensions(
        path: Path,
        pageIndex: Int,
    ): PdfPageDimensions = withSession(path) { session -> pageProcessor.pageDimensions(session, pageIndex) }

    override suspend fun render(request: PdfRasterRequest): PdfPageRaster {
        metrics.recordRenderStarted()
        val startedAt = System.nanoTime()
        return try {
            renderAdmission
                .withPermit {
                    withSession(request.path) { session ->
                        pageProcessor.render(session, request)
                    }
                }.also { raster ->
                    metrics.recordRenderCompleted(
                        durationNanos = System.nanoTime() - startedAt,
                        rasterBytes = raster.byteSize,
                        firstViewportCandidate =
                            request.purpose == PdfRasterPurpose.VIEWPORT &&
                                request.priority == PdfRasterPriority.VISIBLE &&
                                request.layer == PdfRasterLayer.CONTENT,
                    )
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            metrics.recordRenderCancelled(System.nanoTime() - startedAt)
            throw cancelled
        } catch (failure: IOException) {
            recordRenderFailure(startedAt, failure)
        } catch (failure: IllegalArgumentException) {
            recordRenderFailure(startedAt, failure)
        } catch (failure: IllegalStateException) {
            recordRenderFailure(startedAt, failure)
        }
    }

    override suspend fun search(
        path: Path,
        query: String,
    ): List<PdfSearchMatch> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return withSession(path) { session ->
            buildList {
                repeat(session.pageCount()) { pageIndex ->
                    currentCoroutineContext().ensureActive()
                    pageProcessor.searchPage(session, pageIndex, needle)?.let(::add)
                }
            }
        }
    }

    override suspend fun extractPageText(
        path: Path,
        pageIndex: Int,
    ): String =
        withSession(path) { session ->
            pageProcessor.extractText(session, pageIndex)
        }

    override suspend fun nativeOutline(path: Path): List<PdfNativeOutlineNode> =
        withSession(path) { session ->
            PdfNativePayloadCodec.decodeOutline(session.loadOutline())
        }

    override suspend fun structuredText(
        path: Path,
        pageIndex: Int,
    ): PdfStructuredTextPage = withSession(path) { session -> session.extractStructuredText(pageIndex) }

    override suspend fun exportOutline(
        path: Path,
        outputPath: Path,
        payload: ByteArray,
        strategy: PdfOutlineExportStrategy,
    ) {
        require(path.toAbsolutePath().normalize() != outputPath.toAbsolutePath().normalize()) {
            "PDF outline export must use a new output path."
        }
        // MuPDF can append an Outline update safely only when the destination already
        // contains the original byte stream; repaired PDFs fall back to a full save in C.
        Files.copy(path, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        withContext(documentDispatcher) {
            MuPdfNative.exportOutline(
                sourcePathUtf8 = path.toString().encodeToByteArray(),
                outputPathUtf8 = outputPath.toString().encodeToByteArray(),
                payload = payload,
                strategy = strategy.ordinal,
            )
        }
    }

    override suspend fun selectText(
        path: Path,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection =
        withSession(path) { session ->
            pageProcessor.selectText(session, pageIndex, start, end)
        }

    override suspend fun links(
        path: Path,
        pageIndex: Int,
    ): List<PdfLink> =
        withSession(path) { session ->
            decodePdfLinks(session.loadLinks(pageIndex))
        }

    override suspend fun trimIdleResources() {
        sessions.trimIdleSessionsAndStore()
    }

    override fun close() {
        runBlocking {
            progressiveJob.cancelAndJoin()
            sessions.close()
        }
        progressiveSources.close()
        documentDispatcher.close()
        renderDispatcher.close()
        MuPdfNative.emptyStore()
    }

    private suspend fun <T> withSession(
        path: Path,
        operation: suspend (MuPdfDocumentSession) -> T,
    ): T {
        val lease = sessions.acquire(path)
        return try {
            operation(lease.session)
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    private fun recordRenderFailure(
        startedAt: Long,
        failure: Exception,
    ): Nothing {
        metrics.recordRenderFailed(System.nanoTime() - startedAt)
        throw failure
    }

    private companion object {
        const val MAX_RENDER_THREADS = 6

        fun newDaemonDispatcher(
            name: String,
            threads: Int,
        ): ExecutorCoroutineDispatcher {
            val sequence = AtomicInteger()
            val factory =
                ThreadFactory { runnable ->
                    Thread(runnable, "xuexue-mupdf-$name-${sequence.incrementAndGet()}").apply { isDaemon = true }
                }
            return Executors.newFixedThreadPool(threads, factory).asCoroutineDispatcher()
        }
    }
}

internal class PdfRenderAdmission(
    maxInFlight: Int,
) {
    private val permits = Semaphore(maxInFlight)

    init {
        require(maxInFlight > 0) { "PDF render admission must allow at least one task." }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        permits.acquire()
        return try {
            block()
        } finally {
            permits.release()
        }
    }
}

private fun decodePdfLinks(payload: ByteArray): List<PdfLink> {
    if (payload.isEmpty()) return emptyList()
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    require(buffer.remaining() >= Int.SIZE_BYTES) { "MuPDF returned a truncated link payload." }
    val count = buffer.int
    require(count >= 0) { "MuPDF returned a negative link count." }
    return buildList(count) {
        repeat(count) {
            require(buffer.remaining() >= PDF_LINK_FIXED_BYTES) { "MuPDF returned a truncated link record." }
            val left = buffer.float
            val top = buffer.float
            val right = buffer.float
            val bottom = buffer.float
            val external = buffer.int != 0
            val targetPage = buffer.int.takeIf { candidate -> candidate >= 0 }
            val uriLength = buffer.int
            require(uriLength >= 0 && buffer.remaining() >= uriLength) { "MuPDF returned an invalid link URI." }
            val uriBytes = ByteArray(uriLength)
            buffer.get(uriBytes)
            if (left < right && top < bottom) {
                add(
                    PdfLink(
                        region = PdfNormalizedRect(left, top, right, bottom),
                        uri = uriBytes.decodeToString(),
                        external = external,
                        targetPage = targetPage,
                    ),
                )
            }
        }
    }.also {
        require(!buffer.hasRemaining()) { "MuPDF returned trailing link data." }
    }
}

private const val PDF_LINK_FIXED_BYTES = 7 * Int.SIZE_BYTES
