package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextRange
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRecord
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.AgentStreamEvent
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.EditorCommand
import dev.xuexue.core.EditorCommands
import dev.xuexue.core.EditorMode
import dev.xuexue.core.IndexedNote
import dev.xuexue.core.MarkdownCompletion
import dev.xuexue.core.MarkdownCompletionEngine
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.TextSelection
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import dev.xuexue.core.agentSendAvailability
import dev.xuexue.core.requiresExplicitApproval
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.AppPreferences
import dev.xuexue.platform.CachingPdfReaderBackend
import dev.xuexue.platform.DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER
import dev.xuexue.platform.MuPdfReaderBackend
import dev.xuexue.platform.OpenAiCompatibleAgentClient
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfLink
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfPerformanceMetrics
import dev.xuexue.platform.PdfPerformanceSnapshot
import dev.xuexue.platform.PdfRasterLayer
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PdfSearchMatch
import dev.xuexue.platform.PdfTextSelection
import dev.xuexue.platform.PdfTextureCache
import dev.xuexue.platform.PersistedLayoutPreset
import dev.xuexue.platform.PersistedWorkbench
import dev.xuexue.platform.ProgressivePdfRegistry
import dev.xuexue.platform.RenamedEntry
import dev.xuexue.platform.SearchHit
import dev.xuexue.platform.WorkspaceService
import dev.xuexue.platform.normalizedDocumentWheelZoomMultiplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

private data class NavigationLocation(
    val relativePath: String,
    val cursor: TextRange,
    val editorBlockIndex: Int,
    val pdfPage: Int,
)

private data class WorkspaceEntryLoad(
    val kind: DocumentKind,
    val content: String,
    val modifiedAt: Long,
)

private data class PendingWorkspaceEntryOpen(
    val requestSerial: Long,
    val pinned: Boolean,
)

