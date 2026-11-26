@file:Suppress("FunctionName")

package dev.xuexue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.AgentMessage
import dev.xuexue.WorkbenchState

@Composable
internal fun AgentToolHint(
    onOpenPanel: () -> Unit,
    onOpenDocument: () -> Unit,
) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onOpenPanel) { Text("打开 Agent 面板") }
        TextButton(onClick = onOpenDocument) { Text("在文档区打开 Agent") }
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
    val messages = state.agentMessages
    val listState = rememberLazyListState()
    LaunchedEffect(state.activeAgentSessionId, messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Surface(
        modifier = modifier.onGloballyPositioned { state.agentDropBounds = it.boundsInWindow() },
        color = if (outerChrome) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            AgentHeader(state, onSettings, onFloat, onOpenDocument, onClose)
            state.permission?.let { request ->
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                ) {
                    Text("工具权限请求", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(request.summary, style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { request.decision.complete(true) }) { Text("允许一次") }
                        TextButton(onClick = { request.decision.complete(false) }) { Text("拒绝") }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { message -> AgentMessageView(message) }
            }
            AgentComposer(state)
        }
    }
}

@Composable
private fun AgentHeader(
    state: WorkbenchState,
    onSettings: () -> Unit,
    onFloat: (() -> Unit)?,
    onOpenDocument: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }, enabled = !state.agentRunning) {
                Text(
                    state.activeAgentSession.name,
                    Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ExpandMore, "切换会话", Modifier.size(16.dp))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                state.agentSessions.forEach { session ->
                    DropdownMenuItem(
                        text = { Text(session.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            state.selectAgentSession(session.id)
                        },
                    )
                }
            }
        }
        if (state.agentRunning) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            ToolIcon(Icons.Default.Add, "新建会话", state::newAgentSession)
        }
        onOpenDocument?.let { ToolIcon(Icons.Default.Description, "在文档区打开", it) }
        onFloat?.let { ToolIcon(Icons.AutoMirrored.Filled.OpenInNew, if (state.agentFloating) "停靠" else "浮动", it) }
        ToolIcon(Icons.Default.Settings, "设置", onSettings)
        ToolIcon(Icons.Default.Close, "关闭 Agent", onClose)
    }
}

@Composable
private fun AgentComposer(state: WorkbenchState) {
    val unavailable =
        when {
            state.workspaceRoot == null -> "打开工作区后即可使用 Agent"
            state.agentOfflineMode -> "Agent 处于离线模式"
            state.activeAgentSession.legacy -> "旧会话 · 只读"
            else -> null
        }
    val canSend = unavailable == null && !state.agentRunning && state.agentInput.isNotBlank()
    AgentAttachments(state)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = state.agentInput,
            onValueChange = { state.agentInput = it },
            modifier =
                Modifier.weight(1f).onPreviewKeyEvent { event ->
                    val send =
                        canSend && event.type == KeyEventType.KeyDown &&
                            event.isCtrlPressed && event.key == Key.Enter
                    if (send) state.sendAgentMessage()
                    send
                },
            enabled = unavailable == null,
            placeholder = { Text(unavailable ?: "输入消息") },
            maxLines = 5,
        )
        if (state.agentRunning) {
            ToolIcon(Icons.Default.Stop, "停止", state::stopAgent)
        } else {
            ToolIcon(Icons.AutoMirrored.Filled.Send, "发送", state::sendAgentMessage, enabled = canSend)
        }
    }
}

@Composable
private fun AgentAttachments(state: WorkbenchState) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.agentContextPaths.forEach { path ->
            ContextChip(path.substringAfterLast('/')) { state.removeAgentContext(path) }
        }
        state.agentContextSnippets.forEach { snippet ->
            ContextChip(snippet.title) { state.removeAgentSnippetContext(snippet.id) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(state.includeActiveDocument, { state.includeActiveDocument = it }, Modifier.size(32.dp))
        Text("附带当前文档", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ContextChip(
    label: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Default.Close, "移除附件", Modifier.size(14.dp)) },
    )
}

@Composable
private fun AgentMessageView(message: AgentMessage) {
    val (label, color) =
        when {
            message.error -> "错误" to MaterialTheme.colorScheme.error
            message.role == "user" -> "你" to MaterialTheme.colorScheme.primary
            message.role == "reasoning" -> "推理" to MaterialTheme.colorScheme.onSurfaceVariant
            message.role == "tool_call" -> "工具调用" to MaterialTheme.colorScheme.onSurfaceVariant
            message.role == "tool" -> "工具结果" to MaterialTheme.colorScheme.onSurfaceVariant
            message.role == "diff" -> "文件差异" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "Agent" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    var expanded by remember { mutableStateOf(false) }
    val collapsed = message.role in setOf("tool", "tool_call", "reasoning", "diff") && message.content.length > 400
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        SelectionContainer {
            Text(
                if (collapsed && !expanded) message.content.take(400) + "…" else message.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (collapsed) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "折叠" else "展开") }
    }
}

private enum class SettingsPage(
    val label: String,
) {
    APPLICATION("应用"),
    DOCUMENTS("文档"),
    AGENT("Agent"),
}

@Composable
fun AgentSettingsDialog(
    state: WorkbenchState,
    onDismiss: () -> Unit,
) {
    var section by remember { mutableStateOf(SettingsPage.AGENT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Row(Modifier.fillMaxWidth().height(410.dp)) {
                Column(Modifier.width(100.dp)) {
                    SettingsPage.entries.forEach { page ->
                        TextButton(onClick = { section = page }, enabled = section != page) { Text(page.label) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    when (section) {
                        SettingsPage.APPLICATION -> ApplicationSettingsPane(state)
                        SettingsPage.DOCUMENTS -> DocumentSettingsPane(state)
                        SettingsPage.AGENT -> AgentRuntimeSettings(state)
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
private fun AgentRuntimeSettings(state: WorkbenchState) {
    Text("DeepSeek Harness", style = MaterialTheme.typography.titleMedium)
    Text("模型、凭据与插件由 DSH 配置管理。")
    RuntimeToggle("信任工作区", state.workspaceTrusted, !state.agentRunning) { state.workspaceTrusted = it }
    RuntimeToggle("只读模式", state.agentReadOnlyMode, !state.agentRunning) { state.agentReadOnlyMode = it }
    RuntimeToggle("允许写入", state.agentAllowWrite, !state.agentRunning) { state.agentAllowWrite = it }
    RuntimeToggle("允许终端工具", state.agentAllowShell, !state.agentRunning) { state.agentAllowShell = it }
    RuntimeToggle("离线模式", state.agentOfflineMode, !state.agentRunning) { state.agentOfflineMode = it }
}

@Composable
private fun RuntimeToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}
