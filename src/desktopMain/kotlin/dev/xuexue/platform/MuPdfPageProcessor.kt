package dev.xuexue.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MuPdfPageProcessor(
    private val renderDispatcher: CoroutineDispatcher,
    private val onNativeRenderStarted: () -> Unit = {},
) {
    suspend fun pageDimensions(
        session: MuPdfDocumentSession,
        pageIndex: Int,
    ): PdfPageDimensions = withPage(session, pageIndex) { page -> page.dimensions }

    suspend fun render(
        session: MuPdfDocumentSession,
        request: PdfRasterRequest,
    ): PdfPageRaster =
        withPage(session, request.pageIndex) { page ->
            withContext(renderDispatcher) { renderDisplayList(page, request) }
        }

    suspend fun searchPage(
        session: MuPdfDocumentSession,
        pageIndex: Int,
        needle: String,
    ): PdfSearchMatch? =
        withPage(session, pageIndex) { page ->
            withContext(renderDispatcher) {
                currentCoroutineContext().ensureActive()
                val pageText = page.extractText()
                val hits = MuPdfNative.search(page.displayList.handle, needle.encodeToByteArray())
                if (hits.isEmpty()) return@withContext null
                currentCoroutineContext().ensureActive()
                val matchIndex = pageText.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
                val regions =
                    buildList {
                        for (index in hits.indices step RECT_COORDINATES) {
                            MuPdfRect(hits[index], hits[index + 1], hits[index + 2], hits[index + 3])
                                .normalized(page.bounds)
                                ?.let(::add)
                        }
                    }
                PdfSearchMatch(
                    pageIndex = pageIndex,
                    preview = pageText.matchPreview(matchIndex, needle.length),
                    regions = regions,
                )
            }
        }

    suspend fun extractText(
        session: MuPdfDocumentSession,
        pageIndex: Int,
    ): String =
        withPage(session, pageIndex) { page ->
            withContext(renderDispatcher) {
                currentCoroutineContext().ensureActive()
                page.extractText().trim()
            }
        }

    suspend fun selectText(
        session: MuPdfDocumentSession,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection =
        withPage(session, pageIndex) { page ->
            withContext(renderDispatcher) {
                currentCoroutineContext().ensureActive()
                val points = floatArrayOf(start.x, start.y, end.x, end.y)
                val textPage = page.textPage()
                val text = MuPdfNative.copySelection(textPage.handle, points).decodeToString().trim()
                val regions =
                    MuPdfNative
                        .highlightSelection(textPage.handle, points)
                        .asList()
                        .chunked(8)
                        .mapNotNull { quad -> quad.bounds()?.normalized(page.bounds) }
                PdfTextSelection(text, regions)
            }
        }

    private suspend fun renderDisplayList(
        page: MuPdfPageSnapshot,
        request: PdfRasterRequest,
    ): PdfPageRaster {
        require(request.dpi > 0f) { "PDF render DPI must be positive." }
        val scale = request.dpi / POINTS_PER_INCH
        val scaledBounds = page.bounds.scale(scale)
        val rasterRegion = pdfRasterRegion(scaledBounds, request.viewport)
        val displayList =
            when (request.layer) {
                PdfRasterLayer.CONTENT -> page.displayList
                PdfRasterLayer.NATIVE_OVERLAY -> page.overlayDisplayList
            }
        if (displayList == null) {
            return PdfPageRaster(
                pageIndex = page.pageIndex,
                pixelWidth = 1,
                pixelHeight = 1,
                rgbaBytes = byteArrayOf(0, 0, 0, 0),
                viewport = request.viewport,
                empty = true,
            )
        }
        val cookie = MuPdfCookie()
        val cancellationHandle =
            currentCoroutineContext()[Job]?.abortCookieOnCancellation(cookie)
        return try {
            onNativeRenderStarted()
            val renderedRgba =
                cookie.render(
                    displayList = displayList,
                    scale = scale,
                    bounds = rasterRegion.renderBounds,
                    transparent = request.layer == PdfRasterLayer.NATIVE_OVERLAY,
                )
            currentCoroutineContext().ensureActive()
            PdfPageRaster(
                pageIndex = page.pageIndex,
                pixelWidth = rasterRegion.contentBounds.width,
                pixelHeight = rasterRegion.contentBounds.height,
                rgbaBytes = rasterRegion.crop(renderedRgba),
                viewport = request.viewport,
                incomplete = displayList.incomplete,
            )
        } finally {
            cancellationHandle?.dispose()
            cookie.close()
        }
    }

    private suspend fun <T> withPage(
        session: MuPdfDocumentSession,
        pageIndex: Int,
        operation: suspend (MuPdfPageSnapshot) -> T,
    ): T {
        val lease = session.acquirePage(pageIndex)
        return try {
            operation(lease.snapshot)
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    private fun MuPdfRect.normalized(pageBounds: MuPdfRect): PdfNormalizedRect? {
        val width = pageBounds.width
        val height = pageBounds.height
        if (width <= 0f || height <= 0f) return null
        val left = ((x0 - pageBounds.x0) / width).coerceIn(0f, 1f)
        val top = ((y0 - pageBounds.y0) / height).coerceIn(0f, 1f)
        val right = ((x1 - pageBounds.x0) / width).coerceIn(0f, 1f)
        val bottom = ((y1 - pageBounds.y0) / height).coerceIn(0f, 1f)
        return if (left < right && top < bottom) PdfNormalizedRect(left, top, right, bottom) else null
    }

    private fun MuPdfRect.scale(scale: Float): MuPdfRect =
        MuPdfRect(
            x0 = x0 * scale,
            y0 = y0 * scale,
            x1 = x1 * scale,
            y1 = y1 * scale,
        )

    private fun MuPdfPageSnapshot.extractText(): String =
        MuPdfNative
            .extractText(displayList.handle, STRUCTURED_TEXT_OPTIONS_UTF8)
            .decodeToString()

    private fun String.matchPreview(
        index: Int,
        matchLength: Int,
    ): String {
        val start = (index - PREVIEW_BEFORE).coerceAtLeast(0)
        val end = (index + matchLength + PREVIEW_AFTER).coerceAtMost(length)
        return substring(start, end).replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val PREVIEW_BEFORE = 48
        const val PREVIEW_AFTER = 72
        const val RECT_COORDINATES = 4
        val STRUCTURED_TEXT_OPTIONS_UTF8 = "dehyphenate,preserve-whitespace".encodeToByteArray()
    }
}

private fun List<Float>.bounds(): MuPdfRect? {
    if (size != QUAD_COORDINATES) return null
    val xCoordinates = listOf(this[0], this[2], this[4], this[6])
    val yCoordinates = listOf(this[1], this[3], this[5], this[7])
    return MuPdfRect(
        x0 = xCoordinates.min(),
        y0 = yCoordinates.min(),
        x1 = xCoordinates.max(),
        y1 = yCoordinates.max(),
    )
}

private const val QUAD_COORDINATES = 8

@OptIn(InternalCoroutinesApi::class)
private fun Job.abortCookieOnCancellation(cookie: MuPdfCookie): DisposableHandle =
    invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = true,
    ) { cause ->
        if (cause != null) cookie.abort()
    }
