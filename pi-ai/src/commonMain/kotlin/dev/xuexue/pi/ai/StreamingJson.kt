package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

fun parseStreamingJson(partialJson: String?): JsonElement {
    if (partialJson.isNullOrBlank()) return buildJsonObject {}

    return runCatching { parseJsonWithRepair(partialJson) }
        .recoverCatching { parsePartialJson(partialJson) }
        .recoverCatching { parsePartialJson(repairJson(partialJson)) }
        .getOrElse { buildJsonObject {} }
        .takeUnless { it is JsonNull }
        ?: buildJsonObject {}
}
