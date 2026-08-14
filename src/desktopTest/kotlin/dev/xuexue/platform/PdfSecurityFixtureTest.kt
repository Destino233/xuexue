package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("TooManyFunctions") // Independent security fixtures share the native PDF test helpers.
class PdfSecurityFixtureTest {
    @Test
    fun passwordProtectedPdfCanBeAuthenticatedForReading() =
        runBlocking {
            val directory = Files.createTempDirectory("xuexue-password-pdf-fixture")
            val source = directory.resolve("protected.pdf")
            Files.write(
                source,
                securityPdf(
                    userPassword = "user",
                    ownerPassword = "owner",
                    permissions = ALL_PERMISSIONS,
                ),
            )
            val backend = MuPdfReaderBackend()
            try {
                assertFailsWith<PdfPasswordRequiredException> { backend.inspect(source) }
                assertFailsWith<PdfIncorrectPasswordException> { backend.authenticate(source, "wrong") }

                backend.authenticate(source, "user")

                assertEquals(1, backend.inspect(source).pageCount)
                assertTrue(backend.extractPageText(source, 0).contains("Protected fixture"))
                val raster =
                    backend.render(
                        PdfRasterRequest(
                            path = source,
                            pageIndex = 0,
                            dpi = 72f,
                            purpose = PdfRasterPurpose.THUMBNAIL,
                        ),
                    )
                assertTrue(raster.pixelWidth > 0)
                assertTrue(raster.pixelHeight > 0)
            } finally {
                backend.close()
                deleteTree(directory)
            }
        }

    @Test
    fun exportRejectsARealPasswordProtectedPdf() =
        runBlocking {
            assertExportRejected(
                fixture =
                    securityPdf(
                        userPassword = "user",
                        ownerPassword = "owner",
                        permissions = ALL_PERMISSIONS,
                    ),
                expectedMessage = "password",
            )
        }

    @Test
    fun exportRejectsARealPermissionRestrictedPdfAfterUserAuthentication() =
        runBlocking {
            assertExportRejected(
                fixture =
                    securityPdf(
                        userPassword = "",
                        ownerPassword = "owner",
                        permissions = NO_EDIT_OR_ASSEMBLE_PERMISSIONS,
                    ),
                expectedMessage = "permissions",
            )
        }

    @Test
    fun exportRejectsARealCmsSignedPdf() =
        runBlocking {
            val directory = Files.createTempDirectory("xuexue-signed-outline-fixture")
            val source = directory.resolve("signed.pdf")
            val output = directory.resolve("exported.pdf")
            Files.write(source, signedFixture())
            val sourceBytes = Files.readAllBytes(source)
            val backend = MuPdfReaderBackend()
            try {
                assertTrue(Files.size(source) > 8_000)
                val failure =
                    assertFailsWith<IllegalStateException> {
                        backend.exportOutline(
                            path = source,
                            outputPath = output,
                            payload = outlinePayload(),
                            strategy = PdfOutlineExportStrategy.REPLACE,
                        )
                    }
                assertTrue(
                    failure.message.orEmpty().contains("signature", ignoreCase = true),
                    failure.message,
                )
                assertTrue(Files.exists(output))
                assertTrue(sourceBytes.contentEquals(Files.readAllBytes(output)))
            } finally {
                backend.close()
                deleteTree(directory)
            }
        }

