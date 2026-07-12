package dev.xuexue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.xuexue.DocumentTab
import dev.xuexue.BuiltInLayoutPreset
import dev.xuexue.WorkbenchState
import dev.xuexue.ToolPanelDock
import dev.xuexue.core.CompactWorkbenchPage
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchAuxiliaryPanel
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkbenchWidthClass
import dev.xuexue.core.initialCompactPage
import dev.xuexue.core.workbenchWidthClass
import dev.xuexue.core.mediumAuxiliaryPanel
import kotlinx.coroutines.launch
import java.nio.file.Path
import javax.swing.JFileChooser

private enum class Overlay { QUICK_OPEN, COMMANDS, SETTINGS }

@Composable
fun WorkbenchApp(
    state: WorkbenchState,
    closeRequested: Boolean,
    onCancelClose: () -> Unit,
    onConfirmClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var pendingClose by remember { mutableStateOf<DocumentTab?>(null) }

    XueXueTheme {
        Surface(
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when {
                    event.isAltPressed && event.key == Key.F -> state.execute(WorkbenchCommand.OpenTool(ToolWindow.FILES))
                    event.isAltPressed && event.key == Key.S -> state.execute(WorkbenchCommand.OpenTool(ToolWindow.SEARCH))
                    event.isAltPressed && event.key == Key.L -> state.execute(WorkbenchCommand.OpenTool(ToolWindow.BACKLINKS))
                    event.isAltPressed && event.key == Key.A -> state.execute(WorkbenchCommand.ShowAgent)
                    event.isAltPressed && event.key == Key.R -> state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.ACTIVITY_RAIL))
                    event.isAltPressed && event.key == Key.T -> state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.TOOL_WINDOW))
                    event.isAltPressed && event.key == Key.D -> state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.DOCUMENT))
                    event.key == Key.P && event.isShiftPressed -> overlay = Overlay.COMMANDS
                    event.key == Key.P -> overlay = Overlay.QUICK_OPEN
                    event.key == Key.Tab -> state.execute(if (event.isShiftPressed) WorkbenchCommand.PreviousDocument else WorkbenchCommand.NextDocument)
                    event.key == Key.S -> state.execute(WorkbenchCommand.SaveDocument)
                    event.key == Key.Z && event.isShiftPressed -> state.execute(WorkbenchCommand.Redo)
                    event.key == Key.Z -> state.execute(WorkbenchCommand.Undo)
                    event.key == Key.Y -> state.execute(WorkbenchCommand.Redo)
                    event.key == Key.W -> state.activeDocument?.let { if (!state.closeTab(it)) pendingClose = it }
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints {
                val widthClass = workbenchWidthClass(maxWidth.value)
                when {
                    state.workspaceRoot == null -> WelcomeScreen(
                        loading = state.isLoading,
                        onCreate = { chooseWorkspace(create = true)?.let { path -> scope.launch { state.openWorkspace(path) } } },
                        onOpen = { chooseWorkspace(create = false)?.let { path -> scope.launch { state.openWorkspace(path) } } },
                    )
                    widthClass == WorkbenchWidthClass.COMPACT -> CompactWorkbench(
                        state = state,
                        onQuickOpen = { overlay = Overlay.QUICK_OPEN },
                        onCommands = { overlay = Overlay.COMMANDS },
                        onSettings = { overlay = Overlay.SETTINGS },
                    )
                    else -> DesktopWorkbench(
                        state = state,
                        onQuickOpen = { overlay = Overlay.QUICK_OPEN },
                        onCommands = { overlay = Overlay.COMMANDS },
                        onSettings = { overlay = Overlay.SETTINGS },
                        onCloseTab = { tab -> if (!state.closeTab(tab)) pendingClose = tab },
                        medium = widthClass == WorkbenchWidthClass.MEDIUM,
                    )
                }
            }
        }

        if (state.workspaceRoot != null && state.leftPanelVisible && state.toolPanelDock == ToolPanelDock.FLOAT) {
            Window(
                onCloseRequest = { state.leftPanelVisible = false; state.persistLayout() },
                title = "${state.activeTool.name.lowercase().replaceFirstChar(Char::uppercase)} - XueXue",
                state = rememberWindowState(width = 390.dp, height = 620.dp),
            ) {
                XueXueTheme { ToolPanel(state, Modifier.fillMaxSize()) }
            }
        }
        if (state.workspaceRoot != null && state.agentPanelVisible && state.agentFloating) {
            Window(
                onCloseRequest = state::toggleAgentFloating,
                title = "Agent - XueXue",
                state = rememberWindowState(width = 440.dp, height = 720.dp),
            ) {
                XueXueTheme {
                    AgentPanel(
                        state = state,
                        onClose = state::closeAgentPanel,
                        onSettings = { overlay = Overlay.SETTINGS },
                        onFloat = state::toggleAgentFloating,
                        onOpenDocument = state::openAgentDocument,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        state.detachedDocuments.toList().forEach { path ->
            state.documents.firstOrNull { it.relativePath == path }?.let { document ->
                key(path) {
                    Window(
                        onCloseRequest = { state.attachDocument(path) },
                        title = "${document.displayName} - XueXue",
                        state = rememberWindowState(width = 980.dp, height = 760.dp),
                    ) {
                        XueXueTheme { DetachedDocumentView(state, document, onSettings = { overlay = Overlay.SETTINGS }, onAttach = { state.attachDocument(path) }) }
                    }
                }
            }
        }

        when (overlay) {
            Overlay.QUICK_OPEN -> QuickOpenDialog(state, onDismiss = { overlay = null })
            Overlay.COMMANDS -> CommandPaletteDialog(
                state = state,
                onDismiss = { overlay = null },
                onSettings = { overlay = Overlay.SETTINGS },
            )
            Overlay.SETTINGS -> AgentSettingsDialog(state, onDismiss = { overlay = null })
            null -> Unit
        }
        pendingClose?.let { tab ->
            UnsavedDialog(
                name = tab.displayName,
                onCancel = { pendingClose = null },
                onDiscard = { state.closeTab(tab, force = true); pendingClose = null },
                onSave = { state.selectTab(tab.relativePath); state.saveActive(); state.closeTab(tab, force = true); pendingClose = null },
            )
        }
        if (closeRequested) {
            AlertDialog(
                onDismissRequest = onCancelClose,
                title = { Text("保存更改后退出？") },
                text = { Text("仍有未保存的文档。退出前将尝试保存这些更改。") },
                confirmButton = { Button(onClick = onConfirmClose) { Text("保存并退出") } },
                dismissButton = { TextButton(onClick = onCancelClose) { Text("取消") } },
            )
        }
        state.errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = state::clearError,
                icon = { Icon(Icons.Default.Bolt, null) },
                title = { Text("操作未完成") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = state::clearError) { Text("知道了") } },
            )
        }
        if (state.remotePdfDialogVisible) RemotePdfDialog(state)
    }
}

