@file:Suppress("TooManyFunctions")

package dev.xuexue.platform

import java.text.Normalizer

internal data class PiEditReplacement(
    val oldText: String,
    val newText: String,
)

internal data class PiEditApplication(
    val baseContent: String,
    val newContent: String,
)

internal data class PiDiffResult(
    val diff: String,
    val firstChangedLine: Int?,
)

internal fun stripPiBom(content: String): Pair<String, String> =
    if (content.startsWith('\uFEFF')) {
        "\uFEFF" to content.substring(1)
    } else {
        "" to content
    }

internal fun detectPiLineEnding(content: String): String {
    val crlf = content.indexOf("\r\n")
    val lf = content.indexOf('\n')
    return if (lf >= 0 && (crlf >= 0 && crlf < lf)) "\r\n" else "\n"
}

internal fun normalizePiLineEndings(content: String): String = content.replace("\r\n", "\n").replace('\r', '\n')

internal fun restorePiLineEndings(
    content: String,
    ending: String,
): String = if (ending == "\r\n") content.replace("\n", "\r\n") else content

internal fun applyPiEdits(
    content: String,
    edits: List<PiEditReplacement>,
    path: String,
): PiEditApplication {
    require(edits.isNotEmpty()) { "Edit tool input is invalid. edits must contain at least one replacement." }
    val normalizedEdits =
        edits.map { edit ->
            PiEditReplacement(normalizePiLineEndings(edit.oldText), normalizePiLineEndings(edit.newText))
        }
    normalizedEdits.forEachIndexed { index, edit ->
        require(edit.oldText.isNotEmpty()) {
            emptyOldTextError(path, index, normalizedEdits.size).message.orEmpty()
        }
    }

    val initialMatches = normalizedEdits.map { fuzzyFindPiText(content, it.oldText) }
    val useFuzzy = initialMatches.any { it.usedFuzzyMatch }
    val replacementBase = if (useFuzzy) normalizePiFuzzy(content) else content
    val matches =
        normalizedEdits
            .mapIndexed { index, edit ->
                val match = fuzzyFindPiText(replacementBase, edit.oldText)
                require(match.found) { notFoundError(path, index, normalizedEdits.size).message.orEmpty() }
                val occurrences = countPiOccurrences(replacementBase, edit.oldText)
                require(occurrences <= 1) {
                    duplicateError(path, index, normalizedEdits.size, occurrences).message.orEmpty()
                }
                MatchedPiEdit(index, match.index, match.length, edit.newText)
            }.sortedBy { it.index }
    matches.zipWithNext().forEach { (previous, current) ->
        require(previous.index + previous.length <= current.index) {
            "edits[${previous.editIndex}] and edits[${current.editIndex}] overlap in $path. " +
                "Merge them into one edit or target disjoint regions."
        }
    }

    val newContent =
        if (useFuzzy) {
            applyPiReplacementsPreservingLines(content, replacementBase, matches)
        } else {
            applyPiReplacements(replacementBase, matches)
        }
    require(content != newContent) { noChangeError(path, normalizedEdits.size).message.orEmpty() }
    return PiEditApplication(content, newContent)
}

private data class PiTextMatch(
    val found: Boolean,
    val index: Int,
    val length: Int,
    val usedFuzzyMatch: Boolean,
)

private data class MatchedPiEdit(
    val editIndex: Int,
    val index: Int,
    val length: Int,
    val newText: String,
)

private fun fuzzyFindPiText(
    content: String,
    oldText: String,
): PiTextMatch {
    val exact = content.indexOf(oldText)
    if (exact >= 0) return PiTextMatch(true, exact, oldText.length, false)
    val fuzzyContent = normalizePiFuzzy(content)
    val fuzzyOld = normalizePiFuzzy(oldText)
    val fuzzyIndex = fuzzyContent.indexOf(fuzzyOld)
    return if (fuzzyIndex >= 0) {
        PiTextMatch(true, fuzzyIndex, fuzzyOld.length, true)
    } else {
        PiTextMatch(false, -1, 0, false)
    }
}

