package dev.xuexue.platform

import dev.xuexue.core.DocumentKind
import dev.xuexue.core.IndexedNote
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

data class SearchHit(
    val relativePath: String,
    val line: Int,
    val preview: String,
    val column: Int = 1,
    val matchLength: Int = 0,
    val previewMatchStart: Int = 0,
)

data class FileMutationResult(
    val relativePath: String,
    val affectedMarkdown: List<String> = emptyList(),
    val updatedContents: Map<String, String> = emptyMap(),
)

data class RenamedEntry(
    val oldRelativePath: String,
    val newRelativePath: String,
)

data class RenameBatch(
    val entries: List<RenamedEntry>,
    val affectedMarkdown: List<String>,
    val originalContents: Map<String, String>,
    val updatedContents: Map<String, String>,
)

data class TrashedEntry(
    val original: Path,
    val trashed: Path,
)

data class TrashBatch(
    val entries: List<TrashedEntry>,
)

data class PersistedLayoutPreset(
    val name: String,
    val leftPanelVisible: Boolean,
    val agentPanelVisible: Boolean,
    val activityRailVisible: Boolean,
    val activityRailRight: Boolean,
    val toolPanelDock: String,
    val leftWidth: Float,
    val agentWidth: Float,
    val bottomPanelHeight: Float,
    val activeTool: String,
    val toolTabs: List<String>,
    val toolPanelPinned: Boolean,
    val toolPanelExpanded: Boolean,
    val agentFloating: Boolean,
    val documentSplit: String,
    val secondaryTool: String? = null,
)

data class PersistedWorkbench(
    val workspace: String? = null,
    val openDocuments: List<String> = emptyList(),
    val activeDocument: String? = null,
    val recentDocuments: List<String> = emptyList(),
    val activeTool: String? = null,
    val leftWidth: Float = 260f,
    val agentWidth: Float = 350f,
    val activityRailVisible: Boolean = true,
    val activityRailRight: Boolean = false,
    val toolPanelDock: String = "LEFT",
    val bottomPanelHeight: Float = 230f,
    val documentSplit: String = "NONE",
    val secondaryDocument: String? = null,
    val activeAgentSession: String? = null,
    val toolPanelPinned: Boolean = true,
    val toolPanelExpanded: Boolean = false,
    val detachedDocuments: List<String> = emptyList(),
    val agentFloating: Boolean = false,
    val toolTabs: List<String> = emptyList(),
    val secondaryTool: String? = null,
    val leftPanelVisible: Boolean = true,
    val agentPanelVisible: Boolean = true,
    val workspaceTrusted: Boolean = true,
    val agentReadOnlyMode: Boolean = false,
    val agentOfflineMode: Boolean = false,
    val agentAllowWrite: Boolean = true,
    val agentAllowShell: Boolean = true,
    val customLayoutPresets: List<PersistedLayoutPreset> = emptyList(),
    val editorModes: Map<String, String> = emptyMap(),
    val cursorOffsets: Map<String, Int> = emptyMap(),
    val editorBlockIndices: Map<String, Int> = emptyMap(),
    val pdfPages: Map<String, Int> = emptyMap(),
    val pdfScrollOffsets: Map<String, Int> = emptyMap(),
    val pdfHorizontalOffsets: Map<String, Int> = emptyMap(),
    val pdfVerticalOffsets: Map<String, Int> = emptyMap(),
    val pdfZooms: Map<String, Float> = emptyMap(),
    val pdfFitModes: Map<String, String> = emptyMap(),
    val pdfViewModes: Map<String, String> = emptyMap(),
    val pdfSidePanels: Map<String, String> = emptyMap(),
    val pdfRotations: Map<String, Float> = emptyMap(),
    val pdfColorModes: Map<String, String> = emptyMap(),
)

data class AppPreferences(
    val documentWheelZoomMultiplier: Float = DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER,
)

const val DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER = 1f
const val MIN_DOCUMENT_WHEEL_ZOOM_MULTIPLIER = 0.5f
const val MAX_DOCUMENT_WHEEL_ZOOM_MULTIPLIER = 2f

fun normalizedDocumentWheelZoomMultiplier(value: Float): Float =
    value
        .takeIf(Float::isFinite)
        ?.coerceIn(MIN_DOCUMENT_WHEEL_ZOOM_MULTIPLIER, MAX_DOCUMENT_WHEEL_ZOOM_MULTIPLIER)
        ?: DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER

