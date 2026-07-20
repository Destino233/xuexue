@file:Suppress("TooManyFunctions")

package dev.xuexue.platform

import dev.xuexue.core.ToolTruncatedBy
import dev.xuexue.core.ToolTruncationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer

private val UNICODE_SPACES = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")

internal const val PI_TOOL_MAX_BYTES = 50 * 1024
internal const val PI_TOOL_MAX_LINES = 2_000
internal const val PI_GREP_MAX_LINE_LENGTH = 500

internal fun piToolSchema(
    properties: LinkedHashMap<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
        )
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }

internal fun piStringSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

internal fun piNumberSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
    }

internal fun piBooleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

internal fun piTruncationDetails(result: ToolTruncationResult): JsonObject =
    buildJsonObject {
        put("truncated", result.truncated)
        put("truncatedBy", result.truncatedBy?.wireName())
        put("totalLines", result.totalLines)
        put("totalBytes", result.totalBytes)
        put("outputLines", result.outputLines)
        put("outputBytes", result.outputBytes)
        put("lastLinePartial", result.lastLinePartial)
        put("firstLineExceedsLimit", result.firstLineExceedsLimit)
        put("maxLines", result.maxLines)
        put("maxBytes", result.maxBytes)
    }

private fun ToolTruncatedBy.wireName(): String = name.lowercase()

internal fun piDetails(
    truncation: ToolTruncationResult? = null,
    extra: JsonObject? = null,
): JsonObject =
    buildJsonObject {
        truncation?.let { put("truncation", piTruncationDetails(it)) }
        extra?.forEach { (key, value) -> put(key, value) }
    }

internal fun piFormatSize(bytes: Int): String =
    when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${"%.1f".format(java.util.Locale.ROOT, bytes / 1024.0)}KB"
        else -> "${"%.1f".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))}MB"
    }

@Suppress("ReturnCount")
internal fun normalizePiPathInput(input: String): String {
    var normalized = input.trim().replace(UNICODE_SPACES, " ")
    if (normalized.startsWith('@')) normalized = normalized.drop(1)
    if (normalized == "~") return userHome()
    if (normalized.startsWith("~/")) return Path.of(userHome(), normalized.substring(2)).toString()
    if (normalized.startsWith("file://")) {
        return runCatching { Path.of(java.net.URI(normalized)).toString() }.getOrDefault(normalized)
    }
    return normalized
}

internal fun piPathVariants(path: String): List<String> {
    val variants = linkedSetOf(path)
    variants +=
        Regex(" (AM|PM)\\.", RegexOption.IGNORE_CASE).replace(path) { match ->
            "\u202f${match.groupValues[1]}."
        }
    variants += Normalizer.normalize(path, Normalizer.Form.NFD)
    variants += path.replace('\'', '\u2019')
    variants += Normalizer.normalize(path.replace('\'', '\u2019'), Normalizer.Form.NFD)
    return variants.toList()
}

private fun userHome(): String = System.getProperty("user.home") ?: "."

