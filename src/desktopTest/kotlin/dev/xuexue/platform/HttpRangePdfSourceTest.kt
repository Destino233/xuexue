package dev.xuexue.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpRangePdfSourceTest {
    @Test
    fun rangeSourceCoalescesBlocksAndFinalizesExactBytes() =
        runBlocking {
            val sourceBytes = testPdfBytes(paddingBeforeXref = TEST_PADDING_BYTES)
            val root = Files.createTempDirectory("xuexue-range-source")
            testIoDispatcher().use { ioDispatcher ->
                RangePdfTestServer(sourceBytes).use { server ->
                    val cache = root.resolve("remote.pdf.part")
                    val target = root.resolve("remote.pdf")
                    var source =
                        assertNotNull(
                            HttpRangePdfSource.openOrNull(
                                httpClient = HttpClient.newHttpClient(),
                                uri = server.uri,
                                cachePath = cache,
                                ioDispatcher = ioDispatcher,
                                blockSize = TEST_BLOCK_BYTES,
                            ),
                        )
                    try {
                        val lastOffset = (sourceBytes.lastIndex / TEST_BLOCK_BYTES * TEST_BLOCK_BYTES).toLong()
                        val lastRange = PdfByteRange(lastOffset, sourceBytes.size - lastOffset.toInt())
                        coroutineScope {
                            val first = async { source.read(lastRange) }
                            val second = async { source.read(lastRange) }
                            assertContentEquals(first.await(), second.await())
                        }

                        assertEquals(2, server.rangeHeaders.size)
                        val loadedBeforeRestart = source.bytesLoaded
                        source.close()
                        source =
                            assertNotNull(
                                HttpRangePdfSource.resumeOrNull(
                                    httpClient = HttpClient.newHttpClient(),
                                    cachePath = cache,
                                    ioDispatcher = ioDispatcher,
                                ),
                            )
                        assertEquals(loadedBeforeRestart, source.bytesLoaded)
                        val requestsBeforeCachedRead = server.rangeHeaders.size
                        source.read(lastRange)
                        assertEquals(requestsBeforeCachedRead, server.rangeHeaders.size)
                        source.cacheAll { _, _ -> }
                        source.finalizeTo(target)

                        assertTrue(source.complete)
                        assertEquals(sourceBytes.size.toLong(), source.bytesLoaded)
                        assertContentEquals(sourceBytes, Files.readAllBytes(target))
                    } finally {
                        source.close()
                    }
                }
            }
            root.toFile().deleteRecursively()
            Unit
        }

    @Test
    fun muPdfBackendOpensAndRendersFromDemandDrivenHttpRanges() =
        runBlocking {
            val sourceBytes = testPdfBytes(paddingBeforeXref = LARGE_TEST_PADDING_BYTES)
            val root = Files.createTempDirectory("xuexue-range-backend")
            testIoDispatcher().use { ioDispatcher ->
                RangePdfTestServer(sourceBytes).use { server ->
                    val cache = root.resolve("remote.pdf.part")
                    val target = Files.createFile(root.resolve("remote.pdf"))
                    val source =
                        assertNotNull(
                            HttpRangePdfSource.openOrNull(
                                httpClient = HttpClient.newHttpClient(),
                                uri = server.uri,
                                cachePath = cache,
                                ioDispatcher = ioDispatcher,
                            ),
                        )
                    val registry = ProgressivePdfRegistry().apply { register(target, source) }
                    val backend = MuPdfReaderBackend(progressiveSources = registry)
                    try {
                        assertEquals(1, backend.inspect(target).pageCount)
                        val raster =
                            backend.render(
                                PdfRasterRequest(
                                    path = target,
                                    pageIndex = 0,
                                    dpi = 48f,
                                    purpose = PdfRasterPurpose.THUMBNAIL,
                                ),
                            )
                        assertTrue(raster.byteSize > 0)
                        assertTrue(server.requestedOffsets().contains(0L))
                        assertTrue(server.requestedOffsets().any { it >= PROGRESSIVE_PDF_BLOCK_BYTES })
                        val fetchSummary = "Fetched ${source.bytesLoaded}/${source.length}: ${server.rangeHeaders}"
                        assertTrue(source.bytesLoaded < source.length, "Expected demand loading. $fetchSummary")
                    } finally {
                        backend.close()
                    }
                }
            }
            root.toFile().deleteRecursively()
            Unit
        }

    private companion object {
        const val TEST_BLOCK_BYTES = 128
        const val TEST_PADDING_BYTES = 1_100
        const val LARGE_TEST_PADDING_BYTES = PROGRESSIVE_PDF_BLOCK_BYTES * 64
    }
}

private fun testIoDispatcher(): ExecutorCoroutineDispatcher =
    Executors
        .newSingleThreadExecutor()
        .asCoroutineDispatcher()

private class RangePdfTestServer(
    private val bytes: ByteArray,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val rangeHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val uri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/document.pdf")

    init {
        server.createContext("/document.pdf", ::handle)
        server.start()
    }

    fun requestedOffsets(): List<Long> =
        rangeHeaders.mapNotNull { header ->
            RANGE_REGEX
                .matchEntire(header)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val match = rangeHeader?.let(RANGE_REGEX::matchEntire)
        if (match == null) {
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1)
            exchange.close()
            return
        }
        rangeHeaders += rangeHeader
        val start = match.groupValues[1].toInt()
        val requestedEnd = match.groupValues[2].toInt()
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
        const val TEST_ETAG = "\"xuexue-test-pdf\""
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

private fun testPdfBytes(paddingBeforeXref: Int): ByteArray {
    val content = "BT /F1 12 Tf 72 720 Td (XueXue range PDF) Tj ET\n"
    val objects =
        buildList {
            addAll(
                listOf(
                    "<< /Type /Catalog /Pages 2 0 R >>",
                    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                        "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                    "<< /Length ${content.length} >>\nstream\n${content}endstream",
                ),
            )
            if (paddingBeforeXref > 0) {
                val padding = "x".repeat(paddingBeforeXref)
                add("<< /Length ${padding.length} >>\nstream\n$padding\nendstream")
            }
        }
    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf(0)
    objects.forEachIndexed { index, body ->
        offsets += pdf.length
        pdf
            .append(index + 1)
            .append(" 0 obj\n")
            .append(body)
            .append("\nendobj\n")
    }
    val xrefOffset = pdf.length
    pdf.append("xref\n0 ").append(objects.size + 1).append("\n0000000000 65535 f \n")
    offsets.drop(1).forEach { offset -> pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n") }
    pdf
        .append("trailer\n<< /Size ")
        .append(objects.size + 1)
        .append(" /Root 1 0 R >>\nstartxref\n")
        .append(xrefOffset)
        .append("\n%%EOF\n")
    return pdf.toString().toByteArray(StandardCharsets.US_ASCII)
}
