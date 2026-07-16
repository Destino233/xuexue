package dev.xuexue.pi.coding.resources

data class ParsedFrontmatter(
    val fields: Map<String, FrontmatterValue>,
    val body: String,
)

sealed interface FrontmatterValue {
    data class Text(
        val value: String,
    ) : FrontmatterValue

    data class BooleanValue(
        val value: Boolean,
    ) : FrontmatterValue
}

/**
 * Parses the scalar frontmatter Pi consumes for skills and prompt templates. Unsupported YAML constructs fail
 * explicitly so the loader can surface a diagnostic instead of silently producing an invalid resource.
 */
fun parseFrontmatter(content: String): ParsedFrontmatter {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val extracted = extractFrontmatter(normalized)
    val fields = extracted.yaml?.let(::parseFrontmatterFields).orEmpty()
    return ParsedFrontmatter(fields, extracted.body)
}

fun stripFrontmatter(content: String): String = parseFrontmatter(content).body

private fun parseFrontmatterFields(yaml: String): Map<String, FrontmatterValue> {
    val fields = linkedMapOf<String, FrontmatterValue>()
    val lines = yaml.lines()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith('#')) {
            index++
        } else {
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid frontmatter YAML at line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(key.isNotEmpty()) { "Invalid frontmatter YAML at line ${index + 1}" }
            if (value == "|" || value == ">") {
                val scalar = parseBlockScalar(lines, index, rawLine.leadingIndentation(), value == ">")
                fields[key] = FrontmatterValue.Text(scalar.value)
                index = scalar.lastLineIndex + 1
            } else {
                require(value.isBalancedYamlScalar()) { "Invalid frontmatter YAML at line ${index + 1}" }
                fields[key] = value.toFrontmatterValue()
                index++
            }
        }
    }
    return fields
}

private fun parseBlockScalar(
    lines: List<String>,
    headerIndex: Int,
    headerIndentation: Int,
    foldLines: Boolean,
): BlockScalar {
    val content = mutableListOf<String>()
    var index = headerIndex + 1
    while (index < lines.size) {
        val line = lines[index]
        if (line.isNotBlank() && line.leadingIndentation() <= headerIndentation) break
        content += if (line.isBlank()) "" else line.drop(minOf(line.leadingIndentation(), headerIndentation + 2))
        index++
    }
    val value = if (foldLines) content.joinToString(" ") else content.joinToString("\n")
    return BlockScalar(value.trim(), index - 1)
}

private fun extractFrontmatter(content: String): ExtractedFrontmatter {
    if (!content.startsWith("---")) return ExtractedFrontmatter(null, content)
    val endIndex = content.indexOf("\n---", startIndex = 3)
    return if (endIndex == -1) {
        ExtractedFrontmatter(null, content)
    } else {
        ExtractedFrontmatter(content.substring(4, endIndex), content.substring(endIndex + 4).trim())
    }
}

private fun String.toFrontmatterValue(): FrontmatterValue =
    when (this) {
        "true" -> FrontmatterValue.BooleanValue(true)
        "false" -> FrontmatterValue.BooleanValue(false)
        else -> FrontmatterValue.Text(removeSurrounding("\"").removeSurrounding("'"))
    }

private fun String.leadingIndentation(): Int = indexOfFirst { !it.isWhitespace() }.let { if (it == -1) length else it }

private fun String.isBalancedYamlScalar(): Boolean =
    (startsWith("[") && endsWith("]")) ||
        (startsWith("{") && endsWith("}")) ||
        (!startsWith("[") && !startsWith("{"))

internal fun Map<String, FrontmatterValue>.string(name: String): String? = (this[name] as? FrontmatterValue.Text)?.value

internal fun Map<String, FrontmatterValue>.boolean(name: String): Boolean? {
    val value = this[name] as? FrontmatterValue.BooleanValue
    return value?.value
}

private data class ExtractedFrontmatter(
    val yaml: String?,
    val body: String,
)

private data class BlockScalar(
    val value: String,
    val lastLineIndex: Int,
)
