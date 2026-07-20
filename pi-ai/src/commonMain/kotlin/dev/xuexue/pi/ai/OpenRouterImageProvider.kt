@file:Suppress("CyclomaticComplexMethod", "LongMethod", "MaxLineLength", "TooGenericExceptionCaught")

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** OpenRouter's non-streaming chat-completions image modality. */
class OpenRouterImageProvider(
    private val transport: HttpTransport,
) : PiImageProvider {
    override val id: ImagesProviderId = "openrouter"
    override val name: String = "OpenRouter Images"
    override val supportedApis: Set<ImagesApi> = setOf("openrouter-images")

    override suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages {
        var output =
            AssistantImages(
                api = model.api,
                provider = model.provider,
                model = model.id,
                output = emptyList(),
                stopReason = ImagesStopReason.STOP,
                timestamp =
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds(),
            )
        return try {
            if (options.signal?.isAborted == true) throw kotlinx.coroutines.CancellationException("Request was aborted")
            val payload = buildPayload(model, context)
            val transformed = options.onPayload?.invoke(payload, model)
            val requestPayload = transformed as? JsonElement ?: payload
            var headers =
                mergeProviderHeaders(
                    Model(
                        id = model.id,
                        name = model.name,
                        api = "openai-completions",
                        provider = model.provider,
                        baseUrl = model.baseUrl,
                        reasoning = false,
                        input = model.input,
                        cost = model.cost,
                        contextWindow = Long.MAX_VALUE,
                        maxTokens = Long.MAX_VALUE,
                        headers = model.headers,
                    ),
                    StreamOptions(apiKey = options.apiKey, headers = options.headers),
                    defaults = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                )
            if (options.apiKey.orEmpty().isNotBlank() && !hasHeader(headers, "Authorization")) {
                headers = headers + ("Authorization" to "Bearer ${options.apiKey}")
            }
            if (options.apiKey.orEmpty().isBlank() && !hasHeader(headers, "Authorization")) {
                error("No API key for provider: ${model.provider}")
            }
            val response =
                transport.execute(
                    HttpRequest(
                        method = "POST",
                        url = endpoint(model.baseUrl, "/chat/completions"),
                        headers = headers,
                        body = requestPayload.toString(),
                        signal = options.signal,
                        timeoutMs = options.timeoutMs,
                    ),
                )
            options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
            if (response.status !in 200..299) error(providerError(response))
            val body = response.lines.toList().joinToString("\n")
            val root = jsonObjectOrNull(body) ?: error("Image provider returned invalid JSON")
            output = output.copy(responseId = root.providerString("id"))
            val choice = root.providerArray("choices")?.firstOrNull()?.jsonObject
            val message = choice?.providerObject("message")
            val text = message?.providerString("content")
            val images =
                message?.providerArray("images").orEmpty().mapNotNull { imageElement ->
                    val image = imageElement as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val urlElement = image["image_url"] ?: image["imageUrl"] ?: return@mapNotNull null
                    val url =
                        when (urlElement) {
                            is JsonPrimitive -> urlElement.content
                            is kotlinx.serialization.json.JsonObject -> urlElement.providerString("url")
                            else -> null
                        } ?: return@mapNotNull null
                    val match = Regex("^data:([^;]+);base64,(.+)$").find(url) ?: return@mapNotNull null
                    ImageContent(match.groupValues[2], match.groupValues[1])
                }
            val result =
                buildList<ImageOutputContent> {
                    text?.takeIf(String::isNotEmpty)?.let { add(TextContent(it)) }
                    addAll(images)
                }
            val usage = root.providerObject("usage")
            val promptTokens = usage?.providerLong("prompt_tokens") ?: 0
            val outputTokens = usage?.providerLong("completion_tokens") ?: 0
            val details = usage?.providerObject("prompt_tokens_details")
            val reportedCached = details?.providerLong("cached_tokens") ?: 0
            val cacheWrite = details?.providerLong("cache_write_tokens") ?: 0
            val cached = if (cacheWrite > 0) (reportedCached - cacheWrite).coerceAtLeast(0) else reportedCached
            output.copy(
                output = result,
                usage =
                    calculateProviderUsage(
                        model.toTextModel(),
                        (promptTokens - cached - cacheWrite).coerceAtLeast(0),
                        outputTokens,
                        cached,
                        cacheWrite,
                    ),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            output.copy(stopReason = ImagesStopReason.ABORTED, errorMessage = error.message ?: "Request was aborted")
        } catch (error: Throwable) {
            output.copy(stopReason = ImagesStopReason.ERROR, errorMessage = error.message ?: error.toString())
        }
    }

    private fun buildPayload(
        model: ImagesModel,
        context: ImagesContext,
    ): JsonElement =
        buildJsonObject {
            put("model", model.id)
            put("stream", false)
            put(
                "modalities",
                buildJsonArray {
                    add(JsonPrimitive("image"))
                    if (ModelInput.TEXT in model.output) add(JsonPrimitive("text"))
                },
            )
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    context.input.forEach { block ->
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
                                                        put("type", "image_url")
                                                        put("image_url", buildJsonObject { put("url", "data:${block.mimeType};base64,${block.data}") })
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

    private fun ImagesModel.toTextModel(): Model =
        Model(
            id = id,
            name = name,
            api = "openai-completions",
            provider = provider,
            baseUrl = baseUrl,
            reasoning = false,
            input = input,
            cost = cost,
            contextWindow = Long.MAX_VALUE,
            maxTokens = Long.MAX_VALUE,
            headers = headers,
        )
}
