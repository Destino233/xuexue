@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "ReturnCount",
)

package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonObject

sealed interface AuthCredential {
    data class ApiKey(
        val key: String? = null,
        val environment: Map<String, String> = emptyMap(),
    ) : AuthCredential

    data class OAuth(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAtEpochMs: Long? = null,
        val metadata: JsonObject? = null,
    ) : AuthCredential
}

enum class AuthStatusSource {
    STORED,
    RUNTIME,
    ENVIRONMENT,
    NONE,
}

data class AuthStatus(
    val configured: Boolean,
    val source: AuthStatusSource = AuthStatusSource.NONE,
    val label: String? = null,
)

interface AuthStore {
    fun get(provider: String): AuthCredential?

    fun set(
        provider: String,
        credential: AuthCredential,
    )

    fun remove(provider: String)

    fun list(): List<String>

    fun setRuntimeApiKey(
        provider: String,
        apiKey: String,
    )

    fun removeRuntimeApiKey(provider: String)

    suspend fun getApiKey(
        provider: String,
        includeFallback: Boolean = true,
        environment: Map<String, String> = emptyMap(),
    ): String?

    fun hasAuth(
        provider: String,
        environment: Map<String, String> = emptyMap(),
    ): Boolean

    fun getAuthStatus(
        provider: String,
        environment: Map<String, String> = emptyMap(),
    ): AuthStatus
}

class InMemoryAuthStore(
    initial: Map<String, AuthCredential> = emptyMap(),
    private val ambientEnvironment: Map<String, String> = emptyMap(),
) : AuthStore {
    private val credentials = initial.toMutableMap()
    private val runtimeApiKeys = linkedMapOf<String, String>()

    override fun get(provider: String): AuthCredential? = credentials[provider]

    override fun set(
        provider: String,
        credential: AuthCredential,
    ) {
        credentials[provider] = credential
    }

    override fun remove(provider: String) {
        credentials.remove(provider)
    }

    override fun list(): List<String> = credentials.keys.toList()

    override fun setRuntimeApiKey(
        provider: String,
        apiKey: String,
    ) {
        require(apiKey.isNotBlank()) { "Runtime API key must not be blank" }
        runtimeApiKeys[provider] = apiKey
    }

    override fun removeRuntimeApiKey(provider: String) {
        runtimeApiKeys.remove(provider)
    }

    override suspend fun getApiKey(
        provider: String,
        includeFallback: Boolean,
        environment: Map<String, String>,
    ): String? {
        runtimeApiKeys[provider]?.let { return it }
        when (val credential = credentials[provider]) {
            is AuthCredential.ApiKey -> {
                val scopedEnvironment = ambientEnvironment + environment + credential.environment
                credential.key?.let { return resolveConfigValue(it, scopedEnvironment) }
                return null
            }

            is AuthCredential.OAuth -> {
                val expiresAt = credential.expiresAtEpochMs
                if (expiresAt == null || expiresAt > currentEpochMs()) return credential.accessToken
            }

            null -> {}
        }
        if (!includeFallback) return null
        if (provider.isBedrockProvider()) {
            return (ambientEnvironment + environment)["AWS_BEARER_TOKEN_BEDROCK"]
                ?.takeIf(String::isNotBlank)
        }
        return resolveEnvironmentApiKey(provider, ambientEnvironment + environment)
    }

    override fun hasAuth(
        provider: String,
        environment: Map<String, String>,
    ): Boolean {
        if (runtimeApiKeys.containsKey(provider) || credentials.containsKey(provider)) return true
        if (provider.isBedrockProvider()) {
            val values = ambientEnvironment + environment
            return !values["AWS_BEARER_TOKEN_BEDROCK"].isNullOrBlank() ||
                !values["AWS_PROFILE"].isNullOrBlank() ||
                (!values["AWS_ACCESS_KEY_ID"].isNullOrBlank() && !values["AWS_SECRET_ACCESS_KEY"].isNullOrBlank()) ||
                !values["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"].isNullOrBlank() ||
                !values["AWS_CONTAINER_CREDENTIALS_FULL_URI"].isNullOrBlank() ||
                !values["AWS_WEB_IDENTITY_TOKEN_FILE"].isNullOrBlank()
        }
        return resolveEnvironmentApiKey(provider, ambientEnvironment + environment) != null
    }

    override fun getAuthStatus(
        provider: String,
        environment: Map<String, String>,
    ): AuthStatus {
        if (credentials.containsKey(provider)) return AuthStatus(true, AuthStatusSource.STORED)
        if (runtimeApiKeys.containsKey(provider)) return AuthStatus(true, AuthStatusSource.RUNTIME, "runtime")
        if (provider.isBedrockProvider()) {
            val values = ambientEnvironment + environment
            val source =
                when {
                    !values["AWS_BEARER_TOKEN_BEDROCK"].isNullOrBlank() -> {
                        AuthStatus(true, AuthStatusSource.ENVIRONMENT, "AWS_BEARER_TOKEN_BEDROCK")
                    }

                    !values["AWS_PROFILE"].isNullOrBlank() -> {
                        AuthStatus(true, AuthStatusSource.ENVIRONMENT, "AWS_PROFILE")
                    }

                    !values["AWS_ACCESS_KEY_ID"].isNullOrBlank() &&
                        !values["AWS_SECRET_ACCESS_KEY"].isNullOrBlank() -> {
                        AuthStatus(true, AuthStatusSource.ENVIRONMENT, "AWS access keys")
                    }

                    !values["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"].isNullOrBlank() ||
                        !values["AWS_CONTAINER_CREDENTIALS_FULL_URI"].isNullOrBlank() -> {
                        AuthStatus(true, AuthStatusSource.ENVIRONMENT, "ECS task role")
                    }

                    !values["AWS_WEB_IDENTITY_TOKEN_FILE"].isNullOrBlank() -> {
                        AuthStatus(true, AuthStatusSource.ENVIRONMENT, "web identity token")
                    }

                    else -> {
                        AuthStatus(false)
                    }
                }
            return source
        }
        val envName =
            findEnvironmentApiKeys(provider).firstOrNull { name ->
                !((ambientEnvironment + environment)[name]).isNullOrBlank()
            }
        return if (envName == null) {
            AuthStatus(false)
        } else {
            AuthStatus(true, AuthStatusSource.ENVIRONMENT, envName)
        }
    }

    private fun currentEpochMs(): Long =
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds()
}

