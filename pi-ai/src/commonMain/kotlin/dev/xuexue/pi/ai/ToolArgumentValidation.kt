// JSON-schema dispatch is intentionally kept together so recursive coercion and validation share one set of rules.
@file:Suppress("TooManyFunctions")
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.xuexue.pi.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Applies Pi's JSON-schema coercion rules and validates a tool call's arguments.
 *
 * Tool schemas are serialized JSON objects in the Kotlin port, so this deliberately
 * handles the JSON-schema subset used by Pi tools instead of relying on a generated
 * TypeBox validator.
 */
fun validateToolArguments(
    schema: JsonObject,
    arguments: JsonObject,
    toolName: String,
): JsonObject {
    val received = deepCopy(arguments) as JsonObject
    val coerced = coerceWithSchema(deepCopy(arguments), schema)
    val validated =
        validateSchema(coerced, schema, path = "")
    if (validated.isNotEmpty()) {
        val errors = validated.joinToString("\n") { "  - ${it.path}: ${it.message}" }
        val prettyArguments = PrettyJson.encodeToString(JsonElement.serializer(), received)
        throw IllegalArgumentException(
            "Validation failed for tool \"$toolName\":\n$errors\n\nReceived arguments:\n$prettyArguments",
        )
    }
    return coerced as? JsonObject
        ?: throw IllegalArgumentException("Validation failed for tool \"$toolName\":\n  - root: Expected object")
}

private val PrettyJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = true
    }

private data class ValidationError(
    val path: String,
    val message: String,
)

@Suppress("CyclomaticComplexMethod")
private fun coerceWithSchema(
    value: JsonElement,
    schema: JsonObject,
): JsonElement {
    var next = value
    schema["allOf"]?.asSchemaArray()?.forEach { nested ->
        next = coerceWithSchema(next, nested)
    }
    schema["anyOf"]?.asSchemaArray()?.let { branches ->
        next = coerceWithUnionSchema(next, branches)
    }
    schema["oneOf"]?.asSchemaArray()?.let { branches ->
        next = coerceWithUnionSchema(next, branches)
    }

    val types = schemaTypes(schema)
    val alreadyMatchesUnion = types.size > 1 && types.any { matchesJsonType(next, it) }
    if (types.isNotEmpty() && !alreadyMatchesUnion) {
        for (type in types) {
            val candidate = coercePrimitiveByType(next, type)
            if (candidate != next) {
                next = candidate
                break
            }
        }
    }

    if (next is JsonObject && (types.isEmpty() || "object" in types)) {
        next = coerceObject(next, schema)
    }
    if (next is JsonArray && (types.isEmpty() || "array" in types)) {
        next = coerceArray(next, schema)
    }
    return next
}

private fun coerceWithUnionSchema(
    value: JsonElement,
    branches: List<JsonObject>,
): JsonElement {
    for (branch in branches) {
        val candidate = coerceWithSchema(deepCopy(value), branch)
        if (validateSchema(candidate, branch, path = "").isEmpty()) {
            return candidate
        }
    }
    return value
}

private fun coerceObject(
    value: JsonObject,
    schema: JsonObject,
): JsonObject {
    val properties = schema["properties"]?.asSchemaObjectMap().orEmpty()
    val additionalSchema = schema["additionalProperties"] as? JsonObject
    val values = linkedMapOf<String, JsonElement>()
    value.forEach { (key, propertyValue) ->
        val propertySchema = properties[key] ?: additionalSchema
        values[key] =
            if (propertySchema == null) {
                propertyValue
            } else {
                coerceWithSchema(propertyValue, propertySchema)
            }
    }
    return JsonObject(values)
}

private fun coerceArray(
    value: JsonArray,
    schema: JsonObject,
): JsonArray {
    val itemSchemas = schema["items"]?.asSchemaArrayOrTuple()
    if (itemSchemas == null) return value
    val values =
        value.mapIndexed { index, item ->
            val itemSchema = itemSchemas.getOrNull(index) ?: itemSchemas.singleOrNull()
            if (itemSchema == null) item else coerceWithSchema(item, itemSchema)
        }
    return JsonArray(values)
}

