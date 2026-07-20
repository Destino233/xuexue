// The outline surface keeps its small editing and menu composables close to the workflow they serve.
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming", "TooManyFunctions")

package dev.xuexue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xuexue.DocumentTab
import dev.xuexue.WorkbenchState
import dev.xuexue.platform.PdfOutlineDocument
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.summarizePdfOutline
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
internal fun PdfOutlinePanel(
    state: WorkbenchState,
    tab: DocumentTab,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    showHeader: Boolean = true,
    compact: Boolean = false,
) {
    var editingId by remember(tab.relativePath) { mutableStateOf<String?>(null) }
    var editingTitle by remember(tab.relativePath) { mutableStateOf("") }
    var exportRequest by remember(tab.relativePath) { mutableStateOf<OutlineExportRequest?>(null) }
    val outline = tab.pdfOutline
    Column(modifier) {
        if (showHeader) {
            PdfOutlinePanelTitle(outline)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        PdfOutlineToolbar(
            state = state,
            tab = tab,
            compact = compact,
            onExportRequest = { request -> exportRequest = request },
        )
        tab.pdfStaleOutline?.let { stale ->
            PdfStaleOutlineNotice(
                nodeCount = countOutlineNodes(stale.nodes),
                onRestore = { state.restoreStalePdfOutline(tab) },
            )
        }
        tab.pdfOutlineError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (outline == null || outline.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "尚未生成目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PdfOutlineList(
                state = state,
                tab = tab,
                outline = outline,
                onPage = onPage,
                editing =
                    OutlineEditingState(
                        id = editingId,
                        title = editingTitle,
                        onTitleChange = { editingTitle = it },
                        onStartEdit = { node ->
                            editingId = node.id
                            editingTitle = node.title
                        },
                        onSaveEdit = { node ->
                            state.editPdfOutlineTitle(tab, node.id, editingTitle)
                            editingId = null
                        },
                        onCancelEdit = { editingId = null },
                    ),
            )
        }
        exportRequest?.let { request ->
            PdfOutlineExportDialog(
                state = state,
                tab = tab,
                request = request,
                onDismiss = { exportRequest = null },
            )
        }
    }
}

@Composable
private fun PdfStaleOutlineNotice(
    nodeCount: Int,
    onRestore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "文件版本已变化，保留旧目录（$nodeCount 项）",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRestore) {
            Text("使用旧目录")
        }
    }
}

private data class OutlineExportRequest(
    val strategy: PdfOutlineExportStrategy,
    val confirmedOnly: Boolean,
)

@Composable
private fun PdfOutlinePanelTitle(outline: PdfOutlineDocument?) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("目录", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            outline?.nodes?.let(::countOutlineNodes)?.toString() ?: "0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun PdfOutlineToolbar(
    state: WorkbenchState,
    tab: DocumentTab,
    compact: Boolean,
    onExportRequest: (OutlineExportRequest) -> Unit,
) {
    var exportMenuOpen by remember(tab.relativePath) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PdfOutlineAnalyzeControls(state, tab, compact)
        PdfOutlineEngineMenu(state, tab)
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { state.confirmPdfOutlineHighConfidence(tab) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(Icons.Default.Check, "批量确认高置信书签", Modifier.size(17.dp))
        }
        IconButton(
            onClick = { state.addPdfOutlineNode(tab, null, "新书签", tab.pdfPage) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(Icons.Default.Add, "新增书签", Modifier.size(17.dp))
        }
        Box {
            IconButton(onClick = { exportMenuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.FileUpload, "导出书签", Modifier.size(17.dp))
            }
            DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                PdfOutlineExportMenuItem(
                    strategy = PdfOutlineExportStrategy.REPLACE,
                    title = "替换原生目录",
                    onDismiss = { exportMenuOpen = false },
                    onRequest = onExportRequest,
                )
                PdfOutlineExportMenuItem(
                    strategy = PdfOutlineExportStrategy.APPEND,
                    title = "追加到原生目录",
                    onDismiss = { exportMenuOpen = false },
                    onRequest = onExportRequest,
                )
                PdfOutlineExportMenuItem(
                    strategy = PdfOutlineExportStrategy.REPLACE,
                    title = "仅导出已确认书签",
                    confirmedOnly = true,
                    onDismiss = { exportMenuOpen = false },
                    onRequest = onExportRequest,
                )
            }
        }
    }
}