fun resolveConfigValue(
    value: String,
    environment: Map<String, String> = emptyMap(),
): String? {
    if (value.startsWith("!")) return null
    val parts = mutableListOf<String>()
    var index = 0
    while (index < value.length) {
        val dollar = value.indexOf('$', index)
        if (dollar < 0) {
            parts += value.substring(index)
            break
        }
        parts += value.substring(index, dollar)
        if (dollar + 1 >= value.length) {
            parts += "$"
            break
        }
        when (value[dollar + 1]) {
            '$', '!' -> {
                parts += value[dollar + 1].toString()
                index = dollar + 2
            }

            '{' -> {
                val end = value.indexOf('}', dollar + 2)
                if (end < 0) {
                    parts += "$"
                    index = dollar + 1
                } else {
                    val name = value.substring(dollar + 2, end)
                    val resolved = environment[name]
                    if (resolved == null) return null
                    parts += resolved
                    index = end + 1
                }
            }

            else -> {
                var end = dollar + 1
                while (end < value.length &&
                    (value[end].isLetterOrDigit() || value[end] == '_')
                ) {
                    end++
                }
                val name = value.substring(dollar + 1, end)
                if (name.isEmpty()) {
                    parts += "$"
                    index = dollar + 1
                } else {
                    parts += environment[name] ?: return null
                    index = end
                }
            }
        }
    }
    return parts.joinToString("")
}

fun findEnvironmentApiKeys(provider: String): List<String> =
    when (provider.lowercase()) {
        "anthropic" -> listOf("ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY")
        "openai" -> listOf("OPENAI_API_KEY")
        "azure-openai-responses" -> listOf("AZURE_OPENAI_API_KEY")
        "google" -> listOf("GEMINI_API_KEY")
        "google-vertex" -> listOf("GOOGLE_CLOUD_API_KEY")
        "openrouter" -> listOf("OPENROUTER_API_KEY")
        "vercel-ai-gateway" -> listOf("AI_GATEWAY_API_KEY")
        "github-copilot" -> listOf("COPILOT_GITHUB_TOKEN")
        "deepseek" -> listOf("DEEPSEEK_API_KEY")
        "nvidia" -> listOf("NVIDIA_API_KEY")
        "groq" -> listOf("GROQ_API_KEY")
        "cerebras" -> listOf("CEREBRAS_API_KEY")
        "xai" -> listOf("XAI_API_KEY")
        "zai" -> listOf("ZAI_API_KEY")
        "zai-coding-cn" -> listOf("ZAI_CODING_CN_API_KEY")
        "mistral" -> listOf("MISTRAL_API_KEY")
        "minimax" -> listOf("MINIMAX_API_KEY")
        "minimax-cn" -> listOf("MINIMAX_CN_API_KEY")
        "moonshotai", "moonshotai-cn" -> listOf("MOONSHOT_API_KEY")
        "huggingface" -> listOf("HF_TOKEN")
        "fireworks" -> listOf("FIREWORKS_API_KEY")
        "together" -> listOf("TOGETHER_API_KEY")
        "opencode", "opencode-go" -> listOf("OPENCODE_API_KEY")
        "kimi-coding" -> listOf("KIMI_API_KEY")
        "cloudflare-workers-ai", "cloudflare-ai-gateway" -> listOf("CLOUDFLARE_API_KEY")
        "xiaomi" -> listOf("XIAOMI_API_KEY")
        "xiaomi-token-plan-cn" -> listOf("XIAOMI_TOKEN_PLAN_CN_API_KEY")
        "xiaomi-token-plan-ams" -> listOf("XIAOMI_TOKEN_PLAN_AMS_API_KEY")
        "xiaomi-token-plan-sgp" -> listOf("XIAOMI_TOKEN_PLAN_SGP_API_KEY")
        else -> emptyList()
    }

private fun resolveEnvironmentApiKey(
    provider: String,
    environment: Map<String, String>,
): String? =
    findEnvironmentApiKeys(provider)
        .asSequence()
        .mapNotNull(environment::get)
        .firstOrNull(String::isNotBlank)

private fun String.isBedrockProvider(): Boolean = lowercase() in setOf("amazon-bedrock", "bedrock-converse-stream")
