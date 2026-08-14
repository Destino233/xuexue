package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfOutlineFeatureTest {
    @Test
    fun nativeOutlineAndStructuredTextRoundTripThroughMuPdf() =
        runBlocking {
            val source = Files.createTempFile("xuexue-outline-source", ".pdf")
            val output = Files.createTempFile("xuexue-outline-output", ".pdf")
            Files.writeString(source, outlinePdf(), StandardCharsets.US_ASCII)
            val backend = MuPdfReaderBackend()
            try {
                assertNativeOutline(backend, source)
                assertStructuredText(backend, source)
                val outline = rebuiltOutline()
                val sourceBytes = Files.readAllBytes(source)
                backend.exportOutline(
                    path = source,
                    outputPath = output,
                    payload = PdfNativePayloadCodec.encodeOutlineForExport(outline),
                    strategy = PdfOutlineExportStrategy.REPLACE,
                )
                assertTrue(sourceBytes.contentEquals(Files.readAllBytes(source)))
                assertExportedOutline(backend, output)
            } finally {
                backend.close()
                Files.deleteIfExists(source)
                Files.deleteIfExists(output)
            }
        }

    @Test
    fun exportAppendKeepsExistingOutlineAndAddsTheNewTree() =
        runBlocking {
            val source = Files.createTempFile("xuexue-outline-append-source", ".pdf")
            val output = Files.createTempFile("xuexue-outline-append-output", ".pdf")
            Files.writeString(source, outlinePdf(), StandardCharsets.US_ASCII)
            val backend = MuPdfReaderBackend()
            try {
                val outline =
                    PdfOutlineDocument(
                        relativePath = "source.pdf",
                        documentFingerprint = "1".repeat(64),
                        nodes =
                            listOf(
                                PdfOutlineNode(
                                    id = "append",
                                    title = "Appended",
                                    destination = PdfOutlineDestination(0),
                                    source = PdfOutlineSource.MANUAL,
                                    confidence = 1f,
                                    reviewState = PdfOutlineReviewState.CONFIRMED,
                                ),
                            ),
                    )
                backend.exportOutline(
                    source,
                    output,
                    PdfNativePayloadCodec.encodeOutlineForExport(outline),
                    PdfOutlineExportStrategy.APPEND,
                )
                assertEquals(
                    listOf("Chapter 1", "Appended"),
                    backend.nativeOutline(output).map(PdfNativeOutlineNode::title),
                )
            } finally {
                backend.close()
                Files.deleteIfExists(source)
                Files.deleteIfExists(output)
            }
        }

    @Test
    fun truncatedPdfIsRejectedBeforeAnOutlineCanBeWritten() =
        runBlocking {
            val source = Files.createTempFile("xuexue-outline-damaged-source", ".pdf")
            val output = Files.createTempFile("xuexue-outline-damaged-output", ".pdf")
            Files.writeString(source, "%PDF-1.7\n1 0 obj\n<< /Type /Catalog", StandardCharsets.US_ASCII)
            val backend = MuPdfReaderBackend()
            try {
                val failure =
                    assertFailsWith<IllegalStateException> {
                        backend.exportOutline(
                            source,
                            output,
                            PdfNativePayloadCodec.encodeOutlineForExport(rebuiltOutline()),
                            PdfOutlineExportStrategy.REPLACE,
                        )
                    }
                assertTrue(failure.message.orEmpty().isNotBlank())
            } finally {
                backend.close()
                Files.deleteIfExists(source)
                Files.deleteIfExists(output)
            }
        }

    @Test
    fun truncatedOutlinePayloadIsRejectedBeforeSaving() =
        runBlocking {
            val source = Files.createTempFile("xuexue-outline-payload-source", ".pdf")
            val output = Files.createTempFile("xuexue-outline-payload-output", ".pdf")
            Files.writeString(source, outlinePdf(), StandardCharsets.US_ASCII)
            val sourceBytes = Files.readAllBytes(source)
            val payload =
                ByteBuffer
                    .allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x31454C58)
                    .putInt(1)
                    .putInt(1)
                    .array()
            val backend = MuPdfReaderBackend()
            try {
                assertFailsWith<IllegalStateException> {
                    backend.exportOutline(
                        source,
                        output,
                        payload,
                        PdfOutlineExportStrategy.REPLACE,
                    )
                }
                assertTrue(sourceBytes.contentEquals(Files.readAllBytes(output)))
            } finally {
                backend.close()
                Files.deleteIfExists(source)
                Files.deleteIfExists(output)
            }
        }
}

