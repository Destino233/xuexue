@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength")

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

/** Google Generative AI REST streaming adapter (Gemini and Gemma models). */
class GoogleGenerativeAiProvider(
    private val transport: HttpTransport,
) : PiProvider {
    override val id: ProviderId = "google-generative-ai"
    override val name: String = "Google Generative AI"
    override val supportedApis: Set<Api> = setOf("google-generative-ai", "google-vertex")

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
                val payload = buildPayload(model, context, options)
                val transformed = options.stream.onPayload?.invoke(payload, model)
                val requestPayload = transformed as? JsonElement ?: payload
                var headers =
                    mergeProviderHeaders(
                        model,
                        options.stream,
                        defaults = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
                    )
                if (options.stream.apiKey
                        .orEmpty()
                        .isNotBlank() && !hasHeader(headers, "x-goog-api-key")
                ) {
                    headers = headers + ("x-goog-api-key" to options.stream.apiKey.orEmpty())
                }
                if (model.api == "google-vertex" &&
                    options.stream.apiKey
                        .orEmpty()
                        .isBlank() &&
                    !hasHeader(headers, "Authorization")
                ) {
                    options.stream.env
                        ?.get("GOOGLE_CLOUD_ACCESS_TOKEN")
                        ?.takeIf(String::isNotBlank)
                        ?.let { token -> headers = headers + ("Authorization" to "Bearer $token") }
                }
                if (options.stream.apiKey
                        .orEmpty()
                        .isBlank() && !hasHeader(headers, "x-goog-api-key") && !hasHeader(headers, "Authorization")
                ) {
                    error("No API key for provider: ${model.provider}")
                }
                val response =
                    transport.execute(
                        HttpRequest(
                            method = "POST",
                            url = endpointFor(model, options),
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
                var toolIndex = 0
                val toolIndices = linkedMapOf<String, Int>()
                response.lines.decodeSse().collect { event ->
                    val root = jsonObjectOrNull(event.data.trim()) ?: return@collect
                    root.providerString("responseId")?.let(accumulator::setResponseId)
                    root.providerArray("candidates")?.firstOrNull()?.jsonObject?.let { candidate ->
                        val content = candidate.providerObject("content")
                        content?.providerArray("parts")?.forEach { partElement ->
                            val part = partElement as? kotlinx.serialization.json.JsonObject ?: return@forEach
                            val text = part.providerString("text")
                            if (text != null) {
                                val isThought = part["thought"]?.let { (it as? JsonPrimitive)?.content?.toBoolean() } == true
                                if (isThought) {
                                    accumulator.appendThinking(text, part.providerString("thoughtSignature"))
                                } else {
                                    accumulator.appendText(text)
                                }
                            }
                            part.providerObject("functionCall")?.let { call ->
                                val name = call.providerString("name").orEmpty()
                                val id = call.providerString("id")
                                val key = id ?: name
                                val index = toolIndices.getOrPut(key) { toolIndex++ }
                                accumulator.upsertToolCallArguments(
                                    index = index,
                                    id = id,
                                    name = name,
                                    arguments = call.providerObject("args") ?: kotlinx.serialization.json.buildJsonObject {},
                                    thoughtSignature = part.providerString("thoughtSignature"),
                                )
                            }
                        }
                        candidate.providerString("finishReason")?.let { finishReason = mapStopReason(it) }
                    }
                    root.providerObject("usageMetadata")?.let { usage ->
                        val prompt = usage.providerLong("promptTokenCount") ?: 0
                        val cached = usage.providerLong("cachedContentTokenCount") ?: 0
                        val output = usage.providerLong("candidatesTokenCount") ?: 0
                        val thoughts = usage.providerLong("thoughtsTokenCount")
                        accumulator.setUsage(
                            input = (prompt - cached).coerceAtLeast(0),
                            output = output + (thoughts ?: 0),
                            cacheRead = cached,
                            reasoning = thoughts,
                        )
                    }
                }
                accumulator.finish(finishReason)
            },
        )

    private fun endpointFor(
        model: Model,
        options: SimpleStreamOptions,
    ): String {
        val environment = options.stream.env.orEmpty()
        val location = environment["GOOGLE_CLOUD_LOCATION"]?.takeIf(String::isNotBlank)
        val base = model.baseUrl.trimEnd('/').replace("{location}", location ?: "us-central1")
        val path =
            if (model.api == "google-vertex") {
                val project =
                    environment["GOOGLE_CLOUD_PROJECT"]?.takeIf(String::isNotBlank)
                        ?: environment["GCLOUD_PROJECT"]?.takeIf(String::isNotBlank)
                        ?: error("Vertex AI requires GOOGLE_CLOUD_PROJECT or GCLOUD_PROJECT")
                val resolvedLocation = location ?: error("Vertex AI requires GOOGLE_CLOUD_LOCATION")
                if (base.contains("/projects/")) {
                    base
                } else {
                    "$base/v1/projects/$project/locations/$resolvedLocation/publishers/google/models/${model.id}:streamGenerateContent"
                }
            } else if (base.contains("/models/")) {
                base
            } else {
                "$base/models/${model.id}:streamGenerateContent"
            }
        return if (path.contains("?")) "$path&alt=sse" else "$path?alt=sse"
    }

    private fun buildPayload(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): JsonElement =
        buildJsonObject {
            context.systemPrompt?.takeIf(String::isNotBlank)?.let { prompt ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                }
            }
            putJsonArray("contents") {
                transformMessagesForModel(context.messages, model).forEach { add(toGoogleMessage(it)) }
            }
            if (context.tools.orEmpty().isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                context.tools.orEmpty().forEach { tool ->
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.parameters)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            putJsonObject("generationConfig") {
                options.stream.temperature?.let { put("temperature", it) }
                options.stream.maxTokens?.let { put("maxOutputTokens", it) }
                options.reasoning?.let { level ->
                    if (model.reasoning) {
                        putJsonObject("thinkingConfig") {
                            put("includeThoughts", true)
                            put("thinkingBudget", options.thinkingBudgets?.forLevel(level) ?: defaultBudget(level))
                        }
                    } else if (model.reasoning) {
                        putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                    }
                }
            }
        }

    private fun toGoogleMessage(message: Message): JsonElement =
        when (message) {
            is UserMessage -> {
                buildJsonObject {
                    put("role", "user")
                    put("parts", toGoogleParts(message.content))
                }
            }

            is AssistantMessage -> {
                buildJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        message.content.forEach { block ->
                            when (block) {
                                is TextContent -> {
                                    add(buildJsonObject { put("text", block.text) })
                                }

                                is ThinkingContent -> {
                                    add(
                                        buildJsonObject {
                                            put("text", block.thinking)
                                            put("thought", true)
                                            block.thinkingSignature?.let { put("thoughtSignature", it) }
                                        },
                                    )
                                }

                                is ToolCall -> {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "functionCall",
                                                buildJsonObject {
                                                    put("name", block.name)
                                                    put("args", block.arguments)
                                                    block.thoughtSignature?.let { put("thoughtSignature", it) }
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is ToolResultMessage -> {
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                put(
                                    "functionResponse",
                                    buildJsonObject {
                                        put("name", message.toolName)
                                        put("response", buildJsonObject { put("output", message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }) })
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }

    private fun toGoogleParts(content: UserContent): JsonElement =
        when (content) {
            is UserText -> {
                buildJsonArray { add(buildJsonObject { put("text", content.value) }) }
            }

            is UserBlocks -> {
                buildJsonArray {
                    content.value.forEach { block ->
                        when (block) {
                            is TextContent -> {
                                add(buildJsonObject { put("text", block.text) })
                            }

                            is ImageContent -> {
                                add(
                                    buildJsonObject {
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", block.mimeType)
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

    private fun defaultBudget(level: ThinkingLevel): Int =
        when (level) {
            ThinkingLevel.MINIMAL -> 128
            ThinkingLevel.LOW -> 2_048
            ThinkingLevel.MEDIUM -> 8_192
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> 24_576
        }

    private fun mapStopReason(reason: String): StopReason =
        when (reason.uppercase()) {
            "MAX_TOKENS" -> StopReason.LENGTH
            "STOP", "FINISH_REASON_UNSPECIFIED" -> StopReason.STOP
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> StopReason.ERROR
            else -> StopReason.STOP
        }
}
