// The controller remains a compatibility boundary while the formal Pi runtime replaces the legacy UI state.
@file:Suppress("LongParameterList", "UnusedPrivateProperty", "LargeClass", "LongMethod", "ReturnCount")

package dev.xuexue

import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentApprovalRequest
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.requiresExplicitApproval
import dev.xuexue.pi.agent.Agent
import dev.xuexue.pi.agent.AgentInitialState
import dev.xuexue.pi.agent.AgentOptions
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.ai.ProviderRegistry
import dev.xuexue.pi.ai.defaultProviderRegistry
import dev.xuexue.pi.coding.AgentSession
import dev.xuexue.pi.coding.AgentSessionConfig
import dev.xuexue.pi.coding.AgentSessionRuntime
import dev.xuexue.pi.coding.AgentSessionRuntimeFactory
import dev.xuexue.pi.coding.AgentSessionRuntimeResult
import dev.xuexue.pi.coding.AgentSessionRuntimeServices
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionResponse
import dev.xuexue.pi.coding.LocalBashExecutor
import dev.xuexue.pi.coding.LocalSessionHtmlExporter
import dev.xuexue.pi.coding.ScopedModel
import dev.xuexue.pi.coding.exec.LocalPiCommandExecutor
import dev.xuexue.pi.coding.extensions.PiExtensionContext
import dev.xuexue.pi.coding.extensions.PiExtensionMode
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.PiPluginDiscovery
import dev.xuexue.pi.coding.extensions.PiPluginDiscoveryOptions
import dev.xuexue.pi.coding.extensions.PiPluginScope
import dev.xuexue.pi.coding.extensions.SessionStartReason
import dev.xuexue.pi.coding.packages.DefaultPiPackageManager
import dev.xuexue.pi.coding.packages.PiPackageMissingAction
import dev.xuexue.pi.coding.resources.DefaultResourceLoader
import dev.xuexue.pi.coding.resources.ResourceLoaderOptions
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.settings.SettingsManager
import dev.xuexue.pi.coding.trust.ProjectTrustInteraction
import dev.xuexue.pi.coding.trust.ProjectTrustResolutionOptions
import dev.xuexue.pi.coding.trust.ProjectTrustStore
import dev.xuexue.pi.coding.trust.resolveProjectTrusted
import dev.xuexue.platform.AgentClient
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.AgentProviderConfig
import dev.xuexue.platform.DesktopAgentTools
import dev.xuexue.platform.DesktopPiAgentAdapter
import dev.xuexue.platform.DesktopPiCodingTools
import dev.xuexue.platform.OpenAiCompatibleAgentClient
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
    private val providerRegistry: ProviderRegistry = defaultProviderRegistry(),
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private var job: Job? = null
    private var runId = 0
    private var lastDeletedSession: AgentSessionState? = null
    private var formalRuntime: AgentSessionRuntime? = null
    private val piAgent: Agent
        get() = formalRuntime?.session?.agent ?: runtime.piAgent
    private val streamFunction =
        if (client is OpenAiCompatibleAgentClient) {
            DesktopPiAgentAdapter(scope, providerRegistry)
        } else {
            DesktopPiAgentAdapter(scope, client)
        }
    private var approvalDeferred: CompletableDeferred<AgentApprovalDecision>? = null
    private var trustBootstrapCwd: String? = null
    private var trustBootstrapSettings: SettingsManager? = null
    private var trustBootstrapRunner: PiExtensionRunner? = null

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
            formalRuntime?.session?.steer(prompt) ?: piAgent.steer(coreUserMessage(prompt))
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
        formalRuntime?.session?.clearQueue() ?: piAgent.clearAllQueues()
        runtime.interactionBroker.cancelAll("agent stopped")
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

    fun respondInteraction(response: InteractionResponse): Boolean = runtime.respondAgentInteraction(response)

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
        resetFormalRuntime()
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
        resetFormalRuntime()
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
        resetFormalRuntime()
        runtime.cloneSession(fork)
        persistTranscript()
        persistWorkbench()
    }

    fun deleteSession(id: String) {
        if (runtime.agentSessions.none { it.id == id }) return
        if (id == runtime.activeAgentSessionId) {
            if (piAgent.currentState.isStreaming) stop()
            resetFormalRuntime()
        }
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
                val cwd = workspaceRoot()?.toString() ?: "."
                val needsFormalSession = formalRuntime?.session?.sessionId != session.id
                val trusted =
                    if (needsFormalSession) {
                        ensureProjectTrust(cwd, agentDirectory().toString())
                    } else {
                        runtime.workspaceTrusted
                    }
                if (!trusted) onStatus("Project resources are disabled until this workspace is trusted")
                val attachedFiles = loadAttachedFiles()
                val projectInstructions = if (trusted) loadProjectInstructions() else emptyList()
                val model = agentModel()
                val systemPrompt = contextPrompt(attachedFiles, projectInstructions)
                val formalSession = configureAgent(session, model, systemPrompt)
                formalRuntime?.start(SessionStartReason.STARTUP)
                formalSession.agent.setSystemPrompt(systemPrompt)
                val projection = WorkbenchAgentProjection(session, assistantId)
                val subscription = formalSession.subscribe { event -> projection.apply(event) }
                runCatching { formalSession.prompt(prompt) }.onFailure { throwable ->
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
                session.protocolMessages += agentMessagesToLegacyProtocol(formalSession.messages)
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

    private suspend fun configureAgent(
        session: AgentSessionState,
        model: Model,
        systemPrompt: String,
    ): AgentSession {
        val adaptedTools = DesktopPiCodingTools(workspaceService, workspaceRoot, ::authorizeTool, Dispatchers.IO).all()
        val current = formalRuntime?.session
        if (current == null || current.sessionId != session.id) {
            resetFormalRuntime()
            val sessionManager = createFormalSessionManager(session.id)
            val initialSession =
                createFormalSession(
                    session = session,
                    model = model,
                    systemPrompt = systemPrompt,
                    tools = adaptedTools,
                    sessionManager = sessionManager,
                )
            formalRuntime =
                AgentSessionRuntime(
                    initial =
                        AgentSessionRuntimeResult(
                            session = initialSession,
                            services =
                                AgentSessionRuntimeServices(
                                    cwd = sessionManager.getCwd(),
                                    agentDirectory = workspaceRoot()?.resolve(".xuexue/pi")?.toString().orEmpty(),
                                ),
                        ),
                    factory =
                        AgentSessionRuntimeFactory { options ->
                            val nextSession =
                                createFormalSession(
                                    session = session,
                                    model = agentModel(),
                                    systemPrompt = systemPrompt,
                                    tools = adaptedTools,
                                    sessionManager = options.sessionManager,
                                    modelRegistry = options.modelRegistry,
                                    scopedModels = options.scopedModels,
                                )
                            AgentSessionRuntimeResult(
                                session = nextSession,
                                services =
                                    AgentSessionRuntimeServices(
                                        cwd = options.cwd,
                                        agentDirectory = options.agentDirectory,
                                    ),
                            )
                        },
                )
            if (client is OpenAiCompatibleAgentClient) runtime.bindFormalRuntime(formalRuntime, scope)
        }
        val formalSession = requireNotNull(formalRuntime).session
        formalSession.agent.setSystemPrompt(systemPrompt)
        formalSession.agent.setModel(model)
        formalSession.agent.setTools(adaptedTools)
        formalSession.agent.replaceMessages(legacyProtocolToAgentMessages(session.protocolMessages.takeLast(80), model))
        formalSession.agent.streamFunction =
            if (client is OpenAiCompatibleAgentClient) {
                DesktopPiAgentAdapter(
                    scope,
                    providerRegistry.withAdditionalProviders(
                        formalSession.getRegisteredExtensionProviders(),
                        formalSession.getRegisteredExtensionImageProviders(),
                    ),
                )
            } else {
                streamFunction
            }
        formalSession.agent.getApiKey = { runtime.agentApiKey }
        formalSession.agent.afterToolCall = { context, _ -> desktopToolAfterCall(context) }
        formalSession.agent.sessionId = session.id
        return formalSession
    }

    private suspend fun createFormalSession(
        session: AgentSessionState,
        model: Model,
        systemPrompt: String,
        tools: List<dev.xuexue.pi.agent.AgentTool>,
        sessionManager: SessionManager,
        modelRegistry: ModelRegistry? = null,
        scopedModels: List<ScopedModel> = emptyList(),
    ): AgentSession {
        val cwd = sessionManager.getCwd()
        val agentDirectory = agentDirectory()
        val settingsManager =
            trustBootstrapSettings?.takeIf { trustBootstrapCwd == cwd }
                ?: SettingsManager.create(
                    cwd = cwd,
                    agentDirectory = agentDirectory.toString(),
                    projectTrusted = runtime.workspaceTrusted,
                )
        val packageManager =
            DefaultPiPackageManager(
                cwd = cwd,
                agentDirectory = agentDirectory.toString(),
                settingsManager = settingsManager,
            )
        val packageResolution =
            packageManager.resolve { PiPackageMissingAction.SKIP }
        val pluginDiscovery =
            PiPluginDiscovery(
                PiPluginDiscoveryOptions.fromSettings(
                    cwd = cwd,
                    agentDirectory = agentDirectory.toString(),
                    settingsManager = settingsManager,
                    projectTrusted = settingsManager.isProjectTrusted(),
                    additionalPaths = packageResolution.extensionPaths.map { it.path },
                    scopesToLoad =
                        if (trustBootstrapRunner == null) {
                            PiPluginScope.entries.toSet()
                        } else {
                            setOf(PiPluginScope.PROJECT, PiPluginScope.TEMPORARY)
                        },
                ),
            )
        val discoveredPlugins = pluginDiscovery.loadAll()
        val extensionRunner =
            trustBootstrapRunner
                ?: PiExtensionRunner(cwd, commandExecutor = LocalPiCommandExecutor())
        discoveredPlugins.extensions.forEach { extension -> extensionRunner.load(extension) }
        trustBootstrapCwd = null
        trustBootstrapSettings = null
        trustBootstrapRunner = null
        val resourceLoader =
            DefaultResourceLoader(
                ResourceLoaderOptions(
                    cwd = cwd,
                    agentDirectory = agentDirectory.toString(),
                    settingsManager = settingsManager,
                ),
            )
        val agent =
            Agent(
                AgentOptions(
                    initialState =
                        AgentInitialState(
                            systemPrompt = systemPrompt,
                            model = model,
                            tools = tools,
                            messages = legacyProtocolToAgentMessages(session.protocolMessages.takeLast(80), model),
                        ),
                    streamFunction = streamFunction,
                ),
            )
        agent.getApiKey = { runtime.agentApiKey }
        agent.afterToolCall = { context, _ -> desktopToolAfterCall(context) }
        agent.sessionId = session.id
        return AgentSession(
            AgentSessionConfig(
                agent = agent,
                sessionManager = sessionManager,
                modelRegistry = modelRegistry,
                scopedModels = scopedModels,
                settingsManager = settingsManager,
                resourceLoader = resourceLoader,
                extensionRunner = extensionRunner,
                packageManager = packageManager,
                bashExecutor = LocalBashExecutor(),
                htmlExporter = LocalSessionHtmlExporter(),
                interactionPort = runtime.interactionBroker,
            ),
        )
    }

    private fun agentDirectory(): Path =
        Path
            .of(System.getProperty("user.home"), ".xuexue", "pi")
            .toAbsolutePath()
            .normalize()

    private suspend fun ensureProjectTrust(
        cwd: String,
        agentDirectory: String,
    ): Boolean {
        if (!runtime.workspaceTrusted) {
            trustBootstrapRunner?.dispose()
            trustBootstrapCwd = null
            trustBootstrapSettings = null
            trustBootstrapRunner = null
            return false
        }
        if (trustBootstrapCwd == cwd && trustBootstrapSettings != null && trustBootstrapRunner != null) return true

        trustBootstrapRunner?.dispose()
        val settingsManager = SettingsManager.create(cwd, agentDirectory, projectTrusted = false)
        val discovered =
            PiPluginDiscovery(
                PiPluginDiscoveryOptions.fromSettings(
                    cwd = cwd,
                    agentDirectory = agentDirectory,
                    settingsManager = settingsManager,
                    projectTrusted = false,
                    scopesToLoad = setOf(PiPluginScope.USER),
                ),
            ).loadAll()
        val extensionRunner = PiExtensionRunner(cwd, commandExecutor = LocalPiCommandExecutor())
        discovered.extensions.forEach { extension -> extensionRunner.load(extension) }
        val trusted =
            resolveProjectTrusted(
                ProjectTrustResolutionOptions(
                    cwd = cwd,
                    trustStore = ProjectTrustStore.create(agentDirectory),
                    defaultProjectTrust = settingsManager.getDefaultProjectTrust(),
                    extensionRunner = extensionRunner,
                    extensionContext =
                        PiExtensionContext(
                            cwd = cwd,
                            mode = PiExtensionMode.RPC,
                            isProjectTrusted = settingsManager::isProjectTrusted,
                            interaction = runtime.interactionBroker,
                        ),
                    interaction =
                        ProjectTrustInteraction { title, options ->
                            val request =
                                InteractionRequest.Select(
                                    id = "project-trust-${System.nanoTime()}",
                                    title = title,
                                    message =
                                        "Choose whether this workspace may load project resources and extensions.",
                                    options = options,
                                )
                            when (val response = runtime.interactionBroker.request(request)) {
                                is InteractionResponse.Select -> response.selectedIndex
                                else -> null
                            }
                        },
                ),
            )
        if (!trusted) {
            extensionRunner.dispose()
            runtime.workspaceTrusted = false
            trustBootstrapCwd = null
            trustBootstrapSettings = null
            trustBootstrapRunner = null
            return false
        }
        settingsManager.setProjectTrusted(true)
        trustBootstrapCwd = cwd
        trustBootstrapSettings = settingsManager
        trustBootstrapRunner = extensionRunner
        return true
    }

    private fun createFormalSessionManager(sessionId: String): SessionManager {
        val root = workspaceRoot() ?: return SessionManager.inMemory(".")
        val sessionDirectory = root.resolve(".xuexue/pi/sessions")
        val sessionPath = sessionDirectory.resolve("$sessionId.jsonl")
        return if (Files.isRegularFile(sessionPath)) {
            SessionManager.open(
                path = sessionPath.toString(),
                sessionDir = sessionDirectory.toString(),
                cwdOverride = root.toString(),
            )
        } else {
            SessionManager.create(root.toString(), sessionDirectory.toString()).also {
                it.setSessionFile(sessionPath.toString())
            }
        }
    }

    private fun resetFormalRuntime() {
        val oldRuntime = formalRuntime ?: return
        runtime.bindFormalRuntime(null, scope)
        formalRuntime = null
        runtime.interactionBroker.cancelAll("session replaced")
        scope.launch { oldRuntime.dispose() }
    }

    private fun agentModel(): Model =
        Model(
            id = runtime.agentModel,
            name = runtime.agentModel,
            api = "openai-completions",
            provider = "openai",
            baseUrl = normalizeProviderBaseUrl(runtime.agentEndpoint),
            reasoning = true,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 128_000,
            maxTokens = 16_384,
        )

    private fun normalizeProviderBaseUrl(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        return listOf(
            "/chat/completions",
            "/responses",
            "/v1/messages",
        ).firstNotNullOfOrNull { suffix ->
            trimmed
                .takeIf { it.endsWith(suffix) }
                ?.removeSuffix(suffix)
                ?.trimEnd('/')
        } ?: trimmed
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
