package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xuexue.platform.RenameBatch
import dev.xuexue.platform.RenamedEntry
import dev.xuexue.platform.TrashBatch
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.pathString

private sealed interface FileOperationUndo {
    data class Trash(
        val batch: TrashBatch,
    ) : FileOperationUndo

    data class Rename(
        val batch: RenameBatch,
    ) : FileOperationUndo
}

internal class WorkbenchFileController(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val documents: () -> List<DocumentTab>,
    private val reloadWorkspace: suspend () -> Unit,
    private val openRelative: (String, Boolean) -> Unit,
    private val closeTab: (DocumentTab) -> Unit,
    private val applyPathRenames: (List<RenamedEntry>, Map<String, String>) -> Unit,
    private val persistWorkbench: () -> Unit,
    private val onError: (String) -> Unit,
) {
    var message by mutableStateOf<String?>(null)
    var renameImpactPaths by mutableStateOf<List<String>>(emptyList())
        private set
    var renameImpactLoading by mutableStateOf(false)
        private set

    private var lastUndo: FileOperationUndo? = null
    val canUndo: Boolean get() = lastUndo != null

    fun createMarkdown() {
        val root = workspaceRoot() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.createMarkdown(root) } }
                .onSuccess { path ->
                    reloadWorkspace()
                    openRelative(root.relativize(path).pathString.replace('\\', '/'), true)
                }.onFailure { onError(it.message ?: "Could not create the note.") }
        }
    }

    fun createEntry(
        parentRelative: String,
        name: String,
        directory: Boolean,
    ) {
        val root = workspaceRoot() ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { workspaceService.createEntry(root, parentRelative, name, directory) }
            }.onSuccess { path ->
                reloadWorkspace()
                message = if (directory) "Folder created" else "File created"
                if (!directory) openRelative(root.relativize(path).pathString.replace('\\', '/'), true)
            }.onFailure { onError(it.message ?: "Could not create the entry.") }
        }
    }

    fun inspectRenameImpact(relativePath: String) {
        val root = workspaceRoot() ?: return
        renameImpactLoading = true
        renameImpactPaths = emptyList()
        scope.launch {
            renameImpactPaths =
                runCatching {
                    withContext(Dispatchers.IO) { workspaceService.markdownReferenceImpacts(root, relativePath) }
                }.getOrDefault(emptyList())
            renameImpactLoading = false
        }
    }

    fun renameEntries(renames: Map<String, String>) {
        val root = workspaceRoot() ?: return
        val sources = topLevelSelection(renames.keys.toList())
        val requested = renames.filterKeys { it in sources }
        if (requested.isEmpty()) return
        scope.launch {
            runCatching {
                val impacts =
                    withContext(Dispatchers.IO) {
                        requested.keys.flatMap { workspaceService.markdownReferenceImpacts(root, it) }.distinct()
                    }
                require(documents().none { tab -> tab.dirty && impacts.contains(tab.relativePath) }) {
                    "Save affected open notes before renaming these entries."
                }
                withContext(Dispatchers.IO) { workspaceService.renameEntries(root, requested) }
            }.onSuccess { batch ->
                applyPathRenames(batch.entries, batch.updatedContents)
                lastUndo = FileOperationUndo.Rename(batch)
                reloadWorkspace()
                message =
                    if (batch.affectedMarkdown.isEmpty()) {
                        "Renamed ${batch.entries.size} item(s)"
                    } else {
                        "Renamed ${batch.entries.size} item(s) and updated ${batch.affectedMarkdown.size} linked note(s)"
                    }
                persistWorkbench()
            }.onFailure { onError(it.message ?: "Batch rename failed.") }
        }
    }

    fun copyEntries(
        relativePaths: List<String>,
        targetDirectory: String,
    ) {
        val root = workspaceRoot() ?: return
        val sources = topLevelSelection(relativePaths)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.copyEntries(root, sources, targetDirectory) } }
                .onSuccess {
                    reloadWorkspace()
                    message = "Copied ${it.size} item(s)"
                }.onFailure { onError(it.message ?: "Copy failed.") }
        }
    }

    fun moveEntries(
        relativePaths: List<String>,
        targetDirectory: String,
    ) {
        val root = workspaceRoot() ?: return
        val sources = topLevelSelection(relativePaths)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.moveEntries(root, sources, targetDirectory) } }
                .onSuccess { targets ->
                    applyPathRenames(sources.zip(targets).map { (old, new) -> RenamedEntry(old, new) }, emptyMap())
                    reloadWorkspace()
                    message = "Moved ${targets.size} item(s)"
                    persistWorkbench()
                }.onFailure { onError(it.message ?: "Move failed.") }
        }
    }

    fun deleteEntries(relativePaths: List<String>) {
        val root = workspaceRoot() ?: return
        val sources = topLevelSelection(relativePaths)
        if (sources.isEmpty()) return
        val affectedDocuments = documents().filter { tab -> sources.any { source -> tab.isUnder(source) } }
        if (affectedDocuments.any(DocumentTab::dirty)) {
            onError("Save or close modified documents before deleting them.")
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { workspaceService.trashEntries(root, sources) } }
                .onSuccess { batch ->
                    lastUndo = FileOperationUndo.Trash(batch)
                    affectedDocuments.forEach(closeTab)
                    reloadWorkspace()
                    message = "Moved ${batch.entries.size} item(s) to workspace trash"
                }.onFailure { onError(it.message ?: "Delete failed.") }
        }
    }

    fun undoLastOperation() {
        val undo = lastUndo ?: return
        val root = workspaceRoot() ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (undo) {
                        is FileOperationUndo.Trash -> workspaceService.restoreTrash(undo.batch)
                        is FileOperationUndo.Rename -> workspaceService.undoRename(root, undo.batch)
                    }
                }
            }.onSuccess {
                if (undo is FileOperationUndo.Rename) {
                    val reverse = undo.batch.entries.map { RenamedEntry(it.newRelativePath, it.oldRelativePath) }
                    applyPathRenames(reverse, emptyMap())
                    documents().forEach { tab ->
                        undo.batch.originalContents[tab.relativePath]?.let { original ->
                            if (!tab.dirty) {
                                tab.content = original
                                tab.savedContent = original
                            }
                        }
                    }
                    persistWorkbench()
                }
                lastUndo = null
                reloadWorkspace()
                message = if (undo is FileOperationUndo.Trash) "Delete undone" else "Rename undone"
            }.onFailure { onError(it.message ?: "Could not undo the file operation.") }
        }
    }

    fun reset() {
        lastUndo = null
        message = null
        renameImpactPaths = emptyList()
        renameImpactLoading = false
    }

    private fun DocumentTab.isUnder(parent: String): Boolean = relativePath == parent || relativePath.startsWith("$parent/")

    private fun topLevelSelection(paths: List<String>): List<String> {
        val normalized = paths.map { it.replace('\\', '/').trim('/') }.filter(String::isNotBlank).distinct()
        return normalized.filter { path -> normalized.none { other -> other != path && path.startsWith("$other/") } }
    }
}
