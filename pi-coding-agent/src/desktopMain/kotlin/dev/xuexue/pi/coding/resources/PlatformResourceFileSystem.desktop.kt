@file:Suppress("MatchingDeclarationName")

package dev.xuexue.pi.coding.resources

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

actual object PlatformResourceFileSystem : ResourceFileSystem {
    override fun resolvePath(
        path: String,
        baseDirectory: String?,
    ): String {
        val expanded = expandHome(path.trim())
        val candidate = Path.of(expanded)
        val resolved = if (candidate.isAbsolute) candidate else Path.of(baseDirectory.orEmpty()).resolve(candidate)
        return resolved.toAbsolutePath().normalize().toString()
    }

    override fun canonicalizePath(path: String): String =
        runCatching { Path.of(path).toRealPath().toString() }
            .getOrElse { resolvePath(path) }

    override fun join(
        parent: String,
        child: String,
    ): String =
        Path
            .of(parent)
            .resolve(child)
            .normalize()
            .toString()

    override fun parent(path: String): String =
        Path
            .of(path)
            .toAbsolutePath()
            .normalize()
            .parent
            ?.toString() ?: path

    override fun exists(path: String): Boolean = Files.exists(Path.of(path))

    override fun isFile(path: String): Boolean = Files.isRegularFile(Path.of(path))

    override fun isDirectory(path: String): Boolean = Files.isDirectory(Path.of(path))

    override fun readText(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    override fun listDirectory(path: String): List<ResourceDirectoryEntry> {
        val directory = Path.of(path)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { entries ->
            entries
                .map { entry ->
                    ResourceDirectoryEntry(
                        path = entry.toAbsolutePath().normalize().toString(),
                        name = entry.fileName.toString(),
                        isFile = Files.isRegularFile(entry),
                        isDirectory = Files.isDirectory(entry),
                    )
                }.toList()
        }
    }

    private fun expandHome(path: String): String =
        when {
            path == "~" -> System.getProperty("user.home")
            path.startsWith("~/") -> Path.of(System.getProperty("user.home"), path.drop(2)).toString()
            else -> path
        }
}
