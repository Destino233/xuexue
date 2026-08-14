// Pi's registry deliberately keeps auth, provider overrides, model merging, and pattern resolution in one
// precedence pipeline; these suppressions document that protocol-shaped branching rather than hide unrelated debt.
@file:Suppress(
    "CyclomaticComplexMethod",
    "DoubleMutabilityForCollection",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnnecessaryFilter",
    "VarCouldBeVal",
)

package dev.xuexue.pi.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_CONTEXT_WINDOW = 128_000L
private const val DEFAULT_MAX_TOKENS = 16_384L

data class ProviderModelDefinition(
    val id: String,
    val name: String = id,
    val api: Api? = null,
    val baseUrl: String? = null,
    val reasoning: Boolean = false,
    val thinkingLevelMap: Map<ModelThinkingLevel, String?>? = null,
    val input: List<ModelInput> = listOf(ModelInput.TEXT),
    val cost: ModelCost = ModelCost(0.0, 0.0, 0.0, 0.0),
    val contextWindow: Long = DEFAULT_CONTEXT_WINDOW,
    val maxTokens: Long = DEFAULT_MAX_TOKENS,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

data class ModelOverride(
    val name: String? = null,
    val reasoning: Boolean? = null,
    val thinkingLevelMap: Map<ModelThinkingLevel, String?>? = null,
    val input: List<ModelInput>? = null,
    val cost: ModelCostOverride? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

data class ModelCostOverride(
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val tiers: List<ModelCostTier>? = null,
)

data class ProviderConfig(
    val name: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val api: Api? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
    val authHeader: Boolean = false,
    val models: List<ProviderModelDefinition> = emptyList(),
    val modelOverrides: Map<String, ModelOverride> = emptyMap(),
)

data class ResolvedRequestAuth(
    val ok: Boolean,
    val apiKey: String? = null,
    val headers: Map<String, String>? = null,
    val environment: Map<String, String> = emptyMap(),
    val error: String? = null,
)

data class ModelResolution(
    val model: Model?,
    val thinkingLevel: String? = null,
    val warning: String? = null,
)

class ModelRegistry(
    private val authStore: AuthStore = InMemoryAuthStore(),
    private val environment: Map<String, String> = emptyMap(),
    private val builtInModels: List<Model> = defaultModelCatalog(),
    private var modelsJson: String? = null,
) {
    private val providerConfigs = linkedMapOf<String, ProviderConfig>()
    private var models: List<Model> = emptyList()
    private var loadError: String? = null

    init {
        refresh()
    }

    fun refresh(modelsJsonOverride: String? = modelsJson) {
        providerConfigs.clear()
        modelsJson = modelsJsonOverride
        loadError = null
        val parsed =
            modelsJsonOverride
                ?.let { text ->
                    runCatching { parseConfig(text) }
                        .onFailure { error ->
                            loadError = "Failed to load models.json: ${error.message.orEmpty()}"
                        }.getOrNull()
                }.orEmpty()
        providerConfigs.putAll(parsed)
        models = mergeModels(builtInModels, parsed)
    }

    fun getError(): String? = loadError

    fun getAll(): List<Model> = models.toList()

    fun getAvailable(): List<Model> = models.filter(::hasConfiguredAuth)

    fun find(
        provider: String,
        modelId: String,
    ): Model? = models.firstOrNull { it.provider == provider && it.id == modelId }

    fun findExactReference(reference: String): Model? {
        val normalized = reference.trim().lowercase()
        if (normalized.isEmpty()) return null
        val canonical = models.filter { "${it.provider}/${it.id}".lowercase() == normalized }
        if (canonical.isNotEmpty()) return canonical.first()
        val slash = reference.indexOf('/')
        if (slash >= 0) {
            val provider = reference.substring(0, slash).trim()
            val modelId = reference.substring(slash + 1).trim()
            val providerMatches =
                models.filter {
                    it.provider.equals(provider, ignoreCase = true) && it.id.equals(modelId, ignoreCase = true)
                }
            if (providerMatches.isNotEmpty()) return providerMatches.first()
        }
        val idMatches = models.filter { it.id.equals(reference.trim(), ignoreCase = true) }
        return idMatches.singleOrNull()
    }

    fun resolvePattern(pattern: String): ModelResolution {
        val trimmed = pattern.trim()
        findExactReference(trimmed)?.let { return ModelResolution(it) }
        if (hasGlob(trimmed)) {
            val matches =
                models.filter { model ->
                    wildcardMatches("${model.provider}/${model.id}", trimmed) || wildcardMatches(model.id, trimmed)
                }
            return ModelResolution(selectBest(matches))
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon < 0) return ModelResolution(selectBest(partialMatches(trimmed)))
        val prefix = trimmed.substring(0, colon)
        val suffix = trimmed.substring(colon + 1)
        val resolved = resolvePattern(prefix)
        return if (resolved.model == null) {
            ModelResolution(null)
        } else if (suffix.lowercase() in THINKING_LEVEL_NAMES) {
            resolved.copy(thinkingLevel = suffix.lowercase())
        } else {
            resolved.copy(warning = "Invalid thinking level \"$suffix\" in pattern \"$trimmed\". Using default instead.")
        }
    }

    fun hasConfiguredAuth(model: Model): Boolean {
        val config = providerConfigs[model.provider]
        return authStore.hasAuth(model.provider, environment) ||
            (config?.apiKey != null && resolveConfigValue(config.apiKey, environment) != null)
    }

    suspend fun getApiKeyAndHeaders(model: Model): ResolvedRequestAuth {
        return try {
            val config = providerConfigs[model.provider]
            val providerEnvironment = environment + (authStore.get(model.provider) as? AuthCredential.ApiKey)?.environment.orEmpty()
            val apiKey =
                authStore.getApiKey(model.provider, includeFallback = false, environment = environment)
                    ?: config?.apiKey?.let { resolveConfigValue(it, providerEnvironment) }
            var headers = linkedMapOf<String, String>()
            model.headers.orEmpty().forEach { (key, value) ->
                resolveConfigValue(value, providerEnvironment)?.let { headers[key] = it }
            }
            config?.headers.orEmpty().forEach { (key, value) ->
                resolveConfigValue(value, providerEnvironment)?.let { headers[key] = it }
            }
            if (config?.authHeader == true) {
                if (apiKey.isNullOrBlank()) return ResolvedRequestAuth(false, error = "No API key found for ${model.provider}")
                headers["Authorization"] = "Bearer $apiKey"
            }
            ResolvedRequestAuth(
                ok = true,
                apiKey = apiKey,
                headers = headers.takeIf(Map<*, *>::isNotEmpty),
                environment = providerEnvironment,
            )
        } catch (error: Throwable) {
            ResolvedRequestAuth(false, error = error.message.orEmpty())
        }
    }

    fun getProviderAuthStatus(provider: String): AuthStatus =
        authStore.getAuthStatus(provider, environment).let { status ->
            if (status.configured) return status
            val config = providerConfigs[provider]
            val key = config?.apiKey ?: return status
            if (resolveConfigValue(key, environment) != null) {
                AuthStatus(true, AuthStatusSource.ENVIRONMENT, key.takeIf { it.startsWith("$") })
            } else {
                status
            }
        }

    fun getProviderDisplayName(provider: String): String = providerConfigs[provider]?.name ?: provider

    fun registerProvider(
        provider: String,
        config: ProviderConfig,
    ) {
        validateProvider(provider, config)
        providerConfigs[provider] = config
        models = mergeModels(builtInModels, providerConfigs)
    }

    fun unregisterProvider(provider: String) {
        providerConfigs.remove(provider)
        models = mergeModels(builtInModels, providerConfigs)
    }

    private fun mergeModels(
        builtIns: List<Model>,
        configs: Map<String, ProviderConfig>,
    ): List<Model> {
        val merged =
            builtIns
                .map { model ->
                    val config = configs[model.provider]
                    val override = config?.modelOverrides?.get(model.id)
                    applyOverride(
                        model.copy(
                            baseUrl = config?.baseUrl ?: model.baseUrl,
                            headers = mergeHeaders(model.headers, config?.headers),
                            compat = config?.let { mergeJsonObjects(model.compat, rawCompat(it)) } ?: model.compat,
                        ),
                        override,
                    )
                }.toMutableList()
        configs.forEach { (provider, config) ->
            config.models.forEach { definition ->
                val model = toModel(provider, config, definition)
                val index = merged.indexOfFirst { it.provider == provider && it.id == model.id }
                if (index >= 0) merged[index] = model else merged += model
            }
        }
        return merged
    }

    private fun toModel(
        provider: String,
        config: ProviderConfig,
        definition: ProviderModelDefinition,
    ): Model {
        val base = builtInModels.firstOrNull { it.provider == provider }
        val model =
            Model(
                id = definition.id,
                name = definition.name,
                api = definition.api ?: config.api ?: base?.api ?: "openai-completions",
                provider = provider,
                baseUrl = definition.baseUrl ?: config.baseUrl ?: base?.baseUrl.orEmpty(),
                reasoning = definition.reasoning,
                thinkingLevelMap = definition.thinkingLevelMap,
                input = definition.input,
                cost = definition.cost,
                contextWindow = definition.contextWindow,
                maxTokens = definition.maxTokens,
                headers = mergeHeaders(config.headers, definition.headers),
                compat = mergeJsonObjects(config.compat, definition.compat),
            )
        return applyOverride(model, config.modelOverrides[definition.id])
    }

    private fun applyOverride(
        model: Model,
        override: ModelOverride?,
    ): Model {
        if (override == null) return model
        val cost = override.cost
        return model.copy(
            name = override.name ?: model.name,
            reasoning = override.reasoning ?: model.reasoning,
            thinkingLevelMap = override.thinkingLevelMap ?: model.thinkingLevelMap,
            input = override.input ?: model.input,
            cost =
                cost?.let {
                    ModelCost(
                        input = it.input ?: model.cost.input,
                        output = it.output ?: model.cost.output,
                        cacheRead = it.cacheRead ?: model.cost.cacheRead,
                        cacheWrite = it.cacheWrite ?: model.cost.cacheWrite,
                        tiers = it.tiers ?: model.cost.tiers,
                    )
                } ?: model.cost,
            contextWindow = override.contextWindow ?: model.contextWindow,
            maxTokens = override.maxTokens ?: model.maxTokens,
            headers = mergeHeaders(model.headers, override.headers),
            compat = mergeJsonObjects(model.compat, override.compat),
        )
    }

    private fun parseConfig(text: String): Map<String, ProviderConfig> {
        val root = Json.parseToJsonElement(stripJsonComments(text)).jsonObject
        return root["providers"]?.jsonObject.orEmpty().mapValues { (provider, element) ->
            parseProvider(provider, element.jsonObject)
        }
    }

    private fun parseProvider(
        provider: String,
        objectValue: JsonObject,
    ): ProviderConfig {
        val models =
            objectValue["models"]?.jsonArray.orEmpty().mapNotNull { element ->
                parseModelDefinition(element.jsonObject)
            }
        val overrides =
            objectValue["modelOverrides"]
                ?.jsonObject
                .orEmpty()
                .mapNotNull { (modelId, element) ->
                    parseModelOverride(element.jsonObject)?.let { modelId to it }
                }.toMap()
        return ProviderConfig(
            name = objectValue.string("name"),
            baseUrl = objectValue.string("baseUrl"),
            apiKey = objectValue.string("apiKey"),
            api = objectValue.string("api"),
            headers = objectValue.stringMap("headers"),
            compat = objectValue["compat"] as? JsonObject,
            authHeader = objectValue.boolean("authHeader") ?: false,
            models = models,
            modelOverrides = overrides,
        ).also { config -> validateProvider(provider, config) }
    }

    private fun parseModelDefinition(objectValue: JsonObject): ProviderModelDefinition? {
        val id = objectValue.string("id") ?: return null
        return ProviderModelDefinition(
            id = id,
            name = objectValue.string("name") ?: id,
            api = objectValue.string("api"),
            baseUrl = objectValue.string("baseUrl"),
            reasoning = objectValue.boolean("reasoning") ?: false,
            thinkingLevelMap = objectValue.thinkingLevelMap("thinkingLevelMap"),
            input = objectValue.inputTypes("input"),
            cost = objectValue.cost("cost"),
            contextWindow = objectValue.long("contextWindow") ?: DEFAULT_CONTEXT_WINDOW,
            maxTokens = objectValue.long("maxTokens") ?: DEFAULT_MAX_TOKENS,
            headers = objectValue.stringMap("headers"),
            compat = objectValue["compat"] as? JsonObject,
        )
    }

    private fun parseModelOverride(objectValue: JsonObject): ModelOverride? =
        ModelOverride(
            name = objectValue.string("name"),
            reasoning = objectValue.boolean("reasoning"),
            thinkingLevelMap = objectValue.thinkingLevelMap("thinkingLevelMap"),
            input = objectValue.inputTypesOrNull("input"),
            cost = objectValue.costOverride("cost"),
            contextWindow = objectValue.long("contextWindow"),
            maxTokens = objectValue.long("maxTokens"),
            headers = objectValue.stringMap("headers"),
            compat = objectValue["compat"] as? JsonObject,
        )

    private fun validateProvider(
        provider: String,
        config: ProviderConfig,
    ) {
        if (config.models.isEmpty()) {
            require(config.baseUrl != null || config.headers != null || config.modelOverrides.isNotEmpty()) {
                "Provider $provider must specify baseUrl, headers, modelOverrides, or models"
            }
        } else {
            require(config.baseUrl != null || builtInModels.any { it.provider == provider }) {
                "Provider $provider requires baseUrl when defining custom models"
            }
            config.models.forEach { model ->
                require(model.api != null || config.api != null || builtInModels.any { it.provider == provider }) {
                    "Provider $provider, model ${model.id}: no api specified"
                }
                require(model.contextWindow > 0) { "Provider $provider, model ${model.id}: invalid contextWindow" }
                require(model.maxTokens > 0) { "Provider $provider, model ${model.id}: invalid maxTokens" }
            }
        }
    }

    private fun partialMatches(pattern: String): List<Model> = models.filter { it.id.contains(pattern, ignoreCase = true) || it.name.contains(pattern, ignoreCase = true) }

    private fun selectBest(matches: List<Model>): Model? {
        if (matches.isEmpty()) return null
        val aliases = matches.filterNot { DATE_SUFFIX.matches(it.id) }
        return (aliases.ifEmpty { matches }).maxByOrNull { it.id }
    }

    private fun hasGlob(value: String): Boolean = value.any { it == '*' || it == '?' }

    private fun wildcardMatches(
        value: String,
        pattern: String,
    ): Boolean {
        val regex =
            buildString {
                append('^')
                pattern.forEach { char ->
                    when (char) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> append(Regex.escape(char.toString()))
                    }
                }
                append('$')
            }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(value)
    }

    private fun mergeHeaders(
        first: Map<String, String>?,
        second: Map<String, String>?,
    ): Map<String, String>? = (first.orEmpty() + second.orEmpty()).takeIf(Map<*, *>::isNotEmpty)

    private fun rawCompat(config: ProviderConfig): JsonObject? = config.compat

    private fun mergeJsonObjects(
        first: JsonObject?,
        second: JsonObject?,
    ): JsonObject? =
        when {
            first == null -> {
                second
            }

            second == null -> {
                first
            }

            else -> {
                buildJsonObject {
                    first.forEach(::put)
                    second.forEach(::put)
                }
            }
        }

    private fun stripJsonComments(text: String): String {
        // Remove `//` line comments and trailing commas, leaving string literals untouched.
        val withoutComments =
            buildString {
                var inString = false
                var escaped = false
                var index = 0
                while (index < text.length) {
                    val character = text[index]
                    if (character == '\n') {
                        append(character)
                        index++
                        continue
                    }
                    if (character == '"' && !escaped) inString = !inString
                    if (!inString && character == '/' && text.getOrNull(index + 1) == '/') {
                        while (index < text.length && text[index] != '\n') index++
                        continue
                    }
                    append(character)
                    escaped = character == '\\' && !escaped
                    if (character != '\\') escaped = false
                    index++
                }
            }
        return buildString {
            var inString = false
            var escaped = false
            var index = 0
            while (index < withoutComments.length) {
                val character = withoutComments[index]
                if (character == '\n') {
                    append(character)
                    index++
                    continue
                }
                if (character == '"' && !escaped) inString = !inString
                if (!inString && character == ',') {
                    var lookahead = index + 1
                    while (lookahead < withoutComments.length && withoutComments[lookahead].isWhitespace()) {
                        lookahead++
                    }
                    if (withoutComments.getOrNull(lookahead)?.let { it == '}' || it == ']' } == true) {
                        index++
                        continue
                    }
                }
                append(character)
                escaped = character == '\\' && !escaped
                if (character != '\\') escaped = false
                index++
            }
        }
    }

    private companion object {
        val DATE_SUFFIX = Regex("-\\d{8}$")
        val THINKING_LEVEL_NAMES = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}

fun defaultModelCatalog(): List<Model> = generatedPiModelCatalog()

/** Returns the pinned Pi image-generation model catalog. */
fun defaultImageModelCatalog(): List<ImagesModel> = generatedPiImageModelCatalog()

/** Retained only as a migration reference; PiGeneratedModelCatalog is authoritative. */
fun legacyDefaultModelCatalog(): List<Model> =
    buildList {
        addAll(
            catalogModels(
                provider = "openai",
                baseUrl = "https://api.openai.com/v1",
                api = "openai-responses",
                reasoning = true,
                ids =
                    listOf(
                        "gpt-4.1",
                        "gpt-4.1-mini",
                        "gpt-4.1-nano",
                        "gpt-4o",
                        "gpt-4o-mini",
                        "gpt-5",
                        "gpt-5-mini",
                        "gpt-5-nano",
                        "gpt-5-pro",
                        "gpt-5.1",
                        "gpt-5.1-codex",
                        "gpt-5.1-codex-mini",
                        "gpt-5.2",
                        "gpt-5.2-codex",
                        "gpt-5.2-pro",
                        "gpt-5.3-codex",
                        "gpt-5.4",
                        "gpt-5.4-mini",
                        "gpt-5.4-nano",
                        "gpt-5.5",
                        "o3",
                        "o3-mini",
                        "o3-pro",
                        "o4-mini",
                    ),
            ),
        )
        addAll(
            catalogModels(
                provider = "anthropic",
                baseUrl = "https://api.anthropic.com",
                api = "anthropic-messages",
                reasoning = true,
                ids =
                    listOf(
                        "claude-haiku-4-5",
                        "claude-haiku-4-5-20251001",
                        "claude-opus-4-1",
                        "claude-opus-4-5",
                        "claude-opus-4-6",
                        "claude-opus-4-7",
                        "claude-opus-4-8",
                        "claude-sonnet-4",
                        "claude-sonnet-4-5",
                        "claude-sonnet-4-6",
                        "claude-sonnet-5",
                        "claude-fable-5",
                    ),
            ),
        )
        addAll(
            catalogModels(
                provider = "google",
                baseUrl = "https://generativelanguage.googleapis.com",
                api = "google-generative-ai",
                reasoning = true,
                ids =
                    listOf(
                        "gemini-2.0-flash",
                        "gemini-2.0-flash-lite",
                        "gemini-2.5-flash",
                        "gemini-2.5-flash-lite",
                        "gemini-2.5-pro",
                        "gemini-3-flash-preview",
                        "gemini-3-pro-preview",
                        "gemini-3.1-flash-lite",
                        "gemini-3.1-pro-preview",
                        "gemini-3.5-flash",
                        "gemma-4-26b-a4b-it",
                        "gemma-4-31b-it",
                    ),
            ),
        )
        addAll(
            catalogModels(
                provider = "google-vertex",
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                api = "google-vertex",
                reasoning = true,
                ids = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3.1-pro-preview"),
            ),
        )
        addAll(
            catalogModels(
                provider = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                api = "openai-completions",
                reasoning = true,
                ids =
                    listOf(
                        "openai/gpt-4o",
                        "openai/gpt-4.1",
                        "openai/gpt-5",
                        "openai/gpt-5.2",
                        "openai/o3",
                        "anthropic/claude-sonnet-4.5",
                        "anthropic/claude-opus-4.6",
                        "google/gemini-2.5-pro",
                        "google/gemini-3-flash-preview",
                        "deepseek/deepseek-v3.2",
                        "deepseek/deepseek-r1",
                        "moonshotai/kimi-k2.5",
                        "qwen/qwen3-coder",
                        "z-ai/glm-5",
                    ),
            ),
        )
        addAll(
            catalogModels(
                provider = "deepseek",
                baseUrl = "https://api.deepseek.com",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v3.2", "deepseek-v4-flash", "deepseek-v4-pro"),
            ),
        )
        addAll(
            catalogModels(
                provider = "mistral",
                baseUrl = "https://api.mistral.ai/v1",
                api = "mistral-conversations",
                reasoning = true,
                ids = listOf("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest", "codestral-latest", "devstral-latest", "magistral-small"),
            ),
        )
        addAll(
            catalogModels(
                provider = "moonshotai",
                baseUrl = "https://api.moonshot.ai/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("kimi-k2-0711-preview", "kimi-k2-0905-preview", "kimi-k2-thinking", "kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code"),
            ),
        )
        addAll(
            catalogModels(
                provider = "minimax",
                baseUrl = "https://api.minimax.io/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M3"),
            ),
        )
        addAll(
            catalogModels(
                provider = "xai",
                baseUrl = "https://api.x.ai/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("grok-3", "grok-3-fast", "grok-4.3", "grok-4.5", "grok-code-fast-1"),
            ),
        )
        addAll(
            catalogModels(
                provider = "zai",
                baseUrl = "https://api.z.ai/api/paas/v4",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("glm-4.5-air", "glm-4.7", "glm-5-turbo", "glm-5.1", "glm-5.2", "glm-5v-turbo"),
            ),
        )
        addAll(
            catalogModels(
                provider = "groq",
                baseUrl = "https://api.groq.com/openai/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "qwen/qwen3-32b"),
            ),
        )
        addAll(
            catalogModels(
                provider = "cerebras",
                baseUrl = "https://api.cerebras.ai/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("llama3.1-8b", "llama-3.3-70b", "gpt-oss-120b", "zai-glm-4.7"),
            ),
        )
        addAll(
            catalogModels(
                provider = "nvidia",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("meta/llama-3.3-70b-instruct", "nvidia/nemotron-3-super-120b-a12b", "openai/gpt-oss-120b", "qwen/qwen3.5-122b-a10b"),
            ),
        )
        addAll(
            catalogModels(
                provider = "huggingface",
                baseUrl = "https://api-inference.huggingface.co/v1",
                api = "openai-completions",
                reasoning = true,
                ids = listOf("deepseek-ai/DeepSeek-R1", "Qwen/Qwen3-Coder-Next", "Qwen/Qwen3.5-397B-A17B", "zai-org/GLM-5"),
            ),
        )
    }

private fun catalogModels(
    provider: String,
    baseUrl: String,
    api: Api,
    reasoning: Boolean,
    ids: List<String>,
): List<Model> = ids.map { id -> catalogModel(provider, id, baseUrl, api, reasoning) }

private fun catalogModel(
    provider: String,
    id: String,
    baseUrl: String,
    api: Api = "openai-completions",
    reasoning: Boolean,
): Model =
    Model(
        id = id,
        name = id,
        api = api,
        provider = provider,
        baseUrl = baseUrl,
        reasoning = reasoning,
        input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.stringMap(name: String): Map<String, String>? = (this[name] as? JsonObject)?.mapValues { (_, value) -> value.jsonPrimitive.content }

private fun JsonObject.inputTypes(name: String): List<ModelInput> = inputTypesOrNull(name) ?: listOf(ModelInput.TEXT)

private fun JsonObject.inputTypesOrNull(name: String): List<ModelInput>? =
    (this[name] as? JsonArray)?.mapNotNull { value ->
        when (value.jsonPrimitive.content.lowercase()) {
            "text" -> ModelInput.TEXT
            "image" -> ModelInput.IMAGE
            else -> null
        }
    }

private fun JsonObject.thinkingLevelMap(name: String): Map<ModelThinkingLevel, String?>? =
    (this[name] as? JsonObject)
        ?.mapNotNull { (key, value) ->
            val level = ModelThinkingLevel.entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: return@mapNotNull null
            level to value.jsonPrimitive.contentOrNull
        }?.toMap()

private fun JsonObject.cost(name: String): ModelCost =
    costOverride(name)?.let { override ->
        ModelCost(
            input = override.input ?: 0.0,
            output = override.output ?: 0.0,
            cacheRead = override.cacheRead ?: 0.0,
            cacheWrite = override.cacheWrite ?: 0.0,
            tiers = override.tiers,
        )
    } ?: ModelCost(0.0, 0.0, 0.0, 0.0)

private fun JsonObject.costOverride(name: String): ModelCostOverride? {
    val value = this[name] as? JsonObject ?: return null
    val tiers =
        (value["tiers"] as? JsonArray)?.mapNotNull { tier ->
            val objectValue = tier.jsonObject
            val inputTokensAbove = objectValue.long("inputTokensAbove") ?: return@mapNotNull null
            ModelCostTier(
                input = objectValue.double("input"),
                output = objectValue.double("output"),
                cacheRead = objectValue.double("cacheRead"),
                cacheWrite = objectValue.double("cacheWrite"),
                inputTokensAbove = inputTokensAbove,
            )
        }
    return ModelCostOverride(
        input = value.doubleOrNull("input"),
        output = value.doubleOrNull("output"),
        cacheRead = value.doubleOrNull("cacheRead"),
        cacheWrite = value.doubleOrNull("cacheWrite"),
        tiers = tiers,
    )
}

private fun JsonObject.double(name: String): Double = doubleOrNull(name) ?: 0.0

private fun JsonObject.doubleOrNull(name: String): Double? = this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