@Composable
private fun DesktopWorkbench(
    state: WorkbenchState,
    onQuickOpen: () -> Unit,
    onCommands: () -> Unit,
    onSettings: () -> Unit,
    onCloseTab: (DocumentTab) -> Unit,
    medium: Boolean = false,
) {
    val activityFocus = remember { FocusRequester() }
    val toolFocus = remember { FocusRequester() }
    val documentFocus = remember { FocusRequester() }
    val agentFocus = remember { FocusRequester() }
    val mediumPanel = mediumAuxiliaryPanel(state.leftPanelVisible, state.agentPanelVisible)
    val showToolPanel = state.leftPanelVisible && (!medium || mediumPanel == WorkbenchAuxiliaryPanel.TOOL_WINDOW)
    LaunchedEffect(state.focusRequestSerial, showToolPanel) {
        when (state.requestedFocusTarget) {
            WorkbenchFocusTarget.ACTIVITY_RAIL -> if (state.activityRailVisible) activityFocus.requestFocus()
            WorkbenchFocusTarget.TOOL_WINDOW -> {
                if (medium && state.agentPanelVisible) state.agentPanelVisible = false
                else if (showToolPanel && state.toolPanelDock != ToolPanelDock.FLOAT) toolFocus.requestFocus()
            }
            WorkbenchFocusTarget.DOCUMENT -> documentFocus.requestFocus()
            WorkbenchFocusTarget.AGENT -> if (state.agentPanelVisible && !state.agentFloating) agentFocus.requestFocus()
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { state.workbenchBounds = it.boundsInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            WorkbenchHeader(state, onQuickOpen, onCommands)
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.weight(1f).fillMaxWidth()) {
            val railSelect: (ToolWindow) -> Unit = { tool ->
                if (tool == ToolWindow.AGENT) state.agentPanelVisible = !state.agentPanelVisible
                else {
                    if (medium) state.agentPanelVisible = false
                    state.openToolWindow(tool)
                }
                state.persistLayout()
            }
            if (state.activityRailVisible && !state.activityRailRight) ActivityRail(state.activeTool, railSelect, onSettings, Modifier.focusRequester(activityFocus).focusable())
            if (showToolPanel && state.toolPanelDock == ToolPanelDock.LEFT) {
                ToolPanel(state, Modifier.requiredWidth((if (state.toolPanelExpanded) 480f else state.leftPanelWidth).dp).fillMaxHeight().focusRequester(toolFocus).focusable())
                PanelResizer(
                    onDrag = { delta -> state.leftPanelWidth = (state.leftPanelWidth + delta).coerceIn(210f, 480f) },
                    onDragEnd = state::persistLayout,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    DocumentWorkspace(state, onCloseTab, onSettings, onQuickOpen, Modifier.weight(1f).fillMaxHeight().focusRequester(documentFocus).focusable())
                    if (showToolPanel && state.toolPanelDock == ToolPanelDock.RIGHT) {
                        PanelResizer(
                            onDrag = { delta -> state.leftPanelWidth = (state.leftPanelWidth - delta).coerceIn(210f, 480f) },
                            onDragEnd = state::persistLayout,
                        )
                        ToolPanel(state, Modifier.requiredWidth((if (state.toolPanelExpanded) 480f else state.leftPanelWidth).dp).fillMaxHeight().focusRequester(toolFocus).focusable())
                    }
                    if (state.agentPanelVisible && !state.agentFloating) {
                        PanelResizer(
                            onDrag = { delta -> state.agentPanelWidth = (state.agentPanelWidth - delta).coerceIn(300f, 540f) },
                            onDragEnd = state::persistLayout,
                        )
                        AgentPanel(
                            state = state,
                            onClose = state::closeAgentPanel,
                            onSettings = onSettings,
                            onFloat = state::toggleAgentFloating,
                            onOpenDocument = state::openAgentDocument,
                            modifier = Modifier.requiredWidth(state.agentPanelWidth.dp).fillMaxHeight().focusRequester(agentFocus).focusable(),
                        )
                    }
                }
                if (showToolPanel && state.toolPanelDock == ToolPanelDock.BOTTOM) {
                    HorizontalPanelResizer(
                        onDrag = { delta -> state.bottomPanelHeight = (state.bottomPanelHeight - delta).coerceIn(150f, 420f) },
                        onDragEnd = state::persistLayout,
                    )
                    ToolPanel(state, Modifier.fillMaxWidth().requiredHeight((if (state.toolPanelExpanded) 420f else state.bottomPanelHeight).dp).focusRequester(toolFocus).focusable())
                }
            }
            if (state.activityRailVisible && state.activityRailRight) ActivityRail(state.activeTool, railSelect, onSettings, Modifier.focusRequester(activityFocus).focusable())
            }
            StatusBar(state)
        }
        if (state.toolDockDragging) ToolDockPreviewOverlay(state.toolDockPreview)
    }
}