/** A small JVM-side gitignore-shaped matcher used by the headless file tools. */
internal class PiIgnoreIndex(
    private val root: Path,
) {
    private val rules = mutableListOf<IgnoreRule>()
    private val repositoryRoots = mutableListOf<Path>()

    init {
        load()
    }

    @Suppress("ReturnCount")
    fun ignores(path: Path): Boolean {
        if (path == root) return false
        if (isAlwaysIgnored(path)) return true
        val repositoryRoot = nestedRepositoryRoot(path)
        var ignored = false
        rules.forEach { rule ->
            if (!rule.appliesInside(repositoryRoot)) return@forEach
            if (rule.matches(path)) ignored = !rule.negated
        }
        return ignored
    }

    /**
     * An ignored directory can be skipped only when no applicable negation can re-include a child.
     * This preserves Git's last-matching-rule behavior while avoiding needless walks of normal
     * ignored trees.
     */
    fun shouldPrune(path: Path): Boolean =
        when {
            isAlwaysIgnored(path) -> {
                true
            }

            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !ignores(path) -> {
                false
            }

            else -> {
                val repositoryRoot = nestedRepositoryRoot(path)
                rules.none { rule -> rule.canReincludeDescendant(path, repositoryRoot) }
            }
        }

    private fun isAlwaysIgnored(path: Path): Boolean = path.any(::isAlwaysIgnoredDirectoryName)

    private fun isAlwaysIgnoredDirectoryName(pathPart: Path): Boolean {
        val directoryName = pathPart.fileName.toString()
        return directoryName in ALWAYS_IGNORED_DIRECTORY_NAMES
    }

    private fun nestedRepositoryRoot(path: Path): Path? =
        repositoryRoots
            .filter { path.startsWith(it) }
            .maxByOrNull(Path::getNameCount)

    private fun load() {
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (directory != root && directory.fileName.toString() in ALWAYS_IGNORED_DIRECTORY_NAMES) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (directory != root && Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                            repositoryRoots.add(directory)
                        }
                        IgnoreSource.entries.forEach { source ->
                            val ignoreFile = directory.resolve(source.fileName)
                            if (Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)) {
                                readRules(directory, ignoreFile, source)
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exception: java.io.IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
    }

    private fun readRules(
        scope: Path,
        file: Path,
        source: IgnoreSource,
    ) {
        runCatching { Files.readString(file) }
            .getOrNull()
            ?.lineSequence()
            ?.mapNotNull { line -> parseRule(scope, line, source) }
            ?.forEach(rules::add)
    }

    @Suppress("ReturnCount")
    private fun parseRule(
        scope: Path,
        line: String,
        source: IgnoreSource,
    ): IgnoreRule? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || (trimmed.startsWith('#') && !trimmed.startsWith("\\#"))) return null
        var pattern = line.trimStart()
        val negated = pattern.startsWith('!')
        pattern =
            when {
                negated -> pattern.drop(1)
                pattern.startsWith("\\!") -> pattern.drop(1)
                else -> pattern
            }
        pattern = pattern.replace("\\#", "#").trimEnd()
        val anchored = pattern.startsWith('/')
        if (anchored) pattern = pattern.drop(1)
        if (pattern.isEmpty()) return null
        val directoryOnly = pattern.endsWith('/')
        if (directoryOnly) pattern = pattern.dropLast(1)
        if (pattern.isEmpty()) return null
        return IgnoreRule(scope, pattern, negated, source, anchored, directoryOnly)
    }

    private data class IgnoreRule(
        val scope: Path,
        val pattern: String,
        val negated: Boolean,
        val source: IgnoreSource,
        val anchored: Boolean,
        val directoryOnly: Boolean,
    ) {
        private val regex = compileGlob(pattern)
        private val pathPattern = anchored || '/' in pattern

        fun matches(path: Path): Boolean =
            path
                .takeIf { it.startsWith(scope) }
                ?.let {
                    val pathIsDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    val parts =
                        scope
                            .relativize(path)
                            .iterator()
                            .asSequence()
                            .map(Path::toString)
                            .toList()
                    parts.indices.any { index ->
                        val relative = parts.take(index + 1).joinToString("/")
                        val candidateIsDirectory = index < parts.lastIndex || pathIsDirectory
                        (!directoryOnly || candidateIsDirectory) &&
                            regex.matches(if (pathPattern) relative else parts[index])
                    }
                }
                ?: false

        fun appliesInside(repositoryRoot: Path?): Boolean {
            val isParentGitIgnoreRule = source == IgnoreSource.GIT_IGNORE && repositoryRoot != null
            val isRuleInsideNestedRepository = repositoryRoot?.let(scope::startsWith) == true
            return !isParentGitIgnoreRule || isRuleInsideNestedRepository
        }

        fun canReincludeDescendant(
            path: Path,
            repositoryRoot: Path?,
        ): Boolean =
            negated &&
                appliesInside(repositoryRoot) &&
                (path.startsWith(scope) || scope.startsWith(path))
    }

    private enum class IgnoreSource(
        val fileName: String,
    ) {
        GIT_IGNORE(".gitignore"),
        IGNORE(".ignore"),
        FD_IGNORE(".fdignore"),
    }

    private companion object {
        val ALWAYS_IGNORED_DIRECTORY_NAMES = setOf(".git", "node_modules")
    }
}

internal fun Path.toPosixString(): String = toString().replace('\\', '/')

internal fun compilePiGlob(pattern: String): Regex = compileGlob(pattern)

private fun compileGlob(pattern: String): Regex {
    val normalized = pattern.trim('/').replace('\\', '/')
    val result = StringBuilder("^")
    var index = 0
    while (index < normalized.length) {
        val character = normalized[index]
        when {
            character == '*' && index + 1 < normalized.length && normalized[index + 1] == '*' -> {
                if (index + 2 < normalized.length && normalized[index + 2] == '/') {
                    result.append("(?:.*/)?")
                    index += 3
                } else {
                    result.append(".*")
                    index += 2
                }
            }

            character == '*' -> {
                result.append("[^/]*")
                index++
            }

            character == '?' -> {
                result.append("[^/]")
                index++
            }

            character == '[' -> {
                val end = normalized.indexOf(']', index + 1)
                if (end > index + 1) {
                    val classBody = normalized.substring(index + 1, end)
                    result
                        .append('[')
                        .append(if (classBody.startsWith('!')) "^${classBody.drop(1)}" else classBody)
                        .append(']')
                    index = end + 1
                } else {
                    result.append("\\[")
                    index++
                }
            }

            else -> {
                result.append(Regex.escape(character.toString()))
                index++
            }
        }
    }
    return Regex(result.append('$').toString())
}

internal fun Path.relativeToPosix(root: Path): String = root.relativize(this).toPosixString()
