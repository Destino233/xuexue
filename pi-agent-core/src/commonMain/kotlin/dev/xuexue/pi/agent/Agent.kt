@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.PayloadTransform
import dev.xuexue.pi.ai.ResponseObserver
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.StreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingBudgets
import dev.xuexue.pi.ai.Transport
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserContentBlock
import dev.xuexue.pi.ai.UserMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock

fun interface AgentEventListener {
    suspend fun onEvent(
        event: AgentEvent,
        signal: AbortSignal,
    )
}

fun interface AgentSubscription {
    fun dispose()
}

private data class ActiveAgentRun(
    val completion: CompletableDeferred<Unit>,
    val abortController: AbortController,
)

/** Stateful Pi Agent facade and the single observable state source for UI hosts. */
@Suppress("TooManyFunctions")
class Agent(
    options: AgentOptions = AgentOptions(),
) {
    private val mutableState = MutableStateFlow(createInitialAgentState(options.initialState))
    private val mutableEvents = MutableSharedFlow<AgentEvent>()
    private val listeners = AtomicReference<List<AgentEventListener>>(emptyList())
    private val activeRun = AtomicReference<ActiveAgentRun?>(null)
    private val steeringQueue = PendingMessageQueue(options.steeringMode)
    private val followUpQueue = PendingMessageQueue(options.followUpMode)

    val state: StateFlow<AgentState> = mutableState.asStateFlow()
    val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    var convertToLlm: MessageConverter = options.convertToLlm ?: { messages -> defaultConvertToLlm(messages) }
    var transformContext: ContextTransformer? = options.transformContext
    var streamFunction: StreamFunction = options.streamFunction ?: MISSING_STREAM_FUNCTION
    var getApiKey: ApiKeyResolver? = options.getApiKey
    var onPayload: PayloadTransform? = options.onPayload
    var onResponse: ResponseObserver? = options.onResponse
    var beforeToolCall: BeforeToolCallHook? = options.beforeToolCall
    var afterToolCall: AfterToolCallHook? = options.afterToolCall
    var prepareNextTurn: LegacyPrepareNextTurn? = options.prepareNextTurn
    var prepareNextTurnWithContext: ContextualPrepareNextTurn? = options.prepareNextTurnWithContext
    var sessionId: String? = options.sessionId
    var thinkingBudgets: ThinkingBudgets? = options.thinkingBudgets
    var transport: Transport = options.transport
    var maxRetryDelayMs: Long? = options.maxRetryDelayMs
    var toolExecution: ToolExecutionMode = options.toolExecution

    val currentState: AgentState
        get() = state.value

    var steeringMode: QueueMode
        get() = steeringQueue.mode
        set(value) {
            steeringQueue.mode = value
        }

    var followUpMode: QueueMode
        get() = followUpQueue.mode
        set(value) {
            followUpQueue.mode = value
        }

    val signal: AbortSignal?
        get() = activeRun.load()?.abortController?.signal

    fun subscribe(listener: AgentEventListener): AgentSubscription {
        addListener(listener)
        return AgentSubscription { removeListener(listener) }
    }

    fun setSystemPrompt(systemPrompt: String) {
        mutableState.update { it.copy(systemPrompt = systemPrompt) }
    }

    fun setModel(model: Model) {
        mutableState.update { it.copy(model = model) }
    }

    fun setThinkingLevel(thinkingLevel: ModelThinkingLevel) {
        mutableState.update { it.copy(thinkingLevel = thinkingLevel) }
    }

    fun setTools(tools: List<AgentTool>) {
        mutableState.update { it.copy(tools = tools.toList()) }
    }

    fun replaceMessages(messages: List<AgentMessage>) {
        mutableState.update { it.copy(messages = messages.toList()) }
    }

    fun appendMessage(message: AgentMessage) {
        mutableState.update { it.copy(messages = it.messages + message) }
    }

    fun clearMessages() {
        mutableState.update { it.copy(messages = emptyList()) }
    }

    fun steer(message: AgentMessage) {
        steeringQueue.enqueue(message)
        publishQueueSizes()
    }

    fun followUp(message: AgentMessage) {
        followUpQueue.enqueue(message)
        publishQueueSizes()
    }

    fun clearSteeringQueue() {
        steeringQueue.clear()
        publishQueueSizes()
    }

    fun clearFollowUpQueue() {
        followUpQueue.clear()
        publishQueueSizes()
    }

    fun clearAllQueues() {
        steeringQueue.clear()
        followUpQueue.clear()
        publishQueueSizes()
    }

    fun hasQueuedMessages(): Boolean = steeringQueue.hasItems() || followUpQueue.hasItems()

    fun abort() {
        activeRun.load()?.abortController?.abort()
    }

    suspend fun waitForIdle() {
        activeRun.load()?.completion?.await()
    }

    fun reset() {
        steeringQueue.clear()
        followUpQueue.clear()
        mutableState.update { current ->
            current.copy(
                messages = emptyList(),
                isStreaming = false,
                streamingMessage = null,
                pendingToolCalls = emptySet(),
                errorMessage = null,
                queuedSteeringMessages = 0,
                queuedFollowUpMessages = 0,
            )
        }
    }

    suspend fun prompt(
        input: String,
        images: List<ImageContent> = emptyList(),
    ) {
        ensurePromptCanStart()
        runPromptMessages(normalizePromptInput(input, images))
    }

    suspend fun prompt(message: AgentMessage) {
        ensurePromptCanStart()
        runPromptMessages(listOf(message))
    }

    suspend fun prompt(messages: List<AgentMessage>) {
        ensurePromptCanStart()
        runPromptMessages(messages)
    }

    // Backticks retain Pi's public continue() command despite Kotlin's keyword.
    @Suppress("FunctionNaming")
    suspend fun `continue`() {
        ensureContinuationCanStart()
        val lastMessage = currentState.messages.lastOrNull() ?: error("No messages to continue from")
        if (lastMessage is AssistantMessage) {
            val steering = drainSteeringQueue()
            if (steering.isNotEmpty()) {
                runPromptMessages(steering, skipInitialSteeringPoll = true)
                return
            }
            val followUps = drainFollowUpQueue()
            if (followUps.isNotEmpty()) {
                runPromptMessages(followUps)
                return
            }
            error("Cannot continue from message role: assistant")
        }
        runContinuation()
    }

    suspend fun continueConversation() {
        `continue`()
    }

    private fun ensurePromptCanStart() {
        check(activeRun.load() == null) {
            "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."
        }
    }

    private fun ensureContinuationCanStart() {
        check(activeRun.load() == null) {
            "Agent is already processing. Wait for completion before continuing."
        }
    }

    private fun normalizePromptInput(
        input: String,
        images: List<ImageContent>,
    ): List<AgentMessage> {
        val content = mutableListOf<UserContentBlock>(TextContent(input))
        content += images
        return listOf(
            UserMessage(
                content = UserBlocks(content),
                timestamp = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private suspend fun runPromptMessages(
        messages: List<AgentMessage>,
        skipInitialSteeringPoll: Boolean = false,
    ) {
        runWithLifecycle { runSignal ->
            runAgentLoop(
                prompts = messages,
                context = createContextSnapshot(),
                config = createLoopConfig(skipInitialSteeringPoll),
                emit = AgentEventSink(::processEvent),
                signal = runSignal,
                streamFunction = streamFunction,
            )
        }
    }

    private suspend fun runContinuation() {
        runWithLifecycle { runSignal ->
            runAgentLoopContinue(
                context = createContextSnapshot(),
                config = createLoopConfig(),
                emit = AgentEventSink(::processEvent),
                signal = runSignal,
                streamFunction = streamFunction,
            )
        }
    }

    private fun createContextSnapshot(): AgentContext {
        val snapshot = currentState
        return AgentContext(
            systemPrompt = snapshot.systemPrompt,
            messages = snapshot.messages.toMutableList(),
            tools = snapshot.tools.toList(),
        )
    }

    private fun createLoopConfig(skipFirstSteeringPoll: Boolean = false): AgentLoopConfig {
        val snapshot = currentState
        val converter = convertToLlm
        val transformer = transformContext
        val apiKeyResolver = getApiKey
        val beforeHook = beforeToolCall
        val afterHook = afterToolCall
        val hasPrepareNextTurn = prepareNextTurnWithContext != null || prepareNextTurn != null
        val options = createStreamOptions(snapshot)
        var skipSteeringPoll = skipFirstSteeringPoll
        return object : AgentLoopConfig {
            override val model = snapshot.model
            override val streamOptions = options
            override val toolExecution = this@Agent.toolExecution

            override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = converter(messages)

            override suspend fun transformContext(
                messages: List<AgentMessage>,
                signal: AbortSignal?,
            ): List<AgentMessage> = transformer?.invoke(messages, signal) ?: messages

            override suspend fun getApiKey(provider: String): String? = apiKeyResolver?.invoke(provider)

            override suspend fun prepareNextTurn(context: ShouldStopAfterTurnContext): AgentLoopTurnUpdate? {
                if (!hasPrepareNextTurn) return null
                return this@Agent.prepareNextTurnWithContext?.invoke(context, this@Agent.signal)
                    ?: this@Agent.prepareNextTurn?.invoke(this@Agent.signal)
            }

            override suspend fun getSteeringMessages(): List<AgentMessage> {
                if (skipSteeringPoll) {
                    skipSteeringPoll = false
                    return emptyList()
                }
                return drainSteeringQueue()
            }

            override suspend fun getFollowUpMessages(): List<AgentMessage> = drainFollowUpQueue()

            override suspend fun beforeToolCall(
                context: BeforeToolCallContext,
                signal: AbortSignal?,
            ): BeforeToolCallResult? = beforeHook?.invoke(context, signal)

            override suspend fun afterToolCall(
                context: AfterToolCallContext,
                signal: AbortSignal?,
            ): AfterToolCallResult? = afterHook?.invoke(context, signal)
        }
    }

    private fun createStreamOptions(snapshot: AgentState): SimpleStreamOptions =
        SimpleStreamOptions(
            stream =
                StreamOptions(
                    transport = transport,
                    sessionId = sessionId,
                    onPayload = onPayload,
                    onResponse = onResponse,
                    maxRetryDelayMs = maxRetryDelayMs,
                ),
            reasoning = snapshot.thinkingLevel.toAiThinkingLevel(),
            thinkingBudgets = thinkingBudgets,
        )

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runWithLifecycle(executor: suspend (AbortSignal) -> Unit) {
        val run = ActiveAgentRun(CompletableDeferred(), AbortController())
        check(activeRun.compareAndSet(null, run)) { "Agent is already processing." }
        mutableState.update { current ->
            current.copy(
                isStreaming = true,
                streamingMessage = null,
                errorMessage = null,
            )
        }
        try {
            executor(run.abortController.signal)
        } catch (error: Throwable) {
            handleRunFailure(error, run.abortController.signal.isAborted)
        } finally {
            finishRun(run)
        }
    }

    private suspend fun handleRunFailure(
        error: Throwable,
        aborted: Boolean,
    ) {
        val model = currentState.model
        val failure =
            AssistantMessage(
                content = listOf(TextContent("")),
                api = model.api,
                provider = model.provider,
                model = model.id,
                usage = EMPTY_USAGE,
                stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
                errorMessage = error.message ?: error.toString(),
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        processEvent(AgentEvent.MessageStart(failure))
        processEvent(AgentEvent.MessageEnd(failure))
        processEvent(AgentEvent.TurnEnd(failure, emptyList()))
        processEvent(AgentEvent.AgentEnd(listOf(failure)))
    }

    private fun finishRun(run: ActiveAgentRun) {
        mutableState.update { current ->
            current.copy(
                isStreaming = false,
                streamingMessage = null,
                pendingToolCalls = emptySet(),
            )
        }
        run.completion.complete(Unit)
        activeRun.compareAndSet(run, null)
    }

    private suspend fun processEvent(event: AgentEvent) {
        reduceState(event)
        mutableEvents.emit(event)
        val runSignal =
            activeRun.load()?.abortController?.signal
                ?: error("Agent listener invoked outside active run")
        listeners.load().forEach { listener -> listener.onEvent(event, runSignal) }
    }

    private fun reduceState(event: AgentEvent) {
        mutableState.update { current ->
            when (event) {
                is AgentEvent.MessageStart -> {
                    current.copy(streamingMessage = event.message)
                }

                is AgentEvent.MessageUpdate -> {
                    current.copy(streamingMessage = event.message)
                }

                is AgentEvent.MessageEnd -> {
                    current.copy(
                        streamingMessage = null,
                        messages = current.messages + event.message,
                    )
                }

                is AgentEvent.ToolExecutionStart -> {
                    current.copy(pendingToolCalls = current.pendingToolCalls + event.toolCallId)
                }

                is AgentEvent.ToolExecutionEnd -> {
                    current.copy(pendingToolCalls = current.pendingToolCalls - event.toolCallId)
                }

                is AgentEvent.TurnEnd -> {
                    if (event.message is AssistantMessage && event.message.errorMessage != null) {
                        current.copy(errorMessage = event.message.errorMessage)
                    } else {
                        current
                    }
                }

                is AgentEvent.AgentEnd -> {
                    current.copy(streamingMessage = null)
                }

                AgentEvent.AgentStart,
                AgentEvent.TurnStart,
                is AgentEvent.ToolExecutionUpdate,
                -> {
                    current
                }
            }
        }
    }

    private fun drainSteeringQueue(): List<AgentMessage> = steeringQueue.drain().also { publishQueueSizes() }

    private fun drainFollowUpQueue(): List<AgentMessage> = followUpQueue.drain().also { publishQueueSizes() }

    private fun publishQueueSizes() {
        mutableState.update { current ->
            current.copy(
                queuedSteeringMessages = steeringQueue.size,
                queuedFollowUpMessages = followUpQueue.size,
            )
        }
    }

    private fun addListener(listener: AgentEventListener) {
        while (true) {
            val current = listeners.load()
            if (listener in current) return
            if (listeners.compareAndSet(current, current + listener)) return
        }
    }

    private fun removeListener(listener: AgentEventListener) {
        while (true) {
            val current = listeners.load()
            if (listener !in current) return
            if (listeners.compareAndSet(current, current - listener)) return
        }
    }

    private companion object {
        val MISSING_STREAM_FUNCTION =
            StreamFunction { _, _, _ -> error("No stream function configured") }

        val EMPTY_USAGE =
            Usage(
                input = 0,
                output = 0,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = 0,
                cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
            )

        fun defaultConvertToLlm(messages: List<AgentMessage>): List<Message> = messages.filterIsInstance<Message>()
    }
}
