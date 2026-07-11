package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.EditorCommand
import dev.xuexue.core.EditorCommands
import dev.xuexue.core.EditorMode
import dev.xuexue.core.IndexedNote
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.TextSelection
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.OpenAiCompatibleAgentClient
import dev.xuexue.platform.PersistedWorkbench
import dev.xuexue.platform.SearchHit
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString

class DocumentTab(
    val relativePath: String,
    val kind: DocumentKind,
    content: String,
    pinned: Boolean,
) {
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var mode by mutableStateOf(if (kind == DocumentKind.MARKDOWN) EditorMode.EDIT else EditorMode.SOURCE)
    var pinned by mutableStateOf(pinned)
    var cursor by mutableStateOf(TextRange.Zero)
    var pdfPage by mutableStateOf(0)
    var pdfZoom by mutableStateOf(1f)
    val undo = ArrayDeque<String>()
    val redo = ArrayDeque<String>()

    val displayName: String get() = relativePath.substringAfterLast('/')
    val dirty: Boolean get() = content != savedContent
}

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

    val documents = mutableStateListOf<DocumentTab>()
    var activePath by mutableStateOf<String?>(null)
        private set
    val activeDocument: DocumentTab? get() = documents.firstOrNull { it.relativePath == activePath }

    var activeTool by mutableStateOf(ToolWindow.FILES)
    var leftPanelVisible by mutableStateOf(true)
    var agentPanelVisible by mutableStateOf(true)
    var leftPanelWidth by mutableStateOf(268f)
    var agentPanelWidth by mutableStateOf(360f)

    var searchQuery by mutableStateOf("")
    val searchResults = mutableStateListOf<SearchHit>()
    var workspaceIndex by mutableStateOf(WorkspaceIndex(emptyList()))
        private set

    val agentMessages = mutableStateListOf<AgentMessage>()
    var agentInput by mutableStateOf("")
    var agentEndpoint by mutableStateOf("https://api.openai.com/v1/chat/completions")
    var agentModel by mutableStateOf("")
    var agentApiKey by mutableStateOf(System.getenv("OPENAI_API_KEY").orEmpty())
    var agentRunning by mutableStateOf(false)
        private set
    var includeActiveDocument by mutableStateOf(true)
    private var agentJob: Job? = null
    private var saveJob: Job? = null
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
            activePath = null
            leftPanelWidth = persisted.third.leftWidth.coerceIn(210f, 480f)
            agentPanelWidth = persisted.third.agentWidth.coerceIn(300f, 540f)
            activeTool = persisted.third.activeTool?.let { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
                ?: ToolWindow.FILES
            if (restore) {
                persisted.third.openDocuments.forEach { relative ->
                    openRelative(relative, pinned = true, persist = false)
                    documents.firstOrNull { it.relativePath == relative }?.apply {
                        mode = persisted.third.editorModes[relative]
                            ?.let { runCatching { EditorMode.valueOf(it) }.getOrNull() }
                            ?: mode
                        val offset = persisted.third.cursorOffsets[relative]?.coerceIn(0, content.length) ?: 0
                        cursor = TextRange(offset)
                        pdfPage = persisted.third.pdfPages[relative]?.coerceAtLeast(0) ?: 0
                        pdfZoom = persisted.third.pdfZooms[relative]?.coerceIn(0.4f, 4f) ?: 1f
                    }
                }
                activePath = persisted.third.activeDocument?.takeIf { path -> documents.any { it.relativePath == path } }
                    ?: documents.firstOrNull()?.relativePath
            }
            loadAgentTranscript()
            status = root.fileName?.toString() ?: root.pathString
            persistWorkbench()
        }.onFailure { throwable ->
            errorMessage = throwable.message ?: "Could not open the workspace."
            status = "Open failed"
        }
        isLoading = false
    }

    fun closeWorkspace() {
        documents.filter { it.dirty }.forEach(::saveDocumentNow)
        persistWorkbench()
        workspaceRoot = null
        workspaceTree = null
        documents.clear()
        activePath = null
        searchResults.clear()
        agentMessages.clear()
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

    fun openRelative(relativePath: String, pinned: Boolean = false, persist: Boolean = true) {
        val root = workspaceRoot ?: return
        val normalized = relativePath.replace('\\', '/')
        documents.firstOrNull { it.relativePath == normalized }?.let { existing ->
            if (pinned) existing.pinned = true
            activePath = normalized
            if (persist) persistWorkbench()
            return
        }
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
            documents += DocumentTab(normalized, kind, content, pinned)
            activePath = normalized
            status = normalized
            if (persist) persistWorkbench()
        }.onFailure { errorMessage = it.message ?: "Could not open $normalized" }
    }

    fun pin(relativePath: String) {
        documents.firstOrNull { it.relativePath == relativePath }?.pinned = true
        persistWorkbench()
    }

    fun selectTab(relativePath: String) {
        activePath = relativePath
        persistWorkbench()
    }

    fun closeTab(tab: DocumentTab, force: Boolean = false): Boolean {
        if (tab.dirty && !force) return false
        val index = documents.indexOf(tab)
        documents.remove(tab)
        if (activePath == tab.relativePath) {
            activePath = documents.getOrNull(index.coerceAtMost(documents.lastIndex))?.relativePath
        }
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

    fun applyEditorCommand(command: EditorCommand) {
        val tab = activeDocument ?: return
        if (tab.kind != DocumentKind.MARKDOWN) return
        val result = EditorCommands.apply(command, tab.content, TextSelection(tab.cursor.start, tab.cursor.end))
        updateContent(tab, result.text, TextRange(result.selection.start, result.selection.end))
    }

    fun undo() {
        val tab = activeDocument ?: return
        val previous = tab.undo.removeLastOrNull() ?: return
        tab.redo.addLast(tab.content)
        tab.content = previous
        tab.cursor = TextRange(previous.length.coerceAtMost(tab.cursor.start))
        scheduleSave(tab)
    }

    fun redo() {
        val tab = activeDocument ?: return
        val next = tab.redo.removeLastOrNull() ?: return
        tab.undo.addLast(tab.content)
        tab.content = next
        tab.cursor = TextRange(next.length.coerceAtMost(tab.cursor.start))
        scheduleSave(tab)
    }

    fun saveActive() = activeDocument?.let(::saveDocumentNow)

    private fun scheduleSave(tab: DocumentTab) {
        saveJob?.cancel()
        status = "Saving..."
        saveJob = scope.launch {
            delay(700)
            saveDocumentNow(tab)
        }
    }

    private fun saveDocumentNow(tab: DocumentTab) {
        val root = workspaceRoot ?: return
        if (tab.kind !in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) || !tab.dirty) return
        runCatching {
            workspaceService.atomicWrite(root.resolve(tab.relativePath), tab.content)
            tab.savedContent = tab.content
            workspaceIndex = workspaceService.buildIndex(root, openMarkdownOverrides())
            status = "Saved"
            persistWorkbench()
        }.onFailure {
            status = "Save failed"
            errorMessage = "Save failed: ${it.message}"
        }
    }

    fun setEditorMode(mode: EditorMode) {
        activeDocument?.mode = mode
        persistWorkbench()
    }

    fun setPdfPosition(page: Int? = null, zoom: Float? = null) {
        activeDocument?.takeIf { it.kind == DocumentKind.PDF }?.let { tab ->
            if (page != null) tab.pdfPage = page.coerceAtLeast(0)
            if (zoom != null) tab.pdfZoom = zoom.coerceIn(0.4f, 4f)
            persistWorkbench()
        }
    }

    fun search() {
        val root = workspaceRoot ?: return
        activeTool = ToolWindow.SEARCH
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
        val prompt = agentInput.trim()
        if (prompt.isBlank() || agentRunning) return
        agentInput = ""
        agentMessages += AgentMessage("user", prompt)
        val assistantIndex = agentMessages.size
        agentMessages += AgentMessage("assistant", "")
        agentRunning = true
        persistAgentTranscript()
        agentJob = scope.launch {
            val requestMessages = buildList {
                add(AgentMessage("system", "You are the workspace assistant in XueXue. Be concise and preserve user files."))
                if (includeActiveDocument) activeDocument?.takeIf { it.content.isNotBlank() }?.let { tab ->
                    add(AgentMessage("system", "Current document (${tab.relativePath}):\n${tab.content.take(16_000)}"))
                }
                addAll(agentMessages.take(assistantIndex).takeLast(30))
            }
            runCatching {
                agentClient.stream(
                    requestMessages,
                    AgentProviderConfig(agentEndpoint, agentModel, agentApiKey),
                ) { delta ->
                    scope.launch {
                        val current = agentMessages.getOrNull(assistantIndex) ?: return@launch
                        agentMessages[assistantIndex] = current.copy(content = current.content + delta)
                    }
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    val current = agentMessages.getOrNull(assistantIndex)
                    if (current != null) agentMessages[assistantIndex] = current.copy(
                        content = throwable.message ?: "Agent request failed.",
                        error = true,
                    )
                }
            }
            agentRunning = false
            persistAgentTranscript()
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
        agentRunning = false
        status = "Agent stopped"
        persistAgentTranscript()
    }

    fun clearError() {
        errorMessage = null
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
            activeTool = activeTool.name,
            leftWidth = leftPanelWidth,
            agentWidth = agentPanelWidth,
            editorModes = documents.associate { it.relativePath to it.mode.name },
            cursorOffsets = documents.associate { it.relativePath to it.cursor.start },
            pdfPages = documents.associate { it.relativePath to it.pdfPage },
            pdfZooms = documents.associate { it.relativePath to it.pdfZoom },
        )
        scope.launch(Dispatchers.IO) { runCatching { workspaceService.saveWorkbench(root, snapshot) } }
    }

    private fun agentTranscriptPath(): Path? = workspaceRoot?.resolve(".xuexue/agent-session.json")

    private fun loadAgentTranscript() {
        val path = agentTranscriptPath() ?: return
        agentMessages.clear()
        if (!Files.isRegularFile(path)) return
        runCatching { json.decodeFromString<List<AgentMessage>>(Files.readString(path)) }
            .onSuccess { agentMessages += it }
    }

    private fun persistAgentTranscript() {
        val path = agentTranscriptPath() ?: return
        val snapshot = agentMessages.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(path.parent)
                workspaceService.atomicWrite(path, json.encodeToString(snapshot))
            }
        }
    }
}

