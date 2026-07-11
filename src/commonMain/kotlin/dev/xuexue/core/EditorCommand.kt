package dev.xuexue.core

data class TextSelection(val start: Int, val end: Int) {
    fun normalized(length: Int): TextSelection {
        val first = minOf(start, end).coerceIn(0, length)
        val last = maxOf(start, end).coerceIn(0, length)
        return TextSelection(first, last)
    }
}

data class EditResult(
    val text: String,
    val selection: TextSelection,
)

enum class EditorCommand {
    BOLD,
    ITALIC,
    INLINE_CODE,
    LINK,
    HEADING_1,
    HEADING_2,
    BULLET_LIST,
    NUMBERED_LIST,
    QUOTE,
    CODE_BLOCK,
    MATH_BLOCK,
}

object EditorCommands {
    fun apply(command: EditorCommand, source: String, selection: TextSelection): EditResult {
        val range = selection.normalized(source.length)
        return when (command) {
            EditorCommand.BOLD -> wrap(source, range, "**", "**", "bold")
            EditorCommand.ITALIC -> wrap(source, range, "*", "*", "italic")
            EditorCommand.INLINE_CODE -> wrap(source, range, "`", "`", "code")
            EditorCommand.LINK -> wrap(source, range, "[", "](url)", "label")
            EditorCommand.CODE_BLOCK -> wrap(source, range, "```\n", "\n```", "code")
            EditorCommand.MATH_BLOCK -> wrap(source, range, "$$\n", "\n$$", "formula")
            EditorCommand.HEADING_1 -> prefixLines(source, range, "# ")
            EditorCommand.HEADING_2 -> prefixLines(source, range, "## ")
            EditorCommand.BULLET_LIST -> prefixLines(source, range, "- ")
            EditorCommand.NUMBERED_LIST -> prefixNumberedLines(source, range)
            EditorCommand.QUOTE -> prefixLines(source, range, "> ")
        }
    }

    private fun wrap(
        source: String,
        selection: TextSelection,
        prefix: String,
        suffix: String,
        placeholder: String,
    ): EditResult {
        val selected = source.substring(selection.start, selection.end)
        val body = selected.ifEmpty { placeholder }
        val text = source.replaceRange(selection.start, selection.end, prefix + body + suffix)
        val bodyStart = selection.start + prefix.length
        return EditResult(text, TextSelection(bodyStart, bodyStart + body.length))
    }

    private fun prefixLines(source: String, selection: TextSelection, prefix: String): EditResult {
        val lineStart = source.lastIndexOf('\n', (selection.start - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val lineEnd = source.indexOf('\n', selection.end).let { if (it < 0) source.length else it }
        val original = source.substring(lineStart, lineEnd)
        val replacement = original.lineSequence().joinToString("\n") { prefix + it }
        return EditResult(
            text = source.replaceRange(lineStart, lineEnd, replacement),
            selection = TextSelection(lineStart + prefix.length, lineStart + replacement.length),
        )
    }

    private fun prefixNumberedLines(source: String, selection: TextSelection): EditResult {
        val lineStart = source.lastIndexOf('\n', (selection.start - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val lineEnd = source.indexOf('\n', selection.end).let { if (it < 0) source.length else it }
        val original = source.substring(lineStart, lineEnd)
        val replacement = original.lines().mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
        return EditResult(
            text = source.replaceRange(lineStart, lineEnd, replacement),
            selection = TextSelection(lineStart + 3, lineStart + replacement.length),
        )
    }
}

