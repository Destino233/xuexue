@file:Suppress("InjectDispatcher", "MatchingDeclarationName")

package dev.xuexue.pi.coding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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
            val builder =
                ProcessBuilder(WindowsProcessSupport.shellCommand(command))
                    .directory(Path.of(cwd).toFile())
                    .redirectErrorStream(true)
            WindowsProcessSupport.configure(builder)
            val process =
                builder.start()
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

    private fun readOutput(process: Process): PiBashOutputSnapshot {
        val output = PiBashOutputAccumulator(tempFilePrefix = "xuexue-pi-bash")
        val buffer = ByteArray(8 * 1024)
        process.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.append(buffer.copyOf(count))
            }
        }
        output.finish()
        return output.snapshot(persistIfTruncated = true).also { output.closeTempFile() }
    }

    private fun result(
        output: PiBashOutputSnapshot,
        exitCode: Int?,
        cancelled: Boolean,
    ): BashResult =
        BashResult(
            output = output.content,
            exitCode = exitCode.takeUnless { cancelled },
            cancelled = cancelled,
            truncated = output.truncated,
            fullOutputPath = output.fullOutputPath,
        )

    private fun destroy(process: Process) {
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

    private class ActiveProcess(
        val process: Process,
        @Volatile var cancelled: Boolean = false,
    )
}
