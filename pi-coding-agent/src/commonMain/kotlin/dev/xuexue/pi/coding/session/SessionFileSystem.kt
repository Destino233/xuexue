package dev.xuexue.pi.coding.session

data class SessionFileMetadata(
    val path: String,
    val modifiedAtMillis: Long,
)

// Mirrors the single filesystem capability used by Pi's SessionManager.
@Suppress("TooManyFunctions")
interface SessionFileSystem {
    val currentWorkingDirectory: String
    val defaultAgentDirectory: String

    fun resolvePath(path: String): String

    fun normalizePath(path: String): String

    fun join(
        parent: String,
        child: String,
    ): String

    fun parent(path: String): String

    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    fun fileSize(path: String): Long

    fun createDirectories(path: String)

    fun readLines(
        path: String,
        consumer: (String) -> Unit,
    )

    fun readFirstLine(
        path: String,
        maxBytes: Int,
    ): String?

    fun writeLines(
        path: String,
        lines: List<String>,
        createNew: Boolean = false,
    )

    fun appendLine(
        path: String,
        line: String,
    )

    fun listFiles(directory: String): List<SessionFileMetadata>

    fun listDirectories(directory: String): List<String>
}

expect object PlatformSessionFileSystem : SessionFileSystem
