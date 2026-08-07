package dev.xuexue

import dev.xuexue.platform.WorkspaceService
import dev.xuexue.platform.currentJvmMemorySnapshot
import java.nio.file.Path
import kotlin.system.exitProcess

private const val MEMORY_RESTART_ENVIRONMENT = "XUEXUE_MEMORY_RESTARTED"
private const val MEMORY_RESTART_TOLERANCE_MIB = 64L

internal fun restartWithConfiguredMemory(): Boolean {
    val command = configuredMemoryRestartCommand() ?: return false
    return try {
        ProcessBuilder(command)
            .directory(Path.of(System.getProperty("user.dir", ".")).toFile())
            .inheritIO()
            .apply { environment()[MEMORY_RESTART_ENVIRONMENT] = "1" }
            .start()
        exitProcess(0)
    } catch (_: Exception) {
        false
    }
}

private fun configuredMemoryRestartCommand(): List<String>? {
    val workspaceService = WorkspaceService()
    val preferences = workspaceService.loadAppPreferences()
    val shouldRestart =
        System.getenv(MEMORY_RESTART_ENVIRONMENT) != "1" &&
            workspaceService.hasConfiguredApplicationMemoryLimit() &&
            kotlin.math.abs(currentJvmMemorySnapshot().maxMiB - preferences.applicationMemoryLimitMiB) >
            MEMORY_RESTART_TOLERANCE_MIB
    val javaHome = System.getProperty("java.home")
    val classPath = System.getProperty("java.class.path")?.takeIf(String::isNotBlank)
    val executable =
        if (System.getProperty("os.name", "").contains("win", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
    return if (shouldRestart && javaHome != null && classPath != null) {
        buildList {
            add(Path.of(javaHome, "bin", executable).toString())
            add("-Xmx${preferences.applicationMemoryLimitMiB}m")
            add("--enable-native-access=ALL-UNNAMED")
            listOf("java.library.path", "compose.application.resources.dir").forEach { propertyName ->
                System.getProperty(propertyName)?.let { value -> add("-D$propertyName=$value") }
            }
            add("-cp")
            add(classPath)
            add("dev.xuexue.MainKt")
        }
    } else {
        null
    }
}