class WorkbenchState(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService = WorkspaceService(),
    private val agentClient: AgentClient = OpenAiCompatibleAgentClient(),
    private val pdfMetrics: PdfPerformanceMetrics = PdfPerformanceMetrics(),
    pdfReaderBackend: PdfReaderBackend? = null,
    private val layoutState: WorkbenchLayoutState = WorkbenchLayoutState(),
    private val agentState: AgentRuntimeState = AgentRuntimeState(),
) : WorkbenchLayoutModel by layoutState,
    AgentRuntimeModel by agentState {
    private val progressivePdfSources = ProgressivePdfRegistry()
    private val resolvedPdfReaderBackend =
        pdfReaderBackend
            ?: CachingPdfReaderBackend(
                MuPdfReaderBackend(pdfMetrics, progressivePdfSources),
                metrics = pdfMetrics,
            )
    internal val pdfTextureCache =
        PdfTextureCache<ImageBitmap>(
            onBytesChanged = pdfMetrics.textures::updateCacheBytes,
            onEviction = pdfMetrics.textures::recordCacheEviction,
        )
    var workspaceRoot by mutableStateOf<Path?>(null)
        private set
    var workspaceTree by mutableStateOf<WorkspaceEntry?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var status by mutableStateOf("Ready")
        private set
    var documentWheelZoomMultiplier by mutableStateOf(DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var dragPayload by mutableStateOf<WorkspaceDragPayload?>(null)
        private set
    var dragPosition by mutableStateOf(Offset.Unspecified)
        private set
    var documentDropBounds by mutableStateOf<Rect?>(null)
    var agentDropBounds by mutableStateOf<Rect?>(null)
    var workbenchBounds by mutableStateOf<Rect?>(null)
    var toolDockDragging by mutableStateOf(false)
        private set
    var toolDockPreview by mutableStateOf<ToolPanelDock?>(null)
        private set
    var documentTabDragPath by mutableStateOf<String?>(null)
        private set
    var documentTabDropTarget by mutableStateOf<DocumentDropTarget?>(null)
        private set
    private val taskController = WorkbenchTaskController { message -> status = message }
    private var shutdown = false
    val backgroundTasks get() = taskController.tasks
    val pdfPerformance: PdfPerformanceSnapshot
        get() = pdfMetrics.snapshot()

    internal val pdfPerformanceMetrics: PdfPerformanceMetrics
        get() = pdfMetrics
    val documents = mutableStateListOf<DocumentTab>()
    val recentDocuments = mutableStateListOf<String>()
    var activePath by mutableStateOf<String?>(null)
        private set
    val activeDocument: DocumentTab? get() = documents.firstOrNull { it.relativePath == activePath }
    private val backNavigation = ArrayDeque<NavigationLocation>()
    private val forwardNavigation = ArrayDeque<NavigationLocation>()
    private var latestWorkspaceEntryOpenRequest = 0L
    private val pendingWorkspaceEntryOpens = mutableMapOf<String, PendingWorkspaceEntryOpen>()
    val canNavigateBack: Boolean get() = backNavigation.isNotEmpty()
    val canNavigateForward: Boolean get() = forwardNavigation.isNotEmpty()

    var searchQuery by mutableStateOf("")
    val searchResults = mutableStateListOf<SearchHit>()
    var workspaceIndex by mutableStateOf(WorkspaceIndex(emptyList()))
        private set

    val canRestoreDeletedAgentSession: Boolean get() = agentController.canRestoreDeletedSession
    private var watcherJob: Job? = null
    private val agentController =
        WorkbenchAgentController(
            scope = scope,
            runtime = agentState,
            client = agentClient,
            workspaceService = workspaceService,
            workspaceRoot = { workspaceRoot },
            activeDocument = { activeDocument },
            revealPanel = { agentPanelVisible = true },
            onStatus = { message -> status = message },
            onFileMessage = { message -> fileOperationMessage = message },
            persistWorkbench = ::persistWorkbench,
        )
    private val fileController =
        WorkbenchFileController(
            scope = scope,
            workspaceService = workspaceService,
            workspaceRoot = { workspaceRoot },
            documents = { documents.toList() },
            reloadWorkspace = ::reloadWorkspaceNow,
            openRelative = { path, pinned -> openRelative(path, pinned) },
            closeTab = { tab -> closeTab(tab, force = true) },
            applyPathRenames = ::applyPathRenames,
            persistWorkbench = ::persistWorkbench,
            onError = { message -> errorMessage = message },
        )
    private val documentController =
        WorkbenchDocumentController(
            scope = scope,
            workspaceService = workspaceService,
            workspaceRoot = { workspaceRoot },
            documents = { documents.toList() },
            markdownOverrides = ::openMarkdownOverrides,
            onWorkspaceTreeChanged = { tree -> workspaceTree = tree },
            onWorkspaceIndexChanged = { index -> workspaceIndex = index },
            onDocumentPathChanged = ::applySavedAsPath,
            onStatus = { message -> status = message },
            onError = { message -> errorMessage = message },
            persistWorkbench = ::persistWorkbench,
            refreshWorkspace = ::refreshWorkspace,
        )
    private val pdfController =
        WorkbenchPdfController(
            scope = scope,
            workspaceService = workspaceService,
            backend = resolvedPdfReaderBackend,
            workspaceRoot = { workspaceRoot },
            reloadWorkspace = ::reloadWorkspaceNow,
            openRelative = { path, pinned -> openRelative(path, pinned) },
            persistWorkbench = ::persistWorkbench,
            onMessage = { message -> fileOperationMessage = message },
            onError = { message -> errorMessage = message },
        )
    private val remotePdfController =
        WorkbenchRemotePdfController(
            scope = scope,
            infrastructure = RemotePdfInfrastructure(workspaceService, progressivePdfSources),
            taskController = taskController,
            workspaceRoot = { workspaceRoot },
            offlineMode = { agentOfflineMode },
            reloadWorkspace = ::reloadWorkspaceNow,
            openRelative = { path, pinned -> openRelative(path, pinned) },
            openToolWindow = ::openToolWindow,
            onStatus = { message -> status = message },
            onError = { message -> errorMessage = message },
        )

    var fileOperationMessage: String?
        get() = fileController.message
        private set(value) {
            fileController.message = value
        }
    val renameImpactPaths: List<String> get() = fileController.renameImpactPaths
    val renameImpactLoading: Boolean get() = fileController.renameImpactLoading
    val canUndoFileOperation: Boolean get() = fileController.canUndo
    var remotePdfDialogVisible: Boolean
        get() = remotePdfController.dialogVisible
        set(value) {
            remotePdfController.dialogVisible = value
        }
    internal val remotePdfDownloadStates: Map<String, RemotePdfDownloadState>
        get() = remotePdfController.downloadStates

    suspend fun restoreLastWorkspace() {
        val restored =
            withContext(Dispatchers.IO) {
                workspaceService.loadAppPreferences() to workspaceService.loadLastWorkspace()
            }
        documentWheelZoomMultiplier = restored.first.documentWheelZoomMultiplier
        val root = restored.second ?: return
        openWorkspace(root, restore = true)
    }

    fun shutdown() {
        if (shutdown) return
        shutdown = true
        runCatching {
            workspaceService.saveAppPreferences(AppPreferences(documentWheelZoomMultiplier))
        }
        pdfTextureCache.close()
        resolvedPdfReaderBackend.close()
        progressivePdfSources.close()
    }

    suspend fun openWorkspace(
        root: Path,
        restore: Boolean = false,
    ) {
        if (!Files.isDirectory(root)) {
            errorMessage = "The selected workspace is not a readable directory."
            return
        }
        isLoading = true
        errorMessage = null
        status = "Opening workspace..."
        runCatching {
            val safeRoot = withContext(Dispatchers.IO) { workspaceService.canonicalRoot(root) }
            val persisted =
                withContext(Dispatchers.IO) {
                    val tree = workspaceService.scan(safeRoot)
                    val state = if (restore) workspaceService.loadWorkbench(safeRoot) else PersistedWorkbench(workspace = safeRoot.pathString)
                    Triple(tree, workspaceService.buildIndex(safeRoot), state)
                }
            discardWorkspaceEntryOpenRequests()
            workspaceRoot = safeRoot
            val restoredRemotePdfUrls = remotePdfController.restoreRangeDownloads(safeRoot)
            workspaceTree = persisted.first
            workspaceIndex = persisted.second
            documents.clear()
            backNavigation.clear()
            forwardNavigation.clear()
            recentDocuments.clear()
            recentDocuments += persisted.third.recentDocuments
            activePath = null
            leftPanelWidth = persisted.third.leftWidth.coerceIn(210f, 480f)
            agentPanelWidth = persisted.third.agentWidth.coerceIn(300f, 540f)
            leftPanelVisible = persisted.third.leftPanelVisible
            agentPanelVisible = persisted.third.agentPanelVisible
            activityRailVisible = persisted.third.activityRailVisible
            activityRailRight = persisted.third.activityRailRight
            toolPanelDock = runCatching { ToolPanelDock.valueOf(persisted.third.toolPanelDock) }.getOrDefault(ToolPanelDock.LEFT)
            bottomPanelHeight = persisted.third.bottomPanelHeight.coerceIn(150f, 420f)
            documentSplit = runCatching { DocumentSplit.valueOf(persisted.third.documentSplit) }.getOrDefault(DocumentSplit.NONE)
            toolPanelPinned = persisted.third.toolPanelPinned
            toolPanelExpanded = persisted.third.toolPanelExpanded
            agentFloating = persisted.third.agentFloating
            workspaceTrusted = persisted.third.workspaceTrusted
            agentReadOnlyMode = persisted.third.agentReadOnlyMode
            agentOfflineMode = persisted.third.agentOfflineMode
            agentAllowWrite = persisted.third.agentAllowWrite
            agentAllowShell = persisted.third.agentAllowShell
            customLayoutPresets.clear()
            customLayoutPresets += persisted.third.customLayoutPresets
            activeTool = persisted.third.activeTool?.let { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
                ?: ToolWindow.FILES
            toolTabs.clear()
            toolTabs +=
                persisted.third.toolTabs
                    .mapNotNull { name -> runCatching { ToolWindow.valueOf(name) }.getOrNull() }
                    .filter { it != ToolWindow.AGENT }
            if (toolTabs.isEmpty()) toolTabs += activeTool.takeIf { it != ToolWindow.AGENT } ?: ToolWindow.FILES
            if (activeTool != ToolWindow.AGENT && !toolTabs.contains(activeTool)) toolTabs += activeTool
            secondaryTool =
                persisted.third.secondaryTool
                    ?.let { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != activeTool && it != ToolWindow.AGENT }
            secondaryTool?.let { if (!toolTabs.contains(it)) toolTabs += it }
            if (restore) {
                persisted.third.openDocuments.forEach { relative ->
                    openRelative(relative, pinned = true, persist = false)
                    documents.firstOrNull { it.relativePath == relative }?.apply {
                        mode = persisted.third.editorModes[relative]
                            ?.let { runCatching { EditorMode.valueOf(it) }.getOrNull() }
                            ?: mode
                        val offset = persisted.third.cursorOffsets[relative]?.coerceIn(0, content.length) ?: 0
                        cursor = TextRange(offset)
                        editorBlockIndex = persisted.third.editorBlockIndices[relative]?.coerceAtLeast(0) ?: 0
                        pdfPage = persisted.third.pdfPages[relative]?.coerceAtLeast(0) ?: 0
                        pdfScrollOffset = persisted.third.pdfScrollOffsets[relative]?.coerceAtLeast(0) ?: 0
                        pdfHorizontalOffset = persisted.third.pdfHorizontalOffsets[relative]?.coerceAtLeast(0) ?: 0
                        pdfVerticalOffset = persisted.third.pdfVerticalOffsets[relative]?.coerceAtLeast(0) ?: 0
                        pdfZoom = persisted.third.pdfZooms[relative]?.coerceIn(0.4f, 4f) ?: 1f
                        pdfFitMode = persisted.third.pdfFitModes[relative] ?: pdfFitMode
                        pdfViewMode = persisted.third.pdfViewModes[relative] ?: pdfViewMode
                        pdfSidePanel = persisted.third.pdfSidePanels[relative] ?: pdfSidePanel
                        pdfRotation = persisted.third.pdfRotations[relative] ?: pdfRotation
                        pdfColorMode = persisted.third.pdfColorModes[relative] ?: pdfColorMode
                    }
                }
                activePath = persisted.third.activeDocument?.takeIf { path -> documents.any { it.relativePath == path } }
                    ?: documents.firstOrNull()?.relativePath
                secondaryPath = persisted.third.secondaryDocument?.takeIf { path -> documents.any { it.relativePath == path } }
                if (documentSplit != DocumentSplit.NONE && secondaryPath == null) {
                    secondaryPath = documents.firstOrNull { it.relativePath != activePath }?.relativePath ?: activePath
                }
                detachedDocuments.clear()
                detachedDocuments += persisted.third.detachedDocuments.filter { path -> documents.any { it.relativePath == path } }
            }
            agentController.loadTranscript(safeRoot)
            persisted.third.activeAgentSession
                ?.takeIf { id -> agentSessions.any { it.id == id } }
                ?.let { activeAgentSessionId = it }
            status = safeRoot.fileName?.toString() ?: safeRoot.pathString
            startWorkspaceWatcher()
            persistWorkbench()
            restoredRemotePdfUrls.forEach(remotePdfController::download)
        }.onFailure { throwable ->
            errorMessage = throwable.message ?: "Could not open the workspace."
            status = "Open failed"
        }
        isLoading = false
    }

    fun closeWorkspace(): Job =
        scope.launch {
            watcherJob?.cancel()
            discardWorkspaceEntryOpenRequests()
            saveAllDirty()
            documentController.cancelAll()
            fileController.reset()
            taskController.clear(cancelRunning = true)
            remotePdfController.reset()
            remotePdfDialogVisible = false
            persistWorkbench()
            workspaceRoot = null
            workspaceTree = null
            documents.clear()
            recentDocuments.clear()
            activePath = null
            backNavigation.clear()
            forwardNavigation.clear()
            searchResults.clear()
            toolTabs.clear()
            toolTabs += ToolWindow.FILES
            secondaryTool = null
            documentSplit = DocumentSplit.NONE
            secondaryPath = null
            detachedDocuments.clear()
            agentFloating = false
            pendingAgentApproval = null
            agentApprovalHistory.clear()
            customLayoutPresets.clear()
            agentSessions.clear()
            agentSessions += AgentSessionState("default", "默认会话")
            activeAgentSessionId = "default"
            status = "Ready"
        }

    fun refreshWorkspace() {
        val root = workspaceRoot ?: return
        val overrides = openMarkdownOverrides()
        scope.launch {
            isLoading = true
            runCatching {
                val (tree, index) =
                    withContext(Dispatchers.IO) {
                        workspaceService.scan(root) to workspaceService.buildIndex(root, overrides)
                    }
                workspaceTree = tree
                workspaceIndex = index
                status = "Workspace refreshed"
            }.onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun createMarkdown() {
        fileController.createMarkdown()
    }

    fun createEntry(
        parentRelative: String,
        name: String,
        directory: Boolean,
    ) {
        fileController.createEntry(parentRelative, name, directory)
    }

    fun inspectRenameImpact(relativePath: String) {
        fileController.inspectRenameImpact(relativePath)
    }

    fun renameEntry(
        relativePath: String,
        newName: String,
    ) {
        renameEntries(mapOf(relativePath to newName))
    }

    fun renameEntries(renames: Map<String, String>) {
        fileController.renameEntries(renames)
    }

    fun copyEntries(
        relativePaths: List<String>,
        targetDirectory: String,
    ) {
        fileController.copyEntries(relativePaths, targetDirectory)
    }

    fun moveEntries(
        relativePaths: List<String>,
        targetDirectory: String,
    ) {
        fileController.moveEntries(relativePaths, targetDirectory)
    }

    fun deleteEntries(relativePaths: List<String>) {
        fileController.deleteEntries(relativePaths)
    }

    fun undoLastFileOperation() {
        fileController.undoLastOperation()
    }

    private fun applyPathRenames(
        entries: List<RenamedEntry>,
        updatedContents: Map<String, String>,
    ) {
        fun remap(path: String): String {
            val match =
                entries.firstOrNull { entry ->
                    path == entry.oldRelativePath || path.startsWith("${entry.oldRelativePath}/")
                } ?: return path
            return match.newRelativePath + path.removePrefix(match.oldRelativePath)
        }
        documents.forEach { tab ->
            val oldPath = tab.relativePath
            updatedContents[oldPath]?.let { updated ->
                if (!tab.dirty) {
                    tab.content = updated
                    tab.savedContent = updated
                }
            }
            tab.relativePath = remap(oldPath)
        }
        activePath = activePath?.let(::remap)
        secondaryPath = secondaryPath?.let(::remap)
        detachedDocuments.indices.forEach { index -> detachedDocuments[index] = remap(detachedDocuments[index]) }
    }

    private fun applySavedAsPath(
        oldRelative: String,
        newRelative: String,
    ) {
        if (activePath == oldRelative) activePath = newRelative
        if (secondaryPath == oldRelative) secondaryPath = newRelative
        detachedDocuments.indices.forEach { index ->
            if (detachedDocuments[index] == oldRelative) detachedDocuments[index] = newRelative
        }
        recentDocuments.remove(oldRelative)
        recentDocuments.remove(newRelative)
        recentDocuments.add(0, newRelative)
    }

    fun clearFileOperationMessage() {
        fileOperationMessage = null
    }

    fun notifyFileOperation(message: String) {
        fileOperationMessage = message
    }

    fun openRelative(
        relativePath: String,
        pinned: Boolean = false,
        persist: Boolean = true,
    ) {
        nextWorkspaceEntryOpenRequest()
        val normalized = relativePath.replace('\\', '/')
        if (normalized == AGENT_DOCUMENT_PATH) {
            openAgentDocument(persist)
            return
        }
        documents.firstOrNull { it.relativePath == normalized }?.let { existing ->
            if (pinned) existing.pinned = true
            activePath = normalized
            recentDocuments.remove(normalized)
            recentDocuments.add(0, normalized)
            status = normalized
            if (persist) persistWorkbench()
            return
        }
        val root = workspaceRoot ?: return
        val path = runCatching { workspaceService.resolveExistingInside(root, normalized) }.getOrNull()
        if (path == null || !Files.isRegularFile(path)) {
            errorMessage = "File is no longer available: $normalized"
            return
        }
        runCatching {
            val kind = workspaceService.kindOf(path)
            val content =
                if (kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) {
                    workspaceService.readText(path)
                } else {
                    ""
                }
            if (!pinned) documents.firstOrNull { !it.pinned && !it.dirty }?.let { documents.remove(it) }
            val modifiedAt = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            documents += DocumentTab(normalized, kind, content, pinned, modifiedAt)
            documents.lastOrNull()?.takeIf { it.kind == DocumentKind.PDF }?.let(pdfController::loadAnnotations)
            activePath = normalized
            recentDocuments.remove(normalized)
            recentDocuments.add(0, normalized)
            while (recentDocuments.size > 12) recentDocuments.removeLast()
            status = normalized
            if (persist) persistWorkbench()
        }.onFailure { errorMessage = it.message ?: "Could not open $normalized" }
    }

    private fun openWorkspaceEntry(
        relativePath: String,
        pinned: Boolean,
    ) {
        val normalized = relativePath.replace('\\', '/')
        val requestSerial = nextWorkspaceEntryOpenRequest()
        when {
            normalized == AGENT_DOCUMENT_PATH -> openAgentDocument()
            activateExistingWorkspaceEntry(normalized, pinned) -> Unit
            else -> queueWorkspaceEntryOpen(normalized, pinned, requestSerial)
        }
    }

    private fun activateExistingWorkspaceEntry(
        normalized: String,
        pinned: Boolean,
    ): Boolean {
        val existing = documents.firstOrNull { it.relativePath == normalized } ?: return false
        if (pinned) existing.pinned = true
        activateWorkspaceEntry(normalized)
        persistWorkbench()
        return true
    }

    private fun queueWorkspaceEntryOpen(
        normalized: String,
        pinned: Boolean,
        requestSerial: Long,
    ) {
        val root = workspaceRoot ?: return
        val pending = pendingWorkspaceEntryOpens[normalized]
        if (pending != null) {
            pendingWorkspaceEntryOpens[normalized] =
                PendingWorkspaceEntryOpen(
                    requestSerial = requestSerial,
                    pinned = pending.pinned || pinned,
                )
        } else {
            pendingWorkspaceEntryOpens[normalized] = PendingWorkspaceEntryOpen(requestSerial, pinned)
            status = "Opening $normalized..."
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { loadWorkspaceEntry(root, normalized) } }
                completeWorkspaceEntryOpen(root, normalized, result)
            }
        }
    }

    private fun loadWorkspaceEntry(
        root: Path,
        normalized: String,
    ): WorkspaceEntryLoad {
        val path = workspaceService.resolveExistingInside(root, normalized)
        require(Files.isRegularFile(path)) { "File is no longer available: $normalized" }
        val kind = workspaceService.kindOf(path)
        val content =
            if (kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) {
                workspaceService.readText(path)
            } else {
                ""
            }
        return WorkspaceEntryLoad(
            kind = kind,
            content = content,
            modifiedAt = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
        )
    }

    private fun completeWorkspaceEntryOpen(
        root: Path,
        normalized: String,
        result: Result<WorkspaceEntryLoad>,
    ) {
        val request = pendingWorkspaceEntryOpens.remove(normalized)
        if (request != null && workspaceRoot == root) {
            result.fold(
                onSuccess = { loaded -> completeLoadedWorkspaceEntry(normalized, request, loaded) },
                onFailure = { error -> reportWorkspaceEntryOpenFailure(normalized, request, error) },
            )
        }
    }

    private fun completeLoadedWorkspaceEntry(
        normalized: String,
        request: PendingWorkspaceEntryOpen,
        loaded: WorkspaceEntryLoad,
    ) {
        val currentRequest = request.requestSerial == latestWorkspaceEntryOpenRequest
        if (currentRequest || request.pinned) {
            val document = ensureWorkspaceEntryDocument(normalized, request.pinned, loaded)
            if (request.pinned) document.pinned = true
            if (currentRequest) activateWorkspaceEntry(normalized)
            persistWorkbench()
        }
    }

    private fun ensureWorkspaceEntryDocument(
        normalized: String,
        pinned: Boolean,
        loaded: WorkspaceEntryLoad,
    ): DocumentTab =
        documents.firstOrNull { it.relativePath == normalized }
            ?: run {
                if (!pinned) documents.firstOrNull { !it.pinned && !it.dirty }?.let(documents::remove)
                DocumentTab(
                    relativePath = normalized,
                    kind = loaded.kind,
                    content = loaded.content,
                    pinned = pinned,
                    modifiedAt = loaded.modifiedAt,
                ).also { created ->
                    documents += created
                    if (created.kind == DocumentKind.PDF) pdfController.loadAnnotations(created)
                }
            }

    private fun activateWorkspaceEntry(normalized: String) {
        activePath = normalized
        recentDocuments.remove(normalized)
        recentDocuments.add(0, normalized)
        while (recentDocuments.size > 12) recentDocuments.removeLast()
        status = normalized
    }

    private fun reportWorkspaceEntryOpenFailure(
        normalized: String,
        request: PendingWorkspaceEntryOpen,
        error: Throwable,
    ) {
        if (request.requestSerial == latestWorkspaceEntryOpenRequest) {
            errorMessage = error.message ?: "Could not open $normalized"
            status = "Open failed"
        }
    }

    private fun nextWorkspaceEntryOpenRequest(): Long {
        latestWorkspaceEntryOpenRequest += 1
        return latestWorkspaceEntryOpenRequest
    }

    private fun discardWorkspaceEntryOpenRequests() {
        nextWorkspaceEntryOpenRequest()
        pendingWorkspaceEntryOpens.clear()
    }

    fun pin(relativePath: String) {
        documents.firstOrNull { it.relativePath == relativePath }?.pinned = true
        persistWorkbench()
    }

    fun openAgentDocument(persist: Boolean = true) {
        nextWorkspaceEntryOpenRequest()
        status = "Agent"
        documents.firstOrNull { it.kind == DocumentKind.AGENT }?.let { existing ->
            activePath = existing.relativePath
            agentPanelVisible = false
            agentFloating = false
            requestFocus(WorkbenchFocusTarget.DOCUMENT)
            if (persist) persistWorkbench()
            return
        }
        documents += DocumentTab(AGENT_DOCUMENT_PATH, DocumentKind.AGENT, "", pinned = true)
        activePath = AGENT_DOCUMENT_PATH
        agentPanelVisible = false
        agentFloating = false
        requestFocus(WorkbenchFocusTarget.DOCUMENT)
        if (persist) persistWorkbench()
    }

    fun selectTab(relativePath: String) {
        nextWorkspaceEntryOpenRequest()
        activePath = relativePath
        status = relativePath
        persistWorkbench()
    }

    fun selectNextDocument(reverse: Boolean = false) {
        if (documents.size < 2) return
        nextWorkspaceEntryOpenRequest()
        val current = documents.indexOfFirst { it.relativePath == activePath }.coerceAtLeast(0)
        val next = if (reverse) (current - 1 + documents.size) % documents.size else (current + 1) % documents.size
        activePath = documents[next].relativePath
        status = documents[next].relativePath
        persistWorkbench()
    }

    fun navigateTo(
        relativePath: String,
        cursorOffset: Int? = null,
        selectionLength: Int = 0,
    ) {
        activeDocument?.let { current ->
            if (current.relativePath != relativePath) backNavigation.addLast(current.navigationLocation())
        }
        forwardNavigation.clear()
        openRelative(relativePath, pinned = true)
        cursorOffset?.let { offset ->
            activeDocument?.takeIf { it.relativePath == relativePath }?.let { tab ->
                val safeOffset = offset.coerceIn(0, tab.content.length)
                tab.cursor = TextRange(safeOffset, (safeOffset + selectionLength).coerceIn(safeOffset, tab.content.length))
                requestFocus(WorkbenchFocusTarget.DOCUMENT)
            }
        }
    }

    fun navigateToSearchHit(hit: SearchHit) {
        val root = workspaceRoot ?: return
        val content =
            documents.firstOrNull { it.relativePath == hit.relativePath }?.content
                ?: runCatching {
                    workspaceService.readText(workspaceService.resolveExistingInside(root, hit.relativePath))
                }.getOrDefault("")
        val lineOffset = content.lineSequence().take((hit.line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
        val offset = lineOffset + (hit.column - 1).coerceAtLeast(0)
        navigateTo(hit.relativePath, offset, hit.matchLength)
    }

    fun navigateBack() {
        val target = backNavigation.removeLastOrNull() ?: return
        activeDocument?.let { forwardNavigation.addLast(it.navigationLocation()) }
        restoreNavigationLocation(target)
    }

    fun navigateForward() {
        val target = forwardNavigation.removeLastOrNull() ?: return
        activeDocument?.let { backNavigation.addLast(it.navigationLocation()) }
        restoreNavigationLocation(target)
    }

    fun navigateWikiLink(
        sourcePath: String,
        target: String,
    ) {
        workspaceIndex.resolve(sourcePath, target)?.let { navigateTo(it.relativePath) }
    }

    private fun DocumentTab.navigationLocation() = NavigationLocation(relativePath, cursor, editorBlockIndex, pdfPage)

    private fun restoreNavigationLocation(location: NavigationLocation) {
        openRelative(location.relativePath, pinned = true)
        activeDocument?.takeIf { it.relativePath == location.relativePath }?.let { tab ->
            tab.cursor =
                TextRange(
                    location.cursor.start.coerceIn(0, tab.content.length),
                    location.cursor.end.coerceIn(0, tab.content.length),
                )
            tab.editorBlockIndex = location.editorBlockIndex.coerceAtLeast(0)
            tab.pdfPage = location.pdfPage.coerceAtLeast(0)
        }
    }

    fun closeTab(
        tab: DocumentTab,
        force: Boolean = false,
    ): Boolean {
        if (tab.dirty && !force) return false
        val index = documents.indexOf(tab)
        documents.remove(tab)
        documentController.cancel(tab)
        if (activePath == tab.relativePath) {
            activePath = documents.getOrNull(index.coerceAtMost(documents.lastIndex))?.relativePath
        }
        if (secondaryPath == tab.relativePath) {
            secondaryPath = documents.firstOrNull { it.relativePath != activePath }?.relativePath
            if (secondaryPath == null) documentSplit = DocumentSplit.NONE
        }
        detachedDocuments.remove(tab.relativePath)
        persistWorkbench()
        return true
    }

    fun updateContent(
        tab: DocumentTab,
        text: String,
        selection: TextRange,
    ) {
        if (text != tab.content) {
            tab.undo.addLast(tab.content)
            if (tab.undo.size > 100) tab.undo.removeFirst()
            tab.redo.clear()
            tab.content = text
            documentController.scheduleSave(tab)
        }
        tab.cursor = selection
    }

    fun applyEditorCommand(
        command: EditorCommand,
        tab: DocumentTab? = activeDocument,
    ) {
        tab ?: return
        if (tab.kind != DocumentKind.MARKDOWN) return
        val result = EditorCommands.apply(command, tab.content, TextSelection(tab.cursor.start, tab.cursor.end))
        updateContent(tab, result.text, TextRange(result.selection.start, result.selection.end))
    }

    fun execute(
        command: WorkbenchCommand,
        tab: DocumentTab? = activeDocument,
    ) {
        when (command) {
            WorkbenchCommand.NewMarkdown -> createMarkdown()
            WorkbenchCommand.SaveDocument -> tab?.let(::save)
            WorkbenchCommand.Undo -> undo(tab)
            WorkbenchCommand.Redo -> redo(tab)
            WorkbenchCommand.RefreshWorkspace -> refreshWorkspace()
            WorkbenchCommand.NextDocument -> selectNextDocument()
            WorkbenchCommand.PreviousDocument -> selectNextDocument(reverse = true)
            WorkbenchCommand.ShowAgent -> showAgent()
            WorkbenchCommand.OpenAgentDocument -> openAgentDocument()
            is WorkbenchCommand.OpenTool -> openToolWindow(command.tool)
            is WorkbenchCommand.EditMarkdown -> applyEditorCommand(command.command, tab)
            is WorkbenchCommand.Focus -> requestFocus(command.target)
            is WorkbenchCommand.SelectDocument -> selectTab(command.relativePath)
            is WorkbenchCommand.PinDocument -> pin(command.relativePath)
            is WorkbenchCommand.SplitDocument -> splitDocument(command.relativePath, command.direction)
            is WorkbenchCommand.DetachDocument -> detachDocument(command.relativePath)
            WorkbenchCommand.ClearDocumentSplit -> updateDocumentSplit(DocumentSplit.NONE)
            is WorkbenchCommand.OpenWorkspaceEntry -> openWorkspaceEntry(command.relativePath, command.pinned)
            is WorkbenchCommand.CreateWorkspaceEntry -> createEntry(command.parentPath, command.name, command.directory)
            is WorkbenchCommand.RenameWorkspaceEntry -> renameEntry(command.relativePath, command.newName)
            is WorkbenchCommand.BatchRenameWorkspaceEntries -> renameEntries(command.renames)
            is WorkbenchCommand.DeleteWorkspaceEntries -> deleteEntries(command.relativePaths)
            is WorkbenchCommand.CopyWorkspaceEntries -> copyEntries(command.relativePaths, command.targetDirectory)
            is WorkbenchCommand.MoveWorkspaceEntries -> moveEntries(command.relativePaths, command.targetDirectory)
            is WorkbenchCommand.AddAgentContext -> addAgentContext(command.relativePath)
        }
    }

    private fun showAgent() {
        agentPanelVisible = true
        requestFocus(WorkbenchFocusTarget.AGENT)
        persistLayout()
    }

    fun requestFocus(target: WorkbenchFocusTarget) {
        when (target) {
            WorkbenchFocusTarget.ACTIVITY_RAIL -> activityRailVisible = true
            WorkbenchFocusTarget.TOOL_WINDOW -> leftPanelVisible = true
            WorkbenchFocusTarget.DOCUMENT -> Unit
            WorkbenchFocusTarget.AGENT -> agentPanelVisible = true
        }
        requestedFocusTarget = target
        focusRequestSerial++
    }

    fun markdownCompletions(
        tab: DocumentTab,
        cursor: Int = tab.cursor.start,
    ): List<MarkdownCompletion> {
        if (tab.kind != DocumentKind.MARKDOWN) return emptyList()
        return MarkdownCompletionEngine.suggest(
            source = tab.content,
            cursor = cursor,
            notePaths = workspaceIndex.notePaths(),
            tags = workspaceIndex.tags(),
            blockIds = workspaceIndex.blockIds(),
            properties = workspaceIndex.properties(),
        )
    }

    fun applyMarkdownCompletion(
        tab: DocumentTab,
        completion: MarkdownCompletion,
    ) {
        val start = completion.replaceStart.coerceIn(0, tab.content.length)
        val end = completion.replaceEnd.coerceIn(start, tab.content.length)
        val updated = tab.content.replaceRange(start, end, completion.insertText)
        val cursor = start + completion.insertText.length
        updateContent(tab, updated, TextRange(cursor))
    }

    fun undo(tab: DocumentTab? = activeDocument) {
        tab ?: return
        val previous = tab.undo.removeLastOrNull() ?: return
        tab.redo.addLast(tab.content)
        tab.content = previous
        tab.cursor = TextRange(previous.length.coerceAtMost(tab.cursor.start))
        documentController.scheduleSave(tab)
    }

    fun redo(tab: DocumentTab? = activeDocument) {
        tab ?: return
        val next = tab.redo.removeLastOrNull() ?: return
        tab.undo.addLast(tab.content)
        tab.content = next
        tab.cursor = TextRange(next.length.coerceAtMost(tab.cursor.start))
        documentController.scheduleSave(tab)
    }

    fun saveActive(): Job? = activeDocument?.let(::save)

    fun save(tab: DocumentTab): Job = documentController.save(tab)

    fun retrySave(tab: DocumentTab): Job = documentController.save(tab)

    suspend fun saveAllDirty(): Boolean = documentController.saveAllDirty()

    fun saveAs(
        tab: DocumentTab,
        target: Path,
    ): Job? = documentController.saveAs(tab, target)

    fun setEditorMode(
        mode: EditorMode,
        tab: DocumentTab? = activeDocument,
    ) {
        tab?.mode = mode
        persistWorkbench()
    }

    fun acceptExternalVersion(document: DocumentTab) {
        val external = document.externalContent ?: return
        document.content = external
        document.savedContent = external
        document.undo.clear()
        document.redo.clear()
        document.externalContent = null
        status = "External version accepted"
    }

    fun keepLocalVersion(document: DocumentTab) {
        document.externalContent ?: return
        document.externalContent = null
        save(document)
        status = "Local version kept"
    }

    fun mergeExternalVersion(document: DocumentTab) {
        val external = document.externalContent ?: return
        val local = document.content
        document.undo.addLast(local)
        document.content =
            buildString {
                append("<<<<<<< Local\n")
                append(local)
                if (!local.endsWith('\n')) append('\n')
                append("=======\n")
                append(external)
                if (!external.endsWith('\n')) append('\n')
                append(">>>>>>> External\n")
            }
        document.externalContent = null
        status = "Conflict markers inserted"
    }

    fun restoreDeletedDocument(document: DocumentTab) {
        documentController.restoreDeleted(document)
    }

    fun setPdfPosition(
        page: Int? = null,
        scrollOffset: Int? = null,
        zoom: Float? = null,
        document: DocumentTab? = activeDocument,
        persist: Boolean = true,
    ) {
        pdfController.setPosition(document, page, scrollOffset, zoom, persist)
    }

    fun setPdfFitMode(
        mode: String,
        document: DocumentTab? = activeDocument,
    ) {
        pdfController.setFitMode(document, mode)
    }

    suspend fun inspectPdf(document: DocumentTab): PdfDocumentMetadata = pdfController.inspect(document)

    suspend fun inspectPdfPage(
        document: DocumentTab,
        pageIndex: Int,
    ): PdfPageDimensions = pdfController.pageDimensions(document, pageIndex)

    suspend fun renderPdfPage(
        document: DocumentTab,
        pageIndex: Int,
        dpi: Float,
        purpose: PdfRasterPurpose,
        viewport: PdfNormalizedRect? = null,
        priority: PdfRasterPriority = PdfRasterPriority.VISIBLE,
        layer: PdfRasterLayer = PdfRasterLayer.CONTENT,
    ): PdfPageRaster = pdfController.render(document, pageIndex, dpi, purpose, viewport, priority, layer)

    suspend fun searchPdf(
        document: DocumentTab,
        query: String,
    ): List<PdfSearchMatch> = pdfController.search(document, query)

    suspend fun extractPdfPageText(
        document: DocumentTab,
        pageIndex: Int,
    ): String = pdfController.extractPageText(document, pageIndex)

    suspend fun selectPdfText(
        document: DocumentTab,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection = pdfController.selectText(document, pageIndex, start, end)

    suspend fun pdfLinks(
        document: DocumentTab,
        pageIndex: Int,
    ): List<PdfLink> = pdfController.links(document, pageIndex)

    fun addPdfAnnotation(
        document: DocumentTab,
        page: Int,
        kind: String,
        text: String,
        regions: List<PdfHighlightRegion> = emptyList(),
    ) {
        pdfController.addAnnotation(document, page, kind, text, regions)
    }

    fun removePdfAnnotation(
        document: DocumentTab,
        annotation: PdfAnnotation,
    ) {
        pdfController.removeAnnotation(document, annotation)
    }

    fun createPdfReferenceNote(
        document: DocumentTab,
        page: Int,
        quote: String = "",
    ) {
        pdfController.createReferenceNote(document, page, quote)
    }

    fun updateDocumentSplit(split: DocumentSplit) {
        layoutState.updateDocumentSplit(split, documents, activePath)
        persistWorkbench()
    }

    fun splitDocument(
        relativePath: String,
        direction: DocumentSplitDirection,
    ) {
        activePath = layoutState.splitDocument(relativePath, direction, documents, activePath)
        requestFocus(WorkbenchFocusTarget.DOCUMENT)
        persistWorkbench()
    }

    fun setSecondaryDocument(path: String) {
        layoutState.setSecondaryDocument(path, documents)
        persistWorkbench()
    }

    fun openToolWindow(tool: ToolWindow) {
        requestFocus(layoutState.openToolWindow(tool))
        persistWorkbench()
    }

    fun closeToolTab(tool: ToolWindow) {
        layoutState.closeToolTab(tool)
        persistWorkbench()
    }

    fun splitToolWindow(tool: ToolWindow? = null) {
        layoutState.splitToolWindow(tool)
        persistWorkbench()
    }

    fun closeToolSplit() {
        layoutState.closeToolSplit()
        persistWorkbench()
    }

    fun toggleToolPanelPinned() {
        layoutState.toggleToolPanelPinned()
        persistWorkbench()
    }

    fun toggleToolPanelExpanded() {
        layoutState.toggleToolPanelExpanded()
        persistWorkbench()
    }

    fun autoHideToolPanelAfterAction() {
        layoutState.autoHideToolPanelAfterAction()
        persistWorkbench()
    }

    fun detachDocument(relativePath: String) {
        layoutState.detachDocument(relativePath, documents)
        if (activePath == relativePath) {
            activePath =
                documents
                    .firstOrNull { it.relativePath != relativePath && it.relativePath !in detachedDocuments }
                    ?.relativePath
        }
        if (secondaryPath == relativePath) {
            secondaryPath = null
            documentSplit = DocumentSplit.NONE
        }
        persistWorkbench()
    }

    fun attachDocument(relativePath: String) {
        layoutState.attachDocument(relativePath)
        persistWorkbench()
    }

    fun beginToolDockDrag(position: Offset) {
        toolDockDragging = true
        updateToolDockDrag(position)
    }

    fun updateToolDockDrag(position: Offset) {
        val bounds = workbenchBounds ?: return
        val width = bounds.width
        val height = bounds.height
        toolDockPreview =
            when {
                position.x < bounds.left + width * 0.24f -> ToolPanelDock.LEFT
                position.x > bounds.right - width * 0.24f -> ToolPanelDock.RIGHT
                position.y > bounds.bottom - height * 0.28f -> ToolPanelDock.BOTTOM
                else -> ToolPanelDock.FLOAT
            }
    }

    fun finishToolDockDrag() {
        toolDockPreview?.let { target ->
            toolPanelDock = target
            leftPanelVisible = true
        }
        toolDockDragging = false
        toolDockPreview = null
        persistWorkbench()
    }

    fun cancelToolDockDrag() {
        toolDockDragging = false
        toolDockPreview = null
    }

    fun beginDocumentTabDrag(
        relativePath: String,
        position: Offset,
    ) {
        if (documents.none { it.relativePath == relativePath }) return
        documentTabDragPath = relativePath
        updateDocumentTabDrag(position)
    }

    fun updateDocumentTabDrag(position: Offset) {
        val bounds = documentDropBounds ?: return
        val primaryWidth = bounds.width * 0.68f
        val primaryHeight = bounds.height * 0.68f
        documentTabDropTarget =
            when {
                !bounds.contains(position) -> DocumentDropTarget.DETACH
                position.x >= bounds.left + primaryWidth -> DocumentDropTarget.RIGHT
                position.y >= bounds.top + primaryHeight -> DocumentDropTarget.BOTTOM
                else -> DocumentDropTarget.PRIMARY
            }
    }

    fun finishDocumentTabDrag() {
        val path = documentTabDragPath
        val target = documentTabDropTarget
        if (path != null && target != null) {
            when (target) {
                DocumentDropTarget.PRIMARY -> {
                    activePath = path
                }

                DocumentDropTarget.RIGHT, DocumentDropTarget.BOTTOM -> {
                    val primary =
                        activePath?.takeIf { it != path }
                            ?: documents.firstOrNull { it.relativePath != path }?.relativePath
                    if (primary != null) {
                        activePath = primary
                        secondaryPath = path
                        documentSplit = if (target == DocumentDropTarget.RIGHT) DocumentSplit.VERTICAL else DocumentSplit.HORIZONTAL
                    }
                }

                DocumentDropTarget.DETACH -> {
                    detachDocument(path)
                }
            }
        }
        documentTabDragPath = null
        documentTabDropTarget = null
        persistWorkbench()
    }

    fun cancelDocumentTabDrag() {
        documentTabDragPath = null
        documentTabDropTarget = null
    }

    fun beginBackgroundTask(
        id: String,
        title: String,
        detail: String,
        progress: Float? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        taskController.begin(id, title, detail, progress, onCancel)
    }

    fun updateBackgroundTask(
        id: String,
        detail: String? = null,
        progress: Float? = null,
    ) {
        taskController.update(id, detail, progress)
    }

    fun finishBackgroundTask(id: String) {
        taskController.finish(id)
    }

    fun failBackgroundTask(
        id: String,
        title: String,
        detail: String,
        onRetry: (() -> Unit)? = null,
    ) {
        taskController.fail(id, title, detail, onRetry)
    }

    fun cancelBackgroundTask(id: String) {
        taskController.cancel(id)
    }

    fun retryBackgroundTask(id: String) {
        taskController.retry(id)
    }

    fun requestRemotePdfDialog() {
        remotePdfDialogVisible = true
    }

    fun downloadRemotePdf(rawUrl: String) {
        remotePdfController.download(rawUrl)
    }

    fun applyBuiltInLayoutPreset(preset: BuiltInLayoutPreset) {
        layoutState.applyBuiltInPreset(preset)
        persistWorkbench()
    }

    fun saveCurrentLayoutPreset(name: String) {
        if (layoutState.saveCurrentPreset(name)) {
            fileOperationMessage = "Layout preset saved"
            persistWorkbench()
        }
    }

    fun applyCustomLayoutPreset(preset: PersistedLayoutPreset) {
        layoutState.applyCustomPreset(preset, documents, activePath)
        fileOperationMessage = "Applied layout preset: ${preset.name}"
        persistWorkbench()
    }

    fun deleteCustomLayoutPreset(name: String) {
        layoutState.deleteCustomPreset(name)
        persistWorkbench()
    }

    fun toggleAgentFloating() {
        layoutState.toggleAgentFloating()
        persistWorkbench()
    }

    fun closeAgentPanel() {
        layoutState.closeAgentPanel()
        persistWorkbench()
    }

    fun search() {
        val root = workspaceRoot ?: return
        openToolWindow(ToolWindow.SEARCH)
        scope.launch {
            isLoading = true
            val result = runCatching { withContext(Dispatchers.IO) { workspaceService.search(root, searchQuery) } }
            searchResults.clear()
            result.onSuccess { searchResults += it }.onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun backlinksForActive(): List<IndexedNote> =
        activeDocument
            ?.takeIf { it.kind == DocumentKind.MARKDOWN }
            ?.let { workspaceIndex.backlinks(it.relativePath) }
            .orEmpty()

    fun outgoingForActive(): List<IndexedNote> =
        activeDocument
            ?.takeIf { it.kind == DocumentKind.MARKDOWN }
            ?.let { workspaceIndex.outgoing(it.relativePath) }
            .orEmpty()

    fun sendAgentMessage() {
        agentController.sendMessage()
    }

    fun stopAgent() {
        agentController.stop()
    }

    fun requestAgentApproval(request: AgentApprovalRequest): Boolean = agentController.requestApproval(request)

    fun decideAgentApproval(decision: AgentApprovalDecision) {
        agentController.decideApproval(decision)
    }

    fun setAgentDiffDecision(
        messageIndex: Int,
        decision: String?,
    ) {
        agentController.setDiffDecision(messageIndex, decision)
    }

    fun newAgentSession() {
        agentController.newSession()
    }

    fun addAgentContext(relativePath: String) {
        agentController.addContext(relativePath)
    }

    fun addAgentSnippetContext(
        title: String,
        content: String,
    ) {
        agentController.addSnippetContext(title, content)
    }

    fun beginWorkspaceDrag(
        relativePath: String,
        displayName: String,
        position: Offset,
    ) {
        dragPayload = WorkspaceDragPayload(relativePath, displayName)
        dragPosition = position
    }

    fun updateWorkspaceDrag(position: Offset) {
        if (dragPayload != null) dragPosition = position
    }

    fun finishWorkspaceDrag() {
        val payload = dragPayload ?: return
        when {
            agentDropBounds?.contains(dragPosition) == true -> {
                addAgentContext(payload.relativePath)
            }

            documentDropBounds?.contains(dragPosition) == true -> {
                val document = activeDocument
                if (document?.kind == DocumentKind.MARKDOWN) {
                    val offset = document.cursor.start.coerceIn(0, document.content.length)
                    val link = "[[${payload.relativePath}]]"
                    val updated = document.content.replaceRange(offset, offset, link)
                    updateContent(document, updated, TextRange(offset + link.length))
                    fileOperationMessage = "Inserted link to ${payload.displayName}"
                } else {
                    errorMessage = "Open a Markdown document before dropping a file into the document area."
                }
            }
        }
        cancelWorkspaceDrag()
    }

    fun cancelWorkspaceDrag() {
        dragPayload = null
        dragPosition = Offset.Unspecified
    }

    fun removeAgentContext(relativePath: String) {
        agentController.removeContext(relativePath)
    }

    fun removeAgentSnippetContext(id: Long) {
        agentController.removeSnippetContext(id)
    }

    fun selectAgentSession(id: String) {
        agentController.selectSession(id)
    }

    fun renameAgentSession(
        id: String,
        name: String,
    ) {
        agentController.renameSession(id, name)
    }

    fun cloneAgentSession(fork: Boolean = false) {
        agentController.cloneSession(fork)
    }

    fun deleteAgentSession(id: String) {
        agentController.deleteSession(id)
    }

    fun restoreDeletedAgentSession() {
        agentController.restoreDeletedSession()
    }

    fun clearError() {
        errorMessage = null
    }

    fun resolveWorkspacePath(relativePath: String): Path? {
        val root = workspaceRoot ?: return null
        return runCatching { workspaceService.resolveExistingInside(root, relativePath) }.getOrNull()
    }

    fun persistLayout() = persistWorkbench()

    fun setDocumentWheelZoomMultiplier(
        value: Float,
        persist: Boolean = true,
    ) {
        documentWheelZoomMultiplier = normalizedDocumentWheelZoomMultiplier(value)
        if (persist) persistDocumentPreferences()
    }

    fun persistDocumentPreferences() {
        val preferences = AppPreferences(documentWheelZoomMultiplier)
        scope.launch(Dispatchers.IO) { runCatching { workspaceService.saveAppPreferences(preferences) } }
    }

    private suspend fun reloadWorkspaceNow() {
        val root = workspaceRoot ?: return
        val overrides = openMarkdownOverrides()
        val (tree, index) =
            withContext(Dispatchers.IO) {
                workspaceService.scan(root) to workspaceService.buildIndex(root, overrides)
            }
        workspaceTree = tree
        workspaceIndex = index
    }

    private fun startWorkspaceWatcher() {
        watcherJob?.cancel()
        watcherJob =
            scope.launch {
                var knownWorkspaceRevision =
                    withContext(Dispatchers.IO) {
                        workspaceRoot?.let(workspaceService::workspaceRevision) ?: 0L
                    }
                while (true) {
                    delay(1_500)
                    val root = workspaceRoot ?: continue
                    val currentRevision = withContext(Dispatchers.IO) { workspaceService.workspaceRevision(root) }
                    if (currentRevision != knownWorkspaceRevision) {
                        val overrides = openMarkdownOverrides()
                        val (tree, index) =
                            withContext(Dispatchers.IO) {
                                workspaceService.scan(root) to workspaceService.buildIndex(root, overrides)
                            }
                        workspaceTree = tree
                        workspaceIndex = index
                        knownWorkspaceRevision = currentRevision
                        status = "Workspace updated from disk"
                    }
                    documents.filter { it.kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) }.toList().forEach { tab ->
                        val relativePath = tab.relativePath
                        val snapshot =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val path = workspaceService.resolveExistingInside(root, relativePath)
                                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                        null
                                    } else {
                                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() to workspaceService.readText(path)
                                    }
                                }.getOrNull()
                            }
                        if (snapshot == null) {
                            tab.externalDeleted = true
                            return@forEach
                        }
                        tab.externalDeleted = false
                        val (modifiedAt, external) = snapshot
                        if (modifiedAt <= tab.lastKnownModified || external == tab.savedContent) {
                            if (modifiedAt > tab.lastKnownModified) tab.lastKnownModified = modifiedAt
                            return@forEach
                        }
                        tab.lastKnownModified = modifiedAt
                        if (tab.dirty) {
                            tab.externalContent = external
                        } else {
                            tab.content = external
                            tab.savedContent = external
                            status = "Reloaded ${tab.displayName} after an external change"
                        }
                    }
                }
            }
    }

    private fun openMarkdownOverrides(): Map<String, String> =
        documents
            .filter { it.kind == DocumentKind.MARKDOWN }
            .associate { it.relativePath to it.content }

    private fun persistWorkbench() {
        val root = workspaceRoot ?: return
        val snapshot =
            PersistedWorkbench(
                workspace = root.pathString,
                openDocuments = documents.map { it.relativePath },
                activeDocument = activePath,
                recentDocuments = recentDocuments.toList(),
                activeTool = activeTool.name,
                leftWidth = leftPanelWidth,
                agentWidth = agentPanelWidth,
                activityRailVisible = activityRailVisible,
                activityRailRight = activityRailRight,
                toolPanelDock = toolPanelDock.name,
                bottomPanelHeight = bottomPanelHeight,
                documentSplit = documentSplit.name,
                secondaryDocument = secondaryPath,
                activeAgentSession = activeAgentSessionId,
                toolTabs = toolTabs.map(ToolWindow::name),
                secondaryTool = secondaryTool?.name,
                toolPanelPinned = toolPanelPinned,
                toolPanelExpanded = toolPanelExpanded,
                detachedDocuments = detachedDocuments.toList(),
                agentFloating = agentFloating,
                leftPanelVisible = leftPanelVisible,
                agentPanelVisible = agentPanelVisible,
                workspaceTrusted = workspaceTrusted,
                agentReadOnlyMode = agentReadOnlyMode,
                agentOfflineMode = agentOfflineMode,
                agentAllowWrite = agentAllowWrite,
                agentAllowShell = agentAllowShell,
                customLayoutPresets = customLayoutPresets.toList(),
                editorModes = documents.associate { it.relativePath to it.mode.name },
                cursorOffsets = documents.associate { it.relativePath to it.cursor.start },
                editorBlockIndices = documents.filter { it.kind == DocumentKind.MARKDOWN }.associate { it.relativePath to it.editorBlockIndex },
                pdfPages = documents.associate { it.relativePath to it.pdfPage },
                pdfScrollOffsets =
                    documents
                        .filter { it.kind == DocumentKind.PDF }
                        .associate { it.relativePath to it.pdfScrollOffset },
                pdfHorizontalOffsets =
                    documents
                        .filter { it.kind == DocumentKind.PDF }
                        .associate { it.relativePath to it.pdfHorizontalOffset },
                pdfVerticalOffsets =
                    documents
                        .filter { it.kind == DocumentKind.PDF }
                        .associate { it.relativePath to it.pdfVerticalOffset },
                pdfZooms = documents.associate { it.relativePath to it.pdfZoom },
                pdfFitModes = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfFitMode },
                pdfViewModes = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfViewMode },
                pdfSidePanels = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfSidePanel },
                pdfRotations = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfRotation },
                pdfColorModes =
                    documents
                        .filter { it.kind == DocumentKind.PDF }
                        .associate { it.relativePath to it.pdfColorMode },
            )
        scope.launch(Dispatchers.IO) { runCatching { workspaceService.saveWorkbench(root, snapshot) } }
    }
}
