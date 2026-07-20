package dev.xuexue.platform

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test

/**
 * Runs only when the external evaluation properties are supplied. The test keeps the model and PDF outside Git
 * while using the same MuPDF, page cache, detector, and evaluator as the desktop feature.
 */
@Suppress("TooManyFunctions") // Keep report input, evaluation, and serialization helpers together.
class PdfOcrExternalEvaluationTest {
    @Test
    fun configuredScanBookEvaluationProducesReproducibleReport() =
        runBlocking {
            val input = loadEvaluationInput() ?: return@runBlocking
            runEvaluation(input)
        }

    private fun loadEvaluationInput(): ExternalEvaluationInput? {
        val pdf = propertyPath("xuexue.pdf.eval.pdf")
        val manifestPath = propertyPath("xuexue.pdf.eval.manifest")
        val reportPath = propertyPath("xuexue.pdf.eval.report")
        if (pdf == null || manifestPath == null || reportPath == null) return null
        val manifest =
            Json { ignoreUnknownKeys = true }.decodeFromString<ExternalEvaluationManifest>(
                Files.readString(manifestPath),
            )
        require(Files.isRegularFile(pdf)) { "Evaluation PDF does not exist: $pdf" }
        require(manifest.pages.isNotEmpty()) { "Evaluation manifest must select at least one page." }
        require(
            manifest.pages.all { page -> page >= 0 },
        ) { "Evaluation pages must use physical zero-based indices." }
        return ExternalEvaluationInput(
            pdf = pdf,
            manifest = manifest,
            reportPath = reportPath,
            endpoint =
                URI.create(
                    System.getProperty("xuexue.pdf.eval.endpoint", "http://127.0.0.1:8765/analyze"),
                ),
            dpi = System.getProperty("xuexue.pdf.eval.dpi", "200").toInt(),
        )
    }

    private suspend fun runEvaluation(input: ExternalEvaluationInput) {
        val backend = MuPdfReaderBackend()
        val cacheRoot = Files.createTempDirectory("xuexue-external-eval-cache")
        val firstStore = PdfPageLayoutStore()
        val secondStore = PdfPageLayoutStore()
        val cache = PdfAnalysisCache(cacheRoot)
        try {
            val result = evaluateDocument(input, backend, firstStore, secondStore, cache)
            writeReport(input, result)
        } finally {
            firstStore.close()
            secondStore.close()
            cacheRoot.toFile().deleteRecursively()
            backend.close()
        }
    }

    private suspend fun evaluateDocument(
        input: ExternalEvaluationInput,
        backend: PdfReaderBackend,
        firstStore: PdfPageLayoutStore,
        secondStore: PdfPageLayoutStore,
        cache: PdfAnalysisCache,
    ): ExternalEvaluationResult {
        val metadata = backend.inspect(input.pdf)
        require(input.manifest.pages.all { page -> page < metadata.pageCount }) {
            "Evaluation manifest contains a page outside the PDF."
        }
        val fingerprint = sha256(input.pdf)
        require(input.manifest.pdfSha256 == fingerprint) {
            "Evaluation PDF SHA-256 does not match the manifest."
        }
        val engine = PpStructureV3Engine(endpoint = input.endpoint)
        val cacheKey =
            PdfAnalysisCacheKey(
                documentFingerprint = fingerprint,
                fileSize = Files.size(input.pdf),
                pageCount = metadata.pageCount,
                engineId = engine.descriptor.engineId,
                engineVersion = engine.descriptor.engineVersion,
                modelVersion = engine.descriptor.modelVersion,
                dpi = input.dpi,
                language = input.manifest.language,
            )
        val firstPass = analyzePages(backend, input, engine, firstStore, cache, cacheKey)
        val cachedPass = analyzePages(backend, input, engine, secondStore, cache, cacheKey)
        val actual =
            ChapterStructureDetector().detect(
                source = secondStore.source(),
                relativePath = input.pdf.fileName.toString(),
                documentFingerprint = fingerprint,
                analysis = engine.descriptor.toProvenance(input.dpi, input.manifest.language),
            )
        val metrics =
            ChapterEvaluator.evaluate(
                expected = input.manifest.expected.toOutline(input.pdf.fileName.toString(), fingerprint),
                actual = actual.outline,
            )
        return ExternalEvaluationResult(
            pageCount = metadata.pageCount,
            fingerprint = fingerprint,
            descriptor = engine.descriptor,
            firstPass = firstPass,
            cachedPass = cachedPass,
            metrics = metrics,
            actual = actual.outline,
        )
    }