@Suppress("CyclomaticComplexMethod")
private fun coercePrimitiveByType(
    value: JsonElement,
    type: String,
): JsonElement =
    when (type) {
        "number" -> {
            when {
                value is JsonNull -> JsonPrimitive(0)
                value is JsonPrimitive && value.isString -> parseNumber(value.content) ?: value
                value is JsonPrimitive && !value.isString && value.content == "true" -> JsonPrimitive(1)
                value is JsonPrimitive && !value.isString && value.content == "false" -> JsonPrimitive(0)
                else -> value
            }
        }

        "integer" -> {
            when {
                value is JsonNull -> JsonPrimitive(0)
                value is JsonPrimitive && value.isString -> parseInteger(value.content) ?: value
                value is JsonPrimitive && !value.isString && value.content == "true" -> JsonPrimitive(1)
                value is JsonPrimitive && !value.isString && value.content == "false" -> JsonPrimitive(0)
                else -> value
            }
        }

        "boolean" -> {
            when {
                value is JsonNull -> JsonPrimitive(false)
                value is JsonPrimitive && value.isString && value.content == "true" -> JsonPrimitive(true)
                value is JsonPrimitive && value.isString && value.content == "false" -> JsonPrimitive(false)
                value is JsonPrimitive && !value.isString && value.content == "1" -> JsonPrimitive(true)
                value is JsonPrimitive && !value.isString && value.content == "0" -> JsonPrimitive(false)
                else -> value
            }
        }

        "string" -> {
            when {
                value is JsonNull -> JsonPrimitive("")
                value is JsonPrimitive && !value.isString -> JsonPrimitive(value.content)
                else -> value
            }
        }

        "null" -> {
            when {
                value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
                value is JsonPrimitive && !value.isString && value.content == "false" -> JsonNull
                value is JsonPrimitive && !value.isString && value.doubleOrNull == 0.0 -> JsonNull
                else -> value
            }
        }

        else -> {
            value
        }
    }

private fun parseNumber(value: String): JsonPrimitive? {
    val parsed = value.trim().toDoubleOrNull() ?: return null
    return if (parsed.isFinite()) parsed.toJsonNumber() else null
}

private fun parseInteger(value: String): JsonPrimitive? =
    value
        .trim()
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
        ?.toJsonNumber()

private fun Double.toJsonNumber(): JsonPrimitive =
    if (this in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() && this % 1.0 == 0.0) {
        JsonPrimitive(toLong())
    } else {
        JsonPrimitive(this)
    }

@Suppress("CyclomaticComplexMethod")
private fun validateSchema(
    value: JsonElement,
    schema: JsonObject,
    path: String,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    val types = schemaTypes(schema)
    if (types.isNotEmpty() && types.none { matchesJsonType(value, it) }) {
        errors += ValidationError(displayPath(path), "Expected ${types.joinToString(" or ")}")
        return errors
    }

    schema["const"]?.let { expected ->
        if (value != expected) errors += ValidationError(displayPath(path), "Expected value to equal the constant")
    }
    schema["enum"]?.asJsonArray()?.let { allowed ->
        if (allowed.none { it == value }) {
            errors += ValidationError(displayPath(path), "Expected value to be one of the allowed values")
        }
    }

    schema["allOf"]?.asSchemaArray()?.forEach { nested ->
        errors += validateSchema(value, nested, path)
    }
    schema["anyOf"]?.asSchemaArray()?.let { branches ->
        if (branches.none { validateSchema(value, it, path).isEmpty() }) {
            errors += ValidationError(displayPath(path), "Expected value to match one of the schemas")
        }
    }
    schema["oneOf"]?.asSchemaArray()?.let { branches ->
        if (branches.count { validateSchema(value, it, path).isEmpty() } != 1) {
            errors += ValidationError(displayPath(path), "Expected value to match exactly one schema")
        }
    }

    when (value) {
        is JsonObject -> errors += validateObject(value, schema, path)
        is JsonArray -> errors += validateArray(value, schema, path)
        is JsonPrimitive -> errors += validatePrimitive(value, schema, path)
        JsonNull -> Unit
    }
    return errors
}

@Suppress("CyclomaticComplexMethod")
private fun validateObject(
    value: JsonObject,
    schema: JsonObject,
    path: String,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    val properties = schema["properties"]?.asSchemaObjectMap().orEmpty()
    schema["required"]?.asJsonArray()?.forEach { required ->
        val key = (required as? JsonPrimitive)?.content ?: return@forEach
        if (!value.containsKey(key)) {
            errors += ValidationError(joinPath(path, key), "Expected required property")
        }
    }
    properties.forEach { (key, propertySchema) ->
        value[key]?.let { propertyValue ->
            errors += validateSchema(propertyValue, propertySchema, joinPath(path, key))
        }
    }
    val additional = schema["additionalProperties"]
    if (additional is JsonPrimitive && additional.content == "false") {
        value.keys.filterNot(properties::containsKey).forEach { key ->
            errors += ValidationError(joinPath(path, key), "Unexpected property")
        }
    }
    if (additional is JsonObject) {
        value.forEach { (key, propertyValue) ->
            if (!properties.containsKey(key)) {
                errors += validateSchema(propertyValue, additional, joinPath(path, key))
            }
        }
    }
    schema["minProperties"]?.asInt()?.let { minimum ->
        if (value.size < minimum) errors += ValidationError(displayPath(path), "Expected at least $minimum properties")
    }
    schema["maxProperties"]?.asInt()?.let { maximum ->
        if (value.size > maximum) errors += ValidationError(displayPath(path), "Expected at most $maximum properties")
    }
    return errors
}

