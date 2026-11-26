@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.xuexue.BuiltInLayoutPreset
import dev.xuexue.WorkbenchState
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkspaceEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QuickOpenDialog(
    state: WorkbenchState,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val allFiles = remember(state.workspaceTree) { state.workspaceTree?.let(::flattenDialogFiles).orEmpty() }
    val results = allFiles.filter { it.relativePath.contains(query, true) || it.name.contains(query, true) }.take(40)
    LaunchedEffect(results) { selectedIndex = selectedIndex.coerceIn(0, results.lastIndex.coerceAtLeast(0)) }
    LaunchedEffect(selectedIndex, results.size) {
        if (results.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
    }
    MotionDialog(onDismiss) { dismiss ->
        fun openSelected() {
            results.getOrNull(selectedIndex)?.let { file ->
                state.execute(WorkbenchCommand.OpenWorkspaceEntry(file.relativePath, pinned = true))
                dismiss()
            }
        }
        PaletteSurface(
            spec =
                PaletteSurfaceSpec(
                    title = "快速打开",
                    icon = Icons.Default.Search,
                    placeholder = "输入文件名或路径",
                    resultLabel = "${results.size} 个文件",
                ),
            query = query,
            onQueryChange = {
                query = it
                selectedIndex = 0
            },
            focusRequester = focusRequester,
            onDismiss = dismiss,
            onPreviewKeyEvent = { event ->
                handlePaletteNavigation(
                    event = event,
                    itemCount = results.size,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    onAccept = ::openSelected,
                    onDismiss = dismiss,
                )
            },
        ) {
            AnimatedContent(
                targetState = results.isEmpty(),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(WorkbenchMotion.standardTween()) togetherWith fadeOut(WorkbenchMotion.exitTween())
                },
                label = "quick open results",
            ) { empty ->
                if (empty) {
                    PaletteEmptyState(Icons.Default.Search, "没有匹配文件")
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(results, key = { _, file -> file.relativePath }) { index, file ->
                            PaletteResultRow(
                                icon = dialogFileIcon(file.name),
                                title = file.name,
                                detail = file.relativePath,
                                selected = index == selectedIndex,
                                modifier = Modifier,
                                onClick = {
                                    selectedIndex = index
                                    openSelected()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandPaletteDialog(
    state: WorkbenchState,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val commands =
        commandPaletteEntries(state, onSettings)
            .filter { it.label.contains(query, true) }
    LaunchedEffect(commands.size, query) {
        selectedIndex = selectedIndex.coerceIn(0, commands.lastIndex.coerceAtLeast(0))
    }
    LaunchedEffect(selectedIndex, commands.size) {
        if (commands.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
    }
    MotionDialog(onDismiss) { dismiss ->
        fun runSelected() {
            commands.getOrNull(selectedIndex)?.let { command ->
                command.action()
                dismiss()
            }
        }
        PaletteSurface(
            spec =
                PaletteSurfaceSpec(
                    title = "命令面板",
                    icon = Icons.Default.MoreVert,
                    placeholder = "搜索命令",
                    resultLabel = "${commands.size} 条命令",
                ),
            query = query,
            onQueryChange = {
                query = it
                selectedIndex = 0
            },
            focusRequester = focusRequester,
            onDismiss = dismiss,
            onPreviewKeyEvent = { event ->
                handlePaletteNavigation(
                    event = event,
                    itemCount = commands.size,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    onAccept = ::runSelected,
                    onDismiss = dismiss,
                )
            },
        ) {
            AnimatedContent(
                targetState = commands.isEmpty(),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(WorkbenchMotion.standardTween()) togetherWith fadeOut(WorkbenchMotion.exitTween())
                },
                label = "command results",
            ) { empty ->
                if (empty) {
                    PaletteEmptyState(Icons.Default.MoreVert, "没有匹配命令")
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(commands, key = { _, command -> command.label }) { index, command ->
                            PaletteResultRow(
                                icon = command.icon,
                                title = command.label,
                                trailing = command.shortcut,
                                selected = index == selectedIndex,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    selectedIndex = index
                                    runSelected()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class PaletteCommand(
    val label: String,
    val icon: ImageVector,
    val shortcut: String? = null,
    val action: () -> Unit,
)

private data class PaletteSurfaceSpec(
    val title: String,
    val icon: ImageVector,
    val placeholder: String,
    val resultLabel: String,
)

@Composable
private fun MotionDialog(
    onDismiss: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    val progress by
        animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec =
                if (visible) WorkbenchMotion.fastTween() else WorkbenchMotion.exitTween(),
            label = "dialog visibility",
        )
    val dismiss = {
        if (visible) {
            visible = false
            scope.launch {
                delay(WorkbenchMotion.FAST.toLong())
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { visible = true }
    Dialog(onDismissRequest = dismiss) {
        Box(
            Modifier.graphicsLayer {
                alpha = progress
            },
        ) {
            content(dismiss)
        }
    }
}

@Composable
private fun PaletteSurface(
    spec: PaletteSurfaceSpec,
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onDismiss: () -> Unit,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier =
            Modifier
                .width(680.dp)
                .height(520.dp)
                .onPreviewKeyEvent(onPreviewKeyEvent),
        color = MaterialTheme.colorScheme.surface,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(8.dp),
        shadowElevation = 20.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            PaletteHeader(spec.title, spec.icon, onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(spec.placeholder) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape =
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .focusRequester(focusRequester),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                content()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    spec.resultLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PaletteHeader(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(start = 16.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(5.dp),
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Close, "关闭", Modifier.size(17.dp))
        }
    }
}

private fun handlePaletteNavigation(
    event: KeyEvent,
    itemCount: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> {
            if (itemCount > 0) onSelect((selectedIndex + 1) % itemCount)
            true
        }

        Key.DirectionUp -> {
            if (itemCount > 0) onSelect((selectedIndex - 1 + itemCount) % itemCount)
            true
        }

        Key.Enter -> {
            if (itemCount > 0) onAccept()
            true
        }

        Key.Escape -> {
            onDismiss()
            true
        }

        else -> {
            false
        }
    }
}

private fun commandPaletteEntries(
    state: WorkbenchState,
    onSettings: () -> Unit,
): List<PaletteCommand> {
    fun command(
        label: String,
        icon: ImageVector,
        command: WorkbenchCommand,
        shortcut: String? = null,
    ) = PaletteCommand(label, icon, shortcut) { state.execute(command) }

    return buildList {
        add(command("新建 Markdown", Icons.Default.Add, WorkbenchCommand.NewMarkdown))
        add(command("保存当前文档", Icons.Default.Description, WorkbenchCommand.SaveDocument, "Ctrl S"))
        add(command("刷新工作区", Icons.Default.Refresh, WorkbenchCommand.RefreshWorkspace))
        add(
            PaletteCommand(
                "下载远程 PDF",
                Icons.Default.Download,
                action = state::requestRemotePdfDialog,
            ),
        )
        add(command("切换文件窗口", Icons.Default.FolderOpen, WorkbenchCommand.OpenTool(ToolWindow.FILES)))
        add(command("切换搜索窗口", Icons.Default.Search, WorkbenchCommand.OpenTool(ToolWindow.SEARCH)))
        add(command("显示属性窗口", Icons.Default.Tune, WorkbenchCommand.OpenTool(ToolWindow.PROPERTIES)))
        add(command("显示关系图谱", Icons.Default.Hub, WorkbenchCommand.OpenTool(ToolWindow.GRAPH)))
        add(command("显示任务中心", Icons.Default.CheckCircle, WorkbenchCommand.OpenTool(ToolWindow.TASKS)))
        add(command("显示 Agent", Icons.Default.SmartToy, WorkbenchCommand.ShowAgent, "Ctrl Alt A"))
        add(command("在文档区打开 Agent", Icons.Default.Description, WorkbenchCommand.OpenAgentDocument))
        addAll(focusPaletteCommands(state))
        add(command("聚焦 Agent", Icons.Default.SmartToy, WorkbenchCommand.Focus(WorkbenchFocusTarget.AGENT)))
        add(
            PaletteCommand("应用阅读布局", Icons.Default.Visibility) {
                state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.READING)
            },
        )
        add(
            PaletteCommand("应用写作布局", Icons.Default.Edit) {
                state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.WRITING)
            },
        )
        add(
            PaletteCommand("应用 Agent 协作布局", Icons.Default.SmartToy) {
                state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.AGENT)
            },
        )
        add(
            PaletteCommand("保存当前布局", Icons.Default.Add) {
                state.saveCurrentLayoutPreset("自定义布局 ${state.customLayoutPresets.size + 1}")
            },
        )
        state.customLayoutPresets.forEach { preset ->
            add(
                PaletteCommand("应用布局：${preset.name}", Icons.Default.Tune) {
                    state.applyCustomLayoutPreset(preset)
                },
            )
        }
        add(
            PaletteCommand(if (state.activityRailVisible) "隐藏活动栏" else "显示活动栏", Icons.Default.Settings) {
                state.activityRailVisible = !state.activityRailVisible
                state.persistLayout()
            },
        )
        add(PaletteCommand("设置", Icons.Default.Settings, action = onSettings))
        add(PaletteCommand("关闭工作区", Icons.Default.Close, action = state::closeWorkspace))
    }
}

private fun focusPaletteCommands(state: WorkbenchState): List<PaletteCommand> =
    listOf(
        PaletteCommand(
            "聚焦活动栏",
            Icons.Default.MoreVert,
            "Ctrl Alt R",
        ) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.ACTIVITY_RAIL)) },
        PaletteCommand(
            "聚焦工具窗口",
            Icons.Default.FolderOpen,
            "Ctrl Alt T",
        ) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.TOOL_WINDOW)) },
        PaletteCommand(
            "聚焦文档区",
            Icons.Default.Description,
            "Ctrl Alt D",
        ) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.DOCUMENT)) },
    )

private fun flattenDialogFiles(root: WorkspaceEntry): List<WorkspaceEntry> =
    buildList {
        fun visit(entry: WorkspaceEntry) {
            if (!entry.isDirectory) add(entry)
            entry.children.forEach(::visit)
        }
        visit(root)
    }

private fun dialogFileIcon(name: String): ImageVector =
    when {
        name.endsWith(".md", true) -> Icons.Default.Description

        name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf

        name.endsWith(".png", true) ||
            name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) -> Icons.Default.Image

        name.contains('.') -> Icons.AutoMirrored.Filled.InsertDriveFile

        else -> Icons.Default.Folder
    }
