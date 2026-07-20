@file:Suppress("MatchingDeclarationName", "TooGenericExceptionCaught", "TooManyFunctions")

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantContent
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.AssistantMessageEventStream
import dev.xuexue.pi.ai.CacheRetention
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.HttpRequest
import dev.xuexue.pi.ai.HttpTransport
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.PiJson
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.SuccessfulStopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingBudgets
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ThinkingLevel
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.Transport
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.ai.defaultHttpTransport
import dev.xuexue.pi.ai.parseStreamingJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** Options sent to a Pi-compatible LLM proxy, excluding the local auth abort signal. */
data class ProxyStreamOptions(
    val authToken: String,
    val proxyUrl: String,
    val signal: AbortSignal? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoning: ThinkingLevel? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val headers: Map<String, String?>? = null,
    val metadata: JsonObject? = null,
    val transport: Transport? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val maxRetryDelayMs: Long? = null,
)

/**
 * Streams through Pi's HTTP proxy while reconstructing its omitted partial
 * assistant message field client-side, matching packages/agent/src/proxy.ts.
 */
suspend fun streamProxy(
    model: Model,
    context: Context,
    options: ProxyStreamOptions,
    httpTransport: HttpTransport = defaultHttpTransport(),
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    val scope = CoroutineScope(currentCoroutineContext())
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var partial = emptyProxyAssistantMessage(model)
        val toolArguments = mutableMapOf<Int, String>()
        try {
            ensureNotAborted(options.signal)
            val response =
                httpTransport.execute(
                    HttpRequest(
                        method = "POST",
                        url = "${options.proxyUrl}/api/stream",
                        headers =
                            mapOf(
                                "Authorization" to "Bearer ${options.authToken}",
                                "Content-Type" to "application/json",
                            ),
                        body = buildProxyRequest(model, context, options).toString(),
                        signal = options.signal,
                    ),
                )
            if (response.status !in 200..299) error(proxyErrorMessage(response.status, response.lines.toList()))

            response.lines.collect { line ->
                ensureNotAborted(options.signal)
                if (!line.startsWith("data: ")) return@collect
                val data = line.removePrefix("data: ").trim()
                if (data.isBlank()) return@collect
                val proxyEvent = PiJson.parseToJsonElement(data).jsonObject
                partial = processProxyEvent(proxyEvent, partial, toolArguments, stream)
            }
            ensureNotAborted(options.signal)
            stream.end()
        } catch (error: Throwable) {
            val aborted = options.signal?.isAborted == true || error is CancellationException
            partial =
                partial.copy(
                    stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
                    errorMessage = error.message ?: error.toString(),
                )
            stream.push(
                AssistantMessageEvent.Error(
                    reason = if (aborted) FailureStopReason.ABORTED else FailureStopReason.ERROR,
                    error = partial,
                ),
            )
            stream.end()
        }
    }
    return stream
}

private fun buildProxyRequest(
    model: Model,
    context: Context,
    options: ProxyStreamOptions,
): JsonObject =
    buildJsonObject {
        put("model", PiJson.encodeToJsonElement(Model.serializer(), model))
        put("context", context.toProxyJson())
        put("options", options.toProxyJson())
    }

private fun Context.toProxyJson(): JsonObject =
    buildJsonObject {
        systemPrompt?.let { put("systemPrompt", it) }
        put(
            "messages",
            buildJsonArray {
                messages.forEach { message ->
                    add(
                        PiJson.encodeToJsonElement(
                            dev.xuexue.pi.ai.Message
                                .serializer(),
                            message,
                        ),
                    )
                }
            },
        )
        tools?.let { availableTools ->
            put(
                "tools",
                buildJsonArray {
                    availableTools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            },
                        )
                    }
                },
            )
        }
    }

private fun ProxyStreamOptions.toProxyJson(): JsonObject =
    buildJsonObject {
        putNumericProxyOptions(this@toProxyJson)
        putSessionProxyOptions(this@toProxyJson)
        putHeadersAndMetadata(this@toProxyJson)
        putThinkingProxyOptions(this@toProxyJson)
    }

