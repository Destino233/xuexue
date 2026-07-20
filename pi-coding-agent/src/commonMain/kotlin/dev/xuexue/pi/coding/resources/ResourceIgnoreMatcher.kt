package dev.xuexue.pi.coding.resources

/** Gitignore-shaped matcher used by Pi resource directory discovery. */
internal class ResourceIgnoreMatcher(
    private val fileSystem: ResourceFileSystem,
    private val rootDirectory: String,
) {
    private val rules = mutableListOf<IgnoreRule>()

    fun addRules(directory: String) {
        val relativeDirectory = relativePath(rootDirectory, directory)
        val prefix = if (relativeDirectory.isEmpty()) "" else "$relativeDirectory/"
        IGNORE_FILE_NAMES.forEach { fileName ->
            val path = fileSystem.join(directory, fileName)
            if (!fileSystem.isFile(path)) return@forEach
            runCatching { fileSystem.readText(path) }
                .getOrNull()
                ?.lineSequence()
                ?.mapNotNull { line -> prefixIgnorePattern(line, prefix) }
                ?.forEach { pattern -> rules += IgnoreRule(pattern) }
        }
    }

    fun ignores(path: String): Boolean {
        val normalized = path.trim('/').replace('\\', '/')
        if (normalized.isEmpty()) return false
        var ignored = false
        rules.forEach { rule ->
            if (rule.matches(normalized)) ignored = !rule.negated
        }
        return ignored
    }

    private fun relativePath(
        root: String,
        path: String,
    ): String {
        val normalizedRoot = fileSystem.canonicalizePath(root).trimEnd('/')
        val normalizedPath = fileSystem.canonicalizePath(path).trimEnd('/')
        return when {
            normalizedPath == normalizedRoot -> ""
            normalizedPath.startsWith("$normalizedRoot/") -> normalizedPath.removePrefix("$normalizedRoot/")
            else -> normalizedPath
        }
    }

    private data class IgnoreRule(
        val pattern: String,
    ) {
        val negated: Boolean = pattern.startsWith('!')
        private val regex: Regex = compileGlob(if (negated) pattern.drop(1) else pattern)

        fun matches(path: String): Boolean = regex.matches(path)
    }

    private companion object {
        val IGNORE_FILE_NAMES = listOf(".gitignore", ".ignore", ".fdignore")

        fun prefixIgnorePattern(
            line: String,
            prefix: String,
        ): String? {
            val trimmed = line.trim()
            return when {
                trimmed.isEmpty() -> {
                    null
                }

                trimmed.startsWith('#') && !trimmed.startsWith("\\#") -> {
                    null
                }

                else -> {
                    var pattern = line.trimStart()
                    var negated = false
                    if (pattern.startsWith('!')) {
                        negated = true
                        pattern = pattern.drop(1)
                    } else if (pattern.startsWith("\\!")) {
                        pattern = pattern.drop(1)
                    }
                    if (pattern.startsWith('/')) pattern = pattern.drop(1)
                    pattern = pattern.replace("\\#", "#").trimEnd()
                    pattern.takeIf(String::isNotEmpty)?.let {
                        val prefixed = prefix + it
                        if (negated) "!$prefixed" else prefixed
                    }
                }
            }
        }

        fun compileGlob(pattern: String): Regex {
            val normalized = pattern.trim('/').replace('\\', '/')
            val hasSlash = '/' in normalized
            val body =
                buildString {
                    var index = 0
                    while (index < normalized.length) {
                        val character = normalized[index]
                        if (character == '*' && index + 1 < normalized.length && normalized[index + 1] == '*') {
                            append(".*")
                            index += 2
                        } else {
                            when (character) {
                                '*' -> append("[^/]*")
                                '?' -> append("[^/]")
                                else -> append(Regex.escape(character.toString()))
                            }
                            index++
                        }
                    }
                }
            val suffix = "(?:/.*)?"
            return if (hasSlash) {
                Regex("^$body$suffix$")
            } else {
                Regex("(?:^|.*/)$body$suffix")
            }
        }
    }
}