private fun normalizePiFuzzy(content: String): String =
    Normalizer
        .normalize(content, Normalizer.Form.NFKC)
        .split('\n')
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("[\u2018\u2019\u201A\u201B]"), "'")
        .replace(Regex("[\u201C\u201D\u201E\u201F]"), "\"")
        .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), "-")
        .replace(Regex("[\u00A0\u2002-\u200A\u202F\u205F\u3000]"), " ")

private fun countPiOccurrences(
    content: String,
    target: String,
): Int {
    if (target.isEmpty()) return 0
    var count = 0
    var offset = 0
    while (true) {
        val index = content.indexOf(target, offset)
        if (index < 0) return count
        count++
        offset = index + target.length
    }
}

private data class PiLineSpan(
    val start: Int,
    val end: Int,
)

private fun piLinesWithEndings(content: String): List<String> =
    Regex("[^\\n]*\\n|[^\\n]+")
        .findAll(content)
        .map(MatchResult::value)
        .toList()

private fun piLineSpans(content: String): List<PiLineSpan> {
    var offset = 0
    return piLinesWithEndings(content).map { line ->
        PiLineSpan(offset, offset + line.length).also { offset += line.length }
    }
}

private fun applyPiReplacements(
    content: String,
    replacements: List<MatchedPiEdit>,
): String {
    var result = content
    replacements.sortedByDescending { it.index }.forEach { replacement ->
        result =
            result.replaceRange(
                replacement.index,
                replacement.index + replacement.length,
                replacement.newText,
            )
    }
    return result
}

private fun applyPiReplacementsPreservingLines(
    originalContent: String,
    baseContent: String,
    replacements: List<MatchedPiEdit>,
): String {
    val originalLines = piLinesWithEndings(originalContent)
    val baseLines = piLineSpans(baseContent)
    require(originalLines.size == baseLines.size) { "Cannot preserve unchanged lines after fuzzy matching." }
    val groups = mutableListOf<PiReplacementGroup>()
    replacements.sortedBy { it.index }.forEach { replacement ->
        val range = piReplacementLineRange(baseLines, replacement)
        val current = groups.lastOrNull()
        if (current != null && range.first < current.endLine) {
            current.endLine = maxOf(current.endLine, range.second)
            current.replacements += replacement
        } else {
            groups += PiReplacementGroup(range.first, range.second, mutableListOf(replacement))
        }
    }
    var originalLineIndex = 0
    val result = StringBuilder()
    groups.forEach { group ->
        result.append(originalLines.subList(originalLineIndex, group.startLine).joinToString(""))
        val startOffset = baseLines[group.startLine].start
        val endOffset = baseLines[group.endLine - 1].end
        result.append(
            applyPiReplacements(
                baseContent.substring(startOffset, endOffset),
                group.replacements.map { it.copy(index = it.index - startOffset) },
            ),
        )
        originalLineIndex = group.endLine
    }
    result.append(originalLines.drop(originalLineIndex).joinToString(""))
    return result.toString()
}

private data class PiReplacementGroup(
    val startLine: Int,
    var endLine: Int,
    val replacements: MutableList<MatchedPiEdit>,
)

private fun piReplacementLineRange(
    lines: List<PiLineSpan>,
    replacement: MatchedPiEdit,
): Pair<Int, Int> {
    val end = replacement.index + replacement.length
    val startLine = lines.indexOfFirst { replacement.index >= it.start && replacement.index < it.end }
    require(startLine >= 0) { "Replacement range is outside the base content." }
    var endLine = startLine
    while (endLine < lines.size && lines[endLine].end < end) endLine++
    require(endLine < lines.size) { "Replacement range is outside the base content." }
    return startLine to endLine + 1
}

private fun emptyOldTextError(
    path: String,
    index: Int,
    total: Int,
): IllegalArgumentException =
    IllegalArgumentException(
        if (total == 1) {
            "oldText must not be empty in $path."
        } else {
            "edits[$index].oldText must not be empty in $path."
        },
    )