private fun JsonObjectBuilder.putNumericProxyOptions(options: ProxyStreamOptions) {
    options.temperature?.let { put("temperature", it) }
    options.maxTokens?.let { put("maxTokens", it) }
    options.maxRetryDelayMs?.let { put("maxRetryDelayMs", it) }
}

private fun JsonObjectBuilder.putSessionProxyOptions(options: ProxyStreamOptions) {
    options.reasoning?.let { put("reasoning", it.proxyValue()) }
    options.cacheRetention?.let { put("cacheRetention", it.proxyValue()) }
    options.sessionId?.let { put("sessionId", it) }
    options.transport?.let { put("transport", it.proxyValue()) }
}

private fun JsonObjectBuilder.putHeadersAndMetadata(options: ProxyStreamOptions) {
    options.headers?.let { values ->
        val serializedHeaders = values.mapValues { (_, value) -> value?.let(::JsonPrimitive) ?: JsonNull }
        put("headers", JsonObject(serializedHeaders))
    }
    options.metadata?.let { put("metadata", it) }
}

private fun JsonObjectBuilder.putThinkingProxyOptions(options: ProxyStreamOptions) {
    options.thinkingBudgets?.let { budgets ->
        put(
            "thinkingBudgets",
            buildJsonObject {
                budgets.minimal?.let { put("minimal", it) }
                budgets.low?.let { put("low", it) }
                budgets.medium?.let { put("medium", it) }
                budgets.high?.let { put("high", it) }
            },
        )
    }
}

private fun processProxyEvent(
    event: JsonObject,
    currentPartial: AssistantMessage,
    toolArguments: MutableMap<Int, String>,
    stream: AssistantMessageEventStream,
): AssistantMessage {
    val type = event.requiredString("type")
    var partial = currentPartial
    when (type) {
        "start" -> {
            stream.push(AssistantMessageEvent.Start(partial))
        }

        "text_start", "text_delta", "text_end" -> {
            partial = processTextProxyEvent(type, event, partial, stream)
        }

        "thinking_start", "thinking_delta", "thinking_end" -> {
            partial = processThinkingProxyEvent(type, event, partial, stream)
        }

        "toolcall_start", "toolcall_delta", "toolcall_end" -> {
            partial =
                processToolCallProxyEvent(
                    type,
                    event,
                    partial,
                    toolArguments,
                    stream,
                )
        }

        "done" -> {
            partial =
                partial.copy(
                    stopReason = event.requiredSuccessfulReason(),
                    usage = event.requiredUsage(),
                )
            stream.push(
                AssistantMessageEvent.Done(partial.stopReason.toSuccessfulReason(), partial),
            )
        }

        "error" -> {
            partial =
                partial.copy(
                    stopReason = event.requiredFailureReason(),
                    errorMessage = event.optionalString("errorMessage"),
                    usage = event.requiredUsage(),
                )
            stream.push(
                AssistantMessageEvent.Error(partial.stopReason.toFailureReason(), partial),
            )
        }

        else -> {
            error("Unhandled proxy event type: $type")
        }
    }
    return partial
}

private fun processTextProxyEvent(
    type: String,
    event: JsonObject,
    currentPartial: AssistantMessage,
    stream: AssistantMessageEventStream,
): AssistantMessage {
    val index = event.optionalInt("contentIndex").requiredFor(type)
    return when (type) {
        "text_start" -> {
            currentPartial.replaceContent(index, TextContent(""))
        }

        "text_delta" -> {
            val delta = event.requiredString("delta")
            val text = currentPartial.content.at(index, type) as TextContent
            currentPartial.replaceContent(index, text.copy(text = text.text + delta))
        }

        "text_end" -> {
            val text = currentPartial.content.at(index, type) as TextContent
            currentPartial.replaceContent(
                index,
                text.copy(textSignature = event.optionalString("contentSignature")),
            )
        }

        else -> {
            error("Unhandled text proxy event: $type")
        }
    }.also { partial ->
        when (type) {
            "text_start" -> {
                stream.push(AssistantMessageEvent.TextStart(index, partial))
            }

            "text_delta" -> {
                stream.push(AssistantMessageEvent.TextDelta(index, event.requiredString("delta"), partial))
            }

            "text_end" -> {
                val text = partial.content[index] as TextContent
                stream.push(AssistantMessageEvent.TextEnd(index, text.text, partial))
            }
        }
    }
}

