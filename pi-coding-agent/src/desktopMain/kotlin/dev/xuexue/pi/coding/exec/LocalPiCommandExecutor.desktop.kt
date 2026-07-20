@file:Suppress("InjectDispatcher", "MatchingDeclarationName", "TooGenericExceptionCaught")

package dev.xuexue.pi.coding.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** JVM implementation of Pi's non-shell exec protocol. */
class LocalPiCommandExecutor : PiCommandExecutor {
    private val activeProcess = AtomicReference<ProcessHandle?>(null)

    override suspend fun execute(
        command: String,
        args: List<String>,
        options: PiExecOptions,
    ): PiExecResult =
        withContext(Dispatchers.IO) {
            check(command.isNotBlank()) { "Command must not be blank" }
            val processBuilder = ProcessBuilder(listOf(command) + args)
            options.cwd?.let { processBuilder.directory(Path.of(it).toFile()) }
            if (options.environment.isNotEmpty()) processBuilder.environment().putAll(options.environment)
            val process = processBuilder.start()
            val handle = process.toHandle()
            val killed = AtomicBoolean(false)
            activeProcess.set(handle)
            val abortSubscription =
                options.signal?.onAbort {
                    killed.set(true)
                    destroy(handle)
                }
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        killed.set(true)
                        destroy(handle)
                    }
                }
            try {
                val stdout = async { process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                val stderr = async { process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                val completed =
                    withTimeoutOrNull(options.timeoutMs?.takeIf { it > 0 } ?: Long.MAX_VALUE) {
                        val code = process.waitFor()
                        val output = awaitAll(stdout, stderr)
                        PiExecResult(
                            stdout = output[0] as String,
                            stderr = output[1] as String,
                            code = code,
                            killed = killed.get(),
                        )
                    }
                completed ?: run {
                    killed.set(true)
                    destroy(handle)
                    PiExecResult(
                        stdout = stdout.await(),
                        stderr = stderr.await(),
                        code = process.exitValueOrDefault(),
                        killed = true,
                    )
                }
            } finally {
                abortSubscription?.dispose()
                cancellation?.dispose()
                activeProcess.compareAndSet(handle, null)
            }
        }

    fun abort() {
        activeProcess.get()?.let(::destroy)
    }

    private fun destroy(handle: ProcessHandle) {
        handle.descendants().forEach(ProcessHandle::destroy)
        handle.destroy()
        if (handle.isAlive) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly)
            handle.destroyForcibly()
        }
    }

    private fun Process.exitValueOrDefault(): Int = runCatching { exitValue() }.getOrDefault(1)
}
