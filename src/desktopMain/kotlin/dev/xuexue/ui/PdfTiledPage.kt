@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xuexue.DocumentTab
import dev.xuexue.WorkbenchState
import dev.xuexue.platform.PdfLink
import dev.xuexue.platform.PdfNormalizedRect
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfRasterLayer
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import kotlinx.coroutines.CancellationException
import kotlin.math.ceil

internal data class PdfPageInteraction(
    val searchRegions: List<PdfNormalizedRect> = emptyList(),
    val selectionRegions: List<PdfNormalizedRect> = emptyList(),
    val outlineEvidenceRegions: List<PdfOutlineEvidenceRegion> = emptyList(),
    val selectionActions: PdfSelectionActions? = null,
    val textSelectionInteraction: PdfTextSelectionInteraction? = null,
    val onLink: (PdfLink) -> Unit = {},
)

internal data class PdfOutlineEvidenceRegion(
    val bounds: PdfNormalizedRect,
    val reviewState: PdfOutlineReviewState,
    val confidence: Float?,
)

private data class PdfPagePresentation(
    val previewBitmap: ImageBitmap?,
    val previewFailed: Boolean,
    val colorMode: PdfColorMode,
)

internal data class PdfPageRenderConfiguration(
    val pageDimensions: PdfPageDimensions,
    val renderViewport: Boolean,
    val fallbackDpi: Float = DEFAULT_PDF_FALLBACK_DPI,
)

private data class PdfTilePresentation(
    val showLoading: Boolean,
    val colorFilter: ColorFilter?,
    val onRendered: () -> Unit,
)

private data class PdfTileGridConfiguration(
    val dpi: Float,
    val columns: Int,
    val rows: Int,
    val showLoading: Boolean,
    val tileWindow: PdfTileWindow?,
    val colorFilter: ColorFilter?,
)

@Composable
internal fun PdfTiledPage(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    renderConfiguration: PdfPageRenderConfiguration,
    modifier: Modifier,
    decoration: PdfPageDecoration,
    interaction: PdfPageInteraction = PdfPageInteraction(),
) {
    val pageDimensions = renderConfiguration.pageDimensions
    val renderViewport = renderConfiguration.renderViewport
    val colorMode = runCatching { PdfColorMode.valueOf(tab.pdfColorMode) }.getOrDefault(PdfColorMode.NORMAL)
    val previewTexture =
        rememberPdfTexture(
            state = state,
            tab = tab,
            page = page,
            dpi = PDF_PREVIEW_DPI,
            purpose = PdfRasterPurpose.THUMBNAIL,
            priority = PdfRasterPriority.VISIBLE,
        )
    val pageAspectRatio =
        pdfDisplayedPageAspectRatio(
            pageDimensions = pageDimensions,
            previewWidthPixels = previewTexture.bitmap?.width,
            previewHeightPixels = previewTexture.bitmap?.height,
            renderViewport = renderViewport,
            rotationDegrees = decoration.rotation,
        )
    Box(
        modifier
            .aspectRatio(pageAspectRatio)
            .background(pdfPageBackground(colorMode))
            .border(decoration.borderWidth, decoration.borderColor),
        contentAlignment = Alignment.Center,
    ) {
        PdfPageSurface(
            state = state,
            tab = tab,
            page = page,
            renderConfiguration = renderConfiguration,
            decoration = decoration,
            presentation =
                PdfPagePresentation(
                    previewBitmap = previewTexture.bitmap,
                    previewFailed = previewTexture.failed,
                    colorMode = colorMode,
                ),
            interaction = interaction,
        )
    }
}