    private fun writeReport(
        input: ExternalEvaluationInput,
        result: ExternalEvaluationResult,
    ) {
        Files.createDirectories(checkNotNull(input.reportPath.parent))
        Files.writeString(
            input.reportPath,
            Json {
                prettyPrint = true
                encodeDefaults = true
            }.encodeToString(
                ExternalEvaluationReport(
                    datasetId = input.manifest.datasetId,
                    sourceUrl = input.manifest.sourceUrl,
                    documentType = input.manifest.documentType,
                    pdfSha256 = result.fingerprint,
                    pageCount = result.pageCount,
                    evaluatedPages = input.manifest.pages,
                    dpi = input.dpi,
                    engine = result.descriptor.engineId,
                    modelVersion = result.descriptor.modelVersion,
                    firstPass = result.firstPass,
                    cachedPass = result.cachedPass,
                    metrics = result.metrics.toReport(),
                    detectedNodes = result.actual.flatten(),
                ),
            ),
        )
    }

    private suspend fun analyzePages(
        backend: PdfReaderBackend,
        input: ExternalEvaluationInput,
        engine: PpStructureV3Engine,
        store: PdfPageLayoutStore,
        cache: PdfAnalysisCache,
        cacheKey: PdfAnalysisCacheKey,
    ): ExternalPassReport {
        val pageTimes = mutableListOf<Long>()
        var cacheHits = 0
        var modelCalls = 0
        var peakSidecarRss = 0L
        input.manifest.pages.forEach { pageIndex ->
            val started = System.nanoTime()
            val layout =
                cache.loadPage(cacheKey, pageIndex)?.also {
                    cacheHits++
                } ?: run {
                    modelCalls++
                    val raster =
                        backend.render(
                            PdfRasterRequest(
                                path = input.pdf,
                                pageIndex = pageIndex,
                                dpi = input.dpi.toFloat(),
                                purpose = PdfRasterPurpose.ANALYSIS,
                                priority = PdfRasterPriority.BACKGROUND,
                            ),
                        )
                    val request =
                        DocumentAnalysisRequest(
                            path = input.pdf,
                            pageIndex = pageIndex,
                            width = raster.pixelWidth,
                            height = raster.pixelHeight,
                            dpi = input.dpi,
                            language = input.manifest.language,
                            imagePng = raster.toPng(),
                        )
                    engine.analyzePage(request).also { generated -> cache.savePage(cacheKey, generated) }
                }
            store.write(layout)
            pageTimes += ((System.nanoTime() - started) / 1_000_000.0).roundToLong()
            peakSidecarRss = maxOf(peakSidecarRss, sidecarRssBytes())
        }
        return ExternalPassReport(
            pageCount = input.manifest.pages.size,
            cacheHits = cacheHits,
            modelCalls = modelCalls,
            firstPageMs = pageTimes.firstOrNull() ?: 0L,
            p95PageMs = percentile95(pageTimes),
            peakSidecarRssBytes = peakSidecarRss,
        )
    }

    private fun PdfPageRaster.toPng(): ByteArray {
        val image = BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB)
        var offset = 0
        repeat(pixelHeight) { y ->
            repeat(pixelWidth) { x ->
                val red = rgbaBytes[offset++].toInt() and 0xff
                val green = rgbaBytes[offset++].toInt() and 0xff
                val blue = rgbaBytes[offset++].toInt() and 0xff
                val alpha = rgbaBytes[offset++].toInt() and 0xff
                image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG encoder is unavailable." }
            output.toByteArray()
        }
    }