private fun notFoundError(
    path: String,
    index: Int,
    total: Int,
): IllegalArgumentException =
    IllegalArgumentException(
        if (total == 1) {
            "Could not find the exact text in $path. The old text must match exactly including all whitespace and newlines."
        } else {
            "Could not find edits[$index] in $path. The oldText must match exactly including all whitespace and newlines."
        },
    )

private fun duplicateError(
    path: String,
    index: Int,
    total: Int,
    occurrences: Int,
): IllegalArgumentException =
    IllegalArgumentException(
        if (total == 1) {
            "Found $occurrences occurrences of the text in $path. The text must be unique. Please provide more context to make it unique."
        } else {
            "Found $occurrences occurrences of edits[$index] in $path. Each oldText must be unique. Please provide more context to make it unique."
        },
    )

private fun noChangeError(
    path: String,
    total: Int,
): IllegalArgumentException =
    IllegalArgumentException(
        if (total == 1) {
            "No changes made to $path. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected."
        } else {
            "No changes made to $path. The replacements produced identical content."
        },
    )

private sealed interface PiLineOperation {
    data class Equal(
        val line: String,
    ) : PiLineOperation

    data class Add(
        val line: String,
    ) : PiLineOperation

    data class Remove(
        val line: String,
    ) : PiLineOperation
}

internal fun generatePiDiff(
    oldContent: String,
    newContent: String,
    contextLines: Int = 4,
): PiDiffResult {
    val operations = diffPiLines(oldContent, newContent)
    val hunks = piDiffHunks(operations, contextLines)
    if (hunks.isEmpty()) return PiDiffResult("", null)
    val oldLines = oldContent.split('\n')
    val newLines = newContent.split('\n')
    val width = maxOf(oldLines.size, newLines.size).toString().length
    var firstChangedLine: Int? = null
    val output =
        buildString {
            hunks.forEachIndexed { hunkIndex, range ->
                if (hunkIndex > 0) append(" ").append(" ".repeat(width)).append(" ...\n")
                var oldLine = range.oldStart
                var newLine = range.newStart
                range.operations.forEach { operation ->
                    when (operation) {
                        is PiLineOperation.Equal -> {
                            append(" ")
                                .append(oldLine.toString().padStart(width))
                                .append(" ")
                                .append(operation.line)
                                .append('\n')
                            oldLine++
                            newLine++
                        }

                        is PiLineOperation.Remove -> {
                            append('-')
                                .append(oldLine.toString().padStart(width))
                                .append(" ")
                                .append(operation.line)
                                .append('\n')
                            oldLine++
                        }

                        is PiLineOperation.Add -> {
                            if (firstChangedLine == null) firstChangedLine = newLine
                            append('+')
                                .append(newLine.toString().padStart(width))
                                .append(" ")
                                .append(operation.line)
                                .append('\n')
                            newLine++
                        }
                    }
                }
            }
        }.trimEnd()
    return PiDiffResult(output, firstChangedLine)
}

internal fun generatePiUnifiedPatch(
    path: String,
    oldContent: String,
    newContent: String,
    contextLines: Int = 4,
): String {
    val operations = diffPiLines(oldContent, newContent)
    val hunks = piDiffHunks(operations, contextLines)
    if (hunks.isEmpty()) return "--- $path\n+++ $path\n"
    return buildString {
        append("--- ").append(path).append('\n')
        append("+++ ").append(path).append('\n')
        hunks.forEach { hunk ->
            val oldCount = hunk.operations.count { it !is PiLineOperation.Add }
            val newCount = hunk.operations.count { it !is PiLineOperation.Remove }
            append("@@ -${hunk.oldStart},$oldCount +${hunk.newStart},$newCount @@\n")
            hunk.operations.forEach { operation ->
                when (operation) {
                    is PiLineOperation.Equal -> append(' ').append(operation.line).append('\n')
                    is PiLineOperation.Remove -> append('-').append(operation.line).append('\n')
                    is PiLineOperation.Add -> append('+').append(operation.line).append('\n')
                }
            }
        }
    }.trimEnd()
}

