package dev.xuexue

import dev.xuexue.platform.AnalysisProvenance
import dev.xuexue.platform.ChapterDetectionResult
import dev.xuexue.platform.ChapterStructureDetector
import dev.xuexue.platform.DocumentAnalysisEngine
import dev.xuexue.platform.DocumentAnalysisEngineDescriptor
import dev.xuexue.platform.DocumentAnalysisEngineRegistry
import dev.xuexue.platform.DocumentAnalysisRequest
import dev.xuexue.platform.DocumentAnalysisSettings
import dev.xuexue.platform.NativePdfLayoutEngine
import dev.xuexue.platform.PaddleOcrServiceManager
import dev.xuexue.platform.PaddleOcrVlEngine
import dev.xuexue.platform.PdfAnalysisCache
import dev.xuexue.platform.PdfAnalysisCacheKey
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfLayoutBlockType
import dev.xuexue.platform.PdfOutlineRepository
import dev.xuexue.platform.PdfPageClassification
import dev.xuexue.platform.PdfPageClassificationEvidence
import dev.xuexue.platform.PdfPageLayout
import dev.xuexue.platform.PdfPageLayoutStore
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PpStructureV3Engine
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

internal data class PdfAnalysisCallbacks(
    val onMessage: (String) -> Unit,
    val onError: (String) -> Unit,
    val persistWorkbench: () -> Unit,
)

internal class PdfAnalysisServices(
    val workspaceService: WorkspaceService,
    val backend: PdfReaderBackend,
    val workspaceRoot: () -> Path?,
    val engineRegistry: DocumentAnalysisEngineRegistry,
    val repository: PdfOutlineRepository,
    val cache: PdfAnalysisCache,
    val detector: ChapterStructureDetector,
    val sidecar: PaddleOcrServiceManager,
)

