package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRecord
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.AgentStreamEvent
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.PersistedLayoutPreset

interface WorkbenchLayoutModel {
    var activeTool: ToolWindow
    val toolTabs: SnapshotStateList<ToolWindow>
    var secondaryTool: ToolWindow?
    var leftPanelVisible: Boolean
    var agentPanelVisible: Boolean
    var leftPanelWidth: Float
    var agentPanelWidth: Float
    var activityRailVisible: Boolean
    var activityRailRight: Boolean
    var toolPanelDock: ToolPanelDock
    var bottomPanelHeight: Float
    var documentSplit: DocumentSplit
    var secondaryPath: String?
    var toolPanelPinned: Boolean
    var toolPanelExpanded: Boolean
    val detachedDocuments: SnapshotStateList<String>
    var agentFloating: Boolean
    val customLayoutPresets: SnapshotStateList<PersistedLayoutPreset>
    var requestedFocusTarget: WorkbenchFocusTarget
    var focusRequestSerial: Int
}

class WorkbenchLayoutState : WorkbenchLayoutModel {
    override var activeTool by mutableStateOf(ToolWindow.FILES)
    override val toolTabs = mutableStateListOf(ToolWindow.FILES)
    override var secondaryTool by mutableStateOf<ToolWindow?>(null)
    override var leftPanelVisible by mutableStateOf(true)
    override var agentPanelVisible by mutableStateOf(true)
    override var leftPanelWidth by mutableStateOf(268f)
    override var agentPanelWidth by mutableStateOf(360f)
    override var activityRailVisible by mutableStateOf(true)
    override var activityRailRight by mutableStateOf(false)
    override var toolPanelDock by mutableStateOf(ToolPanelDock.LEFT)
    override var bottomPanelHeight by mutableStateOf(230f)
    override var documentSplit by mutableStateOf(DocumentSplit.NONE)
    override var secondaryPath by mutableStateOf<String?>(null)
    override var toolPanelPinned by mutableStateOf(true)
    override var toolPanelExpanded by mutableStateOf(false)
    override val detachedDocuments = mutableStateListOf<String>()
    override var agentFloating by mutableStateOf(false)
    override val customLayoutPresets = mutableStateListOf<PersistedLayoutPreset>()
    override var requestedFocusTarget by mutableStateOf(WorkbenchFocusTarget.DOCUMENT)
    override var focusRequestSerial by mutableStateOf(0)

    fun updateDocumentSplit(
        split: DocumentSplit,
        documents: List<DocumentTab>,
        activePath: String?,
    ) {
        documentSplit = split
        secondaryPath =
            if (split == DocumentSplit.NONE) {
                null
            } else {
                secondaryPath?.takeIf { path -> documents.any { it.relativePath == path } }
                    ?: documents.firstOrNull { it.relativePath != activePath }?.relativePath
                    ?: activePath
            }
    }

    fun splitDocument(
        relativePath: String,
        direction: DocumentSplitDirection,
        documents: List<DocumentTab>,
        activePath: String?,
    ): String? {
        if (documents.none { it.relativePath == relativePath }) return activePath
        secondaryPath = relativePath
        documentSplit =
            when (direction) {
                DocumentSplitDirection.RIGHT -> DocumentSplit.VERTICAL
                DocumentSplitDirection.BOTTOM -> DocumentSplit.HORIZONTAL
            }
        return if (activePath == relativePath) {
            documents.firstOrNull { it.relativePath != relativePath }?.relativePath ?: activePath
        } else {
            activePath
        }
    }

    fun setSecondaryDocument(
        path: String,
        documents: List<DocumentTab>,
    ) {
        if (documents.any { it.relativePath == path }) secondaryPath = path
    }

    fun openToolWindow(tool: ToolWindow): WorkbenchFocusTarget {
        if (tool == ToolWindow.AGENT) {
            agentPanelVisible = true
            return WorkbenchFocusTarget.AGENT
        }
        if (!toolTabs.contains(tool)) toolTabs += tool
        if (secondaryTool == tool) secondaryTool = activeTool.takeIf { it != tool }
        activeTool = tool
        leftPanelVisible = true
        return WorkbenchFocusTarget.TOOL_WINDOW
    }

