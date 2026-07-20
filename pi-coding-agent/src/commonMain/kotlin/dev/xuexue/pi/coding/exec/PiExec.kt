package dev.xuexue.pi.coding.exec

import dev.xuexue.pi.ai.AbortSignal

data class PiExecOptions(
    val signal: AbortSignal? = null,
    val timeoutMs: Long? = null,
    val cwd: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

data class PiExecResult(
    val stdout: String,
    val stderr: String,
    val code: Int,
    val killed: Boolean,
)

/** Platform boundary used by extension APIs and future custom tools. */
fun interface PiCommandExecutor {
    suspend fun execute(
        command: String,
        args: List<String>,
        options: PiExecOptions,
    ): PiExecResult
}

suspend fun PiCommandExecutor.execute(
    command: String,
    args: List<String> = emptyList(),
    options: PiExecOptions = PiExecOptions(),
): PiExecResult = execute(command, args, options)

object UnavailablePiCommandExecutor : PiCommandExecutor {
    override suspend fun execute(
        command: String,
        args: List<String>,
        options: PiExecOptions,
    ): PiExecResult = error("Process execution is unavailable in this host: $command")
}
