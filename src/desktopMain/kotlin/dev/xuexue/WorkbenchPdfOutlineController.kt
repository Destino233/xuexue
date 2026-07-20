package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.DocumentAnalysisEngineDescriptor
import dev.xuexue.platform.DocumentAnalysisEngineRegistry
import dev.xuexue.platform.DocumentAnalysisSettings
import dev.xuexue.platform.EngineAvailability
import dev.xuexue.platform.EngineRuntimeCheck
import dev.xuexue.platform.HostComputeDetector
import dev.xuexue.platform.HostComputeProfile
import dev.xuexue.platform.PaddleOcrHealth
import dev.xuexue.platform.PaddleOcrServiceManager
import dev.xuexue.platform.PaddleOcrSidecarConfig
import dev.xuexue.platform.PaddleOcrVlEngine
import dev.xuexue.platform.PdfOutlineDestination
import dev.xuexue.platform.PdfOutlineDocument
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineRepository
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PpStructureV3Engine
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
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Suppress("TooManyFunctions") // The facade exposes one command for each outline workflow action.
internal class WorkbenchPdfOutlineController(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService,
    private val backend: PdfReaderBackend,
    private val workspaceRoot: () -> Path?,
    private val taskController: WorkbenchTaskController,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val persistWorkbench: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val engineRegistry: DocumentAnalysisEngineRegistry = DocumentAnalysisEngineRegistry.default(),
) {
    private val repository = PdfOutlineRepository(workspaceService)
    private val sidecarManager =
        PaddleOcrServiceManager(
            config = PaddleOcrSidecarConfig.fromEnvironment(),
            ioDispatcher = ioDispatcher,
        )
    private val analysisRunner =
        PdfDocumentAnalysisRunner(
            scope = scope,
            services =
                PdfAnalysisServices(
                    workspaceService = workspaceService,
                    backend = backend,
                    workspaceRoot = workspaceRoot,
                    engineRegistry = engineRegistry,
                    repository = repository,
                    cache = dev.xuexue.platform.PdfAnalysisCache(),
                    detector = dev.xuexue.platform.ChapterStructureDetector(),
                    sidecar = sidecarManager,
                ),
            taskController = taskController,
            ioDispatcher = ioDispatcher,
            callbacks =
                PdfAnalysisCallbacks(
                    onMessage = onMessage,
                    onError = onError,
                    persistWorkbench = persistWorkbench,
                ),
        )
    private val exportService =
        PdfOutlineExportService(
            scope = scope,
            workspaceService = workspaceService,
            backend = backend,
            workspaceRoot = workspaceRoot,
            taskController = taskController,
            ioDispatcher = ioDispatcher,
            onMessage = onMessage,
            onError = onError,
        )
    private val hostComputeDetector = HostComputeDetector()
    private val outlineLoadJobs = ConcurrentHashMap<String, Job>()
    private val activeOutlineLoadJobs = ConcurrentHashMap.newKeySet<Job>()

    var settings: DocumentAnalysisSettings = DocumentAnalysisSettings()
        private set
    var hostComputeProfile by mutableStateOf<HostComputeProfile?>(null)
        private set
    var engineAvailabilities by mutableStateOf<List<EngineAvailability>>(emptyList())
        private set
    var recommendedEngineId by mutableStateOf<String?>(null)
        private set

    val engineDescriptors: List<DocumentAnalysisEngineDescriptor>
        get() = engineRegistry.descriptors

    fun restoreSettings(restored: DocumentAnalysisSettings) {
        settings = restored
    }

    fun refreshHardware(startRuntime: Boolean = false) {
        scope.launch(ioDispatcher) {
            val profile = hostComputeDetector.detect()
            val sidecar =
                runCatching {
                    if (startRuntime) sidecarManager.ensureReady() else sidecarManager.health()
                }.getOrElse { failure ->
                    PaddleOcrHealth(false, detail = failure.message ?: "sidecar 检测失败")
                }
            val vlSidecar =
                if (sidecar.healthy) {
                    runCatching {
                        sidecarManager.probeEngine(
                            PaddleOcrVlEngine.ENGINE_ID,
                            load =
                                settings.globalEngineId == PaddleOcrVlEngine.ENGINE_ID ||
                                    settings.allowLowConfidenceVlFallback,
                        )
                    }.getOrElse { failure ->
                        PaddleOcrHealth(false, detail = failure.message ?: "VL 模型检测失败")
                    }
                } else {
                    sidecar
                }
            val matrix = availabilityMatrix(profile, sidecar, vlSidecar)
            hostComputeProfile = profile
            recommendedEngineId = matrix.recommendedEngineId
            engineAvailabilities = matrix.availabilities
        }
    }

    private fun availabilityMatrix(
        profile: HostComputeProfile,
        sidecar: PaddleOcrHealth,
        vlSidecar: PaddleOcrHealth,
    ): EngineAvailabilityMatrix {
        val runtimeInstalled = mapOf("pp-structure-v3" to sidecar.healthy, "paddleocr-vl" to sidecar.healthy)
        val runtimeVersions = mapOf("pp-structure-v3" to sidecar.version, "paddleocr-vl" to vlSidecar.version)
        val installedModels =
            buildSet {
                add("native-pdf-layout")
                if (sidecar.isEngineReady(PpStructureV3Engine.ENGINE_ID)) add("pp-structure-v3")
                if (vlSidecar.isEngineReady(PaddleOcrVlEngine.ENGINE_ID)) add("paddleocr-vl")
            }
        val checks =
            mapOf(
                "pp-structure-v3" to sidecarRuntimeCheck(sidecar, PpStructureV3Engine.ENGINE_ID),
                "paddleocr-vl" to
                    sidecarRuntimeCheck(
                        vlSidecar,
                        PaddleOcrVlEngine.ENGINE_ID,
                        runtimeInstalled = sidecar.healthy,
                    ),
            )
        val initial =
            hostComputeDetector.matrix(
                descriptors = engineDescriptors,
                profile = profile,
                installedModels = installedModels,
                runtimeInstalledByEngine = runtimeInstalled,
                runtimeVersions = runtimeVersions,
                runtimeChecks = checks,
            )
        val recommendation = hostComputeDetector.recommendedEngineId(initial)
        return EngineAvailabilityMatrix(
            recommendedEngineId = recommendation,
            availabilities =
                hostComputeDetector.matrix(
                    descriptors = engineDescriptors,
                    profile = profile,
                    installedModels = installedModels,
                    runtimeInstalledByEngine = runtimeInstalled,
                    runtimeVersions = runtimeVersions,
                    recommendedEngineId = recommendation,
                    runtimeChecks = checks,
                ),
        )
    }

    private fun sidecarRuntimeCheck(
        health: PaddleOcrHealth,
        engineId: String,
        runtimeInstalled: Boolean = health.healthy,
    ): EngineRuntimeCheck =
        EngineRuntimeCheck(
            runtimeInstalled = runtimeInstalled,
            modelInstalled = health.isEngineReady(engineId),
            backendSupported = health.runtime.deviceAvailable,
            runtimeBackend = health.runtime.backend,
            measuredMemoryBytes = health.measuredMemoryBytes,
            runtimeVersion = health.version,
        )

    fun setGlobalEngine(engineId: String) {
        requireNotNull(engineRegistry.engineOrNull(engineId)) {
            "Unknown document analysis engine: $engineId."
        }
        settings = settings.copy(globalEngineId = engineId)
    }

    fun setDocumentEngine(
        relativePath: String,
        engineId: String?,
    ) {
        engineId?.let {
            requireNotNull(engineRegistry.engineOrNull(it)) {
                "Unknown document analysis engine: $it."
            }
        }
        settings = settings.withDocumentEngine(relativePath, engineId)
    }

    fun setAllowVlFallback(enabled: Boolean) {
        settings = settings.copy(allowLowConfidenceVlFallback = enabled)
    }

    fun loadOutline(document: DocumentTab) {
        if (document.kind != DocumentKind.PDF) return
        val root = workspaceRoot() ?: return
        outlineLoadJobs.remove(document.relativePath)?.cancel()
        lateinit var job: Job
        job =
            scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    val path = resolvePdfPath(workspaceService, root, document)
                    val fingerprint = repository.fingerprint(path)
                    val stored = repository.load(root, document.relativePath, fingerprint)
                    document.pdfStaleOutline = stored.stale
                    if (stored.current != null) {
                        document.pdfOutline = stored.current
                    } else {
                        val native = backend.nativeOutline(path)
                        document.pdfOutline =
                            native.takeIf(List<*>::isNotEmpty)?.let {
                                nativeOutlineDocument(document.relativePath, fingerprint, it)
                            }
                        document.pdfOutline?.let { outline -> repository.save(root, outline) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    reportOutlineLoadFailure(document, failure)
                } catch (failure: IllegalArgumentException) {
                    reportOutlineLoadFailure(document, failure)
                } catch (failure: IllegalStateException) {
                    reportOutlineLoadFailure(document, failure)
                } finally {
                    if (outlineLoadJobs[document.relativePath] === job) {
                        outlineLoadJobs.remove(document.relativePath)
                    }
                }
            }
        outlineLoadJobs[document.relativePath] = job
        activeOutlineLoadJobs += job
        job.invokeOnCompletion { activeOutlineLoadJobs.remove(job) }
        job.start()
    }

    fun analyze(
        document: DocumentTab,
        engineId: String? = null,
    ) {
        if (document.kind != DocumentKind.PDF) return
        val selectedEngineId = engineId ?: settings.engineIdFor(document.relativePath)
        val engine = engineRegistry.engineOrNull(selectedEngineId)
        if (engine == null) {
            onError("未找到识别方案：$selectedEngineId")
            return
        }
        analysisRunner.analyze(document, engine, settings)
    }

    fun cancelAnalysis(document: DocumentTab) {
        analysisRunner.cancel(document)
    }

    fun restoreStaleOutline(document: DocumentTab) {
        val context = staleOutlineRestoreContext(document) ?: return
        val restored = context.stale.copy(documentFingerprint = context.fingerprint)
        document.pdfOutline = restored
        document.pdfStaleOutline = null
        scope.launch(ioDispatcher) {
            runCatching { repository.save(context.root, restored) }
                .onFailure { failure -> onError(failure.message ?: "无法保存恢复的 PDF 目录。") }
        }
        persistWorkbench()
        onMessage("已恢复旧版 PDF 目录，请在导出前检查页码和层级。")
    }

    private fun reportOutlineLoadFailure(
        document: DocumentTab,
        failure: Exception,
    ) {
        document.pdfOutlineError = failure.message ?: "无法读取 PDF 目录。"
        onError(document.pdfOutlineError ?: "无法读取 PDF 目录。")
    }

    private fun staleOutlineRestoreContext(document: DocumentTab): StaleOutlineRestoreContext? =
        document.pdfStaleOutline?.let { stale ->
            workspaceRoot()?.let { root ->
                runCatching {
                    repository.fingerprint(resolvePdfPath(workspaceService, root, document))
                }.onFailure { failure ->
                    onError(failure.message ?: "无法确认当前 PDF 版本。")
                }.getOrNull()
                    ?.let { fingerprint ->
                        StaleOutlineRestoreContext(stale, root, fingerprint)
                    }
            }
        }

    suspend fun cancelTasks() {
        analysisRunner.close()
        exportService.close()
        val outlineJobs = activeOutlineLoadJobs.toList()
        outlineJobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            outlineJobs.joinAll()
        }
        outlineLoadJobs.clear()
        activeOutlineLoadJobs.clear()
        sidecarManager.stop()
    }

    suspend fun close() {
        cancelTasks()
        sidecarManager.close()
    }

    fun editTitle(
        document: DocumentTab,
        id: String,
        title: String,
    ) {
        val clean = title.trim()
        if (clean.isBlank()) return
        updateOutline(document) { nodes ->
            PdfOutlineTreeEditor.updateNode(nodes, id) { node ->
                node.copy(
                    title = clean,
                    source = PdfOutlineSource.MANUAL,
                    reviewState = PdfOutlineReviewState.MANUAL,
                )
            }
        }
    }

    fun setReviewState(
        document: DocumentTab,
        id: String,
        state: PdfOutlineReviewState,
    ) {
        updateOutline(document) { nodes ->
            PdfOutlineTreeEditor.updateNode(nodes, id) { node ->
                node.copy(
                    reviewState = state,
                    source = if (state == PdfOutlineReviewState.MANUAL) PdfOutlineSource.MANUAL else node.source,
                )
            }
        }
    }

    fun confirmHighConfidence(document: DocumentTab) {
        updateOutline(document) { nodes ->
            PdfOutlineTreeEditor.confirmHighConfidence(nodes, HIGH_CONFIDENCE_CONFIRMATION_THRESHOLD)
        }
    }

    fun setDestination(
        document: DocumentTab,
        id: String,
        pageIndex: Int,
    ) {
        if (pageIndex < 0) return
        updateOutline(document) { nodes ->
            PdfOutlineTreeEditor.updateNode(nodes, id) { node ->
                node.copy(
                    destination = node.destination.copy(pageIndex = pageIndex),
                    source = PdfOutlineSource.MANUAL,
                    reviewState = PdfOutlineReviewState.MANUAL,
                )
            }
        }
    }

    fun addNode(
        document: DocumentTab,
        parentId: String?,
        title: String,
        pageIndex: Int,
    ) {
        val clean = title.trim()
        if (clean.isBlank() || pageIndex < 0) return
        val node =
            PdfOutlineNode(
                id = "manual-${System.nanoTime()}",
                title = clean,
                destination = PdfOutlineDestination(pageIndex),
                source = PdfOutlineSource.MANUAL,
                confidence = 1f,
                reviewState = PdfOutlineReviewState.MANUAL,
            )
        updateOutline(document) { nodes ->
            if (parentId == null) {
                nodes + node
            } else {
                PdfOutlineTreeEditor.updateNode(nodes, parentId) { parent ->
                    parent.copy(children = parent.children + node)
                }
            }
        }
    }

    fun removeNode(
        document: DocumentTab,
        id: String,
    ) {
        updateOutline(document) { nodes -> PdfOutlineTreeEditor.removeNode(nodes, id).first }
    }

    fun moveNode(
        document: DocumentTab,
        id: String,
        delta: Int,
    ) {
        if (delta == 0) return
        updateOutline(document) { nodes -> PdfOutlineTreeEditor.moveNode(nodes, id, delta) }
    }

    fun changeLevel(
        document: DocumentTab,
        id: String,
        indent: Boolean,
    ) {
        updateOutline(document) { nodes -> PdfOutlineTreeEditor.changeLevel(nodes, id, indent) }
    }

    fun export(
        document: DocumentTab,
        outputPath: Path,
        strategy: PdfOutlineExportStrategy,
        confirmedOnly: Boolean = false,
    ) {
        val outline = document.pdfOutline
        if (outline == null) {
            onError("当前 PDF 没有可导出的目录。")
            return
        }
        exportService.export(document, outline, outputPath, strategy, confirmedOnly)
    }

    private fun updateOutline(
        document: DocumentTab,
        transform: (List<PdfOutlineNode>) -> List<PdfOutlineNode>,
    ) {
        val current = document.pdfOutline ?: return
        val updated = current.copy(nodes = transform(current.nodes))
        document.pdfOutline = updated
        val root = workspaceRoot() ?: return
        scope.launch(ioDispatcher) {
            runCatching { repository.save(root, updated) }
                .onFailure { failure -> onError(failure.message ?: "无法保存 PDF 目录。") }
        }
        persistWorkbench()
    }

    private companion object {
        const val HIGH_CONFIDENCE_CONFIRMATION_THRESHOLD = 0.86f
    }
}

private data class StaleOutlineRestoreContext(
    val stale: PdfOutlineDocument,
    val root: Path,
    val fingerprint: String,
)

private data class EngineAvailabilityMatrix(
    val recommendedEngineId: String?,
    val availabilities: List<EngineAvailability>,
)