    fun closeToolTab(tool: ToolWindow) {
        toolTabs.remove(tool)
        if (secondaryTool == tool) secondaryTool = null
        if (activeTool == tool) activeTool = toolTabs.lastOrNull() ?: ToolWindow.FILES
        if (toolTabs.isEmpty()) leftPanelVisible = false
    }

    fun splitToolWindow(tool: ToolWindow? = null) {
        val target =
            tool?.takeIf { it != activeTool && it != ToolWindow.AGENT }
                ?: toolTabs.firstOrNull { it != activeTool && it != ToolWindow.AGENT }
                ?: ToolWindow.entries.firstOrNull { it != activeTool && it != ToolWindow.AGENT }
                ?: return
        if (!toolTabs.contains(target)) toolTabs += target
        secondaryTool = target
        leftPanelVisible = true
    }

    fun closeToolSplit() {
        secondaryTool = null
    }

    fun toggleToolPanelPinned() {
        toolPanelPinned = !toolPanelPinned
    }

    fun toggleToolPanelExpanded() {
        toolPanelExpanded = !toolPanelExpanded
    }

    fun autoHideToolPanelAfterAction() {
        if (!toolPanelPinned && toolPanelDock != ToolPanelDock.FLOAT) leftPanelVisible = false
    }

    fun detachDocument(
        relativePath: String,
        documents: List<DocumentTab>,
    ) {
        if (documents.any { it.relativePath == relativePath } && relativePath !in detachedDocuments) {
            detachedDocuments += relativePath
        }
    }

    fun attachDocument(relativePath: String) {
        detachedDocuments.remove(relativePath)
    }

    fun applyBuiltInPreset(preset: BuiltInLayoutPreset) {
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
                if (ToolWindow.OUTLINE !in toolTabs) toolTabs += ToolWindow.OUTLINE
            }

