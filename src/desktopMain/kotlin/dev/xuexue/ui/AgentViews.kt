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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.WorkbenchState
import dev.xuexue.core.AgentApprovalDecision
import dev.xuexue.core.AgentOperationKind
import dev.xuexue.core.AgentSendAvailability
import dev.xuexue.core.DocumentKind

private enum class AgentSettingsSection(
    val label: String,
    val icon: ImageVector,
) {
    DOCUMENTS("文档", Icons.Default.PictureAsPdf),
    MODEL("模型与 Provider", Icons.Default.SmartToy),
    PERMISSIONS("工具与权限", Icons.Default.Tune),
    RESOURCES("资源", Icons.Default.Description),
    EXTENSIONS("扩展与包", Icons.Default.Hub),
    DIAGNOSTICS("诊断", Icons.Default.ErrorOutline),
}

@Composable
internal fun AgentToolHint(
    onOpenPanel: () -> Unit,
    onOpenDocument: () -> Unit,
) {
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
    outerChrome: Boolean = false,
) {
    val piState by state.piAgentState.collectAsState()
    val isAgentRunning = piState.isStreaming
    var panelMenuOpen by remember { mutableStateOf(false) }
    var sessionMenuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    val messageListState = rememberLazyListState()
    val messages = state.agentMessages
    LaunchedEffect(state.activeAgentSessionId, messages.size, messages.lastOrNull()?.content, isAgentRunning) {
        if (messages.isNotEmpty()) {
            messageListState.scrollToItem(messages.lastIndex)
        }
    }
    Box(modifier.onGloballyPositioned { state.agentDropBounds = it.boundsInRoot() }) {
        Surface(
            Modifier.fillMaxSize(),
            color = if (outerChrome) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(43.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Agent", fontWeight = FontWeight.SemiBold)
                        Text(if (state.agentModel.isBlank()) "尚未配置模型" else state.agentModel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        ToolIcon(Icons.Default.MoreVert, "Agent 面板选项", { panelMenuOpen = true })
                        androidx.compose.material3.DropdownMenu(expanded = panelMenuOpen, onDismissRequest = { panelMenuOpen = false }) {
                            onOpenDocument?.let { openDocument ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("在文档区打开") },
                                    leadingIcon = { Icon(Icons.Default.Description, null) },
                                    onClick = {
                                        openDocument()
                                        panelMenuOpen = false
                                    },
                                )
                            }
                            onFloat?.let { toggleFloat ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (state.agentFloating) "停靠 Agent" else "转为浮动窗口") },
                                    leadingIcon = { Icon(if (state.agentFloating) Icons.Default.CloseFullscreen else Icons.AutoMirrored.Filled.OpenInNew, null) },
                                    onClick = {
                                        toggleFloat()
                                        panelMenuOpen = false
                                    },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Agent 设置") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = {
                                    onSettings()
                                    panelMenuOpen = false
                                },
                            )
                        }
                    }
                    ToolIcon(Icons.Default.Close, "关闭 Agent", onClose)
                }
                if (!outerChrome) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().height(37.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                        state.agentSessions.forEach { session ->
                            val active = session.id == state.activeAgentSessionId
                            val interactionSource = remember(session.id) { MutableInteractionSource() }
                            val hovered by interactionSource.collectIsHoveredAsState()
                            val pressed by interactionSource.collectIsPressedAsState()
                            val containerColor by
                                animateColorAsState(
                                    targetValue =
                                        when {
                                            active -> MaterialTheme.colorScheme.surface
                                            hovered -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                                            else -> Color.Transparent
                                        },
                                    animationSpec = WorkbenchMotion.fastTween(),
                                    label = "agent session color",
                                )
                            val scale by
                                animateFloatAsState(
                                    targetValue = if (pressed) 0.97f else 1f,
                                    animationSpec = WorkbenchMotion.expressiveSpring(),
                                    label = "agent session scale",
                                )
                            Box(
                                Modifier
                                    .width(120.dp)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }.background(containerColor)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                    ) {
                                        state.selectAgentSession(session.id)
                                    },
                            ) {
                                Row(
                                    Modifier.fillMaxSize().padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = session.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (active && isAgentRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(11.dp),
                                            strokeWidth = 1.5.dp,
                                        )
                                    }
                                }
                                MotionVisibility(
                                    visible = active,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    enter =
                                        expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) +
                                            fadeIn(),
                                    exit =
                                        shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) +
                                            fadeOut(),
                                ) {
                                    Box(
                                        Modifier
                                            .width(120.dp)
                                            .height(2.dp)
                                            .background(MaterialTheme.colorScheme.tertiary),
                                    )
                                }
                            }
                        }
                    }
                    ToolIcon(Icons.Default.Add, "新建会话", state::newAgentSession)
                    Box {
                        ToolIcon(Icons.Default.MoreVert, "会话操作", { sessionMenuOpen = true })
                        androidx.compose.material3.DropdownMenu(expanded = sessionMenuOpen, onDismissRequest = { sessionMenuOpen = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                renameInput = state.activeAgentSession.name
                                renameDialog = true
                                sessionMenuOpen = false
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("分叉会话") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) }, onClick = {
                                state.cloneAgentSession(fork = true)
                                sessionMenuOpen = false
                            })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("克隆会话") }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = {
                                state.cloneAgentSession()
                                sessionMenuOpen = false
                            })
                            androidx.compose.material3.HorizontalDivider()
                            androidx.compose.material3.DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = {
                                state.deleteAgentSession(state.activeAgentSessionId)
                                sessionMenuOpen = false
                            })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(27.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val session = state.activeAgentSession
                    val reportedTokens = session.inputTokens + session.outputTokens
                    val estimatedTokens = state.agentMessages.sumOf { it.content.length }.div(4)
                    Text(
                        if (reportedTokens > 0) "用量 $reportedTokens tokens" else "上下文 ≈ $estimatedTokens tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(session.costUsd?.let { "费用 \$${"%.4f".format(it)}" } ?: "费用 --", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                MotionVisibility(
                    visible = state.canRestoreDeletedAgentSession,
                    enter =
                        expandVertically(animationSpec = WorkbenchMotion.expressiveSpring()) +
                            fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        shrinkVertically(animationSpec = WorkbenchMotion.standardTween()) +
                            fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("会话已删除", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = state::restoreDeletedAgentSession) {
                            Icon(Icons.Default.Restore, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("恢复")
                        }
                    }
                }
                AnimatedContent(
                    targetState = state.pendingAgentApproval,
                    transitionSpec = {
                        val enter =
                            expandVertically(animationSpec = WorkbenchMotion.expressiveSpring()) +
                                fadeIn(WorkbenchMotion.fastTween())
                        val exit =
                            shrinkVertically(animationSpec = WorkbenchMotion.standardTween()) +
                                fadeOut(WorkbenchMotion.exitTween())
                        enter togetherWith exit
                    },
                    label = "agent approval",
                ) { request ->
                    if (request != null) {
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
                }
                MotionVisibility(
                    visible =
                        state.agentSendAvailability in
                            setOf(
                                AgentSendAvailability.OFFLINE,
                                AgentSendAvailability.MODEL_REQUIRED,
                                AgentSendAvailability.API_KEY_REQUIRED,
                            ),
                    enter =
                        expandVertically(animationSpec = WorkbenchMotion.expressiveSpring()) +
                            fadeIn(WorkbenchMotion.fastTween()),
                    exit =
                        shrinkVertically(animationSpec = WorkbenchMotion.standardTween()) +
                            fadeOut(WorkbenchMotion.exitTween()),
                ) {
                    AgentAvailabilityBanner(state.agentSendAvailability, onSettings)
                }
                AnimatedContent(
                    targetState = state.activeAgentSessionId to messages.isEmpty(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        fadeIn(WorkbenchMotion.standardTween()) togetherWith fadeOut(WorkbenchMotion.exitTween())
                    },
                    label = "agent messages",
                ) { (_, empty) ->
                    if (empty) {
                        Column(
                            Modifier.fillMaxSize().padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.SmartToy, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.height(12.dp))
                            Text("围绕当前文档开始对话", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("可附带当前文档内容；运行时可随时停止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            state = messageListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                androidx.compose.foundation.layout
                                    .PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(
                                messages,
                                key = { index, message -> "$index:${message.role}" },
                            ) { index, message ->
                                AgentMessageBubble(
                                    role = message.role,
                                    content = message.content,
                                    error = message.error,
                                    decision = message.decision,
                                    onDecision = { state.setAgentDiffDecision(index, it) },
                                )
                            }
                            item {
                                MotionVisibility(
                                    visible = isAgentRunning,
                                    enter =
                                        expandVertically(animationSpec = WorkbenchMotion.expressiveSpring()) +
                                            fadeIn(WorkbenchMotion.fastTween()),
                                    exit =
                                        shrinkVertically(animationSpec = WorkbenchMotion.standardTween()) +
                                            fadeOut(WorkbenchMotion.exitTween()),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("正在生成…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.includeActiveDocument, onCheckedChange = { state.includeActiveDocument = it }, modifier = Modifier.size(28.dp))
                        Icon(Icons.Default.AttachFile, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (state.includeActiveDocument && state.activeDocument != null) {
                                val document = state.activeDocument!!
                                if (document.kind == DocumentKind.PDF) "${document.displayName} · 第 ${document.pdfPage + 1} 页" else document.displayName
                            } else {
                                "不附带当前文档"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.agentContextPaths.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            state.agentContextPaths.forEach { path ->
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(4.dp),
                                ) {
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
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                    shape =
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(4.dp),
                                ) {
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
                        modifier =
                            Modifier.weight(1f).onPreviewKeyEvent {
                                val isSendShortcut =
                                    it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed
                                if (isSendShortcut && canSend) {
                                    state.sendAgentMessage()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                    Spacer(Modifier.width(6.dp))
                    MotionIconButton(
                        onClick = state::sendAgentMessage,
                        enabled = canSend,
                        selected = canSend,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, if (isAgentRunning) "发送 Steering" else "发送")
                    }
                    AnimatedVisibility(
                        visible = isAgentRunning,
                        enter = expandHorizontally(animationSpec = WorkbenchMotion.expressiveSpring()) + fadeIn(),
                        exit = shrinkHorizontally(animationSpec = WorkbenchMotion.standardTween()) + fadeOut(),
                    ) {
                        MotionIconButton(
                            onClick = state::stopAgent,
                            modifier = Modifier.size(38.dp),
                            danger = true,
                        ) {
                            Icon(Icons.Default.Stop, "停止")
                        }
                    }
                }
            }
        }
        AnimatedContent(
            targetState = state.dragPayload,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val enter =
                    scaleIn(initialScale = 0.985f, animationSpec = WorkbenchMotion.expressiveSpring()) +
                        fadeIn(WorkbenchMotion.fastTween())
                val exit =
                    scaleOut(targetScale = 0.99f, animationSpec = WorkbenchMotion.exitTween()) +
                        fadeOut(WorkbenchMotion.exitTween())
                enter togetherWith exit
            },
            label = "agent drop overlay",
        ) { payload ->
            if (payload != null) {
                AgentDropOverlay(state, payload.displayName)
            }
        }
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名会话") },
            text = { OutlinedTextField(renameInput, { renameInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(onClick = {
                    state.renameAgentSession(state.activeAgentSessionId, renameInput)
                    renameDialog = false
                }, enabled = renameInput.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AgentDropOverlay(
    state: WorkbenchState,
    displayName: String,
) {
    val active = state.agentDropBounds?.contains(state.dragPosition) == true
    val overlayColor by
        animateColorAsState(
            targetValue = MaterialTheme.colorScheme.tertiary.copy(alpha = if (active) 0.16f else 0.06f),
            animationSpec = WorkbenchMotion.fastTween(),
            label = "agent drop overlay color",
        )
    Box(
        Modifier.fillMaxSize().background(overlayColor),
        contentAlignment = Alignment.Center,
    ) {
        MotionDropTarget(
            active = active,
            accent = MaterialTheme.colorScheme.tertiary,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(7.dp))
                Text("释放以添加 $displayName", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AgentAvailabilityBanner(
    availability: AgentSendAvailability,
    onSettings: () -> Unit,
) {
    val (title, detail) =
        when (availability) {
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
    var expanded by remember(role) { mutableStateOf(!collapsible) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color =
                when {
                    error -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    user -> MaterialTheme.colorScheme.primaryContainer
                    role == "diff" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
            shape =
                androidx.compose.foundation.shape
                    .RoundedCornerShape(6.dp),
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
                                color =
                                    when {
                                        line.startsWith("+") -> MaterialTheme.colorScheme.primary
                                        line.startsWith("-") -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier =
                                    Modifier.fillMaxWidth().background(
                                        when {
                                            line.startsWith("+") -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            line.startsWith("-") -> MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
                                            else -> Color.Transparent
                                        },
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    if (decision == null) {
                        Row {
                            TextButton(onClick = { onDecision("accepted") }) { Text("接受") }
                            TextButton(onClick = { onDecision("rejected") }) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (decision == "accepted") "已接受" else "已拒绝", style = MaterialTheme.typography.labelSmall, color = if (decision == "accepted") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onDecision(null) }) { Text("撤销") }
                        }
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

private fun agentOperationLabel(operation: AgentOperationKind): String =
    when (operation) {
        AgentOperationKind.READ_FILE -> "Agent 请求读取文件"
        AgentOperationKind.WRITE_FILE -> "Agent 请求修改文件"
        AgentOperationKind.RUN_SHELL -> "Agent 请求执行命令"
        AgentOperationKind.OUTSIDE_WORKSPACE -> "Agent 请求访问工作区外部"
    }

@Composable
fun AgentSettingsDialog(
    state: WorkbenchState,
    onDismiss: () -> Unit,
) {
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
                VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 18.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(section.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    when (section) {
                        AgentSettingsSection.DOCUMENTS -> {
                            DocumentSettingsPane(state)
                        }

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
                            ToggleSetting("信任当前工作区", "允许 Runtime 请求工作区内的工具操作", state.workspaceTrusted) {
                                state.workspaceTrusted = it
                                state.persistLayout()
                            }
                            ToggleSetting("只读模式", "阻止写文件与修改类工具", state.agentReadOnlyMode) {
                                state.agentReadOnlyMode = it
                                state.persistLayout()
                            }
                            ToggleSetting("离线模式", "禁用模型与资源的网络访问", state.agentOfflineMode) {
                                state.agentOfflineMode = it
                                state.persistLayout()
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            ToggleSetting("文件写入", "write 与 edit", state.agentAllowWrite, enabled = !state.agentReadOnlyMode) {
                                state.agentAllowWrite = it
                                state.persistLayout()
                            }
                            ToggleSetting("Shell 命令", "bash 与平台命令执行", state.agentAllowShell, enabled = state.workspaceTrusted && !state.agentReadOnlyMode) {
                                state.agentAllowShell = it
                                state.persistLayout()
                            }
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
                            SettingsStatusRow("Headless Runtime", "Pi Agent Core", "已接入")
                            SettingsStatusRow("会话存储", ".xuexue/agent-sessions.jsonl", "${state.agentSessions.size} 个会话")
                            SettingsStatusRow("工作区索引", "Markdown 链接与搜索缓存", "就绪")
                            SettingsStatusRow("缓存统计", "模型、PDF 与资源缓存", "--")
                            SettingsStatusRow("日志与遥测", "诊断、更新检查与可选遥测", "关闭")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                state.persistLayout()
                state.persistDocumentPreferences()
                onDismiss()
            }) { Text("完成") }
        },
    )
}

@Composable
private fun SettingsSectionItem(
    section: AgentSettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
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
private fun ToggleSetting(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SettingsStatusRow(
    title: String,
    detail: String,
    status: String,
) {
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}
