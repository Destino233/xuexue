@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MaxLineLength",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package dev.xuexue.pi.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.max
import kotlin.time.Clock

private const val DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api"
private const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
private const val DEFAULT_TEXT_VERBOSITY = "low"
private const val DEFAULT_REASONING_SUMMARY = "auto"
private const val DEFAULT_MAX_RETRIES = 0
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val BASE_RETRY_DELAY_MS = 1_000L
private const val JWT_CLAIM_PATH = "https://api.openai.com/auth"

/**
 * OpenAI Codex Responses adapter from Pi 0.80.6.
 *
 * The public transport deliberately remains HTTP/SSE. Pi's WebSocket path is a
 * runtime-specific optimization; this adapter keeps the same request and event
 * semantics while allowing the XueXue JVM transport to own connection details.
 */
class OpenAiCodexResponsesProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "openai-codex"
    override val name: String = "OpenAI Codex (ChatGPT OAuth)"
    override val supportedApis: Set<Api> = setOf("openai-codex-responses")

    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        launchProviderStream(
            model = model,
            block = { accumulator ->
                checkCodexAborted(options.stream.signal)
                val token =
                    options.stream.apiKey?.takeIf(String::isNotBlank)
                        ?: error("No API key for provider: ${model.provider}")
                val accountId = extractAccountId(token)
                val payload = buildPayload(model, context, options)
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                val headers = buildHeaders(model, options.stream, accountId, token)
                val maxRetries = (options.stream.maxRetries ?: DEFAULT_MAX_RETRIES).coerceAtLeast(0)
                val response =
                    executeCodexRequest(
                        transport = transport,
                        model = model,
                        options = options.stream,
                        headers = headers,
                        body = requestPayload.toString(),
                        maxRetries = maxRetries,
                    )

                accumulator.start()
                var finishReason: StopReason? = null
                var finishMessage: String? = null
                response.lines.decodeSse().collect { event ->
                    checkCodexAborted(options.stream.signal)
                    val data = event.data.trim()
                    if (data.isBlank() || data == "[DONE]") return@collect
                    val root = jsonObjectOrNull(data) ?: return@collect
                    val eventName = event.event?.takeIf(String::isNotBlank) ?: root.providerString("type").orEmpty()
                    when (eventName) {
                        "error" -> {
                            val nested = root.providerObject("error")
                            val message = root.providerString("message") ?: nested?.providerString("message")
                            val code = root.providerString("code") ?: nested?.providerString("code")
                            error("Codex error: ${message ?: code ?: data}")
                        }

                        "response.created", "response.in_progress" -> {
                            root.providerObject("response")?.providerString("id")?.let(accumulator::setResponseId)
                        }

                        "response.output_item.added" -> {
                            val item = root.providerObject("item")
                            when (item?.providerString("type")) {
                                "function_call" -> {
                                    accumulator.appendToolCall(
                                        index = root.providerInt("output_index") ?: 0,
                                        id = responseToolCallId(item),
                                        name = item.providerString("name"),
                                        argumentsDelta = item.providerString("arguments"),
                                    )
                                }

                                "reasoning" -> {
                                    accumulator.appendThinking(
                                        delta = reasoningItemText(item),
                                        signature = item.providerString("encrypted_content"),
                                    )
                                }
                            }
                        }

                        "response.output_text.delta", "response.refusal.delta" -> {
                            root.providerString("delta")?.let(accumulator::appendText)
                        }

                        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                            root.providerString("delta")?.let(accumulator::appendThinking)
                        }

                        "response.reasoning_summary_part.done" -> {
                            accumulator.appendThinking("\n\n")
                        }

                        "response.function_call_arguments.delta" -> {
                            accumulator.appendToolCall(
                                index = root.providerInt("output_index") ?: 0,
                                id = null,
                                name = null,
                                argumentsDelta = root.providerString("delta"),
                            )
                        }

                        "response.function_call_arguments.done" -> {
                            accumulator.completeToolCall(
                                index = root.providerInt("output_index") ?: 0,
                                id = null,
                                name = null,
                                arguments = root.providerString("arguments"),
                            )
                        }

                        "response.output_item.done" -> {
                            val item = root.providerObject("item")
                            when (item?.providerString("type")) {
                                "function_call" -> {
                                    accumulator.completeToolCall(
                                        index = root.providerInt("output_index") ?: 0,
                                        id = responseToolCallId(item),
                                        name = item.providerString("name"),
                                        arguments = item.providerString("arguments"),
                                    )
                                }

                                "reasoning" -> {
                                    val text = reasoningItemText(item)
                                    if (text.isNotBlank()) accumulator.appendThinking(text)
                                }
                            }
                        }

                        "response.failed" -> {
                            val responseObject = root.providerObject("response") ?: root
                            val errorObject = responseObject.providerObject("error")
                            finishReason = StopReason.ERROR
                            finishMessage =
                                errorObject?.providerString("message")
                                    ?: responseObject.providerString("error")
                                    ?: "Codex response failed"
                            setCodexUsage(accumulator, responseObject.providerObject("usage"))
                            accumulator.setResponseId(responseObject.providerString("id"))
                        }

                        "response.completed", "response.done", "response.incomplete" -> {
                            val responseObject = root.providerObject("response") ?: root
                            accumulator.setResponseId(responseObject.providerString("id"))
                            setCodexUsage(accumulator, responseObject.providerObject("usage"))
                            val status = responseObject.providerString("status") ?: eventName.removePrefix("response.")
                            finishReason =
                                when (status) {
                                    "incomplete" -> StopReason.LENGTH
                                    "cancelled" -> StopReason.ABORTED
                                    "failed" -> StopReason.ERROR
                                    else -> finishReason
                                }
                            if (status == "failed" && finishMessage == null) finishMessage = "Codex response failed"
                        }
                    }
                }
                accumulator.finish(finishReason, finishMessage)
            },
        )

    private fun buildPayload(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): JsonElement {
        val transformedMessages = transformMessagesForModel(context.messages, model)
        val transformedContext = context.copy(messages = transformedMessages)
        val toolPlacement = splitDeferredTools(transformedContext, model.compatBoolean("supportsToolSearch") == true)
        val loadedToolNames = mutableSetOf<String>()
        return buildJsonObject {
            put("model", model.id)
            put("store", false)
            put("stream", true)
            put("instructions", context.systemPrompt?.takeIf(String::isNotBlank) ?: DEFAULT_INSTRUCTIONS)
            putJsonObject("text") { put("verbosity", options.stream.textVerbosity ?: DEFAULT_TEXT_VERBOSITY) }
            putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
            clampOpenAiPromptCacheKey(options.stream.sessionId)?.let { put("prompt_cache_key", it) }
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            putJsonArray("input") {
                transformedMessages.forEachIndexed { index, message ->
                    toCodexMessages(message, index, toolPlacement.deferred, loadedToolNames).forEach(::add)
                }
            }
            if (toolPlacement.immediate.isNotEmpty()) {
                putJsonArray("tools") {
                    toolPlacement.immediate.forEach { tool -> add(codexToolPayload(tool)) }
                }
            }
            options.stream.temperature?.let { put("temperature", it) }
            options.stream.serviceTier
                ?.takeIf(String::isNotBlank)
                ?.let { put("service_tier", it) }
            options.reasoning?.takeIf { model.reasoning }?.let { level ->
                val effort = level.toCodexEffort(model)
                if (effort != null) {
                    putJsonObject("reasoning") {
                        put("effort", effort)
                        put("summary", options.stream.reasoningSummary ?: DEFAULT_REASONING_SUMMARY)
                    }
                }
            }
        }
    }

    private fun toCodexMessages(
        message: Message,
        messageIndex: Int,
        deferredTools: Map<String, Tool>,
        loadedToolNames: MutableSet<String>,
    ): List<JsonElement> =
        when (message) {
            is UserMessage -> {
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", toCodexUserContent(message.content))
                    },
                )
            }

            is AssistantMessage -> {
                buildList {
                    message.content.filterIsInstance<ThinkingContent>().forEach { thinking ->
                        thinking.thinkingSignature?.let { signature ->
                            jsonObjectOrNull(signature)?.let(::add)
                        }
                    }
                    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    if (text.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("status", "completed")
                                put("id", "msg_pi_$messageIndex")
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "output_text")
                                            put("text", text)
                                            putJsonArray("annotations") {}
                                        },
                                    )
                                }
                            },
                        )
                    }
                    message.content.filterIsInstance<ToolCall>().forEach { call ->
                        val parts = call.id.split("|", limit = 2)
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", parts.first())
                                if (parts.size == 2) put("id", parts[1])
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            },
                        )
                    }
                }
            }

            is ToolResultMessage -> {
                buildList {
                    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    val images = message.content.filterIsInstance<ImageContent>()
                    val output: JsonElement =
                        if (images.isNotEmpty()) {
                            buildJsonArray {
                                if (text.isNotBlank()) {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", text)
                                        },
                                    )
                                }
                                images.forEach { image ->
                                    add(
                                        buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", "data:${image.mimeType};base64,${image.data}")
                                        },
                                    )
                                }
                            }
                        } else {
                            JsonPrimitive(text.ifBlank { "(no tool output)" })
                        }
                    add(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", message.toolCallId.substringBefore("|"))
                            put("output", output)
                        },
                    )
                    val newlyLoadedTools =
                        message.addedToolNames.orEmpty().mapNotNull { name ->
                            val tool = deferredTools[name] ?: return@mapNotNull null
                            if (!loadedToolNames.add(name)) return@mapNotNull null
                            tool
                        }
                    if (newlyLoadedTools.isNotEmpty()) {
                        val names = newlyLoadedTools.map(Tool::name)
                        val searchCallId =
                            "pi_tool_load_${deferredToolShortHash("${message.toolCallId}:${names.joinToString(",")}")}"
                        add(
                            buildJsonObject {
                                put("type", "tool_search_call")
                                put("call_id", searchCallId)
                                put("execution", "client")
                                put("status", "completed")
                                putJsonObject("arguments") {
                                    put("query", names.joinToString(" "))
                                    put("limit", names.size)
                                }
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "tool_search_output")
                                put("call_id", searchCallId)
                                put("execution", "client")
                                put("status", "completed")
                                putJsonArray("tools") {
                                    newlyLoadedTools.forEach { tool -> add(codexToolPayload(tool, deferred = true)) }
                                }
                            },
                        )
                    }
                }
            }
        }

    private fun toCodexUserContent(content: UserContent): JsonElement =
        when (content) {
            is UserText -> {
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", content.value)
                        },
                    )
                }
            }

            is UserBlocks -> {
                buildJsonArray {
                    content.value.forEach { block ->
                        when (block) {
                            is TextContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", block.text)
                                    },
                                )
                            }

                            is ImageContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put("detail", "auto")
                                        put("image_url", "data:${block.mimeType};base64,${block.data}")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun codexToolPayload(
        tool: Tool,
        deferred: Boolean = false,
    ): JsonElement =
        buildJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
            put("strict", JsonNull)
            if (deferred) put("defer_loading", true)
        }

    private fun buildHeaders(
        model: Model,
        options: StreamOptions,
        accountId: String,
        token: String,
    ): Map<String, String> {
        var headers =
            mergeProviderHeaders(
                model,
                options,
                defaults =
                    mapOf(
                        "Accept" to "text/event-stream",
                        "Content-Type" to "application/json",
                    ),
            )
        headers = headers.withCodexHeader("Authorization", "Bearer $token")
        headers = headers.withCodexHeader("chatgpt-account-id", accountId)
        headers = headers.withCodexHeader("originator", "pi")
        headers = headers.withCodexHeader("User-Agent", "pi (xuexue JVM)")
        headers = headers.withCodexHeader("OpenAI-Beta", "responses=experimental")
        options.sessionId?.takeIf(String::isNotBlank)?.let { sessionId ->
            headers = headers.withCodexHeader("session-id", sessionId)
            headers = headers.withCodexHeader("x-client-request-id", sessionId)
        }
        return headers
    }

    private fun ThinkingLevel.toCodexEffort(model: Model): String? {
        val modelLevel =
            when (this) {
                ThinkingLevel.MINIMAL -> ModelThinkingLevel.MINIMAL
                ThinkingLevel.LOW -> ModelThinkingLevel.LOW
                ThinkingLevel.MEDIUM -> ModelThinkingLevel.MEDIUM
                ThinkingLevel.HIGH -> ModelThinkingLevel.HIGH
                ThinkingLevel.XHIGH -> ModelThinkingLevel.XHIGH
                ThinkingLevel.MAX -> ModelThinkingLevel.MAX
            }
        return model.thinkingLevelMap?.get(modelLevel) ?: when (this) {
            ThinkingLevel.MINIMAL -> "minimal"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "xhigh"
        }
    }
}

