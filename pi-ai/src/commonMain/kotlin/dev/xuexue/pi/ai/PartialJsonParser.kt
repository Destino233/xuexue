package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parsePartialJson(json: String): JsonElement {
    require(json.isNotBlank()) { "$json is empty" }
    return PartialJsonParser(json.trim()).parse()
}

private class PartialJsonParser(
    private val json: String,
) {
    private var index = 0

    fun parse(): JsonElement = parseAny()

    private fun parseAny(): JsonElement {
        skipBlank()
        if (index >= json.length) throw PartialJsonException("Unexpected end of input at position $index")
        return when (json[index]) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> parseArray()
            else -> parseAtom()
        }
    }

    private fun parseAtom(): JsonElement {
        val remaining = json.substring(index)
        return when {
            json.startsWith("null", index) || "null".startsWith(remaining) -> {
                advanceKeyword("null", JsonNull)
            }

            json.startsWith("true", index) || "true".startsWith(remaining) -> {
                advanceKeyword("true", JsonPrimitive(true))
            }

            json.startsWith("false", index) || "false".startsWith(remaining) -> {
                advanceKeyword("false", JsonPrimitive(false))
            }

            else -> {
                parseNumber()
            }
        }
    }

    private fun advanceKeyword(
        keyword: String,
        value: JsonElement,
    ): JsonElement {
        index += keyword.length
        return value
    }

    private fun parseString(): JsonPrimitive {
        val start = index
        var escaped = false
        index++
        while (index < json.length && (json[index] != '"' || escaped)) {
            escaped = if (json[index] == '\\') !escaped else false
            index++
        }

        if (json.getOrNull(index) == '"') {
            index++
            return parseStringLiteral(json.substring(start, index))
        }

        val end = index - if (escaped) 1 else 0
        return try {
            parseStringLiteral(json.substring(start, end) + '"')
        } catch (_: IllegalArgumentException) {
            val lastEscape = json.lastIndexOf('\\', startIndex = index - 1)
            parseStringLiteral(json.substring(start, lastEscape) + '"')
        }
    }

    private fun parseStringLiteral(value: String): JsonPrimitive {
        val parsed = parseStrictJson(value)
        return parsed as? JsonPrimitive ?: throw MalformedJsonException("Expected string at position $index")
    }

    private fun parseObject(): JsonObject {
        index++
        skipBlank()
        val values = linkedMapOf<String, JsonElement>()
        var parsing = true
        while (parsing && json.getOrNull(index) != '}') {
            skipBlank()
            if (index >= json.length) {
                parsing = false
            } else {
                val key = runCatching { parseString().content }.getOrNull()
                skipBlank()
                index++
                val value = runCatching(::parseAny).getOrNull()
                if (key == null || value == null) {
                    parsing = false
                } else {
                    values[key] = value
                    skipBlank()
                    if (json.getOrNull(index) == ',') index++
                }
            }
        }
        if (json.getOrNull(index) == '}') index++
        return JsonObject(values)
    }

    private fun parseArray(): JsonArray {
        index++
        val values = mutableListOf<JsonElement>()
        try {
            while (json.getOrNull(index) != ']') {
                values += parseAny()
                skipBlank()
                if (json.getOrNull(index) == ',') index++
            }
        } catch (_: RuntimeException) {
            return JsonArray(values)
        }
        index++
        return JsonArray(values)
    }

    private fun parseNumber(): JsonPrimitive {
        val start = index
        if (json.getOrNull(index) == '-') index++
        while (json.getOrNull(index) != null && json[index] !in ",]}") index++
        val candidate = json.substring(start, index)
        if (candidate == "-") throw PartialJsonException("Not sure what '-' is at position $index")
        return try {
            parseNumberLiteral(candidate)
        } catch (error: IllegalArgumentException) {
            val exponent = maxOf(candidate.lastIndexOf('e'), candidate.lastIndexOf('E'))
            if (exponent <= 0) throw MalformedJsonException(error.message.orEmpty(), error)
            parseNumberLiteral(candidate.substring(0, exponent))
        }
    }

    private fun parseNumberLiteral(value: String): JsonPrimitive {
        val parsed = parseStrictJson(value)
        return parsed as? JsonPrimitive ?: throw MalformedJsonException("Expected number at position $index")
    }

    private fun skipBlank() {
        while (index < json.length && json[index] in " \n\r\t") index++
    }
}

private class PartialJsonException(
    message: String,
) : RuntimeException(message)

private class MalformedJsonException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
