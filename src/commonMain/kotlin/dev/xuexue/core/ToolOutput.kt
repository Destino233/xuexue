package dev.xuexue.core

const val DEFAULT_TOOL_MAX_LINES: Int = 2_000
const val DEFAULT_TOOL_MAX_BYTES: Int = 50 * 1024

data class ToolTruncationResult(
    val content: String,
    val truncated: Boolean,
    val truncatedBy: ToolTruncatedBy?,
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    val lastLinePartial: Boolean = false,
    val firstLineExceedsLimit: Boolean = false,
    val maxLines: Int,
    val maxBytes: Int,
)

enum class ToolTruncatedBy {
    LINES,
    BYTES,
}

fun truncateToolHead(
    content: String,
    maxLines: Int = DEFAULT_TOOL_MAX_LINES,
    maxBytes: Int = DEFAULT_TOOL_MAX_BYTES,
): ToolTruncationResult {
    require(maxLines >= 0) { "maxLines must not be negative" }
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val lines = splitToolLines(content)
    val totalBytes = content.encodeToByteArray().size
    val result =
        when {
            lines.size <= maxLines && totalBytes <= maxBytes -> {
                ToolTruncationResult(
                    content = content,
                    truncated = false,
                    truncatedBy = null,
                    totalLines = lines.size,
                    totalBytes = totalBytes,
                    outputLines = lines.size,
                    outputBytes = totalBytes,
                    maxLines = maxLines,
                    maxBytes = maxBytes,
                )
            }

            lines.firstOrNull()?.encodeToByteArray()?.size ?: 0 > maxBytes -> {
                ToolTruncationResult(
                    content = "",
                    truncated = true,
                    truncatedBy = ToolTruncatedBy.BYTES,
                    totalLines = lines.size,
                    totalBytes = totalBytes,
                    outputLines = 0,
                    outputBytes = 0,
                    firstLineExceedsLimit = true,
                    maxLines = maxLines,
                    maxBytes = maxBytes,
                )
            }

            else -> {
                val output = mutableListOf<String>()
                var outputBytes = 0
                var truncatedBy = ToolTruncatedBy.LINES
                for (index in 0 until minOf(lines.size, maxLines)) {
                    val line = lines[index]
                    val lineBytes = line.encodeToByteArray().size + if (index > 0) 1 else 0
                    if (outputBytes + lineBytes > maxBytes) {
                        truncatedBy = ToolTruncatedBy.BYTES
                        break
                    }
                    output += line
                    outputBytes += lineBytes
                }
                if (output.size >= maxLines && outputBytes <= maxBytes) truncatedBy = ToolTruncatedBy.LINES
                val truncatedContent = output.joinToString("\n")
                ToolTruncationResult(
                    content = truncatedContent,
                    truncated = true,
                    truncatedBy = truncatedBy,
                    totalLines = lines.size,
                    totalBytes = totalBytes,
                    outputLines = output.size,
                    outputBytes = truncatedContent.encodeToByteArray().size,
                    maxLines = maxLines,
                    maxBytes = maxBytes,
                )
            }
        }
    return result
}

fun truncateToolTail(
    content: String,
    maxLines: Int = DEFAULT_TOOL_MAX_LINES,
    maxBytes: Int = DEFAULT_TOOL_MAX_BYTES,
): ToolTruncationResult {
    require(maxLines >= 0) { "maxLines must not be negative" }
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val lines = splitToolLines(content)
    val totalBytes = content.encodeToByteArray().size
    if (lines.size <= maxLines && totalBytes <= maxBytes) {
        return ToolTruncationResult(
            content = content,
            truncated = false,
            truncatedBy = null,
            totalLines = lines.size,
            totalBytes = totalBytes,
            outputLines = lines.size,
            outputBytes = totalBytes,
            maxLines = maxLines,
            maxBytes = maxBytes,
        )
    }
    val output = ArrayDeque<String>()
    var outputBytes = 0
    var truncatedBy = ToolTruncatedBy.LINES
    var index = lines.lastIndex
    while (index >= 0 && output.size < maxLines && truncatedBy == ToolTruncatedBy.LINES) {
        val line = lines[index]
        val lineBytes = line.encodeToByteArray().size + if (output.isNotEmpty()) 1 else 0
        if (outputBytes + lineBytes > maxBytes) {
            truncatedBy = ToolTruncatedBy.BYTES
            if (output.isEmpty() && maxBytes > 0) {
                val partial = truncateUtf8FromEnd(line, maxBytes)
                output.addFirst(partial)
            }
        } else {
            output.addFirst(line)
            outputBytes += lineBytes
        }
        index--
    }
    if (output.size >= maxLines && outputBytes <= maxBytes) truncatedBy = ToolTruncatedBy.LINES
    val result = output.joinToString("\n")
    return ToolTruncationResult(
        content = result,
        truncated = true,
        truncatedBy = truncatedBy,
        totalLines = lines.size,
        totalBytes = totalBytes,
        outputLines = output.size,
        outputBytes = result.encodeToByteArray().size,
        lastLinePartial = truncatedBy == ToolTruncatedBy.BYTES && output.size == 1 && lines.lastOrNull() != result,
        maxLines = maxLines,
        maxBytes = maxBytes,
    )
}

private fun splitToolLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split('\n').toMutableList()
    if (content.endsWith('\n')) lines.removeLast()
    return lines
}

private fun truncateUtf8FromEnd(
    content: String,
    maxBytes: Int,
): String {
    val bytes = content.encodeToByteArray()
    if (bytes.size <= maxBytes) return content
    var start = bytes.size - maxBytes
    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
    return bytes.copyOfRange(start, bytes.size).decodeToString()
}
