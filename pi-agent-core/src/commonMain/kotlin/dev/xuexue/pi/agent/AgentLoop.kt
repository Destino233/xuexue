package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.EventStream
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.ThinkingLevel
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun agentLoop(
    scope: CoroutineScope,
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    signal: AbortSignal? = null,
    streamFunction: StreamFunction,
): EventStream<AgentEvent, List<AgentMessage>> {
    val stream = createAgentStream()
    scope.launch {
        val messages = runAgentLoop(prompts, context, config, AgentEventSink(stream::push), signal, streamFunction)
        stream.end(messages)
    }
    return stream
}

fun agentLoopContinue(
    scope: CoroutineScope,
    context: AgentContext,
    config: AgentLoopConfig,
    signal: AbortSignal? = null,
    streamFunction: StreamFunction,
): EventStream<AgentEvent, List<AgentMessage>> {
    requireCanContinue(context)
    val stream = createAgentStream()
    scope.launch {
        val messages = runAgentLoopContinue(context, config, AgentEventSink(stream::push), signal, streamFunction)
        stream.end(messages)
    }
    return stream
}

suspend fun runAgentLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: AbortSignal? = null,
    streamFunction: StreamFunction,
): List<AgentMessage> {
    val newMessages = prompts.toMutableList()
    val currentContext = context.copy(messages = (context.messages + prompts).toMutableList())

    emit.emit(AgentEvent.AgentStart)
    emit.emit(AgentEvent.TurnStart)
    prompts.forEach { prompt ->
        emit.emit(AgentEvent.MessageStart(prompt))
        emit.emit(AgentEvent.MessageEnd(prompt))
    }

    runLoop(currentContext, newMessages, config, signal, emit, streamFunction)
    return newMessages
}

suspend fun runAgentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: AbortSignal? = null,
    streamFunction: StreamFunction,
): List<AgentMessage> {
    requireCanContinue(context)
    val newMessages = mutableListOf<AgentMessage>()
    val currentContext = context.copy()

    emit.emit(AgentEvent.AgentStart)
    emit.emit(AgentEvent.TurnStart)
    runLoop(currentContext, newMessages, config, signal, emit, streamFunction)
    return newMessages
}

// The branching mirrors Pi's two-level turn state machine; splitting it would obscure event ordering.
@Suppress("CyclomaticComplexMethod")
private suspend fun runLoop(
    initialContext: AgentContext,
    newMessages: MutableList<AgentMessage>,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
    streamFunction: StreamFunction,
) {
    var currentContext = initialContext
    var currentModel = config.model
    var currentReasoning = config.streamOptions.reasoning
    var firstTurn = true
    var pendingMessages = config.getSteeringMessages()

    while (true) {
        var hasMoreToolCalls = true
        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            if (!firstTurn) emit.emit(AgentEvent.TurnStart) else firstTurn = false
            appendPendingMessages(pendingMessages, currentContext, newMessages, emit)
            pendingMessages = emptyList()

            val message =
                streamAssistantResponse(
                    currentContext,
                    config,
                    currentModel,
                    currentReasoning,
                    signal,
                    emit,
                    streamFunction,
                )
            newMessages += message

            if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                emit.emit(AgentEvent.TurnEnd(message, emptyList()))
                emit.emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }

            val toolCalls = message.content.filterIsInstance<ToolCall>()
            val toolBatch =
                when {
                    toolCalls.isEmpty() -> ExecutedToolCallBatch(emptyList(), terminate = false)
                    message.stopReason == StopReason.LENGTH -> failTruncatedToolCalls(toolCalls, emit)
                    else -> executeToolCalls(currentContext, message, config, signal, emit)
                }
            hasMoreToolCalls = toolCalls.isNotEmpty() && !toolBatch.terminate
            currentContext.messages += toolBatch.messages
            newMessages += toolBatch.messages

            emit.emit(AgentEvent.TurnEnd(message, toolBatch.messages))
            val turnContext = ShouldStopAfterTurnContext(message, toolBatch.messages, currentContext, newMessages)
            config.prepareNextTurn(turnContext)?.let { update ->
                currentContext = update.context ?: currentContext
                currentModel = update.model ?: currentModel
                if (update.thinkingLevel != null) currentReasoning = update.thinkingLevel.toAiThinkingLevel()
            }
            val stopContext = ShouldStopAfterTurnContext(message, toolBatch.messages, currentContext, newMessages)
            if (config.shouldStopAfterTurn(stopContext)) {
                emit.emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }
            pendingMessages = config.getSteeringMessages()
        }

        val followUpMessages = config.getFollowUpMessages()
        if (followUpMessages.isEmpty()) break
        pendingMessages = followUpMessages
    }

    emit.emit(AgentEvent.AgentEnd(newMessages.toList()))
}

