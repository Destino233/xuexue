package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xuexue.core.ToolWindow
import dev.xuexue.platform.HttpRangePdfManifestStore
import dev.xuexue.platform.HttpRangePdfSource
import dev.xuexue.platform.ProgressivePdfRegistry
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.pathString

internal data class RemotePdfInfrastructure(
    val workspaceService: WorkspaceService,
    val progressivePdfSources: ProgressivePdfRegistry,
)

internal class WorkbenchRemotePdfController(
    private val scope: CoroutineScope,
    private val infrastructure: RemotePdfInfrastructure,
    private val taskController: WorkbenchTaskController,
    private val workspaceRoot: () -> Path?,
    private val offlineMode: () -> Boolean,
    private val reloadWorkspace: suspend () -> Unit,
    private val openRelative: (String, Boolean) -> Unit,
    private val openToolWindow: (ToolWindow) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    var dialogVisible by mutableStateOf(false)

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val manifestStore = HttpRangePdfManifestStore(Dispatchers.IO)
    private val rangeDownloads = mutableMapOf<String, RemoteRangeDownload>()
    val downloadStates = mutableStateMapOf<String, RemotePdfDownloadState>()

    suspend fun restoreRangeDownloads(root: Path): List<String> {
        val downloads = infrastructure.workspaceService.resolveForWriteInside(root, "Downloads")
        return manifestStore.discover(downloads).mapNotNull { recovered ->
            val source =
                HttpRangePdfSource.resumeOrNull(
                    httpClient = httpClient,
                    cachePath = recovered.cachePath,
                    ioDispatcher = Dispatchers.IO,
                ) ?: return@mapNotNull null
            val target =
                recovered.cachePath.resolveSibling(
                    recovered.cachePath.fileName
                        .toString()
                        .removeSuffix(".part"),
                )
            val validTarget =
                withContext(Dispatchers.IO) {
                    when {
                        Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> {
                            true
                        }

                        Files.exists(target, LinkOption.NOFOLLOW_LINKS) -> {
                            false
                        }

                        else -> {
                            Files.createFile(target)
                            true
                        }
                    }
                }
            if (!validTarget) {
                source.close()
                return@mapNotNull null
            }
            val url = source.remoteUri.toString()
            val relativePath = root.relativize(target).pathString.replace('\\', '/')
            infrastructure.progressivePdfSources.register(target, source)
            rangeDownloads[url] =
                RemoteRangeDownload(
                    target = target,
                    relativePath = relativePath,
                    source = source,
                )
            downloadStates[relativePath] =
                RemotePdfDownloadState(
                    relativePath = relativePath,
                    sourceUrl = url,
                    downloadedBytes = source.bytesLoaded,
                    totalBytes = source.length,
                    phase = RemotePdfDownloadPhase.PAUSED,
                )
            url
        }
    }

    fun reset() {
        rangeDownloads.clear()
        downloadStates.clear()
        infrastructure.progressivePdfSources.close()
    }

    fun download(rawUrl: String) {
        val root = workspaceRoot() ?: return
        val url = rawUrl.trim()
        if (offlineMode()) {
            onError("Offline mode is enabled. Disable it before downloading a remote PDF.")
            return
        }
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme !in HTTP_SCHEMES) {
            onError("Enter a valid HTTP or HTTPS PDF URL.")
            return
        }
        dialogVisible = false
        val taskId = "remote-pdf:${url.hashCode()}"
        taskController.cancelExisting(taskId)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var activeRangeDownload: RemoteRangeDownload? = null
                try {
                    val rangeDownload =
                        rangeDownloads[url]
                            ?: prepareRangeDownload(root, uri)?.also { prepared ->
                                rangeDownloads[url] = prepared
                            }
                    activeRangeDownload = rangeDownload
                    if (rangeDownload == null) {
                        val relative =
                            withContext(Dispatchers.IO) {
                                downloadToWorkspace(root, uri, taskId)
                            }
                        reloadWorkspace()
                        openRelative(relative, true)
                        onStatus("Remote PDF downloaded")
                    } else {
                        downloadStates[rangeDownload.relativePath] =
                            rangeDownload.state(RemotePdfDownloadPhase.DOWNLOADING)
                        downloadRanges(rangeDownload, taskId)
                        rangeDownloads.remove(url)
                        downloadStates.remove(rangeDownload.relativePath)
                    }
                    taskController.finish(taskId)
                } catch (cancelled: CancellationException) {
                    activeRangeDownload?.let { download ->
                        downloadStates[download.relativePath] = download.state(RemotePdfDownloadPhase.PAUSED)
                    }
                    taskController.finish(taskId)
                    throw cancelled
                } catch (error: Throwable) {
                    activeRangeDownload?.let { download ->
                        downloadStates[download.relativePath] =
                            download.state(
                                phase = RemotePdfDownloadPhase.FAILED,
                                error = error.message ?: "unknown error",
                            )
                    }
                    taskController.fail(
                        taskId,
                        "Remote PDF",
                        "Download interrupted: ${error.message ?: "unknown error"}. Partial data was retained.",
                        onRetry = { download(url) },
                    )
                }
            }
        taskController.begin(taskId, "Remote PDF", url, onCancel = job::cancel)
        openToolWindow(ToolWindow.TASKS)
        job.start()
    }

    private suspend fun prepareRangeDownload(
        root: Path,
        uri: URI,
    ): RemoteRangeDownload? {
        val target = withContext(Dispatchers.IO) { nextDownloadTarget(root, uri) }
        val partial = target.resolveSibling("${target.fileName}.part")
        val source =
            HttpRangePdfSource.openOrNull(
                httpClient = httpClient,
                uri = uri,
                cachePath = partial,
                ioDispatcher = Dispatchers.IO,
            ) ?: return null
        return try {
            withContext(Dispatchers.IO) { Files.createFile(target) }
            RemoteRangeDownload(
                target = target,
                relativePath = root.relativize(target).pathString.replace('\\', '/'),
                source = source,
            )
        } catch (cancelled: CancellationException) {
            source.close()
            throw cancelled
        } catch (failure: IOException) {
            source.close()
            throw failure
        }
    }

    private suspend fun downloadRanges(
        download: RemoteRangeDownload,
        taskId: String,
    ) {
        if (!download.opened) {
            infrastructure.progressivePdfSources.register(download.target, download.source)
            reloadWorkspace()
            openRelative(download.relativePath, true)
            download.opened = true
            onStatus("Remote PDF opened progressively")
        }
        var lastUpdate = 0L
        download.source.cacheAll { downloaded, total ->
            val now = System.currentTimeMillis()
            if (downloaded == total || now - lastUpdate >= PROGRESS_INTERVAL_MILLIS) {
                withContext(Dispatchers.Main) {
                    downloadStates[download.relativePath] =
                        download.state(RemotePdfDownloadPhase.DOWNLOADING)
                    taskController.update(
                        taskId,
                        "${formatProgress(downloaded, total)} · available while downloading",
                        (downloaded.toFloat() / total).coerceIn(0f, 1f),
                    )
                }
                lastUpdate = now
            }
        }
        download.source.finalizeTo(download.target)
        reloadWorkspace()
        onStatus("Remote PDF downloaded")
    }

    private suspend fun downloadToWorkspace(
        root: Path,
        uri: URI,
        taskId: String,
    ): String {
        val target = nextDownloadTarget(root, uri)
        val partialRelative = root.relativize(target).pathString.replace('\\', '/') + ".part"
        val partial = infrastructure.workspaceService.resolveForWriteInside(root, partialRelative)
        val existingBytes = if (Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) Files.size(partial) else 0L
        val requestBuilder = HttpRequest.newBuilder(uri).GET()
        if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        val resumed = existingBytes > 0 && response.statusCode() == 206
        val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
        val rangeTotal =
            response
                .headers()
                .firstValue("content-range")
                .orElse("")
                .substringAfterLast('/')
                .toLongOrNull()
        val total = rangeTotal ?: if (contentLength > 0) contentLength + if (resumed) existingBytes else 0L else -1L
        streamResponse(response, partial, existingBytes, resumed, total, taskId)
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        return root.relativize(target).pathString.replace('\\', '/')
    }

    private fun nextDownloadTarget(
        root: Path,
        uri: URI,
    ): Path {
        val requestedName = uri.path.substringAfterLast('/').ifBlank { "remote-${System.currentTimeMillis()}.pdf" }
        val baseName =
            requestedName
                .substringBefore('?')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val downloads = infrastructure.workspaceService.resolveForWriteInside(root, "Downloads")
        Files.createDirectories(downloads)
        var target = infrastructure.workspaceService.resolveForWriteInside(root, "Downloads/$baseName")
        var suffix = 2
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            val suffixedName = "${baseName.removeSuffix(".pdf")}-$suffix.pdf"
            target = infrastructure.workspaceService.resolveForWriteInside(root, "Downloads/$suffixedName")
            suffix++
        }
        return target
    }

    private suspend fun streamResponse(
        response: HttpResponse<java.io.InputStream>,
        partial: Path,
        existingBytes: Long,
        resumed: Boolean,
        total: Long,
        taskId: String,
    ) {
        var downloaded = if (resumed) existingBytes else 0L
        var lastUpdate = 0L
        response.body().use { input ->
            val writeOptions =
                if (resumed) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
            Files.newOutputStream(partial, *writeOptions).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MILLIS) {
                        val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                        withContext(Dispatchers.Main) {
                            taskController.update(taskId, formatProgress(downloaded, total), progress)
                        }
                        lastUpdate = now
                    }
                }
            }
        }
    }

    private fun formatProgress(
        downloaded: Long,
        total: Long,
    ): String {
        fun bytes(value: Long): String =
            when {
                value >= 1024L * 1024L -> "%.1f MB".format(value / (1024f * 1024f))
                value >= 1024L -> "%.1f KB".format(value / 1024f)
                else -> "$value B"
            }
        return if (total > 0) "${bytes(downloaded)} / ${bytes(total)}" else bytes(downloaded)
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MILLIS = 150L
    }

    private data class RemoteRangeDownload(
        val target: Path,
        val relativePath: String,
        val source: HttpRangePdfSource,
        var opened: Boolean = false,
    ) {
        fun state(
            phase: RemotePdfDownloadPhase,
            error: String? = null,
        ): RemotePdfDownloadState =
            RemotePdfDownloadState(
                relativePath = relativePath,
                sourceUrl = source.remoteUri.toString(),
                downloadedBytes = source.bytesLoaded,
                totalBytes = source.length,
                phase = phase,
                error = error,
            )
    }
}
