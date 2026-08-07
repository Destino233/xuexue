package dev.xuexue.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryUsageTest {
    @Test
    fun snapshotReportsConsistentJvmHeapBounds() {
        val snapshot = currentJvmMemorySnapshot()

        assertTrue(snapshot.usedBytes >= 0L)
        assertTrue(snapshot.committedBytes >= snapshot.usedBytes)
        assertTrue(snapshot.maxBytes >= snapshot.committedBytes)
        assertEquals("1 MiB", formatMemoryMiB(1L))
        assertEquals("1 MiB", formatMemoryMiB(1024L * 1024L))
    }

    @Test
    fun parsesLinuxResidentMemoryFromProcStatus() {
        assertEquals(
            123L * 1024L,
            parseLinuxResidentBytes("Name:\ttest\nVmRSS:\t 123 kB\nVmPeak:\t456 kB\n"),
        )
        assertNull(parseLinuxResidentBytes("Name:\ttest\n"))
        assertEquals(87L * 1024L, parseLinuxPssBytes("Pss:\t87 kB\n"))
        assertNull(parseLinuxPssBytes("Name:\ttest\n"))
    }

    @Test
    fun readsCurrentLinuxProcessMemoryWhenProcStatusIsAvailable() {
        val linux = System.getProperty("os.name", "").contains("linux", ignoreCase = true)
        val procStatus = Path.of("/proc/self/status")
        val procRollup = Path.of("/proc/self/smaps_rollup")
        if (linux && Files.isRegularFile(procStatus)) {
            val snapshot = currentProcessMemorySnapshot()
            assertEquals(ProcessMemorySource.LINUX_PROC_STATUS, snapshot.source)
            assertTrue(snapshot.residentBytes != null && snapshot.residentBytes > 0L)
            if (Files.isRegularFile(procRollup)) {
                assertTrue(snapshot.pssBytes != null && snapshot.pssBytes > 0L)
            }
        }
    }
}
