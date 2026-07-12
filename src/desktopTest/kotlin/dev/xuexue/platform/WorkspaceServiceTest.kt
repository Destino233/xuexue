package dev.xuexue.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceServiceTest {
    private val service = WorkspaceService()

    @Test
    fun renameMarkdownUpdatesWikiReferences() = withWorkspace { root ->
        service.atomicWrite(root.resolve("Source.md"), "# Source\n")
        service.atomicWrite(root.resolve("Linking.md"), "See [[Source]] and ![[Source|preview]].\n")

        val result = service.renameEntry(root, "Source.md", "Renamed.md")

        assertEquals("Renamed.md", result.relativePath)
        assertEquals(listOf("Linking.md"), result.affectedMarkdown)
        assertTrue(root.resolve("Renamed.md").toFile().isFile)
        assertFalse(root.resolve("Source.md").toFile().exists())
        assertEquals("See [[Renamed]] and ![[Renamed|preview]].\n", root.resolve("Linking.md").readText())
    }

    @Test
    fun batchRenameIsAtomicUpdatesLinksWithoutCascadingAndCanBeUndone() = withWorkspace { root ->
        service.atomicWrite(root.resolve("A.md"), "# A\n")
        service.atomicWrite(root.resolve("B.md"), "# B\n")
        service.atomicWrite(root.resolve("Links.md"), "[[A]] then [[B]]\n")

        val batch = service.renameEntries(root, linkedMapOf("A.md" to "B.md", "B.md" to "C.md"))

        assertEquals(listOf("B.md", "C.md"), batch.entries.map { it.newRelativePath })
        assertFalse(Files.exists(root.resolve("A.md")))
        assertTrue(Files.exists(root.resolve("B.md")))
        assertTrue(Files.exists(root.resolve("C.md")))
        assertEquals("[[B]] then [[C]]\n", root.resolve("Links.md").readText())

        service.undoRename(root, batch)

        assertTrue(Files.exists(root.resolve("A.md")))
        assertTrue(Files.exists(root.resolve("B.md")))
        assertFalse(Files.exists(root.resolve("C.md")))
        assertEquals("[[A]] then [[B]]\n", root.resolve("Links.md").readText())
    }

    @Test
    fun searchReportsExactMatchRangeAndReadablePreview() = withWorkspace { root ->
        service.atomicWrite(root.resolve("Note.md"), "prefix text and ImportantTerm after text\n")

        val hit = service.search(root, "importantterm").single()

        assertEquals(1, hit.line)
        assertEquals(17, hit.column)
        assertEquals("importantterm".length, hit.matchLength)
        assertEquals("ImportantTerm", hit.preview.substring(hit.previewMatchStart, hit.previewMatchStart + hit.matchLength))
    }

    @Test
    fun trashCanBeRestored() = withWorkspace { root ->
        service.atomicWrite(root.resolve("notes/Keep.md"), "# Keep\n")

        val batch = service.trashEntries(root, listOf("notes"))
        assertFalse(Files.exists(root.resolve("notes")))

        service.restoreTrash(batch)
        assertEquals("# Keep\n", root.resolve("notes/Keep.md").readText())
    }

    @Test
    fun copyAndMoveRejectConflicts() = withWorkspace { root ->
        service.createEntry(root, "", "source", directory = true)
        service.createEntry(root, "", "target", directory = true)
        service.createEntry(root, "source", "Note.md", directory = false)

        assertEquals(listOf("target/Note.md"), service.copyEntries(root, listOf("source/Note.md"), "target"))
        service.createEntry(root, "", "moved", directory = true)
        assertEquals(listOf("moved/Note.md"), service.moveEntries(root, listOf("source/Note.md"), "moved"))
        assertTrue(Files.exists(root.resolve("moved/Note.md")))
    }

    @Test
    fun workbenchPersistenceKeepsLayoutAndAgentRuntimeSlots() = withWorkspace { root ->
        val expected = PersistedWorkbench(
            workspace = root.toString(),
            openDocuments = listOf("Note.md", "xuexue://agent"),
            activeDocument = "xuexue://agent",
            recentDocuments = listOf("Note.md", "Reading.pdf"),
            leftPanelVisible = false,
            agentPanelVisible = false,
            workspaceTrusted = false,
            agentReadOnlyMode = true,
            agentOfflineMode = true,
            agentAllowWrite = false,
            agentAllowShell = false,
            secondaryTool = "SEARCH",
            customLayoutPresets = listOf(
                PersistedLayoutPreset(
                    name = "研究布局",
                    leftPanelVisible = true,
                    agentPanelVisible = true,
                    activityRailVisible = true,
                    activityRailRight = true,
                    toolPanelDock = "BOTTOM",
                    leftWidth = 320f,
                    agentWidth = 420f,
                    bottomPanelHeight = 280f,
                    activeTool = "BACKLINKS",
                    toolTabs = listOf("FILES", "BACKLINKS"),
                    toolPanelPinned = false,
                    toolPanelExpanded = true,
                    agentFloating = false,
                    documentSplit = "VERTICAL",
                ),
            ),
            pdfViewModes = mapOf("Reading.pdf" to "DOUBLE"),
            pdfFitModes = mapOf("Reading.pdf" to "HEIGHT"),
            pdfSidePanels = mapOf("Reading.pdf" to "ANNOTATIONS"),
            pdfRotations = mapOf("Reading.pdf" to 90f),
            editorBlockIndices = mapOf("Note.md" to 7),
        )

        service.saveWorkbench(root, expected)
        val restored = service.loadWorkbench(root)

        assertEquals(expected.openDocuments, restored.openDocuments)
        assertEquals(expected.activeDocument, restored.activeDocument)
        assertEquals(expected.recentDocuments, restored.recentDocuments)
        assertEquals(expected.leftPanelVisible, restored.leftPanelVisible)
        assertEquals(expected.agentPanelVisible, restored.agentPanelVisible)
        assertEquals(expected.workspaceTrusted, restored.workspaceTrusted)
        assertEquals(expected.agentReadOnlyMode, restored.agentReadOnlyMode)
        assertEquals(expected.agentOfflineMode, restored.agentOfflineMode)
        assertEquals(expected.agentAllowWrite, restored.agentAllowWrite)
        assertEquals(expected.agentAllowShell, restored.agentAllowShell)
        assertEquals(expected.secondaryTool, restored.secondaryTool)
        assertEquals(expected.customLayoutPresets, restored.customLayoutPresets)
        assertEquals(expected.pdfViewModes, restored.pdfViewModes)
        assertEquals(expected.pdfFitModes, restored.pdfFitModes)
        assertEquals(expected.pdfSidePanels, restored.pdfSidePanels)
        assertEquals(expected.pdfRotations, restored.pdfRotations)
        assertEquals(expected.editorBlockIndices, restored.editorBlockIndices)
    }

    @Test
    fun workspaceRevisionChangesForExternalStructureUpdates() = withWorkspace { root ->
        val before = service.workspaceRevision(root)
        service.atomicWrite(root.resolve("External.md"), "# External\n")
        val afterCreate = service.workspaceRevision(root)
        Files.move(root.resolve("External.md"), root.resolve("Moved.md"))
        val afterMove = service.workspaceRevision(root)

        assertTrue(before != afterCreate)
        assertTrue(afterCreate != afterMove)
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("xuexue-workspace-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