@Composable
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
) // This composition boundary coordinates DPI, visible tiles, selection, and page overlays.
private fun PdfPageSurface(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    renderConfiguration: PdfPageRenderConfiguration,
    decoration: PdfPageDecoration,
    presentation: PdfPagePresentation,
    interaction: PdfPageInteraction,
) {
    val pageDimensions = renderConfiguration.pageDimensions
    val renderViewport = renderConfiguration.renderViewport
    val fallbackDpi = renderConfiguration.fallbackDpi
    val links = rememberPdfLinks(state, tab, page)
    val colorFilter = pdfPageColorFilter(presentation.colorMode.name)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val displayedWidthPixels = with(density) { maxWidth.toPx() }
        val displayedHeightPixels = with(density) { maxHeight.toPx() }
        val renderDpi =
            pdfPageRenderDpi(
                pageDimensions = pageDimensions,
                displayedWidthPixels = displayedWidthPixels,
                displayedHeightPixels = displayedHeightPixels,
                rotationDegrees = decoration.rotation,
                fallbackDpi = fallbackDpi,
            )
        val columns = tileCount(pageDimensions.widthPoints, renderDpi)
        val rows = tileCount(pageDimensions.heightPoints, renderDpi)
        var tileWindow by remember(page, columns, rows, renderDpi, renderViewport) {
            mutableStateOf<PdfTileWindow?>(null)
        }
        var renderedTileViewports by remember(page, columns, rows, renderDpi, renderViewport) {
            mutableStateOf<Set<PdfNormalizedRect>>(emptySet())
        }
        val visibleTileViewports =
            tileWindow
                ?.let { window ->
                    buildSet {
                        for (row in window.firstRow..window.lastRow) {
                            for (column in window.firstColumn..window.lastColumn) {
                                add(tileViewport(column, row, columns, rows))
                            }
                        }
                    }
                }.orEmpty()
        val visibleTilesReady =
            renderViewport &&
                visibleTileViewports.isNotEmpty() &&
                visibleTileViewports.all { viewport -> viewport in renderedTileViewports }
        val showPreview =
            presentation.previewBitmap != null && !visibleTilesReady
        val quarterTurn = decoration.rotation % 180f != 0f
        val unrotatedWidth = if (quarterTurn) maxHeight else maxWidth
        val unrotatedHeight = if (quarterTurn) maxWidth else maxHeight
        val pageModifier =
            Modifier
                .size(unrotatedWidth, unrotatedHeight)
                .align(Alignment.Center)
                .graphicsLayer(rotationZ = decoration.rotation)
                .run {
                    if (renderViewport) {
                        trackVisiblePdfTiles(columns, rows) { visible -> tileWindow = visible }
                    } else {
                        this
                    }
                }
        BoxWithConstraints(pageModifier) {
            val (pageWidth, pageHeight) = maxWidth to maxHeight
            val selectionModifier =
                rememberPdfSelectionGesture(
                    page = page,
                    pageDimensions = pageDimensions,
                    pageWidthPixels = with(density) { maxWidth.toPx() },
                    pageHeightPixels = with(density) { maxHeight.toPx() },
                    interaction = interaction.textSelectionInteraction,
                )
            Box(Modifier.fillMaxSize().then(selectionModifier)) {
                presentation.previewBitmap?.takeIf { showPreview }?.let { preview ->
                    Image(
                        preview,
                        "PDF 第 ${page + 1} 页预览",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = colorFilter,
                    )
                }
                if (renderViewport) {
                    PdfTileGrid(
                        state = state,
                        tab = tab,
                        page = page,
                        configuration =
                            PdfTileGridConfiguration(
                                dpi = renderDpi,
                                columns = columns,
                                rows = rows,
                                showLoading = presentation.previewBitmap == null || !showPreview,
                                tileWindow = tileWindow,
                                colorFilter = colorFilter,
                            ),
                        onTileRendered = { viewport ->
                            if (viewport !in renderedTileViewports) {
                                renderedTileViewports = renderedTileViewports + viewport
                            }
                        },
                    )
                } else if (presentation.previewBitmap == null) {
                    if (presentation.previewFailed) {
                        Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    } else {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    }
                }
                PdfPageOverlays(
                    tab = tab,
                    page = page,
                    interaction = interaction,
                    links = links,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                )
            }
        }
        PdfPageAnnotationControls(
            annotations = tab.pdfAnnotations.filter { annotation -> annotation.page == page },
            selectionActions = interaction.selectionActions,
            geometry = PdfPageOverlayGeometry(maxWidth, maxHeight, decoration.rotation),
        )
    }
}