private fun processThinkingProxyEvent(
    type: String,
    event: JsonObject,
    currentPartial: AssistantMessage,
    stream: AssistantMessageEventStream,
): AssistantMessage {
    val index = event.optionalInt("contentIndex").requiredFor(type)
    return when (type) {
        "thinking_start" -> {
            currentPartial.replaceContent(index, ThinkingContent(""))
        }

        "thinking_delta" -> {
            val delta = event.requiredString("delta")
            val thinking = currentPartial.content.at(index, type) as ThinkingContent
            currentPartial.replaceContent(
                index,
                thinking.copy(thinking = thinking.thinking + delta),
            )
        }

        "thinking_end" -> {
            val thinking = currentPartial.content.at(index, type) as ThinkingContent
            currentPartial.replaceContent(
                index,
                thinking.copy(thinkingSignature = event.optionalString("contentSignature")),
            )
        }

        else -> {
            error("Unhandled thinking proxy event: $type")
        }
    }.also { partial ->
        when (type) {
            "thinking_start" -> {
                stream.push(AssistantMessageEvent.ThinkingStart(index, partial))
            }

            "thinking_delta" -> {
                stream.push(
                    AssistantMessageEvent.ThinkingDelta(index, event.requiredString("delta"), partial),
                )
            }

            "thinking_end" -> {
                val thinking = partial.content[index] as ThinkingContent
                stream.push(AssistantMessageEvent.ThinkingEnd(index, thinking.thinking, partial))
            }
        }
    }
}

private fun processToolCallProxyEvent(
    type: String,
    event: JsonObject,
    currentPartial: AssistantMessage,
    toolArguments: MutableMap<Int, String>,
    stream: AssistantMessageEventStream,
): AssistantMessage {
    val index = event.optionalInt("contentIndex").requiredFor(type)
    val partial =
        when (type) {
            "toolcall_start" -> {
                toolArguments[index] = ""
                currentPartial.replaceContent(
                    index,
                    ToolCall(
                        event.requiredString("id"),
                        event.requiredString("toolName"),
                        JsonObject(emptyMap()),
                    ),
                )
            }

            "toolcall_delta" -> {
                val delta = event.requiredString("delta")
                val arguments = toolArguments.getOrDefault(index, "") + delta
                toolArguments[index] = arguments
                val call = currentPartial.content.at(index, type) as ToolCall
                val parsed = parseStreamingJson(arguments) as? JsonObject ?: JsonObject(emptyMap())
                currentPartial.replaceContent(index, call.copy(arguments = parsed))
            }

            "toolcall_end" -> {
                currentPartial
            }

            else -> {
                error("Unhandled tool call proxy event: $type")
            }
        }
    val call = partial.content.at(index, type) as ToolCall
    when (type) {
        "toolcall_start" -> {
            stream.push(AssistantMessageEvent.ToolCallStart(index, partial))
        }

        "toolcall_delta" -> {
            stream.push(
                AssistantMessageEvent.ToolCallDelta(index, event.requiredString("delta"), partial),
            )
        }

        "toolcall_end" -> {
            toolArguments.remove(index)
            stream.push(AssistantMessageEvent.ToolCallEnd(index, call, partial))
        }
    }
    return partial
}

private fun AssistantMessage.replaceContent(
    index: Int,
    value: AssistantContent,
): AssistantMessage {
    require(index in 0..content.size) {
        "Proxy content index $index is outside the current content"
    }
    val updated = content.toMutableList()
    if (index == updated.size) updated += value else updated[index] = value
    return copy(content = updated)
}

