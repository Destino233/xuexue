package dev.xuexue.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@Suppress("TooManyFunctions") // Independent backend scenarios intentionally share native test fixtures.
class PdfReaderBackendTest {
    @Test
    fun muPdfImplementsTheUiBackendContract() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-reader", ".pdf")
            Files.writeString(path, testPdfWithLinks(), StandardCharsets.US_ASCII)
            val metrics = PdfPerformanceMetrics()
            val backend = MuPdfReaderBackend(metrics)
            try {
                assertEquals("1.27.2", MuPdfNative.version)
                val metadata = backend.inspect(path)
                assertEquals(1, metadata.pageCount)
                assertEquals(PdfPageDimensions(612f, 792f), backend.pageDimensions(path, 0))
                assertTrue(backend.extractPageText(path, 0).contains("searchable text"))
                val searchMatch = backend.search(path, "SEARCHABLE").single()
                assertEquals(0, searchMatch.pageIndex)
                assertTrue(searchMatch.regions.isNotEmpty())
                val textBounds = searchMatch.regions.first()
                val selectionY = (textBounds.top + textBounds.bottom) / 2f * 792f
                val selection =
                    backend.selectText(
                        path = path,
                        pageIndex = 0,
                        start = PdfPagePoint(textBounds.left * 612f, selectionY),
                        end = PdfPagePoint(textBounds.right * 612f, selectionY),
                    )
                assertTrue(
                    selection.text.contains("searchable", ignoreCase = true),
                    "Expected the search-hit coordinates to select searchable, got '${selection.text}'.",
                )
                assertTrue(selection.regions.isNotEmpty())

                val links = backend.links(path, 0)
                assertEquals(2, links.size, "Expected external and internal links, got $links")
                val externalLink = links.single { it.external }
                assertEquals("https://example.com", externalLink.uri)
                assertTrue(externalLink.region.left < externalLink.region.right)
                val internalLink = links.single { !it.external }
                assertEquals(0, internalLink.targetPage)

                val raster =
                    backend.render(
                        PdfRasterRequest(path, pageIndex = 0, dpi = 48f, purpose = PdfRasterPurpose.THUMBNAIL),
                    )
                assertTrue(raster.pixelWidth > 0)
                assertTrue(raster.pixelHeight > 0)
                assertEquals(raster.pixelWidth * raster.pixelHeight * RGBA_BYTES_PER_PIXEL, raster.byteSize)
                assertEquals(
                    listOf(255, 255, 255, 255),
                    raster.rgbaBytes
                        .take(4)
                        .map(Byte::toUByte)
                        .map(UByte::toInt),
                )

                val viewport = PdfNormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
                val tile =
                    backend.render(
                        PdfRasterRequest(
                            path = path,
                            pageIndex = 0,
                            dpi = 72f,
                            purpose = PdfRasterPurpose.VIEWPORT,
                            viewport = viewport,
                        ),
                    )
                assertEquals(306, tile.pixelWidth)
                assertEquals(396, tile.pixelHeight)
                assertEquals(viewport, tile.viewport)

                val performance = metrics.snapshot()
                assertEquals(2L, performance.renderAttempts)
                assertEquals(2L, performance.completedRenders)
                assertEquals(0L, performance.failedRenders)
                assertTrue(performance.renderP95Millis > 0.0)
                assertTrue(performance.visibleTileP95Millis > 0.0)
                assertTrue(checkNotNull(performance.firstViewportTileMillis) > 0.0)
                assertEquals(raster.byteSize.toLong() + tile.byteSize, performance.rasterBytesProduced)
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rasterCacheUsesDiscreteLodAndReusesTheRenderedPage() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-cache", ".pdf")
            try {
                val requestedDpi = mutableListOf<Float>()
                val backend =
                    CachingPdfReaderBackend(
                        recordingBackend { request ->
                            requestedDpi += request.dpi
                            testRaster(request.pageIndex, marker = 1)
                        },
                        maxRasterBytes = 1024,
                    )

                backend.render(PdfRasterRequest(path, 0, 101f, PdfRasterPurpose.VIEWPORT))
                backend.render(PdfRasterRequest(path, 0, 119f, PdfRasterPurpose.VIEWPORT))

                assertEquals(listOf(120f), requestedDpi)
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rasterCacheCanRetainOnlyThumbnailPurposes() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-purpose-cache", ".pdf")
            var renderCount = 0
            val backend =
                CachingPdfReaderBackend(
                    recordingBackend { request ->
                        renderCount++
                        testRaster(request.pageIndex, marker = renderCount.toByte())
                    },
                    maxRasterBytes = 1024,
                    cacheablePurposes = setOf(PdfRasterPurpose.THUMBNAIL),
                )
            try {
                val viewport = PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT)
                val thumbnail = PdfRasterRequest(path, 0, 36f, PdfRasterPurpose.THUMBNAIL)

                backend.render(viewport)
                backend.render(viewport)
                backend.render(thumbnail)
                backend.render(thumbnail)

                assertEquals(3, renderCount)
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun analysisRendersKeepRequestedDpiAndNeverEnterTheViewportCache() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-analysis-cache", ".pdf")
            try {
                val requestedDpi = mutableListOf<Float>()
                val backend =
                    CachingPdfReaderBackend(
                        recordingBackend { request ->
                            requestedDpi += request.dpi
                            testRaster(request.pageIndex, marker = 2)
                        },
                        maxRasterBytes = 1,
                    )
                val request = PdfRasterRequest(path, 0, 300f, PdfRasterPurpose.ANALYSIS)

                backend.render(request)
                backend.render(request)

                assertEquals(listOf(300f, 300f), requestedDpi)
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun nativeAnnotationsRenderOnlyInTheTransparentOverlayLayer() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-overlay", ".pdf")
            Files.writeString(path, testPdfWithVisibleAnnotation(), StandardCharsets.US_ASCII)
            val backend = MuPdfReaderBackend()
            try {
                // Analysis rasters keep byte-array pixels so the overlay content
                // can be asserted directly.
                val request =
                    PdfRasterRequest(
                        path = path,
                        pageIndex = 0,
                        dpi = 72f,
                        purpose = PdfRasterPurpose.ANALYSIS,
                    )
                val content = backend.render(request)
                val overlay = backend.render(request.copy(layer = PdfRasterLayer.NATIVE_OVERLAY))

                assertTrue(
                    !content.rgbaBytes.anyPixel(::isOpaqueRedPixel),
                    "The static content layer contains annotation red.",
                )
                assertTrue(
                    overlay.rgbaBytes.anyPixel(::isOpaqueRedPixel),
                    "The native overlay did not render its appearance.",
                )
                assertTrue(overlay.rgbaBytes.anyPixel { _, _, _, alpha -> alpha == 0 })
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rasterCacheDeduplicatesConcurrentRequests() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-in-flight", ".pdf")
            try {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var renderCount = 0
                val metrics = PdfPerformanceMetrics()
                val backend =
                    CachingPdfReaderBackend(
                        delegate =
                            recordingBackend { request ->
                                renderCount++
                                started.complete(Unit)
                                release.await()
                                testRaster(request.pageIndex, marker = 4)
                            },
                        metrics = metrics,
                    )
                val request = PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT)

                val first = async { backend.render(request) }
                started.await()
                val second = async { backend.render(request) }
                release.complete(Unit)

                assertEquals(first.await(), second.await())
                assertEquals(1, renderCount)
                assertEquals(1L, metrics.snapshot().coalescedRequests)
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rasterCacheInvalidatesWhenThePdfChanges() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-revision", ".pdf")
            try {
                var renderCount = 0
                val backend =
                    CachingPdfReaderBackend(
                        recordingBackend { request ->
                            renderCount++
                            testRaster(request.pageIndex, marker = renderCount.toByte())
                        },
                    )
                val request = PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT)

                backend.render(request)
                Files.write(path, byteArrayOf(1, 2, 3, 4))
                backend.render(request)

                assertEquals(2, renderCount)
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun rasterCacheReportsHitsMissesAndBudgetEvictions() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-metrics", ".pdf")
            val metrics = PdfPerformanceMetrics()
            val backend =
                CachingPdfReaderBackend(
                    delegate = recordingBackend { request -> testRaster(request.pageIndex, marker = 7) },
                    maxRasterBytes = TEST_RASTER_BYTES.toLong(),
                    metrics = metrics,
                )
            try {
                backend.render(PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT))
                backend.render(PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT))
                backend.render(PdfRasterRequest(path, 1, 96f, PdfRasterPurpose.VIEWPORT))

                val performance = metrics.snapshot()
                assertEquals(1L, performance.cacheHits)
                assertEquals(2L, performance.cacheMisses)
                assertEquals(0L, performance.coalescedRequests)
                assertEquals(1L, performance.cacheEvictions)
                assertEquals(TEST_RASTER_BYTES.toLong(), performance.cacheBytes)
                assertEquals(1.0 / 3.0, performance.cacheHitRate, absoluteTolerance = DOUBLE_TOLERANCE)
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun progressiveStreamRequestsOnlyTheMissingBlocks() {
        val source = testPdf().toByteArray(StandardCharsets.US_ASCII)
        val stream = MuPdfProgressiveStream(source.size.toLong(), TEST_PROGRESSIVE_BLOCK_BYTES)
        val requested = mutableListOf<PdfByteRange>()
        var documentHandle = 0L
        var displayListHandle = 0L
        try {
            documentHandle = withProgressiveData(stream, source, requested, stream::openDocument)
            val pageCount = withProgressiveData(stream, source, requested) { MuPdfNative.countPages(documentHandle) }
            displayListHandle =
                withProgressiveData(stream, source, requested) {
                    MuPdfNative.loadDisplayList(documentHandle, 0)
                }

            assertEquals(1, pageCount)
            assertTrue(displayListHandle != 0L)
            assertTrue(requested.size > 1)
            assertTrue(requested.any { it.offset == 0L })
            assertTrue(requested.any { it.offset > 0L })
            assertEquals(requested.distinct().sumOf { it.length }.toLong(), stream.bytesLoaded)
        } finally {
            if (displayListHandle != 0L) MuPdfNative.dropDisplayList(displayListHandle)
            if (documentHandle != 0L) MuPdfNative.closeDocument(documentHandle)
            stream.close()
        }
    }

    @Test
    fun cancellingAnActiveNativeRenderAbortsTheDisplayList() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-cancel", ".pdf")
            Files.writeString(path, testPdf(complexPageContent()), StandardCharsets.US_ASCII)
            val documentDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val renderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val renderStarted = CompletableDeferred<Unit>()
            val session = MuPdfDocumentSession(path, documentDispatcher)
            val processor = MuPdfPageProcessor(renderDispatcher) { renderStarted.complete(Unit) }
            try {
                processor.extractText(session, 0)
                val render =
                    async {
                        processor.render(
                            session,
                            PdfRasterRequest(
                                path = path,
                                pageIndex = 0,
                                dpi = 288f,
                                purpose = PdfRasterPurpose.VIEWPORT,
                            ),
                        )
                    }
                withTimeout(5.seconds) { renderStarted.await() }
                val cancellationLatency =
                    measureTime {
                        render.cancel()
                        assertFailsWith<CancellationException> { render.await() }
                    }
                assertTrue(cancellationLatency < 2.seconds, "Native cancellation took $cancellationLatency")
            } finally {
                session.close()
                documentDispatcher.close()
                renderDispatcher.close()
                Files.deleteIfExists(path)
            }
        }

    private fun recordingBackend(renderPage: suspend (PdfRasterRequest) -> PdfPageRaster): PdfReaderBackend =
        object : PdfReaderBackend {
            override suspend fun inspect(path: java.nio.file.Path): PdfDocumentMetadata = PdfDocumentMetadata(1)

            override suspend fun pageDimensions(
                path: java.nio.file.Path,
                pageIndex: Int,
            ): PdfPageDimensions = PdfPageDimensions(612f, 792f)

            override suspend fun render(request: PdfRasterRequest): PdfPageRaster = renderPage(request)

            override suspend fun search(
                path: java.nio.file.Path,
                query: String,
            ): List<PdfSearchMatch> = emptyList()

            override suspend fun extractPageText(
                path: java.nio.file.Path,
                pageIndex: Int,
            ): String = ""
        }

    private fun testRaster(
        pageIndex: Int,
        marker: Byte,
    ): PdfPageRaster =
        PdfPageRaster(
            pageIndex = pageIndex,
            pixelWidth = TEST_RASTER_EDGE,
            pixelHeight = TEST_RASTER_EDGE,
            rgbaBytes = ByteArray(TEST_RASTER_EDGE * TEST_RASTER_EDGE * RGBA_BYTES_PER_PIXEL) { marker },
        )

    private fun testPdf(
        content: String = "BT /F1 12 Tf 72 720 Td (XueXue searchable text) Tj ET\n",
        annotations: List<String> = emptyList(),
        extraObjects: List<String> = emptyList(),
    ): String {
        val annotationReferences =
            annotations.indices
                .joinToString(separator = " ", prefix = "[", postfix = "]") { index -> "${index + 6} 0 R" }
        val objects =
            mutableListOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                    "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R " +
                    (if (annotations.isEmpty()) "" else "/Annots $annotationReferences ") +
                    ">>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length ${content.length} >>\nstream\n${content}endstream",
            )
        objects += annotations
        objects += extraObjects
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, body ->
            offsets += pdf.length
            pdf.append(index + 1).append(" 0 obj\n")
            pdf.append(body).append("\nendobj\n")
        }
        val xrefOffset = pdf.length
        pdf.append("xref\n0 ").append(objects.size + 1).append("\n")
        pdf.append("0000000000 65535 f \n")
        offsets.drop(1).forEach { offset ->
            pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        pdf
            .append("trailer\n<< /Size ")
            .append(objects.size + 1)
            .append(" /Root 1 0 R >>\nstartxref\n")
            .append(xrefOffset)
            .append("\n%%EOF\n")
        return pdf.toString()
    }

    private fun testPdfWithLinks(): String =
        testPdf(
            annotations =
                listOf(
                    "<< /Type /Annot /Subtype /Link /Rect [72 700 200 724] /Border [0 0 0] " +
                        "/A << /S /URI /URI (https://example.com) >> >>",
                    "<< /Type /Annot /Subtype /Link /Rect [210 700 300 724] /Border [0 0 0] " +
                        "/Dest [3 0 R /Fit] >>",
                ),
        )

    private fun testPdfWithVisibleAnnotation(): String {
        val appearance = "1 0 0 rg 0 0 72 72 re f\n"
        return testPdf(
            annotations =
                listOf(
                    "<< /Type /Annot /Subtype /Stamp /Rect [72 620 144 692] " +
                        "/AP << /N 7 0 R >> >>",
                ),
            extraObjects =
                listOf(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 72 72] " +
                        "/Resources << >> /Length ${appearance.length} >>\nstream\n${appearance}endstream",
                ),
        )
    }

