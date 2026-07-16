@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.xuexue.PdfAnnotation
import dev.xuexue.platform.PdfNormalizedRect

@Composable
internal fun PdfCommentMarkers(
    annotations: List<PdfAnnotation>,
    geometry: PdfPageOverlayGeometry,
) {
    val comments = annotations.filter { it.kind == "comment" }
    var expandedCommentId by remember(annotations) { mutableStateOf<Long?>(null) }
    comments.forEachIndexed { index, annotation ->
        val anchor = pdfDisplayRegion(commentAnchor(annotation, index), geometry.rotation)
        PdfCommentMarker(
            annotation = annotation,
            anchor = anchor,
            geometry = geometry,
            expanded = expandedCommentId == annotation.id,
            onToggle = {
                expandedCommentId = annotation.id.takeUnless { it == expandedCommentId }
            },
        )
    }
}

@Composable
private fun PdfCommentMarker(
    annotation: PdfAnnotation,
    anchor: PdfNormalizedRect,
    geometry: PdfPageOverlayGeometry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val markerX =
        overlayCoordinate(
            requested = geometry.width * anchor.right + PDF_COMMENT_MARKER_GAP,
            available = geometry.width,
            itemSize = PDF_COMMENT_MARKER_SIZE,
        )
    val markerY =
        overlayCoordinate(
            requested = geometry.height * anchor.top - PDF_COMMENT_MARKER_SIZE / 2,
            available = geometry.height,
            itemSize = PDF_COMMENT_MARKER_SIZE,
        )
    Surface(
        modifier =
            Modifier
                .offset(markerX, markerY)
                .size(PDF_COMMENT_MARKER_SIZE)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onToggle),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.Comment,
                "查看批注：${annotation.text}",
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    if (expanded) {
        PdfCommentBubble(annotation, anchor, geometry, onToggle)
    }
}

@Composable
private fun PdfCommentBubble(
    annotation: PdfAnnotation,
    anchor: PdfNormalizedRect,
    geometry: PdfPageOverlayGeometry,
    onClose: () -> Unit,
) {
    val width = overlayWidth(geometry.width, PDF_COMMENT_BUBBLE_WIDTH)
    val placement = overlayPlacement(anchor, geometry, width, PDF_COMMENT_BUBBLE_HEIGHT)
    Surface(
        modifier = Modifier.offset(placement.x, placement.y).width(width),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "批注",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                EditorTool(Icons.Default.Close, "关闭批注", onClose)
            }
            Text(annotation.text, style = MaterialTheme.typography.bodySmall, maxLines = 8)
        }
    }
}

private fun commentAnchor(
    annotation: PdfAnnotation,
    index: Int,
): PdfNormalizedRect =
    annotation.regions.lastOrNull()?.let { region ->
        PdfNormalizedRect(region.left, region.top, region.right, region.bottom)
    } ?: run {
        val top = (PDF_LEGACY_COMMENT_TOP + index * PDF_LEGACY_COMMENT_STEP).coerceAtMost(0.9f)
        PdfNormalizedRect(0.92f, top, 0.96f, (top + 0.03f).coerceAtMost(0.96f))
    }

private fun overlayCoordinate(
    requested: Dp,
    available: Dp,
    itemSize: Dp,
): Dp {
    val maximum = (available - itemSize - PDF_OVERLAY_PADDING).coerceAtLeast(0.dp)
    val minimum = minOf(PDF_OVERLAY_PADDING, maximum)
    return requested.coerceIn(minimum, maximum)
}

private const val PDF_LEGACY_COMMENT_TOP = 0.04f
private const val PDF_LEGACY_COMMENT_STEP = 0.055f
private val PDF_COMMENT_BUBBLE_WIDTH = 260.dp
private val PDF_COMMENT_BUBBLE_HEIGHT = 112.dp
private val PDF_COMMENT_MARKER_SIZE = 26.dp
private val PDF_COMMENT_MARKER_GAP = 4.dp
