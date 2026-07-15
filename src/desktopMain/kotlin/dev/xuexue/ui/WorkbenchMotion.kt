@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal object WorkbenchMotion {
    const val QUICK = 90
    const val FAST = 150
    const val STANDARD = 220
    const val EMPHASIZED = 320

    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> quickTween() = tween<T>(durationMillis = QUICK, easing = StandardEasing)

    fun <T> fastTween() = tween<T>(durationMillis = FAST, easing = StandardEasing)

    fun <T> standardTween() = tween<T>(durationMillis = STANDARD, easing = StandardEasing)

    fun <T> exitTween() = tween<T>(durationMillis = FAST, easing = ExitEasing)

    fun <T> expressiveSpring() =
        spring<T>(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        )
}

@Composable
internal fun MotionVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}

@Composable
internal fun MotionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerColor =
        when {
            danger && hovered -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.065f)
            else -> Color.Transparent
        }
    val contentColor =
        when {
            danger && hovered -> MaterialTheme.colorScheme.onError
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        content = content,
    )
}

@Composable
internal fun MotionDropTarget(
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale by
        animateFloatAsState(
            targetValue = if (active) 1.025f else 1f,
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "drop target scale",
        )
    val backgroundColor by
        animateColorAsState(
            targetValue = accent.copy(alpha = if (active) 0.19f else 0.065f),
            animationSpec = WorkbenchMotion.fastTween(),
            label = "drop target background",
        )
    val borderWidth by
        animateDpAsState(
            targetValue = if (active) 2.dp else 1.dp,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "drop target border",
        )
    val shape =
        androidx.compose.foundation.shape
            .RoundedCornerShape(6.dp)
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.background(backgroundColor, shape)
                .border(borderWidth, accent.copy(alpha = if (active) 1f else 0.72f), shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
