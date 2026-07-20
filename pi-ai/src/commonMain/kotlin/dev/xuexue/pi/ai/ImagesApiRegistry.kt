package dev.xuexue.pi.ai

/**
 * API-level image provider registry matching Pi's images-api-registry module.
 * A provider can advertise several API names; registering it replaces the
 * previous provider for each advertised API.
 */
class ImagesApiRegistry(
    providers: List<PiImageProvider> = emptyList(),
) {
    private val providersByApi = linkedMapOf<ImagesApi, RegisteredImagesProvider>()

    init {
        providers.forEach(::register)
    }

    val providers: List<PiImageProvider>
        get() = providersByApi.values.map { it.provider }.distinctBy { it.id }

    fun register(
        provider: PiImageProvider,
        sourceId: String? = null,
    ) {
        require(provider.supportedApis.isNotEmpty()) {
            "Image provider ${provider.id} must advertise at least one API"
        }
        provider.supportedApis.forEach { api ->
            providersByApi[api] = RegisteredImagesProvider(provider, sourceId)
        }
    }

    fun unregister(providerId: ImagesProviderId) {
        providersByApi.entries.removeIf { (_, registration) -> registration.provider.id == providerId }
    }

    fun find(api: ImagesApi): PiImageProvider? = providersByApi[api]?.provider

    fun sourceId(api: ImagesApi): String? = providersByApi[api]?.sourceId

    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions = ImagesOptions(),
    ): AssistantImages {
        val provider = find(model.api) ?: error("No API provider registered for api: ${model.api}")
        require(model.api in provider.supportedApis) {
            "Mismatched api: ${model.api} is not supported by ${provider.id}"
        }
        return provider.generateImages(model, context, options)
    }

    private data class RegisteredImagesProvider(
        val provider: PiImageProvider,
        val sourceId: String?,
    )
}

private val defaultImagesApiRegistry = ImagesApiRegistry()

/** Process-wide API registry used by the Pi-compatible top-level functions. */
fun registerImagesApiProvider(
    provider: PiImageProvider,
    sourceId: String? = null,
) {
    defaultImagesApiRegistry.register(provider, sourceId)
}

fun unregisterImagesApiProvider(providerId: ImagesProviderId) {
    defaultImagesApiRegistry.unregister(providerId)
}

fun getImagesApiProvider(api: ImagesApi): PiImageProvider? = defaultImagesApiRegistry.find(api)

suspend fun generateImages(
    model: ImagesModel,
    context: ImagesContext,
    options: ImagesOptions = ImagesOptions(),
): AssistantImages = defaultImagesApiRegistry.generateImages(model, context, options)
