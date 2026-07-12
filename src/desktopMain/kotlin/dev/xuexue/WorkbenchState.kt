package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRecord
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.agentSendAvailability
import dev.xuexue.core.EditorCommand
import dev.xuexue.core.EditorCommands
import dev.xuexue.core.EditorMode
import dev.xuexue.core.IndexedNote
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.MarkdownCompletion
import dev.xuexue.core.MarkdownCompletionEngine
import dev.xuexue.core.TextSelection
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import dev.xuexue.core.requiresExplicitApproval
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.OpenAiCompatibleAgentClient
import dev.xuexue.platform.PersistedLayoutPreset
import dev.xuexue.platform.PersistedWorkbench
import dev.xuexue.platform.RenameBatch
import dev.xuexue.platform.RenamedEntry
import dev.xuexue.platform.SearchHit
import dev.xuexue.platform.TrashBatch
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.name
import kotlin.io.path.pathString

const val AGENT_DOCUMENT_PATH = "xuexue://agent"

class DocumentTab(
    relativePath: String,
    val kind: DocumentKind,
    content: String,
    pinned: Boolean,
    modifiedAt: Long = 0L,
) {
    var relativePath by mutableStateOf(relativePath)
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var mode by mutableStateOf(if (kind == DocumentKind.MARKDOWN) EditorMode.EDIT else EditorMode.SOURCE)
    var pinned by mutableStateOf(pinned)
    var cursor by mutableStateOf(TextRange.Zero)
    var pdfPage by mutableStateOf(0)
    var pdfZoom by mutableStateOf(1f)
    var pdfFitMode by mutableStateOf("WIDTH")
    var pdfViewMode by mutableStateOf("CONTINUOUS")
    var pdfSidePanel by mutableStateOf("THUMBNAILS")
    var pdfRotation by mutableStateOf(0f)
    var lastKnownModified by mutableStateOf(modifiedAt)
    var externalContent by mutableStateOf<String?>(null)
    var externalDeleted by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var activeNodeType by mutableStateOf("paragraph")
    var editorBlockIndex by mutableStateOf(0)
    val pdfAnnotations = mutableStateListOf<PdfAnnotation>()
    val undo = ArrayDeque<String>()
    val redo = ArrayDeque<String>()

    val displayName: String get() = if (kind == DocumentKind.AGENT) "Agent" else relativePath.substringAfterLast('/')
    val dirty: Boolean get() = content != savedContent
}

@Serializable
data class PdfAnnotation(
    val id: Long,
    val page: Int,
    val kind: String,
    val text: String,
)

class AgentSessionState(
    val id: String,
    name: String,
    messages: List<AgentMessage> = emptyList(),
) {
    var name by mutableStateOf(name)
    val messages = mutableStateListOf<AgentMessage>().apply { addAll(messages) }
}

@Serializable
data class PersistedAgentSession(
    val id: String,
    val name: String,
    val messages: List<AgentMessage>,
)

data class WorkspaceDragPayload(val relativePath: String, val displayName: String)

private sealed interface FileOperationUndo {
    data class Trash(val batch: TrashBatch) : FileOperationUndo
    data class Rename(val batch: RenameBatch) : FileOperationUndo
}

private data class NavigationLocation(
    val relativePath: String,
    val cursor: TextRange,
    val editorBlockIndex: Int,
    val pdfPage: Int,
)

data class WorkbenchTask(
    val id: String,
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val cancellable: Boolean = false,
    val failed: Boolean = false,
    val retryable: Boolean = false,
)

data class AgentContextSnippet(
    val id: Long,
    val title: String,
    val content: String,
)

enum class ToolPanelDock { LEFT, RIGHT, BOTTOM, FLOAT }
enum class DocumentSplit { NONE, VERTICAL, HORIZONTAL }
enum class DocumentDropTarget { PRIMARY, RIGHT, BOTTOM, DETACH }
enum class BuiltInLayoutPreset { READING, WRITING, AGENT }

