package dev.xuexue

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfRasterRequest
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PdfSearchMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkbenchStateTest {
    @Test
    fun focusCommandsRevealAndTargetWorkbenchRegions() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.activityRailVisible = false
        state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.ACTIVITY_RAIL))
        assertTrue(state.activityRailVisible)
        assertEquals(WorkbenchFocusTarget.ACTIVITY_RAIL, state.requestedFocusTarget)

        state.leftPanelVisible = false
        val serial = state.focusRequestSerial
        state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.TOOL_WINDOW))
        assertTrue(state.leftPanelVisible)
        assertEquals(WorkbenchFocusTarget.TOOL_WINDOW, state.requestedFocusTarget)
        assertEquals(serial + 1, state.focusRequestSerial)
    }

    @Test
    fun manualPdfZoomLeavesFitModeAndFitHeightUsesSinglePage() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        val pdf = DocumentTab("Reading.pdf", DocumentKind.PDF, "", pinned = true)
        state.documents += pdf

        state.setPdfFitMode("HEIGHT", pdf)
        assertEquals("HEIGHT", pdf.pdfFitMode)
        assertEquals("SINGLE", pdf.pdfViewMode)

        state.setPdfPosition(zoom = 1.5f, document = pdf)
        assertEquals("MANUAL", pdf.pdfFitMode)
        assertEquals(1.5f, pdf.pdfZoom)

        state.setPdfPosition(page = 4, scrollOffset = 620, document = pdf)
        assertEquals(4, pdf.pdfPage)
        assertEquals(620, pdf.pdfScrollOffset)

        pdf.pdfHorizontalOffset = 120
        pdf.pdfVerticalOffset = 340
        state.setPdfPosition(page = 5, document = pdf)
        assertEquals(0, pdf.pdfScrollOffset)
        assertEquals(0, pdf.pdfHorizontalOffset)
        assertEquals(0, pdf.pdfVerticalOffset)
    }

    @Test
    fun pdfUiOperationsUseTheInjectedBackendBoundary() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-pdf-backend")
            val stateJob = SupervisorJob()
            val requests = mutableListOf<PdfRasterRequest>()
            val inspectedPaths = mutableListOf<Path>()
            val backend =
                object : PdfReaderBackend {
                    override suspend fun inspect(path: Path): PdfDocumentMetadata {
                        inspectedPaths.add(path)
                        return PdfDocumentMetadata(pageCount = 7)
                    }

                    override suspend fun pageDimensions(
                        path: Path,
                        pageIndex: Int,
                    ): PdfPageDimensions = PdfPageDimensions(612f, 792f)

                    override suspend fun render(request: PdfRasterRequest): PdfPageRaster {
                        requests += request
                        return PdfPageRaster(request.pageIndex, 1, 1, byteArrayOf(1, 2, 3, 4))
                    }

                    override suspend fun search(
                        path: Path,
                        query: String,
                    ): List<PdfSearchMatch> = listOf(PdfSearchMatch(pageIndex = 4, preview = "matching text"))

                    override suspend fun extractPageText(
                        path: Path,
                        pageIndex: Int,
                    ): String = "page $pageIndex"
                }
            val state =
                WorkbenchState(
                    scope = CoroutineScope(stateJob + Dispatchers.Unconfined),
                    pdfReaderBackend = backend,
                )
            try {
                Files.write(root.resolve("Reading.pdf"), byteArrayOf(0))
                state.openWorkspace(root)
                state.openRelative("Reading.pdf", pinned = true)
                val tab = state.activeDocument!!

                assertEquals(7, state.inspectPdf(tab).pageCount)
                assertEquals(root.resolve("Reading.pdf"), inspectedPaths.single())
                assertEquals(
                    byteArrayOf(1, 2, 3, 4).toList(),
                    state.renderPdfPage(tab, 2, 96f, PdfRasterPurpose.VIEWPORT).rgbaBytes.toList(),
                )
                assertEquals(PdfRasterPurpose.VIEWPORT, requests.single().purpose)
                assertEquals(4, state.searchPdf(tab, "match").single().pageIndex)
                assertEquals("page 3", state.extractPdfPageText(tab, 3))
            } finally {
                state.closeWorkspace().join()
                stateJob.cancelAndJoin()
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    @Test
    fun agentDocumentIsUniqueAndTakesWorkbenchFocus() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.agentPanelVisible = true
        state.openAgentDocument()
        state.openAgentDocument()

        assertEquals(1, state.documents.count { it.kind == DocumentKind.AGENT })
        assertEquals(AGENT_DOCUMENT_PATH, state.activePath)
        assertFalse(state.agentPanelVisible)
        assertFalse(state.agentFloating)
    }

    @Test
    fun dockPreviewCommitsToolWindowTarget() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.workbenchBounds = Rect(0f, 0f, 1_000f, 800f)

        state.beginToolDockDrag(Offset(950f, 400f))
        assertEquals(ToolPanelDock.RIGHT, state.toolDockPreview)
        state.finishToolDockDrag()

        assertEquals(ToolPanelDock.RIGHT, state.toolPanelDock)
        assertFalse(state.toolDockDragging)
    }

    @Test
    fun documentTabCanMoveToSplitAndDetach() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.documents += DocumentTab("Primary.md", DocumentKind.MARKDOWN, "", pinned = true)
        state.documents += DocumentTab("Reference.md", DocumentKind.MARKDOWN, "", pinned = true)
        state.selectTab("Primary.md")
        state.documentDropBounds = Rect(0f, 0f, 1_000f, 800f)

        state.beginDocumentTabDrag("Reference.md", Offset(500f, 20f))
        state.updateDocumentTabDrag(Offset(920f, 400f))
        state.finishDocumentTabDrag()

        assertEquals(DocumentSplit.VERTICAL, state.documentSplit)
        assertEquals("Reference.md", state.secondaryPath)
        assertEquals("Primary.md", state.activePath)

        state.beginDocumentTabDrag("Primary.md", Offset(500f, 20f))
        state.updateDocumentTabDrag(Offset(-20f, 400f))
        state.finishDocumentTabDrag()
        assertTrue("Primary.md" in state.detachedDocuments)
    }

    @Test
    fun documentCommandsPinSplitAndDetachTheRequestedTab() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        val primary = DocumentTab("Primary.md", DocumentKind.MARKDOWN, "", pinned = true)
        val reference = DocumentTab("Reference.md", DocumentKind.MARKDOWN, "", pinned = false)
        state.documents += primary
        state.documents += reference
        state.selectTab("Primary.md")

        state.execute(WorkbenchCommand.PinDocument("Reference.md"))
        assertTrue(reference.pinned)

        state.execute(WorkbenchCommand.SplitDocument("Reference.md", DocumentSplitDirection.RIGHT))
        assertEquals(DocumentSplit.VERTICAL, state.documentSplit)
        assertEquals("Reference.md", state.secondaryPath)
        assertEquals("Primary.md", state.activePath)

        state.execute(WorkbenchCommand.DetachDocument("Reference.md"))
        assertTrue("Reference.md" in state.detachedDocuments)
    }

    @Test
    fun workspaceCommandsOpenEntriesAndBuildAgentContext() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        val note = DocumentTab("Note.md", DocumentKind.MARKDOWN, "", pinned = false)
        state.documents += note

        state.execute(WorkbenchCommand.OpenWorkspaceEntry("Note.md", pinned = true))
        assertEquals("Note.md", state.activePath)
        assertTrue(note.pinned)

        state.execute(WorkbenchCommand.AddAgentContext("Note.md"))
        assertEquals(listOf("Note.md"), state.agentContextPaths)
        assertTrue(state.agentPanelVisible)
    }

    @Test
    fun workspaceEntryCommandLoadsAnUnopenedFile() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-open-workspace-entry")
            val stateJob = SupervisorJob()
            val state = WorkbenchState(CoroutineScope(stateJob + Dispatchers.Unconfined))
            try {
                Files.writeString(root.resolve("Note.md"), "loaded from the workspace")
                state.openWorkspace(root)

                state.execute(WorkbenchCommand.OpenWorkspaceEntry("Note.md"))

                withTimeout(2_000) {
                    while (state.activePath != "Note.md") yield()
                }
                assertEquals("loaded from the workspace", state.activeDocument?.content)
            } finally {
                state.closeWorkspace().join()
                stateJob.cancelAndJoin()
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }

    @Test
    fun customLayoutPresetCanBeSavedAppliedAndDeleted() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.leftPanelVisible = false
        state.agentPanelVisible = true
        state.activityRailRight = true
        state.toolPanelDock = ToolPanelDock.BOTTOM
        state.leftPanelWidth = 340f
        state.agentPanelWidth = 430f

        state.saveCurrentLayoutPreset("研究")
        val preset = state.customLayoutPresets.single()

        state.leftPanelVisible = true
        state.agentPanelVisible = false
        state.activityRailRight = false
        state.toolPanelDock = ToolPanelDock.LEFT
        state.applyCustomLayoutPreset(preset)

        assertFalse(state.leftPanelVisible)
        assertTrue(state.agentPanelVisible)
        assertTrue(state.activityRailRight)
        assertEquals(ToolPanelDock.BOTTOM, state.toolPanelDock)
        assertEquals(340f, state.leftPanelWidth)
        assertEquals(430f, state.agentPanelWidth)

        state.deleteCustomLayoutPreset("研究")
        assertTrue(state.customLayoutPresets.isEmpty())
    }

    @Test
    fun toolWindowsCanSplitSwapAndCloseInsideOneDock() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))

        state.splitToolWindow(ToolWindow.SEARCH)
        assertEquals(ToolWindow.FILES, state.activeTool)
        assertEquals(ToolWindow.SEARCH, state.secondaryTool)
        assertTrue(ToolWindow.SEARCH in state.toolTabs)

        state.openToolWindow(ToolWindow.SEARCH)
        assertEquals(ToolWindow.SEARCH, state.activeTool)
        assertEquals(ToolWindow.FILES, state.secondaryTool)

        state.closeToolTab(ToolWindow.FILES)
        assertNull(state.secondaryTool)
    }

    @Test
    fun backgroundTaskSupportsProgressCancelAndRetry() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        var cancelled = false
        state.beginBackgroundTask("pdf", "PDF 搜索", "开始", onCancel = { cancelled = true })
        state.updateBackgroundTask("pdf", "50 / 100", 0.5f)

        assertEquals(0.5f, state.backgroundTasks.single().progress)
        state.cancelBackgroundTask("pdf")
        assertTrue(cancelled)
        assertTrue(state.backgroundTasks.isEmpty())

        var retried = false
        state.failBackgroundTask("remote", "Remote PDF", "Interrupted", onRetry = { retried = true })
        assertTrue(state.backgroundTasks.single().retryable)
        state.retryBackgroundTask("remote")
        assertTrue(retried)
        assertTrue(state.backgroundTasks.isEmpty())
    }

    @Test
    fun documentSwitcherWrapsInBothDirections() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.documents += DocumentTab("A.md", DocumentKind.MARKDOWN, "", pinned = true)
        state.documents += DocumentTab("B.md", DocumentKind.MARKDOWN, "", pinned = true)
        state.selectTab("A.md")

        state.selectNextDocument()
        assertEquals("B.md", state.activePath)
        state.selectNextDocument()
        assertEquals("A.md", state.activePath)
        state.selectNextDocument(reverse = true)
        assertEquals("B.md", state.activePath)
    }

    @Test
    fun navigationHistoryRestoresCursorAndPdfPage() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        val note =
            DocumentTab("A.md", DocumentKind.MARKDOWN, "alpha", pinned = true).apply {
                cursor =
                    androidx.compose.ui.text
                        .TextRange(3)
            }
        val pdf = DocumentTab("B.pdf", DocumentKind.PDF, "", pinned = true).apply { pdfPage = 6 }
        state.documents += note
        state.documents += pdf
        state.selectTab("A.md")

        state.navigateTo("B.pdf")
        assertEquals("B.pdf", state.activePath)
        assertTrue(state.canNavigateBack)

        state.navigateBack()
        assertEquals("A.md", state.activePath)
        assertEquals(3, state.activeDocument?.cursor?.start)
        assertTrue(state.canNavigateForward)

        state.navigateForward()
        assertEquals("B.pdf", state.activePath)
        assertEquals(6, state.activeDocument?.pdfPage)
    }

    @Test
    fun navigationCanSelectTheExactSearchMatch() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        val note = DocumentTab("A.md", DocumentKind.MARKDOWN, "0123456789", pinned = true)
        state.documents += note

        state.navigateTo("A.md", cursorOffset = 3, selectionLength = 4)

        assertEquals(3, note.cursor.start)
        assertEquals(7, note.cursor.end)
        assertEquals(WorkbenchFocusTarget.DOCUMENT, state.requestedFocusTarget)
    }

    @Test
    fun selectingOpenDocumentsMaintainsRecentOrder() {
        val state = WorkbenchState(CoroutineScope(EmptyCoroutineContext))
        state.documents += DocumentTab("A.md", DocumentKind.MARKDOWN, "", pinned = true)
        state.documents += DocumentTab("B.md", DocumentKind.MARKDOWN, "", pinned = true)

        state.openRelative("A.md")
        state.openRelative("B.md")
        state.openRelative("A.md")

        assertEquals(listOf("A.md", "B.md"), state.recentDocuments)
    }

    @Test
    fun saveFailureCanRetryAndSaveAsInsideWorkspace() =
        runBlocking {
            val root = Files.createTempDirectory("xuexue-save-recovery")
            val stateJob = SupervisorJob()
            val state = WorkbenchState(CoroutineScope(stateJob + Dispatchers.Unconfined))
            try {
                Files.createDirectories(root.resolve("notes"))
                Files.writeString(root.resolve("notes/Note.md"), "original")
                state.openWorkspace(root)
                state.openRelative("notes/Note.md", pinned = true)
                val tab = state.activeDocument!!
                tab.content = "changed"

                Files.delete(root.resolve("notes/Note.md"))
                Files.delete(root.resolve("notes"))
                Files.writeString(root.resolve("notes"), "blocks parent directory")
                state.save(tab).join()
                assertNotNull(tab.saveError)

                Files.delete(root.resolve("notes"))
                Files.createDirectories(root.resolve("notes"))
                state.retrySave(tab).join()
                assertNull(tab.saveError)
                assertEquals("changed", Files.readString(root.resolve("notes/Note.md")))

                tab.content = "saved elsewhere"
                state.saveAs(tab, root.resolve("Recovered.md"))?.join()
                assertEquals("Recovered.md", tab.relativePath)
                assertEquals("saved elsewhere", Files.readString(root.resolve("Recovered.md")))
                assertTrue("Recovered.md" in state.recentDocuments)
            } finally {
                state.closeWorkspace().join()
                stateJob.cancelAndJoin()
                val paths = if (Files.exists(root)) Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() } else emptyList()
                paths.forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
        }
}