@Composable
private fun rememberPdfLinks(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
): List<PdfLink> {
    var links by remember(tab.relativePath, page, tab.lastKnownModified) { mutableStateOf<List<PdfLink>>(emptyList()) }
    LaunchedEffect(tab.relativePath, page, tab.lastKnownModified) {
        try {
            links = state.pdfLinks(tab, page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            links = emptyList()
        }
    }
    return links
}

@Composable
private fun PdfTileGrid(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    configuration: PdfTileGridConfiguration,
    onTileRendered: (PdfNormalizedRect) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        repeat(configuration.rows) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(configuration.columns) { column ->
                    val viewport = tileViewport(column, row, configuration.columns, configuration.rows)
                    val modifier = Modifier.fillMaxHeight().weight(1f)
                    if (configuration.tileWindow?.contains(column, row) == true) {
                        PdfTile(
                            state = state,
                            tab = tab,
                            page = page,
                            dpi = configuration.dpi,
                            viewport = viewport,
                            presentation =
                                PdfTilePresentation(
                                    showLoading = configuration.showLoading,
                                    colorFilter = configuration.colorFilter,
                                    onRendered = { onTileRendered(viewport) },
                                ),
                            modifier = modifier,
                        )
                    } else {
                        Box(modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageOverlays(
    tab: DocumentTab,
    page: Int,
    interaction: PdfPageInteraction,
    links: List<PdfLink>,
    pageWidth: Dp,
    pageHeight: Dp,
) {
    tab.pdfAnnotations
        .asSequence()
        .filter { annotation -> annotation.page == page && annotation.kind == "highlight" }
        .flatMap { annotation -> annotation.regions.asSequence() }
        .forEach { region ->
            PdfRegionOverlay(
                PdfNormalizedRect(region.left, region.top, region.right, region.bottom),
                pageWidth,
                pageHeight,
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
            )
        }
    tab.pdfAnnotations
        .asSequence()
        .filter { annotation -> annotation.page == page && annotation.kind == "comment" }
        .flatMap { annotation -> annotation.regions.asSequence() }
        .forEach { region ->
            PdfRegionOverlay(
                PdfNormalizedRect(region.left, region.top, region.right, region.bottom),
                pageWidth,
                pageHeight,
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
            )
        }
    interaction.searchRegions.forEach { region ->
        PdfRegionOverlay(region, pageWidth, pageHeight, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.26f))
    }
    interaction.selectionRegions.forEach { region ->
        PdfRegionOverlay(region, pageWidth, pageHeight, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    }
    interaction.outlineEvidenceRegions.forEach { evidence ->
        val color =
            when (evidence.reviewState) {
                PdfOutlineReviewState.CANDIDATE -> MaterialTheme.colorScheme.tertiary
                PdfOutlineReviewState.REJECTED -> MaterialTheme.colorScheme.error
                PdfOutlineReviewState.CONFIRMED, PdfOutlineReviewState.MANUAL -> Color.Transparent
            }
        if (color != Color.Transparent) {
            Box(
                Modifier
                    .offset(
                        x = pageWidth * evidence.bounds.left,
                        y = pageHeight * evidence.bounds.top,
                    ).width(pageWidth * (evidence.bounds.right - evidence.bounds.left))
                    .height(pageHeight * (evidence.bounds.bottom - evidence.bounds.top))
                    .pdfEmphasis(color.copy(alpha = 0.1f))
                    .border(1.dp, color),
            )
        }
    }
    links.forEach { link ->
        Box(
            Modifier
                .offset(x = pageWidth * link.region.left, y = pageHeight * link.region.top)
                .width(pageWidth * (link.region.right - link.region.left))
                .height(pageHeight * (link.region.bottom - link.region.top))
                .pdfEmphasis(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { interaction.onLink(link) },
        )
    }
}

@Composable
private fun PdfRegionOverlay(
    region: PdfNormalizedRect,
    pageWidth: Dp,
    pageHeight: Dp,
    color: Color,
) {
    Box(
        Modifier
            .offset(x = pageWidth * region.left, y = pageHeight * region.top)
            .width(pageWidth * (region.right - region.left))
            .height(pageHeight * (region.bottom - region.top))
            .pdfEmphasis(color),
    )
}

private fun Modifier.pdfEmphasis(color: Color): Modifier =
    drawBehind {
        drawRect(color, blendMode = BlendMode.Multiply)
    }

@Composable
private fun PdfTile(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    dpi: Float,
    viewport: PdfNormalizedRect,
    presentation: PdfTilePresentation,
    modifier: Modifier,
) {
    val texture =
        rememberPdfTexture(
            state = state,
            tab = tab,
            page = page,
            dpi = dpi,
            purpose = PdfRasterPurpose.VIEWPORT,
            viewport = viewport,
            priority = PdfRasterPriority.VISIBLE,
        )
    val renderedBitmap = texture.bitmap
    if (renderedBitmap != null) {
        SideEffect(presentation.onRendered)
    }
    val overlayTexture =
        rememberPdfTexture(
            state = state,
            tab = tab,
            page = page,
            dpi = dpi,
            purpose = PdfRasterPurpose.VIEWPORT,
            viewport = viewport,
            priority = PdfRasterPriority.VISIBLE,
            layer = PdfRasterLayer.NATIVE_OVERLAY,
        )
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            renderedBitmap != null -> {
                Image(
                    renderedBitmap,
                    "PDF 第 ${page + 1} 页瓦片",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = presentation.colorFilter,
                )
            }

            texture.failed -> {
                Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            presentation.showLoading -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        }
        if (renderedBitmap != null) {
            overlayTexture.bitmap?.let { overlay ->
                Image(
                    overlay,
                    "PDF 第 ${page + 1} 页原生批注层",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}

private fun tileViewport(
    column: Int,
    row: Int,
    columns: Int,
    rows: Int,
): PdfNormalizedRect =
    PdfNormalizedRect(
        left = column.toFloat() / columns,
        top = row.toFloat() / rows,
        right = (column + 1).toFloat() / columns,
        bottom = (row + 1).toFloat() / rows,
    )

private fun tileCount(
    points: Float,
    dpi: Float,
): Int = ceil(points * dpi / POINTS_PER_INCH / TARGET_TILE_PIXELS).toInt().coerceIn(1, MAX_TILES_PER_AXIS)

private fun Modifier.trackVisiblePdfTiles(
    columns: Int,
    rows: Int,
    onWindowChanged: (PdfTileWindow?) -> Unit,
): Modifier =
    onGloballyPositioned { coordinates ->
        val rootCoordinates = coordinates.findRootCoordinates()
        onWindowChanged(
            pdfVisibleTileWindow(
                pageBounds =
                    Rect(
                        left = 0f,
                        top = 0f,
                        right = coordinates.size.width.toFloat(),
                        bottom = coordinates.size.height.toFloat(),
                    ),
                viewportBounds = coordinates.localBoundingBoxOf(rootCoordinates, clipBounds = false),
                columns = columns,
                rows = rows,
            ),
        )
    }

internal data class PdfPageDecoration(
    val rotation: Float,
    val borderWidth: Dp,
    val borderColor: Color,
)

private const val POINTS_PER_INCH = 72f
private const val TARGET_TILE_PIXELS = 1_024f
private const val MAX_TILES_PER_AXIS = 16
