// The PDF screen owns several focused panel and viewport composables in one interaction surface.
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming", "TooManyFunctions")
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.xuexue.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WidthFull
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.DocumentTab
import dev.xuexue.WorkbenchState
import dev.xuexue.platform.PdfDocumentMetadata
import dev.xuexue.platform.PdfLink
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfSearchMatch
import dev.xuexue.platform.PdfTextSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlin.math.roundToInt

private enum class PdfSidePanel { NONE, THUMBNAILS, ANNOTATIONS, OUTLINE }

private enum class PdfViewMode { CONTINUOUS, SINGLE, DOUBLE }

private enum class PdfFitMode { MANUAL, WIDTH, HEIGHT }

@Composable
internal fun PdfDocument(
    state: WorkbenchState,
    tab: DocumentTab,
    compact: Boolean = false,
) {
    var metadata by remember(tab.relativePath) { mutableStateOf(PdfDocumentMetadata(0)) }
    val pageCount = metadata.pageCount
    var loadError by remember(tab.relativePath) { mutableStateOf<String?>(null) }
    var loading by remember(tab.relativePath) { mutableStateOf(true) }
    var loadAttempt by remember(tab.relativePath) { mutableStateOf(0) }
    var pageInput by remember(tab.pdfPage) { mutableStateOf((tab.pdfPage + 1).toString()) }
    val rotation = tab.pdfRotation
    var showSearch by remember(tab.relativePath) { mutableStateOf(false) }
    var searchQuery by remember(tab.relativePath) { mutableStateOf("") }
    var searchRequest by remember(tab.relativePath) { mutableStateOf(0) }
    var searchMatches by remember(tab.relativePath) { mutableStateOf<List<PdfSearchMatch>>(emptyList()) }
    var searchMatchIndex by remember(tab.relativePath) { mutableStateOf(0) }
    var searchRunning by remember(tab.relativePath) { mutableStateOf(false) }
    val viewMode = runCatching { PdfViewMode.valueOf(tab.pdfViewMode) }.getOrDefault(PdfViewMode.CONTINUOUS)
    val fitMode = runCatching { PdfFitMode.valueOf(tab.pdfFitMode) }.getOrDefault(PdfFitMode.WIDTH)
    val configuredSidePanel = runCatching { PdfSidePanel.valueOf(tab.pdfSidePanel) }.getOrDefault(PdfSidePanel.THUMBNAILS)
    val sidePanel = if (compact) PdfSidePanel.NONE else configuredSidePanel
    val colorMode = runCatching { PdfColorMode.valueOf(tab.pdfColorMode) }.getOrDefault(PdfColorMode.NORMAL)
    val remoteDownload = state.remotePdfDownloadStates[tab.relativePath]
    var selectedPage by remember(tab.relativePath) { mutableStateOf<Int?>(null) }
    var selectedTextPage by remember(tab.relativePath) { mutableStateOf<Int?>(null) }
    var textSelection by remember(tab.relativePath) { mutableStateOf<PdfTextSelection?>(null) }
    var selectionRequest by remember(tab.relativePath) { mutableStateOf<PdfSelectionRequest?>(null) }
    var selectionCommitted by remember(tab.relativePath) { mutableStateOf(false) }
    var selectionGestureId by remember(tab.relativePath) { mutableStateOf(0) }
    val selectionRequests = remember(tab.relativePath) { Channel<PdfSelectionRequest>(Channel.CONFLATED) }
    var currentPageDimensions by remember(tab.relativePath, tab.pdfPage) { mutableStateOf(DEFAULT_PAGE_DIMENSIONS) }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = tab.pdfPage,
            initialFirstVisibleItemScrollOffset = tab.pdfScrollOffset,
        )
    val singlePageVerticalScrollState = rememberScrollState(tab.pdfVerticalOffset)
    val singlePageHorizontalScrollState = rememberScrollState(tab.pdfHorizontalOffset)
    val doublePageVerticalScrollState = rememberScrollState(tab.pdfVerticalOffset)
    val doublePageHorizontalScrollState = rememberScrollState(tab.pdfHorizontalOffset)
    var pendingZoomAnchor by remember(tab.relativePath) { mutableStateOf<PdfZoomAnchor?>(null) }
    var zoomPersistenceSerial by remember(tab.relativePath) { mutableStateOf(0) }
    var scrollPersistenceSerial by remember(tab.relativePath) { mutableStateOf(0) }
    val singlePageRenderDpi = rememberPdfRenderDpi("${tab.relativePath}:single", 110f * tab.pdfZoom)
    val beginTextSelection: (Int) -> Unit = { page ->
        selectionGestureId++
        selectedPage = page
        selectedTextPage = page
        textSelection = null
        selectionRequest = null
        selectionCommitted = false
    }
    val requestTextSelection: (Int, PdfPagePoint, PdfPagePoint, Boolean) -> Unit = { page, start, end, isFinal ->
        selectedPage = page
        selectedTextPage = page
        val request = PdfSelectionRequest(page, start, end, isFinal, selectionGestureId)
        selectionRequest = request
        selectionCommitted = false
        selectionRequests.trySend(request)
    }
    val clearTextSelection: () -> Unit = {
        selectionGestureId++
        selectedPage = null
        selectedTextPage = null
        textSelection = null
        selectionRequest = null
        selectionCommitted = false
    }
    val textSelectionInteractionForPage: (Int) -> PdfTextSelectionInteraction = { page ->
        PdfTextSelectionInteraction(
            onStart = { beginTextSelection(page) },
            onChange = { start, end, isFinal -> requestTextSelection(page, start, end, isFinal) },
            onCancel = clearTextSelection,
        )
    }
    val selectionActionsForPage: (Int) -> PdfSelectionActions? = { page ->
        textSelection
            ?.takeIf { selectedTextPage == page && selectionCommitted }
            ?.let { selected ->
                val reference = "[[${tab.relativePath}#page=${page + 1}]]"
                val annotationRegions = selected.regions.toPdfHighlightRegions()
                PdfSelectionActions(
                    text = selected.text,
                    regions = selected.regions,
                    onCopy = {
                        Toolkit
                            .getDefaultToolkit()
                            .systemClipboard
                            .setContents(StringSelection(selected.text), null)
                    },
                    onCopyReference = {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(reference), null)
                    },
                    onHighlight = {
                        state.addPdfAnnotation(tab, page, "highlight", selected.text, annotationRegions)
                        clearTextSelection()
                    },
                    onComment = { comment ->
                        state.addPdfAnnotation(tab, page, "comment", comment, annotationRegions)
                        clearTextSelection()
                    },
                    onQuote = { state.createPdfReferenceNote(tab, page, selected.text) },
                    onAgent = {
                        state.addAgentSnippetContext(
                            "${tab.displayName} · 第 ${page + 1} 页",
                            "Source: $reference\n\n${selected.text}",
                        )
                    },
                    onDismiss = clearTextSelection,
                )
            }
    }
    val openPdfLink: (PdfLink) -> Unit = { link ->
        when {
            link.targetPage != null -> {
                state.setPdfPosition(
                    page = link.targetPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                    document = tab,
                )
            }

            link.external && Desktop.isDesktopSupported() -> {
                runCatching { Desktop.getDesktop().browse(URI(link.uri)) }
            }
        }
    }
    val applyInteractiveZoom: (Float, Offset) -> Unit = { requestedZoom, position ->
        val zoom = requestedZoom.coerceIn(MIN_PDF_ZOOM, MAX_PDF_ZOOM)
        if (zoom != tab.pdfZoom) {
            pendingZoomAnchor =
                PdfZoomAnchor(
                    sourceZoom = tab.pdfZoom,
                    pointerPosition = position,
                    viewMode = viewMode,
                    firstVisiblePage =
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { item ->
                                position.y in item.offset.toFloat()..(item.offset + item.size).toFloat()
                            }.let { item -> item?.index ?: listState.firstVisibleItemIndex },
                    firstVisiblePageOffset =
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { item ->
                                position.y in item.offset.toFloat()..(item.offset + item.size).toFloat()
                            }.let { item ->
                                item?.let { (position.y - it.offset).toInt().coerceAtLeast(0) }
                                    ?: listState.firstVisibleItemScrollOffset
                            },
                    horizontalOffset = singlePageHorizontalScrollState.value,
                    verticalOffset = singlePageVerticalScrollState.value,
                    doubleHorizontalOffset = doublePageHorizontalScrollState.value,
                    doubleVerticalOffset = doublePageVerticalScrollState.value,
                )
            state.setPdfPosition(zoom = zoom, document = tab, persist = false)
            zoomPersistenceSerial++
        }
    }
    LaunchedEffect(tab.relativePath, loadAttempt) {
        loading = true
        loadError = null
        state.pdfPerformanceMetrics.beginFirstViewportMeasurement()
        try {
            metadata =
                runTrackedPdfTask(state, "pdf-load:${tab.relativePath}", "PDF 加载", tab.displayName) {
                    state.inspectPdf(tab)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.message ?: "PDF 加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(selectionRequests) {
        selectionRequests.receiveAsFlow().collect { request ->
            if (request.gestureId != selectionGestureId) return@collect
            try {
                val selected = state.selectPdfText(tab, request.page, request.start, request.end)
                if (request.gestureId == selectionGestureId && selectedTextPage == request.page) {
                    textSelection = selected.takeIf { it.text.isNotBlank() && it.regions.isNotEmpty() }
                    if (selectionRequest == request) {
                        selectionCommitted = request.isFinal && textSelection != null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (selectionRequest == request) {
                    textSelection = null
                    selectionCommitted = false
                }
            }
        }
    }
    LaunchedEffect(tab.relativePath, tab.pdfPage, pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        try {
            currentPageDimensions = state.inspectPdfPage(tab, tab.pdfPage.coerceIn(0, pageCount - 1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            currentPageDimensions = DEFAULT_PAGE_DIMENSIONS
        }
    }
    LaunchedEffect(tab.pdfNavigationSerial, tab.pdfNavigationTopNormalized, viewMode, pageCount) {
        if (viewMode == PdfViewMode.CONTINUOUS && pageCount > 0) {
            val targetPage = tab.pdfPage.coerceIn(0, pageCount - 1)
            if (
                listState.firstVisibleItemIndex != targetPage ||
                listState.firstVisibleItemScrollOffset != tab.pdfScrollOffset
            ) {
                listState.scrollToItem(targetPage, tab.pdfScrollOffset)
            }
            tab.pdfNavigationTopNormalized?.let { top ->
                val pageItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetPage }
                if (pageItem != null) {
                    listState.scrollBy(pageItem.size * top)
                    tab.pdfNavigationTopNormalized = null
                }
            }
        }
    }
    LaunchedEffect(tab.pdfNavigationSerial, viewMode) {
        val horizontalState =
            when (viewMode) {
                PdfViewMode.SINGLE -> singlePageHorizontalScrollState
                PdfViewMode.DOUBLE -> doublePageHorizontalScrollState
                PdfViewMode.CONTINUOUS -> return@LaunchedEffect
            }
        val verticalState =
            when (viewMode) {
                PdfViewMode.SINGLE -> singlePageVerticalScrollState
                PdfViewMode.DOUBLE -> doublePageVerticalScrollState
                PdfViewMode.CONTINUOUS -> return@LaunchedEffect
            }
        horizontalState.scrollTo(tab.pdfHorizontalOffset)
        verticalState.scrollTo(tab.pdfVerticalOffset)
        snapshotFlow { horizontalState.value to verticalState.value }
            .distinctUntilChanged()
            .collect { (horizontal, vertical) ->
                if (horizontal != tab.pdfHorizontalOffset || vertical != tab.pdfVerticalOffset) {
                    tab.pdfHorizontalOffset = horizontal
                    tab.pdfVerticalOffset = vertical
                    scrollPersistenceSerial++
                }
            }
    }
    LaunchedEffect(listState, viewMode) {
        if (viewMode != PdfViewMode.CONTINUOUS) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (page, offset) ->
                if (page != tab.pdfPage || offset != tab.pdfScrollOffset) {
                    state.setPdfPosition(
                        page = page,
                        scrollOffset = offset,
                        document = tab,
                        persist = false,
                    )
                    scrollPersistenceSerial++
                }
            }
    }
    LaunchedEffect(listState, viewMode, tab.relativePath, pageCount) {
        if (viewMode != PdfViewMode.CONTINUOUS || pageCount <= 0) return@LaunchedEffect
        var previousPosition: PdfScrollPosition? = null
        snapshotFlow {
            PdfScrollPosition(
                firstVisiblePage = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                lastVisiblePage =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index
                        ?: listState.firstVisibleItemIndex,
            )
        }.distinctUntilChanged().collectLatest { position ->
            val targetPage = pdfPrefetchTarget(previousPosition, position, pageCount)
            previousPosition = position
            if (targetPage != null) {
                delay(PDF_PREFETCH_DELAY_MILLIS)
                try {
                    state.renderPdfPage(
                        tab,
                        targetPage,
                        PDF_PREVIEW_DPI,
                        PdfRasterPurpose.THUMBNAIL,
                        priority = PdfRasterPriority.ADJACENT,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Prefetch is opportunistic; visible-page rendering owns user-facing failures.
                }
            }
        }
    }
    LaunchedEffect(tab.pdfZoom, viewMode) {
        val anchor = pendingZoomAnchor ?: return@LaunchedEffect
        val zoomRatio = tab.pdfZoom / anchor.sourceZoom
        when (anchor.viewMode) {
            PdfViewMode.CONTINUOUS -> {
                listState.scrollToItem(
                    anchor.firstVisiblePage,
                    (anchor.firstVisiblePageOffset * zoomRatio - anchor.pointerPosition.y)
                        .roundToInt()
                        .coerceAtLeast(0),
                )
            }

            PdfViewMode.SINGLE -> {
                singlePageHorizontalScrollState.scrollTo(
                    zoomedScrollOffset(
                        currentOffset = anchor.horizontalOffset,
                        anchorPosition = anchor.pointerPosition.x,
                        zoomRatio = zoomRatio,
                    ),
                )
                singlePageVerticalScrollState.scrollTo(
                    zoomedScrollOffset(
                        currentOffset = anchor.verticalOffset,
                        anchorPosition = anchor.pointerPosition.y,
                        zoomRatio = zoomRatio,
                    ),
                )
            }

            PdfViewMode.DOUBLE -> {
                doublePageHorizontalScrollState.scrollTo(
                    zoomedScrollOffset(
                        currentOffset = anchor.doubleHorizontalOffset,
                        anchorPosition = anchor.pointerPosition.x,
                        zoomRatio = zoomRatio,
                    ),
                )
                doublePageVerticalScrollState.scrollTo(
                    zoomedScrollOffset(
                        currentOffset = anchor.doubleVerticalOffset,
                        anchorPosition = anchor.pointerPosition.y,
                        zoomRatio = zoomRatio,
                    ),
                )
            }
        }
        pendingZoomAnchor = null
    }
    LaunchedEffect(zoomPersistenceSerial) {
        if (zoomPersistenceSerial == 0) return@LaunchedEffect
        delay(PDF_ZOOM_PERSISTENCE_DELAY_MILLIS)
        state.persistLayout()
    }
    LaunchedEffect(scrollPersistenceSerial) {
        if (scrollPersistenceSerial == 0) return@LaunchedEffect
        delay(PDF_SCROLL_PERSISTENCE_DELAY_MILLIS)
        state.persistLayout()
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest == 0 || searchQuery.isBlank()) return@LaunchedEffect
        searchRunning = true
        try {
            searchMatches =
                runTrackedPdfTask(
                    state,
                    "pdf-search:${tab.relativePath}:$searchRequest",
                    "PDF 搜索",
                    "${tab.displayName} · ${searchQuery.trim()}",
                ) {
                    state.searchPdf(tab, searchQuery)
                }
            searchMatchIndex = 0
            searchMatches.firstOrNull()?.let { state.setPdfPosition(page = it.pageIndex, document = tab) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            searchMatches = emptyList()
            searchMatchIndex = 0
        } finally {
            searchRunning = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(43.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorTool(Icons.AutoMirrored.Filled.NavigateBefore, "上一页", { if (tab.pdfPage > 0) state.setPdfPosition(page = tab.pdfPage - 1, document = tab) })
            BasicTextField(
                value = pageInput,
                onValueChange = { pageInput = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                modifier =
                    Modifier
                        .width(62.dp)
                        .height(32.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(4.dp),
                        ).padding(horizontal = 8.dp, vertical = 6.dp)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                pageInput.toIntOrNull()?.let { page ->
                                    state.setPdfPosition(
                                        page = (page - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                        document = tab,
                                    )
                                }
                                true
                            } else {
                                false
                            }
                        },
                textStyle =
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
            )
            Text(" / ${pageCount.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EditorTool(Icons.AutoMirrored.Filled.NavigateNext, "下一页", {
                val step = if (viewMode == PdfViewMode.DOUBLE) 2 else 1
                if (tab.pdfPage + step < pageCount) state.setPdfPosition(page = tab.pdfPage + step, document = tab)
            })
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 5.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            EditorTool(Icons.Default.ZoomOut, "缩小", { state.setPdfPosition(zoom = tab.pdfZoom - 0.15f, document = tab) })
            Text("${(tab.pdfZoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(45.dp))
            EditorTool(Icons.Default.ZoomIn, "放大", { state.setPdfPosition(zoom = tab.pdfZoom + 0.15f, document = tab) })
            EditorTool(Icons.Default.WidthFull, "适宽", { state.setPdfFitMode(PdfFitMode.WIDTH.name, tab) })
            EditorTool(Icons.Default.Height, "适高", { state.setPdfFitMode(PdfFitMode.HEIGHT.name, tab) })
            EditorTool(Icons.AutoMirrored.Filled.RotateRight, "顺时针旋转", {
                tab.pdfRotation = (tab.pdfRotation + 90f) % 360f
                state.persistLayout()
            })
            EditorTool(
                if (colorMode == PdfColorMode.NIGHT) Icons.Default.LightMode else Icons.Default.DarkMode,
                if (colorMode == PdfColorMode.NIGHT) "关闭夜间阅读" else "夜间阅读",
                {
                    tab.pdfColorMode =
                        if (colorMode == PdfColorMode.NIGHT) PdfColorMode.NORMAL.name else PdfColorMode.NIGHT.name
                    state.persistLayout()
                },
            )
            if (!compact) {
                EditorTool(Icons.AutoMirrored.Filled.ViewSidebar, "缩略图", {
                    tab.pdfSidePanel = (if (sidePanel == PdfSidePanel.THUMBNAILS) PdfSidePanel.NONE else PdfSidePanel.THUMBNAILS).name
                    state.persistLayout()
                })
                EditorTool(Icons.AutoMirrored.Filled.Comment, "批注", {
                    tab.pdfSidePanel = (if (sidePanel == PdfSidePanel.ANNOTATIONS) PdfSidePanel.NONE else PdfSidePanel.ANNOTATIONS).name
                    state.persistLayout()
                })
                EditorTool(Icons.AutoMirrored.Filled.FormatListBulleted, "目录", {
                    tab.pdfSidePanel =
                        (if (sidePanel == PdfSidePanel.OUTLINE) PdfSidePanel.NONE else PdfSidePanel.OUTLINE).name
                    state.persistLayout()
                })
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(4.dp),
            ) {
                Row {
                    PdfModeItem("连续", PdfViewMode.CONTINUOUS, viewMode) {
                        tab.pdfViewMode = it.name
                        state.persistLayout()
                    }
                    PdfModeItem("单页", PdfViewMode.SINGLE, viewMode) {
                        tab.pdfViewMode = it.name
                        state.persistLayout()
                    }
                    PdfModeItem("双页", PdfViewMode.DOUBLE, viewMode) {
                        tab.pdfViewMode = it.name
                        state.persistLayout()
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            EditorTool(Icons.Default.Search, "文档内搜索", { showSearch = !showSearch })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        remoteDownload?.let { download -> PdfRemoteDownloadBar(state, download) }
        if (showSearch) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(43.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    searchQuery,
                    { searchQuery = it },
                    singleLine = true,
                    placeholder = { Text("在 PDF 中搜索") },
                    modifier =
                        Modifier.weight(1f).widthIn(min = 96.dp, max = 260.dp).height(35.dp).onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                searchRequest++
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.labelMedium,
                )
                EditorTool(Icons.Default.Search, "查找") {
                    if (searchQuery.isNotBlank() && !searchRunning) searchRequest++
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        searchRunning -> "正在搜索…"
                        searchRequest == 0 -> ""
                        searchMatches.isEmpty() -> "无结果"
                        else -> "${searchMatchIndex + 1} / ${searchMatches.size} · 第 ${searchMatches[searchMatchIndex].pageIndex + 1} 页"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.75f, fill = false),
                )
                if (searchMatches.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            searchMatchIndex = (searchMatchIndex - 1 + searchMatches.size) % searchMatches.size
                            state.setPdfPosition(page = searchMatches[searchMatchIndex].pageIndex, document = tab)
                        },
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "上一个命中", Modifier.size(17.dp)) }
                    IconButton(
                        onClick = {
                            searchMatchIndex = (searchMatchIndex + 1) % searchMatches.size
                            state.setPdfPosition(page = searchMatches[searchMatchIndex].pageIndex, document = tab)
                        },
                        modifier = Modifier.size(28.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "下一个命中", Modifier.size(17.dp)) }
                    Text(
                        searchMatches[searchMatchIndex].preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(Modifier.fillMaxSize()) {
            if (sidePanel != PdfSidePanel.NONE) {
                PdfSidePanelView(state, tab, pageCount, sidePanel, onPage = { page ->
                    state.setPdfPosition(page = page, document = tab)
                    selectedPage = page
                })
                VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            val viewportModifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFFE4E7E5))
                    .pdfCtrlWheelZoom { scrollDelta, position ->
                        applyInteractiveZoom(
                            pdfZoomForScroll(
                                currentZoom = tab.pdfZoom,
                                verticalScroll = scrollDelta,
                                wheelZoomMultiplier = state.documentWheelZoomMultiplier,
                            ),
                            position,
                        )
                    }.then(
                        if (compact) {
                            Modifier.pointerInput(tab.relativePath, viewMode) {
                                detectTransformGestures { centroid, _, gestureZoom, _ ->
                                    if (gestureZoom < 0.99f || gestureZoom > 1.01f) {
                                        applyInteractiveZoom(tab.pdfZoom * gestureZoom, centroid)
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
            Box(viewportModifier, contentAlignment = Alignment.Center) {
                when {
                    loadError != null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(loadError!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { loadAttempt++ }) { Text("重试") }
                        }
                    }

                    loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(10.dp))
                            Text("正在加载 PDF…", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    viewMode == PdfViewMode.CONTINUOUS && pageCount > 0 -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(pageCount) { page ->
                                PdfPageItem(
                                    state = state,
                                    tab = tab,
                                    page = page,
                                    zoom = tab.pdfZoom * if (compact) 0.72f else 1f,
                                    rotation = rotation,
                                    selected = selectedPage == page,
                                    searchMatch = searchMatches.any { it.pageIndex == page },
                                    searchRegions =
                                        searchMatches
                                            .asSequence()
                                            .filter { it.pageIndex == page }
                                            .flatMap { it.regions.asSequence() }
                                            .toList(),
                                    selectionRegions =
                                        textSelection
                                            ?.takeIf { selectedTextPage == page }
                                            ?.regions
                                            .orEmpty(),
                                    selectionActions = selectionActionsForPage(page),
                                    textSelectionInteraction = textSelectionInteractionForPage(page),
                                    onLink = openPdfLink,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { selectedPage = page }
                            }
                        }
                    }

                    viewMode == PdfViewMode.DOUBLE && pageCount > 0 -> {
                        val fitWidth = fitMode == PdfFitMode.WIDTH
                        Row(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(doublePageVerticalScrollState)
                                .then(
                                    if (fitWidth) {
                                        Modifier
                                    } else {
                                        Modifier.horizontalScroll(doublePageHorizontalScrollState)
                                    },
                                ).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val firstPage = tab.pdfPage.coerceIn(0, pageCount - 1)
                            PdfPageItem(
                                state = state,
                                tab = tab,
                                page = firstPage,
                                zoom = tab.pdfZoom * 0.5f,
                                rotation = rotation,
                                selected = selectedPage == firstPage,
                                searchMatch = searchMatches.any { it.pageIndex == firstPage },
                                searchRegions =
                                    searchMatches
                                        .asSequence()
                                        .filter { it.pageIndex == firstPage }
                                        .flatMap { it.regions.asSequence() }
                                        .toList(),
                                selectionRegions =
                                    textSelection
                                        ?.takeIf { selectedTextPage == firstPage }
                                        ?.regions
                                        .orEmpty(),
                                selectionActions = selectionActionsForPage(firstPage),
                                textSelectionInteraction = textSelectionInteractionForPage(firstPage),
                                onLink = openPdfLink,
                                modifier =
                                    if (fitWidth) {
                                        Modifier.weight(1f)
                                    } else {
                                        Modifier.width((340f * tab.pdfZoom).dp)
                                    },
                            ) { selectedPage = firstPage }
                            if (firstPage + 1 < pageCount) {
                                PdfPageItem(
                                    state = state,
                                    tab = tab,
                                    page = firstPage + 1,
                                    zoom = tab.pdfZoom * 0.5f,
                                    rotation = rotation,
                                    selected = selectedPage == firstPage + 1,
                                    searchMatch = searchMatches.any { it.pageIndex == firstPage + 1 },
                                    searchRegions =
                                        searchMatches
                                            .asSequence()
                                            .filter { it.pageIndex == firstPage + 1 }
                                            .flatMap { it.regions.asSequence() }
                                            .toList(),
                                    selectionRegions =
                                        textSelection
                                            ?.takeIf { selectedTextPage == firstPage + 1 }
                                            ?.regions
                                            .orEmpty(),
                                    selectionActions = selectionActionsForPage(firstPage + 1),
                                    textSelectionInteraction = textSelectionInteractionForPage(firstPage + 1),
                                    onLink = openPdfLink,
                                    modifier =
                                        if (fitWidth) {
                                            Modifier.weight(1f)
                                        } else {
                                            Modifier.width((340f * tab.pdfZoom).dp)
                                        },
                                ) { selectedPage = firstPage + 1 }
                            } else if (fitWidth) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    pageCount > 0 -> {
                        val currentPageMatches = searchMatches.any { it.pageIndex == tab.pdfPage }
                        val pageBorder =
                            when {
                                selectedPage == tab.pdfPage -> MaterialTheme.colorScheme.secondary
                                currentPageMatches -> MaterialTheme.colorScheme.tertiary
                                else -> Color(0xFFC6CBC8)
                            }
                        val pageBorderWidth = if (selectedPage == tab.pdfPage || currentPageMatches) 2.dp else 1.dp
                        val dimensions = currentPageDimensions
                        val originalAspectRatio = dimensions.widthPoints / dimensions.heightPoints
                        val aspectRatio =
                            if (rotation % 180f == 90f) 1f / originalAspectRatio else originalAspectRatio
                        if (fitMode == PdfFitMode.HEIGHT) {
                            Box(Modifier.fillMaxSize().padding(18.dp).clickable { selectedPage = tab.pdfPage }, contentAlignment = Alignment.Center) {
                                PdfTiledPage(
                                    state = state,
                                    tab = tab,
                                    page = tab.pdfPage,
                                    pageDimensions = dimensions,
                                    dpi = singlePageRenderDpi,
                                    modifier =
                                        Modifier
                                            .fillMaxHeight()
                                            .aspectRatio(aspectRatio),
                                    decoration = PdfPageDecoration(rotation, pageBorderWidth, pageBorder),
                                    interaction =
                                        PdfPageInteraction(
                                            searchRegions =
                                                searchMatches
                                                    .asSequence()
                                                    .filter { it.pageIndex == tab.pdfPage }
                                                    .flatMap { it.regions.asSequence() }
                                                    .toList(),
                                            selectionRegions =
                                                textSelection
                                                    ?.takeIf { selectedTextPage == tab.pdfPage }
                                                    ?.regions
                                                    .orEmpty(),
                                            selectionActions = selectionActionsForPage(tab.pdfPage),
                                            textSelectionInteraction = textSelectionInteractionForPage(tab.pdfPage),
                                            onLink = openPdfLink,
                                        ),
                                )
                            }
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(singlePageVerticalScrollState)
                                    .horizontalScroll(singlePageHorizontalScrollState)
                                    .padding(24.dp)
                                    .clickable { selectedPage = tab.pdfPage },
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                PdfTiledPage(
                                    state = state,
                                    tab = tab,
                                    page = tab.pdfPage,
                                    pageDimensions = dimensions,
                                    dpi = singlePageRenderDpi,
                                    modifier =
                                        if (fitMode == PdfFitMode.WIDTH) {
                                            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                                        } else {
                                            Modifier.width((680f * tab.pdfZoom).dp).aspectRatio(aspectRatio)
                                        },
                                    decoration = PdfPageDecoration(rotation, pageBorderWidth, pageBorder),
                                    interaction =
                                        PdfPageInteraction(
                                            searchRegions =
                                                searchMatches
                                                    .asSequence()
                                                    .filter { it.pageIndex == tab.pdfPage }
                                                    .flatMap { it.regions.asSequence() }
                                                    .toList(),
                                            selectionRegions =
                                                textSelection
                                                    ?.takeIf { selectedTextPage == tab.pdfPage }
                                                    ?.regions
                                                    .orEmpty(),
                                            selectionActions = selectionActionsForPage(tab.pdfPage),
                                            textSelectionInteraction = textSelectionInteractionForPage(tab.pdfPage),
                                            onLink = openPdfLink,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfModeItem(
    label: String,
    mode: PdfViewMode,
    active: PdfViewMode,
    onSelect: (PdfViewMode) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (mode == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .background(
                    if (mode == active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(4.dp),
                ).clickable { onSelect(mode) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private suspend fun <T> runTrackedPdfTask(
    state: WorkbenchState,
    id: String,
    title: String,
    detail: String,
    block: suspend () -> T,
): T {
    val job = currentCoroutineContext()[Job]
    state.beginBackgroundTask(id, title, detail, onCancel = job?.let { current -> { current.cancel() } })
    return try {
        block()
    } finally {
        state.finishBackgroundTask(id)
    }
}

@Composable
private fun PdfSidePanelView(
    state: WorkbenchState,
    tab: DocumentTab,
    pageCount: Int,
    panel: PdfSidePanel,
    onPage: (Int) -> Unit,
) {
    Column(Modifier.width(184.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        PdfSidePanelHeader(panel, pageCount, tab)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PdfSidePanelContent(state, tab, pageCount, panel, onPage)
    }
}

@Composable
private fun PdfSidePanelHeader(
    panel: PdfSidePanel,
    pageCount: Int,
    tab: DocumentTab,
) {
    Row(
        Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            panel.title(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            panel.count(pageCount, tab),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun PdfSidePanelContent(
    state: WorkbenchState,
    tab: DocumentTab,
    pageCount: Int,
    panel: PdfSidePanel,
    onPage: (Int) -> Unit,
) {
    when (panel) {
        PdfSidePanel.OUTLINE -> PdfOutlinePanel(state, tab, onPage, showHeader = false, compact = true)
        PdfSidePanel.THUMBNAILS -> PdfThumbnailList(state, tab, pageCount, onPage)
        PdfSidePanel.ANNOTATIONS -> PdfAnnotationList(state, tab, onPage)
        PdfSidePanel.NONE -> Unit
    }
}

@Composable
private fun PdfThumbnailList(
    state: WorkbenchState,
    tab: DocumentTab,
    pageCount: Int,
    onPage: (Int) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(pageCount) { page ->
            PdfThumbnail(state, tab, page, tab.pdfPage == page) { onPage(page) }
        }
    }
}

@Composable
private fun PdfAnnotationList(
    state: WorkbenchState,
    tab: DocumentTab,
    onPage: (Int) -> Unit,
) {
    if (tab.pdfAnnotations.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "选择页面后可添加高亮或批注。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(vertical = 6.dp),
        ) {
            items(tab.pdfAnnotations, key = { it.id }) { annotation ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPage(annotation.page) }
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 3.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val isHighlight = annotation.kind == "highlight"
                    Icon(
                        if (isHighlight) Icons.Default.Highlight else Icons.AutoMirrored.Filled.Comment,
                        null,
                        Modifier.size(16.dp),
                        tint =
                            if (isHighlight) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                    )
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "第 ${annotation.page + 1} 页",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            annotation.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { state.removePdfAnnotation(tab, annotation) },
                        modifier = Modifier.size(27.dp),
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            "删除批注",
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
        }
    }
}

private fun PdfSidePanel.title(): String =
    when (this) {
        PdfSidePanel.THUMBNAILS -> "缩略图"
        PdfSidePanel.ANNOTATIONS -> "批注"
        PdfSidePanel.OUTLINE -> "目录"
        PdfSidePanel.NONE -> ""
    }

private fun PdfSidePanel.count(
    pageCount: Int,
    tab: DocumentTab,
): String =
    when (this) {
        PdfSidePanel.THUMBNAILS -> {
            "$pageCount 页"
        }

        PdfSidePanel.ANNOTATIONS -> {
            tab.pdfAnnotations.size.toString()
        }

        PdfSidePanel.OUTLINE -> {
            tab.pdfOutline
                ?.nodes
                ?.let(::countPdfOutlineNodes)
                ?.toString() ?: "0"
        }

        PdfSidePanel.NONE -> {
            ""
        }
    }

private fun countPdfOutlineNodes(
    nodes: List<PdfOutlineNode>,
): Int = nodes.sumOf { node -> 1 + countPdfOutlineNodes(node.children) }

@Composable
private fun PdfThumbnail(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val texture =
        rememberPdfTexture(
            state = state,
            tab = tab,
            page = page,
            dpi = 28f,
            purpose = PdfRasterPurpose.THUMBNAIL,
            priority = PdfRasterPriority.BACKGROUND,
        )
    val bitmap = texture.bitmap
    val colorMode = runCatching { PdfColorMode.valueOf(tab.pdfColorMode) }.getOrDefault(PdfColorMode.NORMAL)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = pdfPageBackground(colorMode),
            modifier = Modifier.width(118.dp).height(152.dp).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (bitmap == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Image(
                        bitmap,
                        "第 ${page + 1} 页缩略图",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = pdfPageColorFilter(tab.pdfColorMode),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text("${page + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PdfPageItem(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    zoom: Float,
    rotation: Float,
    selected: Boolean,
    searchMatch: Boolean,
    searchRegions: List<PdfNormalizedRect> = emptyList(),
    selectionRegions: List<PdfNormalizedRect> = emptyList(),
    selectionActions: PdfSelectionActions? = null,
    textSelectionInteraction: PdfTextSelectionInteraction,
    onLink: (PdfLink) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onSelect: () -> Unit,
) {
    var pageDimensions by remember(tab.relativePath, page) { mutableStateOf(DEFAULT_PAGE_DIMENSIONS) }
    LaunchedEffect(tab.relativePath, page) {
        try {
            pageDimensions = state.inspectPdfPage(tab, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            pageDimensions = DEFAULT_PAGE_DIMENSIONS
        }
    }
    val renderDpi = rememberPdfRenderDpi("${tab.relativePath}:$page", 96f * zoom)
    val originalAspectRatio = pageDimensions.widthPoints / pageDimensions.heightPoints
    val aspectRatio = if (rotation % 180f == 90f) 1f / originalAspectRatio else originalAspectRatio
    val fitWidth = tab.pdfFitMode == PdfFitMode.WIDTH.name
    val outlineEvidenceRegions = outlineEvidenceForPage(tab.pdfOutline?.nodes.orEmpty(), page)
    Column(modifier.clickable(onClick = onSelect), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${page + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fitWidth) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            contentAlignment = Alignment.Center,
        ) {
            PdfTiledPage(
                state = state,
                tab = tab,
                page = page,
                pageDimensions = pageDimensions,
                dpi = renderDpi,
                modifier =
                    if (fitWidth) {
                        Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    } else {
                        Modifier.width((680f * zoom).dp).aspectRatio(aspectRatio)
                    },
                decoration =
                    PdfPageDecoration(
                        rotation = rotation,
                        borderWidth = if (selected || searchMatch) 2.dp else 1.dp,
                        borderColor =
                            when {
                                selected -> MaterialTheme.colorScheme.secondary
                                searchMatch -> MaterialTheme.colorScheme.tertiary
                                else -> Color(0xFFC6CBC8)
                            },
                    ),
                interaction =
                    PdfPageInteraction(
                        searchRegions = searchRegions,
                        selectionRegions = selectionRegions,
                        outlineEvidenceRegions = outlineEvidenceRegions,
                        selectionActions = selectionActions,
                        textSelectionInteraction = textSelectionInteraction,
                        onLink = onLink,
                    ),
            )
        }
    }
}

internal fun outlineEvidenceForPage(
    nodes: List<PdfOutlineNode>,
    page: Int,
): List<PdfOutlineEvidenceRegion> =
    nodes.flatMap { node ->
        val current =
            node.evidence
                ?.takeIf { evidence -> evidence.pageIndex == page }
                ?.bounds
                ?.let { bounds ->
                    PdfOutlineEvidenceRegion(bounds, node.reviewState, node.confidence)
                }?.takeIf { evidence ->
                    evidence.reviewState == PdfOutlineReviewState.CANDIDATE ||
                        evidence.reviewState == PdfOutlineReviewState.REJECTED
                }
        listOfNotNull(current) + outlineEvidenceForPage(node.children, page)
    }

private data class PdfZoomAnchor(
    val sourceZoom: Float,
    val pointerPosition: Offset,
    val viewMode: PdfViewMode,
    val firstVisiblePage: Int,
    val firstVisiblePageOffset: Int,
    val horizontalOffset: Int,
    val verticalOffset: Int,
    val doubleHorizontalOffset: Int,
    val doubleVerticalOffset: Int,
)

private data class PdfSelectionRequest(
    val page: Int,
    val start: PdfPagePoint,
    val end: PdfPagePoint,
    val isFinal: Boolean,
    val gestureId: Int,
)

private val DEFAULT_PAGE_DIMENSIONS = PdfPageDimensions(widthPoints = 595f, heightPoints = 842f)
