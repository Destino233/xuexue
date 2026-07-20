package dev.xuexue.pi.coding.exec

import dev.xuexue.pi.ai.AbortController
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocalPiCommandExecutorTest {
    @Test
    fun capturesSeparateOutputAndExitCode() =
        runTest {
            val executor = LocalPiCommandExecutor()
            val result = executor.execute(command(), args())

            assertEquals("stdout\n", result.stdout)
            assertEquals("stderr\n", result.stderr)
            assertEquals(0, result.code)
            assertFalse(result.killed)
        }

    @Test
    fun timeoutTerminatesProcessAndKeepsPartialOutput() =
        runTest {
            val executor = LocalPiCommandExecutor()
            val result =
                executor.execute(
                    command(),
                    slowArgs("before-timeout"),
                    PiExecOptions(timeoutMs = 100),
                )

            assertEquals("before-timeout\n", result.stdout)
            assertEquals("", result.stderr)
            assertEquals(0, result.code)
            assertTrue(result.killed)
        }

    @Test
    fun abortSignalTerminatesRunningProcessAndKeepsPartialOutput() =
        runTest {
            val marker = Files.createTempFile("pi-exec-started", ".marker")
            Files.delete(marker)
            try {
                val controller = AbortController()
                val executor = LocalPiCommandExecutor()
                val execution =
                    async {
                        executor.execute(
                            command(),
                            abortArgs(),
                            PiExecOptions(
                                signal = controller.signal,
                                environment = mapOf("PI_EXEC_MARKER" to marker.toString()),
                            ),
                        )
                    }

                awaitMarker(marker)
                controller.abort("cancelled")
                val result = execution.await()

                assertEquals("before-abort\n", result.stdout)
                assertEquals(0, result.code)
                assertTrue(result.killed)
            } finally {
                Files.deleteIfExists(marker)
            }
        }

    @Test
    fun alreadyAbortedSignalTerminatesSpawnedProcess() =
        runTest {
            val controller = AbortController().apply { abort("cancelled") }
            val executor = LocalPiCommandExecutor()
            val result =
                executor.execute(
                    command(),
                    slowArgs("before-abort"),
                    PiExecOptions(signal = controller.signal),
                )

            assertEquals(0, result.code)
            assertTrue(result.killed)
        }

    @Test
    fun spawnFailureMatchesPiResultInsteadOfThrowing() =
        runTest {
            val executor = LocalPiCommandExecutor()
            val result = executor.execute("/definitely/missing/pi-exec-command")

            assertEquals("", result.stdout)
            assertEquals("", result.stderr)
            assertEquals(1, result.code)
            assertFalse(result.killed)
        }

    private fun command(): String =
        if (isWindows()) {
            "cmd.exe"
        } else {
            "/bin/sh"
        }

    private fun args(): List<String> =
        if (isWindows()) {
            listOf("/c", "echo stdout & echo stderr 1>&2")
        } else {
            listOf("-c", "printf 'stdout\\n'; printf 'stderr\\n' >&2")
        }

    private fun slowArgs(prefix: String): List<String> =
        if (isWindows()) {
            listOf("/c", "echo $prefix & ping -n 30 127.0.0.1 >nul")
        } else {
            listOf("-c", "printf '$prefix\\n'; sleep 30")
        }

    private fun abortArgs(): List<String> =
        if (isWindows()) {
            listOf("/c", "type nul > \"%PI_EXEC_MARKER%\" & echo before-abort & ping -n 30 127.0.0.1 >nul")
        } else {
            listOf("-c", ": > \"${'$'}PI_EXEC_MARKER\"; printf 'before-abort\\n'; sleep 30")
        }

    private suspend fun awaitMarker(marker: Path) {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!Files.exists(marker) && System.nanoTime() < deadline) delay(10)
        assertTrue(Files.exists(marker), "Process did not reach its startup marker")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
}
