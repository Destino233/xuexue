@file:Suppress("LargeClass", "TooManyFunctions")

package dev.xuexue.platform

import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.FileMutationQueue
import dev.xuexue.core.ToolTruncatedBy
import dev.xuexue.core.ToolTruncationResult
import dev.xuexue.core.truncateToolHead
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultContent
import dev.xuexue.pi.coding.PiBashOutputAccumulator
import dev.xuexue.pi.coding.PiBashOutputSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
                return@withContext when (val image = processPiImage(Files.readAllBytes(path), mimeType)) {
                    is PiImageProcessResult.Success -> {
                        val note =
                            buildString {
                                append("Read image file [${image.mimeType}]")
                                image.hints.forEach { hint -> append('\n').append(hint) }
                            }
                        AgentToolResult(
                            content = listOf(TextContent(note), ImageContent(image.data, image.mimeType)),
                            details = buildJsonObject {},
                        )
                    }

                    is PiImageProcessResult.Failure -> {
                        AgentToolResult(
                            content = listOf(TextContent("Read image file [$mimeType]\n${image.message}")),
                            details = buildJsonObject {},
                        )
                    }
                }
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
            val output = readOutput(text, truncation, start, offset, limit, rawPath)
            AgentToolResult(
                content = listOf(TextContent(output)),
                details = if (truncation.truncated) piDetails(truncation) else buildJsonObject {},
            )
        }

    @Suppress("ReturnCount")
    private fun readOutput(
        fullText: String,
        truncation: ToolTruncationResult,
        start: Int,
        startLine: Int,
        userLimit: Int?,
        pathHint: String,
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
                "Use bash: sed -n '${startLine}p' $pathHint | " +
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
                val before =
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.readString(path, StandardCharsets.UTF_8)
                    } else {
                        ""
                    }
                throwIfAborted(signal)
                workspaceService.atomicWrite(path, content)
                throwIfAborted(signal)
                val details =
                    if (before != content) {
                        val patch = generatePiUnifiedPatch(relative, before, content)
                        buildJsonObject {
                            put(
                                "fileDiff",
                                buildJsonObject {
                                    put("relativePath", relative)
                                    put("patch", patch.substringAfter("\n").substringAfter("\n"))
                                },
                            )
                        }
                    } else {
                        buildJsonObject {}
                    }
                return@withPath AgentToolResult(
                    content = listOf(TextContent("Successfully wrote ${content.length} bytes to $rawPath")),
                    details = details,
                )
            }
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
                val rawContent = Files.readString(path, StandardCharsets.UTF_8)
                val (bom, contentWithoutBom) = stripPiBom(rawContent)
                val lineEnding = detectPiLineEnding(contentWithoutBom)
                val normalizedContent = normalizePiLineEndings(contentWithoutBom)
                val applied =
                    applyPiEdits(
                        normalizedContent,
                        edits.map { edit -> PiEditReplacement(edit.oldText, edit.newText) },
                        rawPath,
                    )
                val after = bom + restorePiLineEndings(applied.newContent, lineEnding)
                throwIfAborted(signal)
                workspaceService.atomicWrite(path, after)
                val diff = generatePiDiff(applied.baseContent, applied.newContent)
                val details =
                    buildJsonObject {
                        put("diff", diff.diff)
                        put("patch", generatePiUnifiedPatch(rawPath, applied.baseContent, applied.newContent))
                        diff.firstChangedLine?.let { put("firstChangedLine", it) }
                    }
                AgentToolResult(
                    content = listOf(TextContent("Successfully replaced ${edits.size} block(s) in $rawPath.")),
                    details = details,
                )
            }
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

    // Keep Pi's streaming, timeout, abort, exit-code, and temp-file lifecycle in one state machine.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun executeBash(
        args: JsonObject,
        signal: AbortSignal?,
        onUpdate: dev.xuexue.pi.agent.AgentToolUpdateCallback?,
    ): AgentToolResult =
        withContext(ioDispatcher) {
            throwIfAborted(signal)
            val command = args.requiredString("command")
            val timeoutSeconds = args["timeout"]?.jsonPrimitive?.doubleOrNull
            require(
                timeoutSeconds == null ||
                    (
                        timeoutSeconds.isFinite() &&
                            timeoutSeconds > 0.0 &&
                            timeoutSeconds <= MAX_TIMEOUT_SECONDS
                    ),
            ) {
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
            val output = PiBashOutputAccumulator(PI_TOOL_MAX_LINES, PI_TOOL_MAX_BYTES, "pi-bash")
            val timedOut = AtomicBoolean(false)
            val timeoutJob =
                timeoutSeconds?.let { seconds ->
                    launch {
                        delay((seconds * 1_000).toLong().coerceAtLeast(1L))
                        if (timedOut.compareAndSet(false, true)) destroyProcess(process)
                    }
                }
            var outputDirty = false
            var lastUpdateAt = 0L
            var finalSnapshot: PiBashOutputSnapshot? = null

            fun emitOutputUpdate() {
                if (onUpdate == null || !outputDirty) return
                val now = System.nanoTime()
                if (now - lastUpdateAt < BASH_UPDATE_INTERVAL_NS) return
                val snapshot = output.snapshot(persistIfTruncated = true)
                val truncation = snapshot.toToolTruncation()
                onUpdate.update(
                    AgentToolResult(
                        content = listOf(TextContent(snapshot.content)),
                        details = piDetails(truncation.takeIf { snapshot.truncated }, snapshot.fullOutputDetails()),
                    ),
                )
                outputDirty = false
                lastUpdateAt = now
            }

            fun finishOutput(): PiBashOutputSnapshot {
                finalSnapshot?.let { return it }
                output.finish()
                emitOutputUpdate()
                return output.snapshot(persistIfTruncated = true).also {
                    finalSnapshot = it
                    output.closeTempFile()
                }
            }

            onUpdate?.update(AgentToolResult(content = emptyList(), details = buildJsonObject {}))
            try {
                val exitCode =
                    readProcessOutput(process, output, signal) {
                        outputDirty = true
                        emitOutputUpdate()
                    }
                val snapshot = finishOutput()
                if (timedOut.get()) {
                    val text = bashOutput(snapshot.toToolTruncation(), snapshot.fullOutputPath)
                    error("$text\n\nCommand timed out after $timeoutSeconds seconds")
                }
                if (signal?.isAborted == true) {
                    error("${bashOutput(snapshot.toToolTruncation(), snapshot.fullOutputPath)}\n\nCommand aborted")
                }
                val truncation = snapshot.toToolTruncation()
                val text = bashOutput(truncation, snapshot.fullOutputPath)
                if (exitCode != 0) error("$text\n\nCommand exited with code $exitCode")
                AgentToolResult(
                    content = listOf(TextContent(text)),
                    details = piDetails(truncation.takeIf { it.truncated }, snapshot.fullOutputDetails()),
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                destroyProcess(process)
                val snapshot = runCatching { finishOutput() }.getOrNull()
                val outputText = snapshot?.let { bashOutput(it.toToolTruncation(), it.fullOutputPath) }.orEmpty()
                val prefix = outputText.takeIf(String::isNotEmpty)?.let { "$it\n\n" }.orEmpty()
                error("${prefix}Command timed out after $timeoutSeconds seconds")
            } catch (error: CancellationException) {
                destroyProcess(process)
                val snapshot = runCatching { finishOutput() }.getOrNull()
                val outputText = snapshot?.let { bashOutput(it.toToolTruncation(), it.fullOutputPath) }.orEmpty()
                val prefix = outputText.takeIf(String::isNotEmpty)?.let { "$it\n\n" }.orEmpty()
                if (signal?.isAborted == true) {
                    error("${prefix}Command aborted")
                }
                throw error
            } finally {
                if (finalSnapshot == null) runCatching { finishOutput() }
                timeoutJob?.cancel()
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

    private fun readProcessOutput(
        process: Process,
        output: PiBashOutputAccumulator,
        signal: AbortSignal?,
        onChunk: () -> Unit,
    ): Int {
        val buffer = ByteArray(8 * 1024)
        process.inputStream.use { input ->
            while (true) {
                if (signal?.isAborted == true) throw CancellationException("Operation aborted")
                val count = input.read(buffer)
                if (count < 0) break
                output.append(buffer.copyOf(count))
                onChunk()
            }
        }
        return process.waitFor()
    }

    private fun destroyProcess(process: Process) {
        runCatching {
            process.toHandle().descendants().toList().asReversed().forEach { descendant ->
                descendant.destroy()
            }
        }
        process.destroy()
        runCatching {
            process.toHandle().descendants().toList().asReversed().forEach { descendant ->
                if (descendant.isAlive) descendant.destroyForcibly()
            }
        }
        if (process.isAlive) process.destroyForcibly()
    }

    private fun PiBashOutputSnapshot.toToolTruncation(): ToolTruncationResult =
        ToolTruncationResult(
            content = content,
            truncated = truncated,
            truncatedBy =
                truncatedBy?.let {
                    ToolTruncatedBy.valueOf(it.name)
                },
            totalLines = totalLines,
            totalBytes = totalBytes,
            outputLines = outputLines,
            outputBytes = outputBytes,
            lastLinePartial = lastLinePartial,
            maxLines = maxLines,
            maxBytes = maxBytes,
        )

    private fun PiBashOutputSnapshot.fullOutputDetails(): JsonObject? =
        fullOutputPath?.let { path ->
            buildJsonObject { put("fullOutputPath", path) }
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
            Files.walkFileTree(
                base,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult =
                        when {
                            signal?.isAborted == true -> {
                                FileVisitResult.TERMINATE
                            }

                            directory != base && ignoreIndex.ignores(directory) -> {
                                if (ignoreIndex.shouldPrune(directory)) {
                                    FileVisitResult.SKIP_SUBTREE
                                } else {
                                    FileVisitResult.CONTINUE
                                }
                            }

                            else -> {
                                if (directory != base) entries.add(directory)
                                FileVisitResult.CONTINUE
                            }
                        }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (signal?.isAborted == true) return FileVisitResult.TERMINATE
                        if (!ignoreIndex.ignores(file)) entries.add(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exception: java.io.IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        if (signal?.isAborted == true) error("Operation aborted")
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

    private companion object {
        const val BASH_UPDATE_INTERVAL_NS = 100_000_000L
        const val MAX_TIMEOUT_SECONDS = 2_147_483.647
    }

    private data class EditRequest(
        val oldText: String,
        val newText: String,
    )
}