internal class PdfDocumentAnalysisRunner(
    private val scope: CoroutineScope,
    private val services: PdfAnalysisServices,
    private val taskController: WorkbenchTaskController,
    private val ioDispatcher: CoroutineDispatcher,
    private val callbacks: PdfAnalysisCallbacks,
) {
    private val analysisJobs = ConcurrentHashMap<String, Job>()
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val analysisBudget = Semaphore(permits = 1)

    @Suppress("TooGenericExceptionCaught")
    fun analyze(
        document: DocumentTab,
        engine: DocumentAnalysisEngine,
        settings: DocumentAnalysisSettings,
    ) {
        val root = services.workspaceRoot() ?: return
        analysisJobs.remove(document.relativePath)?.cancel()
        val taskId = "pdf-outline-analysis:${document.relativePath}"
        lateinit var job: Job
        job =
            scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                document.pdfOutlineAnalyzing = true
                document.pdfOutlineError = null
                var failed = false

                fun isCurrent(): Boolean = analysisJobs[document.relativePath] === job

                suspend fun ensureCurrent() {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) throw CancellationException("PDF analysis was superseded.")
                }

                try {
                    ensureEngineReady(engine)
                    ensureCurrent()
                    taskController.begin(
                        id = taskId,
                        title = "识别 PDF 章节",
                        detail = "准备识别方案：${engine.descriptor.displayName}",
                        onCancel = { job.cancel() },
                    )
                    analysisBudget.withPermit {
                        val generated = analyzeDocument(root, document, engine, settings, taskId, ::isCurrent)
                        ensureCurrent()
                        val merged = services.repository.mergePreservingReview(document.pdfOutline, generated.outline)
                        document.pdfOutline = merged
                        services.repository.save(root, merged)
                        callbacks.persistWorkbench()
                        callbacks.onMessage("PDF 章节识别完成，共 ${generated.candidates.size} 个候选")
                    }
                } catch (cancelled: CancellationException) {
                    if (isCurrent()) taskController.finish(taskId)
                    throw cancelled
                } catch (failure: Exception) {
                    if (isCurrent()) {
                        failed = true
                        document.pdfOutlineError = failure.message ?: "PDF 章节识别失败。"
                        taskController.fail(
                            id = taskId,
                            title = "PDF 章节识别失败",
                            detail = document.pdfOutlineError ?: "PDF 章节识别失败。",
                            onRetry = { analyze(document, engine, settings) },
                        )
                        callbacks.onError(document.pdfOutlineError ?: "PDF 章节识别失败。")
                    }
                } finally {
                    withContext(NonCancellable) {
                        if (isCurrent()) {
                            document.pdfOutlineAnalyzing = false
                            analysisJobs.remove(document.relativePath)
                            if (!failed) taskController.finish(taskId)
                        }
                    }
                }
            }
        analysisJobs[document.relativePath] = job
        activeJobs += job
        job.invokeOnCompletion { activeJobs.remove(job) }
        job.start()
    }

    fun cancel(document: DocumentTab) {
        analysisJobs[document.relativePath]?.cancel()
        document.pdfOutlineAnalyzing = false
    }

    suspend fun close() {
        val jobs = activeJobs.toList()
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            jobs.joinAll()
        }
        analysisJobs.clear()
        activeJobs.clear()
    }

    private suspend fun ensureEngineReady(engine: DocumentAnalysisEngine) {
        if (engine.descriptor.engineId !in OCR_ENGINE_IDS) return
        val health = services.sidecar.ensureReady(engine.descriptor.engineId)
        require(health.healthy) {
            health.detail ?: "PaddleOCR sidecar 不可用。"
        }
    }

    private suspend fun analyzeDocument(
        root: Path,
        document: DocumentTab,
        engine: DocumentAnalysisEngine,
        settings: DocumentAnalysisSettings,
        taskId: String,
        isCurrent: () -> Boolean,
    ): ChapterDetectionResult {
        val path = resolvePdfPath(services.workspaceService, root, document)
        val fingerprint = services.repository.fingerprint(path)
        val metadata = services.backend.inspect(path)
        val nativeEngine = NativePdfLayoutEngine()
        PdfPageLayoutStore().use { layoutStore ->
            repeat(metadata.pageCount) { pageIndex ->
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw CancellationException("PDF analysis was superseded.")
                val dimensions = services.backend.pageDimensions(path, pageIndex)
                val request =
                    DocumentAnalysisRequest(
                        path = path,
                        pageIndex = pageIndex,
                        width = max(1, ceil(dimensions.widthPoints).toInt()),
                        height = max(1, ceil(dimensions.heightPoints).toInt()),
                        dpi = settings.dpi,
                        language = settings.language,
                    )
                val native = nativeLayout(path, pageIndex, request, nativeEngine, settings, fingerprint, metadata)
                val layout = selectLayout(path, pageIndex, request, native, engine, settings, fingerprint, metadata)
                layoutStore.write(layout)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw CancellationException("PDF analysis was superseded.")
                taskController.update(
                    taskId,
                    "已完成 ${pageIndex + 1} / ${metadata.pageCount} 页",
                    (pageIndex + 1f) / metadata.pageCount.coerceAtLeast(1),
                )
            }
            return services.detector.detect(
                source = layoutStore.source(),
                relativePath = document.relativePath,
                documentFingerprint = fingerprint,
                analysis = engine.descriptor.toProvenance(settings),
            )
        }
    }

    private suspend fun selectLayout(
        path: Path,
        pageIndex: Int,
        request: DocumentAnalysisRequest,
        native: PdfPageLayout?,
        engine: DocumentAnalysisEngine,
        settings: DocumentAnalysisSettings,
        fingerprint: String,
        metadata: PdfDocumentMetadata,
    ): PdfPageLayout {
        val selected =
            when {
                native != null && native.isTrustedNativeText() -> native
                engine is NativePdfLayoutEngine -> native ?: emptyLayout(request, engine.descriptor.engineVersion)
                else -> analyzeWithRaster(path, pageIndex, request, native, engine, settings, fingerprint, metadata)
            }
        return if (settings.allowLowConfidenceVlFallback &&
            engine.descriptor.engineId == PpStructureV3Engine.ENGINE_ID &&
            selected.needsVlFallback()
        ) {
            services.engineRegistry.engineOrNull(PaddleOcrVlEngine.ENGINE_ID)?.let { vlEngine ->
                val vl = analyzeWithRaster(path, pageIndex, request, native, vlEngine, settings, fingerprint, metadata)
                mergeVlLayout(selected, vl)
            } ?: selected
        } else {
            selected
        }
    }

    private suspend fun nativeLayout(
        path: Path,
        pageIndex: Int,
        request: DocumentAnalysisRequest,
        engine: NativePdfLayoutEngine,
        settings: DocumentAnalysisSettings,
        fingerprint: String,
        metadata: PdfDocumentMetadata,
    ): PdfPageLayout? {
        val key = cacheKey(path, metadata, engine.descriptor, settings, fingerprint)
        val cached = services.cache.loadPage(key, pageIndex)
        return if (cached != null) {
            cached
        } else {
            val structured = services.backend.structuredText(path, pageIndex)
            if (structured == null) {
                null
            } else {
                val layout = engine.analyzePage(request.copy(nativeText = structured))
                services.cache.savePage(key, layout)
                layout
            }
        }
    }

    private suspend fun analyzeWithRaster(
        path: Path,
        pageIndex: Int,
        request: DocumentAnalysisRequest,
        native: PdfPageLayout?,
        engine: DocumentAnalysisEngine,
        settings: DocumentAnalysisSettings,
        fingerprint: String,
        metadata: PdfDocumentMetadata,
    ): PdfPageLayout {
        val key = cacheKey(path, metadata, engine.descriptor, settings, fingerprint)
        services.cache.loadPage(key, pageIndex)?.let { return it }
        val renderedImage = renderAnalysisImage(path, pageIndex, settings.dpi)
        val renderedInkCoverage = renderedImage.inkCoverage
        val imagePng = renderedImage.png
        currentCoroutineContext().ensureActive()
        val layout =
            engine.analyzePage(
                request.copy(
                    width = renderedImage.pixelWidth,
                    height = renderedImage.pixelHeight,
                    imagePng = imagePng,
                ),
            )
        val nativeEvidence = native?.classificationEvidence
        val nativeCharacters = nativeEvidence?.nativeCharacterCount ?: 0
        val classification =
            if (nativeCharacters >= MIN_MIXED_NATIVE_CHARS) {
                PdfPageClassification.MIXED
            } else {
                PdfPageClassification.SCANNED
            }
        val classified =
            layout.copy(
                classification = classification,
                classificationEvidence =
                    (layout.classificationEvidence ?: PdfPageClassificationEvidence()).copy(
                        nativeCharacterCount = nativeCharacters,
                        nativeBlockCount = nativeEvidence?.nativeBlockCount ?: 0,
                        imageBlockCount = nativeEvidence?.imageBlockCount ?: 0,
                        nativeCoverage = nativeEvidence?.nativeCoverage ?: 0f,
                        hiddenCharacterRatio = nativeEvidence?.hiddenCharacterRatio ?: 0f,
                        renderedInkCoverage = renderedInkCoverage,
                        signals =
                            (
                                layout.classificationEvidence?.signals.orEmpty() +
                                    listOfNotNull(
                                        if (nativeCharacters >= MIN_MIXED_NATIVE_CHARS) "原生文字与页面图像并存" else null,
                                        "渲染墨迹覆盖率=${"%.3f".format(java.util.Locale.ROOT, renderedInkCoverage)}",
                                    )
                            ).distinct(),
                    ),
            )
        services.cache.savePage(key, classified)
        return classified
    }

    private suspend fun renderAnalysisImage(
        path: Path,
        pageIndex: Int,
        dpi: Int,
    ): RenderedAnalysisImage {
        val raster =
            services.backend.render(
                dev.xuexue.platform.PdfRasterRequest(
                    path = path,
                    pageIndex = pageIndex,
                    dpi = dpi.toFloat(),
                    purpose = PdfRasterPurpose.ANALYSIS,
                    priority = PdfRasterPriority.BACKGROUND,
                ),
            )
        currentCoroutineContext().ensureActive()
        return RenderedAnalysisImage(
            png = rgbaToPng(raster.pixelWidth, raster.pixelHeight, raster.rgbaBytes),
            pixelWidth = raster.pixelWidth,
            pixelHeight = raster.pixelHeight,
            inkCoverage = raster.inkCoverage(),
        )
    }

    private fun cacheKey(
        path: Path,
        metadata: PdfDocumentMetadata,
        descriptor: DocumentAnalysisEngineDescriptor,
        settings: DocumentAnalysisSettings,
        fingerprint: String,
    ): PdfAnalysisCacheKey =
        PdfAnalysisCacheKey(
            documentFingerprint = fingerprint,
            fileSize = Files.size(path),
            pageCount = metadata.pageCount,
            engineId = descriptor.engineId,
            engineVersion = descriptor.engineVersion,
            modelVersion = descriptor.modelVersion,
            dpi = settings.dpi,
            language = settings.language,
            optionsFingerprint = "vlFallback=${settings.allowLowConfidenceVlFallback}",
        )

    private companion object {
        const val MIN_MIXED_NATIVE_CHARS = 8
        val OCR_ENGINE_IDS = setOf(PpStructureV3Engine.ENGINE_ID, PaddleOcrVlEngine.ENGINE_ID)

        fun taskId(relativePath: String): String = "pdf-outline-analysis:$relativePath"
    }
}

