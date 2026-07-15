package dev.xuexue.platform

import java.nio.file.Files
import java.nio.file.Path

// A single class name is part of the JNI ABI; splitting these declarations would only fragment that boundary.
@Suppress("TooManyFunctions")
internal object MuPdfNative {
    val version: String

    init {
        loadNativeLibrary()
        version = initialize(PdfMemoryBudget.MUPDF_STORE_BYTES)
    }

    private fun loadNativeLibrary() {
        val bundledLibrary =
            System
                .getProperty(COMPOSE_APP_RESOURCES_PROPERTY)
                ?.let(Path::of)
                ?.resolve(System.mapLibraryName(NATIVE_LIBRARY_NAME))
                ?.takeIf(Files::isRegularFile)
        if (bundledLibrary == null) {
            System.loadLibrary(NATIVE_LIBRARY_NAME)
        } else {
            System.load(bundledLibrary.toAbsolutePath().toString())
        }
    }

    external fun openDocument(pathUtf8: ByteArray): Long

    external fun createProgressiveStream(
        length: Long,
        blockSize: Int,
    ): Long

    external fun provideProgressiveRange(
        streamHandle: Long,
        offset: Long,
        bytes: ByteArray,
    )

    external fun progressiveRequestedRange(streamHandle: Long): LongArray

    external fun progressiveBytesLoaded(streamHandle: Long): Long

    external fun openDocumentWithProgressiveStream(streamHandle: Long): Long

    external fun dropProgressiveStream(streamHandle: Long)

    external fun closeDocument(handle: Long)

    external fun countPages(handle: Long): Int

    external fun pageBounds(
        handle: Long,
        pageIndex: Int,
    ): FloatArray

    external fun loadDisplayList(
        handle: Long,
        pageIndex: Int,
    ): Long

    external fun loadOverlayDisplayList(
        handle: Long,
        pageIndex: Int,
    ): Long

    external fun loadProgressiveDisplayList(
        documentHandle: Long,
        streamHandle: Long,
        pageIndex: Int,
    ): LongArray

    external fun loadProgressiveOverlayDisplayList(
        documentHandle: Long,
        streamHandle: Long,
        pageIndex: Int,
    ): LongArray

    external fun displayListBounds(handle: Long): FloatArray

    external fun dropDisplayList(handle: Long)

    external fun createCookie(): Long

    external fun abortCookie(handle: Long)

    external fun dropCookie(handle: Long)

    external fun renderRgba(
        displayListHandle: Long,
        scale: Float,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        cookieHandle: Long,
        transparent: Boolean,
    ): ByteArray

    external fun extractText(
        displayListHandle: Long,
        optionsUtf8: ByteArray,
    ): ByteArray

    external fun createTextPage(displayListHandle: Long): Long

    external fun dropTextPage(textPageHandle: Long)

    external fun copySelection(
        textPageHandle: Long,
        points: FloatArray,
    ): ByteArray

    external fun highlightSelection(
        textPageHandle: Long,
        points: FloatArray,
    ): FloatArray

    external fun loadLinks(
        documentHandle: Long,
        pageIndex: Int,
    ): ByteArray

    external fun search(
        displayListHandle: Long,
        needleUtf8: ByteArray,
    ): FloatArray

    external fun emptyStore()

    private external fun initialize(storeSize: Long): String

    private const val NATIVE_LIBRARY_NAME = "xuexue_mupdf"
    private const val COMPOSE_APP_RESOURCES_PROPERTY = "compose.application.resources.dir"
}

internal class PdfTryLaterException(
    message: String,
) : IllegalStateException(message)

internal data class PdfByteRange(
    val offset: Long,
    val length: Int,
)

internal class MuPdfProgressiveStream(
    val length: Long,
    blockSize: Int,
) : AutoCloseable {
    private var handle = MuPdfNative.createProgressiveStream(length, blockSize)

    init {
        check(handle != 0L) { "MuPDF returned an empty progressive stream handle." }
    }

    fun requestedRange(): PdfByteRange {
        val range = MuPdfNative.progressiveRequestedRange(requireHandle())
        check(range.size == 2 && range[1] in 1..Int.MAX_VALUE) { "MuPDF returned an invalid byte-range request." }
        return PdfByteRange(range[0], range[1].toInt())
    }

    fun provide(
        range: PdfByteRange,
        bytes: ByteArray,
    ) {
        require(bytes.size == range.length) { "Progressive PDF range length does not match its payload." }
        MuPdfNative.provideProgressiveRange(requireHandle(), range.offset, bytes)
    }

    fun openDocument(): Long = MuPdfNative.openDocumentWithProgressiveStream(requireHandle())

    fun loadDisplayList(
        documentHandle: Long,
        pageIndex: Int,
    ): LongArray = MuPdfNative.loadProgressiveDisplayList(documentHandle, requireHandle(), pageIndex)

    fun loadOverlayDisplayList(
        documentHandle: Long,
        pageIndex: Int,
    ): LongArray = MuPdfNative.loadProgressiveOverlayDisplayList(documentHandle, requireHandle(), pageIndex)

    val bytesLoaded: Long
        get() = MuPdfNative.progressiveBytesLoaded(requireHandle())

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) MuPdfNative.dropProgressiveStream(current)
    }

    private fun requireHandle(): Long = checkNotNull(handle.takeIf { it != 0L }) { "Progressive PDF stream is closed." }
}

internal class MuPdfDisplayList(
    val handle: Long,
    val incomplete: Boolean = false,
) : AutoCloseable {
    init {
        check(handle != 0L) { "MuPDF returned an empty display-list handle." }
    }

    override fun close() = MuPdfNative.dropDisplayList(handle)
}

internal class MuPdfTextPage(
    val handle: Long,
) : AutoCloseable {
    init {
        check(handle != 0L) { "MuPDF returned an empty structured-text handle." }
    }

    override fun close() = MuPdfNative.dropTextPage(handle)
}

internal class MuPdfCookie : AutoCloseable {
    private val handle = MuPdfNative.createCookie()

    init {
        check(handle != 0L) { "MuPDF returned an empty render-cookie handle." }
    }

    fun abort() = MuPdfNative.abortCookie(handle)

    fun render(
        displayList: MuPdfDisplayList,
        scale: Float,
        bounds: MuPdfPixelBounds,
        transparent: Boolean = false,
    ): ByteArray =
        MuPdfNative.renderRgba(
            displayListHandle = displayList.handle,
            scale = scale,
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height,
            cookieHandle = handle,
            transparent = transparent,
        )

    override fun close() = MuPdfNative.dropCookie(handle)
}

internal data class MuPdfRect(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) {
    val width: Float
        get() = x1 - x0

    val height: Float
        get() = y1 - y0

    fun dimensions(): PdfPageDimensions = PdfPageDimensions(width, height)
}

internal data class MuPdfPixelBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun FloatArray.toMuPdfRect(): MuPdfRect {
    check(size == 4) { "MuPDF rectangle must contain four coordinates." }
    return MuPdfRect(this[0], this[1], this[2], this[3])
}
