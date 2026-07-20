package dev.xuexue

import dev.xuexue.platform.PdfOutlineDocument
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

internal class PdfOutlineExportService(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService,
    private val backend: PdfReaderBackend,
    private val workspaceRoot: () -> Path?,
    private val taskController: WorkbenchTaskController,
    private val ioDispatcher: CoroutineDispatcher,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val exportJobs = ConcurrentHashMap<String, Job>()
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()

    @Suppress("TooGenericExceptionCaught")
    fun export(
        document: DocumentTab,
        outline: PdfOutlineDocument,
        outputPath: Path,
        strategy: PdfOutlineExportStrategy,
        confirmedOnly: Boolean = false,
    ) {
        val root = workspaceRoot() ?: return
        val taskId = "pdf-outline-export:${document.relativePath}"
        exportJobs.remove(document.relativePath)?.cancel()
        lateinit var job: Job
        job =
            scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                taskController.begin(
                    taskId,
                    "导出 PDF 书签",
                    "准备输出到 ${outputPath.name}",
                    onCancel = { job.cancel() },
                )
                val temp = outputPath.resolveSibling(".${outputPath.name}.xuexue.tmp")
                try {
                    val exportOutline =
                        outline.copy(nodes = PdfOutlineTreeEditor.exportable(outline.nodes, confirmedOnly))
                    require(exportOutline.nodes.isNotEmpty()) { "没有可导出的已确认书签。" }
                    val source = resolvePdfPath(workspaceService, root, document)
                    require(source.toAbsolutePath().normalize() != outputPath.toAbsolutePath().normalize()) {
                        "导出路径必须不同于源 PDF。"
                    }
                    Files.deleteIfExists(temp)
                    Files.createDirectories(outputPath.toAbsolutePath().normalize().parent)
                    val sourceFingerprint = fileFingerprint(source)
                    backend.exportOutline(
                        source,
                        temp,
                        dev.xuexue.platform.PdfNativePayloadCodec
                            .encodeOutlineForExport(exportOutline),
                        strategy,
                    )
                    require(fileFingerprint(source) == sourceFingerprint) { "源 PDF 在导出期间发生了变化。" }
                    verifyExport(source, temp, exportOutline, strategy)
                    moveFileAtomically(temp, outputPath)
                    taskController.finish(taskId)
                    onMessage("PDF 书签已导出到 ${outputPath.name}，共 ${exportOutline.nodes.size} 个顶层节点")
                } catch (cancelled: CancellationException) {
                    taskController.finish(taskId)
                    throw cancelled
                } catch (failure: Exception) {
                    val detail = pdfOutlineExportFailureMessage(failure)
                    taskController.fail(
                        taskId,
                        "PDF 书签导出失败",
                        detail,
                    )
                    onError(detail)
                } finally {
                    Files.deleteIfExists(temp)
                    if (exportJobs[document.relativePath] === job) {
                        exportJobs.remove(document.relativePath)
                    }
                }
            }
        exportJobs[document.relativePath] = job
        activeJobs += job
        job.invokeOnCompletion { activeJobs.remove(job) }
        job.start()
    }

    suspend fun close() {
        val jobs = activeJobs.toList()
        exportJobs.keys.forEach { relativePath ->
            taskController.cancelExisting("pdf-outline-export:$relativePath")
        }
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            jobs.joinAll()
        }
        exportJobs.clear()
        activeJobs.clear()
    }

    private suspend fun verifyExport(
        source: Path,
        output: Path,
        outline: PdfOutlineDocument,
        strategy: PdfOutlineExportStrategy,
    ) {
        val sourceMetadata = backend.inspect(source)
        val outputMetadata = backend.inspect(output)
        require(sourceMetadata.pageCount == outputMetadata.pageCount) {
            "导出 PDF 页数发生变化。"
        }
        val actual = backend.nativeOutline(output)
        val expected = outline.nodes
        require(expected.isNotEmpty() && actual.isNotEmpty()) {
            "导出 PDF 未读取到书签树。"
        }
        require(outlineTreeExportMatches(expected, actual, strategy)) {
            "导出 PDF 书签标题、目标页或数量与审校树不一致。"
        }
    }

    private fun fileFingerprint(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            byte
                .toInt()
                .and(0xff)
                .toString(16)
                .padStart(2, '0')
        }
    }
}
