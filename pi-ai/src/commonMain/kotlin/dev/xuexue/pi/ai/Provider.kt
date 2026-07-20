@file:Suppress("MaxLineLength")

package dev.xuexue.pi.ai

import kotlinx.coroutines.flow.Flow

/**
 * The platform-neutral HTTP boundary used by Pi providers.  Providers own the
 * request shape and stream decoding; the host only supplies bytes/lines and
 * cancellation.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val signal: AbortSignal? = null,
    val timeoutMs: Long? = null,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val lines: Flow<String>,
    /** Binary response chunks, used by AWS Event Stream providers. */
    val bytes: Flow<ByteArray>? = null,
)

/** Returns the desktop/JVM transport.  Other hosts can provide their own actual. */
expect fun defaultHttpTransport(): HttpTransport

interface PiProvider {
    val id: ProviderId
    val name: String
    val supportedApis: Set<Api>

    suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = stream(model, context, options)
}

interface PiImageProvider {
    val id: ImagesProviderId
    val name: String
    val supportedApis: Set<ImagesApi>

    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages
}

class ProviderRegistry(
    providers: List<PiProvider>,
    imageProviders: List<PiImageProvider> = emptyList(),
) {
    private val providersByApi = providers.flatMap { provider -> provider.supportedApis.map { it to provider } }.toMap()
    private val providersById = providers.associateBy { it.id }
    private val imageProvidersByApi = imageProviders.flatMap { provider -> provider.supportedApis.map { it to provider } }.toMap()

    val providers: List<PiProvider> = providers.distinctBy { it.id }
    val imageProviders: List<PiImageProvider> = imageProviders.distinctBy { it.id }

    fun find(model: Model): PiProvider? =
        providersByApi[model.api]
            ?: providersById[model.provider]

    fun require(model: Model): PiProvider = find(model) ?: error("No provider registered for ${model.provider}/${model.id} (${model.api})")

    suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = require(model).streamSimple(model, context, options)

    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages =
        imageProvidersByApi[model.api]?.generateImages(model, context, options)
            ?: error("No image provider registered for ${model.provider}/${model.id} (${model.api})")

    fun withAdditionalProviders(
        additionalProviders: List<PiProvider> = emptyList(),
        additionalImageProviders: List<PiImageProvider> = emptyList(),
    ): ProviderRegistry =
        ProviderRegistry(
            providers = providers + additionalProviders,
            imageProviders = imageProviders + additionalImageProviders,
        )
}

fun defaultProviderRegistry(
    transport: HttpTransport = defaultHttpTransport(),
): ProviderRegistry =
    ProviderRegistry(
        listOf(
            OpenAiCompletionsProvider(transport),
            OpenAiResponsesProvider(transport),
            OpenAiCodexResponsesProvider(transport),
            AnthropicMessagesProvider(transport),
            GoogleGenerativeAiProvider(transport),
            BedrockConverseProvider(transport),
        ),
        imageProviders = listOf(OpenRouterImageProvider(transport)),
    )
