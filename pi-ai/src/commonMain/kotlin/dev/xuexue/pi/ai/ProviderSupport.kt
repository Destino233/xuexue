@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package dev.xuexue.pi.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

internal val ProviderJson =
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

/** Host environment used by providers for ambient credentials and region defaults. */
expect fun ambientProviderEnvironment(): Map<String, String>

internal fun emptyUsage(): Usage =
    Usage(
        input = 0,
        output = 0,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 0,
        cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
    )

internal fun calculateProviderUsage(
    model: Model,
    input: Long,
    output: Long,
    cacheRead: Long = 0,
    cacheWrite: Long = 0,
    reasoning: Long? = null,
    cacheWrite1h: Long? = null,
): Usage {
    val inputCost = input * model.cost.input / 1_000_000.0
    val outputCost = output * model.cost.output / 1_000_000.0
    val cacheReadCost = cacheRead * model.cost.cacheRead / 1_000_000.0
    val cacheWriteCost = cacheWrite * model.cost.cacheWrite / 1_000_000.0
    return Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        cacheWrite1h = cacheWrite1h,
        reasoning = reasoning,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost =
            UsageCost(
                input = inputCost,
                output = outputCost,
                cacheRead = cacheReadCost,
                cacheWrite = cacheWriteCost,
                total = inputCost + outputCost + cacheReadCost + cacheWriteCost,
            ),
    )
}

internal data class SseEvent(
    val event: String?,
    val data: String,
    val raw: List<String>,
)

/** Decodes the line-oriented body returned by the JVM transport according to the SSE grammar. */
internal fun Flow<String>.decodeSse(): Flow<SseEvent> =
    flow {
        var event: String? = null
        val data = mutableListOf<String>()
        val raw = mutableListOf<String>()

        suspend fun flush() {
            if (event != null || data.isNotEmpty()) {
                emit(SseEvent(event, data.joinToString("\n"), raw.toList()))
            }
            event = null
            data.clear()
            raw.clear()
        }

        collect { line ->
            if (line.isEmpty()) {
                flush()
                return@collect
            }
            raw += line
            if (line.startsWith(":")) return@collect
            val delimiter = line.indexOf(':')
            val field = if (delimiter < 0) line else line.substring(0, delimiter)
            var value = if (delimiter < 0) "" else line.substring(delimiter + 1)
            if (value.startsWith(' ')) value = value.drop(1)
            when (field) {
                "event" -> event = value
                "data" -> data += value
            }
        }
        flush()
    }

internal fun jsonObjectOrNull(value: String): JsonObject? = runCatching { ProviderJson.parseToJsonElement(value).jsonObject }.getOrNull()

internal fun JsonObject.providerString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.providerLong(name: String): Long? = providerString(name)?.toLongOrNull()

internal fun JsonObject.providerInt(name: String): Int? = providerString(name)?.toIntOrNull()

internal fun JsonObject.providerObject(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.providerArray(name: String): JsonArray? = this[name] as? JsonArray

internal fun mergeProviderHeaders(
    model: Model,
    options: StreamOptions,
    defaults: Map<String, String> = emptyMap(),
    generated: Map<String, String> = emptyMap(),
): Map<String, String> {
    val merged = linkedMapOf<String, String>()

    fun add(source: Map<String, String>?) {
        source.orEmpty().forEach { (key, value) ->
            val previous = merged.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            if (previous != null) merged.remove(previous)
            merged[key] = value
        }
    }
    add(defaults)
    add(model.headers)
    add(generated)
    options.headers.orEmpty().forEach { (key, value) ->
        val previous = merged.keys.firstOrNull { it.equals(key, ignoreCase = true) }
        if (previous != null) merged.remove(previous)
        if (value != null) merged[key] = value
    }
    return merged
}

internal fun hasHeader(
    headers: Map<String, String>,
    name: String,
): Boolean = headers.keys.any { it.equals(name, ignoreCase = true) }

internal fun endpoint(
    baseUrl: String,
    suffix: String,
): String {
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith(suffix)) base else "$base$suffix"
}

