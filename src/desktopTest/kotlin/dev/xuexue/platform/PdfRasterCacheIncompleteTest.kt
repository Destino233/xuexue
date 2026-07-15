package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfRasterCacheIncompleteTest {
    @Test
    fun incompleteRastersAreNotCached() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-incomplete-cache", ".pdf")
            var renderCount = 0
            val backend = CachingPdfReaderBackend(incompleteThenCompleteBackend { renderCount++ })
            val request = PdfRasterRequest(path, 0, 96f, PdfRasterPurpose.VIEWPORT)
            try {
                assertTrue(backend.render(request).incomplete)
                assertFalse(backend.render(request).incomplete)
                assertFalse(backend.render(request).incomplete)
                assertEquals(2, renderCount)
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun adjacentPrefetchCannotEvictAVisibleRaster() =
        runBlocking {
            val path = Files.createTempFile("xuexue-pdf-priority-cache", ".pdf")
            val renderedPages = mutableListOf<Int>()
            val backend =
                CachingPdfReaderBackend(
                    delegate = fixedRasterBackend { page -> renderedPages += page },
                    maxRasterBytes = TEST_RASTER_BYTES.toLong(),
                )
            try {
                backend.render(request(path, page = 0, priority = PdfRasterPriority.VISIBLE))
                backend.render(request(path, page = 1, priority = PdfRasterPriority.ADJACENT))
                backend.render(request(path, page = 0, priority = PdfRasterPriority.VISIBLE))
                backend.render(request(path, page = 1, priority = PdfRasterPriority.VISIBLE))

                assertEquals(listOf(0, 1, 1), renderedPages)
            } finally {
                backend.close()
                Files.deleteIfExists(path)
            }
        }

    private fun incompleteThenCompleteBackend(onRender: () -> Unit): PdfReaderBackend =
        object : PdfReaderBackend {
            private var incomplete = true

            override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(1)

            override suspend fun pageDimensions(
                path: Path,
                pageIndex: Int,
            ): PdfPageDimensions = PdfPageDimensions(1f, 1f)

            override suspend fun render(request: PdfRasterRequest): PdfPageRaster {
                onRender()
                return PdfPageRaster(
                    pageIndex = request.pageIndex,
                    pixelWidth = 1,
                    pixelHeight = 1,
                    rgbaBytes = byteArrayOf(0, 0, 0, -1),
                    incomplete = incomplete.also { incomplete = false },
                )
            }

            override suspend fun search(
                path: Path,
                query: String,
            ): List<PdfSearchMatch> = emptyList()

            override suspend fun extractPageText(
                path: Path,
                pageIndex: Int,
            ): String = ""
        }

    private fun fixedRasterBackend(onRender: (Int) -> Unit): PdfReaderBackend =
        object : PdfReaderBackend {
            override suspend fun inspect(path: Path): PdfDocumentMetadata = PdfDocumentMetadata(1)

            override suspend fun pageDimensions(
                path: Path,
                pageIndex: Int,
            ): PdfPageDimensions = PdfPageDimensions(1f, 1f)

            override suspend fun render(request: PdfRasterRequest): PdfPageRaster {
                onRender(request.pageIndex)
                return PdfPageRaster(
                    pageIndex = request.pageIndex,
                    pixelWidth = 1,
                    pixelHeight = 1,
                    rgbaBytes = byteArrayOf(0, 0, 0, -1),
                )
            }

            override suspend fun search(
                path: Path,
                query: String,
            ): List<PdfSearchMatch> = emptyList()

            override suspend fun extractPageText(
                path: Path,
                pageIndex: Int,
            ): String = ""
        }

    private fun request(
        path: Path,
        page: Int,
        priority: PdfRasterPriority,
    ): PdfRasterRequest =
        PdfRasterRequest(
            path = path,
            pageIndex = page,
            dpi = 96f,
            purpose = PdfRasterPurpose.VIEWPORT,
            priority = priority,
        )

    private companion object {
        const val TEST_RASTER_BYTES = 4
    }
}
