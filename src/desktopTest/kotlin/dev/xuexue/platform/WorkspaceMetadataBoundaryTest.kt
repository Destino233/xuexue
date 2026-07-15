package dev.xuexue.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceMetadataBoundaryTest {
    private val service = WorkspaceService()

    @Test
    fun applicationMetadataRejectsSymbolicLinkEscapes() {
        withWorkspace { root ->
            val outside = Files.createTempDirectory("xuexue-metadata-outside-test")
            try {
                if (!createSymbolicLink(root.resolve(".xuexue"), outside)) return@withWorkspace
                service.atomicWrite(root.resolve("Note.md"), "# Note\n")

                assertFailsWith<IllegalArgumentException> {
                    service.saveWorkbench(root, PersistedWorkbench(openDocuments = listOf("Note.md")))
                }
                assertFailsWith<IllegalArgumentException> {
                    service.trashEntries(root, listOf("Note.md"))
                }
                assertTrue(service.loadWorkbench(root).openDocuments.isEmpty())
                assertTrue(Files.list(outside).use { it.findAny().isEmpty })
            } finally {
                deleteRecursively(outside)
            }
        }
    }

    private fun createSymbolicLink(
        link: Path,
        target: Path,
    ): Boolean = runCatching { Files.createSymbolicLink(link, target) }.isSuccess

    private fun withWorkspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("xuexue-workspace-metadata-test")
        try {
            block(root)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
