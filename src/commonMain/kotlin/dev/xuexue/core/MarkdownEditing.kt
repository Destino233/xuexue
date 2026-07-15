package dev.xuexue.core

data class MarkdownEditNode(
    val start: Int,
    val end: Int,
    val type: String,
    val source: String,
    val prefix: String = "",
    val suffix: String = "",
) {
    val body: String get() = source.removePrefix(prefix).removeSuffix(suffix)
}

object MarkdownEditing {
    fun parseNodes(source: String): List<MarkdownEditNode> {
        if (source.isEmpty()) return listOf(MarkdownEditNode(0, 0, "paragraph", ""))
        val lines = sourceLines(source)
        val nodes = mutableListOf<MarkdownEditNode>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.text.trimStart()
            if (trimmed.isBlank()) {
                val endIndex = consecutiveBlankEndIndex(lines, index)
                if (nodes.isEmpty()) {
                    val end = lines[endIndex].end
                    nodes += MarkdownEditNode(line.start, end, "paragraph", source.substring(line.start, end))
                }
                index = endIndex + 1
                continue
            }

            val delimited = parseDelimitedNode(source, lines, index, trimmed)
            if (delimited != null) {
                nodes += delimited.node
                index = delimited.nextIndex
                continue
            }

            val type = lineType(trimmed, lines, index)
            val parsed = parseStandardNode(source, lines, index, trimmed, type)
            nodes += parsed.node
            index = parsed.nextIndex
        }
        return nodes
    }

    fun replaceNodeBody(
        source: String,
        start: Int,
        prefix: String,
        suffix: String,
        currentBody: String,
        replacementBody: String,
    ): String? {
        val expected = prefix + currentBody + suffix
        val end = start + expected.length
        if (start !in 0..source.length || end !in start..source.length) return null
        if (!source.regionMatches(start, expected, 0, expected.length)) return null
        return source.replaceRange(start, end, prefix + replacementBody + suffix)
    }

    private data class SourceLine(
        val start: Int,
        val end: Int,
        val text: String,
    )

    private data class NodeParseResult(
        val node: MarkdownEditNode,
        val nextIndex: Int,
    )

    private fun sourceLines(source: String): List<SourceLine> {
        val lines = mutableListOf<SourceLine>()
        var start = 0
        while (start < source.length) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline + 1
            val text = source.substring(start, end).removeSuffix("\n").removeSuffix("\r")
            lines += SourceLine(start, end, text)
            start = end
        }
        return lines
    }

    private fun parseDelimitedNode(
        source: String,
        lines: List<SourceLine>,
        index: Int,
        trimmed: String,
    ): NodeParseResult? {
        val (contentEndIndex, type) =
            when {
                index == 0 && trimmed == "---" -> {
                    findClosingLine(lines, index, index) { it.trim() == "---" } to "frontmatter"
                }

                trimmed.startsWith("```") -> {
                    val type = if (trimmed.removePrefix("```").trim().equals("mermaid", true)) "mermaid" else "code"
                    findClosingLine(lines, index, lines.lastIndex) { it.trimStart().startsWith("```") } to type
                }

                trimmed == "$$" -> {
                    findClosingLine(lines, index, lines.lastIndex) { it.trim() == "$$" } to "math"
                }

                else -> {
                    return null
                }
            }
        val endIndex = trailingBlankEndIndex(lines, contentEndIndex)
        return NodeParseResult(
            delimitedNode(source, lines, index, contentEndIndex, endIndex, type),
            endIndex + 1,
        )
    }

    private fun findClosingLine(
        lines: List<SourceLine>,
        startIndex: Int,
        fallbackIndex: Int,
        matches: (String) -> Boolean,
    ): Int = ((startIndex + 1) until lines.size).firstOrNull { matches(lines[it].text) } ?: fallbackIndex

    private fun parseStandardNode(
        source: String,
        lines: List<SourceLine>,
        index: Int,
        trimmed: String,
        type: String,
    ): NodeParseResult {
        val contentEndIndex = sameTypeEndIndex(lines, index, type)
        val endIndex = trailingBlankEndIndex(lines, contentEndIndex)
        val start = lines[index].start
        val end = lines[endIndex].end
        val raw = source.substring(start, end)
        val node =
            when (type) {
                "heading" -> {
                    val marker = trimmed.takeWhile { it == '#' } + " "
                    val leading = lines[index].text.substringBefore(trimmed) + marker
                    val suffix =
                        when {
                            raw.endsWith("\r\n") -> "\r\n"
                            raw.endsWith('\n') -> "\n"
                            else -> ""
                        }
                    MarkdownEditNode(start, end, type, raw, leading, suffix)
                }

                "callout" -> {
                    MarkdownEditNode(start, end, type, raw, source.substring(start, lines[index].end), "")
                }

                else -> {
                    MarkdownEditNode(start, end, type, raw)
                }
            }
        return NodeParseResult(node, endIndex + 1)
    }

    private fun sameTypeEndIndex(
        lines: List<SourceLine>,
        startIndex: Int,
        type: String,
    ): Int {
        if (type == "heading" || type == "divider") return startIndex
        var endIndex = startIndex
        while (
            endIndex + 1 < lines.size &&
            lines[endIndex + 1].text.isNotBlank() &&
            lineType(lines[endIndex + 1].text.trimStart(), lines, endIndex + 1) == type
        ) {
            endIndex++
        }
        return endIndex
    }

    private fun delimitedNode(
        source: String,
        lines: List<SourceLine>,
        startIndex: Int,
        contentEndIndex: Int,
        endIndex: Int,
        type: String,
    ): MarkdownEditNode {
        val start = lines[startIndex].start
        val end = lines[endIndex].end
        val prefix = source.substring(start, lines[startIndex].end)
        val suffix = if (contentEndIndex > startIndex) source.substring(lines[contentEndIndex].start, end) else ""
        return MarkdownEditNode(start, end, type, source.substring(start, end), prefix, suffix)
    }

    private fun consecutiveBlankEndIndex(
        lines: List<SourceLine>,
        startIndex: Int,
    ): Int {
        var endIndex = startIndex
        while (endIndex + 1 < lines.size && lines[endIndex + 1].text.isBlank()) endIndex++
        return endIndex
    }

    private fun trailingBlankEndIndex(
        lines: List<SourceLine>,
        contentEndIndex: Int,
    ): Int {
        val firstBlank = contentEndIndex + 1
        return if (firstBlank < lines.size && lines[firstBlank].text.isBlank()) {
            consecutiveBlankEndIndex(lines, firstBlank)
        } else {
            contentEndIndex
        }
    }

    private fun lineType(
        trimmed: String,
        lines: List<SourceLine>,
        index: Int,
    ): String =
        when {
            Regex("^#{1,6}\\s+").containsMatchIn(trimmed) -> "heading"
            trimmed.startsWith("> [!") -> "callout"
            trimmed.startsWith(">") -> "quote"
            Regex("^(?:[-*+] |\\d+[.)] )").containsMatchIn(trimmed) -> "list"
            trimmed in setOf("---", "***", "___") -> "divider"
            trimmed.contains('|') && index + 1 < lines.size && lines[index + 1].text.contains('|') -> "table"
            trimmed.startsWith(":::") || trimmed.startsWith("<") -> "extension"
            else -> "paragraph"
        }
}
