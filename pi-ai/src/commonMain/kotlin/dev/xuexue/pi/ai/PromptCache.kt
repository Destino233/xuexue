package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared prompt-cache rules used by the provider adapters. */
internal const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64

internal fun resolveCacheRetention(
    explicit: CacheRetention?,
    environment: ProviderEnv? = null,
): CacheRetention {
    if (explicit != null) return explicit
    val mergedEnvironment = ambientProviderEnvironment() + environment.orEmpty()
    return if (mergedEnvironment["PI_CACHE_RETENTION"]?.lowercase() == "long") {
        CacheRetention.LONG
    } else {
        CacheRetention.SHORT
    }
}

/** OpenAI counts Unicode code points, not UTF-16 code units, for this field. */
internal fun clampOpenAiPromptCacheKey(key: String?): String? {
    if (key == null) return null
    var index = 0
    var codePointCount = 0
    val result = StringBuilder()
    while (index < key.length && codePointCount < OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) {
        val current = key[index]
        result.append(current)
        if (current in '\uD800'..'\uDBFF' && index + 1 < key.length && key[index + 1] in '\uDC00'..'\uDFFF') {
            result.append(key[index + 1])
            index++
        }
        index++
        codePointCount++
    }
    return if (index == key.length) key else result.toString()
}

internal fun Model.supportsLongCacheRetention(): Boolean = compat?.boolean("supportsLongCacheRetention") ?: true

internal fun Model.compatBoolean(name: String): Boolean? = compat?.boolean(name)

internal fun Model.compatString(name: String): String? = compat?.get(name)?.jsonPrimitive?.contentOrNull

internal fun anthropicCacheControl(
    model: Model,
    retention: CacheRetention,
): JsonObject? {
    if (retention == CacheRetention.NONE) return null
    return buildJsonObject {
        put("type", "ephemeral")
        if (retention == CacheRetention.LONG && model.supportsLongCacheRetention()) put("ttl", "1h")
    }
}

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name]
    val primitive = value?.jsonPrimitive
    return primitive?.contentOrNull?.toBooleanStrictOrNull()
}
