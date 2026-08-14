@file:Suppress("MatchingDeclarationName")

package dev.xuexue.pi.coding

import java.io.File
import java.util.Locale

/** Resolves the MSYS2 shell used by Windows process-backed agent features. */
internal object WindowsProcessSupport {
    private val windows = System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
    private val shell by lazy(::detectShell)

    fun shellCommand(command: String): List<String> =
        if (windows) {
            listOf(shell.executable, "-lc", command)
        } else {
            val executable = System.getenv("SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
            listOf(executable, "-lc", command)
        }

    fun processCommand(command: List<String>): List<String> {
        if (!windows || command.isEmpty()) return command
        val executable = command.first().lowercase(Locale.ROOT)
        return when {
            executable.endsWith(".sh") -> {
                listOf(shell.executable, command.first().replace('\\', '/')) + command.drop(1)
            }

            executable.endsWith(".cmd") || executable.endsWith(".bat") -> {
                listOf(
                    System.getenv("ComSpec")?.takeIf(String::isNotBlank) ?: "cmd.exe",
                    "/d",
                    "/s",
                    "/c",
                    command.joinToString(" ", transform = ::quoteWindowsArgument),
                )
            }

            else -> {
                command
            }
        }
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

    fun normalizeOutput(value: String): String = if (windows) value.replace("\r\n", "\n").replace('\r', '\n') else value

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

    private fun quoteWindowsArgument(argument: String): String =
        if (argument.isEmpty() || argument.any(Char::isWhitespace) || '"' in argument) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            argument
        }

    private data class ShellInstallation(
        val executable: String,
        val pathEntries: List<String>,
    )
}
