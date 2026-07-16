package dev.xuexue.pi.ai

fun sanitizeSurrogates(text: String): String {
    val sanitized = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val current = text[index]
        when {
            current.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                sanitized.append(current)
                sanitized.append(text[index + 1])
                index += 2
            }

            current.isHighSurrogate() || current.isLowSurrogate() -> {
                index++
            }

            else -> {
                sanitized.append(current)
                index++
            }
        }
    }
    return sanitized.toString()
}

private fun Char.isHighSurrogate(): Boolean = code in 0xD800..0xDBFF

private fun Char.isLowSurrogate(): Boolean = code in 0xDC00..0xDFFF
