package dev.xuexue.platform

import dev.xuexue.core.AgentStreamEvent
import dev.xuexue.core.PiAgentMessage
import dev.xuexue.core.PiAgentTool
import dev.xuexue.core.PiToolCall
import dev.xuexue.core.PiToolDefinition
import dev.xuexue.pi.agent.AfterToolCallContext
import dev.xuexue.pi.agent.AfterToolCallResult
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AssistantContent
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.AssistantMessageEventStream
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import dev.xuexue.pi.ai.AgentMessage as CoreAgentMessage

class DesktopPiAgentAdapter(
    private val scope: CoroutineScope,
    private val client: AgentClient,
) : StreamFunction {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val projection = StreamingAssistantProjection(model)
        stream.push(AssistantMessageEvent.Start(projection.partial()))
        val signal = options.stream.signal
        if (signal?.isAborted == true) {
            stream.push(projection.failure(aborted = true, message = "Operation aborted"))
            return stream
        }

        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response =
                        client.complete(
                            messages = context.toLegacyMessages(),
                            tools =
                                context.tools.orEmpty().map { tool ->
                                    PiToolDefinition(tool.name, tool.description, tool.parameters)
                                },
                            config =
                                AgentProviderConfig(
                                    endpoint = model.baseUrl,
                                    model = model.id,
                                    apiKey = options.stream.apiKey.orEmpty(),
                                ),
                            onEvent = { event -> projection.consume(event).forEach(stream::push) },
                        )
                    projection.complete(response).forEach(stream::push)
                } catch (error: CancellationException) {
                    stream.push(projection.failure(aborted = true, message = error.message ?: "Operation aborted"))
                } catch (error: Exception) {
                    stream.push(projection.failure(aborted = false, message = error.message ?: error.toString()))
                }
            }
        val subscription = signal?.onAbort(job::cancel)
        job.invokeOnCompletion { subscription?.dispose() }
        if (signal?.isAborted == true) job.cancel() else job.start()
        return stream
    }
}

fun adaptDesktopAgentTools(tools: List<PiAgentTool>): List<AgentTool> =
    tools.map { legacy ->
        AgentTool(
            name = legacy.definition.name,
            label = legacy.definition.name,
            description = legacy.definition.description,
            parameters = legacy.definition.parameters,
            execute = { callId, arguments, _, onUpdate ->
                val execution =
                    legacy.execute(callId, arguments) { detail ->
                        onUpdate?.update(
                            AgentToolResult(
                                content = listOf(TextContent(detail)),
                                details = buildJsonObject { put("detail", detail) },
                            ),
                        )
                    }
                AgentToolResult(
                    content = listOf(TextContent(execution.output)),
                    details =
                        buildJsonObject {
                            put("failed", execution.failed)
                            execution.fileDiff?.let { diff ->
                                put(
                                    "fileDiff",
                                    buildJsonObject {
                                        put("relativePath", diff.relativePath)
                                        put("patch", diff.patch)
                                    },
                                )
                            }
                        },
                )
            },
        )
    }

fun desktopToolAfterCall(context: AfterToolCallContext): AfterToolCallResult? {
    val details = context.result.details as? JsonObject ?: return null
    return if (details["failed"]?.jsonPrimitive?.booleanOrNull == true) {
        AfterToolCallResult(isError = true)
    } else {
        null
    }
}

fun legacyProtocolToAgentMessages(
    messages: List<PiAgentMessage>,
    model: Model,
): List<CoreAgentMessage> =
    messages.mapIndexedNotNull { index, message ->
        val timestamp = index.toLong()
        when (message.role) {
            "user" -> {
                UserMessage(UserText(message.content), timestamp)
            }

            "assistant" -> {
                AssistantMessage(
                    content =
                        buildList {
                            if (message.content.isNotEmpty()) add(TextContent(message.content))
                            addAll(message.toolCalls.map(PiToolCall::toToolCall))
                        },
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    usage = emptyUsage(),
                    stopReason = if (message.toolCalls.isEmpty()) StopReason.STOP else StopReason.TOOL_USE,
                    errorMessage = message.content.takeIf { message.error },
                    timestamp = timestamp,
                )
            }

            "tool" -> {
                ToolResultMessage(
                    toolCallId = message.toolCallId ?: return@mapIndexedNotNull null,
                    toolName = message.name.orEmpty(),
                    content = listOf(TextContent(message.content)),
                    isError = message.error,
                    timestamp = timestamp,
                )
            }

            else -> {
                null
            }
        }
    }

fun agentMessagesToLegacyProtocol(messages: List<CoreAgentMessage>): List<PiAgentMessage> =
    messages
        .filterIsInstance<Message>()
        .mapNotNull(Message::toLegacyMessage)

fun coreUserMessage(
    text: String,
    timestamp: Long = Clock.System.now().toEpochMilliseconds(),
): UserMessage = UserMessage(UserText(text), timestamp)

