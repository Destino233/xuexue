package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.core.WorkspaceEntry
import dev.xuexue.core.WorkspaceIndex
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

private data class SavedDocumentResult(
    val modifiedAt: Long,
    val index: WorkspaceIndex,
)

private data class SaveAsResult(
    val modifiedAt: Long,
    val tree: WorkspaceEntry,
    val index: WorkspaceIndex,
)

internal class WorkbenchDocumentController(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService,
    private val workspaceRoot: () -> Path?,
    private val documents: () -> List<DocumentTab>,
    private val markdownOverrides: () -> Map<String, String>,
    private val onWorkspaceTreeChanged: (WorkspaceEntry) -> Unit,
    private val onWorkspaceIndexChanged: (WorkspaceIndex) -> Unit,
    private val onDocumentPathChanged: (String, String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val persistWorkbench: () -> Unit,
    private val refreshWorkspace: () -> Unit,
) {
    private val saveJobs = mutableMapOf<DocumentTab, Job>()

    fun scheduleSave(tab: DocumentTab) {
        enqueueSave(tab, delayMillis = AUTOSAVE_DELAY_MILLIS)
    }

    fun save(tab: DocumentTab): Job = enqueueSave(tab, delayMillis = 0L)

    suspend fun saveAllDirty(): Boolean {
        val dirtyDocuments = documents().filter(DocumentTab::dirty)
        dirtyDocuments.forEach { tab ->
            saveJobs.remove(tab)?.cancel()
            saveDocumentNow(tab)
        }
        return dirtyDocuments.none { it.dirty || it.saveError != null }
    }

    fun saveAs(
        tab: DocumentTab,
        target: Path,
    ): Job? {
        val root = workspaceRoot() ?: return null
        if (tab.kind !in TEXT_DOCUMENT_KINDS) return null
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(root)) {
            tab.saveError = "Choose a location inside the current workspace."
            return null
        }
        val newRelative = root.relativize(normalizedTarget).pathString.replace('\\', '/')
        if (documents().any { it !== tab && it.relativePath == newRelative }) {
            tab.saveError = "Another open document already uses this path."
            return null
        }
        val oldRelative = tab.relativePath
        val contentSnapshot = tab.content
        val overrides =
            markdownOverrides().toMutableMap().apply {
                remove(oldRelative)
                if (tab.kind == DocumentKind.MARKDOWN) this[newRelative] = contentSnapshot
            }
        onStatus("Saving...")
        return scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val safeTarget = workspaceService.resolveForWriteInside(root, newRelative)
                    workspaceService.atomicWrite(safeTarget, contentSnapshot)
                    SaveAsResult(
                        modifiedAt = Files.getLastModifiedTime(safeTarget).toMillis(),
                        tree = workspaceService.scan(root),
                        index = workspaceService.buildIndex(root, overrides),
                    )
                }
            }.onSuccess { result ->
                tab.relativePath = newRelative
                tab.savedContent = contentSnapshot
                tab.lastKnownModified = result.modifiedAt
                tab.externalContent = null
                tab.externalDeleted = false
                tab.saveError = null
                onDocumentPathChanged(oldRelative, newRelative)
                onWorkspaceTreeChanged(result.tree)
                onWorkspaceIndexChanged(result.index)
                onStatus("Saved as $newRelative")
                persistWorkbench()
                if (tab.content != contentSnapshot) scheduleSave(tab)
            }.onFailure { error ->
                tab.saveError = error.message ?: "Save as failed."
                onStatus("Save failed")
            }
        }
    }

    fun restoreDeleted(document: DocumentTab) {
        val root = workspaceRoot() ?: return
        val relativePath = document.relativePath
        val contentSnapshot = document.content
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val path = workspaceService.resolveForWriteInside(root, relativePath)
                    workspaceService.atomicWrite(path, contentSnapshot)
                    Files.getLastModifiedTime(path).toMillis()
                }
            }.onSuccess { modifiedAt ->
                document.externalDeleted = false
                document.savedContent = contentSnapshot
                document.lastKnownModified = modifiedAt
                document.saveError = null
                onStatus("Deleted file restored")
                refreshWorkspace()
            }.onFailure { onError(it.message ?: "Could not restore the file.") }
        }
    }

    fun cancel(tab: DocumentTab) {
        saveJobs.remove(tab)?.cancel()
    }

    fun cancelAll() {
        saveJobs.values.forEach(Job::cancel)
        saveJobs.clear()
    }

    private fun enqueueSave(
        tab: DocumentTab,
        delayMillis: Long,
    ): Job {
        cancel(tab)
        onStatus("Saving...")
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (delayMillis > 0) delay(delayMillis)
                    saveDocumentNow(tab)
                } finally {
                    val current = currentCoroutineContext()[Job]
                    if (saveJobs[tab] === current) saveJobs.remove(tab)
                }
            }
        saveJobs[tab] = job
        job.start()
        return job
    }

    private suspend fun saveDocumentNow(tab: DocumentTab): Boolean {
        val root = workspaceRoot() ?: return false
        if (tab.kind !in TEXT_DOCUMENT_KINDS || !tab.dirty) return true
        val relativePath = tab.relativePath
        val contentSnapshot = tab.content
        val overrides =
            markdownOverrides().toMutableMap().apply {
                if (tab.kind == DocumentKind.MARKDOWN) this[relativePath] = contentSnapshot
            }
        return runCatching {
            withContext(Dispatchers.IO) {
                val path = workspaceService.resolveForWriteInside(root, relativePath)
                workspaceService.atomicWrite(path, contentSnapshot)
                SavedDocumentResult(
                    modifiedAt = Files.getLastModifiedTime(path).toMillis(),
                    index = workspaceService.buildIndex(root, overrides),
                )
            }
        }.fold(
            onSuccess = { result ->
                tab.savedContent = contentSnapshot
                tab.lastKnownModified = result.modifiedAt
                tab.externalContent = null
                tab.externalDeleted = false
                tab.saveError = null
                onWorkspaceIndexChanged(result.index)
                onStatus("Saved")
                persistWorkbench()
                if (tab.content != contentSnapshot) scheduleSave(tab)
                true
            },
            onFailure = {
                onStatus("Save failed")
                tab.saveError = it.message ?: "Could not save this document."
                false
            },
        )
    }

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 700L
        val TEXT_DOCUMENT_KINDS = setOf(DocumentKind.MARKDOWN, DocumentKind.TEXT)
    }
}