private suspend fun executeCodexRequest(
    transport: HttpTransport,
    model: Model,
    options: StreamOptions,
    headers: Map<String, String>,
    body: String,
    maxRetries: Int,
    attempt: Int = 0,
): HttpResponse {
    checkCodexAborted(options.signal)
    // HttpTransport deliberately hides platform-specific IO/CompletionException types.
    val response =
        try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = resolveCodexUrl(model.baseUrl),
                    headers = headers,
                    body = body,
                    signal = options.signal,
                    timeoutMs = options.timeoutMs,
                ),
            )
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (attempt < maxRetries) {
                delayCodexRetry(attempt, null, options)
                executeCodexRequest(transport, model, options, headers, body, maxRetries, attempt + 1)
            } else {
                throw error
            }
        }
    options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
    if (response.status in 200..299) return response

    val errorText = providerError(response, prefix = "Codex API error")
    if (attempt < maxRetries && isCodexRetryable(response.status, errorText)) {
        delayCodexRetry(attempt, response.headers, options, capRetryAfter = response.status == 429)
        return executeCodexRequest(transport, model, options, headers, body, maxRetries, attempt + 1)
    }
    error(errorText)
}

private fun setCodexUsage(
    accumulator: AssistantStreamAccumulator,
    usage: JsonObject?,
) {
    if (usage == null) return
    val details = usage.providerObject("input_tokens_details")
    val cached = details?.providerLong("cached_tokens") ?: 0
    val cacheWrite = details?.providerLong("cache_write_tokens") ?: 0
    accumulator.setUsage(
        input = max(0, (usage.providerLong("input_tokens") ?: 0) - cached - cacheWrite),
        output = usage.providerLong("output_tokens") ?: 0,
        cacheRead = cached,
        cacheWrite = cacheWrite,
        reasoning = usage.providerObject("output_tokens_details")?.providerLong("reasoning_tokens"),
    )
}

