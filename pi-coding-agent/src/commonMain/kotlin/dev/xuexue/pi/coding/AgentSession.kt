@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AfterToolCallResult
import dev.xuexue.pi.agent.Agent
import dev.xuexue.pi.agent.AgentEvent
import dev.xuexue.pi.agent.AgentSubscription
import dev.xuexue.pi.agent.BeforeToolCallResult
import dev.xuexue.pi.agent.QueueMode
import dev.xuexue.pi.agent.UNKNOWN_AGENT_MODEL
import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserContentBlock
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.calculateContextTokens
import dev.xuexue.pi.ai.clampThinkingLevel
import dev.xuexue.pi.ai.cleanupSessionResources
import dev.xuexue.pi.ai.getSupportedThinkingLevels
import dev.xuexue.pi.ai.isContextOverflow
import dev.xuexue.pi.ai.isRetryableAssistantError
import dev.xuexue.pi.ai.modelsAreEqual
import dev.xuexue.pi.coding.compaction.CompactionDetails
import dev.xuexue.pi.coding.compaction.CompactionPreparation
import dev.xuexue.pi.coding.compaction.CompactionRequest
import dev.xuexue.pi.coding.compaction.CompactionResult
import dev.xuexue.pi.coding.compaction.GenerateBranchSummaryOptions
import dev.xuexue.pi.coding.compaction.collectEntriesForBranchSummary
import dev.xuexue.pi.coding.compaction.estimateContextTokens
import dev.xuexue.pi.coding.compaction.estimateTokens
import dev.xuexue.pi.coding.compaction.generateBranchSummary
import dev.xuexue.pi.coding.compaction.prepareCompaction
import dev.xuexue.pi.coding.compaction.shouldCompact
import dev.xuexue.pi.coding.extensions.PiExtensionContext
import dev.xuexue.pi.coding.extensions.PiExtensionEvent
import dev.xuexue.pi.coding.extensions.PiExtensionEventResult
import dev.xuexue.pi.coding.extensions.PiExtensionEventType
import dev.xuexue.pi.coding.extensions.PiExtensionMode
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.PiModLoader
import dev.xuexue.pi.coding.extensions.ProjectTrustDecision
import dev.xuexue.pi.coding.extensions.ResourceDiscoverReason
import dev.xuexue.pi.coding.extensions.SessionApi
import dev.xuexue.pi.coding.extensions.SessionShutdownReason
import dev.xuexue.pi.coding.extensions.SessionStartReason
import dev.xuexue.pi.coding.extensions.TreePreparation
import dev.xuexue.pi.coding.packages.PiPackageManager
import dev.xuexue.pi.coding.resources.ResourceDiagnostic
import dev.xuexue.pi.coding.resources.SystemPromptOptions
import dev.xuexue.pi.coding.resources.buildSystemPrompt
import dev.xuexue.pi.coding.resources.expandPromptTemplate
import dev.xuexue.pi.coding.resources.stripFrontmatter
import dev.xuexue.pi.coding.session.BashExecutionMessage
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.CustomMessage
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionManager
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Instant
import dev.xuexue.pi.coding.compaction.compact as compactContext

private data class ActiveSessionRun(
    val completion: CompletableDeferred<Unit>,
)

private fun CustomMessageDelivery?.toStreamingBehavior(): StreamingBehavior? =
    when (this) {
        CustomMessageDelivery.STEER -> StreamingBehavior.STEER

        CustomMessageDelivery.FOLLOW_UP -> StreamingBehavior.FOLLOW_UP

        CustomMessageDelivery.NEXT_TURN,
        null,
        -> null
    }

private data class ExtensionStartResult(
    val messages: List<CustomMessage>,
    val systemPrompt: String?,
)

private data class SummaryRequestAuth(
    val apiKey: String,
    val headers: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
)

private sealed interface TreeSummaryResolution {
    data object Cancelled : TreeSummaryResolution

    data object Aborted : TreeSummaryResolution

    data class Ready(
        val summaryText: String?,
        val summaryDetails: kotlinx.serialization.json.JsonElement?,
        val fromExtension: Boolean,
        val label: String?,
    ) : TreeSummaryResolution
}

