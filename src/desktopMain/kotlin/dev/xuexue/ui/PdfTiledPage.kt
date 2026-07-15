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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import dev.xuexue.platform.PdfPageDimensions
import dev.xuexue.platform.PdfPagePoint
import dev.xuexue.platform.PdfRasterLayer
import dev.xuexue.platform.PdfRasterPriority
import dev.xuexue.platform.PdfRasterPurpose
import kotlinx.coroutines.CancellationException
import kotlin.math.ceil

internal data class PdfPageInteraction(
    val searchRegions: List<PdfNormalizedRect> = emptyList(),
    val selectionRegions: List<PdfNormalizedRect> = emptyList(),
    val onTextSelection: ((PdfPagePoint, PdfPagePoint) -> Unit)? = null,
    val onLink: (PdfLink) -> Unit = {},
)

private data class PdfPagePresentation(
    val previewBitmap: ImageBitmap?,
    val colorMode: PdfColorMode,
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
    pageDimensions: PdfPageDimensions,
    dpi: Float,
    modifier: Modifier,
    decoration: PdfPageDecoration,
    interaction: PdfPageInteraction = PdfPageInteraction(),
) {
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
    Box(
        modifier
            .background(pdfPageBackground(colorMode))
            .border(decoration.borderWidth, decoration.borderColor),
        contentAlignment = Alignment.Center,
    ) {
        PdfPageSurface(
            state = state,
            tab = tab,
            page = page,
            pageDimensions = pageDimensions,
            dpi = dpi,
            decoration = decoration,
            presentation = PdfPagePresentation(previewTexture.bitmap, colorMode),
            interaction = interaction,
        )
    }
}

@Composable
private fun PdfPageSurface(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    pageDimensions: PdfPageDimensions,
    dpi: Float,
    decoration: PdfPageDecoration,
    presentation: PdfPagePresentation,
    interaction: PdfPageInteraction,
) {
    val columns = tileCount(pageDimensions.widthPoints, dpi)
    val rows = tileCount(pageDimensions.heightPoints, dpi)
    val links = rememberPdfLinks(state, tab, page)
    val colorFilter = pdfPageColorFilter(presentation.colorMode.name)
    var tileWindow by remember(page, columns, rows) { mutableStateOf<PdfTileWindow?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val quarterTurn = decoration.rotation % 180f != 0f
        val unrotatedWidth = if (quarterTurn) maxHeight else maxWidth
        val unrotatedHeight = if (quarterTurn) maxWidth else maxHeight
        BoxWithConstraints(
            modifier =
                Modifier
                    .size(unrotatedWidth, unrotatedHeight)
                    .align(Alignment.Center)
                    .graphicsLayer(rotationZ = decoration.rotation)
                    .trackVisiblePdfTiles(columns, rows) { visible -> tileWindow = visible },
        ) {
            val pageWidth = maxWidth
            val pageHeight = maxHeight
            val density = LocalDensity.current
            val pageWidthPixels = with(density) { maxWidth.toPx() }
            val pageHeightPixels = with(density) { maxHeight.toPx() }
            val selection =
                rememberPdfSelectionGesture(
                    page = page,
                    pageDimensions = pageDimensions,
                    pageWidthPixels = pageWidthPixels,
                    pageHeightPixels = pageHeightPixels,
                    onTextSelection = interaction.onTextSelection,
                )
            Box(Modifier.fillMaxSize().then(selection.modifier)) {
                presentation.previewBitmap?.let { preview ->
                    Image(
                        preview,
                        "PDF 第 ${page + 1} 页预览",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = colorFilter,
                    )
                }
                PdfTileGrid(
                    state = state,
                    tab = tab,
                    page = page,
                    configuration =
                        PdfTileGridConfiguration(
                            dpi = dpi,
                            columns = columns,
                            rows = rows,
                            showLoading = presentation.previewBitmap == null,
                            tileWindow = tileWindow,
                            colorFilter = colorFilter,
                        ),
                )
                PdfPageOverlays(
                    tab = tab,
                    page = page,
                    searchRegions = interaction.searchRegions,
                    selectionRegions = interaction.selectionRegions + listOfNotNull(selection.region),
                    links = links,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    onLink = interaction.onLink,
                )
            }
        }
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
                            showLoading = configuration.showLoading,
                            colorFilter = configuration.colorFilter,
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
    searchRegions: List<PdfNormalizedRect>,
    selectionRegions: List<PdfNormalizedRect>,
    links: List<PdfLink>,
    pageWidth: Dp,
    pageHeight: Dp,
    onLink: (PdfLink) -> Unit,
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
    searchRegions.forEach { region ->
        PdfRegionOverlay(region, pageWidth, pageHeight, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.26f))
    }
    selectionRegions.forEach { region ->
        PdfRegionOverlay(region, pageWidth, pageHeight, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    }
    links.forEach { link ->
        Box(
            Modifier
                .offset(x = pageWidth * link.region.left, y = pageHeight * link.region.top)
                .width(pageWidth * (link.region.right - link.region.left))
                .height(pageHeight * (link.region.bottom - link.region.top))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onLink(link) },
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
            .background(color),
    )
}

@Composable
private fun PdfTile(
    state: WorkbenchState,
    tab: DocumentTab,
    page: Int,
    dpi: Float,
    viewport: PdfNormalizedRect,
    showLoading: Boolean,
    colorFilter: ColorFilter?,
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
                    colorFilter = colorFilter,
                )
            }

            texture.failed -> {
                Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            showLoading -> {
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
private const val TARGET_TILE_PIXELS = 768f
private const val MAX_TILES_PER_AXIS = 16
