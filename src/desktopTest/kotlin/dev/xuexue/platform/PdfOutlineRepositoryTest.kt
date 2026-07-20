package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfOutlineRepositoryTest {
    @Test
    fun repositorySeparatesCurrentAndStaleFingerprintsAndKeepsManualNodes() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-outline-repository")
            try {
                val repository = PdfOutlineRepository(WorkspaceService())
                val saved = outlineDocument("0".repeat(64), "Generated")
                repository.save(root, saved)
                assertNotNull(repository.load(root, "book.pdf", "0".repeat(64)).current)
                assertNull(repository.load(root, "book.pdf", "1".repeat(64)).current)
                assertNotNull(repository.load(root, "book.pdf", "1".repeat(64)).stale)

                val manual =
                    saved.copy(
                        nodes =
                            listOf(
                                saved.nodes.single().copy(
                                    title = "Manual",
                                    source = PdfOutlineSource.MANUAL,
                                    reviewState = PdfOutlineReviewState.MANUAL,
                                ),
                            ),
                    )
                val generated = outlineDocument("0".repeat(64), "Generated")
                assertEquals(
                    "Manual",
                    repository
                        .mergePreservingReview(manual, generated)
                        .nodes
                        .single()
                        .title,
                )

                val protectedRoot =
                    saved.nodes.single().copy(
                        title = "Generated",
                        reviewState = PdfOutlineReviewState.CONFIRMED,
                        children =
                            listOf(
                                saved.nodes.single().copy(
                                    id = "protected-child",
                                    title = "Manual child",
                                    reviewState = PdfOutlineReviewState.MANUAL,
                                    source = PdfOutlineSource.MANUAL,
                                ),
                            ),
                    )
                val generatedChild =
                    saved.nodes.single().copy(
                        id = "generated-child",
                        title = "Generated child",
                    )
                val merged =
                    repository.mergePreservingReview(
                        saved.copy(nodes = listOf(protectedRoot)),
                        saved.copy(
                            nodes =
                                listOf(
                                    saved.nodes.single().copy(
                                        title = "Generated",
                                        children = listOf(generatedChild),
                                    ),
                                ),
                        ),
                    )
                assertEquals(
                    listOf("Manual child", "Generated child"),
                    merged.nodes
                        .single()
                        .children
                        .map(PdfOutlineNode::title),
                )
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    @Test
    fun schemaZeroOutlinesAreMigratedBeforeDecode() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-outline-schema")
            try {
                val fingerprint = "0".repeat(64)
                Files.createDirectories(root.resolve(".xuexue"))
                Files.writeString(
                    root.resolve(".xuexue/pdf-outlines.json"),
                    """
                    {
                      "schemaVersion": 0,
                      "documents": {
                        "book.pdf": {
                          "schemaVersion": 0,
                          "relativePath": "book.pdf",
                          "documentFingerprint": "$fingerprint",
                          "nodes": [{
                            "id": "legacy",
                            "title": "Legacy",
                            "destination": {"pageIndex": 0},
                            "source": "OCR",
                            "reviewState": "CANDIDATE"
                          }]
                        }
                      }
                    }
                    """.trimIndent(),
                )

                val loaded = PdfOutlineRepository(WorkspaceService()).load(root, "book.pdf", fingerprint)

                assertEquals(
                    "Legacy",
                    loaded.current
                        ?.nodes
                        ?.single()
                        ?.title,
                )
                assertTrue(loaded.current?.schemaVersion == CURRENT_PDF_OUTLINE_SCHEMA)
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    private fun outlineDocument(
        fingerprint: String,
        title: String,
    ): PdfOutlineDocument =
        PdfOutlineDocument(
            relativePath = "book.pdf",
            documentFingerprint = fingerprint,
            nodes =
                listOf(
                    PdfOutlineNode(
                        id = "root",
                        title = title,
                        destination = PdfOutlineDestination(0),
                        source = PdfOutlineSource.OCR,
                        reviewState = PdfOutlineReviewState.CANDIDATE,
                    ),
                ),
        )
}
