package dev.xuexue.pi.coding.exec

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
}
