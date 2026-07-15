@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.xuexue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.xuexue.BuiltInLayoutPreset
import dev.xuexue.DocumentTab
import dev.xuexue.ToolPanelDock
import dev.xuexue.WorkbenchState
import dev.xuexue.core.CompactWorkbenchPage
import dev.xuexue.core.ToolWindow
import dev.xuexue.core.WorkbenchAuxiliaryPanel
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.core.WorkbenchFocusTarget
import dev.xuexue.core.WorkbenchWidthClass
import dev.xuexue.core.initialCompactPage
import dev.xuexue.core.mediumAuxiliaryPanel
import dev.xuexue.core.workbenchWidthClass
import kotlinx.coroutines.launch
import java.nio.file.Path
import javax.swing.JFileChooser

private enum class Overlay { QUICK_OPEN, COMMANDS, SETTINGS }

typealias WindowTitleBar = @Composable (modifier: Modifier, content: @Composable () -> Unit) -> Unit

class WindowChrome(
    val titleBar: WindowTitleBar,
    val maximized: Boolean,
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun WorkbenchApp(
    state: WorkbenchState,
    closeRequested: Boolean,
    onCancelClose: () -> Unit,
    onConfirmClose: () -> Unit,
    windowChrome: WindowChrome,
) {
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var pendingClose by remember { mutableStateOf<DocumentTab?>(null) }
    LaunchedEffect(state) {
        while (true) {
            state.pdfPerformanceMetrics.frames.recordFrame(withFrameNanos { frameTime -> frameTime })
        }
    }

    XueXueTheme {
        val windowBorder =
            if (windowChrome.maximized) {
                Modifier
            } else {
                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            }
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(windowBorder)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                        when {
                            event.isAltPressed && event.key == Key.F -> {
                                state.execute(WorkbenchCommand.OpenTool(ToolWindow.FILES))
                            }

                            event.isAltPressed && event.key == Key.S -> {
                                state.execute(WorkbenchCommand.OpenTool(ToolWindow.SEARCH))
                            }

                            event.isAltPressed && event.key == Key.L -> {
                                state.execute(WorkbenchCommand.OpenTool(ToolWindow.BACKLINKS))
                            }

                            event.isAltPressed && event.key == Key.A -> {
                                state.execute(WorkbenchCommand.ShowAgent)
                            }

                            event.isAltPressed && event.key == Key.R -> {
                                state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.ACTIVITY_RAIL))
                            }

                            event.isAltPressed && event.key == Key.T -> {
                                state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.TOOL_WINDOW))
                            }

                            event.isAltPressed && event.key == Key.D -> {
                                state.execute(WorkbenchCommand.Focus(WorkbenchFocusTarget.DOCUMENT))
                            }

                            event.key == Key.P && event.isShiftPressed -> {
                                overlay = Overlay.COMMANDS
                            }

                            event.key == Key.P -> {
                                overlay = Overlay.QUICK_OPEN
                            }

                            event.key == Key.Tab -> {
                                val command =
                                    if (event.isShiftPressed) {
                                        WorkbenchCommand.PreviousDocument
                                    } else {
                                        WorkbenchCommand.NextDocument
                                    }
                                state.execute(command)
                            }

                            event.key == Key.S -> {
                                state.execute(WorkbenchCommand.SaveDocument)
                            }

                            event.key == Key.Z && event.isShiftPressed -> {
                                state.execute(WorkbenchCommand.Redo)
                            }

                            event.key == Key.Z -> {
                                state.execute(WorkbenchCommand.Undo)
                            }

                            event.key == Key.Y -> {
                                state.execute(WorkbenchCommand.Redo)
                            }

                            event.key == Key.W -> {
                                state.activeDocument?.let { document ->
                                    if (!state.closeTab(document)) pendingClose = document
                                }
                            }

                            else -> {
                                return@onPreviewKeyEvent false
                            }
                        }
                        true
                    },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                WorkbenchHeader(
                    state = state,
                    onQuickOpen = { overlay = Overlay.QUICK_OPEN },
                    onCommands = { overlay = Overlay.COMMANDS },
                    windowChrome = windowChrome,
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val widthClass = workbenchWidthClass(maxWidth.value)
                    when {
                        state.workspaceRoot == null -> {
                            WelcomeScreen(
                                loading = state.isLoading,
                                onCreate = { chooseWorkspace(create = true)?.let { path -> scope.launch { state.openWorkspace(path) } } },
                                onOpen = { chooseWorkspace(create = false)?.let { path -> scope.launch { state.openWorkspace(path) } } },
                            )
                        }

                        widthClass == WorkbenchWidthClass.COMPACT -> {
                            CompactWorkbench(
                                state = state,
                                onQuickOpen = { overlay = Overlay.QUICK_OPEN },
                                onSettings = { overlay = Overlay.SETTINGS },
                            )
                        }

                        else -> {
                            DesktopWorkbench(
                                state = state,
                                onQuickOpen = { overlay = Overlay.QUICK_OPEN },
                                onSettings = { overlay = Overlay.SETTINGS },
                                onCloseTab = { tab -> if (!state.closeTab(tab)) pendingClose = tab },
                                medium = widthClass == WorkbenchWidthClass.MEDIUM,
                            )
                        }
                    }
                }
            }
        }

        if (state.workspaceRoot != null && state.leftPanelVisible && state.toolPanelDock == ToolPanelDock.FLOAT) {
            Window(
                onCloseRequest = {
                    state.leftPanelVisible = false
                    state.persistLayout()
                },
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

        overlay?.let { visibleOverlay ->
            when (visibleOverlay) {
                Overlay.QUICK_OPEN -> {
                    QuickOpenDialog(state, onDismiss = { overlay = null })
                }

                Overlay.COMMANDS -> {
                    CommandPaletteDialog(
                        state = state,
                        onDismiss = { overlay = null },
                        onSettings = { overlay = Overlay.SETTINGS },
                    )
                }

                Overlay.SETTINGS -> {
                    AgentSettingsDialog(state, onDismiss = { overlay = null })
                }
            }
        }
        pendingClose?.let { tab ->
            UnsavedDialog(
                name = tab.displayName,
                onCancel = { pendingClose = null },
                onDiscard = {
                    state.closeTab(tab, force = true)
                    pendingClose = null
                },
                onSave = {
                    scope.launch {
                        state.selectTab(tab.relativePath)
                        state.save(tab).join()
                        if (tab.saveError == null) {
                            state.closeTab(tab, force = true)
                            pendingClose = null
                        }
                    }
                },
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
            WorkbenchFocusTarget.ACTIVITY_RAIL -> {
                if (state.activityRailVisible) activityFocus.requestFocus()
            }

            WorkbenchFocusTarget.TOOL_WINDOW -> {
                if (medium && state.agentPanelVisible) {
                    state.agentPanelVisible = false
                } else if (showToolPanel && state.toolPanelDock != ToolPanelDock.FLOAT) {
                    toolFocus.requestFocus()
                }
            }

            WorkbenchFocusTarget.DOCUMENT -> {
                documentFocus.requestFocus()
            }

            WorkbenchFocusTarget.AGENT -> {
                if (state.agentPanelVisible && !state.agentFloating) agentFocus.requestFocus()
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { state.workbenchBounds = it.boundsInRoot() },
    ) {
        val horizontalToolVisible =
            showToolPanel && state.toolPanelDock in setOf(ToolPanelDock.LEFT, ToolPanelDock.RIGHT)
        val dockedAgentVisible = state.agentPanelVisible && !state.agentFloating
        val auxiliaryBudget = (maxWidth - 360.dp).coerceAtLeast(0.dp)
        val requestedToolWidth = (if (state.toolPanelExpanded) 480f else state.leftPanelWidth).dp
        val toolPanelWidth =
            if (horizontalToolVisible && dockedAgentVisible) {
                minOf(requestedToolWidth, auxiliaryBudget * 0.45f)
            } else {
                minOf(requestedToolWidth, auxiliaryBudget)
            }
        val agentPanelWidth =
            if (dockedAgentVisible && horizontalToolVisible) {
                minOf(state.agentPanelWidth.dp, (auxiliaryBudget - toolPanelWidth).coerceAtLeast(0.dp))
            } else {
                minOf(state.agentPanelWidth.dp, auxiliaryBudget)
            }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val railSelect: (ToolWindow) -> Unit = { tool ->
                    if (tool == ToolWindow.AGENT) {
                        state.agentPanelVisible = !state.agentPanelVisible
                    } else {
                        if (medium) state.agentPanelVisible = false
                        state.openToolWindow(tool)
                    }
                    state.persistLayout()
                }
                MotionVisibility(
                    visible = state.activityRailVisible && !state.activityRailRight,
                    enter =
                        expandHorizontally(
                            expandFrom = Alignment.Start,
                            animationSpec = WorkbenchMotion.standardTween(),
                        ) + fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        shrinkHorizontally(
                            shrinkTowards = Alignment.Start,
                            animationSpec = WorkbenchMotion.standardTween(),
                        ) + fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    ActivityRail(
                        active = state.activeTool,
                        agentSelected = state.agentPanelVisible,
                        onSelect = railSelect,
                        onSettings = onSettings,
                        modifier = Modifier.focusRequester(activityFocus).focusable(),
                    )
                }
                MotionVisibility(
                    visible = showToolPanel && state.toolPanelDock == ToolPanelDock.LEFT,
                    enter =
                        expandHorizontally(
                            expandFrom = Alignment.Start,
                            animationSpec = WorkbenchMotion.standardTween(),
                        ) + fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        shrinkHorizontally(
                            shrinkTowards = Alignment.Start,
                            animationSpec = WorkbenchMotion.standardTween(),
                        ) + fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    Row {
                        ToolPanel(
                            state,
                            Modifier
                                .width(toolPanelWidth)
                                .fillMaxHeight()
                                .focusRequester(toolFocus)
                                .focusable(),
                            outerChrome = true,
                        )
                        PanelResizer(
                            onDrag = { delta ->
                                state.leftPanelWidth = (state.leftPanelWidth + delta).coerceIn(210f, 480f)
                            },
                            onDragEnd = state::persistLayout,
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Surface(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(4.dp)
                                    .focusRequester(documentFocus)
                                    .focusable(),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(7.dp),
                            shadowElevation = 1.dp,
                        ) {
                            DocumentWorkspace(state, onCloseTab, onSettings, onQuickOpen, Modifier.fillMaxSize())
                        }
                        AnimatedVisibility(
                            visible = showToolPanel && state.toolPanelDock == ToolPanelDock.RIGHT,
                            enter =
                                expandHorizontally(
                                    expandFrom = Alignment.End,
                                    animationSpec = WorkbenchMotion.standardTween(),
                                ) + fadeIn(WorkbenchMotion.fastTween()),
                            exit =
                                shrinkHorizontally(
                                    shrinkTowards = Alignment.End,
                                    animationSpec = WorkbenchMotion.standardTween(),
                                ) + fadeOut(WorkbenchMotion.exitTween()),
                        ) {
                            Row {
                                PanelResizer(
                                    onDrag = { delta ->
                                        state.leftPanelWidth = (state.leftPanelWidth - delta).coerceIn(210f, 480f)
                                    },
                                    onDragEnd = state::persistLayout,
                                )
                                ToolPanel(
                                    state,
                                    Modifier
                                        .width(toolPanelWidth)
                                        .fillMaxHeight()
                                        .focusRequester(toolFocus)
                                        .focusable(),
                                    outerChrome = true,
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = state.agentPanelVisible && !state.agentFloating,
                            enter =
                                expandHorizontally(
                                    expandFrom = Alignment.End,
                                    animationSpec = WorkbenchMotion.standardTween(),
                                ) + fadeIn(WorkbenchMotion.fastTween()),
                            exit =
                                shrinkHorizontally(
                                    shrinkTowards = Alignment.End,
                                    animationSpec = WorkbenchMotion.standardTween(),
                                ) + fadeOut(WorkbenchMotion.exitTween()),
                        ) {
                            Row {
                                PanelResizer(
                                    onDrag = { delta ->
                                        state.agentPanelWidth = (state.agentPanelWidth - delta).coerceIn(300f, 540f)
                                    },
                                    onDragEnd = state::persistLayout,
                                )
                                AgentPanel(
                                    state = state,
                                    onClose = state::closeAgentPanel,
                                    onSettings = onSettings,
                                    onFloat = state::toggleAgentFloating,
                                    onOpenDocument = state::openAgentDocument,
                                    modifier =
                                        Modifier
                                            .width(agentPanelWidth)
                                            .fillMaxHeight()
                                            .focusRequester(agentFocus)
                                            .focusable(),
                                    outerChrome = true,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = showToolPanel && state.toolPanelDock == ToolPanelDock.BOTTOM,
                        enter =
                            expandVertically(
                                expandFrom = Alignment.Bottom,
                                animationSpec = WorkbenchMotion.expressiveSpring(),
                            ) + fadeIn(WorkbenchMotion.fastTween()),
                        exit =
                            shrinkVertically(
                                shrinkTowards = Alignment.Bottom,
                                animationSpec = WorkbenchMotion.standardTween(),
                            ) + fadeOut(WorkbenchMotion.exitTween()),
                    ) {
                        Column {
                            HorizontalPanelResizer(
                                onDrag = { delta ->
                                    state.bottomPanelHeight = (state.bottomPanelHeight - delta).coerceIn(150f, 420f)
                                },
                                onDragEnd = state::persistLayout,
                            )
                            ToolPanel(
                                state,
                                Modifier
                                    .fillMaxWidth()
                                    .requiredHeight((if (state.toolPanelExpanded) 420f else state.bottomPanelHeight).dp)
                                    .focusRequester(toolFocus)
                                    .focusable(),
                                outerChrome = true,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = state.activityRailVisible && state.activityRailRight,
                    enter =
                        expandHorizontally(
                            expandFrom = Alignment.End,
                            animationSpec = WorkbenchMotion.expressiveSpring(),
                        ) + fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        shrinkHorizontally(
                            shrinkTowards = Alignment.End,
                            animationSpec = WorkbenchMotion.standardTween(),
                        ) + fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    ActivityRail(
                        active = state.activeTool,
                        agentSelected = state.agentPanelVisible,
                        onSelect = railSelect,
                        onSettings = onSettings,
                        modifier = Modifier.focusRequester(activityFocus).focusable(),
                    )
                }
            }
            StatusBar(state)
        }
        MotionVisibility(
            visible = state.toolDockDragging,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(WorkbenchMotion.fastTween()),
            exit = fadeOut(WorkbenchMotion.exitTween()),
        ) {
            ToolDockPreviewOverlay(state.toolDockPreview)
        }
    }
}

@Composable
private fun ToolDockPreviewOverlay(active: ToolPanelDock?) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)),
    ) {
        val sideWidth = maxWidth * 0.24f
        val bottomHeight = maxHeight * 0.28f
        DockPreviewTarget(
            "停靠左侧",
            active == ToolPanelDock.LEFT,
            Modifier.align(Alignment.CenterStart).width(sideWidth).fillMaxHeight(),
        )
        DockPreviewTarget(
            "停靠右侧",
            active == ToolPanelDock.RIGHT,
            Modifier.align(Alignment.CenterEnd).width(sideWidth).fillMaxHeight(),
        )
        DockPreviewTarget(
            "停靠底部",
            active == ToolPanelDock.BOTTOM,
            Modifier.align(Alignment.BottomCenter).width(maxWidth - sideWidth * 2).height(bottomHeight),
        )
        DockPreviewTarget(
            "浮动窗口",
            active == ToolPanelDock.FLOAT,
            Modifier.align(Alignment.TopCenter).width(maxWidth - sideWidth * 2).height(maxHeight - bottomHeight),
        )
    }
}

@Composable
private fun DockPreviewTarget(
    label: String,
    active: Boolean,
    modifier: Modifier,
) {
    MotionDropTarget(
        active = active,
        accent = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CompactWorkbench(
    state: WorkbenchState,
    onQuickOpen: () -> Unit,
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
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(7.dp),
            shadowElevation = 1.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val enter =
                            fadeIn(WorkbenchMotion.standardTween()) +
                                slideInHorizontally(WorkbenchMotion.standardTween()) { it / 14 }
                        val exit =
                            fadeOut(WorkbenchMotion.exitTween()) +
                                slideOutHorizontally(WorkbenchMotion.exitTween()) { -it / 18 }
                        enter togetherWith exit
                    },
                    label = "compact workbench page",
                ) { activePage ->
                    when (activePage) {
                        CompactWorkbenchPage.DOCUMENT -> {
                            DocumentWorkspace(
                                state,
                                { tab -> state.closeTab(tab) },
                                onSettings,
                                onQuickOpen,
                                Modifier.fillMaxSize(),
                                compact = true,
                            )
                        }

                        CompactWorkbenchPage.AGENT -> {
                            AgentPanel(
                                state,
                                state::closeAgentPanel,
                                onSettings,
                                onOpenDocument = state::openAgentDocument,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        else -> {
                            ToolPanel(state, Modifier.fillMaxSize())
                        }
                    }
                }
                MotionVisibility(
                    visible = page == CompactWorkbenchPage.DOCUMENT && !agentSheetVisible,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = scaleIn(WorkbenchMotion.expressiveSpring()) + fadeIn(WorkbenchMotion.fastTween()),
                    exit = scaleOut(WorkbenchMotion.exitTween()) + fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    Surface(
                        onClick = { agentSheetVisible = true },
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.SmartToy, "打开 Agent 底部面板", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                MotionVisibility(
                    visible = page == CompactWorkbenchPage.DOCUMENT && agentSheetVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter =
                        slideInVertically(WorkbenchMotion.expressiveSpring()) { it } +
                            fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        slideOutVertically(WorkbenchMotion.standardTween()) { it } +
                            fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.56f)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                        shadowElevation = 8.dp,
                    ) {
                        AgentPanel(
                            state = state,
                            onClose = { agentSheetVisible = false },
                            onSettings = onSettings,
                            onOpenDocument = {
                                page = CompactWorkbenchPage.AGENT
                                agentSheetVisible = false
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CompactNavItem(Icons.Default.Description, "文档", page == CompactWorkbenchPage.DOCUMENT) { page = CompactWorkbenchPage.DOCUMENT }
            CompactNavItem(Icons.Default.FolderOpen, "文件", page == CompactWorkbenchPage.FILES) { page = CompactWorkbenchPage.FILES }
            CompactNavItem(Icons.Default.Search, "搜索", page == CompactWorkbenchPage.SEARCH) { page = CompactWorkbenchPage.SEARCH }
            CompactNavItem(Icons.Default.SmartToy, "Agent", page == CompactWorkbenchPage.AGENT) { page = CompactWorkbenchPage.AGENT }
            Box {
                CompactNavItem(
                    Icons.Default.MoreVert,
                    "更多",
                    page in
                        setOf(
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
                            onClick = {
                                page = target
                                moreOpen = false
                            },
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
    windowChrome: WindowChrome,
) {
    var presetsOpen by remember { mutableStateOf(false) }
    var savePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    windowChrome.titleBar(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoStories,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("学习学习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.workspaceRoot?.let { workspaceRoot ->
                    Text(
                        "  /  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        workspaceRoot.fileName.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.workspaceRoot != null) {
                HeaderAction(Icons.Default.Search, "快速打开", "Ctrl P", onQuickOpen)
                Spacer(Modifier.width(4.dp))
                HeaderIconAction(Icons.Default.KeyboardCommandKey, "命令面板  Ctrl Shift P", onCommands)
                Box {
                    HeaderIconAction(Icons.Default.Tune, "布局与界面", { presetsOpen = true })
                    DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                        BuiltInLayoutPreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(layoutPresetLabel(preset)) },
                                leadingIcon = {
                                    Icon(
                                        if (preset == BuiltInLayoutPreset.READING) {
                                            Icons.Default.AutoStories
                                        } else if (preset == BuiltInLayoutPreset.WRITING) {
                                            Icons.Default.Description
                                        } else {
                                            Icons.Default.SmartToy
                                        },
                                        null,
                                    )
                                },
                                onClick = {
                                    state.applyBuiltInLayoutPreset(preset)
                                    presetsOpen = false
                                },
                            )
                        }
                        if (state.customLayoutPresets.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            state.customLayoutPresets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Default.Tune, null) },
                                    trailingIcon = {
                                        IconButton(onClick = { state.deleteCustomLayoutPreset(preset.name) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Close, "删除布局 ${preset.name}", Modifier.size(15.dp))
                                        }
                                    },
                                    onClick = {
                                        state.applyCustomLayoutPreset(preset)
                                        presetsOpen = false
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                            onClick = {
                                state.activityRailVisible = true
                                state.activityRailRight = !state.activityRailRight
                                state.persistLayout()
                                presetsOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.activityRailVisible) "隐藏活动栏" else "显示活动栏") },
                            onClick = {
                                state.activityRailVisible = !state.activityRailVisible
                                state.persistLayout()
                                presetsOpen = false
                            },
                        )
                    }
                }
            }
            WindowControlButton(Icons.Default.Minimize, "最小化", windowChrome.onMinimize)
            WindowControlButton(
                icon = if (windowChrome.maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                description = if (windowChrome.maximized) "还原" else "最大化",
                onClick = windowChrome.onToggleMaximize,
            )
            WindowControlButton(Icons.Default.Close, "关闭", windowChrome.onClose, danger = true)
        }
    }
    if (savePresetDialog) {
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            title = { Text("保存布局预设") },
            text = { androidx.compose.material3.OutlinedTextField(presetName, { presetName = it }, singleLine = true, label = { Text("名称") }) },
            confirmButton = {
                Button(onClick = {
                    state.saveCurrentLayoutPreset(presetName)
                    savePresetDialog = false
                }, enabled = presetName.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { savePresetDialog = false }) { Text("取消") } },
        )
    }
}

private fun layoutPresetLabel(preset: BuiltInLayoutPreset): String =
    when (preset) {
        BuiltInLayoutPreset.READING -> "阅读"
        BuiltInLayoutPreset.WRITING -> "写作"
        BuiltInLayoutPreset.AGENT -> "Agent 协作"
    }

@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    shortcut: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) 0.985f else 1f,
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "header action scale",
        )
    val containerColor by
        animateColorAsState(
            targetValue =
                if (hovered) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "header action color",
        )
    Row(
        Modifier
            .height(30.dp)
            .width(150.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }.background(containerColor, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(shortcut, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun HeaderIconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        MotionIconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(icon, description, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WindowControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        MotionIconButton(
            onClick = onClick,
            modifier = Modifier.size(width = 40.dp, height = 44.dp),
            danger = danger,
        ) {
            Icon(icon, description, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActivityRail(
    active: ToolWindow,
    agentSelected: Boolean,
    onSelect: (ToolWindow) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(52.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(7.dp))
        RailItem(Icons.Default.FolderOpen, "文件", active == ToolWindow.FILES) { onSelect(ToolWindow.FILES) }
        RailItem(Icons.Default.Search, "搜索", active == ToolWindow.SEARCH) { onSelect(ToolWindow.SEARCH) }
        Spacer(Modifier.height(6.dp))
        RailItem(Icons.Default.Link, "反向链接", active == ToolWindow.BACKLINKS) { onSelect(ToolWindow.BACKLINKS) }
        RailItem(Icons.Default.AccountTree, "目录", active == ToolWindow.OUTLINE) { onSelect(ToolWindow.OUTLINE) }
        RailItem(Icons.Default.Tune, "属性", active == ToolWindow.PROPERTIES) { onSelect(ToolWindow.PROPERTIES) }
        RailItem(Icons.Default.Hub, "关系图谱", active == ToolWindow.GRAPH) { onSelect(ToolWindow.GRAPH) }
        RailItem(Icons.Default.Checklist, "任务中心", active == ToolWindow.TASKS) { onSelect(ToolWindow.TASKS) }
        Spacer(Modifier.height(6.dp))
        RailItem(Icons.Default.SmartToy, "Agent", agentSelected) { onSelect(ToolWindow.AGENT) }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Default.Settings, "设置", false, onSettings)
        Spacer(Modifier.height(7.dp))
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = rememberRailMotion(selected, hovered, pressed)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .padding(4.dp)
                .background(motion.containerColor, RoundedCornerShape(5.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = selected,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = scaleIn(WorkbenchMotion.expressiveSpring()) + fadeIn(WorkbenchMotion.fastTween()),
                exit = scaleOut(WorkbenchMotion.exitTween()) + fadeOut(WorkbenchMotion.exitTween()),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
            Icon(
                icon,
                contentDescription = label,
                tint = motion.contentColor,
                modifier =
                    Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = motion.scale
                            scaleY = motion.scale
                        },
            )
        }
    }
}

private data class RailMotion(
    val containerColor: Color,
    val contentColor: Color,
    val scale: Float,
)

@Composable
private fun rememberRailMotion(
    selected: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): RailMotion {
    val containerColor by
        animateColorAsState(
            targetValue =
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "rail item color",
        )
    val contentColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "rail icon color",
        )
    val scale by
        animateFloatAsState(
            targetValue =
                when {
                    pressed -> 0.88f
                    hovered || selected -> 1.06f
                    else -> 1f
                },
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "rail icon scale",
        )
    return RailMotion(containerColor, contentColor, scale)
}

@Composable
private fun CompactNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            animationSpec = WorkbenchMotion.fastTween(),
            label = "compact navigation color",
        )
    val scale by
        animateFloatAsState(
            targetValue =
                if (pressed) {
                    0.9f
                } else if (selected) {
                    1.05f
                } else {
                    1f
                },
            animationSpec = WorkbenchMotion.expressiveSpring(),
            label = "compact navigation scale",
        )
    Box(
        Modifier
            .width(72.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) + fadeIn(),
            exit = shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) + fadeOut(),
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(24.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
            )
        }
        Column(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, label, tint = contentColor, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

@Composable
private fun PanelResizer(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Spacer(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .background(if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                    change.consume()
                    onDrag(drag.x)
                }
            },
    )
}

@Composable
private fun HorizontalPanelResizer(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onDragEnd) { change, drag ->
                    change.consume()
                    onDrag(drag.y)
                }
            },
    )
}

@Composable
private fun StatusBar(state: WorkbenchState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusActivity(state.isLoading)
        Spacer(Modifier.width(5.dp))
        StatusMessage(state.status, Modifier.weight(1f))
        StatusDocument(state)
        StatusFlags(state.activeDocument?.dirty == true, state.agentRunning)
    }
}

@Composable
private fun StatusActivity(loading: Boolean) {
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            fadeIn(WorkbenchMotion.fastTween()) togetherWith fadeOut(WorkbenchMotion.exitTween())
        },
        label = "status activity",
    ) { active ->
        if (active) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                Icons.Default.Check,
                null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusMessage(
    status: String,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = status,
        modifier = modifier,
        transitionSpec = {
            val enter =
                fadeIn(WorkbenchMotion.fastTween()) +
                    slideInVertically(WorkbenchMotion.fastTween()) { it / 2 }
            val exit =
                fadeOut(WorkbenchMotion.exitTween()) +
                    slideOutVertically(WorkbenchMotion.exitTween()) { -it / 2 }
            enter togetherWith exit
        },
        label = "status message",
    ) { message ->
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDocument(state: WorkbenchState) {
    AnimatedContent(
        targetState = state.activeDocument?.relativePath,
        transitionSpec = {
            fadeIn(WorkbenchMotion.fastTween()) togetherWith fadeOut(WorkbenchMotion.exitTween())
        },
        label = "status document",
    ) { relativePath ->
        val tab = state.documents.firstOrNull { it.relativePath == relativePath }
        if (tab != null) {
            Text(
                if (tab.kind == dev.xuexue.core.DocumentKind.AGENT) {
                    "Agent · ${state.activeAgentSession.name}"
                } else {
                    tab.relativePath
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}

@Composable
private fun StatusFlags(
    dirty: Boolean,
    agentRunning: Boolean,
) {
    MotionVisibility(
        visible = dirty,
        enter = expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) + fadeIn(),
        exit = shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) + fadeOut(),
    ) {
        Row {
            Spacer(Modifier.width(14.dp))
            Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
    MotionVisibility(
        visible = agentRunning,
        enter = expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) + fadeIn(),
        exit = shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) + fadeOut(),
    ) {
        Row {
            Spacer(Modifier.width(14.dp))
            Text(
                "Agent 运行中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun UnsavedDialog(
    name: String,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
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
    val chooser =
        JFileChooser().apply {
            dialogTitle = if (create) "新建工作区" else "打开工作区"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            approveButtonText = if (create) "使用此目录" else "打开"
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