    private fun isOpaqueRedPixel(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ): Boolean = red > 200 && green < 80 && blue < 80 && alpha > 200

    private fun ByteArray.anyPixel(predicate: (Int, Int, Int, Int) -> Boolean): Boolean {
        for (index in indices step RGBA_BYTES_PER_PIXEL) {
            if (
                predicate(
                    this[index].toUByte().toInt(),
                    this[index + 1].toUByte().toInt(),
                    this[index + 2].toUByte().toInt(),
                    this[index + 3].toUByte().toInt(),
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun complexPageContent(): String =
        buildString {
            append("q 0.1 w\n")
            repeat(COMPLEX_VECTOR_COUNT) { index ->
                val x = index % 600
                val y = index / 600 % 780
                append(x).append(' ').append(y).append(" 1 1 re S\n")
            }
            append("Q\nBT /F1 12 Tf 72 720 Td (XueXue searchable text) Tj ET\n")
        }

    private companion object {
        const val COMPLEX_VECTOR_COUNT = 150_000
        const val TEST_RASTER_EDGE = 10
        const val RGBA_BYTES_PER_PIXEL = 4
        const val TEST_RASTER_BYTES = TEST_RASTER_EDGE * TEST_RASTER_EDGE * RGBA_BYTES_PER_PIXEL
        const val TEST_PROGRESSIVE_BLOCK_BYTES = 128
        const val DOUBLE_TOLERANCE = 0.000_001
    }
}

private fun <T> withProgressiveData(
    stream: MuPdfProgressiveStream,
    source: ByteArray,
    requested: MutableList<PdfByteRange>,
    operation: () -> T,
): T {
    repeat(MAX_PROGRESSIVE_RETRIES) {
        try {
            return operation()
        } catch (_: PdfTryLaterException) {
            val range = stream.requestedRange()
            val end = Math.addExact(range.offset, range.length.toLong()).toInt()
            assertTrue(range.offset >= 0L && end <= source.size)
            stream.provide(range, source.copyOfRange(range.offset.toInt(), end))
            requested += range
        }
    }
    error("MuPDF did not finish after $MAX_PROGRESSIVE_RETRIES progressive range requests.")
}

private const val MAX_PROGRESSIVE_RETRIES = 64