private suspend fun appendPendingMessages(
    pendingMessages: List<AgentMessage>,
    context: AgentContext,
    newMessages: MutableList<AgentMessage>,
    emit: AgentEventSink,
) {
    pendingMessages.forEach { message ->
        emit.emit(AgentEvent.MessageStart(message))
        emit.emit(AgentEvent.MessageEnd(message))
        context.messages += message
        newMessages += message
    }
}

private suspend fun streamAssistantResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    model: Model,
    reasoning: ThinkingLevel?,
    signal: AbortSignal?,
    emit: AgentEventSink,
    streamFunction: StreamFunction,
): AssistantMessage {
    val transformed = config.transformContext(context.messages, signal)
    val llmMessages: List<Message> = config.convertToLlm(transformed)
    val llmContext =
        Context(
            systemPrompt = context.systemPrompt,
            messages = llmMessages,
            tools = context.tools?.map(AgentTool::asLlmTool),
        )
    val apiKey = config.getApiKey(model.provider) ?: config.streamOptions.stream.apiKey
    val options =
        config.streamOptions.copy(
            stream = config.streamOptions.stream.copy(apiKey = apiKey, signal = signal),
            reasoning = reasoning,
        )
    val response = streamFunction.stream(model, llmContext, options)
    var addedPartial = false

    for (event in response) {
        when (event) {
            is AssistantMessageEvent.Start -> {
                context.messages += event.partial
                addedPartial = true
                emit.emit(AgentEvent.MessageStart(event.partial.copy()))
            }

            is AssistantMessageEvent.Done,
            is AssistantMessageEvent.Error,
            -> {
                return finishAssistantMessage(response.result(), addedPartial, context, emit)
            }

            else -> {
                val partial = event.partialMessage()
                if (addedPartial && partial != null) {
                    context.messages[context.messages.lastIndex] = partial
                    emit.emit(AgentEvent.MessageUpdate(partial.copy(), event))
                }
            }
        }
    }

    return finishAssistantMessage(response.result(), addedPartial, context, emit)
}

private suspend fun finishAssistantMessage(
    message: AssistantMessage,
    addedPartial: Boolean,
    context: AgentContext,
    emit: AgentEventSink,
): AssistantMessage {
    if (addedPartial) {
        context.messages[context.messages.lastIndex] = message
    } else {
        context.messages += message
        emit.emit(AgentEvent.MessageStart(message.copy()))
    }
    emit.emit(AgentEvent.MessageEnd(message))
    return message
}

private fun createAgentStream(): EventStream<AgentEvent, List<AgentMessage>> =
    EventStream(
        isComplete = { it is AgentEvent.AgentEnd },
        extractResult = { event -> (event as AgentEvent.AgentEnd).messages },
    )

private fun requireCanContinue(context: AgentContext) {
    require(context.messages.isNotEmpty()) { "Cannot continue: no messages in context" }
    require(context.messages.last() !is AssistantMessage) { "Cannot continue from message role: assistant" }
}
