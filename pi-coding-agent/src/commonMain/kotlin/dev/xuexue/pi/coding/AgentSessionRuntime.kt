@file:Suppress("InjectDispatcher")

package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.coding.extensions.SessionShutdownReason
import dev.xuexue.pi.coding.extensions.SessionStartReason
import dev.xuexue.pi.coding.session.NewSessionOptions
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class AgentSessionRuntimeServices(
    val cwd: String,
    val agentDirectory: String,
)

data class AgentSessionRuntimeResult(
    val session: AgentSession,
    val services: AgentSessionRuntimeServices,
    val diagnostics: List<String> = emptyList(),
)

data class AgentSessionRuntimeCreateOptions(
    val cwd: String,
    val agentDirectory: String,
    val sessionManager: SessionManager,
    val reason: SessionStartReason,
    val previousSessionFile: String?,
    val modelRegistry: ModelRegistry? = null,
    val scopedModels: List<ScopedModel> = emptyList(),
)

fun interface AgentSessionRuntimeFactory {
    suspend fun create(options: AgentSessionRuntimeCreateOptions): AgentSessionRuntimeResult
}

enum class SessionForkPosition {
    BEFORE,
    AT,
}

data class SessionForkResult(
    val cancelled: Boolean,
    val selectedText: String? = null,
)

