@file:Suppress("InjectDispatcher", "MatchingDeclarationName")

package dev.xuexue.pi.coding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Local JVM shell implementation shared by the Desktop UI and RPC host. */
class LocalBashExecutor : AgentSessionBashExecutor {
    private val active = AtomicReference<ActiveProcess?>(null)

    override suspend fun execute(
        command: String,
        cwd: String,
        excludeFromContext: Boolean?,
    ): BashResult =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(shellCommand(command))
                    .directory(Path.of(cwd).toFile())
                    .redirectErrorStream(true)
                    .start()
            val activeProcess = ActiveProcess(process)
            active.set(activeProcess)
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion {
                    activeProcess.cancelled = true
                    destroy(process)
                }
            try {
                val output = readOutput(process)
                val exitCode = process.waitFor()
                result(output, exitCode, activeProcess.cancelled)
            } catch (_: CancellationException) {
                activeProcess.cancelled = true
                destroy(process)
                result(readOutput(process), null, cancelled = true)
            } finally {
                cancellation?.dispose()
                active.compareAndSet(activeProcess, null)
            }
        }

    override fun abort() {
        active.get()?.let { current ->
            current.cancelled = true
            destroy(current.process)
        }
    }

    private fun shellCommand(command: String): List<String> {
        val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
        if (windows) return listOf("cmd.exe", "/c", command)
        val shell = System.getenv("SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
        return listOf(shell, "-lc", command)
    }

    private fun readOutput(process: Process): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        process.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).replace("\r", "")
    }

    private fun result(
        output: String,
        exitCode: Int?,
        cancelled: Boolean,
    ): BashResult {
        if (output.length <= MAX_OUTPUT_CHARS) {
            return BashResult(output, exitCode.takeUnless { cancelled }, cancelled, truncated = false)
        }
        val fullPath =
            runCatching {
                Files
                    .createTempFile("xuexue-pi-bash-", ".log")
                    .also { path ->
                        Files.writeString(path, output)
                    }.toString()
            }.getOrNull()
        return BashResult(
            output = output.takeLast(MAX_OUTPUT_CHARS),
            exitCode = exitCode.takeUnless { cancelled },
            cancelled = cancelled,
            truncated = true,
            fullOutputPath = fullPath,
        )
    }

    private fun destroy(process: Process) {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private class ActiveProcess(
        val process: Process,
        @Volatile var cancelled: Boolean = false,
    )

    private companion object {
        const val MAX_OUTPUT_CHARS = 20_000
    }
}
