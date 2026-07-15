package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfLink
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint
import dev.xuexue.platform.PdfPageRaster
import dev.xuexue.platform.PdfRasterLayer
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfRasterRequest
import dev.xuexue.platform.PdfReaderBackend
import dev.xuexue.platform.PdfSearchMatch
import dev.xuexue.platform.PdfTextSelection
import dev.xuexue.platform.WorkspaceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.pathString

internal class WorkbenchPdfController(
    private val scope: CoroutineScope,
    private val workspaceService: WorkspaceService,
    private val backend: PdfReaderBackend,
    private val workspaceRoot: () -> Path?,
    private val reloadWorkspace: suspend () -> Unit,
    private val openRelative: (String, Boolean) -> Unit,
    private val persistWorkbench: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private val annotationsMutex = Mutex()

    fun setPosition(
        document: DocumentTab?,
        page: Int?,
        scrollOffset: Int?,
        zoom: Float?,
        persist: Boolean = true,
    ) {
        document?.takeIf { it.kind == DocumentKind.PDF }?.let { tab ->
            if (page != null) {
                val nextPage = page.coerceAtLeast(0)
                if (scrollOffset == null) {
                    tab.pdfScrollOffset = 0
                    tab.pdfHorizontalOffset = 0
                    tab.pdfVerticalOffset = 0
                    tab.pdfNavigationSerial++
                }
                tab.pdfPage = nextPage
            }
            if (scrollOffset != null) tab.pdfScrollOffset = scrollOffset.coerceAtLeast(0)
            if (zoom != null) {
                tab.pdfZoom = zoom.coerceIn(0.4f, 4f)
                tab.pdfFitMode = "MANUAL"
            }
            if (persist) persistWorkbench()
        }
    }

    fun setFitMode(
        document: DocumentTab?,
        mode: String,
    ) {
        if (mode !in PDF_FIT_MODES) return
        document?.takeIf { it.kind == DocumentKind.PDF }?.let { tab ->
            tab.pdfFitMode = mode
            if (mode == "HEIGHT") tab.pdfViewMode = "SINGLE"
            persistWorkbench()
        }
    }

    suspend fun inspect(document: DocumentTab): PdfDocumentMetadata = backend.inspect(pdfPath(document))

    suspend fun pageDimensions(
        document: DocumentTab,
        pageIndex: Int,
    ): PdfPageDimensions = backend.pageDimensions(pdfPath(document), pageIndex)

    suspend fun render(
        document: DocumentTab,
        pageIndex: Int,
        dpi: Float,
        purpose: PdfRasterPurpose,
        viewport: PdfNormalizedRect? = null,
        priority: PdfRasterPriority = PdfRasterPriority.VISIBLE,
        layer: PdfRasterLayer = PdfRasterLayer.CONTENT,
    ): PdfPageRaster =
        backend.render(
            PdfRasterRequest(
                path = pdfPath(document),
                pageIndex = pageIndex,
                dpi = dpi,
                purpose = purpose,
                viewport = viewport,
                priority = priority,
                layer = layer,
            ),
        )

    suspend fun search(
        document: DocumentTab,
        query: String,
    ): List<PdfSearchMatch> = backend.search(pdfPath(document), query)

    suspend fun extractPageText(
        document: DocumentTab,
        pageIndex: Int,
    ): String = backend.extractPageText(pdfPath(document), pageIndex)

    suspend fun selectText(
        document: DocumentTab,
        pageIndex: Int,
        start: PdfPagePoint,
        end: PdfPagePoint,
    ): PdfTextSelection = backend.selectText(pdfPath(document), pageIndex, start, end)

    suspend fun links(
        document: DocumentTab,
        pageIndex: Int,
    ): List<PdfLink> = backend.links(pdfPath(document), pageIndex)

    fun addAnnotation(
        document: DocumentTab,
        page: Int,
        kind: String,
        text: String,
        regions: List<PdfHighlightRegion> = emptyList(),
    ) {
        if (document.kind != DocumentKind.PDF) return
        document.pdfAnnotations +=
            PdfAnnotation(
                id = System.currentTimeMillis(),
                page = page,
                kind = kind,
                text = text.ifBlank { "$kind on page ${page + 1}" },
                regions =
                    if (kind == "highlight" && regions.isEmpty()) {
                        listOf(PdfHighlightRegion(0f, 0f, 1f, 1f))
                    } else {
                        regions
                    },
            )
        persistAnnotations(document)
        onMessage("PDF annotation added")
    }

    fun removeAnnotation(
        document: DocumentTab,
        annotation: PdfAnnotation,
    ) {
        document.pdfAnnotations.remove(annotation)
        persistAnnotations(document)
    }

    fun loadAnnotations(document: DocumentTab) {
        val root = workspaceRoot() ?: return
        val documentPath = document.relativePath
        scope.launch {
            val annotations =
                withContext(Dispatchers.IO) {
                    annotationsMutex.withLock {
                        val path = annotationsPath(root)
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@withLock emptyList()
                        runCatching {
                            json.decodeFromString<Map<String, List<PdfAnnotation>>>(Files.readString(path))[documentPath].orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }
            document.pdfAnnotations.clear()
            document.pdfAnnotations += annotations
        }
    }

    fun createReferenceNote(
        document: DocumentTab,
        page: Int,
        quote: String,
    ) {
        val root = workspaceRoot() ?: return
        val sourcePath = document.relativePath
        val displayName = document.displayName
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val path = workspaceService.createMarkdown(root)
                    val reference = "[[$sourcePath#page=${page + 1}]]"
                    val body =
                        buildString {
                            append("# $displayName - Page ${page + 1}\n\n")
                            if (quote.isNotBlank()) append("> ${quote.trim().replace("\n", "\n> ")}\n\n")
                            append("Source: $reference\n")
                        }
                    workspaceService.atomicWrite(path, body)
                    path
                }
            }.onSuccess { path ->
                reloadWorkspace()
                openRelative(root.relativize(path).pathString.replace('\\', '/'), true)
                onMessage("PDF reference note created")
            }.onFailure { onError(it.message ?: "Could not create the reference note.") }
        }
    }

    private fun persistAnnotations(document: DocumentTab) {
        val root = workspaceRoot() ?: return
        val documentPath = document.relativePath
        val annotations = document.pdfAnnotations.toList()
        scope.launch {
            val failure =
                withContext(Dispatchers.IO) {
                    annotationsMutex.withLock {
                        runCatching {
                            val path = annotationsPath(root)
                            val existing =
                                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                                    json.decodeFromString<Map<String, List<PdfAnnotation>>>(Files.readString(path)).toMutableMap()
                                } else {
                                    mutableMapOf()
                                }
                            existing[documentPath] = annotations
                            workspaceService.atomicWrite(path, json.encodeToString(existing))
                        }.exceptionOrNull()
                    }
                }
            failure?.let { onError(it.message ?: "Could not persist PDF annotations.") }
        }
    }

    private fun pdfPath(document: DocumentTab): Path {
        require(document.kind == DocumentKind.PDF) { "The document is not a PDF." }
        val root = workspaceRoot() ?: error("No workspace is open.")
        val path = workspaceService.resolveExistingInside(root, document.relativePath)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "PDF is outside the workspace or no longer exists."
        }
        return path
    }

    private fun annotationsPath(root: Path): Path = workspaceService.resolveForWriteInside(root, ".xuexue/pdf-annotations.json")

    private companion object {
        val PDF_FIT_MODES = setOf("MANUAL", "WIDTH", "HEIGHT")
    }
}
