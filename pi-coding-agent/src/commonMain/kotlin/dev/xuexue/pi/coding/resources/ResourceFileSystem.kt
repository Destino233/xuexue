package dev.xuexue.pi.coding.resources

data class ResourceDirectoryEntry(
    val path: String,
    val name: String,
    val isFile: Boolean,
    val isDirectory: Boolean,
)

interface ResourceFileSystem {
    fun resolvePath(
        path: String,
        baseDirectory: String? = null,
    ): String

    fun canonicalizePath(path: String): String

    fun join(
        parent: String,
        child: String,
    ): String

    fun parent(path: String): String

    fun exists(path: String): Boolean

    fun isFile(path: String): Boolean

    fun isDirectory(path: String): Boolean

    fun readText(path: String): String

    fun listDirectory(path: String): List<ResourceDirectoryEntry>
}

expect object PlatformResourceFileSystem : ResourceFileSystem