/** Owns one AgentSession and replaces it through a single lifecycle path. */
class AgentSessionRuntime(
    initial: AgentSessionRuntimeResult,
    private val factory: AgentSessionRuntimeFactory,
) {
    private var current = initial
    private var started = false
    private var disposed = false
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(initial.session.state.value)
    private val mutableEvents = MutableSharedFlow<AgentSessionEvent>(extraBufferCapacity = 256)
    private var sessionSubscription: AgentSessionSubscription? = null

    init {
        bindSession(initial.session)
    }

    val session: AgentSession
        get() = current.session

    val services: AgentSessionRuntimeServices
        get() = current.services

    val diagnostics: List<String>
        get() = current.diagnostics

    /** The single state source consumed by Compose and RPC hosts. */
    val state: StateFlow<AgentSessionState> = mutableState

    /** Events are rebound atomically whenever a session is replaced. */
    val events: SharedFlow<AgentSessionEvent> = mutableEvents

    suspend fun start(
        reason: SessionStartReason = SessionStartReason.STARTUP,
        previousSessionFile: String? = null,
    ) {
        check(!disposed) { "AgentSessionRuntime is disposed" }
        if (started) return
        session.startExtensions(reason, previousSessionFile)
        started = true
    }

    suspend fun newSession(
        parentSession: String? = null,
    ): Boolean {
        checkStarted()
        val previousSessionFile = session.sessionFile
        val nextManager =
            if (session.sessionManager.isPersisted()) {
                SessionManager.create(session.servicesCwd(), session.sessionManager.getSessionDir())
            } else {
                SessionManager.inMemory(session.servicesCwd())
            }
        if (parentSession != null) nextManager.newSession(NewSessionOptions(parentSession = parentSession))
        if (session.beforeSessionSwitch("new", nextManager.getSessionFile())) return true
        replace(
            nextManager,
            reason = SessionStartReason.NEW,
            shutdownReason = SessionShutdownReason.NEW,
            previousSessionFile = previousSessionFile,
        )
        return false
    }

    suspend fun switchSession(
        sessionPath: String,
        cwdOverride: String? = null,
    ): Boolean {
        checkStarted()
        val nextManager =
            SessionManager.open(
                path = sessionPath,
                sessionDir = session.sessionManager.getSessionDir(),
                cwdOverride = cwdOverride,
            )
        val previousSessionFile = session.sessionFile
        if (session.beforeSessionSwitch("resume", sessionPath)) return true
        replace(
            nextManager,
            reason = SessionStartReason.RESUME,
            shutdownReason = SessionShutdownReason.RESUME,
            previousSessionFile = previousSessionFile,
        )
        return false
    }

    suspend fun fork(
        entryId: String,
        position: SessionForkPosition = SessionForkPosition.BEFORE,
    ): SessionForkResult {
        checkStarted()
        val selectedEntry = session.sessionManager.getEntry(entryId) ?: error("Invalid entry ID for forking")
        val targetLeafId: String?
        val selectedText: String?
        when (position) {
            SessionForkPosition.AT -> {
                targetLeafId = selectedEntry.id
                selectedText = null
            }

            SessionForkPosition.BEFORE -> {
                val message = selectedEntry as? SessionMessageEntry ?: error("Invalid entry ID for forking")
                require(message.message is dev.xuexue.pi.ai.UserMessage) {
                    "Fork-before requires a user message entry"
                }
                targetLeafId = selectedEntry.parentId
                selectedText = message.message.userTextForRuntime()
            }
        }

        val previousSessionFile = session.sessionFile
        if (session.beforeSessionFork(entryId, position.name.lowercase())) {
            return SessionForkResult(cancelled = true)
        }
        val nextManager =
            if (session.sessionManager.isPersisted()) {
                val currentPath = requireNotNull(session.sessionFile) { "Persisted session is missing a session file" }
                SessionManager.open(currentPath, session.sessionManager.getSessionDir()).also { manager ->
                    if (targetLeafId == null) {
                        manager.newSession(NewSessionOptions(parentSession = currentPath))
                    } else {
                        manager.createBranchedSession(targetLeafId)
                    }
                }
            } else {
                session.sessionManager.also { manager ->
                    if (targetLeafId == null) {
                        manager.newSession(NewSessionOptions(parentSession = session.sessionFile))
                    } else {
                        manager.createBranchedSession(targetLeafId)
                    }
                }
            }
        replace(
            nextManager,
            reason = SessionStartReason.FORK,
            shutdownReason = SessionShutdownReason.FORK,
            previousSessionFile = previousSessionFile,
        )
        return SessionForkResult(cancelled = false, selectedText = selectedText)
    }

    suspend fun navigateTree(
        targetId: String,
        options: NavigateTreeOptions = NavigateTreeOptions(),
    ): NavigateTreeResult {
        checkStarted()
        return session.navigateTree(targetId, options)
    }

    suspend fun dispose() {
        if (disposed) return
        disposed = true
        sessionSubscription?.dispose()
        sessionSubscription = null
        session.shutdown(SessionShutdownReason.QUIT)
        runtimeScope.cancel()
    }

    private suspend fun replace(
        nextManager: SessionManager,
        reason: SessionStartReason,
        shutdownReason: SessionShutdownReason,
        previousSessionFile: String?,
    ) {
        val targetSessionFile = nextManager.getSessionFile()
        session.shutdown(shutdownReason, targetSessionFile)
        val result =
            factory.create(
                AgentSessionRuntimeCreateOptions(
                    cwd = nextManager.getCwd(),
                    agentDirectory = services.agentDirectory,
                    sessionManager = nextManager,
                    reason = reason,
                    previousSessionFile = previousSessionFile,
                    modelRegistry = session.modelRegistry,
                    scopedModels = session.scopedModels,
                ),
            )
        current = result
        bindSession(result.session)
        session.startExtensions(reason, previousSessionFile)
    }

    private fun bindSession(next: AgentSession) {
        sessionSubscription?.dispose()
        sessionSubscription =
            next.subscribe { event ->
                mutableState.value = next.state.value
                mutableEvents.tryEmit(event)
            }
        mutableState.value = next.state.value
    }

    private fun checkStarted() {
        check(!disposed) { "AgentSessionRuntime is disposed" }
        check(started) { "AgentSessionRuntime has not been started" }
    }
}

private fun AgentSession.servicesCwd(): String = sessionManager.getCwd()

private fun dev.xuexue.pi.ai.UserMessage.userTextForRuntime(): String =
    when (val value = content) {
        is dev.xuexue.pi.ai.UserText -> {
            value.value
        }

        is dev.xuexue.pi.ai.UserBlocks -> {
            value.value
                .filterIsInstance<dev.xuexue.pi.ai.TextContent>()
                .joinToString("") { it.text }
        }
    }