internal const val MAX_PROVIDER_ERROR_BODY_CHARS = 4_000

/**
 * Reads and formats a non-success response without discarding the provider's
 * diagnostic body. Gateways frequently put the useful reason in JSON while
 * the status alone is reported by the transport.
 */
internal suspend fun providerError(
    response: HttpResponse,
    prefix: String? = null,
): String {
    val body = response.lines.toList().joinToString("\n")
    return formatProviderError(response.status, body, prefix)
}

internal fun formatProviderError(
    status: Int,
    rawBody: String,
    prefix: String? = null,
): String {
    val body = normalizeProviderErrorBody(rawBody)
    val reason = body ?: "empty response"
    return if (prefix.isNullOrBlank()) {
        "Provider returned HTTP $status: $reason"
    } else {
        "$prefix ($status): $reason"
    }
}

/** Keeps useful JSON/text reasons while treating whitespace and `{}` as empty. */
internal fun normalizeProviderErrorBody(rawBody: String): String? {
    val trimmed = rawBody.trim()
    val parsed = runCatching { ProviderJson.parseToJsonElement(trimmed) }.getOrNull()
    return if (trimmed.isEmpty() || (parsed is JsonObject && parsed.isEmpty())) {
        null
    } else {
        truncateProviderErrorText(trimmed, MAX_PROVIDER_ERROR_BODY_CHARS)
    }
}

internal fun truncateProviderErrorText(
    text: String,
    maxChars: Int = MAX_PROVIDER_ERROR_BODY_CHARS,
): String {
    require(maxChars > 0) { "maxChars must be positive" }
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "... [truncated ${text.length - maxChars} chars]"
}

internal suspend fun launchProviderStream(
    block: suspend (AssistantStreamAccumulator) -> Unit,
    model: Model,
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    // The caller owns the parent Job (Agent loop or Runtime).  Launching into
    // that context preserves prompt cancellation without creating a process-wide scope.
    val scope = CoroutineScope(currentCoroutineContext())
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val accumulator = AssistantStreamAccumulator(model) { event -> stream.push(event) }
        try {
            block(accumulator)
        } catch (error: CancellationException) {
            stream.push(accumulator.error(aborted = true, message = error.message ?: "Request was aborted"))
        } catch (error: Throwable) {
            stream.push(accumulator.error(aborted = false, message = error.message ?: error.toString()))
        }
    }
    return stream
}

