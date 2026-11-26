package dev.xuexue.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class DshConnection(
    private val process: Process,
    scope: CoroutineScope,
    private val io: CoroutineContext,
) {
    private val output = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val outputLock = Mutex()
    private val frames = Channel<JsonObject>(capacity = 32)
    private val reader: Job = scope.launch(io) { readFrames() }
    private var nextId = 0

    val isAlive: Boolean get() = process.isAlive

    suspend fun request(
        method: String,
        params: JsonObject,
        onFrame: suspend (JsonObject) -> Unit = {},
    ): JsonObject {
        val id = JsonPrimitive(++nextId)
        send(message(method, params, id))
        while (true) {
            val frame = frames.receive()
            if (frame["id"] == id && frame["method"] == null) return result(frame)
            onFrame(frame)
        }
    }

    suspend fun notify(
        method: String,
        params: JsonObject,
    ) = send(message(method, params))

    suspend fun respond(
        id: JsonElement,
        result: JsonObject,
    ) {
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    suspend fun close() =
        withContext(io) {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.cancel()
            frames.cancel()
        }

    private suspend fun send(frame: JsonObject) =
        outputLock.withLock {
            withContext(io) {
                output.write(frame.toString())
                output.newLine()
                output.flush()
            }
        }

    private suspend fun readFrames() {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    val frame =
                        runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                            ?: throw IOException("DSH returned an invalid ACP frame.")
                    frames.send(frame)
                }
            }
            frames.close(IOException("DSH disconnected. See the DSH runtime log."))
        } catch (error: IOException) {
            frames.close(error)
        }
    }

    private fun message(
        method: String,
        params: JsonObject,
        id: JsonPrimitive? = null,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
        id?.let { put("id", it) }
    }

    private fun result(frame: JsonObject): JsonObject {
        val failure = frame["error"] as? JsonObject
        if (failure != null) throw IOException(failure.text("message") ?: "DSH request failed.")
        return frame["result"] as? JsonObject ?: throw IOException("DSH returned an invalid ACP response.")
    }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal class DshRuntime(
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val appHome = Path.of(System.getProperty("user.home"), ".xuexue")

    suspend fun connect(
        scope: CoroutineScope,
        root: Path,
        readOnly: Boolean,
        allowShell: Boolean,
    ): DshConnection {
        val patch =
            withContext(io + NonCancellable) {
                if (allowShell) null else writeShellPatch()
            }
        var started: DshConnection? = null
        var initialized = false
        try {
            coroutineContext.ensureActive()
            val connection =
                withContext(io + NonCancellable) {
                    DshConnection(start(root, readOnly, patch), scope, io).also { started = it }
                }
            val result =
                withTimeoutOrNull(30_000) {
                    connection.request("initialize", buildJsonObject { put("protocolVersion", 1) })
                } ?: throw IOException("DSH initialization timed out. See the DSH runtime log.")
            if (result["protocolVersion"] != JsonPrimitive(1)) throw IOException("DSH must support ACP v1.")
            initialized = true
            return connection
        } finally {
            withContext(io + NonCancellable) {
                if (!initialized) started?.close()
                patch?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun writeShellPatch(): Path {
        val patch = Files.createTempFile("xuexue-dsh-", ".yml")
        return Files.writeString(patch, "- id: tool-bash\n  disabled: true\n- id: tool-pwsh\n  disabled: true\n")
    }

    private fun start(
        root: Path,
        readOnly: Boolean,
        patch: Path?,
    ): Process {
        val entry = resolveEntry()
        if (!entry.isAbsolute || !Files.isRegularFile(entry)) {
            throw IOException("DSH is unavailable. Install the runtime with tools/setup-dsh-runtime.mjs.")
        }
        val nodeName = if (java.io.File.separatorChar == '\\') "node.exe" else "node"
        val localNode = entry.resolveSibling("../../../../$nodeName").normalize()
        val node = System.getenv("XUEXUE_DSH_NODE") ?: localNode.takeIf(Files::isExecutable)?.toString() ?: "node"
        val home = System.getenv("XUEXUE_DSH_HOME") ?: appHome.resolve("dsh").toString()
        Files.createDirectories(appHome)
        val command =
            listOf(node, entry.toString(), "--profile", "acp") +
                patch?.let { listOf("--patch", it.toString()) }.orEmpty()
        return ProcessBuilder(command)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(appHome.resolve("dsh.log").toFile()))
            .apply {
                environment()["DSH_HOME"] = home
                environment()["DSH_PERMISSION_MODE"] = if (readOnly) "read-only" else "workspace-write"
            }.start()
    }

    private fun resolveEntry(): Path {
        System.getenv("XUEXUE_DSH_ENTRY")?.let { return Path.of(it) }
        val relative = "dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js"
        val bundled = System.getProperty("compose.application.resources.dir")?.let { Path.of(it).resolve(relative) }
        return bundled?.takeIf(Files::isRegularFile) ?: appHome.resolve(relative)
    }
}
