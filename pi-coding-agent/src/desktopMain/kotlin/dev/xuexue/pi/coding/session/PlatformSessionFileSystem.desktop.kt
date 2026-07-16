@file:Suppress("MatchingDeclarationName")

package dev.xuexue.pi.coding.session

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Suppress("TooManyFunctions")
actual object PlatformSessionFileSystem : SessionFileSystem {
    override val currentWorkingDirectory: String =
        Path
            .of("")
            .toAbsolutePath()
            .normalize()
            .toString()
    override val defaultAgentDirectory: String =
        Path
            .of(System.getProperty("user.home"), ".pi", "agent")
            .toAbsolutePath()
            .normalize()
            .toString()

    override fun resolvePath(path: String): String =
        path
            .toPath()
            .toAbsolutePath()
            .normalize()
            .toString()

    override fun normalizePath(path: String): String = path.toPath().normalize().toString()

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
        path
            .toPath()
            .toAbsolutePath()
            .normalize()
            .parent
            ?.toString() ?: resolvePath(path)

    override fun exists(path: String): Boolean = Files.exists(path.toPath())

    override fun isDirectory(path: String): Boolean = Files.isDirectory(path.toPath())

    override fun fileSize(path: String): Long = Files.size(path.toPath())

    override fun createDirectories(path: String) {
        Files.createDirectories(path.toPath())
    }

    override fun readLines(
        path: String,
        consumer: (String) -> Unit,
    ) {
        Files.newBufferedReader(path.toPath(), StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                consumer(line)
            }
        }
    }

    override fun readFirstLine(
        path: String,
        maxBytes: Int,
    ): String? {
        Files.newInputStream(path.toPath()).use { input ->
            val bytes = ByteArray(maxBytes)
            var length = 0
            while (length < bytes.size) {
                val next = input.read()
                if (next < 0 || next == '\n'.code) break
                bytes[length++] = next.toByte()
            }
            return if (length == 0) null else bytes.decodeToString(0, length)
        }
    }

    override fun writeLines(
        path: String,
        lines: List<String>,
        createNew: Boolean,
    ) {
        val writer =
            if (createNew) {
                Files.newBufferedWriter(
                    path.toPath(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
            } else {
                Files.newBufferedWriter(
                    path.toPath(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
        writer.use {
            lines.forEach { line ->
                it.write(line)
                it.newLine()
            }
        }
    }

    override fun appendLine(
        path: String,
        line: String,
    ) {
        Files
            .newBufferedWriter(
                path.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            ).use { writer ->
                writer.write(line)
                writer.newLine()
            }
    }

    override fun listFiles(directory: String): List<SessionFileMetadata> {
        val path = directory.toPath()
        if (!Files.isDirectory(path)) return emptyList()
        return Files.list(path).use { entries ->
            entries
                .filter(Files::isRegularFile)
                .map { file -> SessionFileMetadata(file.toString(), Files.getLastModifiedTime(file).toMillis()) }
                .toList()
        }
    }

    override fun listDirectories(directory: String): List<String> {
        val path = directory.toPath()
        if (!Files.isDirectory(path)) return emptyList()
        return Files.list(path).use { entries ->
            entries.filter(Files::isDirectory).map(Path::toString).toList()
        }
    }

    private fun String.toPath(): Path {
        if (startsWith("file://")) return Path.of(URI.create(this))
        val expanded =
            when {
                this == "~" -> System.getProperty("user.home")
                startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(drop(2)).toString()
                else -> this
            }
        return Path.of(expanded)
    }
}
