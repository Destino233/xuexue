@file:Suppress("TooManyFunctions")

package dev.xuexue.pi.coding

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Truncation reason exposed by the headless Pi bash result. */
enum class PiBashTruncatedBy {
    LINES,
    BYTES,
}

/** Snapshot of bounded streaming bash output and its optional full-output file. */
data class PiBashOutputSnapshot(
    val content: String,
    val truncated: Boolean,
    val truncatedBy: PiBashTruncatedBy?,
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    val lastLinePartial: Boolean,
    val maxLines: Int,
    val maxBytes: Int,
    val fullOutputPath: String?,
)

/**
 * Incrementally tracks process output with Pi's bounded tail and full-output semantics.
 *
 * The accumulator keeps at most a small rolling text window in memory, decodes UTF-8 across
 * arbitrary process chunks, and lazily creates a temp file once a limit is crossed or a
 * truncated snapshot explicitly asks for persistence.
 */
class PiBashOutputAccumulator(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val tempFilePrefix: String = "pi-output",
) {
    private val maxRollingBytes = (maxBytes * 2).coerceAtLeast(1)
    private val retainedChunks = mutableListOf<ByteArray>()
    private var outputStream: OutputStream? = null
    private var tempFilePath: Path? = null
    private var pendingUtf8 = ByteArray(0)
    private var tailText = ""
    private var tailBytes = 0
    private var tailStartsAtLineBoundary = true
    private var totalRawBytes = 0L
    private var totalDecodedBytes = 0L
    private var completedLines = 0L
    private var currentLineBytes = 0
    private var hasOpenLine = false
    private var finished = false

    init {
        require(maxLines >= 0) { "maxLines must not be negative" }
        require(maxBytes >= 0) { "maxBytes must not be negative" }
    }

    fun append(data: ByteArray) {
        check(!finished) { "Cannot append to a finished output accumulator" }
        if (data.isEmpty()) return
        totalRawBytes += data.size
        appendDecoded(decodeUtf8(data))
    }

    fun finish() {
        if (finished) return
        finished = true
        if (pendingUtf8.isNotEmpty()) {
            appendDecoded(pendingUtf8.toString(StandardCharsets.UTF_8))
            pendingUtf8 = ByteArray(0)
        }
        if (shouldUseTempFile()) ensureTempFile()
    }

    fun snapshot(persistIfTruncated: Boolean = false): PiBashOutputSnapshot {
        val text = snapshotText()
        val lines = splitLines(text)
        val totalLines = countAsInt(completedLines + if (hasOpenLine) 1 else 0)
        val totalBytes = countAsInt(totalDecodedBytes)
        val truncated = totalLines > maxLines || totalDecodedBytes > maxBytes
        val tail = truncateTail(lines, totalLines, totalBytes, text)
        if (persistIfTruncated && truncated) ensureTempFile()
        return PiBashOutputSnapshot(
            content = tail.content,
            truncated = truncated,
            truncatedBy = if (truncated) tail.truncatedBy else null,
            totalLines = totalLines,
            totalBytes = totalBytes,
            outputLines = if (truncated) tail.outputLines else lines.size,
            outputBytes = if (truncated) tail.outputBytes else text.toByteArray(StandardCharsets.UTF_8).size,
            lastLinePartial = if (truncated) tail.lastLinePartial else false,
            maxLines = maxLines,
            maxBytes = maxBytes,
            fullOutputPath = tempFilePath?.toString(),
        )
    }

    fun lastLineBytes(): Int = currentLineBytes

    fun closeTempFile() {
        outputStream?.let { stream ->
            stream.flush()
            stream.close()
            outputStream = null
        }
    }

    private fun appendDecoded(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r", "")
        if (normalized.isEmpty()) return
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        totalDecodedBytes += bytes.size
        if (outputStream == null) retainedChunks += bytes else outputStream?.write(bytes)
        tailText += normalized
        tailBytes += bytes.size
        if (tailBytes > maxRollingBytes * 2) trimTail()
        val lastNewline = normalized.lastIndexOf('\n')
        if (lastNewline < 0) {
            currentLineBytes += bytes.size
            hasOpenLine = true
        } else {
            completedLines += normalized.count { it == '\n' }
            val openLine = normalized.substring(lastNewline + 1)
            currentLineBytes = openLine.toByteArray(StandardCharsets.UTF_8).size
            hasOpenLine = openLine.isNotEmpty()
        }
        if (shouldUseTempFile()) ensureTempFile()
    }

    private fun decodeUtf8(data: ByteArray): String {
        val combined = ByteArray(pendingUtf8.size + data.size)
        pendingUtf8.copyInto(combined)
        data.copyInto(combined, pendingUtf8.size)
        val safeLength = safeUtf8PrefixLength(combined)
        pendingUtf8 = combined.copyOfRange(safeLength, combined.size)
        return combined.copyOf(safeLength).toString(StandardCharsets.UTF_8)
    }

    private fun safeUtf8PrefixLength(bytes: ByteArray): Int {
        var leadIndex = bytes.lastIndex
        while (leadIndex >= 0 && isContinuation(bytes[leadIndex])) leadIndex--
        if (leadIndex < 0) return bytes.size
        val lead = bytes[leadIndex].toInt() and 0xff
        val expected =
            when {
                lead and 0x80 == 0 -> 1
                lead and 0xe0 == 0xc0 -> 2
                lead and 0xf0 == 0xe0 -> 3
                lead and 0xf8 == 0xf0 -> 4
                else -> 1
            }
        return if (expected > bytes.size - leadIndex) leadIndex else bytes.size
    }

    private fun isContinuation(value: Byte): Boolean = value.toInt() and 0xc0 == 0x80

    private fun shouldUseTempFile(): Boolean =
        totalRawBytes > maxBytes ||
            totalDecodedBytes > maxBytes ||
            completedLines + (if (hasOpenLine) 1 else 0) > maxLines

    private fun ensureTempFile() {
        if (tempFilePath != null) return
        val safePrefix = tempFilePrefix.takeIf { it.length >= 3 } ?: "pi-output"
        val path = Files.createTempFile(safePrefix, ".log")
        val stream =
            Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        retainedChunks.forEach(stream::write)
        retainedChunks.clear()
        tempFilePath = path
        outputStream = stream
    }

    private fun trimTail() {
        val bytes = tailText.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxRollingBytes) {
            tailBytes = bytes.size
            return
        }
        var start = bytes.size - maxRollingBytes
        while (start < bytes.size && isContinuation(bytes[start])) start++
        tailStartsAtLineBoundary = start == 0 || bytes[start - 1] == '\n'.code.toByte()
        tailText = bytes.copyOfRange(start, bytes.size).toString(StandardCharsets.UTF_8)
        tailBytes = tailText.toByteArray(StandardCharsets.UTF_8).size
    }

    private fun snapshotText(): String {
        if (tailStartsAtLineBoundary) return tailText
        val firstNewline = tailText.indexOf('\n')
        return if (firstNewline < 0) tailText else tailText.substring(firstNewline + 1)
    }

    private fun truncateTail(
        lines: List<String>,
        totalLines: Int,
        totalBytes: Int,
        sourceText: String,
    ): TailResult {
        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return TailResult(sourceText, null, lines.size, sourceText.toByteArray(StandardCharsets.UTF_8).size, false)
        }
        val output = ArrayDeque<String>()
        var outputBytes = 0
        var truncatedBy = PiBashTruncatedBy.LINES
        var index = lines.lastIndex
        while (index >= 0 && output.size < maxLines && truncatedBy == PiBashTruncatedBy.LINES) {
            val line = lines[index]
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + if (output.isNotEmpty()) 1 else 0
            if (outputBytes + lineBytes > maxBytes) {
                truncatedBy = PiBashTruncatedBy.BYTES
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
        if (output.size >= maxLines && outputBytes <= maxBytes) truncatedBy = PiBashTruncatedBy.LINES
        val result = output.joinToString("\n")
        val resultBytes = result.toByteArray(StandardCharsets.UTF_8).size
        val lastLinePartial =
            truncatedBy == PiBashTruncatedBy.BYTES && output.size == 1 && currentLineBytes > maxBytes
        return TailResult(result, truncatedBy, output.size, resultBytes, lastLinePartial)
    }

    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n').toMutableList()
        if (content.endsWith('\n')) lines.removeLast()
        return lines
    }

    private fun truncateUtf8FromEnd(
        content: String,
        limit: Int,
    ): String {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= limit) return content
        var start = bytes.size - limit
        while (start < bytes.size && isContinuation(bytes[start])) start++
        return bytes.copyOfRange(start, bytes.size).toString(StandardCharsets.UTF_8)
    }

    private fun countAsInt(value: Long): Int = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private data class TailResult(
        val content: String,
        val truncatedBy: PiBashTruncatedBy?,
        val outputLines: Int,
        val outputBytes: Int,
        val lastLinePartial: Boolean,
    )

    private companion object {
        const val DEFAULT_MAX_LINES = 2_000
        const val DEFAULT_MAX_BYTES = 50 * 1024
    }
}