private class StreamingAssistantProjection(
    private val model: Model,
) {
    private val order = mutableListOf<StreamBlock>()
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private val timestamp = Clock.System.now().toEpochMilliseconds()

    fun partial(
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
        toolCalls: List<ToolCall> = emptyList(),
        usage: Usage = EMPTY_USAGE,
    ): AssistantMessage =
        AssistantMessage(
            content = content() + toolCalls,
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = timestamp,
        )

    fun consume(event: AgentStreamEvent): List<AssistantMessageEvent> =
        when (event) {
            is AgentStreamEvent.TextDelta -> consumeText(event.text)
            is AgentStreamEvent.ReasoningDelta -> consumeThinking(event.text)
            else -> emptyList()
        }

    fun complete(response: dev.xuexue.core.PiModelResponse): List<AssistantMessageEvent> {
        if (text.isEmpty() && response.content.isNotEmpty()) {
            order += StreamBlock.TEXT
            text.append(response.content)
        }
        val toolCalls = response.toolCalls.map(PiToolCall::toToolCall)
        val stopReason = if (toolCalls.isEmpty()) StopReason.STOP else StopReason.TOOL_USE
        val message = partial(stopReason, toolCalls = toolCalls, usage = response.toUsage())
        val events = mutableListOf<AssistantMessageEvent>()
        order.forEach { block ->
            when (block) {
                StreamBlock.TEXT -> {
                    events += AssistantMessageEvent.TextEnd(indexOf(block), text.toString(), message)
                }

                StreamBlock.THINKING -> {
                    events += AssistantMessageEvent.ThinkingEnd(indexOf(block), thinking.toString(), message)
                }
            }
        }
        events +=
            AssistantMessageEvent.Done(
                if (toolCalls.isEmpty()) SuccessfulStopReason.STOP else SuccessfulStopReason.TOOL_USE,
                message,
            )
        return events
    }

    fun failure(
        aborted: Boolean,
        message: String,
    ): AssistantMessageEvent.Error {
        val reason = if (aborted) StopReason.ABORTED else StopReason.ERROR
        val failure = partial(reason, errorMessage = message)
        return AssistantMessageEvent.Error(
            if (aborted) FailureStopReason.ABORTED else FailureStopReason.ERROR,
            failure,
        )
    }

    private fun consumeText(delta: String): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        if (StreamBlock.TEXT !in order) {
            order += StreamBlock.TEXT
            events += AssistantMessageEvent.TextStart(indexOf(StreamBlock.TEXT), partial())
        }
        text.append(delta)
        events += AssistantMessageEvent.TextDelta(indexOf(StreamBlock.TEXT), delta, partial())
        return events
    }

    private fun consumeThinking(delta: String): List<AssistantMessageEvent> {
        val events = mutableListOf<AssistantMessageEvent>()
        if (StreamBlock.THINKING !in order) {
            order += StreamBlock.THINKING
            events += AssistantMessageEvent.ThinkingStart(indexOf(StreamBlock.THINKING), partial())
        }
        thinking.append(delta)
        events += AssistantMessageEvent.ThinkingDelta(indexOf(StreamBlock.THINKING), delta, partial())
        return events
    }

    private fun content(): List<AssistantContent> =
        order.map { block ->
            when (block) {
                StreamBlock.TEXT -> TextContent(text.toString())
                StreamBlock.THINKING -> ThinkingContent(thinking.toString())
            }
        }

    private fun indexOf(block: StreamBlock): Int = order.indexOf(block)

    private enum class StreamBlock {
        TEXT,
        THINKING,
    }

    private companion object {
        val EMPTY_USAGE =
            Usage(
                input = 0,
                output = 0,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = 0,
                cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
            )
    }
}

private fun Context.toLegacyMessages(): List<PiAgentMessage> =
    buildList {
        systemPrompt?.takeIf(String::isNotBlank)?.let { add(PiAgentMessage("system", it)) }
        messages.mapNotNullTo(this) { message -> message.toLegacyMessage() }
    }

private fun Message.toLegacyMessage(): PiAgentMessage? =
    when (this) {
        is UserMessage -> {
            PiAgentMessage("user", content.toText())
        }

        is AssistantMessage -> {
            PiAgentMessage(
                role = "assistant",
                content = content.filterIsInstance<TextContent>().joinToString("") { it.text },
                toolCalls = content.filterIsInstance<ToolCall>().map { PiToolCall(it.id, it.name, it.arguments) },
                error = stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED,
            )
        }

        is ToolResultMessage -> {
            PiAgentMessage(
                role = "tool",
                content = content.filterIsInstance<TextContent>().joinToString("") { it.text },
                toolCallId = toolCallId,
                name = toolName,
                error = isError,
            )
        }
    }

private fun dev.xuexue.pi.ai.UserContent.toText(): String =
    when (this) {
        is UserText -> value
        is UserBlocks -> value.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

private fun PiToolCall.toToolCall(): ToolCall = ToolCall(id, name, arguments)

private fun dev.xuexue.core.PiModelResponse.toUsage(): Usage {
    val input = inputTokens?.toLong() ?: 0L
    val output = outputTokens?.toLong() ?: 0L
    val totalCost = costUsd ?: 0.0
    return Usage(
        input = input,
        output = output,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = input + output,
        cost = UsageCost(0.0, totalCost, 0.0, 0.0, totalCost),
    )
}

private fun emptyUsage(): Usage =
    Usage(
        input = 0,
        output = 0,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 0,
        cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
    )
