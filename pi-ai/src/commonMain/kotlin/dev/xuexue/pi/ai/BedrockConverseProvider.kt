@file:Suppress(
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooManyFunctions",
    "TooGenericExceptionCaught",
)

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Amazon Bedrock ConverseStream adapter, including AWS event-stream decoding and SigV4. */
class BedrockConverseProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "amazon-bedrock"
    override val name: String = "Amazon Bedrock"
    override val supportedApis: Set<Api> = setOf("bedrock-converse-stream")

    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        launchProviderStream(
            model = model,
            block = { accumulator ->
                val environment = ambientProviderEnvironment() + options.stream.env.orEmpty()
                val endpoint = bedrockEndpoint(model)
                val payload = buildPayload(model, context, options, environment)
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                val body = jsonBody(requestPayload)
                val headers = buildHeaders(endpoint, body, model, options.stream, environment)
                val response =
                    transport.execute(
                        HttpRequest(
                            method = "POST",
                            url = endpoint,
                            headers = headers,
                            body = body.decodeToString(),
                            signal = options.stream.signal,
                            timeoutMs = options.stream.timeoutMs,
                        ),
                    )
                options.stream.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
                if (response.status !in 200..299) error(providerError(response))
                var finishReason: StopReason? = null
                var started = false
                val events = response.bytes?.decodeAwsEventStream() ?: response.lines.decodeBedrockJsonLines()
                events.collect { event ->
                    val root = jsonObjectOrNull(event.payload) ?: buildJsonObject {}
                    when (event.type) {
                        "messageStart" -> {
                            if (root.providerString("role")?.lowercase() == "user") {
                                error("Unexpected assistant message start but got user message start instead")
                            }
                            if (!started) {
                                accumulator.start()
                                started = true
                            }
                        }

                        "contentBlockStart" -> {
                            root.providerObject("start")?.providerObject("toolUse")?.let { toolUse ->
                                accumulator.appendToolCall(
                                    index = root.providerInt("contentBlockIndex") ?: 0,
                                    id = toolUse.providerString("toolUseId"),
                                    name = toolUse.providerString("name"),
                                    argumentsDelta = null,
                                )
                            }
                        }

                        "contentBlockDelta" -> {
                            val index = root.providerInt("contentBlockIndex") ?: 0
                            val delta = root.providerObject("delta") ?: return@collect
                            delta.providerString("text")?.let(accumulator::appendText)
                            delta.providerObject("toolUse")?.providerString("input")?.let {
                                accumulator.appendToolCall(index, null, null, it)
                            }
                            delta.providerObject("reasoningContent")?.let { reasoning ->
                                val reasoningText = reasoning.providerObject("reasoningText")
                                accumulator.appendThinking(
                                    delta = reasoningText?.providerString("text").orEmpty(),
                                    signature = reasoningText?.providerString("signature"),
                                )
                            }
                        }

                        "messageStop" -> {
                            finishReason = mapBedrockStopReason(root.providerString("stopReason"))
                        }

                        "metadata" -> {
                            root.providerObject("usage")?.let { usage ->
                                accumulator.setUsage(
                                    input = usage.providerLong("inputTokens") ?: 0,
                                    output = usage.providerLong("outputTokens") ?: 0,
                                    cacheRead = usage.providerLong("cacheReadInputTokens") ?: 0,
                                    cacheWrite = usage.providerLong("cacheWriteInputTokens") ?: 0,
                                )
                            }
                        }

                        "internalServerException", "modelStreamErrorException", "validationException",
                        "throttlingException", "serviceUnavailableException", "modelTimeoutException",
                        -> {
                            val message = root.providerString("message") ?: event.payload
                            error("${event.type}: $message")
                        }
                    }
                }
                if (!started) accumulator.start()
                accumulator.finish(finishReason)
            },
        )

    private fun buildHeaders(
        endpoint: String,
        body: ByteArray,
        model: Model,
        options: StreamOptions,
        environment: Map<String, String>,
    ): Map<String, String> {
        val host = hostOf(endpoint)
        val merged = linkedMapOf<String, String>()
        merged["content-type"] = "application/json"
        merged["accept"] = "application/vnd.amazon.eventstream"
        merged["host"] = host
        options.headers.orEmpty().forEach { (key, value) ->
            if (value != null && !isReservedAwsHeader(key)) merged[key] = value
        }
        val bearerToken =
            options.bedrockBearerToken?.takeIf(String::isNotBlank)
                ?: options.apiKey?.takeIf(String::isNotBlank)
                ?: environment["AWS_BEARER_TOKEN_BEDROCK"]?.takeIf(String::isNotBlank)
        val skipAuth = environment["AWS_BEDROCK_SKIP_AUTH"] == "1"
        if (bearerToken != null && !skipAuth) {
            merged["authorization"] = "Bearer $bearerToken"
            return merged
        }
        if (skipAuth) return merged
        val credentials =
            AwsCredentials(
                accessKeyId = environment["AWS_ACCESS_KEY_ID"].orEmpty(),
                secretAccessKey = environment["AWS_SECRET_ACCESS_KEY"].orEmpty(),
                sessionToken = environment["AWS_SESSION_TOKEN"]?.takeIf(String::isNotBlank),
            ).takeIf { it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank() }
                ?: error(
                    "No Bedrock credentials found. Set AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, " +
                        "AWS_BEARER_TOKEN_BEDROCK, or pass a Bedrock API key.",
                )
        val amzDate = environment["AWS_AMZ_DATE"]?.takeIf(String::isNotBlank) ?: currentAwsAmzDate()
        val region = bedrockRegion(model, endpoint, environment, options)
        merged["x-amz-date"] = amzDate
        merged["x-amz-content-sha256"] = sha256Hex(body)
        credentials.sessionToken?.let { merged["x-amz-security-token"] = it }
        return signAwsRequest(
            method = "POST",
            url = endpoint,
            canonicalUri = canonicalBedrockPath(model.id),
            headers = merged,
            body = body,
            service = "bedrock",
            region = region,
            credentials = credentials,
            amzDate = amzDate,
        ).headers
    }

    private fun buildPayload(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
        environment: Map<String, String>,
    ): JsonElement =
        buildJsonObject {
            putJsonArray("messages") {
                toBedrockMessages(
                    transformMessagesForModel(context.messages, model),
                    model,
                    options.stream.cacheRetention ?: resolveCacheRetention(environment),
                ).forEach(::add)
            }
            buildSystemPrompt(context.systemPrompt, model, options.stream.cacheRetention ?: resolveCacheRetention(environment))
                ?.let { put("system", it) }
            val maxTokens = options.stream.maxTokens ?: model.maxTokens.toIntOrNullSafe()
            if (maxTokens != null || options.stream.temperature != null) {
                putJsonObject("inferenceConfig") {
                    maxTokens?.let { put("maxTokens", it) }
                    options.stream.temperature?.let { put("temperature", it) }
                }
            }
            buildToolConfig(context.tools, options.stream.bedrockToolChoice)?.let { put("toolConfig", it) }
            buildAdditionalModelRequestFields(model, options, environment)?.let {
                put("additionalModelRequestFields", it)
            }
            options.stream.bedrockRequestMetadata?.let { metadata ->
                putJsonObject("requestMetadata") { metadata.forEach { (key, value) -> put(key, value) } }
            }
        }

    private fun toBedrockMessages(
        messages: List<Message>,
        model: Model,
        cacheRetention: CacheRetention,
    ): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        var index = 0
        while (index < messages.size) {
            when (val message = messages[index]) {
                is UserMessage -> {
                    result +=
                        buildJsonObject {
                            put("role", "user")
                            put("content", userContent(message.content))
                        }
                }

                is AssistantMessage -> {
                    val content =
                        buildJsonArray {
                            message.content.forEach { block ->
                                when (block) {
                                    is TextContent -> {
                                        nonBlankText(block.text)?.let { add(buildJsonObject { put("text", it) }) }
                                    }

                                    is ToolCall -> {
                                        add(
                                            buildJsonObject {
                                                putJsonObject("toolUse") {
                                                    put("toolUseId", normalizeToolCallId(block.id))
                                                    put("name", block.name)
                                                    put("input", block.arguments)
                                                }
                                            },
                                        )
                                    }

                                    is ThinkingContent -> {
                                        thinkingBlock(block, model)?.let(::add)
                                    }
                                }
                            }
                        }
                    if (content.isNotEmpty()) {
                        result +=
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", content)
                            }
                    }
                }

                is ToolResultMessage -> {
                    val toolResults =
                        buildList {
                            var cursor = index
                            while (cursor < messages.size && messages[cursor] is ToolResultMessage) {
                                val tool = messages[cursor] as ToolResultMessage
                                add(
                                    buildJsonObject {
                                        putJsonObject("toolResult") {
                                            put("toolUseId", normalizeToolCallId(tool.toolCallId))
                                            put("content", toolResultContent(tool.content))
                                            put("status", if (tool.isError) "error" else "success")
                                        }
                                    },
                                )
                                cursor++
                            }
                            index = cursor - 1
                        }
                    result +=
                        buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(toolResults))
                        }
                }
            }
            index++
        }
        if (cacheRetention != CacheRetention.NONE && supportsPromptCaching(model) && result.isNotEmpty()) {
            val last = result.last().jsonObject
            if (last["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = (last["content"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                content += cachePoint(cacheRetention)
                result[result.lastIndex] =
                    buildJsonObject {
                        last.forEach { (key, value) -> if (key != "content") put(key, value) }
                        put("content", JsonArray(content))
                    }
            }
        }
        return result
    }

    private fun userContent(content: UserContent): JsonElement {
        val blocks =
            when (content) {
                is UserText -> {
                    listOf(
                        buildJsonObject {
                            put("text", nonBlankText(content.value) ?: EMPTY_TEXT_PLACEHOLDER)
                        },
                    )
                }

                is UserBlocks -> {
                    buildList {
                        content.value.forEach { block ->
                            when (block) {
                                is TextContent -> nonBlankText(block.text)?.let { add(buildJsonObject { put("text", it) }) }
                                is ImageContent -> add(buildJsonObject { put("image", imageBlock(block)) })
                            }
                        }
                    }
                }
            }
        return JsonArray(blocks.ifEmpty { listOf(buildJsonObject { put("text", EMPTY_TEXT_PLACEHOLDER) }) })
    }

    private fun buildSystemPrompt(
        systemPrompt: String?,
        model: Model,
        cacheRetention: CacheRetention,
    ): JsonArray? {
        if (systemPrompt.isNullOrBlank()) return null
        return buildJsonArray {
            add(buildJsonObject { put("text", sanitizeSurrogates(systemPrompt)) })
            if (cacheRetention != CacheRetention.NONE && supportsPromptCaching(model)) add(cachePoint(cacheRetention))
        }
    }

    private fun buildToolConfig(
        tools: List<Tool>?,
        toolChoice: String?,
    ): JsonElement? {
        if (tools.isNullOrEmpty() || toolChoice == "none") return null
        return buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            putJsonObject("toolSpec") {
                                put("name", tool.name)
                                put("description", tool.description)
                                putJsonObject("inputSchema") { put("json", tool.parameters) }
                            }
                        },
                    )
                }
            }
            when {
                toolChoice == "auto" -> {
                    putJsonObject("toolChoice") { putJsonObject("auto") {} }
                }

                toolChoice == "any" -> {
                    putJsonObject("toolChoice") { putJsonObject("any") {} }
                }

                toolChoice.orEmpty().startsWith("tool:") -> {
                    putJsonObject("toolChoice") {
                        putJsonObject("tool") { put("name", toolChoice.orEmpty().removePrefix("tool:")) }
                    }
                }
            }
        }
    }

    private fun buildAdditionalModelRequestFields(
        model: Model,
        options: SimpleStreamOptions,
        environment: Map<String, String>,
    ): JsonObject? {
        val level = options.reasoning ?: return null
        if (!model.reasoning || !isAnthropicClaudeModel(model)) return null
        val display = options.stream.bedrockThinkingDisplay ?: "summarized"
        val adaptive = supportsAdaptiveThinking(model)
        return buildJsonObject {
            if (adaptive) {
                putJsonObject("thinking") {
                    put("type", "adaptive")
                    if (!isGovCloud(model, options.stream, environment)) put("display", display)
                }
                putJsonObject("output_config") { put("effort", mapThinkingLevel(model, level)) }
            } else {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget(options, level))
                    if (!isGovCloud(model, options.stream, environment)) put("display", display)
                }
            }
            if (!adaptive && options.stream.bedrockInterleavedThinking != false) {
                putJsonArray("anthropic_beta") { add(JsonPrimitive("interleaved-thinking-2025-05-14")) }
            }
        }
    }

    private fun thinkingBlock(
        block: ThinkingContent,
        model: Model,
    ): JsonElement? {
        val thinking = sanitizeSurrogates(block.thinking)
        if (thinking.isBlank()) return null
        return buildJsonObject {
            if (isAnthropicClaudeModel(model) && !block.thinkingSignature.isNullOrBlank()) {
                putJsonObject("reasoningContent") {
                    putJsonObject("reasoningText") {
                        put("text", thinking)
                        put("signature", requireNotNull(block.thinkingSignature))
                    }
                }
            } else {
                putJsonObject("reasoningContent") { putJsonObject("reasoningText") { put("text", thinking) } }
            }
        }
    }

    private fun toolResultContent(content: List<ToolResultContent>): JsonArray {
        val blocks =
            buildList<JsonElement> {
                content.forEach { block ->
                    when (block) {
                        is TextContent -> nonBlankText(block.text)?.let { add(buildJsonObject { put("text", it) }) }
                        is ImageContent -> add(buildJsonObject { put("image", imageBlock(block)) })
                    }
                }
            }
        return JsonArray(blocks.ifEmpty { listOf(buildJsonObject { put("text", EMPTY_TEXT_PLACEHOLDER) }) })
    }

    private fun imageBlock(block: ImageContent): JsonObject =
        buildJsonObject {
            put("format", imageFormat(block.mimeType))
            putJsonObject("source") { put("bytes", block.data) }
        }

    private fun bedrockEndpoint(
        model: Model,
    ): String {
        val base = model.baseUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Bedrock endpoint is required in model.baseUrl" }
        return "$base/model/${awsEncodePathSegment(model.id)}/converse-stream"
    }

    private fun bedrockRegion(
        model: Model,
        endpoint: String,
        environment: Map<String, String>,
        options: StreamOptions,
    ): String {
        val arnRegion = Regex("^arn:aws(?:-[a-z0-9-]+)?:bedrock:([a-z0-9-]+):").find(model.id)?.groupValues?.get(1)
        return options.bedrockRegion?.takeIf(String::isNotBlank)
            ?: arnRegion
            ?: environment["AWS_REGION"]?.takeIf(String::isNotBlank)
            ?: environment["AWS_DEFAULT_REGION"]?.takeIf(String::isNotBlank)
            ?: Regex("^bedrock-runtime(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?$")
                .find(hostOf(endpoint).lowercase())
                ?.groupValues
                ?.get(1)
            ?: "us-east-1"
    }

    private fun hostOf(url: String): String =
        url
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')

    private fun canonicalBedrockPath(modelId: String): String = "/model/${awsEncodePathSegment(modelId)}/converse-stream"

    private fun normalizeToolCallId(id: String): String = id.map { char -> if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_' }.joinToString("").take(64)

    private fun nonBlankText(text: String): String? = sanitizeSurrogates(text).takeIf { it.isNotBlank() }

    private fun imageFormat(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpeg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> error("Unknown image type: $mimeType")
        }

    private fun cachePoint(retention: CacheRetention): JsonElement =
        buildJsonObject {
            putJsonObject("cachePoint") {
                put("type", "default")
                if (retention == CacheRetention.LONG) put("ttl", "one_hour")
            }
        }

    private fun supportsPromptCaching(model: Model): Boolean {
        val candidates = model.id.lowercase().replace(Regex("[\\s_.:]+"), "-") + " " + model.name.lowercase()
        return candidates.contains("claude-3-5-haiku") || candidates.contains("claude-3-7-sonnet") ||
            candidates.contains("claude-4") || candidates.contains("claude-5") || candidates.contains("fable-5") ||
            candidates.contains("sonnet-5")
    }

    private fun isAnthropicClaudeModel(model: Model): Boolean = model.id.lowercase().contains("anthropic.claude") || model.name.lowercase().contains("claude")

    private fun supportsAdaptiveThinking(model: Model): Boolean {
        val candidates = model.id.lowercase().replace(Regex("[\\s_.:]+"), "-") + " " + model.name.lowercase()
        return candidates.contains("opus-4-6") || candidates.contains("opus-4-7") || candidates.contains("opus-4-8") ||
            candidates.contains("sonnet-4-6") || candidates.contains("sonnet-5") || candidates.contains("fable-5")
    }

    private fun mapThinkingLevel(
        model: Model,
        level: ThinkingLevel,
    ): String =
        model.thinkingLevelMap?.get(level.toModelLevel()) ?: when (level) {
            ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH -> if (supportsAdaptiveThinking(model)) "xhigh" else "high"
            ThinkingLevel.MAX -> if (supportsAdaptiveThinking(model)) "max" else "high"
        }

    private fun thinkingBudget(
        options: SimpleStreamOptions,
        level: ThinkingLevel,
    ): Int =
        options.thinkingBudgets?.budgetFor(level)?.let { value -> value.coerceAtLeast(1_024) }
            ?: when (level) {
                ThinkingLevel.MINIMAL -> 1_024
                ThinkingLevel.LOW -> 2_048
                ThinkingLevel.MEDIUM -> 8_192
                ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> 16_384
            }

    private fun isGovCloud(
        model: Model,
        options: StreamOptions,
        environment: Map<String, String>,
    ): Boolean =
        (options.bedrockRegion ?: environment["AWS_REGION"]).orEmpty().startsWith("us-gov-") ||
            model.id.lowercase().startsWith("arn:aws-us-gov:") || model.id.lowercase().startsWith("us-gov.")

    private fun resolveCacheRetention(environment: Map<String, String>): CacheRetention =
        when (environment["PI_CACHE_RETENTION"]?.lowercase()) {
            "none" -> CacheRetention.NONE
            "long" -> CacheRetention.LONG
            else -> CacheRetention.SHORT
        }

    private fun mapBedrockStopReason(reason: String?): StopReason =
        when (reason?.lowercase()) {
            "end_turn", "stop_sequence" -> StopReason.STOP
            "max_tokens", "model_context_window_exceeded" -> StopReason.LENGTH
            "tool_use" -> StopReason.TOOL_USE
            else -> StopReason.ERROR
        }

    private fun isReservedAwsHeader(key: String): Boolean {
        val lower = key.lowercase()
        return lower == "authorization" || lower == "host" || lower.startsWith("x-amz-")
    }

    private fun ThinkingLevel.toModelLevel(): ModelThinkingLevel =
        when (this) {
            ThinkingLevel.MINIMAL -> ModelThinkingLevel.MINIMAL
            ThinkingLevel.LOW -> ModelThinkingLevel.LOW
            ThinkingLevel.MEDIUM -> ModelThinkingLevel.MEDIUM
            ThinkingLevel.HIGH -> ModelThinkingLevel.HIGH
            ThinkingLevel.XHIGH -> ModelThinkingLevel.XHIGH
            ThinkingLevel.MAX -> ModelThinkingLevel.MAX
        }

    private fun Long.toIntOrNullSafe(): Int? = takeIf { it in 1..Int.MAX_VALUE }?.toInt()

    private fun ThinkingBudgets.budgetFor(level: ThinkingLevel): Int? =
        when (level) {
            ThinkingLevel.MINIMAL -> minimal
            ThinkingLevel.LOW -> low
            ThinkingLevel.MEDIUM -> medium
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> high
        }

    private companion object {
        const val EMPTY_TEXT_PLACEHOLDER = "<empty>"
    }
}

