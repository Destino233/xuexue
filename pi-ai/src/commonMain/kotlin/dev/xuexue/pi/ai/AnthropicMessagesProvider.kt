@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength", "TooManyFunctions")

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Anthropic Messages streaming API, including tool/thinking block events. */
class AnthropicMessagesProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "anthropic-messages"
    override val name: String = "Anthropic Messages"
    override val supportedApis: Set<Api> = setOf("anthropic-messages")

    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        launchProviderStream(
            model = model,
            block = { accumulator ->
                if (options.stream.signal?.isAborted == true) {
                    throw kotlinx.coroutines.CancellationException("Request was aborted")
                }
                val environment = ambientProviderEnvironment() + options.stream.env.orEmpty()
                val retention = resolveCacheRetention(options.stream.cacheRetention, environment)
                val payload = buildPayload(model, context, options, retention, isAnthropicOAuthToken(options.stream.apiKey))
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                val hasOAuthHeader =
                    options.stream.headers
                        .orEmpty()
                        .keys
                        .any { it.equals("Authorization", ignoreCase = true) }
                var headers =
                    mergeProviderHeaders(
                        model,
                        options.stream,
                        defaults =
                            mapOf(
                                "Content-Type" to "application/json",
                                "Accept" to "text/event-stream",
                                "anthropic-version" to "2023-06-01",
                            ),
                        generated = sessionAffinityHeaders(model, options.stream.sessionId, retention),
                    )
                if (options.stream.apiKey
                        .orEmpty()
                        .isNotBlank()
                ) {
                    if (hasOAuthHeader ||
                        options.stream.apiKey
                            .orEmpty()
                            .startsWith("sk-ant-oat")
                    ) {
                        headers = headers + ("Authorization" to "Bearer ${options.stream.apiKey}")
                    } else if (!hasHeader(headers, "x-api-key")) {
                        headers = headers + ("x-api-key" to options.stream.apiKey.orEmpty())
                    }
                }
                if (!hasHeader(headers, "x-api-key") && !hasHeader(headers, "Authorization")) {
                    error("No API key for provider: ${model.provider}")
                }
                val response =
                    transport.execute(
                        HttpRequest(
                            method = "POST",
                            url = endpoint(model.baseUrl, "/v1/messages"),
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
                    val root = jsonObjectOrNull(event.data.trim()) ?: return@collect
                    when (event.event ?: root.providerString("type")) {
                        "message_start" -> {
                            val message = root.providerObject("message") ?: return@collect
                            accumulator.setResponseId(message.providerString("id"))
                            val usage = message.providerObject("usage")
                            accumulator.setUsage(
                                input = usage?.providerLong("input_tokens") ?: 0,
                                output = usage?.providerLong("output_tokens") ?: 0,
                                cacheRead = usage?.providerLong("cache_read_input_tokens") ?: 0,
                                cacheWrite = usage?.providerLong("cache_creation_input_tokens") ?: 0,
                                cacheWrite1h = usage?.providerObject("cache_creation")?.providerLong("ephemeral_1h_input_tokens"),
                            )
                        }

                        "content_block_start" -> {
                            val index = root.providerInt("index") ?: 0
                            val block = root.providerObject("content_block") ?: return@collect
                            when (block.providerString("type")) {
                                "text" -> {
                                    block.providerString("text")?.let(accumulator::appendText)
                                }

                                "thinking" -> {
                                    accumulator.appendThinking(block.providerString("thinking").orEmpty(), block.providerString("signature"))
                                }

                                "redacted_thinking" -> {
                                    accumulator.appendThinking("[Reasoning redacted]", block.providerString("data"))
                                }

                                "tool_use" -> {
                                    accumulator.appendToolCall(
                                        index = index,
                                        id = block.providerString("id"),
                                        name = block.providerString("name"),
                                        argumentsDelta = null,
                                        initialArguments = block.providerObject("input"),
                                    )
                                }
                            }
                        }

                        "content_block_delta" -> {
                            val index = root.providerInt("index") ?: 0
                            val delta = root.providerObject("delta") ?: return@collect
                            when (delta.providerString("type")) {
                                "text_delta" -> {
                                    accumulator.appendText(delta.providerString("text").orEmpty())
                                }

                                "thinking_delta" -> {
                                    accumulator.appendThinking(delta.providerString("thinking").orEmpty())
                                }

                                "signature_delta" -> {
                                    accumulator.appendThinking("", delta.providerString("signature"))
                                }

                                "input_json_delta" -> {
                                    accumulator.appendToolCall(
                                        index = index,
                                        id = null,
                                        name = null,
                                        argumentsDelta = delta.providerString("partial_json"),
                                    )
                                }
                            }
                        }

                        "message_delta" -> {
                            val delta = root.providerObject("delta")
                            val usage = root.providerObject("usage")
                            finishReason = delta?.providerString("stop_reason")?.let(::mapStopReason)
                            if (usage != null) {
                                accumulator.setUsage(
                                    input = usage.providerLong("input_tokens") ?: 0,
                                    output = usage.providerLong("output_tokens") ?: 0,
                                    cacheRead = usage.providerLong("cache_read_input_tokens") ?: 0,
                                    cacheWrite = usage.providerLong("cache_creation_input_tokens") ?: 0,
                                    reasoning = usage.providerObject("output_tokens_details")?.providerLong("thinking_tokens"),
                                )
                            }
                        }
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
        isOAuthToken: Boolean,
    ): JsonElement =
        buildJsonObject {
            val cacheControl = anthropicCacheControl(model, retention)
            val normalizeToolName: (String) -> String =
                if (isOAuthToken) ::normalizeClaudeCodeToolName else { name -> name }
            val transformedMessages = transformMessagesForModel(context.messages, model)
            val toolPlacement =
                splitDeferredTools(
                    context.copy(messages = transformedMessages),
                    model.compatBoolean("supportsToolReferences") ?: defaultSupportsToolReferences(model),
                    normalizeToolName,
                )
            var immediateTools = toolPlacement.immediate
            var deferredTools = toolPlacement.deferred.values.toList()
            // Anthropic requires at least one eagerly visible tool for a tool
            // call to start the deferred-loading sequence.
            if (immediateTools.isEmpty() && deferredTools.isNotEmpty()) {
                immediateTools = deferredTools
                deferredTools = emptyList()
            }
            val deferredToolNames = deferredTools.mapTo(mutableSetOf()) { tool -> normalizeToolName(tool.name) }
            put("model", model.id)
            put("max_tokens", options.stream.maxTokens ?: model.maxTokens)
            put("stream", true)
            context.systemPrompt?.takeIf(String::isNotBlank)?.let { prompt ->
                if (cacheControl == null) {
                    put("system", prompt)
                } else {
                    putJsonArray("system") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", prompt)
                                put("cache_control", cacheControl)
                            },
                        )
                    }
                }
            }
            val messages =
                toAnthropicMessages(
                    transformedMessages,
                    isOAuthToken,
                    deferredToolNames,
                    normalizeToolName,
                ).toMutableList()
            if (cacheControl != null) addCacheControlToLastUserMessage(messages, cacheControl)
            put("messages", JsonArray(messages))
            if (immediateTools.isNotEmpty() || deferredTools.isNotEmpty()) {
                putJsonArray("tools") {
                    immediateTools.forEachIndexed { index, tool ->
                        add(
                            anthropicToolPayload(
                                tool = tool,
                                isOAuthToken = isOAuthToken,
                                deferred = false,
                                cacheControl =
                                    cacheControl?.takeIf {
                                        model.compatBoolean("supportsCacheControlOnTools") != false &&
                                            index == immediateTools.lastIndex
                                    },
                            ),
                        )
                    }
                    deferredTools.forEach { tool ->
                        add(anthropicToolPayload(tool, isOAuthToken, deferred = true, cacheControl = null))
                    }
                }
            }
            val reasoning = options.reasoning
            if (model.reasoning && reasoning != null) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", options.thinkingBudgets?.forLevel(reasoning) ?: defaultBudget(model, reasoning))
                }
            }
            if (reasoning == null) options.stream.temperature?.let { put("temperature", it) }
        }

    private fun sessionAffinityHeaders(
        model: Model,
        sessionId: String?,
        retention: CacheRetention,
    ): Map<String, String> {
        if (retention == CacheRetention.NONE || sessionId.isNullOrBlank() || model.compatBoolean("sendSessionAffinityHeaders") != true) {
            return emptyMap()
        }
        return mapOf("x-session-affinity" to sessionId)
    }

    private fun addCacheControlToLastUserMessage(
        messages: MutableList<JsonElement>,
        cacheControl: kotlinx.serialization.json.JsonObject,
    ) {
        var updated = false
        for (index in messages.lastIndex downTo 0) {
            val message = messages[index]
            val content =
                message.jsonObject["content"]
                    ?.takeIf { message.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user" }
            val updatedContent =
                content?.let {
                    when (it) {
                        is JsonPrimitive -> {
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", it.content)
                                        put("cache_control", cacheControl)
                                    },
                                )
                            }
                        }

                        is JsonArray -> {
                            val blocks = it.toMutableList()
                            val blockIndex =
                                blocks.indexOfLast { block ->
                                    block.jsonObject["type"]?.jsonPrimitive?.contentOrNull in
                                        setOf("text", "image", "tool_result")
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
            if (!updated && updatedContent != null) {
                messages[index] =
                    buildJsonObject {
                        message.jsonObject.forEach { (key, value) -> if (key != "content") put(key, value) }
                        put("content", updatedContent)
                    }
                updated = true
            }
        }
    }

    private fun toAnthropicMessages(
        messages: List<Message>,
        isOAuthToken: Boolean,
        deferredToolNames: Set<String>,
        normalizeToolName: (String) -> String,
    ): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        val loadedToolNames = mutableSetOf<String>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message !is ToolResultMessage) {
                result += toAnthropicMessage(message, isOAuthToken)
                index++
                continue
            }

            val toolResults = mutableListOf<JsonElement>()
            val siblingContent = mutableListOf<JsonElement>()
            var next = index
            while (next < messages.size && messages[next] is ToolResultMessage) {
                val converted =
                    convertAnthropicToolResult(
                        messages[next] as ToolResultMessage,
                        isOAuthToken,
                        deferredToolNames,
                        loadedToolNames,
                        normalizeToolName,
                    )
                toolResults += converted.first
                siblingContent += converted.second
                next++
            }
            result +=
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        toolResults.forEach(::add)
                        siblingContent.forEach(::add)
                    }
                }
            index = next
        }
        return result
    }

    private fun toAnthropicMessage(
        message: Message,
        isOAuthToken: Boolean,
    ): JsonElement =
        when (message) {
            is UserMessage -> {
                buildJsonObject {
                    put("role", "user")
                    put("content", toAnthropicContent(message.content))
                }
            }

            is AssistantMessage -> {
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        message.content.forEach { block ->
                            when (block) {
                                is TextContent -> {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        },
                                    )
                                }

                                is ThinkingContent -> {
                                    add(
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", block.thinking)
                                            block.thinkingSignature?.let { put("signature", it) }
                                        },
                                    )
                                }

                                is ToolCall -> {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", block.id)
                                            put("name", if (isOAuthToken) normalizeClaudeCodeToolName(block.name) else block.name)
                                            put("input", block.arguments)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is ToolResultMessage -> {
                error("Tool results must be converted as a consecutive block")
            }
        }

    private fun convertAnthropicToolResult(
        message: ToolResultMessage,
        isOAuthToken: Boolean,
        deferredToolNames: Set<String>,
        loadedToolNames: MutableSet<String>,
        normalizeToolName: (String) -> String,
    ): Pair<JsonElement, List<JsonElement>> {
        val references = mutableListOf<JsonElement>()
        message.addedToolNames.orEmpty().forEach { name ->
            val normalizedName = normalizeToolName(name)
            if (normalizedName !in deferredToolNames || !loadedToolNames.add(normalizedName)) return@forEach
            references +=
                buildJsonObject {
                    put("type", "tool_reference")
                    put("tool_name", if (isOAuthToken) normalizeClaudeCodeToolName(name) else name)
                }
        }
        val convertedContent = toAnthropicToolResultContent(message.content)
        val toolResult =
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", message.toolCallId)
                put("is_error", message.isError)
                if (references.isEmpty()) {
                    put("content", convertedContent)
                } else {
                    put("content", JsonArray(references))
                }
            }
        val sibling =
            if (references.isEmpty()) {
                emptyList()
            } else if (convertedContent is JsonPrimitive) {
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", convertedContent.content)
                    },
                )
            } else {
                (convertedContent as? JsonArray).orEmpty()
            }
        return toolResult to sibling
    }

    private fun toAnthropicToolResultContent(content: List<ToolResultContent>): JsonElement {
        val hasImages = content.any { it is ImageContent }
        if (!hasImages) return JsonPrimitive(content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
        val blocks =
            content
                .map { block ->
                    when (block) {
                        is TextContent -> {
                            buildJsonObject {
                                put("type", "text")
                                put("text", block.text)
                            }
                        }

                        is ImageContent -> {
                            buildJsonObject {
                                put("type", "image")
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", block.mimeType)
                                        put("data", block.data)
                                    },
                                )
                            }
                        }
                    }
                }.toMutableList()
        if (blocks.none { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }) {
            blocks.add(
                0,
                buildJsonObject {
                    put("type", "text")
                    put("text", "(see attached image)")
                },
            )
        }
        return JsonArray(blocks)
    }

    private fun anthropicToolPayload(
        tool: Tool,
        isOAuthToken: Boolean,
        deferred: Boolean,
        cacheControl: kotlinx.serialization.json.JsonObject?,
    ): JsonElement =
        buildJsonObject {
            put("name", if (isOAuthToken) normalizeClaudeCodeToolName(tool.name) else tool.name)
            put("description", tool.description)
            put("input_schema", tool.parameters)
            if (deferred) put("defer_loading", true)
            cacheControl?.let { put("cache_control", it) }
        }

    private fun toAnthropicContent(content: UserContent): JsonElement =
        when (content) {
            is UserText -> {
                JsonPrimitive(content.value)
            }

            is UserBlocks -> {
                buildJsonArray {
                    content.value.forEach { block ->
                        when (block) {
                            is TextContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", block.text)
                                    },
                                )
                            }

                            is ImageContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        put(
                                            "source",
                                            buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", block.mimeType)
                                                put("data", block.data)
                                            },
                                        )
                                    },
                                )
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
            }
        }

    private fun ThinkingBudgets.forLevel(level: ThinkingLevel): Int? =
        when (level) {
            ThinkingLevel.MINIMAL -> minimal
            ThinkingLevel.LOW -> low
            ThinkingLevel.MEDIUM -> medium
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> high
        }

    private fun defaultBudget(
        model: Model,
        level: ThinkingLevel,
    ): Int {
        val max = (model.maxTokens / 2).coerceAtLeast(1_024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (level) {
            ThinkingLevel.MINIMAL -> minOf(1_024, max)
            ThinkingLevel.LOW -> minOf(2_048, max)
            ThinkingLevel.MEDIUM -> minOf(8_192, max)
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> max
        }
    }

    private fun mapStopReason(reason: String): StopReason =
        when (reason) {
            "max_tokens" -> StopReason.LENGTH
            "tool_use" -> StopReason.TOOL_USE
            "refusal" -> StopReason.ERROR
            else -> StopReason.STOP
        }

    private fun isAnthropicOAuthToken(apiKey: String?): Boolean = apiKey?.contains("sk-ant-oat") == true

    private fun defaultSupportsToolReferences(model: Model): Boolean {
        val match =
            if (model.provider == "anthropic" && !model.id.contains("haiku", ignoreCase = true)) {
                Regex("^claude-(?:opus|sonnet|fable)-(\\d+)(?:[-.]?(\\d+))?(?:-|$)").find(model.id)
            } else {
                null
            }
        return match?.let {
            val major = it.groupValues[1].toIntOrNull() ?: 0
            val minor = it.groupValues[2].toIntOrNull() ?: 0
            major > 4 || (major == 4 && minor >= 5)
        } ?: false
    }

    private fun normalizeClaudeCodeToolName(name: String): String = claudeCodeToolNames[name.lowercase()] ?: name

    private companion object {
        val claudeCodeToolNames =
            listOf(
                "Read",
                "Write",
                "Edit",
                "Bash",
                "Grep",
                "Glob",
                "AskUserQuestion",
                "EnterPlanMode",
                "ExitPlanMode",
                "KillShell",
                "NotebookEdit",
                "Skill",
                "Task",
                "TaskOutput",
                "TodoWrite",
                "WebFetch",
                "WebSearch",
            ).associateBy(String::lowercase)
    }
}
