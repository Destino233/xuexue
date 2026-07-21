@file:Suppress("TooManyFunctions")

package dev.xuexue.pi.ai

/** Pi's legacy API-keyed provider surface, retained for SDK compatibility. */
interface ApiProvider {
    val api: Api

    suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessageEventStream

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessageEventStream = stream(model, context, options)
}

class ApiProviderRegistry(
    builtInProviders: List<PiProvider> = emptyList(),
) {
    private val providers = linkedMapOf<Api, RegisteredApiProvider>()
    private val builtIns = builtInProviders.flatMap(::apiProvidersFor)

    init {
        registerBuiltInProviders()
    }

    fun register(
        provider: ApiProvider,
        sourceId: String? = null,
    ) {
        providers[provider.api] = RegisteredApiProvider(provider, sourceId)
    }

    fun find(api: Api): ApiProvider? = providers[api]?.provider

    fun all(): List<ApiProvider> = providers.values.map(RegisteredApiProvider::provider)

    fun unregisterBySource(sourceId: String) {
        providers.entries.removeIf { (_, entry) -> entry.sourceId == sourceId }
    }

    fun reset() {
        providers.clear()
        registerBuiltInProviders()
    }

    private fun registerBuiltInProviders() {
        builtIns.forEach { provider ->
            if (find(provider.api) == null) register(provider)
        }
    }

    private data class RegisteredApiProvider(
        val provider: ApiProvider,
        val sourceId: String?,
    )
}

private val defaultApiProviderRegistry by lazy {
    ApiProviderRegistry(defaultProviderRegistry().providers)
}

/** Registers a compatibility API provider, replacing the previous provider for that API. */
fun registerApiProvider(
    provider: ApiProvider,
    sourceId: String? = null,
) {
    defaultApiProviderRegistry.register(provider, sourceId)
}

fun getApiProvider(api: Api): ApiProvider? = defaultApiProviderRegistry.find(api)

fun getApiProviders(): List<ApiProvider> = defaultApiProviderRegistry.all()

fun unregisterApiProviders(sourceId: String) {
    defaultApiProviderRegistry.unregisterBySource(sourceId)
}

fun resetApiProviders() {
    defaultApiProviderRegistry.reset()
}

/** Static Pi catalog compatibility reads. New hosts should use [ModelRegistry]. */
fun getModel(
    provider: ProviderId,
    id: String,
): Model? = defaultModelCatalog().firstOrNull { it.provider == provider && it.id == id }

fun getModels(): List<Model> = defaultModelCatalog()

fun getProviders(): List<ProviderId> = defaultModelCatalog().map(Model::provider).distinct()

/**
 * Pi's old global stream entrypoint. The API registry is resolved by
 * [Model.api], while environment API-key injection only runs when callers did
 * not provide a nonblank key explicitly.
 */
suspend fun stream(
    model: Model,
    context: Context,
    options: SimpleStreamOptions = SimpleStreamOptions(),
): AssistantMessageEventStream =
    requireApiProvider(model.api).stream(
        model,
        context,
        options.withEnvironmentApiKey(model),
    )

suspend fun streamSimple(
    model: Model,
    context: Context,
    options: SimpleStreamOptions = SimpleStreamOptions(),
): AssistantMessageEventStream =
    requireApiProvider(model.api).streamSimple(
        model,
        context,
        options.withEnvironmentApiKey(model),
    )

suspend fun complete(
    model: Model,
    context: Context,
    options: SimpleStreamOptions = SimpleStreamOptions(),
): AssistantMessage = stream(model, context, options).result()

suspend fun completeSimple(
    model: Model,
    context: Context,
    options: SimpleStreamOptions = SimpleStreamOptions(),
): AssistantMessage = streamSimple(model, context, options).result()

private fun apiProvidersFor(provider: PiProvider): List<ApiProvider> =
    provider.supportedApis.map { api ->
        object : ApiProvider {
            override val api: Api = api

            override suspend fun stream(
                model: Model,
                context: Context,
                options: SimpleStreamOptions,
            ): AssistantMessageEventStream {
                check(model.api == api) { "Mismatched api: ${model.api} expected $api" }
                return provider.stream(model, context, options)
            }

            override suspend fun streamSimple(
                model: Model,
                context: Context,
                options: SimpleStreamOptions,
            ): AssistantMessageEventStream {
                check(model.api == api) { "Mismatched api: ${model.api} expected $api" }
                return provider.streamSimple(model, context, options)
            }
        }
    }

private fun requireApiProvider(
    api: Api,
): ApiProvider = getApiProvider(api) ?: error("No API provider registered for api: $api")

private fun SimpleStreamOptions.withEnvironmentApiKey(model: Model): SimpleStreamOptions {
    if (!stream.apiKey.isNullOrBlank()) return this
    val environment = ambientProviderEnvironment() + stream.env.orEmpty()
    val apiKey =
        findEnvironmentApiKeys(model.provider)
            .asSequence()
            .mapNotNull(environment::get)
            .firstOrNull(String::isNotBlank)
    return if (apiKey == null) this else copy(stream = stream.copy(apiKey = apiKey))
}