private data class BedrockStreamEvent(
    val type: String,
    val payload: String,
)

private fun Flow<String>.decodeBedrockJsonLines(): Flow<BedrockStreamEvent> =
    flow {
        var pendingType: String? = null
        collect { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@collect
            if (trimmed.startsWith("event:")) {
                pendingType = trimmed.substringAfter(':').trim()
                return@collect
            }
            val payload = if (trimmed.startsWith("data:")) trimmed.substringAfter(':').trim() else trimmed
            val root = jsonObjectOrNull(payload) ?: return@collect
            val type = pendingType ?: root.keys.firstOrNull { key -> key.endsWith("Exception") || key in BEDROCK_EVENT_TYPES }
            if (type != null) {
                val actualPayload = if (root.containsKey(type)) root[type].toString() else payload
                emit(BedrockStreamEvent(type, actualPayload))
            }
            pendingType = null
        }
    }

private fun Flow<ByteArray>.decodeAwsEventStream(): Flow<BedrockStreamEvent> =
    flow {
        var buffer = ByteArray(0)
        collect { chunk ->
            buffer += chunk
            while (buffer.size >= 12) {
                val totalLength = readBigEndianInt(buffer, 0)
                val headersLength = readBigEndianInt(buffer, 4)
                if (totalLength < 16 || headersLength < 0 || headersLength > totalLength - 16) {
                    error("Invalid AWS event-stream frame")
                }
                if (buffer.size < totalLength) return@collect
                val type = readEventType(buffer, headersLength)
                val payloadStart = 12 + headersLength
                val payloadEnd = totalLength - 4
                if (type != null && payloadStart <= payloadEnd) {
                    emit(BedrockStreamEvent(type, buffer.copyOfRange(payloadStart, payloadEnd).decodeToString()))
                }
                buffer = buffer.copyOfRange(totalLength, buffer.size)
            }
        }
        if (buffer.isNotEmpty()) error("Truncated AWS event-stream frame")
    }

