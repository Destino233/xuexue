package dev.xuexue.platform

import dev.xuexue.core.DocumentKind
import dev.xuexue.core.IndexedNote
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

data class SearchHit(
    val relativePath: String,
    val line: Int,
    val preview: String,
)

data class PersistedWorkbench(
    val workspace: String? = null,
    val openDocuments: List<String> = emptyList(),
    val activeDocument: String? = null,
    val activeTool: String? = null,
    val leftWidth: Float = 260f,
    val agentWidth: Float = 350f,
    val editorModes: Map<String, String> = emptyMap(),
    val cursorOffsets: Map<String, Int> = emptyMap(),
    val pdfPages: Map<String, Int> = emptyMap(),
    val pdfZooms: Map<String, Float> = emptyMap(),
)

class WorkspaceService {
    fun scan(root: Path): WorkspaceEntry = scanEntry(root, root)

    private fun scanEntry(root: Path, path: Path): WorkspaceEntry {
        val children = if (path.isDirectory()) {
            Files.list(path).use { stream ->
                stream
                    .filter { child -> child.fileName.toString() != ".xuexue" }
                    .sorted(compareBy<Path>({ !it.isDirectory() }, { it.fileName.toString().lowercase() }))
                    .map { child -> scanEntry(root, child) }
                    .toList()
            }
        } else {
            emptyList()
        }
        val relative = if (path == root) "" else root.relativize(path).pathString.replace('\\', '/')
        return WorkspaceEntry(
            name = if (path == root) path.fileName?.toString() ?: path.pathString else path.fileName.toString(),
            relativePath = relative,
            isDirectory = path.isDirectory(),
            children = children,
        )
    }

