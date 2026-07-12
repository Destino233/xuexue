@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.WorkbenchState
import dev.xuexue.ToolPanelDock
import dev.xuexue.BuiltInLayoutPreset
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.AgentSendAvailability
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
private enum class AgentSettingsSection(val label: String, val icon: ImageVector) {
    MODEL("模型与 Provider", Icons.Default.SmartToy),
    PERMISSIONS("工具与权限", Icons.Default.Tune),
    RESOURCES("资源", Icons.Default.Description),
    EXTENSIONS("扩展与包", Icons.Default.Hub),
    DIAGNOSTICS("诊断", Icons.Default.ErrorOutline),
}

@Composable
fun ToolPanel(state: WorkbenchState, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ToolPanelHeader(
                title = toolTitle(state.activeTool),
                dock = state.toolPanelDock,
                pinned = state.toolPanelPinned,
                expanded = state.toolPanelExpanded,
                onDock = { state.toolPanelDock = it; state.persistLayout() },
                onTogglePinned = state::toggleToolPanelPinned,
                onToggleExpanded = state::toggleToolPanelExpanded,
                split = state.secondaryTool != null,
                onToggleSplit = { if (state.secondaryTool == null) state.splitToolWindow() else state.closeToolSplit() },
                onDragStart = state::beginToolDockDrag,
                onDrag = state::updateToolDockDrag,
                onDragEnd = state::finishToolDockDrag,
                onDragCancel = state::cancelToolDockDrag,
                onClose = { state.leftPanelVisible = false; state.persistLayout() },
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.toolTabs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))) {
                    state.toolTabs.forEach { tool ->
                        val active = tool == state.activeTool
                        val secondary = tool == state.secondaryTool
                        Row(
                            Modifier.width(112.dp).fillMaxHeight().background(
                                when {
                                    active -> MaterialTheme.colorScheme.surface
                                    secondary -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                }
                            ).clickable { state.openToolWindow(tool) }.padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(toolTitle(tool), style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { state.closeToolTab(tool) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "关闭 ${toolTitle(tool)}", Modifier.size(13.dp)) }
                        }
                        Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val secondary = state.secondaryTool
            if (secondary == null) {
                ToolContent(state, state.activeTool, Modifier.fillMaxSize())
            } else {
                ToolContent(state, state.activeTool, Modifier.weight(1f).fillMaxWidth())
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryToolHeader(state, secondary)
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                ToolContent(state, secondary, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ToolContent(state: WorkbenchState, tool: ToolWindow, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (tool) {
                ToolWindow.FILES -> FilesTool(state)
                ToolWindow.SEARCH -> SearchTool(state)
                ToolWindow.BACKLINKS -> BacklinksTool(state)
                ToolWindow.OUTLINE -> OutlineTool(state)
                ToolWindow.PROPERTIES -> PropertiesTool(state)
                ToolWindow.GRAPH -> GraphTool(state)
                ToolWindow.TASKS -> TaskCenterTool(state)
                ToolWindow.AGENT -> AgentToolHint(
                    onOpenPanel = { state.agentPanelVisible = true },
                    onOpenDocument = state::openAgentDocument,
                )
            }
        }
    }

@Composable
private fun SecondaryToolHeader(state: WorkbenchState, tool: ToolWindow) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(31.dp).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)).padding(start = 10.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(toolTitle(tool), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.height(28.dp)) { Text("切换", style = MaterialTheme.typography.labelSmall) }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ToolWindow.entries.filter { it != state.activeTool && it != ToolWindow.AGENT }.forEach { target ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(toolTitle(target)) },
                        onClick = { state.splitToolWindow(target); menuOpen = false },
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
    var dockMenuOpen by remember { mutableStateOf(false) }
    var titleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).fillMaxHeight()
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
                }
                .padding(top = 12.dp),
        )
        Box {
            TextButton(onClick = { dockMenuOpen = true }) { Text(when (dock) { ToolPanelDock.LEFT -> "左侧"; ToolPanelDock.RIGHT -> "右侧"; ToolPanelDock.BOTTOM -> "底部"; ToolPanelDock.FLOAT -> "浮动" }, style = MaterialTheme.typography.labelSmall) }
            androidx.compose.material3.DropdownMenu(expanded = dockMenuOpen, onDismissRequest = { dockMenuOpen = false }) {
                ToolPanelDock.entries.forEach { target ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(when (target) { ToolPanelDock.LEFT -> "停靠到左侧"; ToolPanelDock.RIGHT -> "停靠到右侧"; ToolPanelDock.BOTTOM -> "停靠到底部"; ToolPanelDock.FLOAT -> "转为浮动窗口" }) },
                        onClick = { onDock(target); dockMenuOpen = false },
                    )
                }
            }
        }
        ToolIcon(Icons.Default.PushPin, if (pinned) "取消固定并自动隐藏" else "固定工具窗口", onTogglePinned)
        ToolIcon(Icons.Default.VerticalSplit, if (split) "关闭工具上下分栏" else "上下分栏工具窗口", onToggleSplit)
        ToolIcon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, if (expanded) "恢复工具窗口尺寸" else "展开工具窗口", onToggleExpanded)
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
            ToolIcon(Icons.Default.NoteAdd, "新建文件", { beginCreate(EntryDialogMode.CREATE_FILE) })
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
            Row(Modifier.fillMaxWidth().height(35.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选择 ${selected.size} 项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                ToolIcon(Icons.Default.ContentCopy, "复制到…", { transferMode = TransferMode.COPY })
                ToolIcon(Icons.Default.DriveFileMove, "移动到…", { transferMode = TransferMode.MOVE })
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
                if (state.canUndoFileOperation) TextButton(onClick = state::undoLastFileOperation) { Icon(Icons.Default.Undo, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("撤销") }
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
                    onOpen = { path, pinned -> state.execute(WorkbenchCommand.OpenWorkspaceEntry(path, pinned)); state.autoHideToolPanelAfterAction() },
                    selectionMode = selectionMode,
                    selectedPaths = selected.toSet(),
                    onSelect = { path -> if (selected.contains(path)) selected.remove(path) else selected.add(path) },
                    onRename = { entry -> dialogEntry = entry; nameInput = entry.name; entryDialog = EntryDialogMode.RENAME; state.inspectRenameImpact(entry.relativePath) },
                    onDelete = { entry -> deleteConfirmation = listOf(entry.relativePath) },
                    onCopyPath = { entry ->
                        val absolute = state.workspaceRoot?.resolve(entry.relativePath)?.toAbsolutePath()?.toString().orEmpty()
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(absolute), null)
                        state.notifyFileOperation("Path copied")
                    },
                    onLocate = { entry -> state.workspaceRoot?.resolve(entry.relativePath)?.parent?.toFile()?.let { runCatching { Desktop.getDesktop().open(it) } } },
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
        val parentPath = when {
            mode == EntryDialogMode.RENAME -> dialogEntry?.relativePath?.substringBeforeLast('/', "").orEmpty()
            dialogEntry?.isDirectory == true -> dialogEntry!!.relativePath
            else -> dialogEntry?.relativePath?.substringBeforeLast('/', "").orEmpty()
        }
        AlertDialog(
            onDismissRequest = { entryDialog = null },
            title = { Text(when (mode) { EntryDialogMode.CREATE_FILE -> "新建文件"; EntryDialogMode.CREATE_FOLDER -> "新建文件夹"; EntryDialogMode.RENAME -> "重命名" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (parentPath.isBlank()) "位置：工作区根目录" else "位置：$parentPath", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(nameInput, { nameInput = it }, singleLine = true, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    if (mode == EntryDialogMode.RENAME) {
                        when {
                            state.renameImpactLoading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)); Text("正在分析引用影响…", style = MaterialTheme.typography.bodySmall) }
                            state.renameImpactPaths.isEmpty() -> Text("没有发现需要更新的 Markdown 双链。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Column {
                                Text("将同步更新 ${state.renameImpactPaths.size} 篇笔记中的双链：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                state.renameImpactPaths.take(4).forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (mode == EntryDialogMode.RENAME) dialogEntry?.let { state.execute(WorkbenchCommand.RenameWorkspaceEntry(it.relativePath, nameInput)) }
                    else state.execute(WorkbenchCommand.CreateWorkspaceEntry(parentPath, nameInput, mode == EntryDialogMode.CREATE_FOLDER))
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
            confirmButton = { Button(onClick = { state.execute(WorkbenchCommand.DeleteWorkspaceEntries(paths)); selected.clear(); deleteConfirmation = null }) { Text("移入回收站") } },
            dismissButton = { TextButton(onClick = { deleteConfirmation = null }) { Text("取消") } },
        )
    }
    transferMode?.let { mode ->
        DirectoryTargetDialog(
            root = root,
            title = if (mode == TransferMode.COPY) "复制到" else "移动到",
            onDismiss = { transferMode = null },
            onSelect = { target ->
                if (mode == TransferMode.COPY) state.execute(WorkbenchCommand.CopyWorkspaceEntries(selected.toList(), target))
                else state.execute(WorkbenchCommand.MoveWorkspaceEntries(selected.toList(), target))
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
    val visible = remember(root, filter, showHidden, expanded.toMap()) {
        buildVisibleEntries(root, filter.trim(), showHidden, expanded)
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        items(visible, key = { it.first.relativePath + ":" + it.second }) { (entry, depth) ->
            FileTreeRow(
                entry = entry,
                depth = depth,
                expanded = expanded[entry.relativePath] == true,
                onToggle = { expanded[entry.relativePath] = expanded[entry.relativePath] != true },
                onOpen = onOpen,
                selectionMode = selectionMode,
                selected = selectedPaths.contains(entry.relativePath),
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
    var dragCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val icon = when {
        entry.isDirectory && expanded -> Icons.Default.FolderOpen
        entry.isDirectory -> Icons.Default.Folder
        entry.name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf
        entry.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") -> Icons.Default.Image
        entry.name.endsWith(".md", true) -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(31.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent).pointerInput(entry.relativePath, selectionMode) {
            detectTapGestures(
                onTap = { if (selectionMode) onSelect() else if (entry.isDirectory) onToggle() else onOpen(entry.relativePath, false) },
                onDoubleTap = { if (!selectionMode) { if (entry.isDirectory) onToggle() else onOpen(entry.relativePath, true) } },
                onLongPress = { menuOpen = true },
            )
        }.padding(start = (4 + depth * 14).dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onSelect() }, modifier = Modifier.size(24.dp))
        if (entry.isDirectory) Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        else Spacer(Modifier.width(16.dp))
        Icon(icon, null, modifier = Modifier.size(17.dp), tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.DragIndicator,
            "拖动文件",
            modifier = Modifier.size(22.dp).onGloballyPositioned { dragCoordinates = it }.pointerInput(entry.relativePath) {
                detectDragGestures(
                    onDragStart = { local -> dragCoordinates?.localToRoot(local)?.let(onDragStart) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = { change, _ -> dragCoordinates?.localToRoot(change.position)?.let(onDrag); change.consume() },
                )
            },
            tint = MaterialTheme.colorScheme.outline,
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.MoreVert, "文件操作", Modifier.size(15.dp)) }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuOpen = false; onRename() })
                androidx.compose.material3.DropdownMenuItem(text = { Text("复制路径") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menuOpen = false; onCopyPath() })
                androidx.compose.material3.DropdownMenuItem(text = { Text("在文件管理器中定位") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menuOpen = false; onLocate() })
                if (!entry.isDirectory) androidx.compose.material3.DropdownMenuItem(text = { Text("添加到 Agent 上下文") }, leadingIcon = { Icon(Icons.Default.SmartToy, null) }, onClick = { menuOpen = false; onAgent() })
                androidx.compose.material3.Divider()
                androidx.compose.material3.DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
            }
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
    val directories = remember(root, query) {
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
    fun visit(entry: WorkspaceEntry, depth: Int) {
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
            modifier = Modifier.fillMaxWidth().padding(8.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { state.search(); true } else false
            },
        )
        when {
            state.isLoading -> CenterMessage("正在搜索…", loading = true)
            state.searchQuery.isBlank() -> CenterMessage("输入关键词搜索当前工作区。")
            state.searchResults.isEmpty() -> CenterMessage("没有找到匹配内容。")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.searchResults) { hit ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { state.navigateToSearchHit(hit); state.autoHideToolPanelAfterAction() }.padding(vertical = 9.dp)) {
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
private fun LinkSection(title: String, links: List<Pair<String, String>>, state: WorkbenchState) {
    SectionLabel(title)
    if (links.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    links.forEach { (label, path) ->
        Row(Modifier.fillMaxWidth().clickable { state.navigateTo(path); state.autoHideToolPanelAfterAction() }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 7.dp)) {
        items(analysis.headings) { heading ->
            Row(
                Modifier.fillMaxWidth().clickable { document.cursor = androidx.compose.ui.text.TextRange(lineOffset(document.content, heading.line)) }
                    .padding(start = (10 + (heading.level - 1) * 13).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(34.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("局部关系", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.weight(0.5f))
        incoming.take(4).forEach { note -> GraphNode(note.title, secondary = true) { state.navigateTo(note.relativePath); state.autoHideToolPanelAfterAction() }; Spacer(Modifier.height(7.dp)) }
        Icon(Icons.Default.Hub, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(7.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) {
            Text(document.displayName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(7.dp))
        Icon(Icons.Default.Hub, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        outgoing.take(4).forEach { note -> Spacer(Modifier.height(7.dp)); GraphNode(note.title, secondary = false) { state.navigateTo(note.relativePath); state.autoHideToolPanelAfterAction() } }
        if (incoming.isEmpty() && outgoing.isEmpty()) Text("暂无已解析的链接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GraphNode(label: String, secondary: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (secondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TaskCenterTool(state: WorkbenchState) {
    Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("后台任务", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = state::requestRemotePdfDialog) { Icon(Icons.Default.Download, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("远程 PDF") }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
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
        Icon(icon, null, Modifier.size(18.dp), tint = when { error -> MaterialTheme.colorScheme.error; running -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary })
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
                Text("文件将保存到工作区的 Downloads 目录。下载进度、取消与失败重试在任务中心处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { state.downloadRemotePdf(url) }, enabled = url.isNotBlank() && !state.agentOfflineMode) { Text("开始下载") } },
        dismissButton = { TextButton(onClick = { state.remotePdfDialogVisible = false }) { Text("取消") } },
    )
}

@Composable
private fun AgentToolHint(onOpenPanel: () -> Unit, onOpenDocument: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.SmartToy, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(12.dp))
        Text("Agent 使用独立面板显示，便于同时引用当前文档。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenPanel) { Text("打开侧栏") }
            TextButton(onClick = onOpenDocument) { Text("在文档区打开") }
        }
    }
}

@Composable
fun AgentPanel(
    state: WorkbenchState,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onFloat: (() -> Unit)? = null,
    onOpenDocument: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var sessionMenuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    Box(modifier.onGloballyPositioned { state.agentDropBounds = it.boundsInRoot() }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(43.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("Agent", fontWeight = FontWeight.SemiBold)
                    Text(if (state.agentModel.isBlank()) "尚未配置模型" else state.agentModel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                onOpenDocument?.let { ToolIcon(Icons.Default.Description, "在文档区打开", it) }
                onFloat?.let { ToolIcon(if (state.agentFloating) Icons.Default.CloseFullscreen else Icons.Default.OpenInNew, if (state.agentFloating) "停靠 Agent" else "Agent 浮动窗口", it) }
                ToolIcon(Icons.Default.Settings, "Agent 设置", onSettings)
                ToolIcon(Icons.Default.Close, "关闭 Agent", onClose)
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(37.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                    state.agentSessions.forEach { session ->
                        val active = session.id == state.activeAgentSessionId
                        Surface(
                            onClick = { state.selectAgentSession(session.id) },
                            color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                            modifier = Modifier.width(120.dp).fillMaxHeight(),
                        ) {
                            Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.name, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (active && state.agentRunning) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                            }
                        }
                        Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                ToolIcon(Icons.Default.Add, "新建会话", state::newAgentSession)
                Box {
                    ToolIcon(Icons.Default.MoreVert, "会话操作", { sessionMenuOpen = true })
                    androidx.compose.material3.DropdownMenu(expanded = sessionMenuOpen, onDismissRequest = { sessionMenuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { renameInput = state.activeAgentSession.name; renameDialog = true; sessionMenuOpen = false })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("分叉会话") }, leadingIcon = { Icon(Icons.Default.CallSplit, null) }, onClick = { state.cloneAgentSession(fork = true); sessionMenuOpen = false })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("克隆会话") }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { state.cloneAgentSession(); sessionMenuOpen = false })
                        androidx.compose.material3.Divider()
                        androidx.compose.material3.DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { state.deleteAgentSession(state.activeAgentSessionId); sessionMenuOpen = false })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(27.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val estimatedTokens = state.agentMessages.sumOf { it.content.length }.div(4)
                Text("上下文 ≈ $estimatedTokens tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("费用 --", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (state.canRestoreDeletedAgentSession) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("会话已删除", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = state::restoreDeletedAgentSession) { Icon(Icons.Default.Restore, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("恢复") }
                }
            }
            state.pendingAgentApproval?.let { request ->
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(7.dp))
                        Text(agentOperationLabel(request.operation), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("等待确认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(request.target, style = MaterialTheme.typography.bodySmall, fontFamily = CodeFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(request.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { state.decideAgentApproval(AgentApprovalDecision.ALLOW_ONCE) }, modifier = Modifier.height(31.dp)) { Text("仅本次允许") }
                        if (request.operation in setOf(AgentOperationKind.WRITE_FILE, AgentOperationKind.RUN_SHELL)) {
                            TextButton(onClick = { state.decideAgentApproval(AgentApprovalDecision.ALLOW_SESSION) }, modifier = Modifier.height(31.dp)) { Text("本会话允许") }
                        }
                        TextButton(onClick = { state.decideAgentApproval(AgentApprovalDecision.DENY) }, modifier = Modifier.height(31.dp)) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (state.agentSendAvailability in setOf(AgentSendAvailability.OFFLINE, AgentSendAvailability.MODEL_REQUIRED, AgentSendAvailability.API_KEY_REQUIRED)) {
                AgentAvailabilityBanner(state.agentSendAvailability, onSettings)
            }
            if (state.agentMessages.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.SmartToy, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(12.dp))
                    Text("围绕当前文档开始对话", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("可附带当前文档内容；运行时可随时停止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(state.agentMessages) { index, message ->
                        AgentMessageBubble(
                            role = message.role,
                            content = message.content,
                            error = message.error,
                            decision = message.decision,
                            onDecision = { state.setAgentDiffDecision(index, it) },
                        )
                    }
                    if (state.agentRunning) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在生成…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.includeActiveDocument, onCheckedChange = { state.includeActiveDocument = it }, modifier = Modifier.size(28.dp))
                    Icon(Icons.Default.AttachFile, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.includeActiveDocument && state.activeDocument != null) {
                            val document = state.activeDocument!!
                            if (document.kind == DocumentKind.PDF) "${document.displayName} · 第 ${document.pdfPage + 1} 页" else document.displayName
                        } else "不附带当前文档",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.agentContextPaths.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        state.agentContextPaths.forEach { path ->
                            Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                                Row(Modifier.padding(start = 7.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                                    IconButton(onClick = { state.removeAgentContext(path) }, modifier = Modifier.size(23.dp)) { Icon(Icons.Default.Close, "移除上下文", Modifier.size(13.dp)) }
                                }
                            }
                        }
                    }
                }
                if (state.agentContextSnippets.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        state.agentContextSnippets.forEach { snippet ->
                            Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                                Row(Modifier.padding(start = 7.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(snippet.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                    IconButton(onClick = { state.removeAgentSnippetContext(snippet.id) }, modifier = Modifier.size(23.dp)) {
                                        Icon(Icons.Default.Close, "移除选区上下文", Modifier.size(13.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                val canSend = state.agentSendAvailability == AgentSendAvailability.READY && state.agentInput.isNotBlank()
                OutlinedTextField(
                    value = state.agentInput,
                    onValueChange = { state.agentInput = it },
                    placeholder = { Text("询问或交代任务…") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && canSend) { state.sendAgentMessage(); true } else false
                    },
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = state::sendAgentMessage, enabled = canSend, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Send, if (state.agentRunning) "发送 Steering" else "发送", tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.agentRunning) IconButton(onClick = state::stopAgent, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                }
            }
            }
        }
        if (state.dragPayload != null) {
            val active = state.agentDropBounds?.contains(state.dragPosition) == true
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiary.copy(alpha = if (active) 0.16f else 0.06f)).border(if (active) 2.dp else 1.dp, MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(7.dp))
                        Text("释放以添加 ${state.dragPayload!!.displayName}", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名会话") },
            text = { OutlinedTextField(renameInput, { renameInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { state.renameAgentSession(state.activeAgentSessionId, renameInput); renameDialog = false }, enabled = renameInput.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AgentAvailabilityBanner(availability: AgentSendAvailability, onSettings: () -> Unit) {
    val (title, detail) = when (availability) {
        AgentSendAvailability.OFFLINE -> "Agent 处于离线模式" to "关闭离线模式后才能发送消息。"
        AgentSendAvailability.MODEL_REQUIRED -> "需要选择模型" to "在模型与 Provider 设置中选择本会话使用的模型。"
        AgentSendAvailability.API_KEY_REQUIRED -> "需要 API Key" to "配置 Provider 凭据后才能发送消息。"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)).padding(start = 10.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onSettings) { Text("设置") }
    }
}

@Composable
private fun AgentMessageBubble(
    role: String,
    content: String,
    error: Boolean,
    decision: String?,
    onDecision: (String?) -> Unit,
) {
    val user = role == "user"
    val collapsible = role in setOf("tool", "tool_call", "reasoning") && content.lines().size > 6
    var expanded by remember(content) { mutableStateOf(!collapsible) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = when { error -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f); user -> MaterialTheme.colorScheme.primaryContainer; role == "diff" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f); else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(if (user) 0.88f else 1f),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    when (role) {
                        "user" -> "你"
                        "steering" -> "Steering · 执行中"
                        "steering_queued" -> "Steering · 已排队"
                        "steering_cancelled" -> "Steering · 已取消"
                        "tool_call" -> "工具调用 · 运行中"
                        "tool" -> "工具结果"
                        "reasoning" -> "推理摘要"
                        "diff" -> "文件差异"
                        else -> "Agent"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (role == "diff") {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(7.dp)) {
                        content.ifBlank { "…" }.lines().forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = dev.xuexue.ui.CodeFont,
                                color = when { line.startsWith("+") -> MaterialTheme.colorScheme.primary; line.startsWith("-") -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                                modifier = Modifier.fillMaxWidth().background(when { line.startsWith("+") -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f); line.startsWith("-") -> MaterialTheme.colorScheme.error.copy(alpha = 0.07f); else -> Color.Transparent }),
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    if (decision == null) Row {
                        TextButton(onClick = { onDecision("accepted") }) { Text("接受") }
                        TextButton(onClick = { onDecision("rejected") }) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (decision == "accepted") "已接受" else "已拒绝", style = MaterialTheme.typography.labelSmall, color = if (decision == "accepted") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onDecision(null) }) { Text("撤销") }
                    }
                } else {
                    val shown = if (expanded) content.ifBlank { "…" } else content.lines().take(6).joinToString("\n") + "\n…"
                    Text(shown, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontFamily = if (role in setOf("tool", "tool_call")) dev.xuexue.ui.CodeFont else null)
                    if (collapsible) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "折叠" else "查看完整输出") }
                }
            }
        }
    }
}

private fun agentOperationLabel(operation: AgentOperationKind): String = when (operation) {
    AgentOperationKind.READ_FILE -> "Agent 请求读取文件"
    AgentOperationKind.WRITE_FILE -> "Agent 请求修改文件"
    AgentOperationKind.RUN_SHELL -> "Agent 请求执行命令"
    AgentOperationKind.OUTSIDE_WORKSPACE -> "Agent 请求访问工作区外部"
}

@Composable
fun AgentSettingsDialog(state: WorkbenchState, onDismiss: () -> Unit) {
    var section by remember { mutableStateOf(AgentSettingsSection.MODEL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp, max = 780.dp),
        title = { Text("设置") },
        text = {
            Row(Modifier.fillMaxWidth().height(410.dp)) {
                Column(Modifier.width(176.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AgentSettingsSection.entries.forEach { item ->
                        SettingsSectionItem(item, selected = item == section) { section = item }
                    }
                }
                Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp).verticalScroll(rememberScrollState())) {
                    Text(section.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    when (section) {
                        AgentSettingsSection.MODEL -> {
                            Text("OpenAI 兼容接口", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(state.agentEndpoint, { state.agentEndpoint = it }, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(state.agentModel, { state.agentModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(state.agentApiKey, { state.agentApiKey = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Text("API Key 仅保存在当前进程。Provider 目录、OAuth 与自定义 Provider 将使用此分区。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        AgentSettingsSection.PERMISSIONS -> {
                            ToggleSetting("信任当前工作区", "允许 Runtime 请求工作区内的工具操作", state.workspaceTrusted) { state.workspaceTrusted = it; state.persistLayout() }
                            ToggleSetting("只读模式", "阻止写文件与修改类工具", state.agentReadOnlyMode) { state.agentReadOnlyMode = it; state.persistLayout() }
                            ToggleSetting("离线模式", "禁用模型与资源的网络访问", state.agentOfflineMode) { state.agentOfflineMode = it; state.persistLayout() }
                            Divider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            ToggleSetting("文件写入", "write 与 edit", state.agentAllowWrite, enabled = !state.agentReadOnlyMode) { state.agentAllowWrite = it; state.persistLayout() }
                            ToggleSetting("Shell 命令", "bash 与平台命令执行", state.agentAllowShell, enabled = state.workspaceTrusted && !state.agentReadOnlyMode) { state.agentAllowShell = it; state.persistLayout() }
                            SettingsStatusRow("读取与搜索", "read · grep · find · ls", "启用")
                        }
                        AgentSettingsSection.RESOURCES -> {
                            SettingsStatusRow("Skills", "全局与项目资源", "0 已启用")
                            SettingsStatusRow("Prompt Templates", "可复用提示模板", "0")
                            SettingsStatusRow("Slash Commands", "会话命令", "0")
                            SettingsStatusRow("项目上下文", "AGENTS.md · CLAUDE.md", "自动发现")
                        }
                        AgentSettingsSection.EXTENSIONS -> {
                            SettingsStatusRow("自定义工具", "Runtime 工具注册", "未配置")
                            SettingsStatusRow("事件钩子", "会话与工具事件", "未配置")
                            SettingsStatusRow("Pi Packages", "npm 与 Git 包", "0 已安装")
                            SettingsStatusRow("上下文压缩", "自动压缩与分支摘要", "默认")
                        }
                        AgentSettingsSection.DIAGNOSTICS -> {
                            SettingsStatusRow("Headless Runtime", "Agent Core", "待接入")
                            SettingsStatusRow("会话存储", ".xuexue/agent-sessions.json", "${state.agentSessions.size} 个会话")
                            SettingsStatusRow("工作区索引", "Markdown 链接与搜索缓存", "就绪")
                            SettingsStatusRow("缓存统计", "模型、PDF 与资源缓存", "--")
                            SettingsStatusRow("日志与遥测", "诊断、更新检查与可选遥测", "关闭")
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { state.persistLayout(); onDismiss() }) { Text("完成") } },
    )
}

@Composable
private fun SettingsSectionItem(section: AgentSettingsSection, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().height(39.dp),
    ) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(section.icon, null, Modifier.size(17.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(section.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ToggleSetting(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SettingsStatusRow(title: String, detail: String, status: String) {
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}

@Composable
fun QuickOpenDialog(state: WorkbenchState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val allFiles = remember(state.workspaceTree) { state.workspaceTree?.let(::flattenFiles).orEmpty() }
    val results = allFiles.filter { it.relativePath.contains(query, true) || it.name.contains(query, true) }.take(40)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速打开") },
        text = {
            Column(Modifier.height(410.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("输入文件名") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty()) CenterMessage("没有匹配文件。")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(results) { file ->
                        Row(Modifier.fillMaxWidth().clickable { state.execute(WorkbenchCommand.OpenWorkspaceEntry(file.relativePath, pinned = true)); onDismiss() }.padding(vertical = 8.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(fileIcon(file.name), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(file.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun CommandPaletteDialog(state: WorkbenchState, onDismiss: () -> Unit, onSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val commands = buildList {
        add(Triple("新建 Markdown", Icons.Default.Add) { state.execute(WorkbenchCommand.NewMarkdown) })
        add(Triple("保存当前文档", Icons.Default.Description) { state.execute(WorkbenchCommand.SaveDocument) })
        add(Triple("刷新工作区", Icons.Default.Refresh) { state.execute(WorkbenchCommand.RefreshWorkspace) })
        add(Triple("下载远程 PDF", Icons.Default.Download) { state.requestRemotePdfDialog() })
        add(Triple("切换文件窗口", Icons.Default.FolderOpen) { state.execute(WorkbenchCommand.OpenTool(ToolWindow.FILES)) })
        add(Triple("切换搜索窗口", Icons.Default.Search) { state.execute(WorkbenchCommand.OpenTool(ToolWindow.SEARCH)) })
        add(Triple("显示属性窗口", Icons.Default.Tune) { state.execute(WorkbenchCommand.OpenTool(ToolWindow.PROPERTIES)) })
        add(Triple("显示关系图谱", Icons.Default.Hub) { state.execute(WorkbenchCommand.OpenTool(ToolWindow.GRAPH)) })
        add(Triple("显示任务中心", Icons.Default.CheckCircle) { state.execute(WorkbenchCommand.OpenTool(ToolWindow.TASKS)) })
        add(Triple("显示 Agent", Icons.Default.SmartToy) { state.execute(WorkbenchCommand.ShowAgent) })
        add(Triple("在文档区打开 Agent", Icons.Default.Description) { state.execute(WorkbenchCommand.OpenAgentDocument) })
        add(Triple("聚焦活动栏", Icons.Default.MoreVert) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.ACTIVITY_RAIL)) })
        add(Triple("聚焦工具窗口", Icons.Default.FolderOpen) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.TOOL_WINDOW)) })
        add(Triple("聚焦文档区", Icons.Default.Description) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.DOCUMENT)) })
        add(Triple("聚焦 Agent", Icons.Default.SmartToy) { state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.AGENT)) })
        add(Triple("应用阅读布局", Icons.Default.Visibility) { state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.READING) })
        add(Triple("应用写作布局", Icons.Default.Edit) { state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.WRITING) })
        add(Triple("应用 Agent 协作布局", Icons.Default.SmartToy) { state.applyBuiltInLayoutPreset(BuiltInLayoutPreset.AGENT) })
        add(Triple("保存当前布局", Icons.Default.Add) { state.saveCurrentLayoutPreset("自定义布局 ${state.customLayoutPresets.size + 1}") })
        state.customLayoutPresets.forEach { preset ->
            add(Triple("应用布局：${preset.name}", Icons.Default.Tune) { state.applyCustomLayoutPreset(preset) })
        }
        add(Triple(if (state.activityRailVisible) "隐藏活动栏" else "显示活动栏", Icons.Default.Settings) { state.activityRailVisible = !state.activityRailVisible; state.persistLayout() })
        add(Triple("设置", Icons.Default.Settings) { onSettings() })
        add(Triple("关闭工作区", Icons.Default.Close) { state.closeWorkspace() })
    }.filter { it.first.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("命令面板") },
        text = {
            Column(Modifier.height(360.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("搜索命令") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(commands) { command ->
                        Row(Modifier.fillMaxWidth().clickable { command.third(); if (command.first != "设置") onDismiss() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(command.second, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(command.first)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun WelcomeScreen(loading: Boolean, onCreate: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).padding(30.dp), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = Color.White, modifier = Modifier.size(27.dp)) }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("学习学习", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("本地优先的阅读、笔记与 Agent 工作台", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(34.dp))
            Text("开始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Surface(onClick = onCreate, color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column { Text("新建工作区", fontWeight = FontWeight.SemiBold); Text("选择一个目录作为新的知识工作区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column { Text("打开已有目录", fontWeight = FontWeight.SemiBold); Text("文件系统始终是内容真源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (loading) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在恢复上次工作区…", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String, loading: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.height(9.dp)) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun ToolIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) { Icon(icon, description, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun WorkspaceDragHandle(state: WorkbenchState, relativePath: String, displayName: String) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Icon(
        Icons.Default.DragIndicator,
        "拖动到文档或 Agent",
        modifier = Modifier.size(25.dp).onGloballyPositioned { coordinates = it }.pointerInput(relativePath) {
            detectDragGestures(
                onDragStart = { local -> coordinates?.localToRoot(local)?.let { state.beginWorkspaceDrag(relativePath, displayName, it) } },
                onDragEnd = state::finishWorkspaceDrag,
                onDragCancel = state::cancelWorkspaceDrag,
                onDrag = { change, _ -> coordinates?.localToRoot(change.position)?.let(state::updateWorkspaceDrag); change.consume() },
            )
        },
        tint = MaterialTheme.colorScheme.outline,
    )
}

private fun toolTitle(tool: ToolWindow): String = when (tool) {
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

private fun lineOffset(text: String, line: Int): Int {
    var current = 1
    text.forEachIndexed { index, char ->
        if (current == line) return index
        if (char == '\n') current++
    }
    return text.length
}

private fun flattenFiles(root: WorkspaceEntry): List<WorkspaceEntry> = buildList {
    fun visit(entry: WorkspaceEntry) {
        if (!entry.isDirectory) add(entry)
        entry.children.forEach(::visit)
    }
    visit(root)
}

private fun flattenDirectories(root: WorkspaceEntry): List<WorkspaceEntry> = buildList {
    fun visit(entry: WorkspaceEntry) {
        if (entry.isDirectory) add(entry)
        entry.children.forEach(::visit)
    }
    visit(root)
}

private fun findEntry(root: WorkspaceEntry, relativePath: String): WorkspaceEntry? {
    if (root.relativePath == relativePath) return root
    return root.children.firstNotNullOfOrNull { child -> findEntry(child, relativePath) }
}

private fun List<String>.filterTopLevelPaths(): List<String> = distinct().filter { candidate ->
    none { other -> other != candidate && candidate.startsWith("${other.trimEnd('/')}/") }
}

private fun validateBatchRename(root: WorkspaceEntry?, renames: Map<String, String>): String? {
    val changed = renames.mapValues { it.value.trim() }.filter { (path, name) -> name != path.substringAfterLast('/') }
    changed.forEach { (_, name) ->
        if (name.isBlank() || name in setOf(".", "..") || '/' in name || '\\' in name) return "名称不能为空，也不能包含路径分隔符。"
    }
    val targets = changed.map { (path, name) ->
        val parent = path.substringBeforeLast('/', "")
        if (parent.isBlank()) name else "$parent/$name"
    }
    if (targets.distinct().size != targets.size) return "多个项目不能使用同一个目标名称。"
    val existing = root?.let { workspaceRoot ->
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

private fun fileIcon(name: String): ImageVector = when {
    name.endsWith(".pdf", true) -> Icons.Default.PictureAsPdf
    name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") -> Icons.Default.Image
    name.endsWith(".md", true) -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