/** Headless Pi coding-agent session facade. */
@Suppress("TooManyFunctions", "LargeClass")
class AgentSession(
    config: AgentSessionConfig,
) {
    val agent: Agent = config.agent
    val sessionManager: SessionManager = config.sessionManager
    val settingsManager: AgentSessionSettings = config.settingsManager
    val modelRegistry: ModelRegistry?
        get() = configuredModelRegistry
    private val configuredModelRegistry = config.modelRegistry
    private var scopedModelEntries: List<ScopedModel> = config.scopedModels.toList()
    private val resourceLoader = config.resourceLoader
    private val extensionRunner: PiExtensionRunner? = config.extensionRunner
    private val modLoader: PiModLoader? = config.modLoader
    private val packageManager: PiPackageManager? = config.packageManager
    private val bashExecutor: AgentSessionBashExecutor? = config.bashExecutor
    private val htmlExporter: AgentSessionHtmlExporter? = config.htmlExporter
    private val extensionMode: PiExtensionMode = config.extensionMode
    private val interactionPort: InteractionPort = config.interactionPort
    private val systemPromptDocumentation = config.systemPromptDocumentation
    private val currentDate = config.currentDate

    private val listeners = AtomicReference<List<AgentSessionEventListener>>(emptyList())
    private val steeringMessages = AtomicReference<List<String>>(emptyList())
    private val followUpMessages = AtomicReference<List<String>>(emptyList())
    private val pendingNextTurnMessages = AtomicReference<List<CustomMessage>>(emptyList())
    private val pendingBashMessages = AtomicReference<List<BashExecutionMessage>>(emptyList())
    private val lastAssistantMessage = AtomicReference<AssistantMessage?>(null)
    private val retryAbortController = AtomicReference<AbortController?>(null)
    private val compactionAbortController = AtomicReference<AbortController?>(null)
    private val autoCompactionAbortController = AtomicReference<AbortController?>(null)
    private val branchSummaryAbortController = AtomicReference<AbortController?>(null)
    private val overflowRecoveryAttempted = AtomicBoolean(false)
    private val retryAttemptState = AtomicInt(0)
    private val activeRun = AtomicReference<ActiveSessionRun?>(null)
    private val disposed = AtomicBoolean(false)
    private val mutableEvents = MutableSharedFlow<AgentSessionEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val mutableState = MutableStateFlow(config.initialState())
    private val agentSubscription = AtomicReference<AgentSubscription?>(null)

    val events: SharedFlow<AgentSessionEvent> = mutableEvents.asSharedFlow()
    val state: StateFlow<AgentSessionState> = mutableState.asStateFlow()

    init {
        resourceLoader?.let { loader ->
            extensionRunner?.getContributedResources()?.let(loader::extendResources)
            loader.reload()
        }
        installExtensionTools()
        installExtensionHooks()
        rebuildSystemPrompt()
        refreshState()
        reconnectToAgent()
        modLoader?.bindSession(createModSessionApi())
    }

    val currentState: AgentSessionState
        get() = state.value

    val model: Model
        get() = agent.currentState.model

    val thinkingLevel: ModelThinkingLevel
        get() = agent.currentState.thinkingLevel

    val isStreaming: Boolean
        get() = activeRun.load() != null

    val isIdle: Boolean
        get() = !isStreaming

    val systemPrompt: String
        get() = agent.currentState.systemPrompt

    val messages: List<AgentMessage>
        get() = agent.currentState.messages

    val steeringMode: QueueMode
        get() = agent.steeringMode

    val followUpMode: QueueMode
        get() = agent.followUpMode

    val sessionFile: String?
        get() = sessionManager.getSessionFile()

    val sessionId: String
        get() = sessionManager.getSessionId()

    val sessionName: String?
        get() = sessionManager.getSessionName()

    val pendingMessageCount: Int
        get() = steeringMessages.load().size + followUpMessages.load().size

    val hasPendingBashMessages: Boolean
        get() = pendingBashMessages.load().isNotEmpty()

    val retryAttempt: Int
        get() = retryAttemptState.load()

    val isRetrying: Boolean
        get() = retryAbortController.load() != null

    val autoRetryEnabled: Boolean
        get() = settingsManager.getRetrySettings().enabled

    val isCompacting: Boolean
        get() =
            compactionAbortController.load() != null ||
                autoCompactionAbortController.load() != null ||
                branchSummaryAbortController.load() != null

    val autoCompactionEnabled: Boolean
        get() = settingsManager.getCompactionSettings().enabled

    /** Models cycled by Pi's scoped model selector, in the configured order. */
    val scopedModels: List<ScopedModel>
        get() = scopedModelEntries

    fun subscribe(listener: AgentSessionEventListener): AgentSessionSubscription {
        addListener(listener)
        return AgentSessionSubscription { removeListener(listener) }
    }

    suspend fun prompt(
        text: String,
        options: PromptOptions = PromptOptions(),
    ) {
        val expandedText = if (options.expandPromptTemplates) expandPromptText(text) else text
        if (isStreaming) {
            val behavior = options.streamingBehavior
            if (behavior == null) {
                options.preflightResult?.invoke(false)
                error(STREAMING_PROMPT_ERROR)
            }
            when (behavior) {
                StreamingBehavior.STEER -> queueSteering(expandedText, options.images)
                StreamingBehavior.FOLLOW_UP -> queueFollowUp(expandedText, options.images)
            }
            options.preflightResult?.invoke(true)
            return
        }

        flushPendingBashMessages()
        if (model == UNKNOWN_AGENT_MODEL) {
            options.preflightResult?.invoke(false)
            error("No model selected.")
        }
        findLastAssistantMessage()?.let { checkCompaction(it, skipAbortedCheck = false) }
        val extensionStart = extensionBeforeAgentStart(expandedText)
        val baseSystemPrompt = agent.currentState.systemPrompt
        val messages =
            buildList<AgentMessage> {
                add(userMessage(expandedText, options.images))
                addAll(drainPendingNextTurnMessages())
                addAll(extensionStart.messages)
            }

        options.preflightResult?.invoke(true)
        if (extensionStart.systemPrompt != null) agent.setSystemPrompt(extensionStart.systemPrompt)
        try {
            runAgentPrompt(messages)
        } finally {
            if (extensionStart.systemPrompt != null) agent.setSystemPrompt(baseSystemPrompt)
        }
    }

    fun steer(
        text: String,
        images: List<ImageContent> = emptyList(),
    ) {
        queueSteering(expandPromptText(text), images)
    }

    fun followUp(
        text: String,
        images: List<ImageContent> = emptyList(),
    ) {
        queueFollowUp(expandPromptText(text), images)
    }

    private fun queueSteering(
        text: String,
        images: List<ImageContent>,
    ) {
        appendAtomic(steeringMessages, text)
        emitQueueUpdate()
        agent.steer(userMessage(text, images))
    }

    private fun queueFollowUp(
        text: String,
        images: List<ImageContent>,
    ) {
        appendAtomic(followUpMessages, text)
        emitQueueUpdate()
        agent.followUp(userMessage(text, images))
    }

    suspend fun sendUserMessage(
        content: String,
        deliverAs: StreamingBehavior? = null,
    ) {
        prompt(
            content,
            PromptOptions(streamingBehavior = deliverAs),
        )
    }

    suspend fun sendUserMessage(
        content: List<UserContentBlock>,
        deliverAs: StreamingBehavior? = null,
    ) {
        val text = content.filterIsInstance<TextContent>().joinToString("\n", transform = TextContent::text)
        val images = content.filterIsInstance<ImageContent>()
        prompt(
            text,
            PromptOptions(
                images = images,
                streamingBehavior = deliverAs,
            ),
        )
    }

    suspend fun sendCustomMessage(
        message: CustomMessageInput,
        options: CustomMessageOptions = CustomMessageOptions(),
    ) {
        val appMessage =
            CustomMessage(
                customType = message.customType,
                content = message.content,
                display = message.display,
                details = message.details,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        when {
            options.deliverAs == CustomMessageDelivery.NEXT_TURN -> {
                appendAtomic(pendingNextTurnMessages, appMessage)
            }

            isStreaming -> {
                if (options.deliverAs == CustomMessageDelivery.FOLLOW_UP) {
                    agent.followUp(appMessage)
                } else {
                    agent.steer(appMessage)
                }
            }

            options.triggerTurn -> {
                runAgentPrompt(listOf(appMessage))
            }

            else -> {
                agent.appendMessage(appMessage)
                sessionManager.appendCustomMessageEntry(
                    message.customType,
                    message.content,
                    message.display,
                    message.details,
                )
                refreshState()
                emitEvent(AgentSessionEvent.MessageStart(appMessage))
                emitEvent(AgentSessionEvent.MessageEnd(appMessage))
            }
        }
    }

    fun clearQueue(): ClearedAgentQueue {
        val cleared =
            ClearedAgentQueue(
                steering = steeringMessages.exchange(emptyList()),
                followUp = followUpMessages.exchange(emptyList()),
            )
        agent.clearAllQueues()
        emitQueueUpdate()
        return cleared
    }

    fun getSteeringMessages(): List<String> = steeringMessages.load().toList()

    fun getFollowUpMessages(): List<String> = followUpMessages.load().toList()

    suspend fun abort() {
        abortRetry()
        agent.abort()
        waitForIdle()
    }

    fun abortRetry() {
        retryAbortController.load()?.abort()
    }

    suspend fun executeBash(
        command: String,
        excludeFromContext: Boolean? = null,
    ): BashResult {
        val normalized = command.trim()
        require(normalized.isNotEmpty()) { "Bash command cannot be empty" }
        val executor = bashExecutor ?: error("Bash execution is not configured for this session")
        val result = executor.execute(normalized, sessionManager.getCwd(), excludeFromContext)
        recordBashResult(normalized, result, excludeFromContext)
        return result
    }

    fun abortBash() {
        bashExecutor?.abort()
    }

    fun setAutoRetryEnabled(enabled: Boolean) {
        settingsManager.setRetryEnabled(enabled)
        refreshState()
    }

    fun abortCompaction() {
        compactionAbortController.load()?.abort()
        autoCompactionAbortController.load()?.abort()
    }

    fun abortBranchSummary() {
        branchSummaryAbortController.load()?.abort()
    }

    fun setAutoCompactionEnabled(enabled: Boolean) {
        settingsManager.setCompactionEnabled(enabled)
        refreshState()
    }

    fun reloadResources(): List<dev.xuexue.pi.coding.resources.ResourceDiagnostic> {
        val loader = resourceLoader ?: return emptyList()
        extensionRunner?.getContributedResources()?.let(loader::extendResources)
        loader.reload()
        installExtensionTools()
        rebuildSystemPrompt()
        refreshState()
        return loader.getDiagnostics()
    }

    /** Runs extension resource discovery and reloads the shared resource view. */
    suspend fun reloadResourcesFromExtensions(): List<ResourceDiagnostic> = reloadExt(ResourceDiscoverReason.RELOAD)

    suspend fun startExtensions(
        reason: SessionStartReason = SessionStartReason.STARTUP,
        previousSessionFile: String? = null,
    ) {
        loadPackageResources()
        extensionRunner?.emit(
            PiExtensionEvent.SessionStart(reason, previousSessionFile),
            extensionContext(),
        )
        reloadExt(ResourceDiscoverReason.STARTUP)
    }

    suspend fun shutdown(
        reason: SessionShutdownReason = SessionShutdownReason.QUIT,
        targetSessionFile: String? = null,
    ) {
        if (!disposed.compareAndSet(false, true)) return
        abortBranchSummary()
        extensionRunner?.dispose(reason, targetSessionFile)
        modLoader?.unbindSession()
        runCatching { agent.abort() }
        disconnectFromAgent()
        listeners.store(emptyList())
        cleanupSessionResources(sessionId)
    }

    suspend fun beforeSessionSwitch(
        reason: String,
        targetSessionFile: String? = null,
    ): Boolean = extensionRunner?.emitBeforeSwitch(reason, targetSessionFile, extensionContext()) ?: false

    suspend fun beforeSessionFork(
        entryId: String,
        position: String,
    ): Boolean = extensionRunner?.emitBeforeFork(entryId, position, extensionContext()) ?: false

    suspend fun resolveProjectTrust(): PiExtensionEventResult.ProjectTrust? {
        val runner = extensionRunner ?: return null
        val result = runner.emitProjectTrust(extensionContext())
        when (result?.trusted) {
            ProjectTrustDecision.YES -> resourceLoader?.setProjectTrusted(true)
            ProjectTrustDecision.NO -> resourceLoader?.setProjectTrusted(false)
            null, ProjectTrustDecision.UNDECIDED -> Unit
        }
        return result
    }

    suspend fun executeExtensionCommand(
        invocationName: String,
        args: String = "",
    ): Boolean = extensionRunner?.executeCommand(invocationName, args, extensionContext()) ?: false

    fun getRegisteredExtensionCommands() = extensionRunner?.getRegisteredCommands().orEmpty()

    fun getRegisteredExtensionProviders(): List<PiProvider> = extensionRunner?.getAllRegisteredProviders().orEmpty()

    fun getRegisteredExtensionImageProviders(): List<PiImageProvider> =
        extensionRunner
            ?.getAllRegisteredImageProviders()
            .orEmpty()

    /** Resources exposed by Pi's get_commands RPC without leaking the loader implementation. */
    fun getPromptTemplatesForRpc() = resourceLoader?.getPrompts()?.prompts.orEmpty()

    fun getSkillsForRpc() = resourceLoader?.getSkills()?.skills.orEmpty()

    @Suppress("TooGenericExceptionCaught")
    suspend fun compact(customInstructions: String? = null): CompactionResult {
        disconnectFromAgent()
        abort()
        val controller = AbortController()
        compactionAbortController.store(controller)
        refreshState()
        emitEvent(AgentSessionEvent.CompactionStart(CompactionReason.MANUAL))
        try {
            requireCompactionModel()
            val pathEntries = sessionManager.getBranch()
            val preparation = requireManualCompactionPreparation(pathEntries)
            val generated =
                compactContext(
                    preparation = preparation,
                    request =
                        CompactionRequest(
                            model = model,
                            apiKey = agent.getApiKey?.invoke(model.provider),
                            customInstructions = customInstructions,
                            signal = controller.signal,
                            thinkingLevel = thinkingLevel,
                            streamFunction = agent.streamFunction,
                        ),
                )
            throwIfCompactionCancelled(controller.signal)

            sessionManager.appendCompaction(
                summary = generated.summary,
                firstKeptEntryId = generated.firstKeptEntryId,
                tokensBefore = generated.tokensBefore,
                details = generated.details?.toJson(),
                fromHook = false,
            )
            val sessionContext = sessionManager.buildSessionContext()
            agent.replaceMessages(sessionContext.messages)
            val result =
                generated.copy(
                    estimatedTokensAfter = sessionContext.messages.sumOf(::estimateTokens),
                )
            refreshState()
            emitEvent(
                AgentSessionEvent.CompactionEnd(
                    reason = CompactionReason.MANUAL,
                    result = result,
                    aborted = false,
                    willRetry = false,
                ),
            )
            return result
        } catch (error: CompactionCancelledException) {
            emitEvent(
                AgentSessionEvent.CompactionEnd(
                    reason = CompactionReason.MANUAL,
                    result = null,
                    aborted = true,
                    willRetry = false,
                    errorMessage = null,
                ),
            )
            throw error
        } catch (error: Exception) {
            emitEvent(
                AgentSessionEvent.CompactionEnd(
                    reason = CompactionReason.MANUAL,
                    result = null,
                    aborted = false,
                    willRetry = false,
                    errorMessage = "Compaction failed: ${error.message ?: error}",
                ),
            )
            throw error
        } finally {
            compactionAbortController.compareAndSet(controller, null)
            reconnectToAgent()
            refreshState()
        }
    }

    suspend fun waitForIdle() {
        while (true) {
            val run = activeRun.load() ?: return
            run.completion.await()
            if (activeRun.load() == null) return
        }
    }

    fun setModel(model: Model) {
        val previousThinkingLevel = thinkingLevel
        agent.setModel(model)
        sessionManager.appendModelChange(model.provider, model.id)
        setThinkingLevel(previousThinkingLevel)
        refreshState()
    }

    /** Replace the ordered model scope used by [cycleModel]. */
    fun setScopedModels(scopedModels: List<ScopedModel>) {
        scopedModelEntries = scopedModels.toList()
    }

    /**
     * Cycle to the next or previous authenticated model.
     *
     * When a scope is configured, its order is authoritative and models without configured
     * authentication are omitted. With no scope, the registry's authenticated model order is
     * used. A missing current model starts from scope index zero, matching Pi's behavior.
     */
    fun cycleModel(
        direction: ModelCycleDirection = ModelCycleDirection.FORWARD,
        modelRegistryOverride: ModelRegistry? = null,
    ): ModelCycleResult? {
        val registry = modelRegistryOverride ?: configuredModelRegistry
        val candidates =
            if (scopedModelEntries.isNotEmpty()) {
                scopedModelEntries.filter { scoped ->
                    registry?.hasConfiguredAuth(scoped.model) != false
                }
            } else {
                registry?.getAvailable()?.map { model -> ScopedModel(model) }.orEmpty()
            }
        if (candidates.size <= 1) return null

        val currentModel = model
        var currentIndex = candidates.indexOfFirst { scoped -> modelsAreEqual(scoped.model, currentModel) }
        if (currentIndex == -1) currentIndex = 0
        val nextIndex =
            when (direction) {
                ModelCycleDirection.FORWARD -> (currentIndex + 1).mod(candidates.size)
                ModelCycleDirection.BACKWARD -> (currentIndex - 1 + candidates.size).mod(candidates.size)
            }
        val next = candidates[nextIndex]
        val requestedThinkingLevel = next.thinkingLevel ?: thinkingLevel
        setModel(next.model)
        setThinkingLevel(requestedThinkingLevel)
        return ModelCycleResult(
            model = next.model,
            thinkingLevel = thinkingLevel,
            isScoped = scopedModelEntries.isNotEmpty(),
        )
    }

    fun setThinkingLevel(level: ModelThinkingLevel) {
        val effectiveLevel = clampThinkingLevel(model, level)
        if (effectiveLevel == thinkingLevel) return
        agent.setThinkingLevel(effectiveLevel)
        sessionManager.appendThinkingLevelChange(effectiveLevel.sessionValue())
        refreshState()
        emitEvent(AgentSessionEvent.ThinkingLevelChanged(effectiveLevel))
    }

    fun cycleThinkingLevel(): ModelThinkingLevel? {
        if (!supportsThinking()) return null
        val levels = getAvailableThinkingLevels()
        val currentIndex = levels.indexOf(thinkingLevel)
        val nextLevel = levels[(currentIndex + 1).mod(levels.size)]
        setThinkingLevel(nextLevel)
        return nextLevel
    }

    fun getAvailableThinkingLevels(): List<ModelThinkingLevel> = getSupportedThinkingLevels(model)

    fun supportsThinking(): Boolean = model.reasoning

    fun setSteeringMode(mode: QueueMode) {
        agent.steeringMode = mode
        refreshState()
    }

    fun setFollowUpMode(mode: QueueMode) {
        agent.followUpMode = mode
        refreshState()
    }

    fun setSessionName(name: String) {
        sessionManager.appendSessionInfo(name)
        refreshState()
        emitEvent(AgentSessionEvent.SessionInfoChanged(sessionName))
    }

    suspend fun navigateTree(
        targetId: String,
        options: NavigateTreeOptions = NavigateTreeOptions(),
    ): NavigateTreeResult =
        when {
            targetId == sessionManager.getLeafId() -> NavigateTreeResult(cancelled = false)
            options.summarize && model == UNKNOWN_AGENT_MODEL -> error("No model available for summarization")
            else -> navigateTreeFromCurrentLeaf(targetId, options)
        }

    private suspend fun navigateTreeFromCurrentLeaf(
        targetId: String,
        options: NavigateTreeOptions,
    ): NavigateTreeResult {
        val oldLeafId = sessionManager.getLeafId()
        val targetEntry = sessionManager.getEntry(targetId) ?: error("Entry $targetId not found")
        val collection = collectEntriesForBranchSummary(sessionManager, oldLeafId, targetId)
        val controller = AbortController()
        branchSummaryAbortController.store(controller)
        refreshState()
        try {
            return when (val summary = resolveTreeSummary(targetId, oldLeafId, collection, options, controller)) {
                TreeSummaryResolution.Cancelled -> {
                    NavigateTreeResult(cancelled = true)
                }

                TreeSummaryResolution.Aborted -> {
                    NavigateTreeResult(cancelled = true, aborted = true)
                }

                is TreeSummaryResolution.Ready -> {
                    completeTreeNavigation(
                        targetEntry,
                        targetId,
                        oldLeafId,
                        summary,
                    )
                }
            }
        } finally {
            branchSummaryAbortController.compareAndSet(controller, null)
            refreshState()
        }
    }

    private suspend fun resolveTreeSummary(
        targetId: String,
        oldLeafId: String?,
        collection: dev.xuexue.pi.coding.compaction.CollectEntriesResult,
        options: NavigateTreeOptions,
        controller: AbortController,
    ): TreeSummaryResolution {
        val preparation =
            TreePreparation(
                targetId = targetId,
                oldLeafId = oldLeafId,
                commonAncestorId = collection.commonAncestorId,
                entriesToSummarize = collection.entries,
                userWantsSummary = options.summarize,
                customInstructions = options.customInstructions,
                replaceInstructions = options.replaceInstructions,
                label = options.label,
            )
        val extensionResult =
            extensionRunner?.emitBeforeTree(
                PiExtensionEvent.SessionBeforeTree(preparation, controller.signal),
                extensionContext(),
            )
        val extensionSummary = extensionResult?.summary.takeIf { options.summarize }
        val generated =
            if (extensionResult?.cancel == true) {
                null
            } else {
                generateDefaultTreeSummary(
                    collection.entries,
                    options,
                    extensionSummary,
                    extensionResult?.customInstructions ?: options.customInstructions,
                    extensionResult?.replaceInstructions ?: options.replaceInstructions,
                    controller,
                )
            }
        return when {
            extensionResult?.cancel == true -> {
                TreeSummaryResolution.Cancelled
            }

            generated?.aborted == true || controller.signal.isAborted -> {
                TreeSummaryResolution.Aborted
            }

            generated?.error != null -> {
                error(generated.error)
            }

            else -> {
                TreeSummaryResolution.Ready(
                    summaryText = generated?.summary ?: extensionSummary?.summary,
                    summaryDetails = generated?.detailsJson() ?: extensionSummary?.details,
                    fromExtension = extensionSummary != null,
                    label = extensionResult?.label ?: options.label,
                )
            }
        }
    }

    private suspend fun generateDefaultTreeSummary(
        entries: List<SessionEntry>,
        options: NavigateTreeOptions,
        extensionSummary: dev.xuexue.pi.coding.extensions.ExtensionBranchSummary?,
        customInstructions: String?,
        replaceInstructions: Boolean?,
        controller: AbortController,
    ) = if (!options.summarize || entries.isEmpty() || extensionSummary != null) {
        null
    } else {
        val auth = requiredSummaryRequestAuth()
        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(
                model = model,
                apiKey = auth.apiKey,
                headers = auth.headers,
                environment = auth.environment,
                signal = controller.signal,
                customInstructions = customInstructions,
                replaceInstructions = replaceInstructions,
                reserveTokens = settingsManager.getBranchSummarySettings().reserveTokens,
                streamFunction = agent.streamFunction,
            ),
        )
    }

    private suspend fun completeTreeNavigation(
        targetEntry: SessionEntry,
        targetId: String,
        oldLeafId: String?,
        summary: TreeSummaryResolution.Ready,
    ): NavigateTreeResult {
        val (newLeafId, editorText) = navigationTarget(targetEntry)
        val summaryEntry =
            attachTreeNavigation(
                newLeafId,
                targetId,
                summary.summaryText,
                summary.summaryDetails,
                summary.fromExtension,
                summary.label,
            )
        agent.replaceMessages(sessionManager.buildSessionContext().messages)
        refreshState()
        extensionRunner?.emit(
            PiExtensionEvent.SessionTree(
                newLeafId = sessionManager.getLeafId(),
                oldLeafId = oldLeafId,
                summaryEntry = summaryEntry,
                fromExtension = summary.fromExtension.takeIf { summary.summaryText != null },
            ),
            extensionContext(),
        )
        return NavigateTreeResult(
            editorText = editorText,
            cancelled = false,
            summaryEntry = summaryEntry,
        )
    }

    private fun navigationTarget(targetEntry: SessionEntry): Pair<String?, String?> =
        when {
            targetEntry is SessionMessageEntry && targetEntry.message is UserMessage -> {
                targetEntry.parentId to userMessageText(targetEntry.message)
            }

            targetEntry is dev.xuexue.pi.coding.session.CustomMessageEntry -> {
                targetEntry.parentId to userContentText(targetEntry.content)
            }

            else -> {
                targetEntry.id to null
            }
        }

    private fun attachTreeNavigation(
        newLeafId: String?,
        targetId: String,
        summaryText: String?,
        summaryDetails: kotlinx.serialization.json.JsonElement?,
        fromExtension: Boolean,
        label: String?,
    ): BranchSummaryEntry? {
        val summaryEntry =
            if (summaryText != null) {
                val summaryId =
                    sessionManager.branchWithSummary(
                        newLeafId,
                        summaryText,
                        summaryDetails,
                        fromExtension,
                    )
                sessionManager.getEntry(summaryId) as BranchSummaryEntry
            } else {
                if (newLeafId == null) sessionManager.resetLeaf() else sessionManager.branch(newLeafId)
                null
            }
        if (label != null) {
            sessionManager.appendLabelChange(summaryEntry?.id ?: targetId, label)
        }
        return summaryEntry
    }

    private suspend fun requiredSummaryRequestAuth(): SummaryRequestAuth {
        configuredModelRegistry?.let { registry ->
            val result = registry.getApiKeyAndHeaders(model)
            if (!result.ok) error(result.error ?: "Authentication failed for ${model.provider}")
            val apiKey =
                result.apiKey?.takeIf(String::isNotBlank)
                    ?: error("No API key found for ${model.provider}")
            return SummaryRequestAuth(apiKey, result.headers, result.environment)
        }
        val apiKey =
            agent.getApiKey?.invoke(model.provider)?.takeIf(String::isNotBlank)
                ?: error("No API key found for ${model.provider}")
        return SummaryRequestAuth(apiKey)
    }

    private fun dev.xuexue.pi.coding.compaction.BranchSummaryResult.detailsJson(): JsonObject =
        buildJsonObject {
            put("readFiles", JsonArray(readFiles.orEmpty().map(::JsonPrimitive)))
            put("modifiedFiles", JsonArray(modifiedFiles.orEmpty().map(::JsonPrimitive)))
        }

    fun appendCustomEntry(
        customType: String,
        data: kotlinx.serialization.json.JsonElement? = null,
    ): String {
        val entryId = sessionManager.appendCustomEntry(customType, data)
        sessionManager.getEntry(entryId)?.let { emitEvent(AgentSessionEvent.EntryAppended(it)) }
        refreshState()
        return entryId
    }

    private fun createModSessionApi(): SessionApi =
        object : SessionApi {
            override val state: AgentSessionState
                get() = currentState

            override val isIdle: Boolean
                get() = this@AgentSession.isIdle

            override suspend fun prompt(
                text: String,
                images: List<ImageContent>,
                behavior: StreamingBehavior?,
            ) {
                this@AgentSession.prompt(
                    text,
                    PromptOptions(
                        images = images,
                        streamingBehavior = behavior,
                    ),
                )
            }

            override suspend fun sendUserMessage(
                text: String,
                options: CustomMessageOptions,
            ) {
                this@AgentSession.sendUserMessage(text, options.deliverAs.toStreamingBehavior())
            }

            override suspend fun sendCustomMessage(
                message: CustomMessageInput,
                options: CustomMessageOptions,
            ) {
                this@AgentSession.sendCustomMessage(message, options)
            }

            override fun steer(message: AgentMessage) {
                agent.steer(message)
                refreshState()
            }

            override fun followUp(message: AgentMessage) {
                agent.followUp(message)
                refreshState()
            }

            override suspend fun abort() {
                this@AgentSession.abort()
            }

            override fun appendCustomEntry(message: CustomMessageInput): String = this@AgentSession.appendCustomEntry(message.customType, message.details)
        }

    fun recordBashResult(
        command: String,
        result: BashResult,
        excludeFromContext: Boolean? = null,
    ) {
        val message =
            BashExecutionMessage(
                command = command,
                output = result.output,
                exitCode = result.exitCode,
                cancelled = result.cancelled,
                truncated = result.truncated,
                fullOutputPath = result.fullOutputPath,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                excludeFromContext = excludeFromContext,
            )
        if (isStreaming) {
            appendAtomic(pendingBashMessages, message)
            if (!isStreaming) flushPendingBashMessages()
        } else {
            appendBashMessage(message)
        }
    }

    fun getUserMessagesForForking(): List<ForkableUserMessage> =
        sessionManager.getEntries().mapNotNull { entry ->
            val message = (entry as? SessionMessageEntry)?.message as? UserMessage ?: return@mapNotNull null
            val text = userMessageText(message)
            text.takeIf(String::isNotEmpty)?.let { ForkableUserMessage(entry.id, it) }
        }

    fun getSessionStats(): SessionStats {
        var userMessages = 0
        var assistantMessages = 0
        var toolCalls = 0
        var toolResults = 0
        var totalMessages = 0
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var cost = 0.0

        sessionManager.getEntries().forEach { entry ->
            val message = (entry as? SessionMessageEntry)?.message ?: return@forEach
            totalMessages++
            when (message) {
                is UserMessage -> {
                    userMessages++
                }

                is ToolResultMessage -> {
                    toolResults++
                }

                is AssistantMessage -> {
                    assistantMessages++
                    toolCalls += message.content.count { it is ToolCall }
                    input += message.usage.input
                    output += message.usage.output
                    cacheRead += message.usage.cacheRead
                    cacheWrite += message.usage.cacheWrite
                    cost += message.usage.cost.total
                }
            }
        }
        val total = input + output + cacheRead + cacheWrite
        return SessionStats(
            sessionFile = sessionFile,
            sessionId = sessionId,
            userMessages = userMessages,
            assistantMessages = assistantMessages,
            toolCalls = toolCalls,
            toolResults = toolResults,
            totalMessages = totalMessages,
            tokens = SessionTokenStats(input, output, cacheRead, cacheWrite, total),
            cost = cost,
            contextUsage = getContextUsage(),
        )
    }

    fun getContextUsage(): ContextUsage? {
        val contextWindow = model.contextWindow
        if (contextWindow <= 0) return null

        val branch = sessionManager.getBranch()
        val latestCompaction = branch.asReversed().firstNotNullOfOrNull { it as? CompactionEntry }
        val missingPostCompactionUsage =
            latestCompaction != null && !hasAssistantUsageAfter(branch, latestCompaction)
        return if (missingPostCompactionUsage) {
            ContextUsage(null, contextWindow, null)
        } else {
            val estimate = estimateContextTokens(messages)
            ContextUsage(
                tokens = estimate.tokens,
                contextWindow = contextWindow,
                percent = estimate.tokens.toDouble() / contextWindow.toDouble() * PERCENT_MULTIPLIER,
            )
        }
    }

    fun getLastAssistantText(): String? {
        val message =
            messages.asReversed().firstOrNull { candidate ->
                candidate is AssistantMessage &&
                    !(candidate.stopReason == StopReason.ABORTED && candidate.content.isEmpty())
            } as? AssistantMessage ?: return null
        return message.content
            .filterIsInstance<TextContent>()
            .joinToString("", transform = TextContent::text)
            .trim()
            .ifEmpty { null }
    }

    suspend fun exportToHtml(outputPath: String? = null): String =
        requireNotNull(htmlExporter) { "HTML export is not configured for this host" }
            .export(this, outputPath)

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        abortRetry()
        abortCompaction()
        abortBranchSummary()
        abortBash()
        runCatching { agent.abort() }
        disconnectFromAgent()
        listeners.store(emptyList())
        cleanupSessionResources(sessionId)
    }

    private suspend fun runAgentPrompt(messages: List<AgentMessage>) {
        val run = ActiveSessionRun(CompletableDeferred())
        check(activeRun.compareAndSet(null, run)) { STREAMING_PROMPT_ERROR }
        refreshState()
        try {
            agent.prompt(messages)
            while (handlePostAgentRun()) {
                agent.continueConversation()
            }
        } finally {
            flushPendingBashMessages()
            activeRun.compareAndSet(run, null)
            refreshState()
            try {
                emitEvent(AgentSessionEvent.AgentSettled)
                extensionRunner?.emit(PiExtensionEvent.AgentSettled, extensionContext())
            } finally {
                run.completion.complete(Unit)
            }
        }
    }

    private suspend fun handleAgentEvent(event: AgentEvent) {
        if (event is AgentEvent.MessageStart) {
            val message = event.message
            if (message is UserMessage) {
                overflowRecoveryAttempted.store(false)
                removeDeliveredQueueMessage(userMessageText(message))
            }
        }
        extensionRunner?.emit(event.toExtensionEvent(), extensionContext())
        refreshState()
        emitEvent(event.toSessionEvent())
        if (event is AgentEvent.MessageEnd) {
            persistMessage(event.message)
            val assistant = event.message as? AssistantMessage
            if (assistant != null) handleAssistantMessageEnd(assistant)
        }
    }

    private suspend fun handlePostAgentRun(): Boolean {
        val message = lastAssistantMessage.exchange(null)
        return if (message == null) {
            false
        } else if (isRetryableError(message) && prepareRetry(message)) {
            true
        } else {
            finishRetryIfNeeded(message)
            checkCompaction(message) || agent.hasQueuedMessages()
        }
    }

    private fun finishRetryIfNeeded(message: AssistantMessage) {
        if (message.stopReason != StopReason.ERROR || retryAttempt == 0) return
        emitEvent(
            AgentSessionEvent.AutoRetryEnd(
                success = false,
                attempt = retryAttempt,
                finalError = message.errorMessage,
            ),
        )
        retryAttemptState.store(0)
        refreshState()
    }

    private fun handleAssistantMessageEnd(message: AssistantMessage) {
        lastAssistantMessage.store(message)
        if (message.stopReason != StopReason.ERROR) overflowRecoveryAttempted.store(false)
        if (message.stopReason != StopReason.ERROR && retryAttempt > 0) {
            emitEvent(
                AgentSessionEvent.AutoRetryEnd(
                    success = true,
                    attempt = retryAttempt,
                ),
            )
            retryAttemptState.store(0)
            refreshState()
        }
    }

    private fun isRetryableError(message: AssistantMessage): Boolean {
        val isOverflow = isContextOverflow(message, model.contextWindow)
        return !isOverflow && isRetryableAssistantError(message)
    }

    private suspend fun prepareRetry(message: AssistantMessage): Boolean {
        val settings = settingsManager.getRetrySettings()
        if (!settings.enabled) return false

        val attempt = retryAttemptState.addAndFetch(1)
        return if (attempt > settings.maxRetries) {
            retryAttemptState.addAndFetch(-1)
            false
        } else {
            waitForRetry(message, settings, attempt)
        }
    }

    private suspend fun waitForRetry(
        message: AssistantMessage,
        settings: RetrySettings,
        attempt: Int,
    ): Boolean {
        val delayMs = settings.baseDelayMs * RETRY_MULTIPLIER.pow(attempt - 1).toLong()
        emitEvent(
            AgentSessionEvent.AutoRetryStart(
                attempt = attempt,
                maxAttempts = settings.maxRetries,
                delayMs = delayMs,
                errorMessage = message.errorMessage ?: "Unknown error",
            ),
        )
        removeLastAssistantFromContext()

        val controller = AbortController()
        retryAbortController.store(controller)
        refreshState()
        return try {
            sleep(delayMs, controller.signal)
            true
        } catch (_: RetrySleepAbortedException) {
            retryAttemptState.store(0)
            emitEvent(
                AgentSessionEvent.AutoRetryEnd(
                    success = false,
                    attempt = attempt,
                    finalError = "Retry cancelled",
                ),
            )
            false
        } finally {
            retryAbortController.compareAndSet(controller, null)
            refreshState()
        }
    }

    private fun removeLastAssistantFromContext() {
        val messages = agent.currentState.messages
        if (messages.lastOrNull() is AssistantMessage) {
            agent.replaceMessages(messages.dropLast(1))
            refreshState()
        }
    }

    @Suppress("ReturnCount")
    private suspend fun checkCompaction(
        assistantMessage: AssistantMessage,
        skipAbortedCheck: Boolean = true,
    ): Boolean {
        val settings = settingsManager.getCompactionSettings()
        if (!settings.enabled) return false
        if (skipAbortedCheck && assistantMessage.stopReason == StopReason.ABORTED) return false

        val contextWindow = model.contextWindow
        val sameModel = assistantMessage.provider == model.provider && assistantMessage.model == model.id
        val latestCompaction = sessionManager.getBranch().asReversed().firstNotNullOfOrNull { it as? CompactionEntry }
        if (latestCompaction != null && assistantMessage.timestamp <= latestCompaction.timestampMillis()) return false

        return if (sameModel && isContextOverflow(assistantMessage, contextWindow)) {
            handleOverflowCompaction(assistantMessage)
        } else {
            val contextTokens = estimateThresholdContextTokens(assistantMessage, latestCompaction) ?: return false
            shouldCompact(contextTokens, contextWindow, settings) &&
                runAutoCompaction(CompactionReason.THRESHOLD, willRetry = false)
        }
    }

    private suspend fun handleOverflowCompaction(assistantMessage: AssistantMessage): Boolean {
        val willRetry = assistantMessage.stopReason != StopReason.STOP
        return when {
            !willRetry -> {
                runAutoCompaction(CompactionReason.OVERFLOW, willRetry = false)
            }

            overflowRecoveryAttempted.load() -> {
                emitEvent(
                    AgentSessionEvent.CompactionEnd(
                        reason = CompactionReason.OVERFLOW,
                        result = null,
                        aborted = false,
                        willRetry = false,
                        errorMessage = OVERFLOW_RECOVERY_EXHAUSTED,
                    ),
                )
                false
            }

            else -> {
                overflowRecoveryAttempted.store(true)
                removeLastAssistantFromContext()
                runAutoCompaction(CompactionReason.OVERFLOW, willRetry = true)
            }
        }
    }

    private fun estimateThresholdContextTokens(
        assistantMessage: AssistantMessage,
        latestCompaction: CompactionEntry?,
    ): Long? {
        val directTokens = calculateContextTokens(assistantMessage.usage)
        val hasDirectUsage = assistantMessage.stopReason != StopReason.ERROR && directTokens != 0L
        if (hasDirectUsage) return directTokens
        return estimateContextTokensAfterLatestCompaction(latestCompaction)
    }

    private fun estimateContextTokensAfterLatestCompaction(
        latestCompaction: CompactionEntry?,
    ): Long? {
        val estimate = estimateContextTokens(messages)
        val usageMessage = estimate.lastUsageIndex?.let(messages::get)?.let { it as? AssistantMessage }
        val predatesLatestCompaction =
            latestCompaction != null &&
                usageMessage != null &&
                usageMessage.timestamp <= latestCompaction.timestampMillis()
        return estimate.tokens.takeUnless { estimate.lastUsageIndex == null || predatesLatestCompaction }
    }

    private suspend fun runAutoCompaction(
        reason: CompactionReason,
        willRetry: Boolean,
    ): Boolean =
        prepareCompaction(
            sessionManager.getBranch(),
            settingsManager.getCompactionSettings(),
        )?.let { preparation ->
            runPreparedAutoCompaction(reason, willRetry, preparation)
        } ?: false

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runPreparedAutoCompaction(
        reason: CompactionReason,
        willRetry: Boolean,
        preparation: CompactionPreparation,
    ): Boolean {
        val controller = AbortController()
        emitEvent(AgentSessionEvent.CompactionStart(reason))
        autoCompactionAbortController.store(controller)
        refreshState()
        return try {
            completeAutoCompaction(reason, willRetry, preparation, controller)
        } catch (error: Exception) {
            emitAutoCompactionFailure(reason, error)
            false
        } finally {
            autoCompactionAbortController.compareAndSet(controller, null)
            refreshState()
        }
    }

    private suspend fun completeAutoCompaction(
        reason: CompactionReason,
        willRetry: Boolean,
        preparation: CompactionPreparation,
        controller: AbortController,
    ): Boolean {
        val generated =
            compactContext(
                preparation = preparation,
                request =
                    CompactionRequest(
                        model = model,
                        apiKey = agent.getApiKey?.invoke(model.provider),
                        signal = controller.signal,
                        thinkingLevel = thinkingLevel,
                        streamFunction = agent.streamFunction,
                    ),
            )
        if (controller.signal.isAborted) {
            emitEvent(
                AgentSessionEvent.CompactionEnd(
                    reason = reason,
                    result = null,
                    aborted = true,
                    willRetry = false,
                ),
            )
            return false
        }

        sessionManager.appendCompaction(
            summary = generated.summary,
            firstKeptEntryId = generated.firstKeptEntryId,
            tokensBefore = generated.tokensBefore,
            details = generated.details?.toJson(),
            fromHook = false,
        )
        val context = sessionManager.buildSessionContext()
        agent.replaceMessages(context.messages)
        val result = generated.copy(estimatedTokensAfter = context.messages.sumOf(::estimateTokens))
        refreshState()
        emitEvent(
            AgentSessionEvent.CompactionEnd(
                reason = reason,
                result = result,
                aborted = false,
                willRetry = willRetry,
            ),
        )
        if (willRetry) removeTrailingErrorFromContext()
        return willRetry || agent.hasQueuedMessages()
    }

    private fun emitAutoCompactionFailure(
        reason: CompactionReason,
        error: Exception,
    ) {
        val prefix = if (reason == CompactionReason.OVERFLOW) "Context overflow recovery" else "Auto-compaction"
        emitEvent(
            AgentSessionEvent.CompactionEnd(
                reason = reason,
                result = null,
                aborted = false,
                willRetry = false,
                errorMessage = "$prefix failed: ${error.message ?: "compaction failed"}",
            ),
        )
    }

    private fun requireCompactionModel() {
        check(model != UNKNOWN_AGENT_MODEL) { "No model selected." }
    }

    private fun requireManualCompactionPreparation(
        pathEntries: List<SessionEntry>,
    ): CompactionPreparation {
        val preparation = prepareCompaction(pathEntries, settingsManager.getCompactionSettings())
        if (preparation != null) return preparation
        check(pathEntries.lastOrNull() !is CompactionEntry) { "Already compacted" }
        error("Nothing to compact (session too small)")
    }

    private fun throwIfCompactionCancelled(signal: AbortSignal) {
        if (signal.isAborted) throw CompactionCancelledException()
    }

    private fun removeTrailingErrorFromContext() {
        val lastMessage = agent.currentState.messages.lastOrNull() as? AssistantMessage
        if (lastMessage?.stopReason == StopReason.ERROR) {
            agent.replaceMessages(agent.currentState.messages.dropLast(1))
            refreshState()
        }
    }

    private fun installExtensionTools() {
        val runner = extensionRunner ?: return
        val currentTools = agent.currentState.tools
        val currentNames = currentTools.mapTo(mutableSetOf(), dev.xuexue.pi.agent.AgentTool::name)
        val extensionTools = runner.getAllRegisteredTools().filter { it.name !in currentNames }
        if (extensionTools.isNotEmpty()) agent.setTools(currentTools + extensionTools)
    }

    private suspend fun reloadExt(
        reason: ResourceDiscoverReason,
    ): List<ResourceDiagnostic> {
        loadPackageResources()
        val discovered = extensionRunner?.emitResourcesDiscover(reason, extensionContext())
        val loader = resourceLoader ?: return emptyList()
        discovered?.let(loader::extendResources)
        return reloadResources()
    }

    private suspend fun loadPackageResources() {
        val loader = resourceLoader
        val manager = packageManager
        if (loader != null && manager != null) {
            try {
                loader.extendResources(manager.resolve().resources)
            } catch (_: Throwable) {
                // Package diagnostics are reported by the manager; an unavailable package must not break a session.
            }
        }
    }

    private fun installExtensionHooks() {
        val runner = extensionRunner ?: return
        val previousBeforeToolCall = agent.beforeToolCall
        val previousAfterToolCall = agent.afterToolCall
        val previousTransformContext = agent.transformContext

        agent.transformContext = { messages, signal ->
            val transformed = previousTransformContext?.invoke(messages, signal) ?: messages
            if (runner.hasHandlers(PiExtensionEventType.CONTEXT)) {
                runner.emitContext(transformed, extensionContext())
            } else {
                transformed
            }
        }
        agent.beforeToolCall = { context, signal ->
            val previous = previousBeforeToolCall?.invoke(context, signal)
            if (previous?.block == true || !runner.hasHandlers(PiExtensionEventType.TOOL_CALL)) {
                previous
            } else {
                val result =
                    runner.emitToolCall(
                        PiExtensionEvent.ToolCall(
                            toolCallId = context.toolCall.id,
                            toolName = context.toolCall.name,
                            args = JsonObject(context.args.toMap()),
                        ),
                        extensionContext(),
                    )
                if (result?.block == true) {
                    BeforeToolCallResult(block = true, reason = result.reason)
                } else {
                    previous
                }
            }
        }
        agent.afterToolCall = { context, signal ->
            val previous = previousAfterToolCall?.invoke(context, signal)
            if (!runner.hasHandlers(PiExtensionEventType.TOOL_RESULT)) {
                previous
            } else {
                val modified =
                    runner.emitToolResult(
                        PiExtensionEvent.ToolResult(
                            toolCallId = context.toolCall.id,
                            toolName = context.toolCall.name,
                            content = context.result.content,
                            details = context.result.details,
                            isError = context.isError,
                        ),
                        extensionContext(),
                    )
                previous?.copy(
                    content = previous.content ?: modified.content,
                    details = previous.details ?: modified.details,
                    isError = previous.isError ?: modified.isError,
                ) ?: AfterToolCallResult(
                    content = modified.content,
                    details = modified.details,
                    isError = modified.isError,
                )
            }
        }
    }

    private fun extensionContext(): PiExtensionContext =
        PiExtensionContext(
            cwd = resourceLoader?.getCurrentWorkingDirectory() ?: sessionManager.getCwd(),
            mode = extensionMode,
            isProjectTrusted = { resourceLoader?.isProjectTrusted() ?: true },
            isIdle = { isIdle },
            abort = { agent.abort() },
            hasPendingMessages = { pendingMessageCount > 0 },
            getSystemPrompt = { systemPrompt },
            getSessionState = { currentState },
            reload = { reloadResources() },
            interaction = interactionPort,
        )

    private suspend fun extensionBeforeAgentStart(prompt: String): ExtensionStartResult {
        val runner = extensionRunner
        if (runner == null || !runner.hasHandlers(PiExtensionEventType.BEFORE_AGENT_START)) {
            return ExtensionStartResult(emptyList(), null)
        }
        val result = runner.emitBeforeAgentStart(prompt, systemPrompt, extensionContext())
        val messages =
            result.messages.map { message ->
                CustomMessage(
                    customType = message.customType,
                    content = message.content,
                    display = message.display,
                    details = message.details,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
            }
        return ExtensionStartResult(messages, result.systemPrompt)
    }

    private fun expandPromptText(text: String): String {
        val loader = resourceLoader ?: return text
        val skillExpanded = expandSkillCommand(text, loader)
        return expandPromptTemplate(skillExpanded, loader.getPrompts().prompts)
    }

    private fun expandSkillCommand(
        text: String,
        loader: dev.xuexue.pi.coding.resources.DefaultResourceLoader,
    ): String {
        val command =
            text.takeIf { it.startsWith(SKILL_COMMAND_PREFIX) }?.substringAfter(SKILL_COMMAND_PREFIX)
                ?: return text
        return expandSkillCommandContent(command, loader) ?: text
    }

    private fun expandSkillCommandContent(
        command: String,
        loader: dev.xuexue.pi.coding.resources.DefaultResourceLoader,
    ): String? {
        val name = command.substringBefore(' ')
        val arguments = command.substringAfter(' ', missingDelimiterValue = "").trim()
        return loader.getSkills().skills.firstOrNull { it.name == name }?.let { skill ->
            loader.readResourceText(skill.filePath)?.let { content ->
                val block =
                    "<skill name=\"${skill.name}\" location=\"${skill.filePath}\">\n" +
                        "References are relative to ${skill.baseDirectory}.\n\n" +
                        "${stripFrontmatter(content).trim()}\n</skill>"
                if (arguments.isEmpty()) block else "$block\n\n$arguments"
            }
        }
    }

    private fun rebuildSystemPrompt() {
        val loader = resourceLoader ?: return
        val tools = agent.currentState.tools
        val snippets = tools.mapNotNull { tool -> normalizedToolSnippet(tool)?.let { tool.name to it } }.toMap()
        val guidelines = tools.flatMap { tool -> normalizedToolGuidelines(tool.promptGuidelines) }
        val appendSystemPrompt = loader.getAppendSystemPrompt().joinToString("\n\n").ifEmpty { null }
        agent.setSystemPrompt(
            buildSystemPrompt(
                SystemPromptOptions(
                    customPrompt = loader.getSystemPrompt(),
                    selectedTools = tools.map { it.name },
                    toolSnippets = snippets,
                    promptGuidelines = guidelines,
                    appendSystemPrompt = appendSystemPrompt,
                    cwd = loader.getCurrentWorkingDirectory(),
                    currentDate = currentDate(),
                    documentation = systemPromptDocumentation,
                    contextFiles = loader.getAgentsFiles(),
                    skills = loader.getSkills().skills,
                ),
            ),
        )
    }

    private fun normalizedToolSnippet(tool: dev.xuexue.pi.agent.AgentTool): String? =
        tool.promptSnippet
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun normalizedToolGuidelines(guidelines: List<String>): List<String> =
        guidelines
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun findLastAssistantMessage(): AssistantMessage? {
        val reversedMessages = messages.asReversed()
        return reversedMessages.firstNotNullOfOrNull { it as? AssistantMessage }
    }

    private fun persistMessage(message: AgentMessage) {
        when (message) {
            is CustomMessage -> {
                sessionManager.appendCustomMessageEntry(
                    message.customType,
                    message.content,
                    message.display,
                    message.details,
                )
            }

            is UserMessage,
            is AssistantMessage,
            is ToolResultMessage,
            -> {
                sessionManager.appendMessage(message)
            }
        }
    }

    private fun hasAssistantUsageAfter(
        branch: List<SessionEntry>,
        compaction: CompactionEntry,
    ): Boolean {
        val compactionIndex = branch.indexOfFirst { it.id == compaction.id }
        return branch.drop(compactionIndex + 1).any { entry ->
            val assistant = (entry as? SessionMessageEntry)?.message as? AssistantMessage
            assistant != null &&
                assistant.stopReason != StopReason.ABORTED &&
                assistant.stopReason != StopReason.ERROR &&
                calculateContextTokens(assistant.usage) > 0
        }
    }

    private fun removeDeliveredQueueMessage(text: String) {
        if (text.isEmpty()) return
        val removedSteering = removeFirstAtomic(steeringMessages, text)
        val removedFollowUp = !removedSteering && removeFirstAtomic(followUpMessages, text)
        if (removedSteering || removedFollowUp) emitQueueUpdate()
    }

    private fun drainPendingNextTurnMessages(): List<CustomMessage> = pendingNextTurnMessages.exchange(emptyList())

    private fun flushPendingBashMessages() {
        pendingBashMessages.exchange(emptyList()).forEach(::appendBashMessage)
    }

    private fun appendBashMessage(message: BashExecutionMessage) {
        agent.appendMessage(message)
        sessionManager.appendMessage(message)
        refreshState()
    }

    private fun emitQueueUpdate() {
        refreshState()
        emitEvent(
            AgentSessionEvent.QueueUpdate(
                steering = steeringMessages.load().toList(),
                followUp = followUpMessages.load().toList(),
            ),
        )
    }

    private fun emitEvent(event: AgentSessionEvent) {
        listeners.load().forEach { listener -> listener.onEvent(event) }
        mutableEvents.tryEmit(event)
    }

    private fun refreshState() {
        mutableState.value =
            AgentSessionState(
                agent = agent.currentState,
                isStreaming = isStreaming,
                steeringMessages = steeringMessages.load().toList(),
                followUpMessages = followUpMessages.load().toList(),
                sessionFile = sessionFile,
                sessionId = sessionId,
                sessionName = sessionName,
                retryAttempt = retryAttempt,
                isRetrying = isRetrying,
                isCompacting = isCompacting,
            )
    }

    private fun disconnectFromAgent() {
        agentSubscription.exchange(null)?.dispose()
    }

    private fun reconnectToAgent() {
        if (disposed.load()) return
        if (agentSubscription.load() != null) return
        val subscription = agent.subscribe { event, _ -> handleAgentEvent(event) }
        if (!agentSubscription.compareAndSet(null, subscription)) subscription.dispose()
    }

    private fun addListener(listener: AgentSessionEventListener) {
        while (true) {
            val current = listeners.load()
            if (listener in current) return
            if (listeners.compareAndSet(current, current + listener)) return
        }
    }

    private fun removeListener(listener: AgentSessionEventListener) {
        while (true) {
            val current = listeners.load()
            if (listener !in current) return
            if (listeners.compareAndSet(current, current - listener)) return
        }
    }

    private fun AgentEvent.toSessionEvent(): AgentSessionEvent =
        when (this) {
            AgentEvent.AgentStart -> {
                AgentSessionEvent.AgentStart
            }

            is AgentEvent.AgentEnd -> {
                AgentSessionEvent.AgentEnd(messages, willRetry = willRetryAfterAgentEnd(messages))
            }

            AgentEvent.TurnStart -> {
                AgentSessionEvent.TurnStart
            }

            is AgentEvent.TurnEnd -> {
                AgentSessionEvent.TurnEnd(message, toolResults)
            }

            is AgentEvent.MessageStart -> {
                AgentSessionEvent.MessageStart(message)
            }

            is AgentEvent.MessageUpdate -> {
                AgentSessionEvent.MessageUpdate(message, assistantMessageEvent)
            }

            is AgentEvent.MessageEnd -> {
                AgentSessionEvent.MessageEnd(message)
            }

            is AgentEvent.ToolExecutionStart -> {
                AgentSessionEvent.ToolExecutionStart(toolCallId, toolName, args)
            }

            is AgentEvent.ToolExecutionUpdate -> {
                AgentSessionEvent.ToolExecutionUpdate(toolCallId, toolName, args, partialResult)
            }

            is AgentEvent.ToolExecutionEnd -> {
                AgentSessionEvent.ToolExecutionEnd(toolCallId, toolName, result, isError)
            }
        }

    private fun AgentEvent.toExtensionEvent(): PiExtensionEvent =
        when (this) {
            AgentEvent.AgentStart -> {
                PiExtensionEvent.AgentStart
            }

            is AgentEvent.AgentEnd -> {
                PiExtensionEvent.AgentEnd(messages)
            }

            AgentEvent.TurnStart -> {
                PiExtensionEvent.TurnStart
            }

            is AgentEvent.TurnEnd -> {
                PiExtensionEvent.TurnEnd(message)
            }

            is AgentEvent.MessageStart -> {
                PiExtensionEvent.MessageStart(message)
            }

            is AgentEvent.MessageUpdate -> {
                PiExtensionEvent.MessageUpdate(message)
            }

            is AgentEvent.MessageEnd -> {
                PiExtensionEvent.MessageEnd(message)
            }

            is AgentEvent.ToolExecutionStart -> {
                PiExtensionEvent.ToolExecutionStart(toolCallId, toolName, args)
            }

            is AgentEvent.ToolExecutionUpdate -> {
                PiExtensionEvent.ToolExecutionUpdate(toolCallId, toolName, args, partialResult)
            }

            is AgentEvent.ToolExecutionEnd -> {
                PiExtensionEvent.ToolExecutionEnd(toolCallId, toolName, result, isError)
            }
        }

    private fun willRetryAfterAgentEnd(messages: List<AgentMessage>): Boolean {
        val settings = settingsManager.getRetrySettings()
        if (!settings.enabled || retryAttempt >= settings.maxRetries) return false
        val assistant = messages.asReversed().firstNotNullOfOrNull { it as? AssistantMessage }
        return assistant?.let(::isRetryableError) == true
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 1_024
        const val SKILL_COMMAND_PREFIX = "/skill:"
        const val PERCENT_MULTIPLIER = 100.0
        const val RETRY_MULTIPLIER = 2.0
        const val OVERFLOW_RECOVERY_EXHAUSTED =
            "Context overflow recovery failed after one compact-and-retry attempt. " +
                "Try reducing context or switching to a larger-context model."
        const val STREAMING_PROMPT_ERROR =
            "Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message."
    }
}

