@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.BuiltInLayoutPreset
import dev.xuexue.ToolPanelDock
import dev.xuexue.WorkbenchState
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkspaceEntry
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private enum class EntryDialogMode { CREATE_FILE, CREATE_FOLDER, RENAME }

private enum class TransferMode { COPY, MOVE }

@Composable
fun ToolPanel(
    state: WorkbenchState,
    modifier: Modifier = Modifier,
    outerChrome: Boolean = false,
) {
    Surface(
        modifier,
        color = if (outerChrome) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            ToolPanelHeader(
                title = if (state.toolTabs.size > 1) "工具" else toolTitle(state.activeTool),
                dock = state.toolPanelDock,
                pinned = state.toolPanelPinned,
                expanded = state.toolPanelExpanded,
                onDock = {
                    state.toolPanelDock = it
                    state.persistLayout()
                },
                onTogglePinned = state::toggleToolPanelPinned,
                onToggleExpanded = state::toggleToolPanelExpanded,
                split = state.secondaryTool != null,
                onToggleSplit = { if (state.secondaryTool == null) state.splitToolWindow() else state.closeToolSplit() },
                onDragStart = state::beginToolDockDrag,
                onDrag = state::updateToolDockDrag,
                onDragEnd = state::finishToolDockDrag,
                onDragCancel = state::cancelToolDockDrag,
                onClose = {
                    state.leftPanelVisible = false
                    state.persistLayout()
                },
            )
            if (!outerChrome) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToolTabStrip(state)
            val secondary = state.secondaryTool
            if (secondary == null) {
                ToolContent(state, state.activeTool, Modifier.fillMaxSize())
            } else {
                ToolContent(state, state.activeTool, Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryToolHeader(state, secondary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolContent(state, secondary, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ToolTabStrip(state: WorkbenchState) {
    if (state.toolTabs.size <= 1) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(start = 3.dp),
    ) {
        state.toolTabs.forEach { tool ->
            ToolTab(
                state = state,
                tool = tool,
                active = tool == state.activeTool,
                secondary = tool == state.secondaryTool,
            )
        }
    }
}

@Composable
private fun ToolTab(
    state: WorkbenchState,
    tool: ToolWindow,
    active: Boolean,
    secondary: Boolean,
) {
    val interactionSource = remember(tool) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val containerColor by
        animateColorAsState(
            targetValue =
                when {
                    active -> MaterialTheme.colorScheme.surface
                    secondary -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                    hovered -> MaterialTheme.colorScheme.surface.copy(alpha = 0.54f)
                    else -> Color.Transparent
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "tool tab color",
        )
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "tool tab scale",
        )
    Box(
        Modifier
            .width(112.dp)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                state.openToolWindow(tool)
            },
    ) {
        ToolTabContent(state, tool, active)
        MotionVisibility(
            visible = active,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) + fadeIn(),
            exit = shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) + fadeOut(),
        ) {
            Box(
                Modifier
                    .width(112.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ToolTabContent(
    state: WorkbenchState,
    tool: ToolWindow,
    active: Boolean,
) {
    Row(
        Modifier.fillMaxSize().padding(start = 9.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            toolTitle(tool),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MotionIconButton(onClick = { state.closeToolTab(tool) }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, "关闭 ${toolTitle(tool)}", Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ToolContent(
    state: WorkbenchState,
    tool: ToolWindow,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = tool,
        modifier = modifier,
        transitionSpec = {
            val enter =
                fadeIn(WorkbenchMotion.standardTween()) +
                    slideInHorizontally(WorkbenchMotion.standardTween()) { it / 18 }
            val exit =
                fadeOut(WorkbenchMotion.exitTween()) +
                    slideOutHorizontally(WorkbenchMotion.exitTween()) { -it / 24 }
            enter togetherWith exit
        },
        label = "tool content",
    ) { activeTool ->
        when (activeTool) {
            ToolWindow.FILES -> {
                FilesTool(state)
            }

            ToolWindow.SEARCH -> {
                SearchTool(state)
            }

            ToolWindow.BACKLINKS -> {
                BacklinksTool(state)
            }

            ToolWindow.OUTLINE -> {
                OutlineTool(state)
            }

            ToolWindow.PROPERTIES -> {
                PropertiesTool(state)
            }

            ToolWindow.GRAPH -> {
                GraphTool(state)
            }

            ToolWindow.TASKS -> {
                TaskCenterTool(state)
            }

            ToolWindow.AGENT -> {
                AgentToolHint(
                    onOpenPanel = { state.agentPanelVisible = true },
                    onOpenDocument = state::openAgentDocument,
                )
            }
        }
    }
}

@Composable
private fun SecondaryToolHeader(
    state: WorkbenchState,
    tool: ToolWindow,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(31.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f))
            .padding(start = 10.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(toolTitle(tool), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.height(28.dp)) { Text("切换", style = MaterialTheme.typography.labelSmall) }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ToolWindow.entries.filter { it != state.activeTool && it != ToolWindow.AGENT }.forEach { target ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(toolTitle(target)) },
                        onClick = {
                            state.splitToolWindow(target)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        ToolIcon(Icons.Default.Close, "关闭下方工具分栏", state::closeToolSplit)
    }
}

@Composable
private fun ToolPanelHeader(
    title: String,
    dock: ToolPanelDock,
    pinned: Boolean,
    expanded: Boolean,
    split: Boolean,
    onDock: (ToolPanelDock) -> Unit,
    onTogglePinned: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleSplit: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var titleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onGloballyPositioned { titleCoordinates = it }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleExpanded() }) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { local -> titleCoordinates?.localToRoot(local)?.let(onDragStart) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                        ) { change, _ ->
                            change.consume()
                            titleCoordinates?.localToRoot(change.position)?.let(onDrag)
                        }
                    }.padding(top = 13.dp),
        )
        Box {
            ToolIcon(Icons.Default.MoreVert, "工具窗口选项", { menuOpen = true })
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (pinned) "取消固定" else "固定工具窗口") },
                    leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    onClick = {
                        onTogglePinned()
                        menuOpen = false
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (split) "关闭工具分栏" else "上下分栏") },
                    leadingIcon = { Icon(Icons.Default.VerticalSplit, null) },
                    onClick = {
                        onToggleSplit()
                        menuOpen = false
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (expanded) "恢复窗口尺寸" else "展开工具窗口") },
                    leadingIcon = { Icon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, null) },
                    onClick = {
                        onToggleExpanded()
                        menuOpen = false
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolPanelDock.entries.forEach { target ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                (if (target == dock) "· " else "") +
                                    when (target) {
                                        ToolPanelDock.LEFT -> "停靠到左侧"
                                        ToolPanelDock.RIGHT -> "停靠到右侧"
                                        ToolPanelDock.BOTTOM -> "停靠到底部"
                                        ToolPanelDock.FLOAT -> "转为浮动窗口"
                                    },
                            )
                        },
                        onClick = {
                            onDock(target)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        ToolIcon(Icons.Default.Close, "关闭工具窗口", onClose)
    }
}

@Composable
private fun FilesTool(state: WorkbenchState) {
    var filter by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var entryDialog by remember { mutableStateOf<EntryDialogMode?>(null) }
    var dialogEntry by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var deleteConfirmation by remember { mutableStateOf<List<String>?>(null) }
    var transferMode by remember { mutableStateOf<TransferMode?>(null) }
    var batchRenameVisible by remember { mutableStateOf(false) }
    val batchRenameNames = remember { mutableStateMapOf<String, String>() }
    val root = state.workspaceTree

    fun beginCreate(mode: EntryDialogMode) {
        dialogEntry = selected.singleOrNull()?.let { path -> root?.let { findEntry(it, path) } }
        entryDialog = mode
        nameInput = if (mode == EntryDialogMode.CREATE_FILE) "Untitled.md" else "New folder"
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(39.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.AutoMirrored.Filled.NoteAdd, "新建文件", { beginCreate(EntryDialogMode.CREATE_FILE) })
            ToolIcon(Icons.Default.CreateNewFolder, "新建文件夹", { beginCreate(EntryDialogMode.CREATE_FOLDER) })
            ToolIcon(Icons.Default.Download, "下载远程 PDF", state::requestRemotePdfDialog)
            ToolIcon(Icons.Default.Refresh, "刷新目录", state::refreshWorkspace)
            ToolIcon(Icons.Default.CheckCircle, if (selectionMode) "退出多选" else "多选", {
                selectionMode = !selectionMode
                if (!selectionMode) selected.clear()
            })
            Spacer(Modifier.weight(1f))
            ToolIcon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showHidden) "隐藏点文件" else "显示点文件", { showHidden = !showHidden })
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            placeholder = { Text("按名称筛选") },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(17.dp)) },
            trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Clear, "清除") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        )
        if (selected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(35.dp)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选择 ${selected.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                ToolIcon(Icons.Default.ContentCopy, "复制到…", { transferMode = TransferMode.COPY })
                ToolIcon(Icons.AutoMirrored.Filled.DriveFileMove, "移动到…", { transferMode = TransferMode.MOVE })
                ToolIcon(Icons.Default.Edit, "批量重命名", {
                    batchRenameNames.clear()
                    selected.filterTopLevelPaths().forEach { path ->
                        root?.let { findEntry(it, path) }?.let { batchRenameNames[path] = it.name }
                    }
                    batchRenameVisible = batchRenameNames.isNotEmpty()
                })
                ToolIcon(Icons.Default.DeleteOutline, "删除所选", { deleteConfirmation = selected.toList() })
            }
        }
        state.fileOperationMessage?.let { message ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)).padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.canUndoFileOperation) {
                    TextButton(onClick = state::undoLastFileOperation) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("撤销")
                    }
                }
                IconButton(onClick = state::clearFileOperationMessage, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.Close, "关闭", Modifier.size(14.dp)) }
            }
        }
        if (state.isLoading && state.workspaceTree == null) {
            CenterMessage("正在读取工作区…", loading = true)
        } else {
            state.workspaceTree?.let { root ->
                WorkspaceTree(
                    root = root,
                    filter = filter,
                    showHidden = showHidden,
                    activePath = state.activePath,
                    onOpen = { path, pinned ->
                        state.execute(WorkbenchCommand.OpenWorkspaceEntry(path, pinned))
                        state.autoHideToolPanelAfterAction()
                    },
                    selectionMode = selectionMode,
                    selectedPaths = selected.toSet(),
                    onSelect = { path -> if (selected.contains(path)) selected.remove(path) else selected.add(path) },
                    onRename = { entry ->
                        dialogEntry = entry
                        nameInput = entry.name
                        entryDialog = EntryDialogMode.RENAME
                        state.inspectRenameImpact(entry.relativePath)
                    },
                    onDelete = { entry -> deleteConfirmation = listOf(entry.relativePath) },
                    onCopyPath = { entry ->
                        val absolute =
                            state
                                .resolveWorkspacePath(entry.relativePath)
                                ?.toAbsolutePath()
                                ?.toString()
                                .orEmpty()
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(absolute), null)
                        state.notifyFileOperation("Path copied")
                    },
                    onLocate = { entry ->
                        state
                            .resolveWorkspacePath(entry.relativePath)
                            ?.parent
                            ?.toFile()
                            ?.let { runCatching { Desktop.getDesktop().open(it) } }
                    },
                    onAgent = { entry -> state.execute(WorkbenchCommand.AddAgentContext(entry.relativePath)) },
                    onDragStart = { entry, position -> state.beginWorkspaceDrag(entry.relativePath, entry.name, position) },
                    onDrag = state::updateWorkspaceDrag,
                    onDragEnd = state::finishWorkspaceDrag,
                    onDragCancel = state::cancelWorkspaceDrag,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    entryDialog?.let { mode ->
        val parentPath =
            when {
                mode == EntryDialogMode.RENAME -> dialogEntry?.relativePath?.substringBeforeLast('/', "").orEmpty()
                dialogEntry?.isDirectory == true -> dialogEntry!!.relativePath
                else -> dialogEntry?.relativePath?.substringBeforeLast('/', "").orEmpty()
            }
        AlertDialog(
            onDismissRequest = { entryDialog = null },
            title = {
                Text(
                    when (mode) {
                        EntryDialogMode.CREATE_FILE -> "新建文件"
                        EntryDialogMode.CREATE_FOLDER -> "新建文件夹"
                        EntryDialogMode.RENAME -> "重命名"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (parentPath.isBlank()) "位置：工作区根目录" else "位置：$parentPath", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(nameInput, { nameInput = it }, singleLine = true, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    if (mode == EntryDialogMode.RENAME) {
                        when {
                            state.renameImpactLoading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(7.dp))
                                    Text("正在分析引用影响…", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            state.renameImpactPaths.isEmpty() -> {
                                Text(
                                    "没有发现需要更新的 Markdown 双链。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            else -> {
                                Column {
                                    Text("将同步更新 ${state.renameImpactPaths.size} 篇笔记中的双链：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    state.renameImpactPaths.take(4).forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (mode == EntryDialogMode.RENAME) {
                        dialogEntry?.let {
                            state.execute(WorkbenchCommand.RenameWorkspaceEntry(it.relativePath, nameInput))
                        }
                    } else {
                        state.execute(
                            WorkbenchCommand.CreateWorkspaceEntry(
                                parentPath,
                                nameInput,
                                mode == EntryDialogMode.CREATE_FOLDER,
                            ),
                        )
                    }
                    entryDialog = null
                }, enabled = nameInput.isNotBlank() && !state.renameImpactLoading) { Text(if (mode == EntryDialogMode.RENAME) "重命名" else "创建") }
            },
            dismissButton = { TextButton(onClick = { entryDialog = null }) { Text("取消") } },
        )
    }
    deleteConfirmation?.let { paths ->
        AlertDialog(
            onDismissRequest = { deleteConfirmation = null },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 ${paths.size} 个项目？") },
            text = { Text("项目将移入工作区回收站，可在本次运行中撤销。包含未保存修改的打开文档不会被删除。") },
            confirmButton = {
                Button(onClick = {
                    state.execute(WorkbenchCommand.DeleteWorkspaceEntries(paths))
                    selected.clear()
                    deleteConfirmation = null
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = null }) { Text("取消") } },
        )
    }
    transferMode?.let { mode ->
        DirectoryTargetDialog(
            root = root,
            title = if (mode == TransferMode.COPY) "复制到" else "移动到",
            onDismiss = { transferMode = null },
            onSelect = { target ->
                if (mode == TransferMode.COPY) {
                    state.execute(WorkbenchCommand.CopyWorkspaceEntries(selected.toList(), target))
                } else {
                    state.execute(WorkbenchCommand.MoveWorkspaceEntries(selected.toList(), target))
                }
                selected.clear()
                transferMode = null
            },
        )
    }
    if (batchRenameVisible) {
        val renameError = validateBatchRename(root, batchRenameNames)
        val changed = batchRenameNames.filter { (path, name) -> name.trim() != path.substringAfterLast('/') }
        AlertDialog(
            onDismissRequest = { batchRenameVisible = false },
            title = { Text("批量重命名 ${batchRenameNames.size} 个项目") },
            text = {
                Column(Modifier.widthIn(min = 520.dp).height(390.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("逐项确认新名称。执行时会同步更新 Markdown 双链，并可作为一批撤销。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(batchRenameNames.keys.toList(), key = { it }) { path ->
                            Column {
                                Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                OutlinedTextField(
                                    value = batchRenameNames[path].orEmpty(),
                                    onValueChange = { batchRenameNames[path] = it },
                                    singleLine = true,
                                    label = { Text("新名称") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Text(
                        renameError ?: "将修改 ${changed.size} 个名称；所有冲突会在执行前再次校验。",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (renameError == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.execute(WorkbenchCommand.BatchRenameWorkspaceEntries(changed.mapValues { it.value.trim() }))
                        selected.clear()
                        batchRenameVisible = false
                    },
                    enabled = changed.isNotEmpty() && renameError == null,
                ) { Text("应用重命名") }
            },
            dismissButton = { TextButton(onClick = { batchRenameVisible = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkspaceTree(
    root: WorkspaceEntry,
    filter: String,
    showHidden: Boolean,
    activePath: String?,
    onOpen: (String, Boolean) -> Unit,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    onSelect: (String) -> Unit,
    onRename: (WorkspaceEntry) -> Unit,
    onDelete: (WorkspaceEntry) -> Unit,
    onCopyPath: (WorkspaceEntry) -> Unit,
    onLocate: (WorkspaceEntry) -> Unit,
    onAgent: (WorkspaceEntry) -> Unit,
    onDragStart: (WorkspaceEntry, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember(root.relativePath) { mutableStateMapOf<String, Boolean>().apply { put(root.relativePath, true) } }
    val visible =
        remember(root, filter, showHidden, expanded.toMap()) {
            buildVisibleEntries(root, filter.trim(), showHidden, expanded)
        }
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 4.dp),
    ) {
        items(visible, key = { it.first.relativePath + ":" + it.second }) { (entry, depth) ->
            FileTreeRow(
                entry = entry,
                depth = depth,
                expanded = expanded[entry.relativePath] == true,
                onToggle = { expanded[entry.relativePath] = expanded[entry.relativePath] != true },
                onOpen = onOpen,
                selectionMode = selectionMode,
                selected = selectedPaths.contains(entry.relativePath),
                active = activePath == entry.relativePath,
                onSelect = { onSelect(entry.relativePath) },
                onRename = { onRename(entry) },
                onDelete = { onDelete(entry) },
                onCopyPath = { onCopyPath(entry) },
                onLocate = { onLocate(entry) },
                onAgent = { onAgent(entry) },
                onDragStart = { position -> onDragStart(entry, position) },
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
            )
        }
    }
}

@Composable
private fun FileTreeRow(
    entry: WorkspaceEntry,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String, Boolean) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onLocate: () -> Unit,
    onAgent: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember(entry.relativePath) { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val motion = rememberFileTreeMotion(selected, active, expanded, hovered)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(31.dp)
                .background(motion.rowColor)
                .hoverable(interactionSource)
                .fileTreeRowInput(entry, selectionMode, onSelect, onToggle, onOpen) { menuOpen = true }
                .padding(start = (4 + depth * 14).dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTreeRowBody(
            entry = entry,
            expanded = expanded,
            selectionMode = selectionMode,
            selected = selected,
            active = active,
            motion = motion,
            onSelect = onSelect,
            dragActions = FileTreeDragActions(onDragStart, onDrag, onDragEnd, onDragCancel),
        )
        FileTreeEntryMenu(
            expanded = menuOpen,
            isDirectory = entry.isDirectory,
            onOpen = { menuOpen = true },
            onDismiss = { menuOpen = false },
            actions = FileTreeActions(onRename, onDelete, onCopyPath, onLocate, onAgent),
        )
    }
}

private data class FileTreeMotion(
    val rowColor: Color,
    val chevronRotation: Float,
    val dragAlpha: Float,
    val activeIndicatorAlpha: Float,
)

private data class FileTreeDragActions(
    val start: (Offset) -> Unit,
    val drag: (Offset) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

@Composable
private fun rememberFileTreeMotion(
    selected: Boolean,
    active: Boolean,
    expanded: Boolean,
    hovered: Boolean,
): FileTreeMotion {
    val rowColor by
        animateColorAsState(
            targetValue =
                when {
                    selected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.052f)
                    else -> Color.Transparent
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "workspace row color",
        )
    val chevronRotation by
        animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "folder chevron",
        )
    val dragAlpha by
        animateFloatAsState(
            targetValue = if (hovered) 0.9f else 0.28f,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "file drag handle",
        )
    val activeIndicatorAlpha by
        animateFloatAsState(
            targetValue = if (active) 1f else 0f,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "active file indicator",
        )
    return FileTreeMotion(rowColor, chevronRotation, dragAlpha, activeIndicatorAlpha)
}

private fun Modifier.fileTreeRowInput(
    entry: WorkspaceEntry,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onOpen: (String, Boolean) -> Unit,
    onLongPress: () -> Unit,
): Modifier =
    pointerInput(entry.relativePath, selectionMode) {
        detectTapGestures(
            onTap = {
                when {
                    selectionMode -> onSelect()
                    entry.isDirectory -> onToggle()
                    else -> onOpen(entry.relativePath, false)
                }
            },
            onDoubleTap = {
                if (!selectionMode) {
                    if (entry.isDirectory) onToggle() else onOpen(entry.relativePath, true)
                }
            },
            onLongPress = { onLongPress() },
        )
    }

@Composable
private fun androidx.compose.foundation.layout.RowScope.FileTreeRowBody(
    entry: WorkspaceEntry,
    expanded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    active: Boolean,
    motion: FileTreeMotion,
    onSelect: () -> Unit,
    dragActions: FileTreeDragActions,
) {
    var dragCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onSelect() }, modifier = Modifier.size(24.dp))
    Box(
        Modifier
            .width(3.dp)
            .height(17.dp)
            .graphicsLayer { alpha = motion.activeIndicatorAlpha }
            .background(
                MaterialTheme.colorScheme.primary,
                androidx.compose.foundation.shape
                    .RoundedCornerShape(3.dp),
            ),
    )
    Spacer(Modifier.width(4.dp))
    if (entry.isDirectory) {
        Icon(
            Icons.Default.ExpandMore,
            null,
            Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = motion.chevronRotation },
            tint = MaterialTheme.colorScheme.outline,
        )
    } else {
        Spacer(Modifier.width(16.dp))
    }
    Icon(
        workspaceTreeIcon(entry, expanded),
        null,
        modifier = Modifier.size(17.dp),
        tint =
            if (entry.isDirectory) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
    Spacer(Modifier.width(6.dp))
    Text(
        entry.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Icon(
        Icons.Default.DragIndicator,
        "拖动文件",
        modifier =
            Modifier
                .size(22.dp)
                .graphicsLayer { alpha = motion.dragAlpha }
                .onGloballyPositioned { dragCoordinates = it }
                .pointerInput(entry.relativePath) {
                    detectDragGestures(
                        onDragStart = { local -> dragCoordinates?.localToRoot(local)?.let(dragActions.start) },
                        onDragEnd = dragActions.end,
                        onDragCancel = dragActions.cancel,
                        onDrag = { change, _ ->
                            dragCoordinates?.localToRoot(change.position)?.let(dragActions.drag)
                            change.consume()
                        },
                    )
                },
        tint = MaterialTheme.colorScheme.outline,
    )
}

private fun workspaceTreeIcon(
    entry: WorkspaceEntry,
    expanded: Boolean,
): ImageVector =
    when {
        entry.isDirectory && expanded -> Icons.Default.FolderOpen
        entry.isDirectory -> Icons.Default.Folder
        entry.name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf
        entry.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") -> Icons.Default.Image
        entry.name.endsWith(".md", true) -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

private data class FileTreeActions(
    val rename: () -> Unit,
    val delete: () -> Unit,
    val copyPath: () -> Unit,
    val locate: () -> Unit,
    val agent: () -> Unit,
)

@Composable
private fun FileTreeEntryMenu(
    expanded: Boolean,
    isDirectory: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    actions: FileTreeActions,
) {
    Box {
        IconButton(onClick = onOpen, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.MoreVert, "文件操作", Modifier.size(15.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = {
                    onDismiss()
                    actions.rename()
                },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("复制路径") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = {
                    onDismiss()
                    actions.copyPath()
                },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("在文件管理器中定位") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                onClick = {
                    onDismiss()
                    actions.locate()
                },
            )
            if (!isDirectory) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("添加到 Agent 上下文") },
                    leadingIcon = { Icon(Icons.Default.SmartToy, null) },
                    onClick = {
                        onDismiss()
                        actions.agent()
                    },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismiss()
                    actions.delete()
                },
            )
        }
    }
}

@Composable
private fun DirectoryTargetDialog(
    root: WorkspaceEntry?,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val directories =
        remember(root, query) {
            root?.let(::flattenDirectories).orEmpty().filter { it.name.contains(query, true) || it.relativePath.contains(query, true) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.height(370.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("筛选目标文件夹") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(7.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(directories) { directory ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(directory.relativePath) }.padding(horizontal = 5.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(if (directory.relativePath.isBlank()) "工作区根目录" else directory.name)
                                if (directory.relativePath.isNotBlank()) Text(directory.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun buildVisibleEntries(
    root: WorkspaceEntry,
    filter: String,
    showHidden: Boolean,
    expanded: Map<String, Boolean>,
): List<Pair<WorkspaceEntry, Int>> {
    val result = mutableListOf<Pair<WorkspaceEntry, Int>>()

    fun matches(entry: WorkspaceEntry): Boolean = filter.isBlank() || entry.name.contains(filter, true) || entry.children.any(::matches)

    fun visit(
        entry: WorkspaceEntry,
        depth: Int,
    ) {
        if (entry !== root && (!showHidden && entry.name.startsWith('.'))) return
        if (entry !== root && !matches(entry)) return
        if (entry !== root) result += entry to (depth - 1)
        val revealChildren = filter.isNotBlank() || entry === root || expanded[entry.relativePath] == true
        if (entry.isDirectory && revealChildren) entry.children.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return result
}

@Composable
private fun SearchTool(state: WorkbenchState) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.searchQuery = it },
            singleLine = true,
            placeholder = { Text("搜索正文、标签和链接") },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { IconButton(onClick = state::search) { Icon(Icons.Default.Search, "搜索") } },
            modifier =
                Modifier.fillMaxWidth().padding(8.dp).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                        state.search()
                        true
                    } else {
                        false
                    }
                },
        )
        when {
            state.isLoading -> {
                CenterMessage("正在搜索…", loading = true)
            }

            state.searchQuery.isBlank() -> {
                CenterMessage("输入关键词搜索当前工作区。")
            }

            state.searchResults.isEmpty() -> {
                CenterMessage("没有找到匹配内容。")
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.searchResults) { hit ->
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        state.navigateToSearchHit(hit)
                                        state.autoHideToolPanelAfterAction()
                                    }.padding(vertical = 9.dp),
                            ) {
                                Text(hit.relativePath, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    buildAnnotatedString {
                                        append("${hit.line}:${hit.column}  ")
                                        val prefixLength = length
                                        append(hit.preview)
                                        val start = prefixLength + hit.previewMatchStart.coerceIn(0, hit.preview.length)
                                        val end = (start + hit.matchLength).coerceIn(start, length)
                                        if (end > start) addStyle(SpanStyle(color = MaterialTheme.colorScheme.onPrimary, background = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold), start, end)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            ToolIcon(Icons.Default.SmartToy, "添加到 Agent 上下文", { state.execute(WorkbenchCommand.AddAgentContext(hit.relativePath)) })
                            WorkspaceDragHandle(state, hit.relativePath, hit.relativePath.substringAfterLast('/'))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BacklinksTool(state: WorkbenchState) {
    val active = state.activeDocument
    if (active?.kind != DocumentKind.MARKDOWN) {
        CenterMessage("打开 Markdown 文档后可查看链接关系。")
        return
    }
    val backlinks = state.backlinksForActive()
    val outgoing = state.outgoingForActive()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        LinkSection("反向链接", backlinks.map { it.title to it.relativePath }, state)
        LinkSection("出链", outgoing.map { it.title to it.relativePath }, state)
        val tags = MarkdownParser.analyze(active.content).tags
        if (tags.isNotEmpty()) {
            SectionLabel("标签")
            tags.forEach { tag -> Text("#$tag", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) }
        }
    }
}

@Composable
private fun LinkSection(
    title: String,
    links: List<Pair<String, String>>,
    state: WorkbenchState,
) {
    SectionLabel(title)
    if (links.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    links.forEach { (label, path) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    state.navigateTo(path)
                    state.autoHideToolPanelAfterAction()
                }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Link, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(7.dp))
            Column {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OutlineTool(state: WorkbenchState) {
    val document = state.activeDocument
    if (document?.kind != DocumentKind.MARKDOWN) {
        CenterMessage("当前文档没有可显示的目录。")
        return
    }
    val analysis = remember(document.content) { MarkdownParser.analyze(document.content) }
    if (analysis.headings.isEmpty()) {
        CenterMessage("添加标题后，文档结构会显示在这里。")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 7.dp),
    ) {
        items(analysis.headings) { heading ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        document.cursor =
                            androidx.compose.ui.text
                                .TextRange(lineOffset(document.content, heading.line))
                    }.padding(start = (10 + (heading.level - 1) * 13).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(heading.text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (heading.level == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PropertiesTool(state: WorkbenchState) {
    val document = state.activeDocument
    if (document?.kind != DocumentKind.MARKDOWN) {
        CenterMessage("打开 Markdown 文档后可查看属性。")
        return
    }
    val properties = remember(document.content) { parseFrontmatter(document.content) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (properties.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Icon(Icons.Default.Tune, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(9.dp))
                    Text("当前文档没有 Frontmatter", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text("属性窗口为标签、日期、别名和插件字段保留统一入口。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                properties.forEach { (key, value) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(82.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("块 ID · 别名 · 插件字段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun GraphTool(state: WorkbenchState) {
    val document = state.activeDocument
    if (document?.kind != DocumentKind.MARKDOWN) {
        CenterMessage("打开笔记后可查看局部关系图。")
        return
    }
    val incoming = state.backlinksForActive()
    val outgoing = state.outgoingForActive()
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("局部关系", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.weight(0.5f))
        incoming.take(4).forEach { note ->
            GraphNode(note.title, secondary = true) {
                state.navigateTo(note.relativePath)
                state.autoHideToolPanelAfterAction()
            }
            Spacer(Modifier.height(7.dp))
        }
        Icon(Icons.Default.Hub, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(7.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(5.dp),
        ) {
            Text(document.displayName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(7.dp))
        Icon(Icons.Default.Hub, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        outgoing.take(4).forEach { note ->
            Spacer(Modifier.height(7.dp))
            GraphNode(note.title, secondary = false) {
                state.navigateTo(note.relativePath)
                state.autoHideToolPanelAfterAction()
            }
        }
        if (incoming.isEmpty() && outgoing.isEmpty()) Text("暂无已解析的链接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GraphNode(
    label: String,
    secondary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color =
            if (secondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskCenterTool(state: WorkbenchState) {
    Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("后台任务", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = state::requestRemotePdfDialog) {
                Icon(Icons.Default.Download, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("远程 PDF")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (state.isLoading) TaskRow(Icons.Default.HourglassTop, "工作区索引", "正在更新目录与链接索引", running = true)
            state.backgroundTasks.forEach { task ->
                TaskRow(
                    icon = if (task.failed) Icons.Default.ErrorOutline else Icons.Default.HourglassTop,
                    title = task.title,
                    detail = task.detail,
                    running = !task.failed,
                    error = task.failed,
                    progress = task.progress,
                    onCancel = if (task.cancellable) ({ state.cancelBackgroundTask(task.id) }) else null,
                    onRetry = if (task.retryable) ({ state.retryBackgroundTask(task.id) }) else null,
                )
            }
            if (state.agentRunning) TaskRow(Icons.Default.HourglassTop, "Agent", "模型正在生成，可从 Agent 面板停止", running = true)
            state.errorMessage?.let { TaskRow(Icons.Default.ErrorOutline, "需要处理", it, error = true) }
            if (!state.isLoading && state.backgroundTasks.isEmpty() && !state.agentRunning && state.errorMessage == null) {
                CenterMessage("没有耗时、可取消或失败的后台任务。")
            }
        }
    }
}

@Composable
private fun TaskRow(
    icon: ImageVector,
    title: String,
    detail: String,
    running: Boolean = false,
    error: Boolean = false,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            null,
            Modifier.size(18.dp),
            tint =
                when {
                    error -> MaterialTheme.colorScheme.error
                    running -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
            }
        }
        when {
            onRetry != null -> TextButton(onClick = onRetry) { Text("重试") }
            onCancel != null -> TextButton(onClick = onCancel) { Text("取消") }
            running -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun RemotePdfDialog(state: WorkbenchState) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { state.remotePdfDialogVisible = false },
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text("下载远程 PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("HTTP 或 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "保存位置：工作区 / Downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { state.downloadRemotePdf(url) }, enabled = url.isNotBlank() && !state.agentOfflineMode) { Text("开始下载") } },
        dismissButton = { TextButton(onClick = { state.remotePdfDialogVisible = false }) { Text("取消") } },
    )
}

@Composable
private fun CenterMessage(
    text: String,
    loading: Boolean = false,
) {
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(9.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
internal fun ToolIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider =
            androidx.compose.material3.TooltipDefaults
                .rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        MotionIconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(icon, description, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WorkspaceDragHandle(
    state: WorkbenchState,
    relativePath: String,
    displayName: String,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Icon(
        Icons.Default.DragIndicator,
        "拖动到文档或 Agent",
        modifier =
            Modifier.size(25.dp).onGloballyPositioned { coordinates = it }.pointerInput(relativePath) {
                detectDragGestures(
                    onDragStart = { local -> coordinates?.localToRoot(local)?.let { state.beginWorkspaceDrag(relativePath, displayName, it) } },
                    onDragEnd = state::finishWorkspaceDrag,
                    onDragCancel = state::cancelWorkspaceDrag,
                    onDrag = { change, _ ->
                        coordinates?.localToRoot(change.position)?.let(state::updateWorkspaceDrag)
                        change.consume()
                    },
                )
            },
        tint = MaterialTheme.colorScheme.outline,
    )
}

private fun toolTitle(tool: ToolWindow): String =
    when (tool) {
        ToolWindow.FILES -> "文件"
        ToolWindow.SEARCH -> "全局搜索"
        ToolWindow.BACKLINKS -> "链接"
        ToolWindow.OUTLINE -> "目录"
        ToolWindow.PROPERTIES -> "属性"
        ToolWindow.GRAPH -> "关系图谱"
        ToolWindow.TASKS -> "任务中心"
        ToolWindow.AGENT -> "Agent"
    }

private fun parseFrontmatter(source: String): List<Pair<String, String>> {
    val lines = source.lines()
    if (lines.firstOrNull()?.trim() != "---") return emptyList()
    return lines.drop(1).takeWhile { it.trim() != "---" }.mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }
}

private fun lineOffset(
    text: String,
    line: Int,
): Int {
    var current = 1
    text.forEachIndexed { index, char ->
        if (current == line) return index
        if (char == '\n') current++
    }
    return text.length
}

private fun flattenFiles(root: WorkspaceEntry): List<WorkspaceEntry> =
    buildList {
        fun visit(entry: WorkspaceEntry) {
            if (!entry.isDirectory) add(entry)
            entry.children.forEach(::visit)
        }
        visit(root)
    }

private fun flattenDirectories(root: WorkspaceEntry): List<WorkspaceEntry> =
    buildList {
        fun visit(entry: WorkspaceEntry) {
            if (entry.isDirectory) add(entry)
            entry.children.forEach(::visit)
        }
        visit(root)
    }

private fun findEntry(
    root: WorkspaceEntry,
    relativePath: String,
): WorkspaceEntry? {
    if (root.relativePath == relativePath) return root
    return root.children.firstNotNullOfOrNull { child -> findEntry(child, relativePath) }
}

private fun List<String>.filterTopLevelPaths(): List<String> =
    distinct().filter { candidate ->
        none { other -> other != candidate && candidate.startsWith("${other.trimEnd('/')}/") }
    }

private fun validateBatchRename(
    root: WorkspaceEntry?,
    renames: Map<String, String>,
): String? {
    val changed = renames.mapValues { it.value.trim() }.filter { (path, name) -> name != path.substringAfterLast('/') }
    changed.forEach { (_, name) ->
        if (name.isBlank() || name in setOf(".", "..") || '/' in name || '\\' in name) return "名称不能为空，也不能包含路径分隔符。"
    }
    val targets =
        changed.map { (path, name) ->
            val parent = path.substringBeforeLast('/', "")
            if (parent.isBlank()) name else "$parent/$name"
        }
    if (targets.distinct().size != targets.size) return "多个项目不能使用同一个目标名称。"
    val existing =
        root
            ?.let { workspaceRoot ->
                buildSet {
                    fun visit(entry: WorkspaceEntry) {
                        if (entry.relativePath.isNotBlank()) add(entry.relativePath)
                        entry.children.forEach(::visit)
                    }
                    visit(workspaceRoot)
                }
            }.orEmpty()
    val movingSources = changed.keys
    val occupied = targets.firstOrNull { target -> target in existing && target !in movingSources }
    if (occupied != null) return "目标已存在：$occupied"
    return null
}

private fun fileIcon(name: String): ImageVector =
    when {
        name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf

        name.substringAfterLast('.', "").lowercase() in
            setOf(
                "png",
                "jpg",
                "jpeg",
                "gif",
                "webp",
            )
        -> Icons.Default.Image

        name.endsWith(".md", true) -> Icons.Default.Description

        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
