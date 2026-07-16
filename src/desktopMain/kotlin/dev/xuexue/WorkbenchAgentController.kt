package dev.xuexue

import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.requiresExplicitApproval
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.DesktopAgentTools
import dev.xuexue.platform.DesktopPiAgentAdapter
import dev.xuexue.platform.WorkspaceService
import dev.xuexue.platform.adaptDesktopAgentTools
import dev.xuexue.platform.agentMessagesToLegacyProtocol
import dev.xuexue.platform.coreUserMessage
import dev.xuexue.platform.desktopToolAfterCall
import dev.xuexue.platform.legacyProtocolToAgentMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class WorkbenchAgentController(
    private val scope: CoroutineScope,
    private val runtime: AgentRuntimeState,
    private val client: AgentClient,
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val activeDocument: () -> DocumentTab?,
    private val revealPanel: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFileMessage: (String) -> Unit,
    private val persistWorkbench: () -> Unit,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private var job: Job? = null
    private var runId = 0
    private var lastDeletedSession: AgentSessionState? = null
    private val piAgent = runtime.piAgent
    private val streamFunction = DesktopPiAgentAdapter(scope, client)
    private var approvalDeferred: CompletableDeferred<AgentApprovalDecision>? = null

    val canRestoreDeletedSession: Boolean get() = lastDeletedSession != null

    fun sendMessage() {
        if (runtime.agentSendAvailability != AgentSendAvailability.READY) {
            onStatus("Agent is not ready to send")
            return
        }
        val prompt = runtime.agentInput.trim()
        if (prompt.isBlank()) return
        runtime.agentInput = ""
        if (piAgent.currentState.isStreaming) {
            runtime.agentMessages += AgentMessage("steering_queued", prompt)
            piAgent.steer(coreUserMessage(prompt))
            onStatus("Steering queued")
            persistTranscript()
            return
        }
        runtime.agentMessages += AgentMessage("user", prompt)
        startTurn(prompt)
    }

    fun stop() {
        runId++
        piAgent.abort()
        piAgent.clearAllQueues()
        runtime.stop()
        approvalDeferred?.complete(AgentApprovalDecision.DENY)
        approvalDeferred = null
        onStatus("Agent stopped")
        persistTranscript()
    }

    fun requestApproval(request: AgentApprovalRequest): Boolean {
        val required =
            request.operation.requiresExplicitApproval(
                workspaceTrusted = runtime.workspaceTrusted,
                readOnlyMode = runtime.agentReadOnlyMode,
                allowWrite = runtime.agentAllowWrite,
                allowShell = runtime.agentAllowShell,
            )
        if (!required) return false
        runtime.pendingAgentApproval = request
        revealPanel()
        onStatus("Agent is waiting for approval")
        return true
    }

    fun decideApproval(decision: AgentApprovalDecision) {
        runtime.applyApprovalDecision(decision) ?: return
        onStatus(
            when (decision) {
                AgentApprovalDecision.ALLOW_ONCE -> "Agent operation allowed once"
                AgentApprovalDecision.ALLOW_SESSION -> "Agent operation allowed for this session"
                AgentApprovalDecision.DENY -> "Agent operation denied"
            },
        )
        persistWorkbench()
        approvalDeferred?.complete(decision)
        approvalDeferred = null
    }

    fun setDiffDecision(
        messageIndex: Int,
        decision: String?,
    ) {
        if (!runtime.setDiffDecision(messageIndex, decision)) return
        onStatus(
            when (decision) {
                "accepted" -> "Agent change accepted"
                "rejected" -> "Agent change rejected"
                else -> "Agent change decision undone"
            },
        )
        persistTranscript()
    }

    fun newSession() {
        if (piAgent.currentState.isStreaming) stop()
        runtime.newSession()
        persistTranscript()
        persistWorkbench()
    }

    fun addContext(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.isNotBlank() && normalized !in runtime.agentContextPaths) runtime.agentContextPaths += normalized
        revealPanel()
        onFileMessage("Added to Agent context")
    }

    fun addSnippetContext(
        title: String,
        content: String,
    ) {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return
        runtime.agentContextSnippets +=
            AgentContextSnippet(
                id = System.currentTimeMillis(),
                title = title.trim().ifBlank { "Selected text" },
                content = cleanContent,
            )
        revealPanel()
        onFileMessage("Selected context added to Agent")
    }

    fun removeContext(relativePath: String) {
        runtime.agentContextPaths.remove(relativePath)
    }

    fun removeSnippetContext(id: Long) {
        runtime.agentContextSnippets.removeAll { it.id == id }
    }

    fun selectSession(id: String) {
        if (id == runtime.activeAgentSessionId || runtime.agentSessions.none { it.id == id }) return
        if (piAgent.currentState.isStreaming) stop()
        if (!runtime.selectSession(id)) return
        persistTranscript()
        persistWorkbench()
    }

    fun renameSession(
        id: String,
        name: String,
    ) {
        runtime.renameSession(id, name)
        persistTranscript()
    }

    fun cloneSession(fork: Boolean) {
        if (piAgent.currentState.isStreaming) stop()
        runtime.cloneSession(fork)
        persistTranscript()
        persistWorkbench()
    }

    fun deleteSession(id: String) {
        if (runtime.agentSessions.none { it.id == id }) return
        if (piAgent.currentState.isStreaming && id == runtime.activeAgentSessionId) stop()
        runtime.removeSession(id)?.let { session ->
            lastDeletedSession = session
            onFileMessage("Agent session deleted")
        }
        persistTranscript()
        persistWorkbench()
    }

    fun restoreDeletedSession() {
        val session = lastDeletedSession ?: return
        runtime.restoreSession(session)
        lastDeletedSession = null
        persistTranscript()
        persistWorkbench()
    }

    suspend fun loadTranscript(root: Path) {
        val loaded =
            withContext(Dispatchers.IO) {
                val jsonlPath =
                    runCatching {
                        workspaceService.resolveExistingInside(root, ".xuexue/agent-sessions.jsonl")
                    }.getOrNull()
                val path =
                    runCatching {
                        workspaceService.resolveExistingInside(root, ".xuexue/agent-sessions.json")
                    }.getOrNull()
                val legacyPath =
                    runCatching {
                        workspaceService.resolveExistingInside(root, ".xuexue/agent-session.json")
                    }.getOrNull()
                when {
                    jsonlPath != null && Files.isRegularFile(jsonlPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> {
                        Files
                            .readAllLines(jsonlPath)
                            .filter(String::isNotBlank)
                            .mapNotNull { line ->
                                runCatching { json.decodeFromString<PersistedAgentSession>(line) }.getOrNull()
                            }
                    }

                    path != null && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> {
                        runCatching {
                            json.decodeFromString<List<PersistedAgentSession>>(Files.readString(path))
                        }.getOrDefault(emptyList())
                    }

                    legacyPath != null && Files.isRegularFile(legacyPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> {
                        listOf(
                            PersistedAgentSession(
                                "default",
                                "默认会话",
                                runCatching {
                                    json.decodeFromString<List<AgentMessage>>(Files.readString(legacyPath))
                                }.getOrDefault(emptyList()),
                            ),
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        runtime.agentSessions.clear()
        loaded.forEach { session ->
            runtime.agentSessions +=
                AgentSessionState(
                    session.id,
                    session.name,
                    session.messages,
                    session.protocolMessages,
                    session.inputTokens,
                    session.outputTokens,
                    session.costUsd,
                )
        }
        if (runtime.agentSessions.isEmpty()) runtime.agentSessions += AgentSessionState("default", "默认会话")
        runtime.activeAgentSessionId = runtime.agentSessions.first().id
    }

    fun persistTranscript() {
        val root = workspaceRoot() ?: return
        val snapshot =
            runtime.agentSessions.map { session ->
                PersistedAgentSession(
                    session.id,
                    session.name,
                    session.messages.toList(),
                    session.protocolMessages.toList(),
                    session.inputTokens,
                    session.outputTokens,
                    session.costUsd,
                )
            }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val path = workspaceService.resolveForWriteInside(root, ".xuexue/agent-sessions.jsonl")
                val content = snapshot.joinToString("\n") { session -> json.encodeToString(session) }
                workspaceService.atomicWrite(path, content)
            }
        }
    }

    private fun startTurn(prompt: String) {
        val session = runtime.activeAgentSession
        val sessionMessages = session.messages
        runtime.agentRunning = true
        val currentRunId = ++runId
        val assistantId = "assistant:$currentRunId"
        persistTranscript()
        job =
            scope.launch {
                val attachedFiles = loadAttachedFiles()
                val projectInstructions = loadProjectInstructions()
                val model = agentModel()
                configureAgent(session, model, contextPrompt(attachedFiles, projectInstructions))
                val projection = WorkbenchAgentProjection(session, assistantId)
                val subscription = piAgent.subscribe { event, _ -> projection.apply(event) }
                runCatching { piAgent.prompt(coreUserMessage(prompt)) }.onFailure { throwable ->
                    if (throwable !is CancellationException) {
                        val index = sessionMessages.indexOfFirst { it.streamId == assistantId }
                        val failure =
                            AgentMessage(
                                "assistant",
                                throwable.message ?: "Agent request failed.",
                                error = true,
                                streamId = assistantId,
                            )
                        if (index >= 0) sessionMessages[index] = failure else sessionMessages += failure
                    }
                }
                subscription.dispose()
                session.protocolMessages.clear()
                session.protocolMessages += agentMessagesToLegacyProtocol(piAgent.currentState.messages)
                if (runId == currentRunId) {
                    runtime.agentRunning = false
                    persistTranscript()
                }
            }
    }

    private fun contextPrompt(
        attachedFiles: List<Pair<String, String>>,
        projectInstructions: List<Pair<String, String>>,
    ): String =
        buildList {
            add(SYSTEM_PROMPT)
            if (runtime.includeActiveDocument) {
                activeDocument()?.takeIf { it.content.isNotBlank() }?.let { tab ->
                    val content = "Current document (${tab.relativePath}):\n${tab.content.take(16_000)}"
                    add(content)
                }
            }
            attachedFiles.forEach { (relative, content) ->
                add("Attached workspace file ($relative):\n$content")
            }
            projectInstructions.forEach { (relative, content) ->
                add("Project instructions ($relative):\n$content")
            }
            runtime.agentContextSnippets.takeLast(8).forEach { snippet ->
                add("Attached selected context (${snippet.title}):\n${snippet.content.take(8_000)}")
            }
        }.joinToString("\n\n")

    private fun configureAgent(
        session: AgentSessionState,
        model: Model,
        systemPrompt: String,
    ) {
        val tools = DesktopAgentTools(workspaceService, workspaceRoot, ::authorizeTool, Dispatchers.IO).all()
        piAgent.setSystemPrompt(systemPrompt)
        piAgent.setModel(model)
        piAgent.setTools(adaptDesktopAgentTools(tools))
        piAgent.replaceMessages(legacyProtocolToAgentMessages(session.protocolMessages.takeLast(80), model))
        piAgent.streamFunction = streamFunction
        piAgent.getApiKey = { runtime.agentApiKey }
        piAgent.afterToolCall = { context, _ -> desktopToolAfterCall(context) }
        piAgent.sessionId = session.id
    }

    private fun agentModel(): Model =
        Model(
            id = runtime.agentModel,
            name = runtime.agentModel,
            api = "openai-completions",
            provider = "openai",
            baseUrl = runtime.agentEndpoint,
            reasoning = true,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 128_000,
            maxTokens = 16_384,
        )

    private suspend fun authorizeTool(
        operation: dev.xuexue.core.AgentOperationKind,
        target: String,
        detail: String,
    ): Boolean {
        val request = AgentApprovalRequest("tool-${System.nanoTime()}", operation, target, detail)
        val deferred = CompletableDeferred<AgentApprovalDecision>()
        if (!requestApproval(request)) return true
        approvalDeferred = deferred
        return deferred.await() != AgentApprovalDecision.DENY
    }

    private suspend fun loadAttachedFiles(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val root = workspaceRoot() ?: return@withContext emptyList()
            runtime.agentContextPaths.take(8).map { relative ->
                val content =
                    runCatching {
                        val path = workspaceService.resolveExistingInside(root, relative)
                        if (
                            Files.isRegularFile(path) &&
                            workspaceService.kindOf(path) in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)
                        ) {
                            workspaceService.readText(path).take(12_000)
                        } else {
                            "[Binary or unavailable file]"
                        }
                    }.getOrDefault("[Could not read file]")
                relative to content
            }
        }

    private suspend fun loadProjectInstructions(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val root = workspaceRoot() ?: return@withContext emptyList()
            listOf("AGENTS.md", "CLAUDE.md").mapNotNull { relative ->
                runCatching {
                    val path = workspaceService.resolveExistingInside(root, relative)
                    if (Files.isRegularFile(path)) relative to workspaceService.readText(path).take(12_000) else null
                }.getOrNull()
            }
        }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are the workspace assistant in XueXue. Be concise, use tools when needed, " +
                "and preserve user files."
    }
}