private fun responseToolCallId(item: JsonObject): String? {
    val callId = item.providerString("call_id") ?: return item.providerString("id")
    val itemId = item.providerString("id")
    return if (itemId.isNullOrBlank()) callId else "$callId|$itemId"
}

private fun reasoningItemText(item: JsonObject): String =
    item
        .providerArray("summary")
        ?.mapNotNull { (it as? JsonObject)?.providerString("text") }
        ?.joinToString("\n\n")
        .orEmpty()

private fun checkCodexAborted(signal: AbortSignal?) {
    if (signal?.isAborted == true) throw kotlinx.coroutines.CancellationException("Request was aborted")
}

private suspend fun delayCodexRetry(
    attempt: Int,
    headers: Map<String, String>?,
    options: StreamOptions,
    capRetryAfter: Boolean = false,
) {
    val requested = headers?.retryAfterDelayMs()
    val exponential = BASE_RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(20))
    val delayMs =
        (requested ?: exponential).let { value ->
            if (requested != null && capRetryAfter) {
                val cap = options.maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
                if (cap > 0) value.coerceAtMost(cap) else value
            } else {
                value
            }
        }
    delay(delayMs.coerceAtLeast(0))
    checkCodexAborted(options.signal)
}

private fun isCodexRetryable(
    status: Int,
    errorText: String,
): Boolean {
    if (status == 429 && Regex("GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|insufficient_quota|out of budget|quota exceeded|billing", RegexOption.IGNORE_CASE).containsMatchIn(errorText)) {
        return false
    }
    return status == 429 || status == 500 || status == 502 || status == 503 || status == 504 ||
        Regex("rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused", RegexOption.IGNORE_CASE).containsMatchIn(errorText)
}

