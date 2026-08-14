@file:Suppress("InjectDispatcher", "MatchingDeclarationName", "TooGenericExceptionCaught")

package dev.xuexue.pi.coding.exec

import dev.xuexue.pi.coding.WindowsProcessSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** JVM implementation of Pi's non-shell exec protocol. */
class LocalPiCommandExecutor : PiCommandExecutor {
    private val activeProcesses = ConcurrentHashMap<ProcessHandle, AtomicBoolean>()

    override suspend fun execute(
        command: String,
        args: List<String>,
        options: PiExecOptions,
    ): PiExecResult =
        withContext(Dispatchers.IO) {
            val processBuilder = ProcessBuilder(listOf(command) + args)
            options.cwd?.let { processBuilder.directory(Path.of(it).toFile()) }
            if (options.environment.isNotEmpty()) processBuilder.environment().putAll(options.environment)
            val process =
                runCatching(processBuilder::start).getOrElse {
                    return@withContext PiExecResult(
                        stdout = "",
                        stderr = "",
                        code = 1,
                        killed = false,
                    )
                }
            val handle = process.toHandle()
            val killed = AtomicBoolean(false)
            activeProcesses[handle] = killed
            val killProcess = {
                if (killed.compareAndSet(false, true)) terminate(handle)
            }
            val abortSubscription =
                options.signal?.onAbort(killProcess)
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) killProcess()
                }
            try {
                val stdout = async { process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                val stderr = async { process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                if (options.signal?.isAborted == true) killProcess()
                val completed =
                    options.timeoutMs
                        ?.takeIf { it > 0 }
                        ?.let { timeout -> process.waitFor(timeout, TimeUnit.MILLISECONDS) }
                        ?: run {
                            process.waitFor()
                            true
                        }
                if (!completed) {
                    killProcess()
                    process.waitFor()
                }
                val exitCode = process.exitValueOrDefault()
                PiExecResult(
                    stdout = WindowsProcessSupport.normalizeOutput(stdout.await()),
                    stderr = WindowsProcessSupport.normalizeOutput(stderr.await()),
                    code = exitCode.takeUnless { killed.get() } ?: 0,
                    killed = killed.get(),
                )
            } finally {
                abortSubscription?.dispose()
                cancellation?.dispose()
                activeProcesses.remove(handle)
            }
        }

    fun abort() {
        activeProcesses.forEach { (handle, killed) ->
            if (killed.compareAndSet(false, true)) terminate(handle)
        }
    }

    private fun terminate(handle: ProcessHandle) {
        destroyDescendants(handle, force = false)
        handle.destroy()
        FORCE_KILL_SCHEDULER.schedule(
            {
                destroyDescendants(handle, force = true)
                if (handle.isAlive) handle.destroyForcibly()
            },
            FORCE_KILL_DELAY_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun destroyDescendants(
        handle: ProcessHandle,
        force: Boolean,
    ) {
        handle.descendants().use { descendants ->
            descendants.forEach { descendant ->
                if (force) descendant.destroyForcibly() else descendant.destroy()
            }
        }
    }

    private fun Process.exitValueOrDefault(): Int = runCatching { exitValue() }.getOrDefault(1)

    private companion object {
        const val FORCE_KILL_DELAY_SECONDS = 5L
        val FORCE_KILL_SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "pi-exec-force-kill").apply { isDaemon = true }
            }
    }
}