/** Mutable event builder shared by the JSON/SSE provider adapters. */
internal class AssistantStreamAccumulator(
    private val model: Model,
    private val sink: (AssistantMessageEvent) -> Unit,
) {
    private val content = mutableListOf<AssistantContent>()
    private val toolStates = linkedMapOf<Int, ToolState>()
    private val closedTools = mutableSetOf<Int>()
    private var activeText = -1
    private var activeThinking = -1
    private val timestamp = Clock.System.now().toEpochMilliseconds()
    private var responseId: String? = null
    private var usage = emptyUsage()
    private var stopReason = StopReason.STOP
    private var errorMessage: String? = null

    fun start() {
        emit(AssistantMessageEvent.Start(partial()))
    }

    fun setResponseId(value: String?) {
        if (!value.isNullOrBlank() && responseId == null) responseId = value
    }

    fun setUsage(value: Usage) {
        usage = value
    }

    fun setUsage(
        input: Long,
        output: Long,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        reasoning: Long? = null,
        cacheWrite1h: Long? = null,
    ) {
        usage = calculateProviderUsage(model, input, output, cacheRead, cacheWrite, reasoning, cacheWrite1h)
    }

    fun appendText(delta: String) {
        if (delta.isEmpty()) return
        closeThinking()
        if (activeText < 0) {
            activeText = content.size
            content += TextContent("")
            emit(AssistantMessageEvent.TextStart(activeText, partial()))
        }
        val current = content[activeText] as TextContent
        content[activeText] = current.copy(text = current.text + delta)
        emit(AssistantMessageEvent.TextDelta(activeText, delta, partial()))
    }

    fun appendThinking(
        delta: String,
        signature: String? = null,
    ) {
        if (delta.isEmpty() && signature.isNullOrEmpty()) return
        closeText()
        if (activeThinking < 0) {
            activeThinking = content.size
            content += ThinkingContent("", thinkingSignature = signature)
            emit(AssistantMessageEvent.ThinkingStart(activeThinking, partial()))
        }
        val current = content[activeThinking] as ThinkingContent
        content[activeThinking] =
            current.copy(
                thinking = current.thinking + delta,
                thinkingSignature = current.thinkingSignature ?: signature,
            )
        if (delta.isNotEmpty()) emit(AssistantMessageEvent.ThinkingDelta(activeThinking, delta, partial()))
    }

    fun appendToolCall(
        index: Int,
        id: String?,
        name: String?,
        argumentsDelta: String?,
        thoughtSignature: String? = null,
        initialArguments: JsonObject? = null,
    ) {
        closeText()
        closeThinking()
        val state =
            toolStates.getOrPut(index) {
                val blockIndex = content.size
                // The first delta is applied below; keeping the initial block empty
                // avoids duplicating a provider's first function name/id fragment.
                content += ToolCall("", "", buildJsonObject {})
                emit(AssistantMessageEvent.ToolCallStart(blockIndex, partial()))
                ToolState(blockIndex)
            }
        var current = content[state.contentIndex] as ToolCall
        if (initialArguments != null && state.arguments.isEmpty() && initialArguments.isNotEmpty()) {
            state.arguments.append(initialArguments.toString())
            current = current.copy(arguments = initialArguments)
        }
        val nextId = id?.takeIf(String::isNotBlank) ?: current.id
        val nextName = (current.name + name.orEmpty()).ifBlank { current.name }
        state.arguments.append(argumentsDelta.orEmpty())
        val parsed = parsePartialJson(state.arguments.toString())
        content[state.contentIndex] =
            current.copy(
                id = nextId,
                name = nextName,
                arguments = parsed,
                thoughtSignature = current.thoughtSignature ?: thoughtSignature,
            )
        if (!argumentsDelta.isNullOrEmpty()) {
            emit(AssistantMessageEvent.ToolCallDelta(state.contentIndex, argumentsDelta, partial()))
        }
    }

    /**
     * Upserts a provider event that contains a complete JSON object rather than
     * a textual argument delta (for example Gemini function-call chunks).
     * Repeated identical chunks are ignored, while later object fields merge
     * into the same tool call instead of creating duplicate calls.
     */
    fun upsertToolCallArguments(
        index: Int,
        id: String?,
        name: String?,
        arguments: JsonObject,
        thoughtSignature: String? = null,
    ) {
        closeText()
        closeThinking()
        val state =
            toolStates.getOrPut(index) {
                val blockIndex = content.size
                content += ToolCall("", "", buildJsonObject {})
                emit(AssistantMessageEvent.ToolCallStart(blockIndex, partial()))
                ToolState(blockIndex)
            }
        val current = content[state.contentIndex] as ToolCall
        val mergedArguments =
            buildJsonObject {
                current.arguments.forEach { (key, value) -> put(key, value) }
                arguments.forEach { (key, value) -> put(key, value) }
            }
        val nextId = id?.takeIf(String::isNotBlank) ?: current.id
        val nextName = (current.name + name.orEmpty()).ifBlank { current.name }
        val previousArguments = current.arguments
        state.arguments.clear()
        state.arguments.append(mergedArguments.toString())
        content[state.contentIndex] =
            current.copy(
                id = nextId,
                name = nextName,
                arguments = mergedArguments,
                thoughtSignature = current.thoughtSignature ?: thoughtSignature,
            )
        if (mergedArguments != previousArguments) {
            emit(AssistantMessageEvent.ToolCallDelta(state.contentIndex, mergedArguments.toString(), partial()))
        }
    }

    /** Replaces the scratch JSON with a provider's final, authoritative value. */
    fun completeToolCall(
        index: Int,
        id: String?,
        name: String?,
        arguments: String?,
        thoughtSignature: String? = null,
    ) {
        val state =
            toolStates[index] ?: run {
                appendToolCall(index, id, name, null, thoughtSignature)
                toolStates[index] ?: return
            }
        val current = content[state.contentIndex] as ToolCall
        val nextId = id?.takeIf(String::isNotBlank) ?: current.id
        val nextName = (current.name + name.orEmpty()).ifBlank { current.name }
        val finalArguments = arguments?.takeIf(String::isNotBlank) ?: state.arguments.toString()
        val previous = state.arguments.toString()
        state.arguments.clear()
        state.arguments.append(finalArguments)
        content[state.contentIndex] =
            current.copy(
                id = nextId,
                name = nextName,
                arguments = parseStreamingJson(finalArguments) as? JsonObject ?: buildJsonObject {},
                thoughtSignature = current.thoughtSignature ?: thoughtSignature,
            )
        if (finalArguments != previous && !finalArguments.startsWith(previous)) {
            emit(AssistantMessageEvent.ToolCallDelta(state.contentIndex, finalArguments, partial()))
        } else if (finalArguments.startsWith(previous)) {
            val suffix = finalArguments.removePrefix(previous)
            if (suffix.isNotEmpty()) emit(AssistantMessageEvent.ToolCallDelta(state.contentIndex, suffix, partial()))
        }
    }

    fun finish(
        reason: StopReason? = null,
        message: String? = null,
    ) {
        closeText()
        closeThinking()
        toolStates.forEach { (index, state) ->
            if (closedTools.add(index)) {
                val call = content[state.contentIndex] as ToolCall
                val finalized = call.copy(arguments = parsePartialJson(state.arguments.toString()))
                content[state.contentIndex] = finalized
                emit(AssistantMessageEvent.ToolCallEnd(state.contentIndex, finalized, partial()))
            }
        }
        stopReason = reason ?: if (toolStates.isEmpty()) StopReason.STOP else StopReason.TOOL_USE
        errorMessage = message
        emit(
            if (stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED) {
                AssistantMessageEvent.Error(
                    if (stopReason == StopReason.ABORTED) FailureStopReason.ABORTED else FailureStopReason.ERROR,
                    partial(),
                )
            } else {
                AssistantMessageEvent.Done(stopReason.toSuccessfulReason(), partial())
            },
        )
    }

    fun error(
        aborted: Boolean,
        message: String,
    ): AssistantMessageEvent.Error {
        closeText()
        closeThinking()
        stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR
        errorMessage = message
        return AssistantMessageEvent.Error(
            if (aborted) FailureStopReason.ABORTED else FailureStopReason.ERROR,
            partial(),
        )
    }

    fun partial(): AssistantMessage =
        AssistantMessage(
            content = content.toList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            responseId = responseId,
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = timestamp,
        )

    private fun closeText() {
        if (activeText >= 0) {
            val text = (content[activeText] as TextContent).text
            emit(AssistantMessageEvent.TextEnd(activeText, text, partial()))
            activeText = -1
        }
    }

    private fun closeThinking() {
        if (activeThinking >= 0) {
            val thinking = (content[activeThinking] as ThinkingContent).thinking
            emit(AssistantMessageEvent.ThinkingEnd(activeThinking, thinking, partial()))
            activeThinking = -1
        }
    }

    private fun parsePartialJson(value: String): JsonObject = runCatching { ProviderJson.parseToJsonElement(value).jsonObject }.getOrDefault(buildJsonObject {})

    private fun emit(event: AssistantMessageEvent) = sink(event)

    private fun StopReason.toSuccessfulReason(): SuccessfulStopReason =
        when (this) {
            StopReason.LENGTH -> SuccessfulStopReason.LENGTH
            StopReason.TOOL_USE -> SuccessfulStopReason.TOOL_USE
            else -> SuccessfulStopReason.STOP
        }

    private data class ToolState(
        val contentIndex: Int,
        val arguments: StringBuilder = StringBuilder(),
    )
}