    fun readText(path: Path): String {
        require(Files.size(path) <= MAX_TEXT_BYTES) { "File is larger than the 8 MB alpha limit." }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    fun atomicWrite(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun createMarkdown(root: Path): Path {
        var index = 1
        var candidate = root.resolve("Untitled.md")
        while (Files.exists(candidate)) {
            index += 1
            candidate = root.resolve("Untitled $index.md")
        }
        atomicWrite(candidate, "# ${candidate.nameWithoutExtension}\n\n")
        return candidate
    }

    fun search(root: Path, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val normalized = query.trim()
        val hits = mutableListOf<SearchHit>()
        Files.walk(root).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) && path.fileName.toString() != ".xuexue" &&
                    kindOf(path) in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) &&
                    Files.size(path) <= MAX_TEXT_BYTES
            }.forEach { path ->
                Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, line ->
                    if (line.contains(normalized, ignoreCase = true) && hits.size < 200) {
                        hits += SearchHit(
                            relativePath = root.relativize(path).pathString.replace('\\', '/'),
                            line = index + 1,
                            preview = line.trim().take(180),
                        )
                    }
                }
            }
        }
        return hits
    }

    fun buildIndex(root: Path, openOverrides: Map<String, String> = emptyMap()): WorkspaceIndex {
        val notes = mutableListOf<IndexedNote>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("md", ignoreCase = true) }
                .forEach { path ->
                    val relative = root.relativize(path).pathString.replace('\\', '/')
                    val content = openOverrides[relative] ?: runCatching { readText(path) }.getOrDefault("")
                    val title = MarkdownParser.analyze(content).headings.firstOrNull()?.text
                        ?: path.nameWithoutExtension
                    notes += IndexedNote(relative, title, MarkdownParser.analyze(content))
                }
        }
        return WorkspaceIndex(notes)
    }

    fun kindOf(path: Path): DocumentKind = kindOf(path.extension)

    private fun kindOf(extension: String): DocumentKind = when (extension.lowercase()) {
        "md", "markdown" -> DocumentKind.MARKDOWN
        "txt", "log", "json", "yaml", "yml", "toml", "kt", "kts", "java", "js", "ts", "css", "html", "xml" -> DocumentKind.TEXT
        "pdf" -> DocumentKind.PDF
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> DocumentKind.IMAGE
        else -> DocumentKind.UNSUPPORTED
    }

    fun saveWorkbench(root: Path, state: PersistedWorkbench) {
        val directory = root.resolve(".xuexue")
        Files.createDirectories(directory)
        val properties = Properties().apply {
            setProperty("workspace", root.toAbsolutePath().normalize().pathString)
            setProperty("openDocuments", state.openDocuments.joinToString(",") { encode(it) })
            setProperty("activeDocument", state.activeDocument?.let(::encode).orEmpty())
            setProperty("activeTool", state.activeTool.orEmpty())
            setProperty("leftWidth", state.leftWidth.toString())
            setProperty("agentWidth", state.agentWidth.toString())
            state.editorModes.forEach { (path, mode) -> setProperty("mode.${encode(path)}", mode) }
            state.cursorOffsets.forEach { (path, offset) -> setProperty("cursor.${encode(path)}", offset.toString()) }
            state.pdfPages.forEach { (path, page) -> setProperty("page.${encode(path)}", page.toString()) }
            state.pdfZooms.forEach { (path, zoom) -> setProperty("zoom.${encode(path)}", zoom.toString()) }
        }
        val target = directory.resolve("workbench.properties")
        val temp = Files.createTempFile(directory, "workbench.", ".tmp")
        Files.newOutputStream(temp).use { properties.store(it, "XueXue workspace state") }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        saveLastWorkspace(root)
    }

    fun loadWorkbench(root: Path): PersistedWorkbench {
        val target = root.resolve(".xuexue/workbench.properties")
        if (!Files.isRegularFile(target)) return PersistedWorkbench(workspace = root.pathString)
        val properties = Properties()
        Files.newInputStream(target).use(properties::load)
        val modes = properties.stringPropertyNames()
            .filter { it.startsWith("mode.") }
            .associate { decode(it.removePrefix("mode.")) to properties.getProperty(it) }
        val pages = properties.stringPropertyNames()
            .filter { it.startsWith("page.") }
            .associate { decode(it.removePrefix("page.")) to properties.getProperty(it).toIntOrNull().orZero() }
        val cursors = properties.stringPropertyNames()
            .filter { it.startsWith("cursor.") }
            .associate { decode(it.removePrefix("cursor.")) to properties.getProperty(it).toIntOrNull().orZero() }
        val zooms = properties.stringPropertyNames()
            .filter { it.startsWith("zoom.") }
            .associate { decode(it.removePrefix("zoom.")) to (properties.getProperty(it).toFloatOrNull() ?: 1f) }
        return PersistedWorkbench(
            workspace = properties.getProperty("workspace"),
            openDocuments = properties.getProperty("openDocuments", "")
                .split(',').filter { it.isNotBlank() }.map(::decode),
            activeDocument = properties.getProperty("activeDocument", "").takeIf { it.isNotBlank() }?.let(::decode),
            activeTool = properties.getProperty("activeTool", "").takeIf { it.isNotBlank() },
            leftWidth = properties.getProperty("leftWidth")?.toFloatOrNull() ?: 260f,
            agentWidth = properties.getProperty("agentWidth")?.toFloatOrNull() ?: 350f,
            editorModes = modes,
            cursorOffsets = cursors,
            pdfPages = pages,
            pdfZooms = zooms,
        )
    }

    fun loadLastWorkspace(): Path? {
        val file = appStateFile()
        if (!Files.isRegularFile(file)) return null
        return runCatching { Path.of(Files.readString(file).trim()) }
            .getOrNull()
            ?.takeIf { Files.isDirectory(it) }
    }

    private fun saveLastWorkspace(root: Path) {
        val file = appStateFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, root.toAbsolutePath().normalize().pathString)
    }

    private fun appStateFile(): Path = Path.of(System.getProperty("user.home"), ".xuexue", "last-workspace")
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value))
    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
    }
}