private fun PdfPageLayout.isTrustedNativeText(): Boolean =
    classification == PdfPageClassification.NATIVE_TEXT ||
        (classification == PdfPageClassification.UNKNOWN && blocks.sumOf { block -> block.text.length } >= 24)

private fun PdfPageLayout.needsVlFallback(): Boolean =
    blocks.none { block ->
        block.type == PdfLayoutBlockType.DOCUMENT_TITLE || block.type == PdfLayoutBlockType.SECTION_TITLE
    } ||
        blocks.any { block ->
            block.type == PdfLayoutBlockType.SECTION_TITLE && (block.recognitionConfidence ?: 1f) < 0.7f
        }

private fun mergeVlLayout(
    original: PdfPageLayout,
    vl: PdfPageLayout,
): PdfPageLayout {
    val titleBlocks =
        vl.blocks
            .filter { block ->
                block.type == PdfLayoutBlockType.DOCUMENT_TITLE || block.type == PdfLayoutBlockType.SECTION_TITLE
            }
    val aligned =
        titleBlocks.mapNotNull { candidate ->
            original.blocks
                .map { block -> block to textOverlap(block.text, candidate.text) }
                .maxByOrNull { (_, overlap) -> overlap }
                ?.takeIf { (_, overlap) -> overlap >= VL_TEXT_ALIGNMENT_THRESHOLD }
                ?.let { (block, _) -> candidate to block }
        }
    val replacements =
        aligned.associate { (candidate, originalBlock) ->
            originalBlock.id to candidate.copy(id = originalBlock.id, bounds = originalBlock.bounds)
        }
    val unmatched =
        titleBlocks
            .filterNot { candidate -> aligned.any { (alignedCandidate, _) -> alignedCandidate === candidate } }
            .mapIndexed { index, candidate ->
                candidate.copy(
                    id = "${vl.engine.engineId}-${vl.pageIndex}-unmatched-$index",
                    requiresReview = true,
                )
            }
    return if (replacements.isEmpty() && unmatched.isEmpty()) {
        original
    } else {
        original.copy(
            blocks =
                (original.blocks.map { block -> replacements[block.id] ?: block } + unmatched)
                    .sortedWith(compareBy({ it.order }, { it.bounds.top }, { it.bounds.left })),
            engine = vl.engine,
        )
    }
}

