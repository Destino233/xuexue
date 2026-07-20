package dev.xuexue.pi.coding.trust

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

internal fun readProjectTrustFile(content: String?): Map<String, ProjectTrustValue> {
    if (content.isNullOrBlank()) return emptyMap()
    val root =
        (TRUST_JSON.parseToJsonElement(content) as? JsonObject)
            ?: error("Invalid trust store: expected an object")
    return root.mapValues { (path, value) ->
        when (value) {
            JsonNull -> null
            is JsonPrimitive -> value.booleanOrNull ?: invalidTrustValue(path)
            else -> invalidTrustValue(path)
        }
    }
}

internal fun encodeProjectTrustFile(data: Map<String, ProjectTrustValue>): String =
    buildJsonObject {
        data.keys.sorted().forEach { path ->
            when (val value = data[path]) {
                null -> put(path, JsonNull)
                else -> put(path, JsonPrimitive(value))
            }
        }
    }.let { TRUST_JSON.encodeToString(JsonObject.serializer(), it) + "\n" }

private fun invalidTrustValue(path: String): Nothing =
    error(
        "Invalid trust store value for ${Json.encodeToString(path)}: expected true, false, or null",
    )

private val TRUST_JSON = Json { prettyPrint = true }