@Composable
private fun ToolDockPreviewOverlay(active: ToolPanelDock?) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f))) {
        DockPreviewTarget("停靠左侧", active == ToolPanelDock.LEFT, Modifier.align(Alignment.CenterStart).width(138.dp).fillMaxHeight(0.68f))
        DockPreviewTarget("停靠右侧", active == ToolPanelDock.RIGHT, Modifier.align(Alignment.CenterEnd).width(138.dp).fillMaxHeight(0.68f))
        DockPreviewTarget("停靠底部", active == ToolPanelDock.BOTTOM, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.55f).height(116.dp))
        DockPreviewTarget("浮动窗口", active == ToolPanelDock.FLOAT, Modifier.align(Alignment.Center).width(220.dp).height(108.dp))
    }
}

@Composable
private fun DockPreviewTarget(label: String, active: Boolean, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = if (active) 0.18f else 0.07f))
            .border(if (active) 2.dp else 1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CompactWorkbench(
    state: WorkbenchState,
    onQuickOpen: () -> Unit,
    onCommands: () -> Unit,
    onSettings: () -> Unit,
) {
    var page by remember { mutableStateOf(initialCompactPage(state.activeDocument != null)) }
    var moreOpen by remember { mutableStateOf(false) }
    var agentSheetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.activePath) {
        if (state.activeDocument != null) page = CompactWorkbenchPage.DOCUMENT
    }
    LaunchedEffect(page) {
        if (page != CompactWorkbenchPage.DOCUMENT) agentSheetVisible = false
        page.toolWindow?.let { state.execute(WorkbenchCommand.OpenTool(it)) }
    }
    Column(Modifier.fillMaxSize()) {
        WorkbenchHeader(state, onQuickOpen, onCommands)
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                CompactWorkbenchPage.DOCUMENT -> DocumentWorkspace(state, {}, onSettings, onQuickOpen, Modifier.fillMaxSize(), compact = true)
                CompactWorkbenchPage.AGENT -> AgentPanel(state, {}, onSettings, onOpenDocument = state::openAgentDocument, modifier = Modifier.fillMaxSize())
                else -> ToolPanel(state, Modifier.fillMaxSize())
            }
            if (page == CompactWorkbenchPage.DOCUMENT && !agentSheetVisible) {
                Surface(
                    onClick = { agentSheetVisible = true },
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SmartToy, "打开 Agent 底部面板", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
            if (page == CompactWorkbenchPage.DOCUMENT && agentSheetVisible) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.56f)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                    shadowElevation = 8.dp,
                ) {
                    AgentPanel(
                        state = state,
                        onClose = { agentSheetVisible = false },
                        onSettings = onSettings,
                        onOpenDocument = { page = CompactWorkbenchPage.AGENT; agentSheetVisible = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CompactNavItem(Icons.Default.Description, "文档", page == CompactWorkbenchPage.DOCUMENT) { page = CompactWorkbenchPage.DOCUMENT }
            CompactNavItem(Icons.Default.FolderOpen, "文件", page == CompactWorkbenchPage.FILES) { page = CompactWorkbenchPage.FILES }
            CompactNavItem(Icons.Default.Search, "搜索", page == CompactWorkbenchPage.SEARCH) { page = CompactWorkbenchPage.SEARCH }
            CompactNavItem(Icons.Default.SmartToy, "Agent", page == CompactWorkbenchPage.AGENT) { page = CompactWorkbenchPage.AGENT }
            Box {
                CompactNavItem(
                    Icons.Default.MoreVert,
                    "更多",
                    page in setOf(
                        CompactWorkbenchPage.LINKS,
                        CompactWorkbenchPage.OUTLINE,
                        CompactWorkbenchPage.PROPERTIES,
                        CompactWorkbenchPage.GRAPH,
                        CompactWorkbenchPage.TASKS,
                    ),
                ) { moreOpen = true }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    listOf(
                        CompactWorkbenchPage.LINKS to "反向链接",
                        CompactWorkbenchPage.OUTLINE to "目录",
                        CompactWorkbenchPage.PROPERTIES to "属性",
                        CompactWorkbenchPage.GRAPH to "关系图谱",
                        CompactWorkbenchPage.TASKS to "任务中心",
                    ).forEach { (target, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { page = target; moreOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchHeader(
    state: WorkbenchState,
    onQuickOpen: () -> Unit,
    onCommands: () -> Unit,
) {
    var presetsOpen by remember { mutableStateOf(false) }
    var savePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(9.dp))
        Text("学习学习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("  /  ", color = MaterialTheme.colorScheme.outline)
        Text(
            state.workspaceRoot?.fileName?.toString().orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderAction(Icons.Default.Search, "快速打开", "Ctrl P", onQuickOpen)
        Spacer(Modifier.width(6.dp))
        HeaderAction(Icons.Default.KeyboardCommandKey, "命令", "Ctrl Shift P", onCommands)
        Spacer(Modifier.width(6.dp))
        Box {
            TextButton(onClick = { presetsOpen = true }) { Text("布局") }
            DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                BuiltInLayoutPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(layoutPresetLabel(preset)) },
                        leadingIcon = { Icon(if (preset == BuiltInLayoutPreset.READING) Icons.Default.AutoStories else if (preset == BuiltInLayoutPreset.WRITING) Icons.Default.Description else Icons.Default.SmartToy, null) },
                        onClick = {
                            state.applyBuiltInLayoutPreset(preset)
                            presetsOpen = false
                        },
                    )
                }
                if (state.customLayoutPresets.isNotEmpty()) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    state.customLayoutPresets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                            trailingIcon = {
                                IconButton(onClick = { state.deleteCustomLayoutPreset(preset.name) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, "删除布局 ${preset.name}", Modifier.size(15.dp))
                                }
                            },
                            onClick = { state.applyCustomLayoutPreset(preset); presetsOpen = false },
                        )
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("保存当前布局…") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = {
                        presetName = "自定义布局 ${state.customLayoutPresets.size + 1}"
                        presetsOpen = false
                        savePresetDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (state.activityRailRight) "活动栏移到左侧" else "活动栏移到右侧") },
                    onClick = { state.activityRailVisible = true; state.activityRailRight = !state.activityRailRight; state.persistLayout(); presetsOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(if (state.activityRailVisible) "隐藏活动栏" else "显示活动栏") },
                    onClick = { state.activityRailVisible = !state.activityRailVisible; state.persistLayout(); presetsOpen = false },
                )
            }
        }
    }
    if (savePresetDialog) {
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            title = { Text("保存布局预设") },
            text = { androidx.compose.material3.OutlinedTextField(presetName, { presetName = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = {
                Button(onClick = { state.saveCurrentLayoutPreset(presetName); savePresetDialog = false }, enabled = presetName.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { savePresetDialog = false }) { Text("取消") } },
        )
    }
}

private fun layoutPresetLabel(preset: BuiltInLayoutPreset): String = when (preset) {
    BuiltInLayoutPreset.READING -> "阅读"
    BuiltInLayoutPreset.WRITING -> "写作"
    BuiltInLayoutPreset.AGENT -> "Agent 协作"
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, shortcut: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(12.dp))
            Text(shortcut, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ActivityRail(active: ToolWindow, onSelect: (ToolWindow) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(48.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        RailItem(Icons.Default.FolderOpen, "文件", active == ToolWindow.FILES) { onSelect(ToolWindow.FILES) }
        RailItem(Icons.Default.Search, "搜索", active == ToolWindow.SEARCH) { onSelect(ToolWindow.SEARCH) }
        RailItem(Icons.Default.Link, "反向链接", active == ToolWindow.BACKLINKS) { onSelect(ToolWindow.BACKLINKS) }
        RailItem(Icons.Default.AccountTree, "目录", active == ToolWindow.OUTLINE) { onSelect(ToolWindow.OUTLINE) }
        RailItem(Icons.Default.Tune, "属性", active == ToolWindow.PROPERTIES) { onSelect(ToolWindow.PROPERTIES) }
        RailItem(Icons.Default.Hub, "关系图谱", active == ToolWindow.GRAPH) { onSelect(ToolWindow.GRAPH) }
        RailItem(Icons.Default.Checklist, "任务中心", active == ToolWindow.TASKS) { onSelect(ToolWindow.TASKS) }
        RailItem(Icons.Default.SmartToy, "Agent", active == ToolWindow.AGENT) { onSelect(ToolWindow.AGENT) }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Default.Settings, "设置", false, onSettings)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
        Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun CompactNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PanelResizer(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    Box(
        Modifier.width(5.dp).fillMaxHeight().pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragEnd) { change, drag -> change.consume(); onDrag(drag.x) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun HorizontalPanelResizer(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    Box(
        Modifier.fillMaxWidth().height(5.dp).pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragEnd) { change, drag -> change.consume(); onDrag(drag.y) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun StatusBar(state: WorkbenchState) {
    Row(
        Modifier.fillMaxWidth().height(25.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(5.dp))
        Text(state.status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        state.activeDocument?.let { tab ->
            Text(
                if (tab.kind == dev.xuexue.core.DocumentKind.AGENT) "Agent · ${state.activeAgentSession.name}" else tab.relativePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(Modifier.width(14.dp))
            if (tab.dirty) Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.agentRunning) {
            Spacer(Modifier.width(14.dp))
            Text("Agent 运行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun UnsavedDialog(name: String, onCancel: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("保存对 $name 的更改？") },
        text = { Text("关闭前保存修改，或放弃本次未保存内容。") },
        confirmButton = { Button(onClick = onSave) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("放弃", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        },
    )
}

private fun chooseWorkspace(create: Boolean): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = if (create) "新建工作区" else "打开工作区"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        approveButtonText = if (create) "使用此目录" else "打开"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
