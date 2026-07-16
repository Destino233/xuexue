package dev.xuexue.pi.coding.resources

class ResourceTestFileSystem(
    files: Map<String, String>,
) : ResourceFileSystem {
    private val files = files.mapKeys { (path, _) -> normalize(path) }.toMutableMap()

    fun replace(
        path: String,
        content: String,
    ) {
        files[normalize(path)] = content
    }

    override fun resolvePath(
        path: String,
        baseDirectory: String?,
    ): String =
        if (path.startsWith('/')) {
            normalize(path)
        } else {
            normalize("${baseDirectory ?: "/"}/$path")
        }

    override fun canonicalizePath(path: String): String = normalize(path)

    override fun join(
        parent: String,
        child: String,
    ): String = normalize("$parent/$child")

    override fun parent(path: String): String = normalize(path).substringBeforeLast('/', "/").ifEmpty { "/" }

    override fun exists(path: String): Boolean = isFile(path) || isDirectory(path)

    override fun isFile(path: String): Boolean = normalize(path) in files

    override fun isDirectory(path: String): Boolean {
        val directory = normalize(path).trimEnd('/') + "/"
        return files.keys.any { it.startsWith(directory) }
    }

    override fun readText(path: String): String = files[normalize(path)] ?: error("Missing file: $path")

    override fun listDirectory(path: String): List<ResourceDirectoryEntry> {
        val directory = normalize(path).trimEnd('/')
        val prefix = if (directory == "/") "/" else "$directory/"
        val children = linkedMapOf<String, Boolean>()
        files.keys.filter { it.startsWith(prefix) }.forEach { file ->
            val remainder = file.removePrefix(prefix)
            val name = remainder.substringBefore('/')
            children[name] = '/' in remainder
        }
        return children.map { (name, directoryChild) ->
            ResourceDirectoryEntry(
                path = join(directory, name),
                name = name,
                isFile = !directoryChild,
                isDirectory = directoryChild,
            )
        }
    }

    private fun normalize(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "",
                ".",
                -> Unit

                ".." -> if (segments.isNotEmpty()) segments.removeLast()

                else -> segments += segment
            }
        }
        return "/${segments.joinToString("/")}".removeSuffix("/").ifEmpty { "/" }
    }
}
