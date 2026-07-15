@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.xuexue.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class WelcomeEntrance(
    val brand: Float,
    val create: Float,
    val open: Float,
    val offset: Float,
)

@Composable
fun WelcomeScreen(
    loading: Boolean,
    onCreate: () -> Unit,
    onOpen: () -> Unit,
) {
    val entrance = rememberWelcomeEntrance()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Box(Modifier.width(520.dp).padding(30.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                WelcomeBrand(Modifier.welcomeEntrance(entrance.brand, entrance.offset))
                Spacer(Modifier.height(34.dp))
                Text(
                    "开始",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.welcomeEntrance(entrance.create, entrance.offset),
                )
                Spacer(Modifier.height(10.dp))
                WelcomeAction(
                    icon = Icons.Default.Add,
                    title = "新建工作区",
                    detail = "选择一个目录作为新的知识工作区",
                    primary = true,
                    onClick = onCreate,
                    modifier = Modifier.welcomeEntrance(entrance.create, entrance.offset),
                    enabled = entrance.create >= 0.99f,
                )
                Spacer(Modifier.height(8.dp))
                WelcomeAction(
                    icon = Icons.Default.FolderOpen,
                    title = "打开已有目录",
                    detail = "文件系统始终是内容真源",
                    primary = false,
                    onClick = onOpen,
                    modifier = Modifier.welcomeEntrance(entrance.open, entrance.offset),
                    enabled = entrance.open >= 0.99f,
                )
            }
            MotionVisibility(
                visible = loading,
                modifier = Modifier.align(Alignment.BottomStart),
                enter =
                    expandVertically(animationSpec = WorkbenchMotion.expressiveSpring()) +
                        fadeIn(WorkbenchMotion.fastTween()),
                exit =
                    shrinkVertically(animationSpec = WorkbenchMotion.standardTween()) +
                        fadeOut(WorkbenchMotion.exitTween()),
            ) {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在恢复上次工作区…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun rememberWelcomeEntrance(): WelcomeEntrance {
    var entered by remember { mutableStateOf(false) }
    val entranceOffset = with(LocalDensity.current) { 14.dp.toPx() }
    val brandProgress by
        animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(WorkbenchMotion.EMPHASIZED, easing = WorkbenchMotion.StandardEasing),
            label = "welcome brand entrance",
        )
    val createProgress by
        animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = WorkbenchMotion.EMPHASIZED,
                    delayMillis = 70,
                    easing = WorkbenchMotion.StandardEasing,
                ),
            label = "welcome create entrance",
        )
    val openProgress by
        animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = WorkbenchMotion.EMPHASIZED,
                    delayMillis = 130,
                    easing = WorkbenchMotion.StandardEasing,
                ),
            label = "welcome open entrance",
        )
    LaunchedEffect(Unit) { entered = true }
    return WelcomeEntrance(brandProgress, createProgress, openProgress, entranceOffset)
}

@Composable
private fun WelcomeBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(6.dp),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("学习学习", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("本地优先的阅读、笔记与 Agent 工作台", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeAction(
    icon: ImageVector,
    title: String,
    detail: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            targetValue =
                if (pressed) {
                    0.985f
                } else if (hovered) {
                    1.006f
                } else {
                    1f
                },
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "welcome action scale",
        )
    val elevation by
        animateDpAsState(
            targetValue = if (hovered) 3.dp else 0.dp,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "welcome action elevation",
        )
    val color by
        animateColorAsState(
            targetValue =
                when {
                    primary && hovered -> MaterialTheme.colorScheme.primaryContainer
                    primary -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                    hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "welcome action color",
        )
    Surface(
        color = color,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(6.dp),
        shadowElevation = elevation,
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Modifier.welcomeEntrance(
    progress: Float,
    offset: Float,
): Modifier =
    graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * offset
    }
