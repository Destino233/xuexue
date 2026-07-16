package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private val validJsonEscapes = setOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u')

fun repairJson(json: String): String {
    val repaired = StringBuilder(json.length)
    var inString = false
    var index = 0
    while (index < json.length) {
        val character = json[index]
        if (!inString) {
            repaired.append(character)
            if (character == '"') inString = true
            index++
            continue
        }

        when {
            character == '"' -> {
                repaired.append(character)
                inString = false
            }

            character == '\\' -> {
                index += repairEscape(json, index, repaired)
            }

            character.code in 0x00..0x1F -> {
                repaired.append(escapeControlCharacter(character))
            }

            else -> {
                repaired.append(character)
            }
        }
        index++
    }
    return repaired.toString()
}

fun parseJsonWithRepair(json: String): JsonElement =
    try {
        parseStrictJson(json)
    } catch (original: IllegalArgumentException) {
        val repaired = repairJson(json)
        if (repaired == json) throw original
        parseStrictJson(repaired)
    }

internal fun parseStrictJson(json: String): JsonElement = PiJson.parseToJsonElement(json).also(::requireStrictLiterals)

private fun repairEscape(
    json: String,
    index: Int,
    repaired: StringBuilder,
): Int {
    val nextCharacter = json.getOrNull(index + 1)
    val unicodeDigits =
        if (nextCharacter == 'u') json.substring(index + 2, minOf(index + 6, json.length)) else ""
    return when {
        nextCharacter == null -> {
            repaired.append("\\\\")
            0
        }

        nextCharacter == 'u' && unicodeDigits.length == 4 && unicodeDigits.all(Char::isHexDigit) -> {
            repaired.append("\\u").append(unicodeDigits)
            5
        }

        nextCharacter in validJsonEscapes -> {
            repaired.append('\\').append(nextCharacter)
            1
        }

        else -> {
            repaired.append("\\\\")
            0
        }
    }
}

private fun escapeControlCharacter(character: Char): String =
    when (character) {
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> "\\u${character.code.toString(16).padStart(4, '0')}"
    }

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun requireStrictLiterals(element: JsonElement) {
    when (element) {
        is JsonNull -> {
            return
        }

        is JsonArray -> {
            element.forEach(::requireStrictLiterals)
        }

        is JsonObject -> {
            element.values.forEach(::requireStrictLiterals)
        }

        is JsonPrimitive -> {
            val validLiteral =
                element.isString || element.content.toBooleanStrictOrNull() != null || element.doubleOrNull != null
            require(validLiteral) { "Invalid JSON literal: ${element.content}" }
        }
    }
}
