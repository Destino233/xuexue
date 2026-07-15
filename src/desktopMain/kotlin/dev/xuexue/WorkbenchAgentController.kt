package dev.xuexue

import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.PiAgentMessage
import dev.xuexue.core.PiAgentModel
import dev.xuexue.core.PiAgentRuntime
import dev.xuexue.core.requiresExplicitApproval
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.DesktopAgentTools
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val piRuntime = PiAgentRuntime()
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
        if (runtime.agentRunning) {
            runtime.agentMessages += AgentMessage("steering_queued", prompt)
            onStatus("Steering queued")
            persistTranscript()
            return
        }
        runtime.agentMessages += AgentMessage("user", prompt)
        runtime.activeAgentSession.protocolMessages += PiAgentMessage("user", prompt)
        startTurn()
    }

    fun stop() {
        runId++
        job?.cancel()
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
        if (runtime.agentRunning) stop()
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
        if (runtime.agentRunning) stop()
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
        if (runtime.agentRunning) stop()
        runtime.cloneSession(fork)
        persistTranscript()
        persistWorkbench()
    }

    fun deleteSession(id: String) {
        if (runtime.agentSessions.none { it.id == id }) return
        if (runtime.agentRunning && id == runtime.activeAgentSessionId) stop()
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

    private fun startTurn() {
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
                val contextMessages = contextMessages(attachedFiles, projectInstructions)
                runCatching {
                    val result = runPiAgent(session, contextMessages, assistantId)
                    session.protocolMessages.clear()
                    session.protocolMessages += result.drop(contextMessages.size)
                }.onFailure { throwable ->
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
                if (runId == currentRunId) {
                    runtime.agentRunning = false
                    val queuedIndex = sessionMessages.indexOfFirst { it.role == "steering_queued" }
                    if (queuedIndex >= 0) {
                        sessionMessages[queuedIndex] = sessionMessages[queuedIndex].copy(role = "steering")
                        onStatus("Queued steering started")
                        persistTranscript()
                        startTurn()
                    } else {
                        persistTranscript()
                    }
                }
            }
    }

    private fun contextMessages(
        attachedFiles: List<Pair<String, String>>,
        projectInstructions: List<Pair<String, String>>,
    ): List<PiAgentMessage> =
        buildList {
            add(PiAgentMessage("system", SYSTEM_PROMPT))
            if (runtime.includeActiveDocument) {
                activeDocument()?.takeIf { it.content.isNotBlank() }?.let { tab ->
                    val content = "Current document (${tab.relativePath}):\n${tab.content.take(16_000)}"
                    add(PiAgentMessage("system", content))
                }
            }
            attachedFiles.forEach { (relative, content) ->
                add(PiAgentMessage("system", "Attached workspace file ($relative):\n$content"))
            }
            projectInstructions.forEach { (relative, content) ->
                add(PiAgentMessage("system", "Project instructions ($relative):\n$content"))
            }
            runtime.agentContextSnippets.takeLast(8).forEach { snippet ->
                add(
                    PiAgentMessage(
                        "system",
                        "Attached selected context (${snippet.title}):\n${snippet.content.take(8_000)}",
                    ),
                )
            }
        }

    private suspend fun runPiAgent(
        session: AgentSessionState,
        contextMessages: List<PiAgentMessage>,
        assistantId: String,
    ): List<PiAgentMessage> =
        piRuntime.run(
            initialMessages = contextMessages + session.protocolMessages.takeLast(80),
            model = agentModel(),
            tools = DesktopAgentTools(workspaceService, workspaceRoot, ::authorizeTool, Dispatchers.IO).all(),
            onEvent = { event ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runtime.applyStreamEvent(session, assistantId, event)
                }
            },
            drainSteering = { drainQueuedSteering(session) },
        )

    private fun agentModel(): PiAgentModel =
        object : PiAgentModel {
            override suspend fun complete(
                messages: List<PiAgentMessage>,
                tools: List<dev.xuexue.core.PiToolDefinition>,
                onEvent: (dev.xuexue.core.AgentStreamEvent) -> Unit,
            ) = client.complete(
                messages,
                tools,
                AgentProviderConfig(runtime.agentEndpoint, runtime.agentModel, runtime.agentApiKey),
                onEvent,
            )
        }

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

    private fun drainQueuedSteering(session: AgentSessionState): List<PiAgentMessage> {
        val queued = session.messages.filter { it.role == "steering_queued" }
        queued.forEach { message ->
            val index = session.messages.indexOf(message)
            if (index >= 0) session.messages[index] = message.copy(role = "steering")
        }
        return queued.map { message -> PiAgentMessage("user", message.content) }
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