private fun validateArray(
    value: JsonArray,
    schema: JsonObject,
    path: String,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    schema["items"]?.let { items ->
        when (items) {
            is JsonObject -> {
                value.forEachIndexed { index, item ->
                    errors += validateSchema(item, items, joinPath(path, index.toString()))
                }
            }

            is JsonArray -> {
                value.forEachIndexed { index, item ->
                    items.getOrNull(index)?.let { itemSchema ->
                        (itemSchema as? JsonObject)?.let { objectSchema ->
                            errors += validateSchema(item, objectSchema, joinPath(path, index.toString()))
                        }
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }
    schema["minItems"]?.asInt()?.let { minimum ->
        if (value.size < minimum) errors += ValidationError(displayPath(path), "Expected at least $minimum items")
    }
    schema["maxItems"]?.asInt()?.let { maximum ->
        if (value.size > maximum) errors += ValidationError(displayPath(path), "Expected at most $maximum items")
    }
    schema["uniqueItems"]?.asBoolean()?.takeIf { it }?.let {
        if (value.toSet().size != value.size) {
            errors += ValidationError(displayPath(path), "Expected items to be unique")
        }
    }
    return errors
}

@Suppress("CyclomaticComplexMethod")
private fun validatePrimitive(
    value: JsonPrimitive,
    schema: JsonObject,
    path: String,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    if (value.isString) {
        schema["minLength"]?.asInt()?.let { minimum ->
            if (value.content.length < minimum) {
                errors += ValidationError(displayPath(path), "Expected string length at least $minimum")
            }
        }
        schema["maxLength"]?.asInt()?.let { maximum ->
            if (value.content.length > maximum) {
                errors += ValidationError(displayPath(path), "Expected string length at most $maximum")
            }
        }
        schema["pattern"]?.asString()?.let { pattern ->
            if (runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(false).not()) {
                errors += ValidationError(displayPath(path), "Expected string to match pattern")
            }
        }
    } else {
        value.doubleOrNull?.let { number ->
            schema["minimum"]?.asDouble()?.let { minimum ->
                if (number < minimum) errors += ValidationError(displayPath(path), "Expected number >= $minimum")
            }
            schema["maximum"]?.asDouble()?.let { maximum ->
                if (number > maximum) errors += ValidationError(displayPath(path), "Expected number <= $maximum")
            }
            schema["exclusiveMinimum"]?.asDouble()?.let { minimum ->
                if (number <= minimum) errors += ValidationError(displayPath(path), "Expected number > $minimum")
            }
            schema["exclusiveMaximum"]?.asDouble()?.let { maximum ->
                if (number >= maximum) errors += ValidationError(displayPath(path), "Expected number < $maximum")
            }
        }
    }
    return errors
}

private fun schemaTypes(schema: JsonObject): List<String> =
    when (val type = schema["type"]) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

@Suppress("CyclomaticComplexMethod")
private fun matchesJsonType(
    value: JsonElement,
    type: String,
): Boolean =
    when (type) {
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it % 1.0 == 0.0 } == true
        "boolean" -> value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
        "string" -> value is JsonPrimitive && value.isString
        "null" -> value is JsonNull
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        else -> false
    }

private fun deepCopy(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> deepCopy(value) })
        is JsonArray -> JsonArray(element.map(::deepCopy))
        else -> element
    }

private fun JsonElement.asJsonArray(): JsonArray? = this as? JsonArray

private fun JsonElement.asSchemaArray(): List<JsonObject>? = (this as? JsonArray)?.mapNotNull { it as? JsonObject }

private fun JsonElement.asSchemaArrayOrTuple(): List<JsonObject>? =
    when (this) {
        is JsonObject -> listOf(this)
        is JsonArray -> mapNotNull { it as? JsonObject }
        else -> null
    }

private fun JsonElement.asSchemaObjectMap(): Map<String, JsonObject>? =
    (this as? JsonObject)
        ?.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { key to it }
        }?.toMap()

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.content

private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.longOrNull?.toInt()

private fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull

private fun JsonElement.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.let { primitive ->
        if (primitive.isString) null else primitive.content.toBooleanStrictOrNull()
    }

private fun joinPath(
    path: String,
    segment: String,
): String = if (path.isEmpty()) segment else "$path.$segment"

private fun displayPath(path: String): String = path.ifEmpty { "root" }