            BuiltInLayoutPreset.AGENT -> {
                leftPanelVisible = false
                agentPanelVisible = true
                agentFloating = false
            }
        }
    }

    fun saveCurrentPreset(name: String): Boolean {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return false
        val snapshot =
            PersistedLayoutPreset(
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
        return true
    }

    fun applyCustomPreset(
        preset: PersistedLayoutPreset,
        documents: List<DocumentTab>,
        activePath: String?,
    ) {
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
        toolTabs +=
            preset.toolTabs
                .mapNotNull { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
                .filter { it != ToolWindow.AGENT }
        if (toolTabs.isEmpty()) toolTabs += activeTool.takeIf { it != ToolWindow.AGENT } ?: ToolWindow.FILES
        if (activeTool != ToolWindow.AGENT && activeTool !in toolTabs) toolTabs += activeTool
        secondaryTool =
            preset.secondaryTool
                ?.let { runCatching { ToolWindow.valueOf(it) }.getOrNull() }
                ?.takeIf { it != activeTool && it != ToolWindow.AGENT }
        secondaryTool?.let { if (it !in toolTabs) toolTabs += it }
        toolPanelPinned = preset.toolPanelPinned
        toolPanelExpanded = preset.toolPanelExpanded
        agentFloating = preset.agentFloating && agentPanelVisible
        documentSplit = runCatching { DocumentSplit.valueOf(preset.documentSplit) }.getOrDefault(DocumentSplit.NONE)
        if (documentSplit != DocumentSplit.NONE && documents.size < 2) documentSplit = DocumentSplit.NONE
        if (documentSplit != DocumentSplit.NONE && secondaryPath == null) {
            secondaryPath = documents.firstOrNull { it.relativePath != activePath }?.relativePath
        }
        if (documentSplit == DocumentSplit.NONE) secondaryPath = null
    }

    fun deleteCustomPreset(name: String) {
        customLayoutPresets.removeAll { it.name.equals(name, ignoreCase = true) }
    }

    fun toggleAgentFloating() {
        agentFloating = !agentFloating
        agentPanelVisible = true
    }

    fun closeAgentPanel() {
        agentPanelVisible = false
        agentFloating = false
    }
}

interface AgentRuntimeModel {
    val agentSessions: SnapshotStateList<AgentSessionState>
    var activeAgentSessionId: String
    val activeAgentSession: AgentSessionState
    val agentMessages: SnapshotStateList<AgentMessage>
    var agentInput: String
    var agentEndpoint: String
    var agentModel: String
    var agentApiKey: String
    var workspaceTrusted: Boolean
    var agentReadOnlyMode: Boolean
    var agentOfflineMode: Boolean
    var agentAllowWrite: Boolean
    var agentAllowShell: Boolean
    var pendingAgentApproval: AgentApprovalRequest?
    val agentApprovalHistory: SnapshotStateList<AgentApprovalRecord>
    var agentRunning: Boolean
    var includeActiveDocument: Boolean
    val agentSendAvailability: AgentSendAvailability
    val agentContextPaths: SnapshotStateList<String>
    val agentContextSnippets: SnapshotStateList<AgentContextSnippet>
}

class AgentRuntimeState : AgentRuntimeModel {
    override val agentSessions = mutableStateListOf(AgentSessionState("default", "默认会话"))
    override var activeAgentSessionId by mutableStateOf("default")
    override val activeAgentSession: AgentSessionState
        get() = agentSessions.firstOrNull { it.id == activeAgentSessionId } ?: agentSessions.first()
    override val agentMessages: SnapshotStateList<AgentMessage> get() = activeAgentSession.messages
    override var agentInput by mutableStateOf("")
    override var agentEndpoint by mutableStateOf("https://api.openai.com/v1/chat/completions")
    override var agentModel by mutableStateOf("")
    override var agentApiKey by mutableStateOf(System.getenv("OPENAI_API_KEY").orEmpty())
    override var workspaceTrusted by mutableStateOf(true)
    override var agentReadOnlyMode by mutableStateOf(false)
    override var agentOfflineMode by mutableStateOf(false)
    override var agentAllowWrite by mutableStateOf(true)
    override var agentAllowShell by mutableStateOf(true)
    override var pendingAgentApproval by mutableStateOf<AgentApprovalRequest?>(null)
    override val agentApprovalHistory = mutableStateListOf<AgentApprovalRecord>()
    override var agentRunning by mutableStateOf(false)
    override var includeActiveDocument by mutableStateOf(true)
    override val agentSendAvailability: AgentSendAvailability
        get() =
            dev.xuexue.core.agentSendAvailability(
                offline = agentOfflineMode,
                model = agentModel,
                apiKey = agentApiKey,
                awaitingApproval = pendingAgentApproval != null,
            )
    override val agentContextPaths = mutableStateListOf<String>()
    override val agentContextSnippets = mutableStateListOf<AgentContextSnippet>()

    fun applyStreamEvent(
        session: AgentSessionState,
        assistantId: String,
        event: AgentStreamEvent,
    ) {
        val messages = session.messages

        fun append(
            role: String,
            id: String,
            text: String,
            error: Boolean = false,
        ) {
            val index = messages.indexOfFirst { it.streamId == id && it.role == role }
            if (index >= 0) {
                messages[index] = messages[index].copy(content = messages[index].content + text, error = error)
            } else {
                messages += AgentMessage(role, text, error = error, streamId = id)
            }
        }
        when (event) {
            is AgentStreamEvent.TextDelta -> {
                append("assistant", assistantId, event.text)
            }

            is AgentStreamEvent.ReasoningDelta -> {
                append("reasoning", "reasoning:$assistantId", event.text)
            }

            is AgentStreamEvent.ToolStarted -> {
                val content = listOf(event.name, event.target, event.detail).filter(String::isNotBlank).joinToString("\n")
                messages += AgentMessage("tool_call", content, streamId = event.callId)
            }

            is AgentStreamEvent.ToolProgress -> {
                append("tool_call", event.callId, "\n${event.detail}")
            }

            is AgentStreamEvent.ToolFinished -> {
                messages += AgentMessage("tool", event.output, error = event.failed, streamId = event.callId)
            }

            is AgentStreamEvent.FileDiff -> {
                messages +=
                    AgentMessage(
                        role = "diff",
                        content = "--- a/${event.relativePath}\n+++ b/${event.relativePath}\n${event.patch}",
                        streamId = "diff:${event.relativePath}:$assistantId",
                    )
            }

            is AgentStreamEvent.Usage -> {
                session.inputTokens = event.inputTokens
                session.outputTokens = event.outputTokens
                session.costUsd = event.costUsd
            }
        }
    }

    fun stop() {
        agentRunning = false
        agentMessages.indices.filter { agentMessages[it].role == "steering_queued" }.forEach { index ->
            agentMessages[index] = agentMessages[index].copy(role = "steering_cancelled")
        }
    }

    fun applyApprovalDecision(decision: AgentApprovalDecision): AgentApprovalRequest? {
        val request = pendingAgentApproval ?: return null
        if (decision == AgentApprovalDecision.ALLOW_SESSION) {
            when (request.operation) {
                dev.xuexue.core.AgentOperationKind.WRITE_FILE -> agentAllowWrite = true
                dev.xuexue.core.AgentOperationKind.RUN_SHELL -> agentAllowShell = true
                else -> Unit
            }
            if (request.operation == dev.xuexue.core.AgentOperationKind.WRITE_FILE) agentReadOnlyMode = false
        }
        agentApprovalHistory += AgentApprovalRecord(request, decision)
        pendingAgentApproval = null
        return request
    }

    fun setDiffDecision(
        messageIndex: Int,
        decision: String?,
    ): Boolean {
        if (decision !in setOf(null, "accepted", "rejected")) return false
        val message = agentMessages.getOrNull(messageIndex) ?: return false
        if (message.role != "diff" || message.decision == decision) return false
        agentMessages[messageIndex] = message.copy(decision = decision)
        return true
    }

    fun newSession(): AgentSessionState {
        val session = AgentSessionState("session-${System.currentTimeMillis()}", "新会话 ${agentSessions.size + 1}")
        agentSessions += session
        activeAgentSessionId = session.id
        return session
    }

    fun selectSession(id: String): Boolean {
        if (id == activeAgentSessionId || agentSessions.none { it.id == id }) return false
        activeAgentSessionId = id
        return true
    }

    fun renameSession(
        id: String,
        name: String,
    ) {
        val clean = name.trim()
        if (clean.isNotEmpty()) agentSessions.firstOrNull { it.id == id }?.name = clean
    }

    fun cloneSession(fork: Boolean): AgentSessionState {
        val source = activeAgentSession
        val suffix = if (fork) "分支" else "副本"
        val session =
            AgentSessionState(
                id = "session-${System.currentTimeMillis()}",
                name = "${source.name} · $suffix",
                messages = source.messages.toList(),
                protocolMessages = source.protocolMessages.toList(),
                inputTokens = source.inputTokens,
                outputTokens = source.outputTokens,
                costUsd = source.costUsd,
            )
        agentSessions += session
        activeAgentSessionId = session.id
        return session
    }

    fun removeSession(id: String): AgentSessionState? {
        val session = agentSessions.firstOrNull { it.id == id } ?: return null
        if (agentSessions.size == 1) {
            session.messages.clear()
            session.name = "默认会话"
            return null
        }
        agentSessions.remove(session)
        if (activeAgentSessionId == id) activeAgentSessionId = agentSessions.first().id
        return session
    }

    fun restoreSession(session: AgentSessionState) {
        agentSessions += session
        activeAgentSessionId = session.id
    }
}