private fun readEventType(
    frame: ByteArray,
    headersLength: Int,
): String? {
    var offset = 12
    val end = 12 + headersLength
    while (offset < end) {
        val nameLength = frame[offset].toInt() and 0xff
        offset++
        if (offset + nameLength >= end) return null
        val name = frame.copyOfRange(offset, offset + nameLength).decodeToString()
        offset += nameLength
        val valueType = frame[offset].toInt() and 0xff
        offset++
        when (valueType) {
            7 -> {
                if (offset + 2 > end) return null
                val length = readUnsignedShort(frame, offset)
                offset += 2
                if (offset + length > end) return null
                val value = frame.copyOfRange(offset, offset + length).decodeToString()
                offset += length
                if (name == ":event-type") return value
            }

            6 -> {
                if (offset + 2 > end) return null
                offset += 2 + readUnsignedShort(frame, offset)
            }

            0, 1 -> {
                Unit
            }

            2 -> {
                offset += 1
            }

            3 -> {
                offset += 2
            }

            4 -> {
                offset += 4
            }

            5, 8 -> {
                offset += 8
            }

            9 -> {
                offset += 16
            }

            else -> {
                return null
            }
        }
    }
    return null
}

private fun readBigEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun readUnsignedShort(
    bytes: ByteArray,
    offset: Int,
): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private val BEDROCK_EVENT_TYPES =
    setOf(
        "messageStart",
        "contentBlockStart",
        "contentBlockDelta",
        "contentBlockStop",
        "messageStop",
        "metadata",
    )
