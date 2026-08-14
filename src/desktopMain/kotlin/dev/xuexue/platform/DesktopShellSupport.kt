package dev.xuexue.platform

import java.io.File
import java.util.Locale

/** Resolves the shell used by desktop workspace tools on each host platform. */
internal object DesktopShellSupport {
    private val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
    private val shell by lazy(::detectShell)

    fun command(command: String): List<String> =
        if (windows) {
            listOf(shell.executable, "-lc", command)
        } else {
            val executable = System.getenv("SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
            listOf(executable, "-lc", command)
        }

    fun configure(builder: ProcessBuilder) {
        if (!windows) return
        val environment = builder.environment()
        val inherited = environment["PATH"].orEmpty().split(File.pathSeparator)
        environment["PATH"] =
            (shell.pathEntries + inherited)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(File.pathSeparator)
    }

    private fun detectShell(): ShellInstallation {
        val roots =
            listOfNotNull(
                System.getenv("MSYS2_HOME")?.takeIf(String::isNotBlank)?.let(::File),
                File("C:/msys64"),
                File("C:/msys2"),
                File("C:/Program Files/Git"),
            )
        val candidates =
            roots.flatMap { root ->
                listOf(
                    File(root, "usr/bin/bash.exe"),
                    File(root, "bin/bash.exe"),
                )
            }
        val executable = candidates.firstOrNull(File::isFile)?.absolutePath ?: "bash"
        val shellDirectory = File(executable).parentFile
        val root =
            shellDirectory?.let { directory ->
                if (directory.name.equals("bin", ignoreCase = true) &&
                    directory.parentFile?.name.equals("usr", ignoreCase = true)
                ) {
                    directory.parentFile?.parentFile
                } else {
                    directory.parentFile
                }
            }
        val pathEntries =
            listOfNotNull(
                shellDirectory,
                root?.resolve("mingw64/bin"),
                root?.resolve("bin"),
                root?.resolve("usr/bin"),
            ).filter(File::isDirectory).map(File::getAbsolutePath)
        return ShellInstallation(executable, pathEntries)
    }

    private data class ShellInstallation(
        val executable: String,
        val pathEntries: List<String>,
    )
}
