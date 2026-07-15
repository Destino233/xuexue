package dev.xuexue

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.xuexue.core.ToolWindow
import dev.xuexue.platform.ProgressivePdfRegistry
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkbenchRemotePdfControllerTest {
    @Test
    fun interruptedRangeDownloadPublishesProgressAndRetriesFromTheRetainedCache() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-remote-pdf-state")
            val bytes = ByteArray(TEST_PDF_BYTES) { index -> (index % Byte.MAX_VALUE).toByte() }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val sources = ProgressivePdfRegistry()
            val tasks = WorkbenchTaskController {}
            val opened = mutableListOf<String>()
            FailingRangeServer(bytes).use { server ->
                val controller =
                    WorkbenchRemotePdfController(
                        scope = scope,
                        infrastructure = RemotePdfInfrastructure(WorkspaceService(), sources),
                        taskController = tasks,
                        workspaceRoot = { root },
                        offlineMode = { false },
                        reloadWorkspace = {},
                        openRelative = { path, _ -> opened += path },
                        openToolWindow = { _: ToolWindow -> },
                        onStatus = {},
                        onError = {},
                    )
                try {
                    controller.download(server.uri.toString())
                    val failedState =
                        withTimeout(TEST_TIMEOUT_MILLIS) {
                            while (
                                controller.downloadStates.values.none {
                                    it.phase == RemotePdfDownloadPhase.FAILED
                                }
                            ) {
                                delay(POLL_MILLIS)
                            }
                            controller.downloadStates.values.single()
                        }

                    assertTrue(failedState.downloadedBytes > 0L)
                    assertTrue(failedState.progress in 0f..<1f)
                    assertEquals(listOf(failedState.relativePath), opened)

                    controller.download(failedState.sourceUrl)
                    withTimeout(TEST_TIMEOUT_MILLIS) {
                        while (tasks.tasks.isNotEmpty() || controller.downloadStates.isNotEmpty()) delay(POLL_MILLIS)
                    }

                    assertContentEquals(bytes, Files.readAllBytes(root.resolve(failedState.relativePath)))
                } finally {
                    controller.reset()
                    scope.cancel()
                }
            }
            root.toFile().deleteRecursively()
            Unit
        }

    private companion object {
        const val TEST_PDF_BYTES = 3 * 64 * 1024 + 317
        const val TEST_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}

private class FailingRangeServer(
    private val bytes: ByteArray,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val failNextNonInitialRange = AtomicBoolean(true)
    val uri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/remote.pdf")

    init {
        server.createContext("/remote.pdf", ::handle)
        server.start()
    }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")?.let(RANGE_REGEX::matchEntire)
        if (range == null) {
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1)
            exchange.close()
            return
        }
        val start = range.groupValues[1].toInt()
        if (start > 0 && failNextNonInitialRange.compareAndSet(true, false)) {
            exchange.sendResponseHeaders(HTTP_SERVER_ERROR, -1)
            exchange.close()
            return
        }
        val requestedEnd = range.groupValues[2].toInt()
        val endInclusive = min(requestedEnd, bytes.lastIndex)
        val body = bytes.copyOfRange(start, endInclusive + 1)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/${bytes.size}")
        exchange.responseHeaders.add("ETag", TEST_ETAG)
        exchange.sendResponseHeaders(HTTP_PARTIAL_CONTENT, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        val RANGE_REGEX = Regex("bytes=(\\d+)-(\\d+)")
        const val TEST_ETAG = "\"xuexue-reader-progress\""
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_SERVER_ERROR = 500
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