// Function-count debt is already tracked in the static-analysis baseline; formatting alone should not duplicate it.
@Suppress("LargeClass")
class WorkspaceService(
    private val appStateDirectory: Path = Path.of(System.getProperty("user.home"), ".xuexue"),
) {
    fun canonicalRoot(root: Path): Path {
        require(Files.isDirectory(root)) { "Workspace root is not a readable directory." }
        return root.toRealPath()
    }

    fun resolveExistingInside(
        root: Path,
        relative: String,
    ): Path =
        resolveInside(root, relative).also { path ->
            require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Path is no longer available: $relative" }
        }

    fun resolveForWriteInside(
        root: Path,
        relative: String,
    ): Path = resolveInside(root, relative)

    fun scan(root: Path): WorkspaceEntry {
        val safeRoot = canonicalRoot(root)
        return scanEntry(safeRoot, safeRoot)
    }

    fun workspaceRevision(root: Path): Long {
        val safeRoot = canonicalRoot(root)
        return Files.walk(safeRoot).use { paths ->
            paths
                .filter { path -> !Files.isSymbolicLink(path) }
                .filter { path -> !path.startsWith(safeRoot.resolve(".xuexue")) }
                .sorted()
                .mapToLong { path ->
                    val relative = safeRoot.relativize(path).pathString.replace('\\', '/')
                    val modified = runCatching { Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrDefault(0L)
                    val size = runCatching { if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.size(path) else 0L }.getOrDefault(0L)
                    relative.hashCode().toLong() * 31L + modified * 17L + size
                }.reduce(1_125_899_906_842_597L) { revision, value -> revision * 31L + value }
        }
    }

    private fun scanEntry(
        root: Path,
        path: Path,
    ): WorkspaceEntry {
        require(path.startsWith(root) && !Files.isSymbolicLink(path)) { "Symbolic links are not part of the workspace tree." }
        val isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val children =
            if (isDirectory) {
                Files.list(path).use { stream ->
                    stream
                        .filter { child -> !Files.isSymbolicLink(child) }
                        .filter { child -> child.fileName.toString() != ".xuexue" }
                        .sorted(compareBy<Path>({ !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }, { it.fileName.toString().lowercase() }))
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
            isDirectory = isDirectory,
            children = children,
        )
    }

    fun readText(path: Path): String {
        require(Files.size(path) <= MAX_TEXT_BYTES) { "File is larger than the 8 MB alpha limit." }
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    fun atomicWrite(
        path: Path,
        content: String,
    ) {
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
        val safeRoot = canonicalRoot(root)
        var index = 1
        var candidate = safeRoot.resolve("Untitled.md")
        while (Files.exists(candidate)) {
            index += 1
            candidate = safeRoot.resolve("Untitled $index.md")
        }
        atomicWrite(candidate, "# ${candidate.nameWithoutExtension}\n\n")
        return candidate
    }

    fun createEntry(
        root: Path,
        parentRelative: String,
        name: String,
        directory: Boolean,
    ): Path {
        val parent = resolveInside(root, parentRelative)
        require(Files.isDirectory(parent)) { "Target folder is no longer available." }
        val cleanName = validateEntryName(name)
        val safeRoot = canonicalRoot(root)
        val target = resolveInside(safeRoot, safeRoot.relativize(parent.resolve(cleanName)).pathString)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "An entry named '$cleanName' already exists." }
        if (directory) {
            Files.createDirectory(target)
        } else {
            atomicWrite(target, if (cleanName.endsWith(".md", true)) "# ${target.nameWithoutExtension}\n\n" else "")
        }
        return target
    }

    fun markdownReferenceImpacts(
        root: Path,
        relativePath: String,
    ): List<String> {
        val safeRoot = canonicalRoot(root)
        val source = resolveInside(safeRoot, relativePath)
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.extension.equals("md", true)) return emptyList()
        val oldStem = source.nameWithoutExtension
        return Files.walk(safeRoot).use { paths ->
            paths
                .filter { path ->
                    !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.extension.equals("md", true) && !path.startsWith(safeRoot.resolve(".xuexue"))
                }.filter { path ->
                    val content = runCatching { readText(path) }.getOrDefault("")
                    MarkdownParser.analyze(content).links.any { link -> link.target.substringAfterLast('/').removeSuffix(".md") == oldStem }
                }.map { safeRoot.relativize(it).pathString.replace('\\', '/') }
                .toList()
        }
    }

    fun renameEntry(
        root: Path,
        relativePath: String,
        newName: String,
    ): FileMutationResult {
        val batch = renameEntries(root, mapOf(relativePath to newName))
        val renamed = batch.entries.single()
        return FileMutationResult(
            relativePath = renamed.newRelativePath,
            affectedMarkdown = batch.affectedMarkdown,
            updatedContents = batch.updatedContents,
        )
    }

    fun renameEntries(
        root: Path,
        renames: Map<String, String>,
    ): RenameBatch {
        val normalizedRoot = canonicalRoot(root)
        val requested =
            renames.entries.mapNotNull { (relative, name) ->
                val source = resolveInside(normalizedRoot, relative)
                require(Files.exists(source)) { "The entry is no longer available: $relative" }
                val cleanName = validateEntryName(name)
                val target = resolveInside(normalizedRoot, normalizedRoot.relativize(source.resolveSibling(cleanName)).pathString)
                if (source == target) null else Triple(relative.replace('\\', '/'), source, target)
            }
        require(requested.isNotEmpty()) { "Change at least one name." }
        val sources = requested.map { it.second }.toSet()
        require(requested.none { (_, source, _) -> sources.any { other -> other != source && source.startsWith(other) } }) {
            "Rename a folder or its contents, not both in the same batch."
        }
        val targets = requested.map { it.third }
        require(targets.distinct().size == targets.size) { "Two entries cannot use the same destination name." }
        requested.forEach { (_, _, target) ->
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || target in sources) {
                "An entry named '${target.fileName}' already exists."
            }
        }

        val stemRenames =
            requested
                .filter { (_, source, _) -> Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && source.extension.equals("md", true) }
                .groupBy({ it.second.nameWithoutExtension }, { it.third.nameWithoutExtension })
                .mapValues { (oldStem, newStems) ->
                    require(newStems.distinct().size == 1) { "Notes named '$oldStem' must use the same new base name in one batch." }
                    newStems.first()
                }
        val affected =
            if (stemRenames.isEmpty()) {
                emptyList()
            } else {
                Files.walk(normalizedRoot).use { paths ->
                    paths
                        .filter { path ->
                            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                                path.extension.equals("md", true) && !path.startsWith(normalizedRoot.resolve(".xuexue"))
                        }.filter { path ->
                            MarkdownParser.analyze(runCatching { readText(path) }.getOrDefault("")).links.any { link ->
                                link.target.substringAfterLast('/').removeSuffix(".md") in stemRenames
                            }
                        }.map { normalizedRoot.relativize(it).pathString.replace('\\', '/') }
                        .toList()
                }
            }
        val originals = affected.associateWith { path -> readText(normalizedRoot.resolve(path)) }
        val updated = originals.mapValues { (_, content) -> rewriteWikiTargets(content, stemRenames) }
        val entries =
            requested.map { (relative, _, target) ->
                RenamedEntry(relative, normalizedRoot.relativize(target).pathString.replace('\\', '/'))
            }
        try {
            moveAtomically(requested.map { it.second to it.third }, normalizedRoot)
            val finalPaths = entries.associate { it.oldRelativePath to it.newRelativePath }
            updated.forEach { (path, content) -> atomicWrite(normalizedRoot.resolve(finalPaths[path] ?: path), content) }
        } catch (error: Throwable) {
            runCatching { moveAtomically(requested.map { it.third to it.second }, normalizedRoot) }
            originals.forEach { (path, content) -> runCatching { atomicWrite(normalizedRoot.resolve(path), content) } }
            throw error
        }
        return RenameBatch(entries, affected, originals, updated)
    }

    fun undoRename(
        root: Path,
        batch: RenameBatch,
    ) {
        val normalizedRoot = canonicalRoot(root)
        moveAtomically(
            batch.entries.map { resolveInside(normalizedRoot, it.newRelativePath) to resolveInside(normalizedRoot, it.oldRelativePath) },
            normalizedRoot,
        )
        batch.originalContents.forEach { (path, content) -> atomicWrite(normalizedRoot.resolve(path), content) }
    }

    fun copyEntries(
        root: Path,
        relativePaths: List<String>,
        targetRelative: String,
    ): List<String> {
        val safeRoot = canonicalRoot(root)
        val targetDirectory = resolveInside(safeRoot, targetRelative)
        require(Files.isDirectory(targetDirectory)) { "Target folder is no longer available." }
        val copied = mutableListOf<Path>()
        try {
            return relativePaths.distinct().map { relative ->
                val source = resolveInside(safeRoot, relative)
                require(Files.exists(source)) { "Source is no longer available: $relative" }
                val target = resolveInside(safeRoot, safeRoot.relativize(targetDirectory.resolve(source.fileName)).pathString)
                require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "${source.fileName} already exists in the target folder." }
                copyRecursively(source, target)
                copied.add(target)
                safeRoot.relativize(target).pathString.replace('\\', '/')
            }
        } catch (error: Throwable) {
            copied.asReversed().forEach { runCatching { deleteRecursively(it) } }
            throw error
        }
    }

    fun moveEntries(
        root: Path,
        relativePaths: List<String>,
        targetRelative: String,
    ): List<String> {
        val safeRoot = canonicalRoot(root)
        val targetDirectory = resolveInside(safeRoot, targetRelative)
        require(Files.isDirectory(targetDirectory)) { "Target folder is no longer available." }
        val moved = mutableListOf<Pair<Path, Path>>()
        try {
            return relativePaths.distinct().map { relative ->
                val source = resolveInside(safeRoot, relative)
                val target = resolveInside(safeRoot, safeRoot.relativize(targetDirectory.resolve(source.fileName)).pathString)
                require(source != target && !target.startsWith(source)) { "A folder cannot be moved into itself." }
                require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "${source.fileName} already exists in the target folder." }
                Files.move(source, target)
                moved += source to target
                safeRoot.relativize(target).pathString.replace('\\', '/')
            }
        } catch (error: Throwable) {
            moved.asReversed().forEach { (source, target) -> runCatching { if (Files.exists(target)) Files.move(target, source) } }
            throw error
        }
    }

    fun trashEntries(
        root: Path,
        relativePaths: List<String>,
    ): TrashBatch {
        val safeRoot = canonicalRoot(root)
        val trashRoot = resolveForWriteInside(safeRoot, ".xuexue/trash/${System.currentTimeMillis()}")
        Files.createDirectories(trashRoot)
        val entries = mutableListOf<TrashedEntry>()
        try {
            relativePaths.distinct().forEach { relative ->
                val source = resolveInside(safeRoot, relative)
                require(source != safeRoot && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) { "Entry is no longer available: $relative" }
                val target = trashRoot.resolve(relative).normalize()
                Files.createDirectories(target.parent)
                Files.move(source, target)
                entries += TrashedEntry(source, target)
            }
        } catch (error: Throwable) {
            entries.asReversed().forEach { entry ->
                runCatching {
                    Files.createDirectories(entry.original.parent)
                    Files.move(entry.trashed, entry.original)
                }
            }
            throw error
        }
        return TrashBatch(entries)
    }

    fun restoreTrash(batch: TrashBatch) {
        batch.entries.sortedBy { it.original.nameCount }.forEach { entry ->
            require(!Files.exists(entry.original)) { "Cannot restore ${entry.original.fileName}: the path is occupied." }
            Files.createDirectories(entry.original.parent)
            Files.move(entry.trashed, entry.original)
        }
    }

    fun search(
        root: Path,
        query: String,
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val safeRoot = canonicalRoot(root)
        val normalized = query.trim()
        val hits = mutableListOf<SearchHit>()
        Files.walk(safeRoot).use { paths ->
            paths
                .filter { path ->
                    !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        !path.startsWith(safeRoot.resolve(".xuexue")) &&
                        kindOf(path) in setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT) &&
                        Files.size(path) <= MAX_TEXT_BYTES
                }.forEach { path ->
                    Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, line ->
                        val matchIndex = line.indexOf(normalized, ignoreCase = true)
                        if (matchIndex >= 0 && hits.size < 200) {
                            val previewStart = (matchIndex - 60).coerceAtLeast(0)
                            val previewEnd = (previewStart + 180).coerceAtMost(line.length)
                            hits +=
                                SearchHit(
                                    relativePath = safeRoot.relativize(path).pathString.replace('\\', '/'),
                                    line = index + 1,
                                    preview = line.substring(previewStart, previewEnd),
                                    column = matchIndex + 1,
                                    matchLength = normalized.length,
                                    previewMatchStart = matchIndex - previewStart,
                                )
                        }
                    }
                }
        }
        return hits
    }

    fun buildIndex(
        root: Path,
        openOverrides: Map<String, String> = emptyMap(),
    ): WorkspaceIndex {
        val safeRoot = canonicalRoot(root)
        val notes = mutableListOf<IndexedNote>()
        Files.walk(safeRoot).use { paths ->
            paths
                .filter {
                    !Files.isSymbolicLink(it) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                        !it.startsWith(safeRoot.resolve(".xuexue")) && it.extension.equals("md", ignoreCase = true)
                }.forEach { path ->
                    val relative = safeRoot.relativize(path).pathString.replace('\\', '/')
                    val content = openOverrides[relative] ?: runCatching { readText(path) }.getOrDefault("")
                    val title =
                        MarkdownParser
                            .analyze(content)
                            .headings
                            .firstOrNull()
                            ?.text
                            ?: path.nameWithoutExtension
                    notes += IndexedNote(relative, title, MarkdownParser.analyze(content))
                }
        }
        return WorkspaceIndex(notes)
    }

    fun kindOf(path: Path): DocumentKind = kindOf(path.extension)

    private fun kindOf(extension: String): DocumentKind =
        when (extension.lowercase()) {
            "md", "markdown" -> DocumentKind.MARKDOWN
            "txt", "log", "json", "yaml", "yml", "toml", "kt", "kts", "java", "js", "ts", "css", "html", "xml" -> DocumentKind.TEXT
            "pdf" -> DocumentKind.PDF
            "png", "jpg", "jpeg", "gif", "bmp", "webp" -> DocumentKind.IMAGE
            else -> DocumentKind.UNSUPPORTED
        }

    fun saveWorkbench(
        root: Path,
        state: PersistedWorkbench,
    ) {
        val safeRoot = canonicalRoot(root)
        val directory = resolveForWriteInside(safeRoot, ".xuexue")
        Files.createDirectories(directory)
        val properties =
            Properties().apply {
                setProperty("workspace", safeRoot.pathString)
                setProperty("openDocuments", state.openDocuments.joinToString(",") { encode(it) })
                setProperty("activeDocument", state.activeDocument?.let(::encode).orEmpty())
                setProperty("recentDocuments", state.recentDocuments.joinToString(",") { encode(it) })
                setProperty("activeTool", state.activeTool.orEmpty())
                setProperty("leftWidth", state.leftWidth.toString())
                setProperty("agentWidth", state.agentWidth.toString())
                setProperty("activityRailVisible", state.activityRailVisible.toString())
                setProperty("activityRailRight", state.activityRailRight.toString())
                setProperty("toolPanelDock", state.toolPanelDock)
                setProperty("bottomPanelHeight", state.bottomPanelHeight.toString())
                setProperty("documentSplit", state.documentSplit)
                setProperty("secondaryDocument", state.secondaryDocument?.let(::encode).orEmpty())
                setProperty("activeAgentSession", state.activeAgentSession.orEmpty())
                setProperty("toolPanelPinned", state.toolPanelPinned.toString())
                setProperty("toolPanelExpanded", state.toolPanelExpanded.toString())
                setProperty("detachedDocuments", state.detachedDocuments.joinToString(",") { encode(it) })
                setProperty("agentFloating", state.agentFloating.toString())
                setProperty("toolTabs", state.toolTabs.joinToString(","))
                setProperty("secondaryTool", state.secondaryTool.orEmpty())
                setProperty("leftPanelVisible", state.leftPanelVisible.toString())
                setProperty("agentPanelVisible", state.agentPanelVisible.toString())
                setProperty("workspaceTrusted", state.workspaceTrusted.toString())
                setProperty("agentReadOnlyMode", state.agentReadOnlyMode.toString())
                setProperty("agentOfflineMode", state.agentOfflineMode.toString())
                setProperty("agentAllowWrite", state.agentAllowWrite.toString())
                setProperty("agentAllowShell", state.agentAllowShell.toString())
                setProperty("layoutPreset.count", state.customLayoutPresets.size.toString())
                state.customLayoutPresets.forEachIndexed { index, preset ->
                    val prefix = "layoutPreset.$index."
                    setProperty(prefix + "name", encode(preset.name))
                    setProperty(prefix + "leftPanelVisible", preset.leftPanelVisible.toString())
                    setProperty(prefix + "agentPanelVisible", preset.agentPanelVisible.toString())
                    setProperty(prefix + "activityRailVisible", preset.activityRailVisible.toString())
                    setProperty(prefix + "activityRailRight", preset.activityRailRight.toString())
                    setProperty(prefix + "toolPanelDock", preset.toolPanelDock)
                    setProperty(prefix + "leftWidth", preset.leftWidth.toString())
                    setProperty(prefix + "agentWidth", preset.agentWidth.toString())
                    setProperty(prefix + "bottomPanelHeight", preset.bottomPanelHeight.toString())
                    setProperty(prefix + "activeTool", preset.activeTool)
                    setProperty(prefix + "toolTabs", preset.toolTabs.joinToString(","))
                    setProperty(prefix + "toolPanelPinned", preset.toolPanelPinned.toString())
                    setProperty(prefix + "toolPanelExpanded", preset.toolPanelExpanded.toString())
                    setProperty(prefix + "agentFloating", preset.agentFloating.toString())
                    setProperty(prefix + "documentSplit", preset.documentSplit)
                    setProperty(prefix + "secondaryTool", preset.secondaryTool.orEmpty())
                }
                state.editorModes.forEach { (path, mode) -> setProperty("mode.${encode(path)}", mode) }
                state.cursorOffsets.forEach { (path, offset) ->
                    setProperty("cursor.${encode(path)}", offset.toString())
                }
                state.editorBlockIndices.forEach { (path, index) ->
                    setProperty("blockIndex.${encode(path)}", index.toString())
                }
                writePdfState(state)
            }
        val target = directory.resolve("workbench.properties")
        val temp = Files.createTempFile(directory, "workbench.", ".tmp")
        Files.newOutputStream(temp).use { properties.store(it, "XueXue workspace state") }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        saveLastWorkspace(safeRoot)
    }

    private fun Properties.writePdfState(state: PersistedWorkbench) {
        state.pdfPages.forEach { (path, page) -> setProperty("page.${encode(path)}", page.toString()) }
        state.pdfScrollOffsets.forEach { (path, offset) ->
            setProperty("pdfScrollOffset.${encode(path)}", offset.toString())
        }
        state.pdfHorizontalOffsets.forEach { (path, offset) ->
            setProperty("pdfHorizontalOffset.${encode(path)}", offset.toString())
        }
        state.pdfVerticalOffsets.forEach { (path, offset) ->
            setProperty("pdfVerticalOffset.${encode(path)}", offset.toString())
        }
        state.pdfZooms.forEach { (path, zoom) -> setProperty("zoom.${encode(path)}", zoom.toString()) }
        state.pdfFitModes.forEach { (path, mode) -> setProperty("pdfFit.${encode(path)}", mode) }
        state.pdfViewModes.forEach { (path, mode) -> setProperty("pdfMode.${encode(path)}", mode) }
        state.pdfSidePanels.forEach { (path, panel) -> setProperty("pdfPanel.${encode(path)}", panel) }
        state.pdfRotations.forEach { (path, rotation) ->
            setProperty("pdfRotation.${encode(path)}", rotation.toString())
        }
        state.pdfColorModes.forEach { (path, mode) -> setProperty("pdfColor.${encode(path)}", mode) }
    }

    fun loadWorkbench(root: Path): PersistedWorkbench {
        val safeRoot = canonicalRoot(root)
        val target =
            runCatching { resolveExistingInside(safeRoot, ".xuexue/workbench.properties") }.getOrNull()
                ?: return PersistedWorkbench(workspace = safeRoot.pathString)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return PersistedWorkbench(workspace = safeRoot.pathString)
        val properties = Properties()
        Files.newInputStream(target).use(properties::load)
        val modes =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("mode.") }
                .associate { decode(it.removePrefix("mode.")) to properties.getProperty(it) }
        val pages =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("page.") }
                .associate { decode(it.removePrefix("page.")) to properties.getProperty(it).toIntOrNull().orZero() }
        val pdfScrollOffsets =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfScrollOffset.") }
                .associate {
                    decode(it.removePrefix("pdfScrollOffset.")) to properties.getProperty(it).toIntOrNull().orZero()
                }
        val pdfHorizontalOffsets =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfHorizontalOffset.") }
                .associate {
                    decode(it.removePrefix("pdfHorizontalOffset.")) to properties.getProperty(it).toIntOrNull().orZero()
                }
        val pdfVerticalOffsets =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfVerticalOffset.") }
                .associate {
                    decode(it.removePrefix("pdfVerticalOffset.")) to properties.getProperty(it).toIntOrNull().orZero()
                }
        val cursors =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("cursor.") }
                .associate { decode(it.removePrefix("cursor.")) to properties.getProperty(it).toIntOrNull().orZero() }
        val blockIndices =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("blockIndex.") }
                .associate { decode(it.removePrefix("blockIndex.")) to properties.getProperty(it).toIntOrNull().orZero() }
        val zooms =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("zoom.") }
                .associate { decode(it.removePrefix("zoom.")) to (properties.getProperty(it).toFloatOrNull() ?: 1f) }
        val pdfModes =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfMode.") }
                .associate { decode(it.removePrefix("pdfMode.")) to properties.getProperty(it) }
        val pdfFitModes =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfFit.") }
                .associate { decode(it.removePrefix("pdfFit.")) to properties.getProperty(it) }
        val pdfPanels =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfPanel.") }
                .associate { decode(it.removePrefix("pdfPanel.")) to properties.getProperty(it) }
        val pdfRotations =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfRotation.") }
                .associate { decode(it.removePrefix("pdfRotation.")) to (properties.getProperty(it).toFloatOrNull() ?: 0f) }
        val pdfColorModes =
            properties
                .stringPropertyNames()
                .filter { it.startsWith("pdfColor.") }
                .associate { decode(it.removePrefix("pdfColor.")) to properties.getProperty(it) }
        val customLayouts =
            (0 until (properties.getProperty("layoutPreset.count")?.toIntOrNull() ?: 0)).mapNotNull { index ->
                val prefix = "layoutPreset.$index."
                val encodedName = properties.getProperty(prefix + "name") ?: return@mapNotNull null
                PersistedLayoutPreset(
                    name = runCatching { decode(encodedName) }.getOrDefault(encodedName),
                    leftPanelVisible =
                        properties.getProperty(prefix + "leftPanelVisible")?.toBooleanStrictOrNull() ?: true,
                    agentPanelVisible =
                        properties.getProperty(prefix + "agentPanelVisible")?.toBooleanStrictOrNull() ?: false,
                    activityRailVisible =
                        properties.getProperty(prefix + "activityRailVisible")?.toBooleanStrictOrNull() ?: true,
                    activityRailRight =
                        properties.getProperty(prefix + "activityRailRight")?.toBooleanStrictOrNull() ?: false,
                    toolPanelDock = properties.getProperty(prefix + "toolPanelDock", "LEFT"),
                    leftWidth = properties.getProperty(prefix + "leftWidth")?.toFloatOrNull() ?: 260f,
                    agentWidth = properties.getProperty(prefix + "agentWidth")?.toFloatOrNull() ?: 350f,
                    bottomPanelHeight = properties.getProperty(prefix + "bottomPanelHeight")?.toFloatOrNull() ?: 230f,
                    activeTool = properties.getProperty(prefix + "activeTool", "FILES"),
                    toolTabs = properties.getProperty(prefix + "toolTabs", "").split(',').filter(String::isNotBlank),
                    toolPanelPinned =
                        properties.getProperty(prefix + "toolPanelPinned")?.toBooleanStrictOrNull() ?: true,
                    toolPanelExpanded = properties.getProperty(prefix + "toolPanelExpanded")?.toBooleanStrictOrNull() ?: false,
                    agentFloating = properties.getProperty(prefix + "agentFloating")?.toBooleanStrictOrNull() ?: false,
                    documentSplit = properties.getProperty(prefix + "documentSplit", "NONE"),
                    secondaryTool = properties.getProperty(prefix + "secondaryTool", "").takeIf(String::isNotBlank),
                )
            }
        return PersistedWorkbench(
            workspace = properties.getProperty("workspace"),
            openDocuments =
                properties
                    .getProperty("openDocuments", "")
                    .split(',')
                    .filter { it.isNotBlank() }
                    .map(::decode),
            activeDocument = properties.getProperty("activeDocument", "").takeIf { it.isNotBlank() }?.let(::decode),
            recentDocuments =
                properties
                    .getProperty("recentDocuments", "")
                    .split(',')
                    .filter { it.isNotBlank() }
                    .mapNotNull { encoded -> runCatching { decode(encoded) }.getOrNull() },
            activeTool = properties.getProperty("activeTool", "").takeIf { it.isNotBlank() },
            leftWidth = properties.getProperty("leftWidth")?.toFloatOrNull() ?: 260f,
            agentWidth = properties.getProperty("agentWidth")?.toFloatOrNull() ?: 350f,
            activityRailVisible = properties.getProperty("activityRailVisible")?.toBooleanStrictOrNull() ?: true,
            activityRailRight = properties.getProperty("activityRailRight")?.toBooleanStrictOrNull() ?: false,
            toolPanelDock = properties.getProperty("toolPanelDock", "LEFT"),
            bottomPanelHeight = properties.getProperty("bottomPanelHeight")?.toFloatOrNull() ?: 230f,
            documentSplit = properties.getProperty("documentSplit", "NONE"),
            secondaryDocument = properties.getProperty("secondaryDocument", "").takeIf { it.isNotBlank() }?.let(::decode),
            activeAgentSession = properties.getProperty("activeAgentSession", "").takeIf { it.isNotBlank() },
            toolPanelPinned = properties.getProperty("toolPanelPinned")?.toBooleanStrictOrNull() ?: true,
            toolPanelExpanded = properties.getProperty("toolPanelExpanded")?.toBooleanStrictOrNull() ?: false,
            detachedDocuments =
                properties
                    .getProperty("detachedDocuments", "")
                    .split(',')
                    .filter { it.isNotBlank() }
                    .map(::decode),
            agentFloating = properties.getProperty("agentFloating")?.toBooleanStrictOrNull() ?: false,
            toolTabs = properties.getProperty("toolTabs", "").split(',').filter { it.isNotBlank() },
            secondaryTool = properties.getProperty("secondaryTool", "").takeIf(String::isNotBlank),
            leftPanelVisible = properties.getProperty("leftPanelVisible")?.toBooleanStrictOrNull() ?: true,
            agentPanelVisible = properties.getProperty("agentPanelVisible")?.toBooleanStrictOrNull() ?: true,
            workspaceTrusted = properties.getProperty("workspaceTrusted")?.toBooleanStrictOrNull() ?: true,
            agentReadOnlyMode = properties.getProperty("agentReadOnlyMode")?.toBooleanStrictOrNull() ?: false,
            agentOfflineMode = properties.getProperty("agentOfflineMode")?.toBooleanStrictOrNull() ?: false,
            agentAllowWrite = properties.getProperty("agentAllowWrite")?.toBooleanStrictOrNull() ?: true,
            agentAllowShell = properties.getProperty("agentAllowShell")?.toBooleanStrictOrNull() ?: true,
            customLayoutPresets = customLayouts,
            editorModes = modes,
            cursorOffsets = cursors,
            editorBlockIndices = blockIndices,
            pdfPages = pages,
            pdfScrollOffsets = pdfScrollOffsets,
            pdfHorizontalOffsets = pdfHorizontalOffsets,
            pdfVerticalOffsets = pdfVerticalOffsets,
            pdfZooms = zooms,
            pdfFitModes = pdfFitModes,
            pdfViewModes = pdfModes,
            pdfSidePanels = pdfPanels,
            pdfRotations = pdfRotations,
            pdfColorModes = pdfColorModes,
        )
    }

    fun loadLastWorkspace(): Path? {
        val file = appStateFile()
        if (!Files.isRegularFile(file)) return null
        return runCatching { Path.of(Files.readString(file).trim()) }
            .getOrNull()
            ?.takeIf { Files.isDirectory(it) }
    }

    fun loadAppPreferences(): AppPreferences {
        val file = appPreferencesFile()
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return AppPreferences()
        return runCatching {
            val properties = Properties()
            Files.newInputStream(file).use(properties::load)
            AppPreferences(
                documentWheelZoomMultiplier =
                    normalizedDocumentWheelZoomMultiplier(
                        properties.getProperty("documentWheelZoomMultiplier")?.toFloatOrNull()
                            ?: DEFAULT_DOCUMENT_WHEEL_ZOOM_MULTIPLIER,
                    ),
            )
        }.getOrDefault(AppPreferences())
    }

    fun saveAppPreferences(preferences: AppPreferences) {
        Files.createDirectories(appStateDirectory)
        val target = appPreferencesFile()
        val temp = Files.createTempFile(appStateDirectory, "preferences.", ".tmp")
        try {
            val properties =
                Properties().apply {
                    setProperty(
                        "documentWheelZoomMultiplier",
                        normalizedDocumentWheelZoomMultiplier(preferences.documentWheelZoomMultiplier).toString(),
                    )
                }
            Files.newOutputStream(temp).use { properties.store(it, "XueXue application preferences") }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun saveLastWorkspace(root: Path) {
        val file = appStateFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, root.toAbsolutePath().normalize().pathString)
    }

    private fun appStateFile(): Path = appStateDirectory.resolve("last-workspace")

    private fun appPreferencesFile(): Path = appStateDirectory.resolve("preferences.properties")

    private fun resolveInside(
        root: Path,
        relative: String,
    ): Path {
        val normalizedRoot = canonicalRoot(root)
        val path = normalizedRoot.resolve(relative.replace('\\', '/')).normalize()
        require(path.startsWith(normalizedRoot)) { "Path escapes the workspace." }

        var current = normalizedRoot
        normalizedRoot.relativize(path).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "Symbolic links are not allowed in workspace paths." }
            }
        }

        val existing =
            generateSequence(path) { candidate ->
                candidate.parent?.takeIf { it.startsWith(normalizedRoot) }
            }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: normalizedRoot
        val realExisting = existing.toRealPath()
        require(realExisting.startsWith(normalizedRoot)) { "Path escapes the workspace through a symbolic link." }
        return path
    }

    private fun validateEntryName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean !in setOf(".", "..") && '/' !in clean && '\\' !in clean) { "Enter a valid file or folder name." }
        return clean
    }

    private fun copyRecursively(
        source: Path,
        target: Path,
    ) {
        require(!Files.isSymbolicLink(source)) { "Symbolic links cannot be copied as workspace content." }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    require(!Files.isSymbolicLink(path)) { "Symbolic links cannot be copied as workspace content." }
                    val destination = target.resolve(source.relativize(path).pathString)
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(destination)
                    } else {
                        Files.copy(path, destination)
                    }
                }
            }
        } else {
            Files.copy(source, target)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun rewriteWikiTarget(
        content: String,
        oldStem: String,
        newStem: String,
    ): String = rewriteWikiTargets(content, mapOf(oldStem to newStem))

    private fun rewriteWikiTargets(
        content: String,
        renames: Map<String, String>,
    ): String {
        val target = Regex("(!?\\[\\[)([^]#^|]+)")
        return target.replace(content) { match ->
            val raw = match.groupValues[2]
            val normalized = raw.trim()
            val oldStem = normalized.substringAfterLast('/').removeSuffix(".md")
            val newStem = renames[oldStem] ?: return@replace match.value
            val prefix = normalized.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
            val extension = if (normalized.endsWith(".md")) ".md" else ""
            match.groupValues[1] + prefix + newStem + extension
        }
    }

    private fun moveAtomically(
        moves: List<Pair<Path, Path>>,
        root: Path,
    ) {
        if (moves.isEmpty()) return
        val stagingRoot = resolveForWriteInside(root, ".xuexue/rename-staging/${UUID.randomUUID()}")
        Files.createDirectories(stagingRoot)
        val staged = mutableListOf<Triple<Path, Path, Path>>()
        try {
            moves.forEachIndexed { index, (source, target) ->
                require(Files.exists(source)) { "The entry is no longer available: ${source.fileName}" }
                val temporary = stagingRoot.resolve(index.toString())
                Files.move(source, temporary)
                staged += Triple(source, temporary, target)
            }
            staged.forEach { (_, temporary, target) ->
                Files.createDirectories(target.parent)
                Files.move(temporary, target)
            }
        } catch (error: Throwable) {
            staged.asReversed().forEach { (source, temporary, target) ->
                runCatching {
                    when {
                        Files.exists(temporary) -> Files.move(temporary, source)
                        Files.exists(target) -> Files.move(target, source)
                    }
                }
            }
            throw error
        } finally {
            runCatching { deleteRecursively(stagingRoot) }
        }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value))

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
    }
}
