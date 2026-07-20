package dev.xuexue.pi.coding

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalBashExecutorTest {
    @Test
    fun capturesCombinedOutputAndExitCode() =
        runTest {
            val cwd = Files.createTempDirectory("xuexue-bash-test")
            val result = LocalBashExecutor().execute("printf out; printf err >&2", cwd.toString())

            assertEquals("outerr", result.output)
            assertEquals(0, result.exitCode)
            assertFalse(result.cancelled)
            assertFalse(result.truncated)
        }

    @Test
    fun persistsFullOutputWhenTailIsTruncated() =
        runTest {
            val cwd = Files.createTempDirectory("xuexue-bash-test")
            val result = LocalBashExecutor().execute("printf '%060000d' 0", cwd.toString())

            assertTrue(result.truncated)
            val fullOutputPath = assertNotNull(result.fullOutputPath)
            assertTrue(
                Files.size(
                    java.nio.file.Path
                        .of(fullOutputPath),
                ) > result.output.length,
            )
        }
}
