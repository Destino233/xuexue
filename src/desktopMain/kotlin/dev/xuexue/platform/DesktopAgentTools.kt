package dev.xuexue.platform

import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.FileMutationQueue
import dev.xuexue.core.PiAgentTool
import dev.xuexue.core.PiFileDiff
import dev.xuexue.core.PiToolDefinition
import dev.xuexue.core.PiToolExecution
import dev.xuexue.core.truncateToolHead
import dev.xuexue.core.truncateToolTail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

internal class DesktopAgentTools(
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val authorize: suspend (AgentOperationKind, String, String) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutationQueue = FileMutationQueue()

    fun all(): List<PiAgentTool> =
        listOf(
            ReadTool(),
            WriteTool(),
            EditTool(),
            BashTool(),
            GrepTool(),
            FindTool(),
            LsTool(),
        )

    private abstract inner class WorkspaceTool : PiAgentTool {
        fun root(): Path = workspaceRoot() ?: error("Open a workspace before using tools.")

        fun path(arguments: JsonObject): Pair<Path, String> {
            val relative = arguments.string("path").ifBlank { error("The 'path' argument is required.") }
            val root = root()
            val resolved = workspaceService.resolveForWriteInside(root, relative)
            return resolved to root.relativize(resolved).toString().replace('\\', '/')
        }

        suspend fun allowed(
            operation: AgentOperationKind,
            target: String,
            detail: String,
        ) {
            if (!authorize(operation, target, detail)) error("Operation denied by user policy.")
        }
    }

    private inner class ReadTool : WorkspaceTool() {
        override val definition =
            toolDefinition("read", "Read a UTF-8 text file inside the workspace", pathSchema("path", "offset", "limit"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val (path, relative) = path(arguments)
                allowed(AgentOperationKind.READ_FILE, relative, "Read workspace file")
                if (!Files.isRegularFile(path)) error("Not a regular file: $relative")
                onProgress("Reading $relative")
                val lines = workspaceService.readText(path).lines()
                val offset = arguments.int("offset").coerceAtLeast(1)
                val limit = arguments.int("limit").takeIf { it > 0 }?.coerceAtMost(2_000) ?: 500
                val shown = lines.drop(offset - 1).take(limit)
                val output = shown.mapIndexed { index, line -> "${offset + index}: $line" }.joinToString("\n")
                PiToolExecution(renderToolOutput(truncateToolHead(output)))
            }
    }

    private inner class WriteTool : WorkspaceTool() {
        override val definition =
            toolDefinition("write", "Create or replace a UTF-8 workspace file", pathSchema("path", "content"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val (path, relative) = path(arguments)
                mutationQueue.withPath(path.toString()) {
                    val content = arguments.string("content")
                    allowed(AgentOperationKind.WRITE_FILE, relative, "Write ${content.length} characters")
                    val before = if (Files.exists(path)) workspaceService.readText(path) else ""
                    onProgress("Writing $relative")
                    workspaceService.atomicWrite(path, content)
                    PiToolExecution(
                        "Wrote $relative",
                        fileDiff = PiFileDiff(relative, unifiedDiff(before, content)),
                    )
                }
            }
    }

    private inner class EditTool : WorkspaceTool() {
        override val definition =
            toolDefinition(
                "edit",
                "Replace exactly one text occurrence in a workspace file",
                pathSchema("path", "oldText", "newText"),
            )

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val (path, relative) = path(arguments)
                mutationQueue.withPath(path.toString()) {
                    val oldText = arguments.string("oldText")
                    val newText = arguments.string("newText")
                    allowed(AgentOperationKind.WRITE_FILE, relative, "Edit one occurrence in workspace file")
                    if (oldText.isEmpty()) error("'oldText' must not be empty.")
                    val before = workspaceService.readText(path)
                    val occurrences = before.windowed(oldText.length, partialWindows = false).count { it == oldText }
                    if (occurrences != 1) error("Expected exactly one matching occurrence, found $occurrences.")
                    val after = before.replace(oldText, newText)
                    onProgress("Editing $relative")
                    workspaceService.atomicWrite(path, after)
                    PiToolExecution(
                        "Edited $relative",
                        fileDiff = PiFileDiff(relative, unifiedDiff(before, after)),
                    )
                }
            }
    }

    private inner class BashTool : WorkspaceTool() {
        override val definition =
            toolDefinition("bash", "Run a shell command from the workspace root", pathSchema("command"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val root = root()
                val command = arguments.string("command").ifBlank { error("The 'command' argument is required.") }
                allowed(AgentOperationKind.RUN_SHELL, command, "Run command in ${root.fileName}")
                onProgress("Running command")
                val process =
                    ProcessBuilder("bash", "-lc", command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start()
                val cancellation =
                    coroutineContext[Job]?.invokeOnCompletion {
                        process.destroy()
                        if (process.isAlive) process.destroyForcibly()
                    }
                try {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    coroutineContext.ensureActive()
                    val code = process.waitFor()
                    PiToolExecution(
                        "exit $code\n${renderToolOutput(truncateToolTail(output))}",
                        failed = code != 0,
                    )
                } finally {
                    cancellation?.dispose()
                }
            }
    }

    private inner class GrepTool : WorkspaceTool() {
        override val definition =
            toolDefinition("grep", "Search text files below a workspace path", pathSchema("query", "path"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val root = root()
                val query = arguments.string("query").ifBlank { error("The 'query' argument is required.") }
                val relative = arguments.string("path").ifBlank { "." }
                allowed(AgentOperationKind.READ_FILE, relative, "Search workspace files")
                val base = workspaceService.resolveExistingInside(root, relative)
                onProgress("Searching $relative")
                val matches = mutableListOf<String>()
                Files.walk(base).use { paths ->
                    paths.filter(Files::isRegularFile).takeWhile { matches.size < 200 }.forEach { path ->
                        runCatching { workspaceService.readText(path) }.getOrNull()?.lineSequence()?.forEachIndexed {
                            index,
                            line,
                            ->
                            if (query in line && matches.size < 200) {
                                matches += "${root.relativize(path)}:${index + 1}: $line"
                            }
                        }
                    }
                }
                val output = matches.ifEmpty { listOf("No matches.") }.joinToString("\n")
                PiToolExecution(renderToolOutput(truncateToolHead(output)))
            }
    }

    private inner class FindTool : WorkspaceTool() {
        override val definition =
            toolDefinition("find", "Find workspace files by path substring", pathSchema("query"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val root = root()
                val query = arguments.string("query")
                allowed(AgentOperationKind.READ_FILE, query, "List matching workspace files")
                onProgress("Finding files")
                val result =
                    Files.walk(root).use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .map(root::relativize)
                            .map(Path::toString)
                            .filter { query.lowercase() in it.lowercase() }
                            .limit(500)
                            .toList()
                    }
                val output = result.ifEmpty { listOf("No files found.") }.joinToString("\n")
                PiToolExecution(renderToolOutput(truncateToolHead(output)))
            }
    }

    private inner class LsTool : WorkspaceTool() {
        override val definition =
            toolDefinition("ls", "List a workspace directory", pathSchema("path"))

        override suspend fun execute(
            callId: String,
            arguments: JsonObject,
            onProgress: (String) -> Unit,
        ): PiToolExecution =
            withContext(ioDispatcher) {
                val root = root()
                val relative = arguments.string("path").ifBlank { "." }
                allowed(AgentOperationKind.READ_FILE, relative, "List workspace directory")
                val path = workspaceService.resolveExistingInside(root, relative)
                if (!Files.isDirectory(path)) error("Not a directory: $relative")
                onProgress("Listing $relative")
                Files.list(path).use { entries ->
                    val output =
                        entries
                            .map { entry ->
                                if (Files.isDirectory(entry)) "${entry.fileName}/" else entry.fileName.toString()
                            }.sorted()
                            .limit(500)
                            .toList()
                            .joinToString("\n")
                    PiToolExecution(renderToolOutput(truncateToolHead(output)))
                }
            }
    }
}

private fun toolDefinition(
    name: String,
    description: String,
    parameters: JsonObject,
) = PiToolDefinition(name, description, parameters)

private fun pathSchema(vararg names: String): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                names.forEach { name ->
                    val type = if (name == "offset" || name == "limit") "integer" else "string"
                    put(name, buildJsonObject { put("type", type) })
                }
            },
        )
        put(
            "required",
            kotlinx.serialization.json.JsonArray(names.take(1).map { JsonPrimitive(it) }),
        )
    }

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: 0

private fun renderToolOutput(result: dev.xuexue.core.ToolTruncationResult): String {
    val suffix = if (result.truncated) "\n[output truncated]" else ""
    return result.content + suffix
}

private fun unifiedDiff(
    before: String,
    after: String,
): String =
    "@@ -1,${before.lines().size} +1,${after.lines().size} @@\n" +
        before.lines().joinToString("\n") { "-$it" } +
        "\n" +
        after.lines().joinToString("\n") { "+$it" }
