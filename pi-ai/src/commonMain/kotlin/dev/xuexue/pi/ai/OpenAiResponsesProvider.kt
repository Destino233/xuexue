@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MaxLineLength",
    "TooManyFunctions",
)

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** OpenAI Responses API, including the event names used by the 0.80.6 adapter. */
class OpenAiResponsesProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "openai-responses"
    override val name: String = "OpenAI Responses"
    override val supportedApis: Set<Api> = setOf("openai-responses", "azure-openai-responses")

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
                val azureConfig =
                    if (model.api == "azure-openai-responses") {
                        resolveAzureConfig(model, options.stream)
                    } else {
                        null
                    }
                val environment = ambientProviderEnvironment() + options.stream.env.orEmpty()
                val retention = resolveCacheRetention(options.stream.cacheRetention, environment)
                val payload = buildPayload(model, context, options, azureConfig, retention)
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                var headers =
                    mergeProviderHeaders(
                        model,
                        options.stream,
                        defaults = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
                        generated = sessionAffinityHeaders(model, options.stream.sessionId, retention),
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
                            url = requestEndpoint(model, azureConfig),
                            headers = headers,
                            body = requestPayload.toString(),
                            signal = options.stream.signal,
                            timeoutMs = options.stream.timeoutMs,
                        ),
                    )
                options.stream.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
                if (response.status !in 200..299) error(providerError(response, prefix = "OpenAI API error"))
                accumulator.start()
                var finishReason: StopReason? = null
                response.lines.decodeSse().collect { event ->
                    val data = event.data.trim()
                    if (data.isBlank()) return@collect
                    val root = jsonObjectOrNull(data) ?: return@collect
                    val eventName = event.event.orEmpty()
                    when (eventName) {
                        "response.created", "response.in_progress" -> {
                            root.providerObject("response")?.providerString("id")?.let(accumulator::setResponseId)
                        }

                        "response.output_text.delta" -> {
                            root.providerString("delta")?.let(accumulator::appendText)
                        }

                        "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                            root.providerString("delta")?.let(accumulator::appendThinking)
                        }

                        "response.output_item.added" -> {
                            val item = root.providerObject("item")
                            if (item?.providerString("type") == "function_call") {
                                val index = root.providerInt("output_index") ?: 0
                                accumulator.appendToolCall(
                                    index = index,
                                    id = item.providerString("call_id") ?: item.providerString("id"),
                                    name = item.providerString("name"),
                                    argumentsDelta = item.providerString("arguments"),
                                )
                            }
                        }

                        "response.function_call_arguments.delta" -> {
                            accumulator.appendToolCall(
                                index = root.providerInt("output_index") ?: 0,
                                id = root.providerString("call_id"),
                                name = null,
                                argumentsDelta = root.providerString("delta"),
                            )
                        }

                        "response.function_call_arguments.done" -> {
                            accumulator.completeToolCall(
                                index = root.providerInt("output_index") ?: 0,
                                id = root.providerString("call_id"),
                                name = null,
                                arguments = root.providerString("arguments"),
                            )
                        }

                        "response.output_item.done" -> {
                            val item = root.providerObject("item")
                            if (item?.providerString("type") == "function_call") {
                                accumulator.completeToolCall(
                                    index = root.providerInt("output_index") ?: 0,
                                    id = item.providerString("call_id") ?: item.providerString("id"),
                                    name = item.providerString("name"),
                                    arguments = item.providerString("arguments"),
                                )
                            }
                        }

                        "response.completed", "response.incomplete", "response.failed" -> {
                            val responseObject = root.providerObject("response") ?: root
                            accumulator.setResponseId(responseObject.providerString("id"))
                            responseObject.providerObject("usage")?.let { usage ->
                                accumulator.setUsage(
                                    input = usage.providerLong("input_tokens") ?: 0,
                                    output = usage.providerLong("output_tokens") ?: 0,
                                    cacheRead = usage.providerObject("input_tokens_details")?.providerLong("cached_tokens") ?: 0,
                                    cacheWrite = usage.providerObject("input_tokens_details")?.providerLong("cache_write_tokens") ?: 0,
                                    reasoning = usage.providerObject("output_tokens_details")?.providerLong("reasoning_tokens"),
                                )
                            }
                            finishReason =
                                when {
                                    eventName == "response.failed" -> StopReason.ERROR
                                    eventName == "response.incomplete" -> StopReason.LENGTH
                                    responseObject.providerString("status") == "incomplete" -> StopReason.LENGTH
                                    else -> null
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
        azureConfig: AzureConfig?,
        retention: CacheRetention,
    ): JsonElement =
        buildJsonObject {
            val toolPlacement =
                splitDeferredTools(
                    context,
                    model.compatBoolean("supportsToolSearch") == true,
                )
            val loadedToolNames = mutableSetOf<String>()
            val transformedMessages = transformMessagesForModel(context.messages, model)
            put("model", azureConfig?.deploymentName ?: model.id)
            put("stream", true)
            // Pi never stores Responses server-side; the session key is only used
            // for prompt-cache routing and remains provider-neutral.
            put("store", false)
            if (retention != CacheRetention.NONE) {
                clampOpenAiPromptCacheKey(options.stream.sessionId)?.let { put("prompt_cache_key", it) }
            }
            if (retention == CacheRetention.LONG && model.supportsLongCacheRetention()) {
                put("prompt_cache_retention", "24h")
            }
            context.systemPrompt?.takeIf(String::isNotBlank)?.let { put("instructions", it) }
            putJsonArray("input") {
                transformedMessages.forEach { message ->
                    toResponsesMessages(message, toolPlacement.deferred, loadedToolNames).forEach(::add)
                }
            }
            if (toolPlacement.immediate.isNotEmpty()) {
                putJsonArray("tools") {
                    toolPlacement.immediate.forEach { tool -> add(responsesToolPayload(tool)) }
                }
            }
            options.stream.temperature?.let { put("temperature", it) }
            options.stream.maxTokens?.let { maxTokens ->
                put(
                    "max_output_tokens",
                    if (azureConfig == null) maxTokens else maxTokens.coerceAtLeast(MIN_RESPONSES_OUTPUT_TOKENS),
                )
            }
            options.reasoning?.takeIf { model.reasoning }?.let { level ->
                putJsonObject("reasoning") {
                    put("effort", level.toResponsesEffort(model))
                    if (azureConfig != null) {
                        put("summary", options.stream.azureReasoningSummary ?: "auto")
                    }
                }
                if (azureConfig != null) {
                    putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
                }
            }
        }

    private fun sessionAffinityHeaders(
        model: Model,
        sessionId: String?,
        retention: CacheRetention,
    ): Map<String, String> {
        if (retention == CacheRetention.NONE || sessionId.isNullOrBlank()) return emptyMap()
        val headers = linkedMapOf<String, String>("x-client-request-id" to sessionId)
        if (model.compatBoolean("sendSessionIdHeader") != false) headers["session_id"] = sessionId
        return headers
    }

    private fun toResponsesMessages(
        message: Message,
        deferredTools: Map<String, Tool>,
        loadedToolNames: MutableSet<String>,
    ): List<JsonElement> =
        when (message) {
            is UserMessage -> {
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", toResponsesContent(message.content))
                    },
                )
            }

            is AssistantMessage -> {
                buildList {
                    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    if (text.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            },
                        )
                    }
                    message.content.filterIsInstance<ToolCall>().forEach { call ->
                        // Responses represents assistant tool calls as independent input items.
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            },
                        )
                    }
                }
            }

            is ToolResultMessage -> {
                buildList {
                    add(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", message.toolCallId.substringBefore("|"))
                            put("output", message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
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
                                    newlyLoadedTools.forEach { tool -> add(responsesToolPayload(tool, deferred = true)) }
                                }
                            },
                        )
                    }
                }
            }
        }

    private fun responsesToolPayload(
        tool: Tool,
        deferred: Boolean = false,
    ): JsonElement =
        buildJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
            if (deferred) put("defer_loading", true)
        }

    private fun toResponsesContent(content: UserContent): JsonElement =
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
                                        put("type", "input_text")
                                        put("text", block.text)
                                    },
                                )
                            }

                            is ImageContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:${block.mimeType};base64,${block.data}")
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

    private fun ThinkingLevel.toResponsesEffort(model: Model): String =
        model.thinkingLevelMap
            ?.get(toModelThinkingLevel())
            ?: toResponsesEffort()

    private fun ThinkingLevel.toResponsesEffort(): String =
        when (this) {
            ThinkingLevel.MINIMAL -> "minimal"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "xhigh"
        }

    private fun ThinkingLevel.toModelThinkingLevel(): ModelThinkingLevel =
        when (this) {
            ThinkingLevel.MINIMAL -> ModelThinkingLevel.MINIMAL
            ThinkingLevel.LOW -> ModelThinkingLevel.LOW
            ThinkingLevel.MEDIUM -> ModelThinkingLevel.MEDIUM
            ThinkingLevel.HIGH -> ModelThinkingLevel.HIGH
            ThinkingLevel.XHIGH -> ModelThinkingLevel.XHIGH
            ThinkingLevel.MAX -> ModelThinkingLevel.MAX
        }

    private fun requestEndpoint(
        model: Model,
        azureConfig: AzureConfig?,
    ): String =
        if (azureConfig == null) {
            endpoint(model.baseUrl, "/responses")
        } else {
            val base = endpoint(azureConfig.baseUrl, "/responses")
            appendQueryParameter(base, "api-version", azureConfig.apiVersion)
        }

    private fun resolveAzureConfig(
        model: Model,
        options: StreamOptions,
    ): AzureConfig {
        val environment = options.env.orEmpty()
        val apiVersion =
            options.azureApiVersion?.takeIf(String::isNotBlank)
                ?: environment["AZURE_OPENAI_API_VERSION"]?.takeIf(String::isNotBlank)
                ?: DEFAULT_AZURE_API_VERSION
        val explicitBase =
            options.azureBaseUrl?.trim()?.takeIf(String::isNotBlank)
                ?: environment["AZURE_OPENAI_BASE_URL"]?.trim()?.takeIf(String::isNotBlank)
        val resourceName =
            options.azureResourceName?.trim()?.takeIf(String::isNotBlank)
                ?: environment["AZURE_OPENAI_RESOURCE_NAME"]?.trim()?.takeIf(String::isNotBlank)
        val base =
            explicitBase
                ?: resourceName?.let(::buildDefaultAzureBaseUrl)
                ?: model.baseUrl.trim().takeIf(String::isNotBlank)
                ?: error(
                    "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or " +
                        "AZURE_OPENAI_RESOURCE_NAME, or pass azureBaseUrl, azureResourceName, or model.baseUrl.",
                )
        val deploymentName =
            options.azureDeploymentName?.trim()?.takeIf(String::isNotBlank)
                ?: parseAzureDeploymentMap(environment["AZURE_OPENAI_DEPLOYMENT_NAME_MAP"])[model.id]
                ?: model.id
        return AzureConfig(
            baseUrl = normalizeAzureBaseUrl(base),
            apiVersion = apiVersion,
            deploymentName = deploymentName,
        )
    }

    private fun parseAzureDeploymentMap(value: String?): Map<String, String> =
        value
            .orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0 || separator == entry.lastIndex) {
                    null
                } else {
                    entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
                }
            }.filter { (modelId, deployment) -> modelId.isNotBlank() && deployment.isNotBlank() }
            .toMap()

    private fun normalizeAzureBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        require(schemeEnd > 0) { "Invalid Azure OpenAI base URL: $baseUrl" }
        val authorityStart = schemeEnd + 3
        val pathStart = trimmed.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        val authority =
            (if (pathStart < 0) trimmed.substring(authorityStart) else trimmed.substring(authorityStart, pathStart))
                .substringBefore(':')
                .lowercase()
        require(authority.isNotBlank()) { "Invalid Azure OpenAI base URL: $baseUrl" }
        val isAzureHost =
            authority.endsWith(".openai.azure.com") ||
                authority.endsWith(".cognitiveservices.azure.com") ||
                authority.endsWith(".ai.azure.com")
        if (!isAzureHost) return trimmed

        val pathAndQuery = if (pathStart < 0) "" else trimmed.substring(pathStart)
        val path = pathAndQuery.substringBefore('?').substringBefore('#').trimEnd('/')
        return if (path.isEmpty() || path == "/" || path == "/openai" || path == "/openai/v1/responses") {
            trimmed.substring(0, authorityStart) + authorityFrom(trimmed, authorityStart, pathStart) + "/openai/v1"
        } else {
            trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        }
    }

    private fun authorityFrom(
        value: String,
        authorityStart: Int,
        pathStart: Int,
    ): String = if (pathStart < 0) value.substring(authorityStart) else value.substring(authorityStart, pathStart)

    private fun buildDefaultAzureBaseUrl(resourceName: String): String = "https://${resourceName.trim()}.openai.azure.com/openai/v1"

    private fun appendQueryParameter(
        url: String,
        name: String,
        value: String,
    ): String {
        if (value.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$name=$value"
    }

    private data class AzureConfig(
        val baseUrl: String,
        val apiVersion: String,
        val deploymentName: String,
    )

    private companion object {
        const val DEFAULT_AZURE_API_VERSION = "v1"
        const val MIN_RESPONSES_OUTPUT_TOKENS = 16
    }
}