class WorkbenchState(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService = WorkspaceService(),
    private val agentClient: AgentClient = OpenAiCompatibleAgentClient(),
) {
    var workspaceRoot by mutableStateOf<Path?>(null)
        private set
    var workspaceTree by mutableStateOf<WorkspaceEntry?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var status by mutableStateOf("Ready")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var fileOperationMessage by mutableStateOf<String?>(null)
        private set
    var renameImpactPaths by mutableStateOf<List<String>>(emptyList())
        private set
    var renameImpactLoading by mutableStateOf(false)
        private set
    private var lastFileOperationUndo: FileOperationUndo? = null
    val canUndoFileOperation: Boolean get() = lastFileOperationUndo != null
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
    val backgroundTasks = mutableStateListOf<WorkbenchTask>()
    private val backgroundTaskCancelActions = mutableMapOf<String, () -> Unit>()
    private val backgroundTaskRetryActions = mutableMapOf<String, () -> Unit>()
    var remotePdfDialogVisible by mutableStateOf(false)

    val documents = mutableStateListOf<DocumentTab>()
    val recentDocuments = mutableStateListOf<String>()
    var activePath by mutableStateOf<String?>(null)
        private set
    val activeDocument: DocumentTab? get() = documents.firstOrNull { it.relativePath == activePath }
    private val backNavigation = ArrayDeque<NavigationLocation>()
    private val forwardNavigation = ArrayDeque<NavigationLocation>()
    val canNavigateBack: Boolean get() = backNavigation.isNotEmpty()
    val canNavigateForward: Boolean get() = forwardNavigation.isNotEmpty()

    var activeTool by mutableStateOf(ToolWindow.FILES)
    val toolTabs = mutableStateListOf(ToolWindow.FILES)
    var secondaryTool by mutableStateOf<ToolWindow?>(null)
        private set
    var leftPanelVisible by mutableStateOf(true)
    var agentPanelVisible by mutableStateOf(true)
    var leftPanelWidth by mutableStateOf(268f)
    var agentPanelWidth by mutableStateOf(360f)
    var activityRailVisible by mutableStateOf(true)
    var activityRailRight by mutableStateOf(false)
    var toolPanelDock by mutableStateOf(ToolPanelDock.LEFT)
    var bottomPanelHeight by mutableStateOf(230f)
    var documentSplit by mutableStateOf(DocumentSplit.NONE)
    var secondaryPath by mutableStateOf<String?>(null)
    var toolPanelPinned by mutableStateOf(true)
        private set
    var toolPanelExpanded by mutableStateOf(false)
        private set
    val detachedDocuments = mutableStateListOf<String>()
    var agentFloating by mutableStateOf(false)
        private set
    val customLayoutPresets = mutableStateListOf<PersistedLayoutPreset>()
    var requestedFocusTarget by mutableStateOf(WorkbenchFocusTarget.DOCUMENT)
        private set
    var focusRequestSerial by mutableStateOf(0)
        private set

    var searchQuery by mutableStateOf("")
    val searchResults = mutableStateListOf<SearchHit>()
    var workspaceIndex by mutableStateOf(WorkspaceIndex(emptyList()))
        private set

    val agentSessions = mutableStateListOf(AgentSessionState("default", "默认会话"))
    var activeAgentSessionId by mutableStateOf("default")
        private set
    val activeAgentSession: AgentSessionState get() = agentSessions.firstOrNull { it.id == activeAgentSessionId } ?: agentSessions.first()
    val agentMessages get() = activeAgentSession.messages
    private var lastDeletedAgentSession: AgentSessionState? = null
    val canRestoreDeletedAgentSession: Boolean get() = lastDeletedAgentSession != null
    var agentInput by mutableStateOf("")
    var agentEndpoint by mutableStateOf("https://api.openai.com/v1/chat/completions")
    var agentModel by mutableStateOf("")
    var agentApiKey by mutableStateOf(System.getenv("OPENAI_API_KEY").orEmpty())
    var workspaceTrusted by mutableStateOf(true)
    var agentReadOnlyMode by mutableStateOf(false)
    var agentOfflineMode by mutableStateOf(false)
    var agentAllowWrite by mutableStateOf(true)
    var agentAllowShell by mutableStateOf(true)
    var pendingAgentApproval by mutableStateOf<AgentApprovalRequest?>(null)
        private set
    val agentApprovalHistory = mutableStateListOf<AgentApprovalRecord>()
    var agentRunning by mutableStateOf(false)
        private set
    var includeActiveDocument by mutableStateOf(true)
    val agentSendAvailability: AgentSendAvailability
        get() = agentSendAvailability(
            offline = agentOfflineMode,
            model = agentModel,
            apiKey = agentApiKey,
            awaitingApproval = pendingAgentApproval != null,
        )
    val agentContextPaths = mutableStateListOf<String>()
    val agentContextSnippets = mutableStateListOf<AgentContextSnippet>()
    private var agentJob: Job? = null
    private var agentRunId = 0
    private val saveJobs = mutableMapOf<DocumentTab, Job>()
    private var watcherJob: Job? = null
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun restoreLastWorkspace() {
        val root = withContext(Dispatchers.IO) { workspaceService.loadLastWorkspace() } ?: return
        openWorkspace(root, restore = true)
    }

    suspend fun openWorkspace(root: Path, restore: Boolean = false) {
        if (!Files.isDirectory(root)) {
            errorMessage = "The selected workspace is not a readable directory."
            return
        }
        isLoading = true
        errorMessage = null
        status = "Opening workspace..."
        runCatching {
            val persisted = withContext(Dispatchers.IO) {
                val tree = workspaceService.scan(root)
                val state = if (restore) workspaceService.loadWorkbench(root) else PersistedWorkbench(workspace = root.pathString)
                Triple(tree, workspaceService.buildIndex(root), state)
            }
            workspaceRoot = root.toAbsolutePath().normalize()
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
            toolTabs += persisted.third.toolTabs.mapNotNull { name -> runCatching { ToolWindow.valueOf(name) }.getOrNull() }.filter { it != ToolWindow.AGENT }
            if (toolTabs.isEmpty()) toolTabs += activeTool.takeIf { it != ToolWindow.AGENT } ?: ToolWindow.FILES
            if (activeTool != ToolWindow.AGENT && !toolTabs.contains(activeTool)) toolTabs += activeTool
            secondaryTool = persisted.third.secondaryTool
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
                        pdfZoom = persisted.third.pdfZooms[relative]?.coerceIn(0.4f, 4f) ?: 1f
                        pdfFitMode = persisted.third.pdfFitModes[relative] ?: pdfFitMode
                        pdfViewMode = persisted.third.pdfViewModes[relative] ?: pdfViewMode
                        pdfSidePanel = persisted.third.pdfSidePanels[relative] ?: pdfSidePanel
                        pdfRotation = persisted.third.pdfRotations[relative] ?: pdfRotation
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
            loadAgentTranscript()
            persisted.third.activeAgentSession?.takeIf { id -> agentSessions.any { it.id == id } }?.let { activeAgentSessionId = it }
            status = root.fileName?.toString() ?: root.pathString
            startWorkspaceWatcher()
            persistWorkbench()
        }.onFailure { throwable ->
            errorMessage = throwable.message ?: "Could not open the workspace."
            status = "Open failed"
        }
        isLoading = false
    }

    fun closeWorkspace() {
        watcherJob?.cancel()
        saveJobs.values.forEach(Job::cancel)
        saveJobs.clear()
        backgroundTaskCancelActions.values.toList().forEach { it() }
        backgroundTaskCancelActions.clear()
        backgroundTaskRetryActions.clear()
        backgroundTasks.clear()
        remotePdfDialogVisible = false
        documents.filter { it.dirty }.forEach(::saveDocumentNow)
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
        scope.launch {
            isLoading = true
            runCatching {
                val (tree, index) = withContext(Dispatchers.IO) {
                    workspaceService.scan(root) to workspaceService.buildIndex(root, openMarkdownOverrides())
                }
                workspaceTree = tree
                workspaceIndex = index
                status = "Workspace refreshed"
            }.onFailure { errorMessage = it.message }
            isLoading = false
        }
    }

    fun createMarkdown() {
        val root = workspaceRoot ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.createMarkdown(root) } }
                .onSuccess { path ->
                    refreshWorkspace()
                    openRelative(root.relativize(path).pathString.replace('\\', '/'), pinned = true)
                }
                .onFailure { errorMessage = it.message }
        }
    }

    fun createEntry(parentRelative: String, name: String, directory: Boolean) {
        val root = workspaceRoot ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.createEntry(root, parentRelative, name, directory) } }
                .onSuccess { path ->
                    reloadWorkspaceNow()
                    fileOperationMessage = if (directory) "Folder created" else "File created"
                    if (!directory) openRelative(root.relativize(path).pathString.replace('\\', '/'), pinned = true)
                }
                .onFailure { errorMessage = it.message ?: "Could not create the entry." }
        }
    }

    fun inspectRenameImpact(relativePath: String) {
        val root = workspaceRoot ?: return
        renameImpactLoading = true
        renameImpactPaths = emptyList()
        scope.launch {
            renameImpactPaths = runCatching {
                withContext(Dispatchers.IO) { workspaceService.markdownReferenceImpacts(root, relativePath) }
            }.getOrDefault(emptyList())
            renameImpactLoading = false
        }
    }

    fun renameEntry(relativePath: String, newName: String) {
        renameEntries(mapOf(relativePath to newName))
    }

    fun renameEntries(renames: Map<String, String>) {
        val root = workspaceRoot ?: return
        val sources = topLevelSelection(renames.keys.toList())
        val requested = renames.filterKeys { it in sources }
        if (requested.isEmpty()) return
        scope.launch {
            runCatching {
                val impacts = withContext(Dispatchers.IO) {
                    requested.keys.flatMap { workspaceService.markdownReferenceImpacts(root, it) }.distinct()
                }
                require(documents.none { tab -> tab.dirty && impacts.contains(tab.relativePath) }) {
                    "Save affected open notes before renaming these entries."
                }
                withContext(Dispatchers.IO) { workspaceService.renameEntries(root, requested) }
            }.onSuccess { batch ->
                applyPathRenames(batch.entries, batch.updatedContents)
                lastFileOperationUndo = FileOperationUndo.Rename(batch)
                reloadWorkspaceNow()
                fileOperationMessage = if (batch.affectedMarkdown.isEmpty()) {
                    "Renamed ${batch.entries.size} item(s)"
                } else {
                    "Renamed ${batch.entries.size} item(s) and updated ${batch.affectedMarkdown.size} linked note(s)"
                }
                persistWorkbench()
            }.onFailure { errorMessage = it.message ?: "Batch rename failed." }
        }
    }

    fun copyEntries(relativePaths: List<String>, targetDirectory: String) {
        val root = workspaceRoot ?: return
        val sources = topLevelSelection(relativePaths)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.copyEntries(root, sources, targetDirectory) } }
                .onSuccess { reloadWorkspaceNow(); fileOperationMessage = "Copied ${it.size} item(s)" }
                .onFailure { errorMessage = it.message ?: "Copy failed." }
        }
    }

    fun moveEntries(relativePaths: List<String>, targetDirectory: String) {
        val root = workspaceRoot ?: return
        val sources = topLevelSelection(relativePaths)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.moveEntries(root, sources, targetDirectory) } }
                .onSuccess { targets ->
                    sources.zip(targets).forEach { (oldPrefix, newPrefix) ->
                        documents.forEach { tab ->
                            if (tab.relativePath == oldPrefix || tab.relativePath.startsWith("$oldPrefix/")) tab.relativePath = newPrefix + tab.relativePath.removePrefix(oldPrefix)
                        }
                        if (activePath == oldPrefix || activePath?.startsWith("$oldPrefix/") == true) activePath = newPrefix + activePath!!.removePrefix(oldPrefix)
                        if (secondaryPath == oldPrefix || secondaryPath?.startsWith("$oldPrefix/") == true) secondaryPath = newPrefix + secondaryPath!!.removePrefix(oldPrefix)
                        detachedDocuments.indices.forEach { index ->
                            val path = detachedDocuments[index]
                            if (path == oldPrefix || path.startsWith("$oldPrefix/")) detachedDocuments[index] = newPrefix + path.removePrefix(oldPrefix)
                        }
                    }
                    reloadWorkspaceNow()
                    fileOperationMessage = "Moved ${targets.size} item(s)"
                    persistWorkbench()
                }
                .onFailure { errorMessage = it.message ?: "Move failed." }
        }
    }

    fun deleteEntries(relativePaths: List<String>) {
        val root = workspaceRoot ?: return
        val sources = topLevelSelection(relativePaths)
        if (sources.isEmpty()) return
        if (documents.any { tab -> tab.dirty && sources.any { source -> tab.relativePath == source || tab.relativePath.startsWith("$source/") } }) {
            errorMessage = "Save or close modified documents before deleting them."
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.trashEntries(root, sources) } }
                .onSuccess { batch ->
                    lastFileOperationUndo = FileOperationUndo.Trash(batch)
                    documents.filter { tab -> sources.any { source -> tab.relativePath == source || tab.relativePath.startsWith("$source/") } }
                        .toList().forEach { closeTab(it, force = true) }
                    reloadWorkspaceNow()
                    fileOperationMessage = "Moved ${batch.entries.size} item(s) to workspace trash"
                }
                .onFailure { errorMessage = it.message ?: "Delete failed." }
        }
    }

    fun undoLastFileOperation() {
        val undo = lastFileOperationUndo ?: return
        val root = workspaceRoot ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (undo) {
                        is FileOperationUndo.Trash -> workspaceService.restoreTrash(undo.batch)
                        is FileOperationUndo.Rename -> workspaceService.undoRename(root, undo.batch)
                    }
                }
            }.onSuccess {
                if (undo is FileOperationUndo.Rename) {
                    val reverse = undo.batch.entries.map { RenamedEntry(it.newRelativePath, it.oldRelativePath) }
                    applyPathRenames(reverse, emptyMap())
                    documents.forEach { tab ->
                        undo.batch.originalContents[tab.relativePath]?.let { original ->
                            if (!tab.dirty) { tab.content = original; tab.savedContent = original }
                        }
                    }
                    persistWorkbench()
                }
                lastFileOperationUndo = null
                reloadWorkspaceNow()
                fileOperationMessage = if (undo is FileOperationUndo.Trash) "Delete undone" else "Rename undone"
            }.onFailure { errorMessage = it.message ?: "Could not undo the file operation." }
        }
    }

    private fun applyPathRenames(entries: List<RenamedEntry>, updatedContents: Map<String, String>) {
        fun remap(path: String): String {
            val match = entries.firstOrNull { entry ->
                path == entry.oldRelativePath || path.startsWith("${entry.oldRelativePath}/")
            } ?: return path
            return match.newRelativePath + path.removePrefix(match.oldRelativePath)
        }
        documents.forEach { tab ->
            val oldPath = tab.relativePath
            updatedContents[oldPath]?.let { updated ->
                if (!tab.dirty) { tab.content = updated; tab.savedContent = updated }
            }
            tab.relativePath = remap(oldPath)
        }
        activePath = activePath?.let(::remap)
        secondaryPath = secondaryPath?.let(::remap)
        detachedDocuments.indices.forEach { index -> detachedDocuments[index] = remap(detachedDocuments[index]) }
    }

    fun clearFileOperationMessage() {
        fileOperationMessage = null
    }

    fun notifyFileOperation(message: String) {
        fileOperationMessage = message
    }

    fun openRelative(relativePath: String, pinned: Boolean = false, persist: Boolean = true) {
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
            if (persist) persistWorkbench()
            return
        }
        val root = workspaceRoot ?: return
        val path = root.resolve(normalized).normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            errorMessage = "File is no longer available: $normalized"
            return
        }
        runCatching {
            val kind = workspaceService.kindOf(path)
            val content = if (kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) {
                workspaceService.readText(path)
            } else {
                ""
            }
            if (!pinned) documents.firstOrNull { !it.pinned && !it.dirty }?.let { documents.remove(it) }
            val modifiedAt = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            documents += DocumentTab(normalized, kind, content, pinned, modifiedAt)
            documents.lastOrNull()?.takeIf { it.kind == DocumentKind.PDF }?.let(::loadPdfAnnotations)
            activePath = normalized
            recentDocuments.remove(normalized)
            recentDocuments.add(0, normalized)
            while (recentDocuments.size > 12) recentDocuments.removeLast()
            status = normalized
            if (persist) persistWorkbench()
        }.onFailure { errorMessage = it.message ?: "Could not open $normalized" }
    }

    fun pin(relativePath: String) {
        documents.firstOrNull { it.relativePath == relativePath }?.pinned = true
        persistWorkbench()
    }

    fun openAgentDocument(persist: Boolean = true) {
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
        status = "Agent"
        if (persist) persistWorkbench()
    }

    fun selectTab(relativePath: String) {
        activePath = relativePath
        persistWorkbench()
    }

    fun selectNextDocument(reverse: Boolean = false) {
        if (documents.size < 2) return
        val current = documents.indexOfFirst { it.relativePath == activePath }.coerceAtLeast(0)
        val next = if (reverse) (current - 1 + documents.size) % documents.size else (current + 1) % documents.size
        activePath = documents[next].relativePath
        persistWorkbench()
    }

    fun navigateTo(relativePath: String, cursorOffset: Int? = null, selectionLength: Int = 0) {
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
        val content = documents.firstOrNull { it.relativePath == hit.relativePath }?.content
            ?: runCatching { workspaceService.readText(root.resolve(hit.relativePath)) }.getOrDefault("")
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

    fun navigateWikiLink(sourcePath: String, target: String) {
        workspaceIndex.resolve(sourcePath, target)?.let { navigateTo(it.relativePath) }
    }

    private fun DocumentTab.navigationLocation() = NavigationLocation(relativePath, cursor, editorBlockIndex, pdfPage)

    private fun restoreNavigationLocation(location: NavigationLocation) {
        openRelative(location.relativePath, pinned = true)
        activeDocument?.takeIf { it.relativePath == location.relativePath }?.let { tab ->
            tab.cursor = TextRange(
                location.cursor.start.coerceIn(0, tab.content.length),
                location.cursor.end.coerceIn(0, tab.content.length),
            )
            tab.editorBlockIndex = location.editorBlockIndex.coerceAtLeast(0)
            tab.pdfPage = location.pdfPage.coerceAtLeast(0)
        }
    }

    fun closeTab(tab: DocumentTab, force: Boolean = false): Boolean {
        if (tab.dirty && !force) return false
        val index = documents.indexOf(tab)
        documents.remove(tab)
        saveJobs.remove(tab)?.cancel()
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

    fun updateContent(tab: DocumentTab, text: String, selection: TextRange) {
        if (text != tab.content) {
            tab.undo.addLast(tab.content)
            if (tab.undo.size > 100) tab.undo.removeFirst()
            tab.redo.clear()
            tab.content = text
            scheduleSave(tab)
        }
        tab.cursor = selection
    }

    fun applyEditorCommand(command: EditorCommand, tab: DocumentTab? = activeDocument) {
        tab ?: return
        if (tab.kind != DocumentKind.MARKDOWN) return
        val result = EditorCommands.apply(command, tab.content, TextSelection(tab.cursor.start, tab.cursor.end))
        updateContent(tab, result.text, TextRange(result.selection.start, result.selection.end))
    }

    fun execute(command: WorkbenchCommand, tab: DocumentTab? = activeDocument) {
        when (command) {
            WorkbenchCommand.NewMarkdown -> createMarkdown()
            WorkbenchCommand.SaveDocument -> tab?.let(::save)
            WorkbenchCommand.Undo -> undo(tab)
            WorkbenchCommand.Redo -> redo(tab)
            WorkbenchCommand.RefreshWorkspace -> refreshWorkspace()
            WorkbenchCommand.NextDocument -> selectNextDocument()
            WorkbenchCommand.PreviousDocument -> selectNextDocument(reverse = true)
            WorkbenchCommand.ShowAgent -> {
                agentPanelVisible = true
                requestFocus(WorkbenchFocusTarget.AGENT)
                persistLayout()
            }
            WorkbenchCommand.OpenAgentDocument -> openAgentDocument()
            is WorkbenchCommand.OpenTool -> openToolWindow(command.tool)
            is WorkbenchCommand.EditMarkdown -> applyEditorCommand(command.command, tab)
            is WorkbenchCommand.Focus -> requestFocus(command.target)
            is WorkbenchCommand.SelectDocument -> selectTab(command.relativePath)
            is WorkbenchCommand.PinDocument -> pin(command.relativePath)
            is WorkbenchCommand.SplitDocument -> splitDocument(command.relativePath, command.direction)
            is WorkbenchCommand.DetachDocument -> detachDocument(command.relativePath)
            WorkbenchCommand.ClearDocumentSplit -> updateDocumentSplit(DocumentSplit.NONE)
            is WorkbenchCommand.OpenWorkspaceEntry -> openRelative(command.relativePath, command.pinned)
            is WorkbenchCommand.CreateWorkspaceEntry -> createEntry(command.parentPath, command.name, command.directory)
            is WorkbenchCommand.RenameWorkspaceEntry -> renameEntry(command.relativePath, command.newName)
            is WorkbenchCommand.BatchRenameWorkspaceEntries -> renameEntries(command.renames)
            is WorkbenchCommand.DeleteWorkspaceEntries -> deleteEntries(command.relativePaths)
            is WorkbenchCommand.CopyWorkspaceEntries -> copyEntries(command.relativePaths, command.targetDirectory)
            is WorkbenchCommand.MoveWorkspaceEntries -> moveEntries(command.relativePaths, command.targetDirectory)
            is WorkbenchCommand.AddAgentContext -> addAgentContext(command.relativePath)
        }
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

    fun markdownCompletions(tab: DocumentTab, cursor: Int = tab.cursor.start): List<MarkdownCompletion> {
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

    fun applyMarkdownCompletion(tab: DocumentTab, completion: MarkdownCompletion) {
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
        scheduleSave(tab)
    }

    fun redo(tab: DocumentTab? = activeDocument) {
        tab ?: return
        val next = tab.redo.removeLastOrNull() ?: return
        tab.undo.addLast(tab.content)
        tab.content = next
        tab.cursor = TextRange(next.length.coerceAtMost(tab.cursor.start))
        scheduleSave(tab)
    }

    fun saveActive() = activeDocument?.let(::saveDocumentNow)
    fun save(tab: DocumentTab) = saveDocumentNow(tab)
    fun retrySave(tab: DocumentTab) = saveDocumentNow(tab)

    fun saveAs(tab: DocumentTab, target: Path) {
        val root = workspaceRoot ?: return
        if (tab.kind !in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) return
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            tab.saveError = "Choose a location inside the current workspace."
            return
        }
        val newRelative = normalizedRoot.relativize(normalizedTarget).pathString.replace('\\', '/')
        if (documents.any { it !== tab && it.relativePath == newRelative }) {
            tab.saveError = "Another open document already uses this path."
            return
        }
        val oldRelative = tab.relativePath
        runCatching {
            workspaceService.atomicWrite(normalizedTarget, tab.content)
            tab.relativePath = newRelative
            tab.savedContent = tab.content
            tab.lastKnownModified = Files.getLastModifiedTime(normalizedTarget).toMillis()
            tab.externalContent = null
            tab.externalDeleted = false
            tab.saveError = null
            if (activePath == oldRelative) activePath = newRelative
            if (secondaryPath == oldRelative) secondaryPath = newRelative
            detachedDocuments.indices.forEach { index -> if (detachedDocuments[index] == oldRelative) detachedDocuments[index] = newRelative }
            recentDocuments.remove(oldRelative)
            recentDocuments.remove(newRelative)
            recentDocuments.add(0, newRelative)
            workspaceIndex = workspaceService.buildIndex(root, openMarkdownOverrides())
            status = "Saved as $newRelative"
            refreshWorkspace()
            persistWorkbench()
        }.onFailure { error ->
            tab.saveError = error.message ?: "Save as failed."
            status = "Save failed"
        }
    }

    private fun scheduleSave(tab: DocumentTab) {
        saveJobs.remove(tab)?.cancel()
        status = "Saving..."
        saveJobs[tab] = scope.launch {
            delay(700)
            saveDocumentNow(tab)
            saveJobs.remove(tab)
        }
    }

    private fun saveDocumentNow(tab: DocumentTab) {
        val root = workspaceRoot ?: return
        if (tab.kind !in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) || !tab.dirty) return
        runCatching {
            workspaceService.atomicWrite(root.resolve(tab.relativePath), tab.content)
            tab.savedContent = tab.content
            tab.lastKnownModified = Files.getLastModifiedTime(root.resolve(tab.relativePath)).toMillis()
            tab.externalContent = null
            tab.externalDeleted = false
            tab.saveError = null
            workspaceIndex = workspaceService.buildIndex(root, openMarkdownOverrides())
            status = "Saved"
            persistWorkbench()
        }.onFailure {
            status = "Save failed"
            tab.saveError = it.message ?: "Could not save this document."
        }
    }

    fun setEditorMode(mode: EditorMode, tab: DocumentTab? = activeDocument) {
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
        saveDocumentNow(document)
        status = "Local version kept"
    }

    fun mergeExternalVersion(document: DocumentTab) {
        val external = document.externalContent ?: return
        val local = document.content
        document.undo.addLast(local)
        document.content = buildString {
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
        document.externalDeleted = false
        val root = workspaceRoot ?: return
        runCatching {
            workspaceService.atomicWrite(root.resolve(document.relativePath), document.content)
            document.savedContent = document.content
            document.lastKnownModified = Files.getLastModifiedTime(root.resolve(document.relativePath)).toMillis()
            status = "Deleted file restored"
            refreshWorkspace()
        }.onFailure { errorMessage = it.message ?: "Could not restore the file." }
    }

    fun setPdfPosition(page: Int? = null, zoom: Float? = null, document: DocumentTab? = activeDocument) {
        document?.takeIf { it.kind == DocumentKind.PDF }?.let { tab ->
            if (page != null) tab.pdfPage = page.coerceAtLeast(0)
            if (zoom != null) {
                tab.pdfZoom = zoom.coerceIn(0.4f, 4f)
                tab.pdfFitMode = "MANUAL"
            }
            persistWorkbench()
        }
    }

    fun setPdfFitMode(mode: String, document: DocumentTab? = activeDocument) {
        if (mode !in setOf("MANUAL", "WIDTH", "HEIGHT")) return
        document?.takeIf { it.kind == DocumentKind.PDF }?.let { tab ->
            tab.pdfFitMode = mode
            if (mode == "HEIGHT") tab.pdfViewMode = "SINGLE"
            persistWorkbench()
        }
    }

    fun addPdfAnnotation(document: DocumentTab, page: Int, kind: String, text: String) {
        if (document.kind != DocumentKind.PDF) return
        document.pdfAnnotations += PdfAnnotation(System.currentTimeMillis(), page, kind, text.ifBlank { "$kind on page ${page + 1}" })
        persistPdfAnnotations(document)
        fileOperationMessage = "PDF annotation added"
    }

    fun removePdfAnnotation(document: DocumentTab, annotation: PdfAnnotation) {
        document.pdfAnnotations.remove(annotation)
        persistPdfAnnotations(document)
    }

    fun createPdfReferenceNote(document: DocumentTab, page: Int, quote: String = "") {
        val root = workspaceRoot ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val path = workspaceService.createMarkdown(root)
                    val reference = "[[${document.relativePath}#page=${page + 1}]]"
                    val body = buildString {
                        append("# ${document.displayName} · Page ${page + 1}\n\n")
                        if (quote.isNotBlank()) append("> ${quote.trim().replace("\n", "\n> ")}\n\n")
                        append("Source: $reference\n")
                    }
                    workspaceService.atomicWrite(path, body)
                    path
                }
            }.onSuccess { path ->
                reloadWorkspaceNow()
                openRelative(root.relativize(path).pathString.replace('\\', '/'), pinned = true)
                fileOperationMessage = "PDF reference note created"
            }.onFailure { errorMessage = it.message ?: "Could not create the reference note." }
        }
    }

    fun updateDocumentSplit(split: DocumentSplit) {
        documentSplit = split
        secondaryPath = if (split == DocumentSplit.NONE) null
        else secondaryPath?.takeIf { path -> documents.any { it.relativePath == path } }
            ?: documents.firstOrNull { it.relativePath != activePath }?.relativePath
            ?: activePath
        persistWorkbench()
    }

    fun splitDocument(relativePath: String, direction: DocumentSplitDirection) {
        if (documents.none { it.relativePath == relativePath }) return
        if (activePath == relativePath) {
            documents.firstOrNull { it.relativePath != relativePath }?.let { activePath = it.relativePath }
        }
        secondaryPath = relativePath
        documentSplit = when (direction) {
            DocumentSplitDirection.RIGHT -> DocumentSplit.VERTICAL
            DocumentSplitDirection.BOTTOM -> DocumentSplit.HORIZONTAL
        }
        requestFocus(WorkbenchFocusTarget.DOCUMENT)
        persistWorkbench()
    }

    fun setSecondaryDocument(path: String) {
        if (documents.any { it.relativePath == path }) secondaryPath = path
        persistWorkbench()
    }

    fun openToolWindow(tool: ToolWindow) {
        if (tool == ToolWindow.AGENT) {
            agentPanelVisible = true
            requestFocus(WorkbenchFocusTarget.AGENT)
            return
        }
        if (!toolTabs.contains(tool)) toolTabs += tool
        if (secondaryTool == tool) secondaryTool = activeTool.takeIf { it != tool }
        activeTool = tool
        leftPanelVisible = true
        requestFocus(WorkbenchFocusTarget.TOOL_WINDOW)
        persistWorkbench()
    }

    fun closeToolTab(tool: ToolWindow) {
        toolTabs.remove(tool)
        if (secondaryTool == tool) secondaryTool = null
        if (activeTool == tool) activeTool = toolTabs.lastOrNull() ?: ToolWindow.FILES
        if (toolTabs.isEmpty()) leftPanelVisible = false
        persistWorkbench()
    }

    fun splitToolWindow(tool: ToolWindow? = null) {
        val target = tool?.takeIf { it != activeTool && it != ToolWindow.AGENT }
            ?: toolTabs.firstOrNull { it != activeTool && it != ToolWindow.AGENT }
            ?: ToolWindow.entries.firstOrNull { it != activeTool && it != ToolWindow.AGENT }
            ?: return
        if (!toolTabs.contains(target)) toolTabs += target
        secondaryTool = target
        leftPanelVisible = true
        persistWorkbench()
    }

    fun closeToolSplit() {
        secondaryTool = null
        persistWorkbench()
    }

    fun toggleToolPanelPinned() {
        toolPanelPinned = !toolPanelPinned
        persistWorkbench()
    }

    fun toggleToolPanelExpanded() {
        toolPanelExpanded = !toolPanelExpanded
        persistWorkbench()
    }

    fun autoHideToolPanelAfterAction() {
        if (!toolPanelPinned && toolPanelDock != ToolPanelDock.FLOAT) leftPanelVisible = false
        persistWorkbench()
    }

    fun detachDocument(relativePath: String) {
        if (documents.any { it.relativePath == relativePath } && !detachedDocuments.contains(relativePath)) detachedDocuments += relativePath
        persistWorkbench()
    }

    fun attachDocument(relativePath: String) {
        detachedDocuments.remove(relativePath)
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
        toolDockPreview = when {
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

    fun beginDocumentTabDrag(relativePath: String, position: Offset) {
        if (documents.none { it.relativePath == relativePath }) return
        documentTabDragPath = relativePath
        updateDocumentTabDrag(position)
    }

    fun updateDocumentTabDrag(position: Offset) {
        val bounds = documentDropBounds ?: return
        documentTabDropTarget = when {
            !bounds.contains(position) -> DocumentDropTarget.DETACH
            position.x > bounds.right - bounds.width * 0.32f -> DocumentDropTarget.RIGHT
            position.y > bounds.bottom - bounds.height * 0.32f -> DocumentDropTarget.BOTTOM
            else -> DocumentDropTarget.PRIMARY
        }
    }

    fun finishDocumentTabDrag() {
        val path = documentTabDragPath
        val target = documentTabDropTarget
        if (path != null && target != null) {
            when (target) {
                DocumentDropTarget.PRIMARY -> activePath = path
                DocumentDropTarget.RIGHT, DocumentDropTarget.BOTTOM -> {
                    val primary = activePath?.takeIf { it != path }
                        ?: documents.firstOrNull { it.relativePath != path }?.relativePath
                    if (primary != null) {
                        activePath = primary
                        secondaryPath = path
                        documentSplit = if (target == DocumentDropTarget.RIGHT) DocumentSplit.VERTICAL else DocumentSplit.HORIZONTAL
                    }
                }
                DocumentDropTarget.DETACH -> detachDocument(path)
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

    fun beginBackgroundTask(id: String, title: String, detail: String, progress: Float? = null, onCancel: (() -> Unit)? = null) {
        backgroundTasks.removeAll { it.id == id }
        backgroundTasks += WorkbenchTask(id, title, detail, progress, cancellable = onCancel != null)
        if (onCancel != null) backgroundTaskCancelActions[id] = onCancel else backgroundTaskCancelActions.remove(id)
        backgroundTaskRetryActions.remove(id)
    }

    fun updateBackgroundTask(id: String, detail: String? = null, progress: Float? = null) {
        val index = backgroundTasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = backgroundTasks[index]
        backgroundTasks[index] = current.copy(detail = detail ?: current.detail, progress = progress ?: current.progress)
    }

    fun finishBackgroundTask(id: String) {
        backgroundTasks.removeAll { it.id == id }
        backgroundTaskCancelActions.remove(id)
        backgroundTaskRetryActions.remove(id)
    }

    fun failBackgroundTask(id: String, title: String, detail: String, onRetry: (() -> Unit)? = null) {
        backgroundTasks.removeAll { it.id == id }
        backgroundTasks += WorkbenchTask(id, title, detail, failed = true, retryable = onRetry != null)
        backgroundTaskCancelActions.remove(id)
        if (onRetry != null) backgroundTaskRetryActions[id] = onRetry else backgroundTaskRetryActions.remove(id)
    }

    fun cancelBackgroundTask(id: String) {
        backgroundTaskCancelActions.remove(id)?.invoke()
        finishBackgroundTask(id)
        status = "Task cancelled"
    }

    fun retryBackgroundTask(id: String) {
        val retry = backgroundTaskRetryActions.remove(id) ?: return
        backgroundTasks.removeAll { it.id == id }
        retry()
    }

    fun requestRemotePdfDialog() {
        remotePdfDialogVisible = true
    }

    fun downloadRemotePdf(rawUrl: String) {
        val root = workspaceRoot ?: return
        val url = rawUrl.trim()
        if (agentOfflineMode) {
            errorMessage = "Offline mode is enabled. Disable it before downloading a remote PDF."
            return
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https")) {
            errorMessage = "Enter a valid HTTP or HTTPS PDF URL."
            return
        }
        remotePdfDialogVisible = false
        val taskId = "remote-pdf:${url.hashCode()}"
        backgroundTaskCancelActions.remove(taskId)?.invoke()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val relative = withContext(Dispatchers.IO) {
                    val requestedName = uri.path.substringAfterLast('/').ifBlank { "remote-${System.currentTimeMillis()}.pdf" }
                    val baseName = requestedName.substringBefore('?').replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                    val downloads = root.resolve("Downloads")
                    Files.createDirectories(downloads)
                    var target = downloads.resolve(baseName)
                    var suffix = 2
                    while (Files.exists(target)) {
                        target = downloads.resolve("${baseName.removeSuffix(".pdf")}-$suffix.pdf")
                        suffix++
                    }
                    val partial = target.resolveSibling(target.fileName.toString() + ".part")
                    val existingBytes = if (Files.isRegularFile(partial)) Files.size(partial) else 0L
                    val requestBuilder = HttpRequest.newBuilder(uri).GET()
                    if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")
                    val request = requestBuilder.build()
                    val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                        .send(request, HttpResponse.BodyHandlers.ofInputStream())
                    require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
                    val resumed = existingBytes > 0 && response.statusCode() == 206
                    val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
                    val rangeTotal = response.headers().firstValue("content-range").orElse("").substringAfterLast('/').toLongOrNull()
                    val total = rangeTotal ?: if (contentLength > 0) contentLength + if (resumed) existingBytes else 0L else -1L
                    var downloaded = if (resumed) existingBytes else 0L
                    var lastUpdate = 0L
                    response.body().use { input ->
                        val writeOptions = if (resumed) {
                            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        } else {
                            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                        }
                        Files.newOutputStream(partial, *writeOptions).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= 150) {
                                    val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                                    withContext(Dispatchers.Main) {
                                        updateBackgroundTask(taskId, formatDownloadProgress(downloaded, total), progress)
                                    }
                                    lastUpdate = now
                                }
                            }
                        }
                    }
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
                    root.relativize(target).pathString.replace('\\', '/')
                }
                finishBackgroundTask(taskId)
                reloadWorkspaceNow()
                openRelative(relative, pinned = true)
                status = "Remote PDF downloaded"
            } catch (cancelled: CancellationException) {
                finishBackgroundTask(taskId)
                throw cancelled
            } catch (error: Throwable) {
                failBackgroundTask(
                    taskId,
                    "Remote PDF",
                    "Download interrupted: ${error.message ?: "unknown error"}. Partial data was retained.",
                    onRetry = { downloadRemotePdf(url) },
                )
            }
        }
        beginBackgroundTask(taskId, "Remote PDF", url, onCancel = job::cancel)
        openToolWindow(ToolWindow.TASKS)
        job.start()
    }

    private fun formatDownloadProgress(downloaded: Long, total: Long): String {
        fun bytes(value: Long): String = when {
            value >= 1024L * 1024L -> "%.1f MB".format(value / (1024f * 1024f))
            value >= 1024L -> "%.1f KB".format(value / 1024f)
            else -> "$value B"
        }
        return if (total > 0) "${bytes(downloaded)} / ${bytes(total)}" else bytes(downloaded)
    }

    fun applyBuiltInLayoutPreset(preset: BuiltInLayoutPreset) {
        secondaryTool = null
        when (preset) {
            BuiltInLayoutPreset.READING -> {
                leftPanelVisible = false
                agentPanelVisible = false
                agentFloating = false
            }
            BuiltInLayoutPreset.WRITING -> {
                leftPanelVisible = true
                agentPanelVisible = false
                agentFloating = false
                toolPanelDock = ToolPanelDock.LEFT
                activeTool = ToolWindow.OUTLINE
                if (!toolTabs.contains(ToolWindow.OUTLINE)) toolTabs += ToolWindow.OUTLINE
            }
            BuiltInLayoutPreset.AGENT -> {
                leftPanelVisible = false
                agentPanelVisible = true
                agentFloating = false
            }
        }
        persistWorkbench()
    }

    fun saveCurrentLayoutPreset(name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val snapshot = PersistedLayoutPreset(
            name = cleanName,
            leftPanelVisible = leftPanelVisible,
            agentPanelVisible = agentPanelVisible,
            activityRailVisible = activityRailVisible,
            activityRailRight = activityRailRight,
            toolPanelDock = toolPanelDock.name,
            leftWidth = leftPanelWidth,
            agentWidth = agentPanelWidth,
            bottomPanelHeight = bottomPanelHeight,
            activeTool = activeTool.name,
            toolTabs = toolTabs.map(ToolWindow::name),
            toolPanelPinned = toolPanelPinned,
            toolPanelExpanded = toolPanelExpanded,
            agentFloating = agentFloating,
            documentSplit = documentSplit.name,
            secondaryTool = secondaryTool?.name,
        )
        val existingIndex = customLayoutPresets.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        if (existingIndex >= 0) customLayoutPresets[existingIndex] = snapshot else customLayoutPresets += snapshot
        fileOperationMessage = "Layout preset saved"
        persistWorkbench()
    }

    fun applyCustomLayoutPreset(preset: PersistedLayoutPreset) {
        leftPanelVisible = preset.leftPanelVisible
        agentPanelVisible = preset.agentPanelVisible
        activityRailVisible = preset.activityRailVisible
        activityRailRight = preset.activityRailRight
        toolPanelDock = runCatching { ToolPanelDock.valueOf(preset.toolPanelDock) }.getOrDefault(ToolPanelDock.LEFT)
        leftPanelWidth = preset.leftWidth.coerceIn(210f, 480f)
        agentPanelWidth = preset.agentWidth.coerceIn(300f, 540f)
        bottomPanelHeight = preset.bottomPanelHeight.coerceIn(150f, 420f)
        activeTool = runCatching { ToolWindow.valueOf(preset.activeTool) }.getOrDefault(ToolWindow.FILES)
        toolTabs.clear()
        toolTabs += preset.toolTabs.mapNotNull { runCatching { ToolWindow.valueOf(it) }.getOrNull() }.filter { it != ToolWindow.AGENT }
        if (toolTabs.isEmpty()) toolTabs += activeTool.takeIf { it != ToolWindow.AGENT } ?: ToolWindow.FILES
        if (activeTool != ToolWindow.AGENT && !toolTabs.contains(activeTool)) toolTabs += activeTool
        secondaryTool = preset.secondaryTool
            ?.let { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
            ?.takeIf { it != activeTool && it != ToolWindow.AGENT }
        secondaryTool?.let { if (!toolTabs.contains(it)) toolTabs += it }
        toolPanelPinned = preset.toolPanelPinned
        toolPanelExpanded = preset.toolPanelExpanded
        agentFloating = preset.agentFloating && agentPanelVisible
        documentSplit = runCatching { DocumentSplit.valueOf(preset.documentSplit) }.getOrDefault(DocumentSplit.NONE)
        if (documentSplit != DocumentSplit.NONE && documents.size < 2) documentSplit = DocumentSplit.NONE
        if (documentSplit != DocumentSplit.NONE && secondaryPath == null) {
            secondaryPath = documents.firstOrNull { it.relativePath != activePath }?.relativePath
        }
        if (documentSplit == DocumentSplit.NONE) secondaryPath = null
        fileOperationMessage = "Applied layout preset: ${preset.name}"
        persistWorkbench()
    }

    fun deleteCustomLayoutPreset(name: String) {
        customLayoutPresets.removeAll { it.name.equals(name, ignoreCase = true) }
        persistWorkbench()
    }

    fun toggleAgentFloating() {
        agentFloating = !agentFloating
        agentPanelVisible = true
        persistWorkbench()
    }

    fun closeAgentPanel() {
        agentPanelVisible = false
        agentFloating = false
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

    fun backlinksForActive(): List<IndexedNote> = activeDocument
        ?.takeIf { it.kind == DocumentKind.MARKDOWN }
        ?.let { workspaceIndex.backlinks(it.relativePath) }
        .orEmpty()

    fun outgoingForActive(): List<IndexedNote> = activeDocument
        ?.takeIf { it.kind == DocumentKind.MARKDOWN }
        ?.let { workspaceIndex.outgoing(it.relativePath) }
        .orEmpty()

    fun sendAgentMessage() {
        if (agentSendAvailability != AgentSendAvailability.READY) {
            status = "Agent is not ready to send"
            return
        }
        val prompt = agentInput.trim()
        if (prompt.isBlank()) return
        agentInput = ""
        if (agentRunning) {
            agentMessages += AgentMessage("steering_queued", prompt)
            status = "Steering queued"
            persistAgentTranscript()
            return
        } else {
            agentMessages += AgentMessage("user", prompt)
        }
        startAgentTurn()
    }

    private fun startAgentTurn() {
        val sessionMessages = agentMessages
        val assistantIndex = sessionMessages.size
        sessionMessages += AgentMessage("assistant", "")
        agentRunning = true
        val runId = ++agentRunId
        persistAgentTranscript()
        agentJob = scope.launch {
            val attachedFiles = withContext(Dispatchers.IO) {
                val root = workspaceRoot
                if (root == null) emptyList() else agentContextPaths.take(8).map { relative ->
                    val path = root.resolve(relative).normalize()
                    val content = runCatching {
                        if (Files.isRegularFile(path) && workspaceService.kindOf(path) in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) workspaceService.readText(path).take(12_000) else "[Binary or unavailable file]"
                    }.getOrDefault("[Could not read file]")
                    relative to content
                }
            }
            val requestMessages = buildList {
                add(AgentMessage("system", "You are the workspace assistant in XueXue. Be concise and preserve user files."))
                if (includeActiveDocument) activeDocument?.takeIf { it.content.isNotBlank() }?.let { tab ->
                    add(AgentMessage("system", "Current document (${tab.relativePath}):\n${tab.content.take(16_000)}"))
                }
                attachedFiles.forEach { (relative, content) ->
                    add(AgentMessage("system", "Attached workspace file ($relative):\n$content"))
                }
                agentContextSnippets.takeLast(8).forEach { snippet ->
                    add(AgentMessage("system", "Attached selected context (${snippet.title}):\n${snippet.content.take(8_000)}"))
                }
                addAll(sessionMessages.take(assistantIndex).takeLast(30).map { message ->
                    if (message.role == "steering") message.copy(role = "user") else message
                }.filter { it.role in setOf("user", "assistant", "system") })
            }
            runCatching {
                agentClient.stream(
                    requestMessages,
                    AgentProviderConfig(agentEndpoint, agentModel, agentApiKey),
                ) { delta ->
                    scope.launch {
                        val current = sessionMessages.getOrNull(assistantIndex) ?: return@launch
                        sessionMessages[assistantIndex] = current.copy(content = current.content + delta)
                    }
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    val current = sessionMessages.getOrNull(assistantIndex)
                    if (current != null) sessionMessages[assistantIndex] = current.copy(
                        content = throwable.message ?: "Agent request failed.",
                        error = true,
                    )
                }
            }
            if (agentRunId == runId) {
                agentRunning = false
                val queuedIndex = sessionMessages.indexOfFirst { it.role == "steering_queued" }
                if (queuedIndex >= 0) {
                    sessionMessages[queuedIndex] = sessionMessages[queuedIndex].copy(role = "steering")
                    status = "Queued steering started"
                    persistAgentTranscript()
                    startAgentTurn()
                } else {
                    persistAgentTranscript()
                }
            }
        }
    }

    fun stopAgent() {
        agentRunId++
        agentJob?.cancel()
        agentRunning = false
        agentMessages.indices.filter { agentMessages[it].role == "steering_queued" }.forEach { index ->
            agentMessages[index] = agentMessages[index].copy(role = "steering_cancelled")
        }
        status = "Agent stopped"
        persistAgentTranscript()
    }

    fun requestAgentApproval(request: AgentApprovalRequest): Boolean {
        val required = request.operation.requiresExplicitApproval(
            workspaceTrusted = workspaceTrusted,
            readOnlyMode = agentReadOnlyMode,
            allowWrite = agentAllowWrite,
            allowShell = agentAllowShell,
        )
        if (!required) return false
        pendingAgentApproval = request
        agentPanelVisible = true
        status = "Agent is waiting for approval"
        return true
    }

    fun decideAgentApproval(decision: AgentApprovalDecision) {
        val request = pendingAgentApproval ?: return
        if (decision == AgentApprovalDecision.ALLOW_SESSION) {
            when (request.operation) {
                AgentOperationKind.WRITE_FILE -> agentAllowWrite = true
                AgentOperationKind.RUN_SHELL -> agentAllowShell = true
                else -> Unit
            }
            if (request.operation == AgentOperationKind.WRITE_FILE) agentReadOnlyMode = false
        }
        agentApprovalHistory += AgentApprovalRecord(request, decision)
        pendingAgentApproval = null
        status = when (decision) {
            AgentApprovalDecision.ALLOW_ONCE -> "Agent operation allowed once"
            AgentApprovalDecision.ALLOW_SESSION -> "Agent operation allowed for this session"
            AgentApprovalDecision.DENY -> "Agent operation denied"
        }
        persistWorkbench()
    }

    fun setAgentDiffDecision(messageIndex: Int, decision: String?) {
        if (decision !in setOf(null, "accepted", "rejected")) return
        val message = agentMessages.getOrNull(messageIndex) ?: return
        if (message.role != "diff" || message.decision == decision) return
        agentMessages[messageIndex] = message.copy(decision = decision)
        status = when (decision) {
            "accepted" -> "Agent change accepted"
            "rejected" -> "Agent change rejected"
            else -> "Agent change decision undone"
        }
        persistAgentTranscript()
    }

    fun newAgentSession() {
        if (agentRunning) stopAgent()
        val id = "session-${System.currentTimeMillis()}"
        agentSessions += AgentSessionState(id, "新会话 ${agentSessions.size + 1}")
        activeAgentSessionId = id
        persistAgentTranscript()
        persistWorkbench()
    }

    fun addAgentContext(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.isNotBlank() && !agentContextPaths.contains(normalized)) agentContextPaths += normalized
        agentPanelVisible = true
        fileOperationMessage = "Added to Agent context"
    }

    fun addAgentSnippetContext(title: String, content: String) {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return
        agentContextSnippets += AgentContextSnippet(
            id = System.currentTimeMillis(),
            title = title.trim().ifBlank { "Selected text" },
            content = cleanContent,
        )
        agentPanelVisible = true
        fileOperationMessage = "Selected context added to Agent"
    }

    fun beginWorkspaceDrag(relativePath: String, displayName: String, position: Offset) {
        dragPayload = WorkspaceDragPayload(relativePath, displayName)
        dragPosition = position
    }

    fun updateWorkspaceDrag(position: Offset) {
        if (dragPayload != null) dragPosition = position
    }

    fun finishWorkspaceDrag() {
        val payload = dragPayload ?: return
        when {
            agentDropBounds?.contains(dragPosition) == true -> addAgentContext(payload.relativePath)
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
        agentContextPaths.remove(relativePath)
    }

    fun removeAgentSnippetContext(id: Long) {
        agentContextSnippets.removeAll { it.id == id }
    }

    fun selectAgentSession(id: String) {
        if (id == activeAgentSessionId || agentSessions.none { it.id == id }) return
        if (agentRunning) stopAgent()
        activeAgentSessionId = id
        persistAgentTranscript()
        persistWorkbench()
    }

    fun renameAgentSession(id: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) agentSessions.firstOrNull { it.id == id }?.name = clean
        persistAgentTranscript()
    }

    fun cloneAgentSession(fork: Boolean = false) {
        if (agentRunning) stopAgent()
        val source = activeAgentSession
        val id = "session-${System.currentTimeMillis()}"
        val suffix = if (fork) "分支" else "副本"
        agentSessions += AgentSessionState(id, "${source.name} · $suffix", source.messages.toList())
        activeAgentSessionId = id
        persistAgentTranscript()
        persistWorkbench()
    }

    fun deleteAgentSession(id: String) {
        val session = agentSessions.firstOrNull { it.id == id } ?: return
        if (agentSessions.size == 1) {
            session.messages.clear()
            session.name = "默认会话"
        } else {
            if (agentRunning && id == activeAgentSessionId) stopAgent()
            lastDeletedAgentSession = session
            agentSessions.remove(session)
            if (activeAgentSessionId == id) activeAgentSessionId = agentSessions.first().id
            fileOperationMessage = "Agent session deleted"
        }
        persistAgentTranscript()
        persistWorkbench()
    }

    fun restoreDeletedAgentSession() {
        val session = lastDeletedAgentSession ?: return
        agentSessions += session
        activeAgentSessionId = session.id
        lastDeletedAgentSession = null
        persistAgentTranscript()
        persistWorkbench()
    }

    fun clearError() {
        errorMessage = null
    }

    fun persistLayout() = persistWorkbench()

    private suspend fun reloadWorkspaceNow() {
        val root = workspaceRoot ?: return
        val (tree, index) = withContext(Dispatchers.IO) {
            workspaceService.scan(root) to workspaceService.buildIndex(root, openMarkdownOverrides())
        }
        workspaceTree = tree
        workspaceIndex = index
    }

    private fun startWorkspaceWatcher() {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            var knownWorkspaceRevision = withContext(Dispatchers.IO) {
                workspaceRoot?.let(workspaceService::workspaceRevision) ?: 0L
            }
            while (true) {
                delay(1_500)
                val root = workspaceRoot ?: continue
                val currentRevision = withContext(Dispatchers.IO) { workspaceService.workspaceRevision(root) }
                if (currentRevision != knownWorkspaceRevision) {
                    val (tree, index) = withContext(Dispatchers.IO) {
                        workspaceService.scan(root) to workspaceService.buildIndex(root, openMarkdownOverrides())
                    }
                    workspaceTree = tree
                    workspaceIndex = index
                    knownWorkspaceRevision = currentRevision
                    status = "Workspace updated from disk"
                }
                documents.filter { it.kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) }.toList().forEach { tab ->
                    val exists = withContext(Dispatchers.IO) { Files.isRegularFile(root.resolve(tab.relativePath)) }
                    if (!exists) {
                        tab.externalDeleted = true
                        return@forEach
                    }
                    tab.externalDeleted = false
                    val snapshot = withContext(Dispatchers.IO) {
                        val path = root.resolve(tab.relativePath)
                        if (!Files.isRegularFile(path)) null else runCatching {
                            Files.getLastModifiedTime(path).toMillis() to workspaceService.readText(path)
                        }.getOrNull()
                    } ?: return@forEach
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

    private fun topLevelSelection(paths: List<String>): List<String> {
        val normalized = paths.map { it.replace('\\', '/').trim('/') }.filter { it.isNotBlank() }.distinct()
        return normalized.filter { path -> normalized.none { other -> other != path && path.startsWith("$other/") } }
    }

    private fun openMarkdownOverrides(): Map<String, String> = documents
        .filter { it.kind == DocumentKind.MARKDOWN }
        .associate { it.relativePath to it.content }

    private fun persistWorkbench() {
        val root = workspaceRoot ?: return
        val snapshot = PersistedWorkbench(
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
            pdfZooms = documents.associate { it.relativePath to it.pdfZoom },
            pdfFitModes = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfFitMode },
            pdfViewModes = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfViewMode },
            pdfSidePanels = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfSidePanel },
            pdfRotations = documents.filter { it.kind == DocumentKind.PDF }.associate { it.relativePath to it.pdfRotation },
        )
        scope.launch(Dispatchers.IO) { runCatching { workspaceService.saveWorkbench(root, snapshot) } }
    }

    private fun agentTranscriptPath(): Path? = workspaceRoot?.resolve(".xuexue/agent-sessions.json")

    private fun pdfAnnotationsPath(): Path? = workspaceRoot?.resolve(".xuexue/pdf-annotations.json")

    private fun loadPdfAnnotations(document: DocumentTab) {
        val path = pdfAnnotationsPath() ?: return
        if (!Files.isRegularFile(path)) return
        runCatching { json.decodeFromString<Map<String, List<PdfAnnotation>>>(Files.readString(path)) }
            .getOrNull()?.get(document.relativePath)?.let { document.pdfAnnotations += it }
    }

    private fun persistPdfAnnotations(document: DocumentTab) {
        val path = pdfAnnotationsPath() ?: return
        val documentPath = document.relativePath
        val annotations = document.pdfAnnotations.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val existing = if (Files.isRegularFile(path)) {
                    json.decodeFromString<Map<String, List<PdfAnnotation>>>(Files.readString(path)).toMutableMap()
                } else mutableMapOf()
                existing[documentPath] = annotations
                Files.createDirectories(path.parent)
                workspaceService.atomicWrite(path, json.encodeToString(existing))
            }
        }
    }

    private fun loadAgentTranscript() {
        val path = agentTranscriptPath() ?: return
        val legacyPath = workspaceRoot?.resolve(".xuexue/agent-session.json")
        agentSessions.clear()
        val sessions = when {
            Files.isRegularFile(path) -> runCatching { json.decodeFromString<List<PersistedAgentSession>>(Files.readString(path)) }.getOrDefault(emptyList())
            legacyPath != null && Files.isRegularFile(legacyPath) -> listOf(PersistedAgentSession("default", "默认会话", runCatching { json.decodeFromString<List<AgentMessage>>(Files.readString(legacyPath)) }.getOrDefault(emptyList())))
            else -> emptyList()
        }
        sessions.forEach { session -> agentSessions += AgentSessionState(session.id, session.name, session.messages) }
        if (agentSessions.isEmpty()) agentSessions += AgentSessionState("default", "默认会话")
        activeAgentSessionId = agentSessions.first().id
    }

    private fun persistAgentTranscript() {
        val path = agentTranscriptPath() ?: return
        val snapshot = agentSessions.map { session -> PersistedAgentSession(session.id, session.name, session.messages.toList()) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(path.parent)
                workspaceService.atomicWrite(path, json.encodeToString(snapshot))
            }
        }
    }
}