private fun List<AssistantContent>.at(
    index: Int,
    eventType: String,
): AssistantContent = getOrNull(index) ?: error("Received $eventType for missing content index $index")

private fun JsonObject.requiredUsage(): Usage = PiJson.decodeFromJsonElement(Usage.serializer(), getValue("usage"))

private fun JsonObject.requiredSuccessfulReason(): dev.xuexue.pi.ai.StopReason =
    when (requiredString("reason")) {
        "stop" -> dev.xuexue.pi.ai.StopReason.STOP
        "length" -> dev.xuexue.pi.ai.StopReason.LENGTH
        "toolUse" -> dev.xuexue.pi.ai.StopReason.TOOL_USE
        else -> error("Invalid successful proxy stop reason: ${requiredString("reason")}")
    }

private fun JsonObject.requiredFailureReason(): dev.xuexue.pi.ai.StopReason =
    when (requiredString("reason")) {
        "aborted" -> dev.xuexue.pi.ai.StopReason.ABORTED
        "error" -> dev.xuexue.pi.ai.StopReason.ERROR
        else -> error("Invalid failed proxy stop reason: ${requiredString("reason")}")
    }

private fun dev.xuexue.pi.ai.StopReason.toSuccessfulReason(): SuccessfulStopReason =
    when (this) {
        dev.xuexue.pi.ai.StopReason.STOP -> SuccessfulStopReason.STOP
        dev.xuexue.pi.ai.StopReason.LENGTH -> SuccessfulStopReason.LENGTH
        dev.xuexue.pi.ai.StopReason.TOOL_USE -> SuccessfulStopReason.TOOL_USE
        else -> error("Expected a successful proxy stop reason, got $this")
    }

private fun dev.xuexue.pi.ai.StopReason.toFailureReason(): FailureStopReason =
    when (this) {
        dev.xuexue.pi.ai.StopReason.ABORTED -> FailureStopReason.ABORTED
        dev.xuexue.pi.ai.StopReason.ERROR -> FailureStopReason.ERROR
        else -> error("Expected a failed proxy stop reason, got $this")
    }

private fun JsonObject.requiredString(
    name: String,
): String = this[name]?.jsonPrimitive?.contentOrNull ?: error("Proxy event is missing '$name'")

private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.optionalInt(name: String): Int? = optionalString(name)?.toIntOrNull()

private fun Int?.requiredFor(type: String): Int = this ?: error("Proxy $type event is missing contentIndex")

private fun ThinkingLevel.proxyValue(): String =
    when (this) {
        ThinkingLevel.MINIMAL -> "minimal"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        ThinkingLevel.MAX -> "max"
    }

private fun CacheRetention.proxyValue(): String =
    when (this) {
        CacheRetention.NONE -> "none"
        CacheRetention.SHORT -> "short"
        CacheRetention.LONG -> "long"
    }

private fun Transport.proxyValue(): String =
    when (this) {
        Transport.SSE -> "sse"
        Transport.WEBSOCKET -> "websocket"
        Transport.WEBSOCKET_CACHED -> "websocket-cached"
        Transport.AUTO -> "auto"
    }

private fun emptyProxyAssistantMessage(model: Model): AssistantMessage =
    AssistantMessage(
        content = emptyList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage(0, 0, 0, 0, totalTokens = 0, cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = dev.xuexue.pi.ai.StopReason.STOP,
        timestamp = Clock.System.now().toEpochMilliseconds(),
    )

private fun ensureNotAborted(signal: AbortSignal?) {
    if (signal?.isAborted == true) throw CancellationException("Request aborted by user")
}

private fun proxyErrorMessage(
    status: Int,
    lines: List<String>,
): String {
    val body = lines.joinToString("\n")
    val error =
        runCatching { PiJson.parseToJsonElement(body).jsonObject.optionalString("error") }
            .getOrNull()
    return if (error != null) "Proxy error: $error" else "Proxy error: HTTP $status"
}
