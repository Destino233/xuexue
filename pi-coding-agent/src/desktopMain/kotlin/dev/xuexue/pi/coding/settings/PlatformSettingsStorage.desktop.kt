@file:Suppress("MatchingDeclarationName")

package dev.xuexue.pi.coding.settings

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

actual object PlatformSettingsStorage {
    actual fun create(
        cwd: String,
        agentDirectory: String,
    ): SettingsStorage =
        FileSettingsStorage(
            globalPath = normalize(agentDirectory).resolve("settings.json"),
            projectPath =
                normalize(cwd)
                    .resolve(".xuexue")
                    .resolve("pi")
                    .resolve("settings.json"),
        )

    actual fun normalizePath(path: String): String = normalize(path).toString()

    private fun normalize(path: String) =
        java.nio.file.Path
            .of(
                when {
                    path == "~" -> {
                        System.getProperty("user.home")
                    }

                    path.startsWith("~/") -> {
                        java.nio.file.Path
                            .of(System.getProperty("user.home"), path.drop(2))
                            .toString()
                    }

                    else -> {
                        path
                    }
                },
            ).toAbsolutePath()
            .normalize()
}

private class FileSettingsStorage(
    private val globalPath: java.nio.file.Path,
    private val projectPath: java.nio.file.Path,
) : SettingsStorage {
    private val lock = Any()

    override fun withLock(
        scope: SettingsScope,
        update: (current: String?) -> String?,
    ) {
        synchronized(lock) {
            val path = if (scope == SettingsScope.GLOBAL) globalPath else projectPath
            val current = path.takeIf(Files::exists)?.let { Files.readString(it, StandardCharsets.UTF_8) }
            val next = update(current) ?: return
            Files.createDirectories(requireNotNull(path.parent))
            Files.writeString(
                path,
                next,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }
}
