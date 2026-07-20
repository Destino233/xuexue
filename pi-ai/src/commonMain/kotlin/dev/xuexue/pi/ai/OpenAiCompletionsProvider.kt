@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength")

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** OpenAI Chat Completions and the OpenAI-compatible Mistral conversations API. */
class OpenAiCompletionsProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "openai-completions"
    override val name: String = "OpenAI-compatible Chat Completions"
    override val supportedApis: Set<Api> = setOf("openai-completions", "mistral-conversations")

    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        launchProviderStream(
            model = model,
            block = { accumulator ->
                checkNotAborted(options.stream.signal)
                val environment = ambientProviderEnvironment() + options.stream.env.orEmpty()
                val retention = resolveCacheRetention(options.stream.cacheRetention, environment)
                val generatedHeaders = sessionAffinityHeaders(model, options.stream.sessionId, retention)
                val payload = buildPayload(model, context, options, retention)
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                var headers =
                    mergeProviderHeaders(
                        model,
                        options.stream,
                        defaults = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
                        generated = generatedHeaders,
                    )
                if (options.stream.apiKey
                        .orEmpty()
                        .isNotBlank() && !hasHeader(headers, "Authorization")
                ) {
                    headers = headers + ("Authorization" to "Bearer ${options.stream.apiKey}")
                }
                if (options.stream.apiKey
                        .orEmpty()
                        .isBlank() && !hasHeader(headers, "Authorization")
                ) {
                    error("No API key for provider: ${model.provider}")
                }
                val response =
                    transport.execute(
                        HttpRequest(
                            method = "POST",
                            url = completionEndpoint(model),
                            headers = headers,
                            body = requestPayload.toString(),
                            signal = options.stream.signal,
                            timeoutMs = options.stream.timeoutMs,
                        ),
                    )
                options.stream.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
                if (response.status !in 200..299) error(providerError(response))
                accumulator.start()
                var finishReason: StopReason? = null
                response.lines.decodeSse().collect { event ->
                    val data = event.data.trim()
                    if (data.isBlank() || data == "[DONE]") return@collect
                    val root = jsonObjectOrNull(data) ?: return@collect
                    accumulator.setResponseId(root.providerString("id"))
                    val choice = root.providerArray("choices")?.firstOrNull()?.jsonObject
                    val delta = choice?.providerObject("delta")
                    delta?.providerString("content")?.let(accumulator::appendText)
                    delta?.providerString("reasoning_content")?.let(accumulator::appendThinking)
                    delta?.providerString("reasoning")?.let(accumulator::appendThinking)
                    delta?.providerArray("tool_calls")?.forEach { call ->
                        val callObject = call as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val index = callObject.providerInt("index") ?: 0
                        val function = callObject.providerObject("function")
                        accumulator.appendToolCall(
                            index = index,
                            id = callObject.providerString("id"),
                            name = function?.providerString("name"),
                            argumentsDelta = function?.providerString("arguments"),
                        )
                    }
                    root.providerObject("usage")?.let { usage ->
                        val details = usage.providerObject("prompt_tokens_details")
                        val completionDetails = usage.providerObject("completion_tokens_details")
                        accumulator.setUsage(
                            input = usage.providerLong("prompt_tokens") ?: 0,
                            output = usage.providerLong("completion_tokens") ?: 0,
                            cacheRead = details?.providerLong("cached_tokens") ?: 0,
                            reasoning = completionDetails?.providerLong("reasoning_tokens"),
                        )
                    }
                    choice?.providerString("finish_reason")?.let { finish ->
                        if (finish != "null") finishReason = mapStopReason(finish)
                    }
                }
                accumulator.finish(finishReason)
            },
        )

    private fun buildPayload(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
        retention: CacheRetention,
    ): JsonElement =
        buildJsonObject {
            put("model", model.id)
            put("stream", true)
            if (model.api != "mistral-conversations") {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            val cacheControl = anthropicCacheControl(model, retention).takeIf { model.compatString("cacheControlFormat") == "anthropic" }
            val messages =
                buildList {
                    context.systemPrompt?.takeIf(String::isNotBlank)?.let { prompt ->
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", prompt)
                            },
                        )
                    }
                    transformMessagesForModel(context.messages, model).forEach { add(toOpenAiMessage(it)) }
                }.toMutableList()
            if (cacheControl != null) applyAnthropicCacheControl(messages, cacheControl)
            put("messages", JsonArray(messages))
            if (context.tools.orEmpty().isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        context.tools.orEmpty().forEachIndexed { index, tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.parameters)
                                        },
                                    )
                                    if (cacheControl != null && index == context.tools.orEmpty().lastIndex) {
                                        put("cache_control", cacheControl)
                                    }
                                },
                            )
                        }
                    },
                )
            }
            options.stream.temperature?.let { put("temperature", it) }
            options.stream.maxTokens?.let { put("max_tokens", it) }
            options.reasoning?.takeIf { model.reasoning }?.let { level ->
                put("reasoning_effort", level.toOpenAiReasoningEffort())
                if (model.api == "mistral-conversations") {
                    put("prompt_mode", "reasoning")
                }
            }
            val cacheKeyAllowed =
                retention != CacheRetention.NONE &&
                    (
                        model.baseUrl.contains("api.openai.com", ignoreCase = true) ||
                            (retention == CacheRetention.LONG && model.supportsLongCacheRetention())
                    )
            if (cacheKeyAllowed) {
                clampOpenAiPromptCacheKey(options.stream.sessionId)?.let { put("prompt_cache_key", it) }
            }
            if (retention == CacheRetention.LONG && model.supportsLongCacheRetention()) {
                put("prompt_cache_retention", "24h")
            }
        }

    private fun sessionAffinityHeaders(
        model: Model,
        sessionId: String?,
        retention: CacheRetention,
    ): Map<String, String> {
        if (retention == CacheRetention.NONE || sessionId.isNullOrBlank() || model.compatBoolean("sendSessionAffinityHeaders") != true) {
            return emptyMap()
        }
        return mapOf(
            "session_id" to sessionId,
            "x-client-request-id" to sessionId,
            "x-session-affinity" to sessionId,
        )
    }

    private fun applyAnthropicCacheControl(
        messages: MutableList<JsonElement>,
        cacheControl: JsonObject,
    ) {
        messages
            .indexOfFirst { message ->
                message.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "system"
            }.takeIf { it >= 0 }
            ?.let { index ->
                addCacheControlToTextMessage(messages[index], cacheControl)?.let { updated -> messages[index] = updated }
            }
        for (index in messages.lastIndex downTo 0) {
            val role = messages[index].jsonObject["role"]?.jsonPrimitive?.contentOrNull
            if (role == "user" || role == "assistant") {
                val updated = addCacheControlToTextMessage(messages[index], cacheControl)
                if (updated != null) {
                    messages[index] = updated
                    break
                }
            }
        }
    }

    private fun addCacheControlToTextMessage(
        message: JsonElement,
        cacheControl: JsonObject,
    ): JsonElement? {
        val objectValue = message.jsonObject
        val content = objectValue["content"]
        val updatedContent =
            content?.let {
                when (it) {
                    is JsonPrimitive -> {
                        it.contentOrNull
                            ?.takeIf(String::isNotEmpty)
                            ?.let { text ->
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                            put("cache_control", cacheControl)
                                        },
                                    )
                                }
                            }
                    }

                    is JsonArray -> {
                        val blocks = it.toMutableList()
                        val blockIndex =
                            blocks.indexOfLast { block ->
                                block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                            }
                        if (blockIndex < 0) {
                            null
                        } else {
                            blocks[blockIndex] =
                                buildJsonObject {
                                    blocks[blockIndex].jsonObject.forEach { (key, value) -> put(key, value) }
                                    put("cache_control", cacheControl)
                                }
                            JsonArray(blocks)
                        }
                    }

                    else -> {
                        null
                    }
                }
            }
        return updatedContent?.let { updated ->
            buildJsonObject {
                objectValue.forEach { (key, value) -> if (key != "content") put(key, value) }
                put("content", updated)
            }
        }
    }

    private fun completionEndpoint(model: Model): String =
        if (model.api == "mistral-conversations") {
            val base = model.baseUrl.trimEnd('/')
            if (base.endsWith("/v1")) "$base/chat/completions" else endpoint(base, "/v1/chat/completions")
        } else {
            endpoint(model.baseUrl, "/chat/completions")
        }

    private fun toOpenAiMessage(message: Message): JsonElement =
        when (message) {
            is UserMessage -> {
                buildJsonObject {
                    put("role", "user")
                    put("content", toOpenAiContent(message.content))
                }
            }

            is AssistantMessage -> {
                buildJsonObject {
                    put("role", "assistant")
                    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    if (text.isNotEmpty()) put("content", text) else put("content", JsonPrimitive(""))
                    val calls = message.content.filterIsInstance<ToolCall>()
                    if (calls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            buildJsonArray {
                                calls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            put(
                                                "function",
                                                buildJsonObject {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments.toString())
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            is ToolResultMessage -> {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.toolCallId)
                    put("content", toOpenAiResultContent(message.content))
                }
            }
        }

    private fun toOpenAiContent(content: UserContent): JsonElement =
        when (content) {
            is UserText -> {
                JsonPrimitive(content.value)
            }

            is UserBlocks -> {
                val blocks =
                    content.value.mapNotNull { block ->
                        when (block) {
                            is TextContent -> {
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", block.text)
                                }
                            }

                            is ImageContent -> {
                                buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:${block.mimeType};base64,${block.data}") })
                                }
                            }

                            else -> {
                                null
                            }
                        }
                    }
                JsonArray(blocks)
            }
        }

    private fun toOpenAiResultContent(content: List<ToolResultContent>): JsonElement =
        if (content.all { it is TextContent }) {
            JsonPrimitive(content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
        } else {
            JsonArray(
                content.mapNotNull { block ->
                    when (block) {
                        is TextContent -> {
                            buildJsonObject {
                                put("type", "text")
                                put("text", block.text)
                            }
                        }

                        is ImageContent -> {
                            buildJsonObject {
                                put("type", "text")
                                put("text", "[image:${block.mimeType}]")
                            }
                        }

                        else -> {
                            null
                        }
                    }
                },
            )
        }

    private fun mapStopReason(reason: String): StopReason =
        when (reason.lowercase()) {
            "length", "max_tokens" -> StopReason.LENGTH
            "tool_calls", "function_call" -> StopReason.TOOL_USE
            "content_filter" -> StopReason.ERROR
            else -> StopReason.STOP
        }

    private fun ThinkingLevel.toOpenAiReasoningEffort(): String =
        when (this) {
            ThinkingLevel.MINIMAL -> "minimal"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "high"
        }
}

private fun checkNotAborted(signal: AbortSignal?) {
    if (signal?.isAborted == true) throw kotlinx.coroutines.CancellationException("Request was aborted")
}
