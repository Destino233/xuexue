@file:Suppress("InjectDispatcher")

package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfNativeOutlineNode
import dev.xuexue.platform.PdfOutlineDestination
import dev.xuexue.platform.PdfOutlineDocument
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterRequest
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PdfSearchMatch
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class PdfOutlineExportServiceTest {
    @Test
    fun closeWaitsForAnInFlightExportBeforeTheBackendCanBeClosed() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-outline-export-close")
            val source = root.resolve("book.pdf")
            Files.writeString(source, "fixture")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val backend = BlockingExportBackend(started, release)
            val service =
                PdfOutlineExportService(
                    scope = scope,
                    workspaceService = WorkspaceService(),
                    backend = backend,
                    workspaceRoot = { root },
                    taskController = WorkbenchTaskController { },
                    ioDispatcher = Dispatchers.Default,
                    onMessage = {},
                    onError = {},
                )
            val document = DocumentTab("book.pdf", DocumentKind.PDF, "", pinned = true)
            val outline =
                PdfOutlineDocument(
                    relativePath = "book.pdf",
                    documentFingerprint = "0".repeat(64),
                    nodes =
                        listOf(
                            PdfOutlineNode(
                                id = "chapter",
                                title = "Chapter",
                                destination = PdfOutlineDestination(0),
                                source = PdfOutlineSource.MANUAL,
                                confidence = 1f,
                                reviewState = PdfOutlineReviewState.MANUAL,
                            ),
                        ),
                )
            try {
                service.export(
                    document = document,
                    outline = outline,
                    outputPath = root.resolve("out.pdf"),
                    strategy = PdfOutlineExportStrategy.REPLACE,
                    confirmedOnly = false,
                )
                withTimeout(2_000) { started.await() }
                val closing = async(Dispatchers.Default) { service.close() }
                delay(100)
                assertFalse(closing.isCompleted)
                release.complete(Unit)
                withTimeout(2_000) { closing.await() }
            } finally {
                release.complete(Unit)
                service.close()
                scope.cancel()
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }

    private class BlockingExportBackend(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : PdfReaderBackend {
        override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(1)

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

        override suspend fun exportOutline(
            path: Path,
            outputPath: Path,
            payload: ByteArray,
            strategy: PdfOutlineExportStrategy,
        ) {
            Files.copy(path, outputPath)
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }

        override suspend fun nativeOutline(path: Path): List<PdfNativeOutlineNode> =
            listOf(
                PdfNativeOutlineNode("Chapter", 0, null, null),
            )
    }
}