private const val VL_TEXT_ALIGNMENT_THRESHOLD = 0.45f

private fun textOverlap(
    left: String,
    right: String,
): Float {
    val leftChars = left.filterNot(Char::isWhitespace).lowercase().toSet()
    val rightChars = right.filterNot(Char::isWhitespace).lowercase().toSet()
    if (leftChars.isEmpty() || rightChars.isEmpty()) return 0f
    return (
        leftChars.intersect(rightChars).size.toFloat() /
            max(leftChars.size, rightChars.size).coerceAtLeast(1)
    ).coerceIn(0f, 1f)
}

private fun emptyLayout(
    request: DocumentAnalysisRequest,
    version: String,
): PdfPageLayout =
    PdfPageLayout(
        pageIndex = request.pageIndex,
        width = request.width,
        height = request.height,
        blocks = emptyList(),
        engine = AnalysisProvenance("native-pdf-layout", version, language = request.language),
        classification = PdfPageClassification.EMPTY,
        classificationEvidence = PdfPageClassificationEvidence(signals = listOf("未发现可用文字层")),
    )

private fun DocumentAnalysisEngineDescriptor.toProvenance(
    settings: DocumentAnalysisSettings,
): AnalysisProvenance =
    AnalysisProvenance(
        engineId = engineId,
        engineVersion = engineVersion,
        modelVersion = modelVersion,
        language = settings.language,
        dpi = settings.dpi,
    )

private fun rgbaToPng(
    width: Int,
    height: Int,
    rgba: ByteArray,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    var offset = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val red = rgba[offset++].toInt() and 0xff
            val green = rgba[offset++].toInt() and 0xff
            val blue = rgba[offset++].toInt() and 0xff
            val alpha = rgba[offset++].toInt() and 0xff
            image.setRGB(x, y, alpha shl 24 or (red shl 16) or (green shl 8) or blue)
        }
    }
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output)) { "Could not encode the PDF page image." }
        image.flush()
        output.toByteArray()
    }
}

private fun PdfPageRaster.inkCoverage(): Float {
    var darkPixels = 0
    var offset = 0
    val pixels = pixelWidth * pixelHeight
    repeat(pixels) {
        val red = rgbaBytes[offset++].toInt() and 0xff
        val green = rgbaBytes[offset++].toInt() and 0xff
        val blue = rgbaBytes[offset++].toInt() and 0xff
        val alpha = rgbaBytes[offset++].toInt() and 0xff
        if (isInkPixel(red, green, blue, alpha)) darkPixels++
    }
    return if (pixels == 0) 0f else darkPixels.toFloat() / pixels
}

private fun isInkPixel(
    red: Int,
    green: Int,
    blue: Int,
    alpha: Int,
): Boolean {
    if (alpha <= 8) return false
    return red < 245 || green < 245 || blue < 245
}

private data class RenderedAnalysisImage(
    val png: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val inkCoverage: Float,
)