    private suspend fun assertExportRejected(
        fixture: ByteArray,
        expectedMessage: String,
    ) {
        val directory = Files.createTempDirectory("xuexue-protected-outline-fixture")
        val source = directory.resolve("protected.pdf")
        val output = directory.resolve("exported.pdf")
        Files.write(source, fixture)
        val sourceBytes = Files.readAllBytes(source)
        val backend = MuPdfReaderBackend()
        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    backend.exportOutline(
                        path = source,
                        outputPath = output,
                        payload = outlinePayload(),
                        strategy = PdfOutlineExportStrategy.REPLACE,
                    )
                }
            assertTrue(
                failure.message.orEmpty().contains(expectedMessage, ignoreCase = true),
                failure.message,
            )
            assertTrue(Files.exists(output))
            assertTrue(sourceBytes.contentEquals(Files.readAllBytes(output)))
        } finally {
            backend.close()
            deleteTree(directory)
        }
    }

    private fun outlinePayload(): ByteArray =
        PdfNativePayloadCodec.encodeOutlineForExport(
            PdfOutlineDocument(
                relativePath = "fixture.pdf",
                documentFingerprint = "0".repeat(64),
                nodes =
                    listOf(
                        PdfOutlineNode(
                            id = "fixture-node",
                            title = "Fixture chapter",
                            destination = PdfOutlineDestination(0),
                            source = PdfOutlineSource.MANUAL,
                            confidence = 1f,
                            reviewState = PdfOutlineReviewState.CONFIRMED,
                        ),
                    ),
            ),
        )

    private fun signedFixture(): ByteArray =
        Base64
            .getDecoder()
            .decode(SIGNED_FIXTURE_BASE64.filterNot(Char::isWhitespace))

    private fun securityPdf(
        userPassword: String,
        ownerPassword: String,
        permissions: Int,
    ): ByteArray {
        val fileId = ByteArray(16) { index -> (index + 1).toByte() }
        val pdfPermissions = (permissions and 0xF3C) or 0xFFFFF0C0.toInt()
        val ownerKey = md5(padPassword(ownerPassword)).copyOf(5)
        val ownerEntry = rc4(ownerKey, padPassword(userPassword))
        val fileKey =
            md5(
                padPassword(userPassword),
                ownerEntry,
                littleEndian(pdfPermissions),
                fileId,
            ).copyOf(5)
        val userEntry = rc4(fileKey, PASSWORD_PADDING)
        val encryptedContent =
            rc4(
                objectKey(fileKey, 5),
                "BT /F1 18 Tf 72 700 Td (Protected fixture) Tj ET\n".toByteArray(StandardCharsets.US_ASCII),
            )
        val objects =
            linkedMapOf(
                1 to ascii("<< /Type /Catalog /Pages 2 0 R >>"),
                2 to ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                3 to
                    ascii(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                    ),
                4 to ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                6 to ascii("null"),
                7 to ascii("null"),
                8 to ascii("null"),
                5 to
                    ascii("<< /Length " + encryptedContent.size + " >>\nstream\n") +
                    encryptedContent +
                    ascii("\nendstream"),
                9 to
                    ascii(
                        "<< /Filter /Standard /V 1 /Length 40 /R 2 " +
                            "/O <" + ownerEntry.toHex() + "> /U <" + userEntry.toHex() + "> " +
                            "/P " + pdfPermissions + " >>",
                    ),
            )
        return assemblePdf(
            objects = objects,
            trailer =
                "<< /Size 10 /Root 1 0 R /ID [<" + fileId.toHex() + "><" + fileId.toHex() +
                    ">] /Encrypt 9 0 R >>",
        )
    }

    private fun assemblePdf(
        objects: LinkedHashMap<Int, ByteArray>,
        trailer: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ascii("%PDF-1.4\n"))
        val offsets = IntArray((objects.keys.maxOrNull() ?: 0) + 1)
        objects.forEach { (number, body) ->
            offsets[number] = output.size()
            output.write(ascii(number.toString() + " 0 obj\n"))
            output.write(body)
            output.write(ascii("\nendobj\n"))
        }
        val firstFreeObject = 0
        val objectCount = offsets.size
        val xrefOffset = output.size()
        output.write(ascii("xref\n" + firstFreeObject + " " + objectCount + "\n"))
        output.write(ascii("0000000000 65535 f \n"))
        for (number in 1 until objectCount) {
            output.write(ascii(offsets[number].toString().padStart(10, '0') + " 00000 n \n"))
        }
        output.write(ascii("trailer\n<< " + trailer.removePrefix("<< ").removeSuffix(" >>") + " >>\n"))
        output.write(ascii("startxref\n" + xrefOffset))
        output.write(ascii("\n%%EOF\n"))
        return output.toByteArray()
    }

    private fun objectKey(
        fileKey: ByteArray,
        objectNumber: Int,
    ): ByteArray =
        md5(
            fileKey,
            byteArrayOf(
                (objectNumber and 0xff).toByte(),
                ((objectNumber ushr 8) and 0xff).toByte(),
                ((objectNumber ushr 16) and 0xff).toByte(),
                0,
                0,
            ),
        ).copyOf(10)

    private fun padPassword(password: String): ByteArray {
        val bytes = password.toByteArray(StandardCharsets.US_ASCII).copyOf(32)
        if (password.length < 32) {
            PASSWORD_PADDING.copyInto(bytes, password.length, 0, 32 - password.length)
        }
        return bytes
    }

    private fun littleEndian(value: Int): ByteArray = ByteArray(4) { index -> (value ushr (index * 8)).toByte() }

    private fun md5(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach(digest::update)
        return digest.digest()
    }

    private fun rc4(
        key: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val state = IntArray(256) { index -> index }
        var j = 0
        for (index in state.indices) {
            j = (j + state[index] + (key[index % key.size].toInt() and 0xff)) and 0xff
            val swapped = state[index]
            state[index] = state[j]
            state[j] = swapped
        }

        var i = 0
        j = 0
        return ByteArray(input.size) { index ->
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val swapped = state[i]
            state[i] = state[j]
            state[j] = swapped
            (input[index].toInt() xor state[(state[i] + state[j]) and 0xff]).toByte()
        }
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.toHex(): String {
        val digits = "0123456789ABCDEF"
        val result = StringBuilder(size * 2)
        forEach { value ->
            val unsigned = value.toInt() and 0xff
            result.append(digits[unsigned ushr 4])
            result.append(digits[unsigned and 0x0f])
        }
        return result.toString()
    }

    private fun deleteTree(directory: Path) {
        Files
            .walk(directory)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }

    private companion object {
        const val ALL_PERMISSIONS = 0xF3C
        const val NO_EDIT_OR_ASSEMBLE_PERMISSIONS = 0
        val PASSWORD_PADDING =
            byteArrayOf(
                0x28,
                0xBF.toByte(),
                0x4E,
                0x5E,
                0x4E,
                0x75,
                0x8A.toByte(),
                0x41,
                0x64,
                0,
                0x4E,
                0x56,
                0xFF.toByte(),
                0xFA.toByte(),
                1,
                8,
                0x2E,
                0x2E,
                0,
                0xB6.toByte(),
                0xD0.toByte(),
                0x68,
                0x3E,
                0x80.toByte(),
                0x2F,
                0x0C,
                0xA9.toByte(),
                0xFE.toByte(),
                0x64,
                0x53,
                0x69,
                0x7A,
            )

        // Generated as a detached SHA-256 CMS signature over the two ByteRange segments.
        const val SIGNED_FIXTURE_BASE64 = """JVBERi0xLjcKJeLjz9MKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgL0Fjcm9Gb3JtIDkgMCBSID4+CmVuZG9iagoyIDAgb2JqCjw8
IC9UeXBlIC9QYWdlcyAvS2lkcyBbMyAwIFJdIC9Db3VudCAxID4+CmVuZG9iagozIDAgb2JqCjw8IC9UeXBlIC9QYWdlIC9QYXJlbnQgMiAwIFIgL01lZGlh
Qm94IFswIDAgNjEyIDc5Ml0gL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNCAwIFIgPj4gPj4gL0NvbnRlbnRzIDUgMCBSIC9Bbm5vdHMgWzcgMCBSXSA+
PgplbmRvYmoKNCAwIG9iago8PCAvVHlwZSAvRm9udCAvU3VidHlwZSAvVHlwZTEgL0Jhc2VGb250IC9IZWx2ZXRpY2EgPj4KZW5kb2JqCjUgMCBvYmoKPDwg
L0xlbmd0aCA0NiA+PgpzdHJlYW0KQlQgL0YxIDE4IFRmIDcyIDcwMCBUZCAoU2lnbmVkIGZpeHR1cmUpIFRqIEVUCmVuZHN0cmVhbQplbmRvYmoKNiAwIG9i
ago8PCAvRlQgL1NpZyAvS2lkcyBbNyAwIFJdIC9UIChGaXh0dXJlU2lnbmF0dXJlKSA+PgplbmRvYmoKNyAwIG9iago8PCAvVHlwZSAvQW5ub3QgL1N1YnR5
cGUgL1dpZGdldCAvUGFyZW50IDYgMCBSIC9SZWN0IFswIDAgMCAwXSAvRiA0IC9WIDggMCBSIC9QIDMgMCBSID4+CmVuZG9iago4IDAgb2JqCjw8IC9UeXBl
IC9TaWcgL0ZpbHRlciAvQWRvYmUuUFBLTGl0ZSAvU3ViRmlsdGVyIC9hZGJlLnBrY3M3LmRldGFjaGVkIC9CeXRlUmFuZ2UgWzAwMDAwMDAwMDAgMDAwMDAw
MDc1OSAwMDAwMDA0ODU1IDAwMDAwMDQ0MjNdIC9Db250ZW50cyA8MzA4MjA1MjIwNjA5MkE4NjQ4ODZGNzBEMDEwNzAyQTA4MjA1MTMzMDgyMDUwRjAyMDEw
MTMxMEQzMDBCMDYwOTYwODY0ODAxNjUwMzA0MDIwMTMwMEIwNjA5MkE4NjQ4ODZGNzBEMDEwNzAxQTA4MjAzMUYzMDgyMDMxQjMwODIwMjAzQTAwMzAyMDEw
MjAyMTQ1N0FERTIxNDNFOTQ0NDQ5QUUwRDk0QUQ2Q0EwRjU2QTJEMUI2QUVCMzAwRDA2MDkyQTg2NDg4NkY3MEQwMTAxMEIwNTAwMzAxRDMxMUIzMDE5MDYw
MzU1MDQwMzBDMTI1ODc1NjU1ODc1NjUyMDUwNDQ0NjIwNjY2OTc4NzQ3NTcyNjUzMDFFMTcwRDMyMzYzMDM3MzEzOTMxMzMzMjM3MzAzMjVBMTcwRDMzMzYz
MDM3MzEzNjMxMzMzMjM3MzAzMjVBMzAxRDMxMUIzMDE5MDYwMzU1MDQwMzBDMTI1ODc1NjU1ODc1NjUyMDUwNDQ0NjIwNjY2OTc4NzQ3NTcyNjUzMDgyMDEy
MjMwMEQwNjA5MkE4NjQ4ODZGNzBEMDEwMTAxMDUwMDAzODIwMTBGMDAzMDgyMDEwQTAyODIwMTAxMDBCMDMwQTRFQTc0NjlCRTA1MjBEQ0M4RTc2NDhCRUIx
OUY2N0Y4ODNDOTBBQkE0OTIyQzkyNzI3Njg4QzEwMTM5RTEzOTlDRTU1QTNCRjg1OTZBODkwNTAwQTYyQ0YyMEVDNzlBRjU1OUE4QjFGNUYxMzI3NEUxMDQ3
OUQwMkE5MkM4MzRDRjU1Qzk4NUZDODgxQkY5RjAzRkZGOTkzNzAxQTI0RTJFNUIxMTRGRDMxMTVENjc1MzE3MkNBMzlDN0M1NzBFODdCQzEzMkZCNjZCQUY4
MEQ5MEQ0QUVCOUM3OTNBRjNGOEYzRkQ1QjUwNUY5MDEzMjFBMzYwOTc4M0Q4MjQzQjNGNjYyOTlCM0YwMTEzQzMzQzc1MjZGREEwMDMxNkM5RjNDMjA4QjhD
RjhGMTVCRjlDNUQ4MTY5RUNCOTIzRkI2MTc4RjY0QkQxQjNDRDVFRDNEQTQ4RkZEMEJDQ0YyQ0M2QzFCQzMzNEQ1RDgwQTc4MEU0MkE0NUEyQzQ0QjZFRDcx
NTI0NkU2Mjk0RTRBREQwRTVDMjBFMzIyODkwRDgwNkZCNURGMzY2RjRBNDZEMDhEMTVEMzc4OUY5M0FBMzQ4NkRBOUVFMkI4NTRGNUVCREU1NEYzMjQwMDFF
MjQxQkY1M0VFQUZCNzY5OENFODcwMjg2RDQ5NzJERDAyMDMwMTAwMDFBMzUzMzA1MTMwMUQwNjAzNTUxRDBFMDQxNjA0MTQzQ0QxMjk3RTMyODlDMjM0RTkw
ODI5RkMyNTI0ODlBMzczODU1RUFFMzAxRjA2MDM1NTFEMjMwNDE4MzAxNjgwMTQzQ0QxMjk3RTMyODlDMjM0RTkwODI5RkMyNTI0ODlBMzczODU1RUFFMzAw
RjA2MDM1NTFEMTMwMTAxRkYwNDA1MzAwMzAxMDFGRjMwMEQwNjA5MkE4NjQ4ODZGNzBEMDEwMTBCMDUwMDAzODIwMTAxMDA5NkU1RDY2QTQxMTAxQkQ0OTRE
MTUwREVFODY5QUM3RjVGMTc5MkEyRUE0Q0EwMTAzQzZDQUQwNjJEQ0VBNjU5QTc3QzBENzIxQTUwQ0RFOTEwMUQzMTdBRjVGMkQ0MzRGN0E3MjdDQjFDMzVC
NUMzNDQ0OTcxQjI1OEUwRDFCMkUyODNENkJBMjhDNDFDNDU2RUI3Q0Q1MkRBREMxMTRGMTRDRkRFQkFGRUE5MDM0QkVGRUNBRkFCQ0UwOURDNEU5OEVGREQ1
NTY3OUU1M0MzMzA5ODVGRjgxNDNCMDQ3OUZCODVDQkMxNzhEM0U4MTAwMTU4QzU4RjY4NDUyNjg3NDE0QzNCQzRDODg0RkRERTRCREQ0NUZGQzNCNkU3ODU0
QzEwMzhBMjBERjJBQTVGNzk1Njc3MjVCMTUwMjE1REZFNkI2QjFFRDMxQTZBNUZFMDc1MzQ0NEZCOTc4ODVGN0E3MTM1NUVEMDZCQkQ4NEEzNTY2NjBEMzA2
RUU3MjA5REFGQjk1OEE2OTRFRjlFMTU2Rjg5QjFEN0RCODJERUY4RUQwNjM0QjcwMUUyN0YwNTk2MDhFODJBNTNBRjhBRkQ2QkYzRUM0RjM1MkM2NzE1N0JE
QTIyREI2QUE3NzhCMTEyNEI1REMyQzk5MTUxRDY4OEExMzk1MzUwNDBEMzMxODIwMUM5MzA4MjAxQzUwMjAxMDEzMDM1MzAxRDMxMUIzMDE5MDYwMzU1MDQw
MzBDMTI1ODc1NjU1ODc1NjUyMDUwNDQ0NjIwNjY2OTc4NzQ3NTcyNjUwMjE0NTdBREUyMTQzRTk0NDQ0OUFFMEQ5NEFENkNBMEY1NkEyRDFCNkFFQjMwMEIw
NjA5NjA4NjQ4MDE2NTAzMDQwMjAxQTA2OTMwMTgwNjA5MkE4NjQ4ODZGNzBEMDEwOTAzMzEwQjA2MDkyQTg2NDg4NkY3MEQwMTA3MDEzMDFDMDYwOTJBODY0
ODg2RjcwRDAxMDkwNTMxMEYxNzBEMzIzNjMwMzczMTM5MzEzMzMyMzczMDMyNUEzMDJGMDYwOTJBODY0ODg2RjcwRDAxMDkwNDMxMjIwNDIwMkZFNjdEMDQy
RTYxRUNDQjQ2Rjk4RERFMDZBQUU0NkM1QzRFN0FDQTY5NzYyRkUxNzg4M0QzMzJENDc4NkVCQjMwMEQwNjA5MkE4NjQ4ODZGNzBEMDEwMTAxMDUwMDA0ODIw
MTAwMkEzM0VFRjVGNjAzRTk2QUJDRTBBMjlFRUY3NDREOTE4ODNCMjk2Q0FDNThCRkZDNDFCN0JCMTUzOENENDUzNDc4OENEQzk4REYzNEJFQTZEMTQyMDVB
OTMwMkQ1RkQyNEE0OUJCNDEyQjgwNzY3NkRGMEM5QkRGQzYyQjlFODA1NDE4OTE0RjAwNzA0NTFCMzk5Q0M4MUJEOUZCNTJFN0M3NTEwNUREODc0N0REMjYx
OEVFRjYwMUU2QUFCNTExNzA2RkEzMUU1NjlGOUY4NzQ0RkREMTA5RUJFQ0E1Rjk1NzgwMTZEQ0IzQzQ1Qzc3QTUzNkVBQjdGNzg4MjhDMDBDMjg3REUyQTkx
MURBQTFBNUQ2Mzk2M0YwMDY3OUQ5NTc1QzkyNDBDNTQzNDc4MTY5ODhGQ0JCMTg1Q0QxRUFCQjA0QzhBRUFFN0FGREVCMTU1NkNCN0YwRURBRjBDNUExMzg3
MjAxOUI5RUQ5RTI4QTVFNjRBOTk4NzQ0Mzg4NEY3RDFENERDODM4ODgxRTAwQ0MxMDM5QTU1QkNDNTA4RDEyNUVCNDc2QzYwRTA1NTQ2NjMxNUE1MzY2QkI3
QTJEQTU0RjNFQUFFMDNFOEQzMjgyNTdCMzM4NzhENDRDMEYyQTg5NkIyOTZCQ0E4ODU5RjA2QzE5MkY3RkEzMUMwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAw
MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA+ID4+CmVuZG9iago5IDAgb2JqCjw8IC9GaWVsZHMgWzYgMCBSXSAvU2lnRmxhZ3Mg
MyA+PgplbmRvYmoKeHJlZgowIDEwCjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAxNSAwMDAwMCBuIAowMDAwMDAwMDgwIDAwMDAwIG4gCjAwMDAwMDAx
MzcgMDAwMDAgbiAKMDAwMDAwMDI3OSAwMDAwMCBuIAowMDAwMDAwMzQ5IDAwMDAwIG4gCjAwMDAwMDA0NDQgMDAwMDAgbiAKMDAwMDAwMDUxMCAwMDAwMCBu
IAowMDAwMDAwNjE0IDAwMDAwIG4gCjAwMDAwMDg5NjMgMDAwMDAgbiAKdHJhaWxlcgo8PCAvU2l6ZSAxMCAvUm9vdCAxIDAgUiA+PgpzdGFydHhyZWYKOTAx
MgolJUVPRgo="""
    }
}