private data class PiDiffHunk(
    val oldStart: Int,
    val newStart: Int,
    val operations: List<PiLineOperation>,
)

private fun piDiffHunks(
    operations: List<PiLineOperation>,
    context: Int,
): List<PiDiffHunk> {
    val changes = operations.indices.filter { operations[it] !is PiLineOperation.Equal }
    if (changes.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = maxOf(0, changes.first() - context)
    var end = minOf(operations.lastIndex, changes.first() + context)
    changes.drop(1).forEach { change ->
        if (change - end <= context * 2 + 1) {
            end = minOf(operations.lastIndex, change + context)
        } else {
            ranges += start..end
            start = maxOf(0, change - context)
            end = minOf(operations.lastIndex, change + context)
        }
    }
    ranges += start..end
    return ranges.map { range ->
        var oldLine = 1
        var newLine = 1
        operations.take(range.first).forEach { operation ->
            if (operation !is PiLineOperation.Add) oldLine++
            if (operation !is PiLineOperation.Remove) newLine++
        }
        PiDiffHunk(oldLine, newLine, operations.slice(range))
    }
}

/** LCS is intentionally isolated here to preserve Pi's context and hunk behavior. */
@Suppress("CyclomaticComplexMethod")
private fun diffPiLines(
    oldContent: String,
    newContent: String,
): List<PiLineOperation> {
    val oldLines = oldContent.split('\n')
    val newLines = newContent.split('\n')
    val product = oldLines.size.toLong() * newLines.size.toLong()
    if (product > 1_000_000L) {
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }
        return buildList {
            oldLines.take(prefix).forEach { add(PiLineOperation.Equal(it)) }
            oldLines
                .subList(prefix, oldLines.size - suffix)
                .forEach { add(PiLineOperation.Remove(it)) }
            newLines.subList(prefix, newLines.size - suffix).forEach { add(PiLineOperation.Add(it)) }
            newLines.takeLast(suffix).forEach { add(PiLineOperation.Equal(it)) }
        }
    }
    val columns = newLines.size + 1
    val table = IntArray((oldLines.size + 1) * columns)
    for (oldIndex in oldLines.lastIndex downTo 0) {
        for (newIndex in newLines.lastIndex downTo 0) {
            table[oldIndex * columns + newIndex] =
                if (oldLines[oldIndex] == newLines[newIndex]) {
                    table[(oldIndex + 1) * columns + newIndex + 1] + 1
                } else {
                    maxOf(
                        table[(oldIndex + 1) * columns + newIndex],
                        table[oldIndex * columns + newIndex + 1],
                    )
                }
        }
    }
    val result = mutableListOf<PiLineOperation>()
    var oldIndex = 0
    var newIndex = 0
    while (oldIndex < oldLines.size || newIndex < newLines.size) {
        val shouldAdd = shouldAddPiLine(oldIndex, newIndex, oldLines, newLines, table, columns)
        when {
            oldIndex < oldLines.size && newIndex < newLines.size && oldLines[oldIndex] == newLines[newIndex] -> {
                result += PiLineOperation.Equal(oldLines[oldIndex])
                oldIndex++
                newIndex++
            }

            shouldAdd -> {
                result += PiLineOperation.Add(newLines[newIndex++])
            }

            else -> {
                result += PiLineOperation.Remove(oldLines[oldIndex++])
            }
        }
    }
    return result
}

private fun shouldAddPiLine(
    oldIndex: Int,
    newIndex: Int,
    oldLines: List<String>,
    newLines: List<String>,
    table: IntArray,
    columns: Int,
): Boolean =
    when {
        newIndex >= newLines.size -> false
        oldIndex == oldLines.size -> true
        else -> table[oldIndex * columns + newIndex + 1] >= table[(oldIndex + 1) * columns + newIndex]
    }
