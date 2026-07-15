@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class PaletteRowMotion(
    val color: Color,
    val scale: Float,
)

@Composable
internal fun PaletteResultRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = rememberPaletteRowMotion(selected, hovered, pressed)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (detail == null) 46.dp else 56.dp)
                .graphicsLayer {
                    scaleX = motion.scale
                    scaleY = motion.scale
                }.background(
                    motion.color,
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(5.dp),
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(start = 7.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .graphicsLayer { alpha = if (selected) 1f else 0f }
                .background(
                    MaterialTheme.colorScheme.primary,
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(3.dp),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            icon,
            null,
            Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = CodeFont,
            )
        }
    }
}

@Composable
internal fun PaletteEmptyState(
    icon: ImageVector,
    text: String,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun rememberPaletteRowMotion(
    selected: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): PaletteRowMotion {
    val color by
        animateColorAsState(
            targetValue =
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                    hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "palette row color",
        )
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) 0.992f else 1f,
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "palette row scale",
        )
    return PaletteRowMotion(color, scale)
}