private class RetrySleepAbortedException : Exception()

private class CompactionCancelledException : Exception("Compaction cancelled")

private fun CompactionDetails.toJson() =
    buildJsonObject {
        put("readFiles", JsonArray(readFiles.map(::JsonPrimitive)))
        put("modifiedFiles", JsonArray(modifiedFiles.map(::JsonPrimitive)))
    }

private fun CompactionEntry.timestampMillis(): Long =
    runCatching { Instant.parse(timestamp).toEpochMilliseconds() }
        .getOrDefault(Long.MIN_VALUE)

private suspend fun sleep(
    delayMs: Long,
    signal: AbortSignal,
) {
    if (signal.isAborted) throw RetrySleepAbortedException()
    suspendCancellableCoroutine { continuation ->
        val scope = CoroutineScope(continuation.context)
        val completed = AtomicBoolean(false)
        val resumeDelay = {
            if (completed.compareAndSet(false, true)) continuation.resume(Unit)
        }
        val resumeAbort = {
            if (completed.compareAndSet(false, true)) {
                continuation.resumeWithException(RetrySleepAbortedException())
            }
        }
        val delayJob =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                resumeDelay()
            }
        val abortSubscription =
            signal.onAbort {
                delayJob.cancel()
                resumeAbort()
            }
        continuation.invokeOnCancellation {
            completed.store(true)
            delayJob.cancel()
            abortSubscription.dispose()
        }
        delayJob.invokeOnCompletion { abortSubscription.dispose() }
        if (signal.isAborted) {
            delayJob.cancel()
            resumeAbort()
        } else {
            delayJob.start()
        }
    }
}

