package dev.xuexue.pi.coding.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull

internal fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(name: String): Double? = (get(name) as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull()
