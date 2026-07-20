package dev.xuexue.pi.ai

/** Pi's fast deterministic hash for compact, stable identifiers. */
fun shortHash(value: String): String {
    var h1 = 0xDEADBEEF.toInt()
    var h2 = 0x41C6CE57.toInt()
    value.forEach { character ->
        val code = character.code
        h1 = (h1 xor code) * 2654435761.toInt()
        h2 = (h2 xor code) * 1597334677.toInt()
    }
    h1 = ((h1 xor (h1 ushr 16)) * 2246822507.toInt()) xor
        ((h2 xor (h2 ushr 13)) * 3266489909.toInt())
    h2 = ((h2 xor (h2 ushr 16)) * 2246822507.toInt()) xor
        ((h1 xor (h1 ushr 13)) * 3266489909.toInt())
    return h2.toUInt().toString(36) + h1.toUInt().toString(36)
}

/** Removes deleted provider headers and returns null when no header remains. */
fun providerHeadersToRecord(headers: ProviderHeaders?): Map<String, String>? {
    val resolved = headers.orEmpty().mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    return resolved.takeIf(Map<*, *>::isNotEmpty)
}

/** Copies a platform HTTP header iterator into Pi's stable string record shape. */
fun headersToRecord(headers: Iterable<Pair<String, String>>): Map<String, String> = headers.toMap()

/**
 * Resolves Pi provider environment values from per-request overrides before
 * the ambient platform environment. Empty overrides deliberately fall through,
 * matching JavaScript's truthy lookup.
 */
fun getProviderEnvValue(
    name: String,
    environment: ProviderEnv? = null,
): String? = environment?.get(name).takeUnless(String?::isNullOrEmpty) ?: ambientProviderEnvironment()[name]
