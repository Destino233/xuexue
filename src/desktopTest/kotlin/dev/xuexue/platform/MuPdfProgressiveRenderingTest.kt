package dev.xuexue.platform

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuPdfProgressiveRenderingTest {
    @Test
    fun missingPageContentProducesAnIncompleteDisplayListThatCanBeRefined() {
        val fixture = progressiveContentPdf()
        val stream = MuPdfProgressiveStream(fixture.bytes.size.toLong(), TEST_BLOCK_BYTES)
        var documentHandle = 0L
        var initialDisplayList: MuPdfDisplayList? = null
        var refinedDisplayList: MuPdfDisplayList? = null
        val requestedRanges = mutableListOf<PdfByteRange>()
        try {
            documentHandle = withRequiredRanges(stream, fixture.bytes, requestedRanges, stream::openDocument)
            val pageCount =
                withRequiredRanges(stream, fixture.bytes, requestedRanges) {
                    MuPdfNative.countPages(documentHandle)
                }
            assertTrue(pageCount == 1)

            initialDisplayList =
                withRequiredRanges(stream, fixture.bytes, requestedRanges) {
                    stream
                        .loadDisplayList(documentHandle, 0)
                        .toDisplayList()
                }
            assertTrue(
                initialDisplayList.incomplete,
                "Expected missing content at ${fixture.contentOffset}; loaded $requestedRanges",
            )

            val contentRange = stream.requestedRange()
            assertTrue(fixture.contentOffset in contentRange.offset until contentRange.endExclusive)
            stream.provide(contentRange, fixture.bytes.copyOfRange(contentRange.startIndex, contentRange.endIndex))

            refinedDisplayList =
                stream
                    .loadDisplayList(documentHandle, 0)
                    .toDisplayList()
            assertFalse(refinedDisplayList.incomplete)
            val text = MuPdfNative.extractText(refinedDisplayList.handle, ByteArray(0)).decodeToString()
            assertTrue(text.contains(TEST_PAGE_TEXT))
        } finally {
            refinedDisplayList?.close()
            initialDisplayList?.close()
            if (documentHandle != 0L) MuPdfNative.closeDocument(documentHandle)
            stream.close()
        }
    }

    private fun LongArray.toDisplayList(): MuPdfDisplayList {
        check(size == DISPLAY_LIST_RESULT_SIZE) { "MuPDF returned an invalid progressive display-list result." }
        return MuPdfDisplayList(this[0], incomplete = this[1] != 0L)
    }

    private companion object {
        const val TEST_BLOCK_BYTES = 512
        const val DISPLAY_LIST_RESULT_SIZE = 2
        const val TEST_PAGE_TEXT = "XueXue progressive refinement"
    }
}

private data class ProgressivePdfFixture(
    val bytes: ByteArray,
    val contentOffset: Long,
)

private fun progressiveContentPdf(): ProgressivePdfFixture {
    val content = "BT /F1 12 Tf 72 720 Td (XueXue progressive refinement) Tj ET\n"
    val objects =
        listOf(
            1 to "<< /Type /Catalog /Pages 2 0 R >>",
            2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            3 to
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            4 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            6 to paddingStream(),
            5 to "<< /Length ${content.length} >>\nstream\n${content}endstream",
            7 to paddingStream(),
        )
    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = IntArray(objects.maxOf { it.first } + 1)
    objects.forEach { (number, body) ->
        offsets[number] = pdf.length
        pdf
            .append(number)
            .append(" 0 obj\n")
            .append(body)
            .append("\nendobj\n")
    }
    val xrefOffset = pdf.length
    pdf.append("xref\n0 ").append(offsets.size).append("\n0000000000 65535 f \n")
    offsets.drop(1).forEach { offset -> pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n") }
    pdf
        .append("trailer\n<< /Size ")
        .append(offsets.size)
        .append(" /Root 1 0 R >>\nstartxref\n")
        .append(xrefOffset)
        .append("\n%%EOF\n")
    return ProgressivePdfFixture(
        bytes = pdf.toString().toByteArray(StandardCharsets.US_ASCII),
        contentOffset = offsets[5].toLong(),
    )
}

private fun paddingStream(): String {
    val padding = "x".repeat(PDF_PADDING_BYTES)
    return "<< /Length ${padding.length} >>\nstream\n$padding\nendstream"
}

private fun <T> withRequiredRanges(
    stream: MuPdfProgressiveStream,
    source: ByteArray,
    requestedRanges: MutableList<PdfByteRange>,
    operation: () -> T,
): T {
    repeat(MAX_PROGRESSIVE_RANGE_REQUESTS) {
        try {
            return operation()
        } catch (_: PdfTryLaterException) {
            val range = stream.requestedRange()
            stream.provide(range, source.copyOfRange(range.startIndex, range.endIndex))
            requestedRanges += range
        }
    }
    error("MuPDF did not finish after $MAX_PROGRESSIVE_RANGE_REQUESTS progressive range requests.")
}

private val PdfByteRange.endExclusive: Long
    get() = offset + length

private val PdfByteRange.startIndex: Int
    get() = offset.toInt()

private val PdfByteRange.endIndex: Int
    get() = endExclusive.toInt()

private const val PDF_PADDING_BYTES = 4 * 1024
private const val MAX_PROGRESSIVE_RANGE_REQUESTS = 64
