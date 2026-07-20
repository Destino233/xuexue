@file:Suppress("LargeClass", "TooManyFunctions")

package dev.xuexue.platform

import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.FileMutationQueue
import dev.xuexue.core.ToolTruncationResult
import dev.xuexue.core.truncateToolHead
import dev.xuexue.core.truncateToolTail
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

/** Pi's headless coding tools backed by the XueXue workspace policy layer. */
internal class DesktopPiCodingTools(
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val authorize: suspend (AgentOperationKind, String, String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutationQueue = FileMutationQueue()
    private val activeProcess = AtomicReference<Process?>(null)

    fun all(): List<AgentTool> =
        listOf(
            readTool(),
            bashTool(),
            editTool(),
            writeTool(),
            grepTool(),
            findTool(),
            lsTool(),
        )

    private fun readTool(): AgentTool =
        AgentTool(
            name = "read",
            label = "read",
            description =
                "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). " +
                    "Images are sent as attachments. For text files, output is truncated to " +
                    "$PI_TOOL_MAX_LINES lines or ${PI_TOOL_MAX_BYTES / 1024}KB (whichever is hit first). " +
                    "Use offset/limit for large files.",
            promptSnippet = "Read file contents",
            promptGuidelines = listOf("Use read to examine files instead of cat or sed."),
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "path" to piStringSchema("Path to the file to read (relative or absolute)"),
                        "offset" to piNumberSchema("Line number to start reading from (1-indexed)"),
                        "limit" to piNumberSchema("Maximum number of lines to read"),
                    ),
                    required = listOf("path"),
                ),
            execute = { _, args, signal, _ -> executeRead(args, signal) },
        )

    private suspend fun executeRead(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val rawPath = args.requiredString("path")
            val root = root()
            val path = resolveExistingPath(root, rawPath)
            val relative = displayPath(root, path)
            allowed(AgentOperationKind.READ_FILE, relative, "Read workspace file")
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Not a regular file: $relative" }
            val mimeType = detectImageMime(path)
            if (mimeType != null) {
                val data = Base64.getEncoder().encodeToString(Files.readAllBytes(path))
                return@withContext AgentToolResult(
                    content =
                        listOf<ToolResultContent>(
                            TextContent("Read image file [$mimeType]"),
                            ImageContent(data, mimeType),
                        ),
                    details = buildJsonObject {},
                )
            }

            val text = Files.readString(path, StandardCharsets.UTF_8)
            val lines = text.split('\n')
            val offset = args.intValue("offset")?.coerceAtLeast(1) ?: 1
            val start = offset - 1
            if (start >= lines.size) {
                error("Offset $offset is beyond end of file (${lines.size} lines total)")
            }
            val limit = args.intValue("limit")?.coerceAtLeast(0)
            val end = limit?.let { (start + it).coerceAtMost(lines.size) } ?: lines.size
            val selected = lines.subList(start, end).joinToString("\n")
            val truncation = truncateToolHead(selected, PI_TOOL_MAX_LINES, PI_TOOL_MAX_BYTES)
            val output = readOutput(text, selected, truncation, start, offset, limit)
            AgentToolResult(
                content = listOf(TextContent(output)),
                details = if (truncation.truncated) piDetails(truncation) else buildJsonObject {},
            )
        }

    @Suppress("ReturnCount")
    private fun readOutput(
        fullText: String,
        selected: String,
        truncation: ToolTruncationResult,
        start: Int,
        startLine: Int,
        userLimit: Int?,
    ): String {
        if (truncation.firstLineExceedsLimit) {
            val firstLineBytes =
                fullText
                    .split('\n')
                    .getOrNull(start)
                    ?.toByteArray(StandardCharsets.UTF_8)
                    ?.size ?: 0
            return "[Line $startLine is ${piFormatSize(firstLineBytes)}, " +
                "exceeds ${piFormatSize(PI_TOOL_MAX_BYTES)} limit. " +
                "Use bash: sed -n '${startLine}p' ${selectedPathHint(selected)} | " +
                "head -c $PI_TOOL_MAX_BYTES]"
        }
        if (truncation.truncated) {
            val endLine = startLine + truncation.outputLines - 1
            val nextOffset = endLine + 1
            val suffix =
                if (truncation.truncatedBy?.name == "LINES") {
                    "[Showing lines $startLine-$endLine of ${fullText.split('\n').size}. Use offset=$nextOffset to continue.]"
                } else {
                    "[Showing lines $startLine-$endLine of ${fullText.split('\n').size} " +
                        "(${piFormatSize(PI_TOOL_MAX_BYTES)} limit). Use offset=$nextOffset to continue.]"
                }
            return "${truncation.content}\n\n$suffix"
        }
        if (userLimit != null && start + userLimit < fullText.split('\n').size) {
            val remaining = fullText.split('\n').size - (start + userLimit)
            val nextOffset = start + userLimit + 1
            return "${truncation.content}\n\n[$remaining more lines in file. Use offset=$nextOffset to continue.]"
        }
        return truncation.content
    }

    private fun selectedPathHint(selected: String): String = if (selected.isEmpty()) "<path>" else "<path>"

    private fun writeTool(): AgentTool =
        AgentTool(
            name = "write",
            label = "write",
            description =
                "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. " +
                    "Automatically creates parent directories.",
            promptSnippet = "Create or overwrite files",
            promptGuidelines = listOf("Use write only for new files or complete rewrites."),
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "path" to piStringSchema("Path to the file to write (relative or absolute)"),
                        "content" to piStringSchema("Content to write to the file"),
                    ),
                    required = listOf("path", "content"),
                ),
            execute = { _, args, signal, _ -> executeWrite(args, signal) },
        )

    private suspend fun executeWrite(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val rawPath = args.requiredString("path")
            val content = args.requiredString("content")
            val root = root()
            val path = resolveWritePath(root, rawPath)
            val relative = displayPath(root, path)
            mutationQueue.withPath(path.toString()) {
                allowed(AgentOperationKind.WRITE_FILE, relative, "Write ${content.length} characters")
                throwIfAborted(signal)
                workspaceService.atomicWrite(path, content)
                throwIfAborted(signal)
            }
            AgentToolResult(
                content = listOf(TextContent("Successfully wrote ${content.length} bytes to $rawPath")),
                details = buildJsonObject {},
            )
        }

    private fun editTool(): AgentTool =
        AgentTool(
            name = "edit",
            label = "edit",
            description =
                "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, " +
                    "non-overlapping region of the original file.",
            promptSnippet = "Make precise file edits with exact text replacement",
            promptGuidelines =
                listOf(
                    "Use edit for precise changes (edits[].oldText must match exactly)",
                    "Use one edit call with multiple entries in edits[] for separate locations in one file",
                ),
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "path" to piStringSchema("Path to the file to edit (relative or absolute)"),
                        "edits" to
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("oldText", piStringSchema("Exact text to replace"))
                                                put("newText", piStringSchema("Replacement text"))
                                            },
                                        )
                                        put("required", JsonArray(listOf("oldText", "newText").map(::JsonPrimitive)))
                                    },
                                )
                            },
                    ),
                    required = listOf("path", "edits"),
                ),
            prepareArguments = ::prepareEditArguments,
            execute = { _, args, signal, _ -> executeEdit(args, signal) },
        )

    private fun prepareEditArguments(args: JsonObject): JsonObject {
        val parsedEdits =
            args["edits"]?.jsonPrimitive?.contentOrNull?.let { encoded ->
                runCatching {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(encoded)
                        .jsonArray
                }.getOrNull()
            }
        val legacyOld = args["oldText"]?.jsonPrimitive?.contentOrNull
        val legacyNew = args["newText"]?.jsonPrimitive?.contentOrNull
        if (parsedEdits == null && legacyOld == null && legacyNew == null) return args
        return buildJsonObject {
            args.forEach { (key, value) ->
                if (key !in setOf("oldText", "newText", "edits")) put(key, value)
            }
            when {
                parsedEdits != null -> {
                    put("edits", parsedEdits)
                }

                legacyOld != null && legacyNew != null -> {
                    put(
                        "edits",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("oldText", legacyOld)
                                    put("newText", legacyNew)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    private suspend fun executeEdit(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val rawPath = args.requiredString("path")
            val edits =
                args["edits"]?.jsonArray.orEmpty().map { edit ->
                    val objectValue = edit.jsonObject
                    EditRequest(
                        oldText = objectValue.requiredString("oldText"),
                        newText = objectValue.requiredString("newText"),
                    )
                }
            require(edits.isNotEmpty()) { "Edit tool input is invalid. edits must contain at least one replacement." }
            val root = root()
            val path = resolveExistingPath(root, rawPath)
            val relative = displayPath(root, path)
            mutationQueue.withPath(path.toString()) {
                allowed(AgentOperationKind.WRITE_FILE, relative, "Edit workspace file")
                val before = Files.readString(path, StandardCharsets.UTF_8)
                val ranges = edits.map { edit -> findUniqueRange(before, edit.oldText) }
                requireNoOverlaps(ranges)
                val after = applyEdits(before, edits, ranges)
                throwIfAborted(signal)
                workspaceService.atomicWrite(path, after)
                val diff = simpleDiff(before, after)
                val details =
                    buildJsonObject {
                        put("diff", diff)
                        put("patch", simplePatch(rawPath, before, after))
                        put("firstChangedLine", firstChangedLine(before, after))
                    }
                AgentToolResult(
                    content = listOf(TextContent("Successfully replaced ${edits.size} block(s) in $rawPath.")),
                    details = details,
                )
            }
        }

    private fun findUniqueRange(
        content: String,
        oldText: String,
    ): IntRange {
        require(oldText.isNotEmpty()) { "Could not find the exact text because oldText is empty." }
        val first = content.indexOf(oldText)
        require(first >= 0) { "Could not find the exact text in the file." }
        val second = content.indexOf(oldText, first + oldText.length)
        require(second < 0) { "Found multiple occurrences of the exact text; each edit must be unique." }
        return first until (first + oldText.length)
    }

    private fun requireNoOverlaps(ranges: List<IntRange>) {
        ranges.indices.forEach { index ->
            ranges.drop(index + 1).forEach { other ->
                require(ranges[index].last < other.first || other.last < ranges[index].first) {
                    "Edit ranges overlap; merge nearby changes into one edit."
                }
            }
        }
    }

    private fun applyEdits(
        content: String,
        edits: List<EditRequest>,
        ranges: List<IntRange>,
    ): String {
        val ordered = ranges.indices.sortedByDescending { ranges[it].first }
        var result = content
        ordered.forEach { index ->
            val range = ranges[index]
            result = result.replaceRange(range.first, range.last + 1, edits[index].newText)
        }
        return result
    }

    private fun simpleDiff(
        before: String,
        after: String,
    ): String =
        buildString {
            append("@@ -1,")
            append(before.lines().size)
            append(" +1,")
            append(after.lines().size)
            append(" @@\n")
            before.lines().forEach { append('-').append(it).append('\n') }
            after.lines().forEach { append('+').append(it).append('\n') }
        }.trimEnd()

    private fun simplePatch(
        path: String,
        before: String,
        after: String,
    ): String = "--- $path\n+++ $path\n${simpleDiff(before, after)}"

    private fun firstChangedLine(
        before: String,
        after: String,
    ): Int {
        var index = 0
        val limit = minOf(before.length, after.length)
        while (index < limit && before[index] == after[index]) index++
        return before.substring(0, index).count { it == '\n' } + 1
    }

    private fun bashTool(): AgentTool =
        AgentTool(
            name = "bash",
            label = "bash",
            description =
                "Execute a bash command in the current working directory. Returns stdout and stderr. " +
                    "Output is truncated to the last $PI_TOOL_MAX_LINES lines or ${PI_TOOL_MAX_BYTES / 1024}KB.",
            promptSnippet = "Execute bash commands (ls, grep, find, etc.)",
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "command" to piStringSchema("Bash command to execute"),
                        "timeout" to piNumberSchema("Timeout in seconds (optional, no default timeout)"),
                    ),
                    required = listOf("command"),
                ),
            execute = { _, args, signal, onUpdate -> executeBash(args, signal, onUpdate) },
        )

    private suspend fun executeBash(
        args: JsonObject,
        signal: AbortSignal?,
        onUpdate: dev.xuexue.pi.agent.AgentToolUpdateCallback?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val command = args.requiredString("command")
            val timeoutSeconds = args["timeout"]?.jsonPrimitive?.doubleOrNull
            require(timeoutSeconds == null || timeoutSeconds > 0.0) {
                "Invalid timeout: must be a finite number of seconds"
            }
            val root = root()
            allowed(AgentOperationKind.RUN_SHELL, command, "Run command in ${root.fileName}")
            val process =
                ProcessBuilder(shellCommand(command))
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start()
            activeProcess.set(process)
            val abortSubscription = signal?.onAbort { destroyProcess(process) }
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { destroyProcess(process) }
            try {
                val output =
                    if (timeoutSeconds != null) {
                        withTimeout((timeoutSeconds * 1_000).toLong().coerceAtLeast(1L)) {
                            readProcessOutput(process)
                        }
                    } else {
                        readProcessOutput(process)
                    }
                throwIfAborted(signal)
                val exitCode = process.waitFor()
                val truncation = truncateToolTail(output, PI_TOOL_MAX_LINES, PI_TOOL_MAX_BYTES)
                var fullOutputPath: String? = null
                if (truncation.truncated) {
                    fullOutputPath =
                        runCatching {
                            Files
                                .createTempFile("pi-bash-", ".log")
                                .also { path ->
                                    Files.writeString(
                                        path,
                                        output,
                                        StandardCharsets.UTF_8,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                    )
                                }.toString()
                        }.getOrNull()
                }
                val text = bashOutput(truncation, fullOutputPath)
                onUpdate?.update(
                    AgentToolResult(
                        content = listOf(TextContent(text)),
                        details =
                            piDetails(
                                truncation.takeIf { it.truncated },
                                fullOutputPath?.let { buildJsonObject { put("fullOutputPath", it) } },
                            ),
                    ),
                )
                if (exitCode != 0) error("$text\n\nCommand exited with code $exitCode")
                AgentToolResult(
                    content = listOf(TextContent(text)),
                    details =
                        piDetails(
                            truncation.takeIf { it.truncated },
                            fullOutputPath?.let { buildJsonObject { put("fullOutputPath", it) } },
                        ),
                )
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                destroyProcess(process)
                throw IllegalStateException("Command timed out after $timeoutSeconds seconds", error)
            } finally {
                abortSubscription?.dispose()
                cancellation?.dispose()
                activeProcess.compareAndSet(process, null)
            }
        }

    private fun shellCommand(command: String): List<String> {
        val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        if (windows) return listOf("cmd.exe", "/c", command)
        val shell = System.getenv("SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
        return listOf(shell, "-lc", command)
    }

    private fun readProcessOutput(process: Process): String =
        process.inputStream
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText().replace("\r", "") }

    private fun destroyProcess(process: Process) {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private fun bashOutput(
        truncation: ToolTruncationResult,
        fullOutputPath: String?,
    ): String {
        val text = truncation.content.ifEmpty { "(no output)" }
        if (!truncation.truncated) return text
        val endLine = truncation.totalLines
        val startLine = endLine - truncation.outputLines + 1
        val footer =
            if (truncation.lastLinePartial) {
                "[Showing last ${piFormatSize(truncation.outputBytes)} of line $endLine. Full output: $fullOutputPath]"
            } else if (truncation.truncatedBy?.name == "LINES") {
                "[Showing lines $startLine-$endLine of ${truncation.totalLines}. Full output: $fullOutputPath]"
            } else {
                "[Showing lines $startLine-$endLine of ${truncation.totalLines} " +
                    "(${piFormatSize(PI_TOOL_MAX_BYTES)} limit). Full output: $fullOutputPath]"
            }
        return "$text\n\n$footer"
    }

    private fun grepTool(): AgentTool =
        AgentTool(
            name = "grep",
            label = "grep",
            description =
                "Search file contents for a pattern. Returns matching lines with file paths and line numbers. " +
                    "Respects .gitignore. Output is truncated to $PI_TOOL_MAX_BYTES bytes or 100 matches.",
            promptSnippet = "Search file contents for patterns (respects .gitignore)",
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "pattern" to piStringSchema("Search pattern (regex or literal string)"),
                        "path" to piStringSchema("Directory or file to search (default: current directory)"),
                        "glob" to piStringSchema("Filter files by glob pattern"),
                        "ignoreCase" to piBooleanSchema("Case-insensitive search (default: false)"),
                        "literal" to piBooleanSchema("Treat pattern as literal string instead of regex"),
                        "context" to piNumberSchema("Number of lines before and after each match"),
                        "limit" to piNumberSchema("Maximum number of matches to return (default: 100)"),
                    ),
                    required = listOf("pattern"),
                ),
            execute = { _, args, signal, _ -> executeGrep(args, signal) },
        )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun executeGrep(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val pattern = args.requiredString("pattern")
            val root = root()
            val rawSearchPath = args.stringValue("path")?.takeIf(String::isNotBlank) ?: "."
            val searchPath = resolveExistingPath(root, rawSearchPath)
            val searchIsDirectory = Files.isDirectory(searchPath, LinkOption.NOFOLLOW_LINKS)
            val displayRoot = if (searchIsDirectory) searchPath else searchPath.parent ?: root
            allowed(AgentOperationKind.READ_FILE, displayPath(root, searchPath), "Search workspace files")
            val flags = if (args.booleanValue("ignoreCase")) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex =
                runCatching {
                    Regex(if (args.booleanValue("literal")) Regex.escape(pattern) else pattern, flags)
                }.getOrElse { error("Invalid regex pattern: ${it.message}") }
            val effectiveLimit = max(1, args.intValue("limit") ?: 100)
            val context = max(0, args.intValue("context") ?: 0)
            val ignoreIndex = PiIgnoreIndex(root)
            val files =
                searchEntries(searchPath, ignoreIndex, signal)
                    .filter { path ->
                        args.stringValue("glob")?.let { glob ->
                            val relative = path.relativeToPosix(displayRoot)
                            globMatches(glob, relative)
                        } ?: true
                    }.sortedBy { it.relativeToPosix(displayRoot) }
            val outputLines = mutableListOf<String>()
            var matchCount = 0
            var linesTruncated = false
            files.forEach { path ->
                if (matchCount >= effectiveLimit) return@forEach
                val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@forEach
                if (bytes.any { it == 0.toByte() }) return@forEach
                val lines =
                    bytes
                        .toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .split('\n')
                lines.forEachIndexed { index, line ->
                    if (matchCount >= effectiveLimit) return@forEachIndexed
                    if (!regex.containsMatchIn(line)) return@forEachIndexed
                    matchCount++
                    val start = max(0, index - context)
                    val end = minOf(lines.lastIndex, index + context)
                    for (lineIndex in start..end) {
                        val raw = lines[lineIndex].replace("\r", "")
                        val truncated = raw.length > PI_GREP_MAX_LINE_LENGTH
                        if (truncated) linesTruncated = true
                        val shown = raw.take(PI_GREP_MAX_LINE_LENGTH)
                        val relative = path.relativeToPosix(displayRoot)
                        outputLines +=
                            if (lineIndex == index) {
                                "$relative:${lineIndex + 1}: $shown"
                            } else {
                                "$relative-${lineIndex + 1}- $shown"
                            }
                    }
                }
            }
            if (matchCount == 0) {
                return@withContext AgentToolResult(listOf(TextContent("No matches found")), buildJsonObject {})
            }
            val truncation = truncateToolHead(outputLines.joinToString("\n"), Int.MAX_VALUE, PI_TOOL_MAX_BYTES)
            val notices = mutableListOf<String>()
            val limitReached = matchCount >= effectiveLimit
            if (limitReached) {
                notices +=
                    "$effectiveLimit matches limit reached. " +
                    "Use limit=${effectiveLimit * 2} for more, or refine pattern"
            }
            if (truncation.truncated) notices += "${piFormatSize(PI_TOOL_MAX_BYTES)} limit reached"
            if (linesTruncated) {
                notices +=
                    "Some lines truncated to $PI_GREP_MAX_LINE_LENGTH chars. " +
                    "Use read tool to see full lines"
            }
            val output =
                truncation.content +
                    notices.takeIf(List<String>::isNotEmpty)?.let { "\n\n[${it.joinToString(". ")}]" }.orEmpty()
            val extra =
                buildJsonObject {
                    if (limitReached) put("matchLimitReached", effectiveLimit)
                    if (linesTruncated) put("linesTruncated", true)
                }
            AgentToolResult(listOf(TextContent(output)), piDetails(truncation.takeIf { it.truncated }, extra))
        }

    private fun findTool(): AgentTool =
        AgentTool(
            name = "find",
            label = "find",
            description =
                "Search for files by glob pattern. Returns matching paths relative to the search directory. " +
                    "Respects .gitignore. Output is truncated to 1000 results or ${PI_TOOL_MAX_BYTES / 1024}KB.",
            promptSnippet = "Find files by glob pattern (respects .gitignore)",
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "pattern" to piStringSchema("Glob pattern to match files, e.g. '*.ts' or '**/*.json'"),
                        "path" to piStringSchema("Directory to search in (default: current directory)"),
                        "limit" to piNumberSchema("Maximum number of results (default: 1000)"),
                    ),
                    required = listOf("pattern"),
                ),
            execute = { _, args, signal, _ -> executeFind(args, signal) },
        )

    private suspend fun executeFind(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val pattern = args.requiredString("pattern")
            val root = root()
            val rawSearchPath = args.stringValue("path")?.takeIf(String::isNotBlank) ?: "."
            val searchPath = resolveExistingPath(root, rawSearchPath)
            require(Files.isDirectory(searchPath, LinkOption.NOFOLLOW_LINKS)) { "Not a directory: $rawSearchPath" }
            allowed(AgentOperationKind.READ_FILE, displayPath(root, searchPath), "Find workspace files")
            val effectiveLimit = max(1, args.intValue("limit") ?: 1_000)
            val ignoreIndex = PiIgnoreIndex(root)
            val entries =
                searchEntries(searchPath, ignoreIndex, signal)
                    .filter { it != searchPath && globMatches(pattern, it.relativeToPosix(searchPath)) }
                    .sortedBy { it.relativeToPosix(searchPath) }
            val limitReached = entries.size >= effectiveLimit
            val selected =
                entries.take(effectiveLimit).map { path ->
                    val relative = path.relativeToPosix(searchPath)
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) "$relative/" else relative
                }
            if (selected.isEmpty()) {
                return@withContext AgentToolResult(
                    listOf(TextContent("No files found matching pattern")),
                    buildJsonObject {},
                )
            }
            val truncation = truncateToolHead(selected.joinToString("\n"), Int.MAX_VALUE, PI_TOOL_MAX_BYTES)
            val notices = mutableListOf<String>()
            if (limitReached) {
                notices +=
                    "$effectiveLimit results limit reached. " +
                    "Use limit=${effectiveLimit * 2} for more, or refine pattern"
            }
            if (truncation.truncated) notices += "${piFormatSize(PI_TOOL_MAX_BYTES)} limit reached"
            val output =
                truncation.content +
                    notices.takeIf(List<String>::isNotEmpty)?.let { "\n\n[${it.joinToString(". ")}]" }.orEmpty()
            val extra = buildJsonObject { if (limitReached) put("resultLimitReached", effectiveLimit) }
            AgentToolResult(listOf(TextContent(output)), piDetails(truncation.takeIf { it.truncated }, extra))
        }

    private fun lsTool(): AgentTool =
        AgentTool(
            name = "ls",
            label = "ls",
            description =
                "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. " +
                    "Includes dotfiles. Output is truncated to 500 entries or ${PI_TOOL_MAX_BYTES / 1024}KB.",
            promptSnippet = "List directory contents",
            parameters =
                piToolSchema(
                    linkedMapOf(
                        "path" to piStringSchema("Directory to list (default: current directory)"),
                        "limit" to piNumberSchema("Maximum number of entries to return (default: 500)"),
                    ),
                ),
            execute = { _, args, signal, _ -> executeLs(args, signal) },
        )

    private suspend fun executeLs(
        args: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val root = root()
            val rawPath = args.stringValue("path")?.takeIf(String::isNotBlank) ?: "."
            val directory = resolveExistingPath(root, rawPath)
            allowed(AgentOperationKind.READ_FILE, displayPath(root, directory), "List workspace directory")
            require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Not a directory: $rawPath" }
            val effectiveLimit = max(1, args.intValue("limit") ?: 500)
            val entries =
                Files.list(directory).use { stream ->
                    stream.toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() })
                }
            if (entries.isEmpty()) {
                return@withContext AgentToolResult(
                    listOf(TextContent("(empty directory)")),
                    buildJsonObject {},
                )
            }
            val limitReached = entries.size > effectiveLimit
            val selected =
                entries.take(effectiveLimit).map { entry ->
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        "${entry.fileName}/"
                    } else {
                        entry.fileName.toString()
                    }
                }
            val truncation = truncateToolHead(selected.joinToString("\n"), Int.MAX_VALUE, PI_TOOL_MAX_BYTES)
            val notices = mutableListOf<String>()
            if (limitReached) {
                notices += "$effectiveLimit entries limit reached. Use limit=${effectiveLimit * 2} for more"
            }
            if (truncation.truncated) notices += "${piFormatSize(PI_TOOL_MAX_BYTES)} limit reached"
            val output =
                truncation.content +
                    notices.takeIf(List<String>::isNotEmpty)?.let { "\n\n[${it.joinToString(". ")}]" }.orEmpty()
            val extra = buildJsonObject { if (limitReached) put("entryLimitReached", effectiveLimit) }
            AgentToolResult(listOf(TextContent(output)), piDetails(truncation.takeIf { it.truncated }, extra))
        }

    private fun searchEntries(
        base: Path,
        ignoreIndex: PiIgnoreIndex,
        signal: AbortSignal?,
    ): List<Path> {
        val entries = mutableListOf<Path>()
        runCatching {
            Files.walk(base).use { stream ->
                stream.forEach { path ->
                    if (signal?.isAborted == true || ignoreIndex.ignores(path)) return@forEach
                    val isFileOrDirectory =
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    if (path != base && isFileOrDirectory) {
                        entries.add(path)
                    }
                }
            }
        }
        return entries
    }

    private fun globMatches(
        pattern: String,
        relativePath: String,
    ): Boolean {
        val normalized = pattern.trim().removePrefix("./").replace('\\', '/')
        val regex = compilePiGlob(normalized)
        return if ('/' in normalized) {
            regex.matches(relativePath)
        } else {
            regex.matches(relativePath.substringAfterLast('/'))
        }
    }

    private fun root(): Path = workspaceRoot() ?: error("Open a workspace before using tools.")

    private suspend fun allowed(
        operation: AgentOperationKind,
        target: String,
        detail: String,
    ) {
        if (!authorize(operation, target, detail)) error("Operation denied by user policy.")
    }

    private fun resolveExistingPath(
        root: Path,
        rawPath: String,
    ): Path {
        val normalized = normalizePiPathInput(rawPath)
        piPathVariants(normalized).forEach { candidate ->
            runCatching { workspaceService.resolveExistingInside(root, candidate) }
                .getOrNull()
                ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                ?.let { return it }
        }
        error("Path not found: $normalized")
    }

    private fun resolveWritePath(
        root: Path,
        rawPath: String,
    ): Path = workspaceService.resolveForWriteInside(root, normalizePiPathInput(rawPath))

    private fun displayPath(
        root: Path,
        path: Path,
    ): String =
        root
            .toAbsolutePath()
            .normalize()
            .relativize(path.toAbsolutePath().normalize())
            .toPosixString()

    private fun throwIfAborted(signal: AbortSignal?) {
        if (signal?.isAborted == true) error("Operation aborted")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun detectImageMime(path: Path): String? {
        val bytes =
            runCatching {
                Files.readAllBytes(path).copyOf(minOf(Files.size(path).toInt(), 16))
            }.getOrNull() ?: return null
        val extension =
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
        return when {
            bytes.size >= 8 &&
                bytes.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                ) -> "image/png"

            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"

            bytes.size >= 6 && String(bytes, Charsets.US_ASCII).startsWith("GIF") -> "image/gif"

            bytes.size >= 12 && String(bytes, Charsets.US_ASCII).startsWith("RIFF") &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"

            extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> null

            else -> null
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: error("The '$name' argument is required.")

    private fun JsonObject.stringValue(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intValue(name: String): Int? = this[name]?.jsonPrimitive?.doubleOrNull?.toInt()

    private fun JsonObject.booleanValue(name: String): Boolean =
        this[name]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false

    private data class EditRequest(
        val oldText: String,
        val newText: String,
    )
}
