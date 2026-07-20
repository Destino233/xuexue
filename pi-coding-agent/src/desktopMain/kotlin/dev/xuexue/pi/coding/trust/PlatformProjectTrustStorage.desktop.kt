@file:Suppress("MatchingDeclarationName", "TooGenericExceptionCaught")

package dev.xuexue.pi.coding.trust

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

actual object PlatformProjectTrustStorage {
    actual fun create(agentDirectory: String): ProjectTrustStorage =
        FileProjectTrustStorage(
            trustPath =
                Path
                    .of(agentDirectory)
                    .toAbsolutePath()
                    .normalize()
                    .resolve("trust.json"),
        )
}

private class FileProjectTrustStorage(
    private val trustPath: Path,
) : ProjectTrustStorage {
    private val lockPath = trustPath.resolveSibling("${trustPath.fileName}.lock")
    private val processLock = Any()

    override fun <T> withLock(read: (current: String?) -> T): T =
        withFileLock {
            val content = trustPath.takeIf(Files::isRegularFile)?.let { Files.readString(it, StandardCharsets.UTF_8) }
            read(content)
        }

    override fun update(transform: (current: String?) -> String?) {
        withFileLock {
            val current = trustPath.takeIf(Files::isRegularFile)?.let { Files.readString(it, StandardCharsets.UTF_8) }
            transform(current)?.let(::writeAtomically)
        }
    }

    private fun writeAtomically(content: String) {
        val parent = requireNotNull(trustPath.parent)
        Files.createDirectories(parent)
        val temporary =
            parent.resolve(".${trustPath.fileName}.${Thread.currentThread().id}.tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporary,
                    trustPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, trustPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withFileLock(block: () -> T): T =
        synchronized(processLock) {
            Files.createDirectories(requireNotNull(lockPath.parent))
            var lastError: Throwable? = null
            repeat(MAX_LOCK_ATTEMPTS) {
                try {
                    FileChannel
                        .open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                        .use { channel ->
                            val lock = channel.lock()
                            return@withFileLock useLock(lock, block)
                        }
                } catch (error: OverlappingFileLockException) {
                    lastError = error
                    Thread.sleep(LOCK_RETRY_DELAY_MS)
                }
            }
            error(lastError?.message ?: "Failed to acquire project trust store lock: $lockPath")
        }

    private fun <T> useLock(
        lock: FileLock,
        block: () -> T,
    ): T =
        try {
            block()
        } finally {
            lock.release()
        }

    private companion object {
        const val LOCK_RETRY_DELAY_MS = 20L
        const val MAX_LOCK_ATTEMPTS = 10
    }
}
