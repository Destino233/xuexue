// This test deliberately uses real background dispatchers to exercise cancellation across the runner boundary.
@file:Suppress("InjectDispatcher")

package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.AnalysisComputeBackend
import dev.xuexue.platform.AnalysisPrivacy
import dev.xuexue.platform.AnalysisProvenance
import dev.xuexue.platform.ChapterStructureDetector
import dev.xuexue.platform.DocumentAnalysisEngine
import dev.xuexue.platform.DocumentAnalysisEngineDescriptor
import dev.xuexue.platform.DocumentAnalysisEngineRegistry
import dev.xuexue.platform.DocumentAnalysisRequest
import dev.xuexue.platform.DocumentAnalysisSettings
import dev.xuexue.platform.NativePdfLayoutEngine
import dev.xuexue.platform.PaddleOcrServiceManager
import dev.xuexue.platform.PdfAnalysisCache
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfOutlineRepository
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPageLayout
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterRequest
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PdfSearchMatch
import dev.xuexue.platform.PdfStructuredTextPage
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfDocumentAnalysisRunnerTest {
    @Test
    fun rasterEngineReceivesRenderedPixelDimensions() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-analysis-raster-size")
            Files.writeString(root.resolve("book.pdf"), "raster-fixture")
            val capturedRequest = CompletableDeferred<DocumentAnalysisRequest>()
            val backend =
                object : PdfReaderBackend {
                    override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(pageCount = 1)

                    override suspend fun pageDimensions(
                        path: Path,
                        pageIndex: Int,
                    ): PdfPageDimensions = PdfPageDimensions(612f, 792f)

                    override suspend fun render(request: PdfRasterRequest): PdfPageRaster =
                        PdfPageRaster(
                            pageIndex = request.pageIndex,
                            pixelWidth = 120,
                            pixelHeight = 160,
                            rgbaBytes =
                                ByteArray(120 * 160 * 4) { index ->
                                    if (index % 4 == 3) (-1).toByte() else 255.toByte()
                                },
                        )

                    override suspend fun search(
                        path: Path,
                        query: String,
                    ): List<PdfSearchMatch> = emptyList()

                    override suspend fun extractPageText(
                        path: Path,
                        pageIndex: Int,
                    ): String = ""

                    override suspend fun structuredText(
                        path: Path,
                        pageIndex: Int,
                    ): PdfStructuredTextPage? = null
                }
            val engine =
                object : DocumentAnalysisEngine {
                    override val descriptor =
                        DocumentAnalysisEngineDescriptor(
                            engineId = "test-raster-engine",
                            displayName = "测试栅格引擎",
                            engineVersion = "test",
                            backends = setOf(AnalysisComputeBackend.CPU),
                            privacy = AnalysisPrivacy.LOCAL,
                        )

                    override suspend fun analyzePage(request: DocumentAnalysisRequest): PdfPageLayout {
                        capturedRequest.complete(request)
                        return PdfPageLayout(
                            pageIndex = request.pageIndex,
                            width = request.width,
                            height = request.height,
                            blocks = emptyList(),
                            engine = AnalysisProvenance(descriptor.engineId, descriptor.engineVersion),
                        )
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sidecar = PaddleOcrServiceManager(ioDispatcher = Dispatchers.IO)
            val runner = analysisRunner(root, backend, scope, sidecar)
            val document = DocumentTab("book.pdf", DocumentKind.PDF, "", pinned = true)
            try {
                runner.analyze(
                    document,
                    engine,
                    DocumentAnalysisSettings(globalEngineId = engine.descriptor.engineId, dpi = 200),
                )
                val actualRequest = withTimeout(2_000) { capturedRequest.await() }
                assertEquals(120, actualRequest.width)
                assertEquals(160, actualRequest.height)
                withTimeout(2_000) {
                    while (document.pdfOutlineAnalyzing) delay(10)
                }
            } finally {
                runner.close()
                scope.cancel()
                sidecar.close()
                deleteTree(root)
            }
        }

    @Test
    fun cancellingAnalysisStopsBeforeSchedulingTheNextPage() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-analysis-cancel")
            val pdf = root.resolve("book.pdf")
            Files.writeString(pdf, "fixture")
            val started = CompletableDeferred<Unit>()
            val processedPages = mutableListOf<Int>()
            val backend = blockingBackend(started, processedPages)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sidecar = PaddleOcrServiceManager(ioDispatcher = Dispatchers.IO)
            val services =
                PdfAnalysisServices(
                    workspaceService = WorkspaceService(),
                    backend = backend,
                    workspaceRoot = { root },
                    engineRegistry = DocumentAnalysisEngineRegistry.default(),
                    repository = PdfOutlineRepository(WorkspaceService()),
                    cache = PdfAnalysisCache(root.resolve("cache")),
                    detector = ChapterStructureDetector(),
                    sidecar = sidecar,
                )
            val runner =
                PdfDocumentAnalysisRunner(
                    scope = scope,
                    services = services,
                    taskController = WorkbenchTaskController { },
                    ioDispatcher = Dispatchers.Default,
                    callbacks =
                        PdfAnalysisCallbacks(
                            onMessage = {},
                            onError = {},
                            persistWorkbench = {},
                        ),
                )
            val document = DocumentTab("book.pdf", DocumentKind.PDF, "", pinned = true)
            try {
                runner.analyze(
                    document,
                    NativePdfLayoutEngine(),
                    DocumentAnalysisSettings(globalEngineId = "native-pdf-layout"),
                )
                withTimeout(2_000) { started.await() }

                runner.cancel(document)
                withTimeout(2_000) {
                    while (document.pdfOutlineAnalyzing) delay(10)
                }

                assertTrue(processedPages.contains(0))
                assertFalse(processedPages.contains(1))
            } finally {
                runner.close()
                scope.cancel()
                sidecar.close()
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }

    @Test
    fun differentDocumentsShareOneAnalysisPermit() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-analysis-serial")
            Files.writeString(root.resolve("first.pdf"), "first-fixture")
            Files.writeString(root.resolve("second.pdf"), "second-fixture")
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val backend =
                trackingBackend { path, _ ->
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                    try {
                        if (path.fileName.toString() == "first.pdf") {
                            firstEntered.complete(Unit)
                            releaseFirst.await(2, TimeUnit.SECONDS)
                        } else {
                            secondEntered.complete(Unit)
                        }
                        emptyStructuredText()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sidecar = PaddleOcrServiceManager(ioDispatcher = Dispatchers.IO)
            val runner = analysisRunner(root, backend, scope, sidecar)
            val first = DocumentTab("first.pdf", DocumentKind.PDF, "", pinned = true)
            val second = DocumentTab("second.pdf", DocumentKind.PDF, "", pinned = true)
            try {
                val settings = DocumentAnalysisSettings(globalEngineId = "native-pdf-layout")
                runner.analyze(first, NativePdfLayoutEngine(), settings)
                withTimeout(2_000) { firstEntered.await() }
                runner.analyze(second, NativePdfLayoutEngine(), settings)

                assertFalse(
                    withTimeoutOrNull(250) {
                        secondEntered.await()
                        true
                    } == true,
                )
                releaseFirst.countDown()
                withTimeout(2_000) {
                    while (first.pdfOutlineAnalyzing || second.pdfOutlineAnalyzing) delay(10)
                }
                assertEquals(1, maximumActive.get())
            } finally {
                runner.close()
                scope.cancel()
                sidecar.close()
                deleteTree(root)
            }
        }

    @Test
    fun closeWaitsForAnInFlightAnalysisBeforeReturning() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-analysis-close")
            Files.writeString(root.resolve("book.pdf"), "close-fixture")
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val backend =
                trackingBackend { _, _ ->
                    started.complete(Unit)
                    release.await(2, TimeUnit.SECONDS)
                    emptyStructuredText()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sidecar = PaddleOcrServiceManager(ioDispatcher = Dispatchers.IO)
            val runner = analysisRunner(root, backend, scope, sidecar)
            val document = DocumentTab("book.pdf", DocumentKind.PDF, "", pinned = true)
            try {
                runner.analyze(
                    document,
                    NativePdfLayoutEngine(),
                    DocumentAnalysisSettings(globalEngineId = "native-pdf-layout"),
                )
                withTimeout(2_000) { started.await() }

                val closing = async(Dispatchers.Default) { runner.close() }
                delay(100)
                assertFalse(closing.isCompleted)
                release.countDown()
                withTimeout(2_000) { closing.await() }
            } finally {
                release.countDown()
                runner.close()
                scope.cancel()
                sidecar.close()
                deleteTree(root)
            }
        }

    private fun blockingBackend(
        started: CompletableDeferred<Unit>,
        processedPages: MutableList<Int>,
    ): PdfReaderBackend =
        object : PdfReaderBackend {
            override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(pageCount = 2)

            override suspend fun pageDimensions(
                path: Path,
                pageIndex: Int,
            ): PdfPageDimensions {
                synchronized(processedPages) { processedPages += pageIndex }
                return PdfPageDimensions(612f, 792f)
            }

            override suspend fun render(request: PdfRasterRequest): PdfPageRaster =
                PdfPageRaster(
                    request.pageIndex,
                    1,
                    1,
                    byteArrayOf(0, 0, 0, -1),
                )

            override suspend fun search(
                path: Path,
                query: String,
            ): List<PdfSearchMatch> = emptyList()

            override suspend fun extractPageText(
                path: Path,
                pageIndex: Int,
            ): String = ""

            override suspend fun structuredText(
                path: Path,
                pageIndex: Int,
            ): PdfStructuredTextPage {
                started.complete(Unit)
                awaitCancellation()
            }
        }

    private fun analysisRunner(
        root: Path,
        backend: PdfReaderBackend,
        scope: CoroutineScope,
        sidecar: PaddleOcrServiceManager,
    ): PdfDocumentAnalysisRunner =
        PdfDocumentAnalysisRunner(
            scope = scope,
            services =
                PdfAnalysisServices(
                    workspaceService = WorkspaceService(),
                    backend = backend,
                    workspaceRoot = { root },
                    engineRegistry = DocumentAnalysisEngineRegistry.default(),
                    repository = PdfOutlineRepository(WorkspaceService()),
                    cache = PdfAnalysisCache(root.resolve("cache")),
                    detector = ChapterStructureDetector(),
                    sidecar = sidecar,
                ),
            taskController = WorkbenchTaskController { },
            ioDispatcher = Dispatchers.Default,
            callbacks = PdfAnalysisCallbacks({}, {}, {}),
        )

    private fun trackingBackend(
        structuredTextProvider: suspend (Path, Int) -> PdfStructuredTextPage,
    ): PdfReaderBackend =
        object : PdfReaderBackend {
            override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(pageCount = 1)

            override suspend fun pageDimensions(
                path: Path,
                pageIndex: Int,
            ): PdfPageDimensions = PdfPageDimensions(612f, 792f)

            override suspend fun render(request: PdfRasterRequest): PdfPageRaster =
                PdfPageRaster(
                    request.pageIndex,
                    1,
                    1,
                    byteArrayOf(0, 0, 0, -1),
                )

            override suspend fun search(
                path: Path,
                query: String,
            ): List<PdfSearchMatch> = emptyList()

            override suspend fun extractPageText(
                path: Path,
                pageIndex: Int,
            ): String = ""

            override suspend fun structuredText(
                path: Path,
                pageIndex: Int,
            ): PdfStructuredTextPage = structuredTextProvider(path, pageIndex)
        }

    private fun emptyStructuredText(): PdfStructuredTextPage =
        PdfStructuredTextPage(
            widthPoints = 612f,
            heightPoints = 792f,
            blocks = emptyList(),
        )

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
