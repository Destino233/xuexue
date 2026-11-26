package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.DshConnection
import dev.xuexue.platform.DshRuntime
import dev.xuexue.platform.WorkspaceService
import dev.xuexue.platform.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

internal class WorkbenchAgentController(
    private val scope: CoroutineScope,
    private val state: AgentRuntimeState,
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val activeDocument: () -> DocumentTab?,
    private val revealPanel: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val runtime = DshRuntime()
    private val transcripts = AgentTranscriptStore(workspaceService)
    private var turn: Job? = null
    private var connection: DshConnection? = null
    private var openedSession: String? = null
    private var readOnly = false
    private var allowShell = false
    private var cancelling = false
    private var prompting = false

    fun sendMessage() {
        val root = workspaceRoot() ?: return
        val prompt = state.agentInput.trim()
        val session = state.activeAgentSession
        if (state.agentRunning || state.agentOfflineMode) return
        if (prompt.isBlank() || session.legacy) return
        state.agentRunning = true
        turn = scope.launch(start = CoroutineStart.UNDISPATCHED) { runTurn(root, session, prompt) }
    }

    private suspend fun runTurn(
        root: Path,
        session: AgentSessionState,
        prompt: String,
    ) {
        try {
            val client = connect(root)
            val id =
                withTimeoutOrNull(30_000) { openSession(client, root, session) }
                    ?: throw IOException("DSH did not open the session in time.")
            val content = promptContent(root, prompt)
            if (state.agentInput.trim() == prompt) state.agentInput = ""
            if (session.messages.isEmpty()) session.name = prompt.lineSequence().first().take(60)
            session.messages += AgentMessage("user", prompt)
            val projection = DshTurnProjection(session)
            prompting = true
            val result =
                client.request("session/prompt", sessionParams(id, "prompt", content)) { frame ->
                    val params = frame["params"] as? JsonObject ?: return@request
                    when (frame.text("method")) {
                        "session/update" -> {
                            if (params.text("sessionId") == id) {
                                (params["update"] as? JsonObject)?.let(projection::update)
                            }
                        }

                        "session/request_permission" -> {
                            requestPermission(client, frame, params, projection, id)
                        }
                    }
                }
            onStatus(if (result.text("stopReason") == "cancelled") "Agent stopped" else "Agent ready")
        } catch (error: IOException) {
            val message = error.message ?: "DSH request failed."
            session.messages += AgentMessage("assistant", message, error = true)
            onStatus(message)
            closeConnection()
        } finally {
            withContext(NonCancellable) {
                state.permission = null
                cancelling = false
                prompting = false
                try {
                    transcripts.save(root, state.agentSessions)
                } catch (error: IOException) {
                    onStatus(error.message ?: "Could not save the Agent transcript.")
                } finally {
                    state.agentRunning = false
                }
            }
        }
    }

    fun stop() {
        if (!state.agentRunning || cancelling) return
        cancelling = true
        val client = connection
        val id = openedSession
        val currentTurn = turn
        val promptActive = prompting
        state.permission?.decision?.complete(false)
        scope.launch {
            val sent =
                promptActive && client != null && id != null &&
                    runCatching { client.notify("session/cancel", sessionParams(id)) }.isSuccess
            if (!sent) {
                if (turn == currentTurn && state.agentRunning) {
                    currentTurn?.cancelAndJoin()
                    if (turn == currentTurn) closeConnection()
                }
            }
        }
    }

    suspend fun close(reset: Boolean = true) {
        turn?.cancelAndJoin()
        turn = null
        closeConnection()
        if (reset) {
            state.agentSessions.clear()
            state.agentSessions += AgentSessionState(UUID.randomUUID().toString(), "新会话")
            state.activeAgentSessionId = state.agentSessions.first().id
            state.agentInput = ""
            state.agentContextPaths.clear()
            state.agentContextSnippets.clear()
        }
    }

    suspend fun loadTranscript(
        root: Path,
        selected: String?,
    ) {
        val loaded = transcripts.load(root)
        if (loaded.isEmpty()) return
        state.agentSessions.clear()
        state.agentSessions += loaded
        state.activeAgentSessionId = loaded.firstOrNull { it.id == selected }?.id ?: loaded.first().id
    }

    fun newSession() {
        if (state.agentRunning) return
        val draft =
            state.agentSessions.firstOrNull { !it.legacy && it.dshId == null && it.messages.isEmpty() }
                ?: AgentSessionState(UUID.randomUUID().toString(), "新会话").also { state.agentSessions.add(0, it) }
        state.activeAgentSessionId = draft.id
    }

    fun selectSession(id: String) {
        if (!state.agentRunning && state.agentSessions.any { it.id == id }) state.activeAgentSessionId = id
    }

    fun addContext(relativePath: String) {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.isNotBlank() && normalized !in state.agentContextPaths) state.agentContextPaths += normalized
        revealPanel()
    }

    fun addSnippetContext(
        title: String,
        content: String,
    ) {
        if (content.isBlank()) return
        state.agentContextSnippets += AgentContextSnippet(UUID.randomUUID().toString(), title, content.trim())
        revealPanel()
    }

    private suspend fun connect(root: Path): DshConnection {
        val requestedReadOnly = state.agentReadOnlyMode || !state.workspaceTrusted || !state.agentAllowWrite
        val requestedShell = state.workspaceTrusted && state.agentAllowShell
        connection
            ?.takeIf { it.isAlive && readOnly == requestedReadOnly && allowShell == requestedShell }
            ?.let { return it }
        closeConnection()
        return runtime.connect(scope, root, requestedReadOnly, requestedShell).also {
            connection = it
            readOnly = requestedReadOnly
            allowShell = requestedShell
        }
    }

    private suspend fun closeConnection() {
        val client = connection ?: return
        connection = null
        openedSession = null
        withContext(NonCancellable) { client.close() }
    }

    private suspend fun openSession(
        client: DshConnection,
        root: Path,
        session: AgentSessionState,
    ): String {
        val previous = openedSession
        val saved = session.dshId
        if (previous != null && previous == saved) return previous
        if (previous != null) {
            client.request("session/close", sessionParams(previous))
            openedSession = null
        }
        val params =
            buildJsonObject {
                put("cwd", root.toString())
                put("mcpServers", JsonArray(emptyList()))
                saved?.let { put("sessionId", it) }
            }
        val result = client.request(if (saved == null) "session/new" else "session/resume", params)
        val id = saved ?: result.text("sessionId") ?: throw IOException("DSH did not return a session ID.")
        session.dshId = id
        openedSession = id
        return id
    }

    private suspend fun requestPermission(
        client: DshConnection,
        frame: JsonObject,
        params: JsonObject,
        projection: DshTurnProjection,
        sessionId: String,
    ) {
        val requestId = frame["id"] ?: return
        val call = params["toolCall"] as? JsonObject
        val tool = projection.toolMessage(call?.text("toolCallId"))
        val options = (params["options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val allow = options.firstOrNull { it.text("kind") == "allow_once" }?.text("optionId")
        val reject = options.firstOrNull { it.text("kind") == "reject_once" }?.text("optionId")
        val current = params.text("sessionId") == sessionId && !cancelling
        val outcome =
            if (current && tool != null && allow != null) {
                val permission = AgentPermission(tool.content)
                state.permission = permission
                try {
                    if (permission.decision.await()) allow else reject
                } finally {
                    state.permission = null
                }
            } else {
                null
            }
        client.respond(
            requestId,
            buildJsonObject {
                put(
                    "outcome",
                    buildJsonObject {
                        put("outcome", if (outcome == null) "cancelled" else "selected")
                        outcome?.let { put("optionId", it) }
                    },
                )
            },
        )
    }

    private suspend fun promptContent(
        root: Path,
        prompt: String,
    ): JsonArray {
        val paths = state.agentContextPaths.take(8)
        val snippets = state.agentContextSnippets.takeLast(8)
        val active =
            activeDocument()
                ?.takeIf {
                    state.includeActiveDocument && it.kind in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)
                }?.let { it.relativePath to it.content.take(16_000) }
        val attachments =
            withContext(Dispatchers.IO) {
                paths.map { relative ->
                    val text =
                        runCatching {
                            val path = workspaceService.resolveExistingInside(root, relative)
                            if (workspaceService.kindOf(path) in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)) {
                                workspaceService.readText(path).take(12_000)
                            } else {
                                "[See workspace file]"
                            }
                        }.getOrDefault("[Could not read file]")
                    "Attached workspace file ($relative):\n$text"
                }
            }
        val content =
            buildList {
                add(prompt)
                active?.let { (path, text) -> add("Current document ($path):\n$text") }
                addAll(attachments)
                snippets.forEach { add("Selected context (${it.title}):\n${it.content.take(8_000)}") }
            }.joinToString("\n\n")
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", content)
                },
            )
        }
    }

    private fun sessionParams(
        id: String,
        key: String = "",
        value: JsonArray? = null,
    ) = buildJsonObject {
        put("sessionId", id)
        value?.let { put(key, it) }
    }
}