    private fun percentile95(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * 0.95).roundToInt()]
    }

    private fun sidecarRssBytes(): Long {
        val status =
            System
                .getProperty("xuexue.pdf.eval.sidecarPid")
                ?.toLongOrNull()
                ?.let { pid -> Path.of("/proc", pid.toString(), "status") }
                ?.takeIf { path -> Files.isRegularFile(path) }
        return status
            ?.let { path -> Regex("^VmRSS:\\s+(\\d+) kB$", RegexOption.MULTILINE).find(Files.readString(path)) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.times(1024)
            ?: 0L
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
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

    private fun propertyPath(name: String): Path? =
        System
            .getProperty(name)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)

    private data class ExternalEvaluationInput(
        val pdf: Path,
        val manifest: ExternalEvaluationManifest,
        val reportPath: Path,
        val endpoint: URI,
        val dpi: Int,
    )

    private data class ExternalEvaluationResult(
        val pageCount: Int,
        val fingerprint: String,
        val descriptor: DocumentAnalysisEngineDescriptor,
        val firstPass: ExternalPassReport,
        val cachedPass: ExternalPassReport,
        val metrics: ChapterEvaluationMetrics,
        val actual: PdfOutlineDocument,
    )

    @Serializable
    private data class ExternalEvaluationManifest(
        val datasetId: String,
        val sourceUrl: String,
        val documentType: String,
        val pdfSha256: String,
        val language: String = "en",
        val pages: List<Int>,
        val expected: List<ExpectedChapter>,
    )

    @Serializable
    private data class ExpectedChapter(
        val title: String,
        val pageIndex: Int,
        val level: Int = 1,
    )

    @Serializable
    private data class ExternalEvaluationReport(
        val datasetId: String,
        val sourceUrl: String,
        val documentType: String,
        val pdfSha256: String,
        val pageCount: Int,
        val evaluatedPages: List<Int>,
        val dpi: Int,
        val engine: String,
        val modelVersion: String?,
        val firstPass: ExternalPassReport,
        val cachedPass: ExternalPassReport,
        val metrics: ExternalMetricsReport,
        val detectedNodes: List<DetectedNodeReport>,
    )

    @Serializable
    private data class ExternalPassReport(
        val pageCount: Int,
        val cacheHits: Int,
        val modelCalls: Int,
        val firstPageMs: Long,
        val p95PageMs: Long,
        val peakSidecarRssBytes: Long,
    )

    @Serializable
    private data class ExternalMetricsReport(
        val titlePrecision: Float,
        val titleRecall: Float,
        val hierarchyEdgeAccuracy: Float,
        val targetPageAccuracy: Float,
        val expectedTitles: Int,
        val actualTitles: Int,
        val matchedTitles: Int,
    )

    @Serializable
    private data class DetectedNodeReport(
        val title: String,
        val pageIndex: Int,
        val level: Int,
        val parentTitle: String?,
        val reviewState: String,
        val confidence: Float,
        val evidenceReasons: List<String>,
    )

    private fun ExpectedChapter.toNode(index: Int): PdfOutlineNode =
        PdfOutlineNode(
            id = "expected-$index",
            title = title,
            destination = PdfOutlineDestination(pageIndex),
            source = PdfOutlineSource.MANUAL,
            confidence = 1f,
            reviewState = PdfOutlineReviewState.CONFIRMED,
        )

    private fun List<ExpectedChapter>.toOutline(
        relativePath: String,
        fingerprint: String,
    ): PdfOutlineDocument {
        val roots = mutableListOf<MutableExpectedNode>()
        val stack = mutableListOf<Pair<Int, MutableExpectedNode>>()
        forEachIndexed { index, chapter ->
            require(chapter.level > 0) { "Expected chapter levels must be positive." }
            while ((stack.lastOrNull()?.first ?: 0) >= chapter.level) stack.removeLast()
            val node = MutableExpectedNode(chapter.toNode(index))
            stack
                .lastOrNull()
                ?.second
                ?.children
                ?.add(node) ?: roots.add(node)
            stack.add(chapter.level to node)
        }
        return PdfOutlineDocument(
            relativePath = relativePath,
            documentFingerprint = fingerprint,
            nodes = roots.map(MutableExpectedNode::toNode),
        )
    }

    private data class MutableExpectedNode(
        val node: PdfOutlineNode,
        val children: MutableList<MutableExpectedNode> = mutableListOf(),
    ) {
        fun toNode(): PdfOutlineNode = node.copy(children = children.map(MutableExpectedNode::toNode))
    }

    private fun ChapterEvaluationMetrics.toReport(): ExternalMetricsReport =
        ExternalMetricsReport(
            titlePrecision,
            titleRecall,
            hierarchyEdgeAccuracy,
            targetPageAccuracy,
            expectedTitles,
            actualTitles,
            matchedTitles,
        )

    private fun PdfOutlineDocument.flatten(): List<DetectedNodeReport> = flattenNodes(nodes, null, 1)

    private fun flattenNodes(
        nodes: List<PdfOutlineNode>,
        parentTitle: String?,
        level: Int,
    ): List<DetectedNodeReport> =
        nodes.flatMap { node ->
            listOf(
                DetectedNodeReport(
                    title = node.title,
                    pageIndex = node.destination.pageIndex,
                    level = level,
                    parentTitle = parentTitle,
                    reviewState = node.reviewState.name,
                    confidence = node.confidence ?: 0f,
                    evidenceReasons = node.evidence?.reasons.orEmpty(),
                ),
            ) + flattenNodes(node.children, node.title, level + 1)
        }
}

private fun DocumentAnalysisEngineDescriptor.toProvenance(
    dpi: Int,
    language: String,
): AnalysisProvenance =
    AnalysisProvenance(
        engineId = engineId,
        engineVersion = engineVersion,
        modelVersion = modelVersion,
        language = language,
        dpi = dpi,
    )