private fun userMessage(
    text: String,
    images: List<ImageContent>,
): UserMessage =
    UserMessage(
        content = UserBlocks(listOf(TextContent(text)) + images),
        timestamp = Clock.System.now().toEpochMilliseconds(),
    )

private fun userMessageText(message: UserMessage): String =
    when (val content = message.content) {
        is UserText -> content.value
        is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString("", transform = TextContent::text)
    }

private fun userContentText(content: dev.xuexue.pi.ai.UserContent): String =
    when (content) {
        is UserText -> content.value
        is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString("", transform = TextContent::text)
    }

private fun ModelThinkingLevel.sessionValue(): String =
    when (this) {
        ModelThinkingLevel.OFF -> "off"
        ModelThinkingLevel.MINIMAL -> "minimal"
        ModelThinkingLevel.LOW -> "low"
        ModelThinkingLevel.MEDIUM -> "medium"
        ModelThinkingLevel.HIGH -> "high"
        ModelThinkingLevel.XHIGH -> "xhigh"
        ModelThinkingLevel.MAX -> "max"
    }

private fun <T> appendAtomic(
    target: AtomicReference<List<T>>,
    value: T,
) {
    while (true) {
        val current = target.load()
        if (target.compareAndSet(current, current + value)) return
    }
}

private fun <T> removeFirstAtomic(
    target: AtomicReference<List<T>>,
    value: T,
): Boolean {
    while (true) {
        val current = target.load()
        val index = current.indexOf(value)
        if (index == -1) return false
        val updated = current.toMutableList().also { it.removeAt(index) }
        if (target.compareAndSet(current, updated)) return true
    }
}
