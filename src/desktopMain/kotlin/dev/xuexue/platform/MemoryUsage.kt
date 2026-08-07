package dev.xuexue.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private const val BYTES_PER_MIB = 1024L * 1024L
private const val BYTES_PER_KIB = 1024L

enum class ProcessMemorySource(
    val description: String,
) {
    LINUX_PROC_STATUS("Linux /proc/self/status + smaps_rollup"),
    MACOS_NATIVE_RESERVED("macOS native API reserved"),
    WINDOWS_NATIVE_RESERVED("Windows native API reserved"),
    UNAVAILABLE("当前平台不可用"),
}

data class ProcessMemorySnapshot(
    val residentBytes: Long? = null,
    val pssBytes: Long? = null,
    val source: ProcessMemorySource = ProcessMemorySource.UNAVAILABLE,
) {
    val residentMiB: Long?
        get() = residentBytes?.let(::bytesToMiB)

    val pssMiB: Long?
        get() = pssBytes?.let(::bytesToMiB)
}

data class JvmMemorySnapshot(
    val usedBytes: Long,
    val committedBytes: Long,
    val maxBytes: Long,
    val processMemory: ProcessMemorySnapshot = ProcessMemorySnapshot(),
) {
    val usedMiB: Long
        get() = bytesToMiB(usedBytes)
    val committedMiB: Long
        get() = bytesToMiB(committedBytes)
    val maxMiB: Long
        get() = bytesToMiB(maxBytes)
}

fun currentJvmMemorySnapshot(): JvmMemorySnapshot {
    val runtime = Runtime.getRuntime()
    val committedBytes = runtime.totalMemory()
    return JvmMemorySnapshot(
        usedBytes = (committedBytes - runtime.freeMemory()).coerceAtLeast(0L),
        committedBytes = committedBytes,
        maxBytes = runtime.maxMemory(),
        processMemory = currentProcessMemorySnapshot(),
    )
}

fun currentProcessMemorySnapshot(): ProcessMemorySnapshot {
    val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return when {
        osName.contains("linux") -> {
            ProcessMemorySnapshot(
                residentBytes = readLinuxProcessResidentBytes(),
                pssBytes = readLinuxProcessPssBytes(),
                source = ProcessMemorySource.LINUX_PROC_STATUS,
            )
        }

        osName.contains("mac") || osName.contains("darwin") -> {
            ProcessMemorySnapshot(
                residentBytes = readMacProcessResidentBytes(),
                source = ProcessMemorySource.MACOS_NATIVE_RESERVED,
            )
        }

        osName.contains("win") -> {
            ProcessMemorySnapshot(
                residentBytes = readWindowsProcessResidentBytes(),
                source = ProcessMemorySource.WINDOWS_NATIVE_RESERVED,
            )
        }

        else -> {
            ProcessMemorySnapshot()
        }
    }
}

private fun readLinuxProcessResidentBytes(): Long? {
    val statusPath = Path.of("/proc/self/status")
    return runCatching {
        if (Files.isRegularFile(statusPath)) {
            parseLinuxResidentBytes(Files.readString(statusPath))
        } else {
            null
        }
    }.getOrNull()
}

private fun readLinuxProcessPssBytes(): Long? {
    val rollupPath = Path.of("/proc/self/smaps_rollup")
    return runCatching {
        if (Files.isRegularFile(rollupPath)) {
            parseLinuxPssBytes(Files.readString(rollupPath))
        } else {
            null
        }
    }.getOrNull()
}

internal fun parseLinuxResidentBytes(status: String): Long? = parseLinuxMemoryField(status, "VmRSS")

internal fun parseLinuxPssBytes(status: String): Long? = parseLinuxMemoryField(status, "Pss")

private fun parseLinuxMemoryField(
    status: String,
    fieldName: String,
): Long? =
    status
        .lineSequence()
        .mapNotNull { line ->
            val fields = line.split(':', limit = 2)
            if (fields.firstOrNull()?.trim() != fieldName) return@mapNotNull null
            val values = fields.getOrNull(1)?.trim()?.split(Regex("\\s+")) ?: return@mapNotNull null
            val amount = values.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            when (values.getOrNull(1)?.lowercase(Locale.ROOT)) {
                "kb" -> amount * BYTES_PER_KIB
                "b" -> amount
                else -> null
            }
        }.firstOrNull()

// Reserved for a future mach_task_info bridge; heap values remain available on macOS for now.
@Suppress("FunctionOnlyReturningConstant")
private fun readMacProcessResidentBytes(): Long? = null

// Reserved for a future GetProcessMemoryInfo bridge; heap values remain available on Windows for now.
@Suppress("FunctionOnlyReturningConstant")
private fun readWindowsProcessResidentBytes(): Long? = null

fun bytesToMiB(bytes: Long): Long {
    val nonNegative = bytes.coerceAtLeast(0L)
    return nonNegative / BYTES_PER_MIB + if (nonNegative % BYTES_PER_MIB == 0L) 0L else 1L
}

fun formatMemoryMiB(bytes: Long): String = "${bytesToMiB(bytes)} MiB"
