@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.xuexue.PdfAnnotation
import dev.xuexue.PdfHighlightRegion
import dev.xuexue.platform.PdfNormalizedRect
import kotlin.math.roundToInt

internal data class PdfSelectionActions(
    val text: String,
    val regions: List<PdfNormalizedRect>,
    val onCopy: () -> Unit,
    val onCopyReference: () -> Unit,
    val onHighlight: () -> Unit,
    val onComment: (String) -> Unit,
    val onQuote: () -> Unit,
    val onAgent: () -> Unit,
    val onDismiss: () -> Unit,
)

internal data class PdfPageOverlayGeometry(
    val width: Dp,
    val height: Dp,
    val rotation: Float,
)

@Composable
internal fun PdfPageAnnotationControls(
    annotations: List<PdfAnnotation>,
    selectionActions: PdfSelectionActions?,
    geometry: PdfPageOverlayGeometry,
) {
    Box(Modifier.fillMaxSize().zIndex(PDF_ANNOTATION_CONTROLS_Z_INDEX)) {
        PdfCommentMarkers(annotations, geometry)
        selectionActions?.takeIf { it.regions.isNotEmpty() }?.let { actions ->
            PdfSelectionContext(actions, geometry)
        }
    }
}

@Composable
private fun PdfSelectionContext(
    actions: PdfSelectionActions,
    geometry: PdfPageOverlayGeometry,
) {
    var editingComment by remember(actions.text, actions.regions) { mutableStateOf(false) }
    val anchor = pdfDisplayRegion(actions.regions.last(), geometry.rotation)
    if (editingComment) {
        PdfInlineCommentEditor(
            anchor = anchor,
            geometry = geometry,
            onSubmit = actions.onComment,
            onCancel = { editingComment = false },
        )
    } else {
        PdfSelectionToolbar(
            anchor = anchor,
            geometry = geometry,
            actions = actions,
            onComment = { editingComment = true },
        )
    }
}

@Composable
private fun PdfSelectionToolbar(
    anchor: PdfNormalizedRect,
    geometry: PdfPageOverlayGeometry,
    actions: PdfSelectionActions,
    onComment: () -> Unit,
) {
    val width = overlayWidth(geometry.width, PDF_SELECTION_TOOLBAR_WIDTH)
    val placement = overlayPlacement(anchor, geometry, width, PDF_SELECTION_TOOLBAR_HEIGHT)
    Surface(
        modifier =
            Modifier
                .offset(placement.x, placement.y)
                .width(width)
                .height(PDF_SELECTION_TOOLBAR_HEIGHT),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorTool(Icons.Default.ContentCopy, "复制所选文字", actions.onCopy)
            EditorTool(Icons.Default.Description, "复制页引用", actions.onCopyReference)
            EditorTool(Icons.Default.Highlight, "高亮所选文字", actions.onHighlight)
            EditorTool(Icons.AutoMirrored.Filled.Comment, "批注所选文字", onComment)
            EditorTool(Icons.Default.FormatQuote, "引用到笔记", actions.onQuote)
            EditorTool(Icons.Default.SmartToy, "交给 Agent", actions.onAgent)
            EditorTool(Icons.Default.Close, "取消选择", actions.onDismiss)
        }
    }
}

@Composable
private fun PdfInlineCommentEditor(
    anchor: PdfNormalizedRect,
    geometry: PdfPageOverlayGeometry,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(anchor) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val width = overlayWidth(geometry.width, PDF_COMMENT_EDITOR_WIDTH)
    val contentWidth = (width - 20.dp).coerceAtLeast(0.dp)
    val placement = overlayPlacement(anchor, geometry, width, PDF_COMMENT_EDITOR_HEIGHT)
    val submit: () -> Unit = {
        val comment = text.trim()
        if (comment.isNotEmpty()) onSubmit(comment)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier = Modifier.offset(placement.x, placement.y).width(width),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 7.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("添加批注", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("批注内容") },
                minLines = 2,
                maxLines = 4,
                modifier =
                    Modifier
                        .width(contentWidth)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                    onCancel()
                                    true
                                }

                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    event.isCtrlPressed -> {
                                    submit()
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        },
            )
            Row(Modifier.width(contentWidth), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("取消")
                }
                Button(onClick = submit, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
            }
        }
    }
}

internal fun List<PdfNormalizedRect>.toPdfHighlightRegions(): List<PdfHighlightRegion> =
    map { region ->
        PdfHighlightRegion(
            left = region.left,
            top = region.top,
            right = region.right,
            bottom = region.bottom,
        )
    }

internal fun pdfDisplayRegion(
    region: PdfNormalizedRect,
    rotation: Float,
): PdfNormalizedRect =
    when (normalizedQuarterTurns(rotation)) {
        1 -> PdfNormalizedRect(1f - region.bottom, region.left, 1f - region.top, region.right)
        2 -> PdfNormalizedRect(1f - region.right, 1f - region.bottom, 1f - region.left, 1f - region.top)
        3 -> PdfNormalizedRect(region.top, 1f - region.right, region.bottom, 1f - region.left)
        else -> region
    }

private fun normalizedQuarterTurns(rotation: Float): Int {
    val turns = (rotation / 90f).roundToInt()
    return (turns % PDF_QUARTER_TURNS + PDF_QUARTER_TURNS) % PDF_QUARTER_TURNS
}

internal fun overlayWidth(
    pageWidth: Dp,
    preferredWidth: Dp,
): Dp = minOf(preferredWidth, (pageWidth - PDF_OVERLAY_PADDING * 2).coerceAtLeast(0.dp))

internal fun overlayPlacement(
    anchor: PdfNormalizedRect,
    geometry: PdfPageOverlayGeometry,
    overlayWidth: Dp,
    overlayHeight: Dp,
): PdfOverlayPlacement {
    val maxX = (geometry.width - overlayWidth - PDF_OVERLAY_PADDING).coerceAtLeast(PDF_OVERLAY_PADDING)
    val desiredX = geometry.width * ((anchor.left + anchor.right) / 2f) - overlayWidth / 2
    val x = desiredX.coerceIn(PDF_OVERLAY_PADDING, maxX)
    val above = geometry.height * anchor.top - overlayHeight - PDF_OVERLAY_GAP
    val maxY = (geometry.height - overlayHeight - PDF_OVERLAY_PADDING).coerceAtLeast(PDF_OVERLAY_PADDING)
    val below = geometry.height * anchor.bottom + PDF_OVERLAY_GAP
    val y = (if (above >= PDF_OVERLAY_PADDING) above else below).coerceIn(PDF_OVERLAY_PADDING, maxY)
    return PdfOverlayPlacement(x, y)
}

internal data class PdfOverlayPlacement(
    val x: Dp,
    val y: Dp,
)

private const val PDF_QUARTER_TURNS = 4
private const val PDF_ANNOTATION_CONTROLS_Z_INDEX = 4f
internal val PDF_OVERLAY_PADDING = 6.dp
internal val PDF_OVERLAY_GAP = 6.dp
private val PDF_SELECTION_TOOLBAR_WIDTH = 232.dp
private val PDF_SELECTION_TOOLBAR_HEIGHT = 40.dp
private val PDF_COMMENT_EDITOR_WIDTH = 280.dp
private val PDF_COMMENT_EDITOR_HEIGHT = 184.dp