private fun Map<String, String>.retryAfterDelayMs(): Long? {
    val retryAfterMs = entries.firstOrNull { it.key.equals("retry-after-ms", ignoreCase = true) }?.value
    val retryAfter = entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
    val retryAfterSeconds = retryAfter?.toDoubleOrNull()
    return when {
        retryAfterMs != null -> {
            retryAfterMs.toDoubleOrNull()?.toLong()?.coerceAtLeast(0)
        }

        retryAfter == null -> {
            null
        }

        retryAfterSeconds != null -> {
            (retryAfterSeconds * 1_000).toLong().coerceAtLeast(0)
        }

        else -> {
            parseHttpDateEpochMs(retryAfter)?.let {
                (it - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
            }
        }
    }
}

private fun Map<String, String>.withCodexHeader(
    name: String,
    value: String,
): Map<String, String> =
    linkedMapOf<String, String>().also { result ->
        forEach { (key, existing) -> if (!key.equals(name, ignoreCase = true)) result[key] = existing }
        result[name] = value
    }

private fun resolveCodexUrl(baseUrl: String): String {
    val raw = baseUrl.trim().ifBlank { DEFAULT_CODEX_BASE_URL }.trimEnd('/')
    return when {
        raw.endsWith("/codex/responses") -> raw
        raw.endsWith("/codex") -> "$raw/responses"
        else -> "$raw/codex/responses"
    }
}

private fun extractAccountId(token: String): String =
    runCatching {
        val payload = token.split('.').getOrNull(1) ?: error("Invalid token")
        val json = ProviderJson.parseToJsonElement(decodeBase64Url(payload).decodeToString()).jsonObject
        json[JWT_CLAIM_PATH]
            ?.jsonObject
            ?.providerString("chatgpt_account_id")
            ?.takeIf(String::isNotBlank)
            ?: error("No account ID in token")
    }.getOrElse { error("Failed to extract accountId from token") }

private fun decodeBase64Url(value: String): ByteArray {
    val normalized = value.replace('-', '+').replace('_', '/')
    val output = ByteArray((normalized.length * 6) / 8 + 2)
    var buffer = 0
    var bits = 0
    var count = 0
    normalized.forEach { character ->
        if (character == '=') return@forEach
        val digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf(character)
        if (digit < 0) error("Invalid base64url payload")
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[count++] = (buffer shr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return output.copyOf(count)
}

private fun parseHttpDateEpochMs(value: String): Long? =
    runCatching {
        val tokens = value.trim().replace(Regex("\\s+"), " ").split(' ')
        require(tokens.size >= 4)
        val dateTokens = if (tokens[0].endsWith(',')) tokens.drop(1) else tokens
        val parsed =
            when {
                dateTokens.size >= 3 && dateTokens[0].contains('-') -> {
                    val parts = dateTokens[0].split('-')
                    require(parts.size == 3 && dateTokens.size >= 2)
                    Quad(
                        requireNotNull(parts[0].toIntOrNull()),
                        requireNotNull(monthNumber(parts[1])),
                        normalizeHttpYear(parts[2]),
                        dateTokens[1],
                    )
                }

                dateTokens.size >= 4 && dateTokens[0].toIntOrNull() != null -> {
                    Quad(
                        requireNotNull(dateTokens[0].toIntOrNull()),
                        requireNotNull(monthNumber(dateTokens[1])),
                        requireNotNull(dateTokens[2].toIntOrNull()),
                        dateTokens[3],
                    )
                }

                tokens.size >= 5 -> {
                    Quad(
                        requireNotNull(tokens[2].toIntOrNull()),
                        requireNotNull(monthNumber(tokens[1])),
                        requireNotNull(tokens[4].toIntOrNull()),
                        tokens[3],
                    )
                }

                else -> {
                    error("Unsupported HTTP date")
                }
            }
        val clock = parsed.time.split(':').map(String::toIntOrNull)
        require(clock.size == 3 && clock.all { it != null })
        val hour = requireNotNull(clock[0])
        val minute = requireNotNull(clock[1])
        val second = requireNotNull(clock[2])
        daysFromCivil(parsed.year, parsed.month, parsed.day) * 86_400_000L +
            (hour * 3_600L + minute * 60L + second) * 1_000L
    }.getOrNull()

private data class Quad(
    val day: Int,
    val month: Int,
    val year: Int,
    val time: String,
)

private fun monthNumber(value: String): Int? =
    listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .indexOf(value.lowercase())
        .takeIf { it >= 0 }
        ?.plus(1)

private fun normalizeHttpYear(value: String): Int {
    val year = value.toIntOrNull() ?: return -1
    return if (year < 100) year + if (year >= 70) 1900 else 2000 else year
}

private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    var adjustedYear = year
    if (month <= 2) adjustedYear--
    val era = adjustedYear / 400
    val yearOfEra = adjustedYear - era * 400
    val monthPrime = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