@Composable
private fun PdfOutlineAnalyzeControls(
    state: WorkbenchState,
    tab: DocumentTab,
    compact: Boolean,
) {
    if (tab.pdfOutlineAnalyzing) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (!compact) {
            Text(
                "正在识别",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { state.cancelPdfOutlineAnalysis(tab) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, "取消识别", Modifier.size(17.dp))
        }
    } else if (compact) {
        IconButton(onClick = { state.analyzePdfOutline(tab) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.AutoFixHigh, "重新识别", Modifier.size(17.dp))
        }
    } else {
        Button(
            onClick = { state.analyzePdfOutline(tab) },
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("重新识别", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PdfOutlineEngineMenu(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    var menuOpen by remember(tab.relativePath) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.MoreVert, "选择识别方案", Modifier.size(17.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            state.documentAnalysisEngineDescriptors.forEach { descriptor ->
                val availability =
                    state.documentAnalysisAvailabilities.firstOrNull { item -> item.engineId == descriptor.engineId }
                DropdownMenuItem(
                    text = {
                        Text(
                            descriptor.displayName +
                                availability
                                    ?.let { item ->
                                        " · ${item.state.name.lowercase()}${if (item.recommended) " · 推荐" else ""}"
                                    }.orEmpty(),
                        )
                    },
                    onClick = {
                        state.setPdfDocumentAnalysisEngine(tab, descriptor.engineId)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PdfOutlineExportMenuItem(
    strategy: PdfOutlineExportStrategy,
    title: String,
    confirmedOnly: Boolean = false,
    onDismiss: () -> Unit,
    onRequest: (OutlineExportRequest) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title) },
        onClick = {
            onRequest(OutlineExportRequest(strategy, confirmedOnly))
            onDismiss()
        },
    )
}

@Composable
private fun PdfOutlineExportDialog(
    state: WorkbenchState,
    tab: DocumentTab,
    request: OutlineExportRequest,
    onDismiss: () -> Unit,
) {
    val summary = summarizePdfOutline(tab.pdfOutline?.nodes.orEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导出 PDF 书签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("节点总数：${summary.total}")
                Text("已确认：${summary.confirmed} · 手动：${summary.manual}")
                Text("待审核：${summary.pending} · 已拒绝：${summary.rejected}")
                Text(
                    if (request.strategy == PdfOutlineExportStrategy.REPLACE) {
                        "策略：替换原生目录"
                    } else {
                        "策略：追加到原生目录"
                    },
                )
                if (request.confirmedOnly && summary.pending > 0) {
                    Text(
                        "待审核节点不会写入输出文件。",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    chooseExportPath(tab, request.strategy)?.let { path ->
                        if (request.confirmedOnly) {
                            state.exportConfirmedPdfOutline(tab, path, request.strategy)
                        } else {
                            state.exportPdfOutline(tab, path, request.strategy)
                        }
                        onDismiss()
                    }
                },
            ) {
                Text("选择输出路径")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PdfOutlineList(
    state: WorkbenchState,
    tab: DocumentTab,
    outline: PdfOutlineDocument,
    onPage: (Int) -> Unit,
    editing: OutlineEditingState,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 4.dp),
    ) {
        items(flattenOutline(outline.nodes), key = { item -> item.node.id }) { item ->
            if (editing.id == item.node.id) {
                PdfOutlineEditingRow(
                    depth = item.depth,
                    title = editing.title,
                    onTitleChange = editing.onTitleChange,
                    onSave = { editing.onSaveEdit(item.node) },
                    onCancel = editing.onCancelEdit,
                )
            } else {
                PdfOutlineRow(
                    state = state,
                    tab = tab,
                    node = item.node,
                    depth = item.depth,
                    onPage = onPage,
                    onEdit = { editing.onStartEdit(item.node) },
                )
            }
        }
    }
}

@Composable
private fun PdfOutlineEditingRow(
    depth: Int,
    title: String,
    onTitleChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (8 + depth * 14).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            singleLine = true,
            modifier = Modifier.weight(1f).height(38.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        IconButton(onClick = onSave, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Check, "保存标题", Modifier.size(16.dp))
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, "取消编辑", Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PdfOutlineRow(
    state: WorkbenchState,
    tab: DocumentTab,
    node: PdfOutlineNode,
    depth: Int,
    onPage: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember(node.id) { mutableStateOf(false) }
    val color =
        when (node.reviewState) {
            PdfOutlineReviewState.CONFIRMED, PdfOutlineReviewState.MANUAL -> MaterialTheme.colorScheme.onSurface
            PdfOutlineReviewState.CANDIDATE -> MaterialTheme.colorScheme.tertiary
            PdfOutlineReviewState.REJECTED -> MaterialTheme.colorScheme.outline
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                onPage(node.destination.pageIndex)
                state.setPdfPosition(
                    page = node.destination.pageIndex,
                    topNormalized = node.destination.topNormalized,
                    document = tab,
                )
            }.padding(start = (8 + depth * 14).dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PdfOutlineNodeSummary(node, color, Modifier.weight(1f))
        PdfOutlineRowActions(
            state = state,
            tab = tab,
            node = node,
            onEdit = onEdit,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
        )
    }
}

@Composable
private fun PdfOutlineNodeSummary(
    node: PdfOutlineNode,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            node.title,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "第 ${node.destination.pageIndex + 1} 页" +
                (node.confidence?.let { confidence -> " · ${(confidence * 100).toInt()}%" }.orEmpty()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        node.evidence?.let { evidence ->
            val evidenceText =
                buildList {
                    addAll(evidence.reasons.take(2))
                    evidence.matchedTocTitle?.let { title -> add("目录：$title") }
                    evidence.bounds?.let { bounds ->
                        add("识别框 ${bounds.left},${bounds.top} - ${bounds.right},${bounds.bottom}")
                    }
                }.joinToString(" · ")
            if (evidenceText.isNotBlank()) {
                Text(
                    evidenceText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PdfOutlineRowActions(
    state: WorkbenchState,
    tab: DocumentTab,
    node: PdfOutlineNode,
    onEdit: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    IconButton(onClick = onEdit, modifier = Modifier.size(25.dp)) {
        Icon(Icons.Default.Edit, "编辑标题", Modifier.size(14.dp))
    }
    IconButton(
        onClick = { state.setPdfOutlineReviewState(tab, node.id, PdfOutlineReviewState.CONFIRMED) },
        modifier = Modifier.size(25.dp),
    ) {
        Icon(Icons.Default.Check, "确认书签", Modifier.size(14.dp))
    }
    Box {
        IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(25.dp)) {
            Icon(Icons.Default.MoreVert, "书签操作", Modifier.size(15.dp))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuOpenChange(false) },
        ) {
            OutlineMoveMenuItem(state, tab, node, -1, "上移", Icons.Default.KeyboardArrowUp, onMenuOpenChange)
            OutlineMoveMenuItem(state, tab, node, 1, "下移", Icons.Default.KeyboardArrowDown, onMenuOpenChange)
            OutlineLevelMenuItem(
                state,
                tab,
                node,
                true,
                "降一级",
                Icons.AutoMirrored.Filled.FormatIndentIncrease,
                onMenuOpenChange,
            )
            DropdownMenuItem(
                text = { Text("设为当前页") },
                leadingIcon = { Icon(Icons.Default.Check, null) },
                onClick = {
                    state.setPdfOutlineDestination(tab, node.id, tab.pdfPage)
                    onMenuOpenChange(false)
                },
            )
            OutlineLevelMenuItem(
                state,
                tab,
                node,
                false,
                "升一级",
                Icons.AutoMirrored.Filled.FormatIndentDecrease,
                onMenuOpenChange,
            )
            DropdownMenuItem(
                text = { Text("驳回") },
                leadingIcon = { Icon(Icons.Default.Close, null) },
                onClick = {
                    state.setPdfOutlineReviewState(tab, node.id, PdfOutlineReviewState.REJECTED)
                    onMenuOpenChange(false)
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    state.removePdfOutlineNode(tab, node.id)
                    onMenuOpenChange(false)
                },
            )
        }
    }
}

@Composable
private fun OutlineMoveMenuItem(
    state: WorkbenchState,
    tab: DocumentTab,
    node: PdfOutlineNode,
    delta: Int,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDismiss: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, null) },
        onClick = {
            state.movePdfOutlineNode(tab, node.id, delta)
            onDismiss(false)
        },
    )
}

@Composable
private fun OutlineLevelMenuItem(
    state: WorkbenchState,
    tab: DocumentTab,
    node: PdfOutlineNode,
    indent: Boolean,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onDismiss: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title) },
        leadingIcon = { Icon(icon, null) },
        onClick = {
            state.changePdfOutlineLevel(tab, node.id, indent)
            onDismiss(false)
        },
    )
}

private data class OutlineRowItem(
    val node: PdfOutlineNode,
    val depth: Int,
)

private data class OutlineEditingState(
    val id: String?,
    val title: String,
    val onTitleChange: (String) -> Unit,
    val onStartEdit: (PdfOutlineNode) -> Unit,
    val onSaveEdit: (PdfOutlineNode) -> Unit,
    val onCancelEdit: () -> Unit,
)

private fun flattenOutline(
    nodes: List<PdfOutlineNode>,
    depth: Int = 0,
): List<OutlineRowItem> =
    nodes.flatMap { node ->
        listOf(OutlineRowItem(node, depth)) + flattenOutline(node.children, depth + 1)
    }

private fun countOutlineNodes(
    nodes: List<PdfOutlineNode>,
): Int = nodes.sumOf { node -> 1 + countOutlineNodes(node.children) }

private fun chooseExportPath(
    tab: DocumentTab,
    strategy: PdfOutlineExportStrategy,
): Path? {
    val chooser = JFileChooser()
    chooser.dialogTitle = if (strategy == PdfOutlineExportStrategy.REPLACE) "导出替换后的 PDF" else "导出追加书签的 PDF"
    chooser.selectedFile = java.io.File(tab.displayName.removeSuffix(".pdf") + "-bookmarked.pdf")
    return chooser.takeIf { it.showSaveDialog(null) == JFileChooser.APPROVE_OPTION }?.selectedFile?.toPath()
}
