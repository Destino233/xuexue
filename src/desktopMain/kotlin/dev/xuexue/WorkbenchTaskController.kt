package dev.xuexue

import androidx.compose.runtime.mutableStateListOf

data class WorkbenchTask(
    val id: String,
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val cancellable: Boolean = false,
    val failed: Boolean = false,
    val retryable: Boolean = false,
)

class WorkbenchTaskController(
    private val onStatus: (String) -> Unit,
) {
    val tasks = mutableStateListOf<WorkbenchTask>()
    private val cancelActions = mutableMapOf<String, () -> Unit>()
    private val retryActions = mutableMapOf<String, () -> Unit>()

    fun begin(
        id: String,
        title: String,
        detail: String,
        progress: Float? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        tasks.removeAll { it.id == id }
        tasks += WorkbenchTask(id, title, detail, progress, cancellable = onCancel != null)
        if (onCancel != null) cancelActions[id] = onCancel else cancelActions.remove(id)
        retryActions.remove(id)
    }

    fun update(
        id: String,
        detail: String? = null,
        progress: Float? = null,
    ) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = tasks[index]
        tasks[index] = current.copy(detail = detail ?: current.detail, progress = progress ?: current.progress)
    }

    fun finish(id: String) {
        tasks.removeAll { it.id == id }
        cancelActions.remove(id)
        retryActions.remove(id)
    }

    fun fail(
        id: String,
        title: String,
        detail: String,
        onRetry: (() -> Unit)? = null,
    ) {
        tasks.removeAll { it.id == id }
        tasks += WorkbenchTask(id, title, detail, failed = true, retryable = onRetry != null)
        cancelActions.remove(id)
        if (onRetry != null) retryActions[id] = onRetry else retryActions.remove(id)
    }

    fun cancel(id: String) {
        cancelExisting(id)
        onStatus("Task cancelled")
    }

    fun cancelExisting(id: String) {
        cancelActions.remove(id)?.invoke()
        finish(id)
    }

    fun retry(id: String) {
        val retry = retryActions.remove(id) ?: return
        tasks.removeAll { it.id == id }
        retry()
    }

    fun clear(cancelRunning: Boolean = true) {
        if (cancelRunning) cancelActions.values.toList().forEach { it() }
        cancelActions.clear()
        retryActions.clear()
        tasks.clear()
    }
}