private suspend fun assertNativeOutline(
    backend: MuPdfReaderBackend,
    source: Path,
) {
    val native = backend.nativeOutline(source)
    assertEquals(1, native.size)
    assertEquals("Chapter 1", native.single().title)
    assertEquals(1, native.single().children.size)
    assertEquals(
        "Section 1",
        native
            .single()
            .children
            .single()
            .title,
    )
    assertEquals(0, native.single().pageIndex)
    assertNull(native.single().topNormalized)
}

private suspend fun assertStructuredText(
    backend: MuPdfReaderBackend,
    source: Path,
) {
    val text = checkNotNull(backend.structuredText(source, 0))
    val chars = text.blocks.flatMap(PdfStructuredTextBlock::lines).flatMap(PdfStructuredTextLine::chars)
    assertTrue(chars.isNotEmpty())
    assertTrue(chars.any { char -> char.codePoint == 'C'.code })
}

private fun rebuiltOutline(): PdfOutlineDocument =
    PdfOutlineDocument(
        relativePath = "source.pdf",
        documentFingerprint = "0".repeat(64),
        nodes =
            listOf(
                PdfOutlineNode(
                    id = "manual-root",
                    title = "Rebuilt root",
                    destination = PdfOutlineDestination(0, 0.2f),
                    source = PdfOutlineSource.MANUAL,
                    confidence = 1f,
                    reviewState = PdfOutlineReviewState.CONFIRMED,
                    children =
                        listOf(
                            PdfOutlineNode(
                                id = "manual-child",
                                title = "Rebuilt child",
                                destination = PdfOutlineDestination(0),
                                source = PdfOutlineSource.MANUAL,
                                confidence = 1f,
                                reviewState = PdfOutlineReviewState.CONFIRMED,
                            ),
                        ),
                ),
            ),
    )

private suspend fun assertExportedOutline(
    backend: MuPdfReaderBackend,
    output: Path,
) {
    val exported = backend.nativeOutline(output)
    assertEquals(listOf("Rebuilt root"), exported.map(PdfNativeOutlineNode::title))
    assertEquals(listOf("Rebuilt child"), exported.single().children.map(PdfNativeOutlineNode::title))
    assertTrue(abs((exported.single().topNormalized ?: -1f) - 0.2f) < 0.03f)
    assertEquals(1, backend.inspect(output).pageCount)
    if (!isWindows()) {
        assertTrue(runExternalReader("pdfinfo", output).contains("Pages:           1"))
        assertTrue(runExternalReader("pdftotext", output, "-").contains("Chapter 1"))
        val independentOutline =
            runExternalReader(
                "python3",
                Path.of("tools/verify_pdf_outline.py"),
                output.toString(),
            )
        assertTrue(independentOutline.contains("Rebuilt root"))
        assertTrue(independentOutline.contains("Rebuilt child"))
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").contains("win", ignoreCase = true)

private fun runExternalReader(
    executable: String,
    path: java.nio.file.Path,
    vararg arguments: String,
): String {
    val process = ProcessBuilder(listOf(executable, path.toString()) + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    check(process.exitValue() == 0) { "$executable failed: $output" }
    return output
}

private fun outlinePdf(): String {
    val content = "BT /F1 18 Tf 72 700 Td (Chapter 1) Tj 0 -24 Td (Section 1) Tj ET\n"
    val objects =
        listOf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>",
            2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            3 to
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            4 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            5 to "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            6 to "<< /Type /Outlines /First 7 0 R /Last 7 0 R /Count 2 >>",
            7 to "<< /Title (Chapter 1) /Parent 6 0 R /Dest [3 0 R /Fit] /First 8 0 R /Last 8 0 R /Count 1 >>",
            8 to "<< /Title (Section 1) /Parent 7 0 R /Dest [3 0 R /Fit] >>",
        )
    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = IntArray(9)
    objects.forEach { (number, body) ->
        offsets[number] = pdf.length
        pdf
            .append(number)
            .append(" 0 obj\n")
            .append(body)
            .append("\nendobj\n")
    }
    val xref = pdf.length
    pdf.append("xref\n0 9\n0000000000 65535 f \n")
    offsets.drop(1).forEach { offset -> pdf.append(offset.toString().padStart(10, '0')).append(" 00000 n \n") }
    pdf.append("trailer\n<< /Size 9 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n")
    return pdf.toString()
}
