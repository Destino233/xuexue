@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.xuexue.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.WidthFull
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.LooksTwo
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xuexue.DocumentTab
import dev.xuexue.DocumentDropTarget
import dev.xuexue.DocumentSplit
import dev.xuexue.WorkbenchState
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.EditorCommand
import dev.xuexue.core.EditorMode
import dev.xuexue.core.MarkdownBlock
import dev.xuexue.core.MarkdownCompletion
import dev.xuexue.core.MarkdownCompletionKind
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.WorkbenchCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayOutputStream
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.imageio.ImageIO
import javax.swing.JFileChooser

private enum class PdfSidePanel { NONE, THUMBNAILS, ANNOTATIONS }
private enum class PdfViewMode { CONTINUOUS, SINGLE, DOUBLE }
private enum class PdfFitMode { MANUAL, WIDTH, HEIGHT }

@Composable
fun DocumentWorkspace(
    state: WorkbenchState,
    onCloseTab: (DocumentTab) -> Unit,
    onSettings: () -> Unit = {},
    onQuickOpen: () -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Box(modifier.onGloballyPositioned { state.documentDropBounds = it.boundsInRoot() }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.documents.isNotEmpty()) {
                DocumentTabs(state, onCloseTab, compact)
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val document = state.activeDocument
            if (document == null) EmptyDocumentArea(state, onQuickOpen)
            else if (compact) DocumentContent(state, document, onSettings, Modifier.fillMaxSize(), compact = true)
            else when (state.documentSplit) {
                DocumentSplit.NONE -> DocumentContent(state, document, onSettings, Modifier.fillMaxSize())
                DocumentSplit.VERTICAL -> Row(Modifier.fillMaxSize()) {
                    DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxHeight())
                    Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SecondaryDocumentPane(state, onSettings, Modifier.weight(1f).fillMaxHeight())
                }
                DocumentSplit.HORIZONTAL -> Column(Modifier.fillMaxSize()) {
                    DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxWidth())
                    Divider(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SecondaryDocumentPane(state, onSettings, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        if (state.dragPayload != null) {
            val active = state.documentDropBounds?.contains(state.dragPosition) == true
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = if (active) 0.13f else 0.05f)).border(if (active) 2.dp else 1.dp, MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)) {
                    Text("释放以插入 [[${state.dragPayload!!.displayName}]]", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }
        if (state.documentTabDragPath != null) DocumentTabDockPreview(state.documentTabDropTarget)
    }
}

@Composable
private fun DocumentTabDockPreview(active: DocumentDropTarget?) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f))) {
        DocumentDropPreviewTarget("主文档区", active == DocumentDropTarget.PRIMARY, Modifier.align(Alignment.Center).width(250.dp).height(112.dp))
        DocumentDropPreviewTarget("右侧分栏", active == DocumentDropTarget.RIGHT, Modifier.align(Alignment.CenterEnd).width(150.dp).fillMaxHeight(0.72f))
        DocumentDropPreviewTarget("下方分栏", active == DocumentDropTarget.BOTTOM, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.58f).height(108.dp))
        DocumentDropPreviewTarget("拖到窗口外以独立打开", active == DocumentDropTarget.DETACH, Modifier.align(Alignment.TopCenter).width(236.dp).height(54.dp))
    }
}

@Composable
private fun DocumentDropPreviewTarget(label: String, active: Boolean, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = if (active) 0.2f else 0.07f))
            .border(if (active) 2.dp else 1.dp, MaterialTheme.colorScheme.secondary, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun DocumentContent(state: WorkbenchState, document: DocumentTab, onSettings: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier) {
        if (document.externalDeleted) ExternalDeletedBanner(state, document)
        else if (document.externalContent != null) ExternalChangeBanner(state, document)
        else if (document.saveError != null) SaveFailureBanner(state, document)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (document.kind) {
                DocumentKind.MARKDOWN -> MarkdownDocument(state, document, compact)
                DocumentKind.TEXT -> TextDocument(state, document)
                DocumentKind.PDF -> PdfDocument(state, document, compact)
                DocumentKind.IMAGE -> ImageDocument(state, document)
                DocumentKind.AGENT -> AgentPanel(
                    state = state,
                    onClose = { state.closeTab(document, force = true) },
                    onSettings = onSettings,
                    modifier = Modifier.fillMaxSize(),
                )
                DocumentKind.UNSUPPORTED -> UnsupportedDocument(state, document)
            }
        }
    }
}

@Composable
private fun SaveFailureBanner(state: WorkbenchState, document: DocumentTab) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.error.copy(alpha = 0.09f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("保存失败：${document.saveError}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { state.retrySave(document) }) { Text("重试") }
        TextButton(onClick = {
            val root = state.workspaceRoot ?: return@TextButton
            val chooser = JFileChooser(root.toFile()).apply {
                dialogTitle = "另存为"
                selectedFile = root.resolve(document.displayName).toFile()
                fileSelectionMode = JFileChooser.FILES_ONLY
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) state.saveAs(document, chooser.selectedFile.toPath())
        }) { Text("另存为") }
    }
}

@Composable
fun DetachedDocumentView(state: WorkbenchState, document: DocumentTab, onSettings: () -> Unit, onAttach: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(documentIcon(document.kind), null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(7.dp))
            Text(document.relativePath, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onAttach) { Icon(Icons.Default.CloseFullscreen, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("停靠回主窗口") }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(25.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (document.dirty) "未保存" else "已保存", style = MaterialTheme.typography.labelSmall, color = if (document.dirty) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(document.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExternalDeletedBanner(state: WorkbenchState, document: DocumentTab) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.09f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("文件已从磁盘删除。当前编辑内容仍保留在内存中。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = { state.restoreDeletedDocument(document) }) { Text("恢复文件") }
        TextButton(onClick = { state.closeTab(document, force = true) }) { Text("关闭标签") }
    }
}

@Composable
private fun ExternalChangeBanner(state: WorkbenchState, document: DocumentTab) {
    var showDiff by remember(document) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.error.copy(alpha = 0.09f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("文件已在外部修改，本地也有未保存更改。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { showDiff = true }) { Text("查看差异") }
        TextButton(onClick = { state.keepLocalVersion(document) }) { Text("保留本地") }
        TextButton(onClick = { state.acceptExternalVersion(document) }) { Text("接受外部") }
        TextButton(onClick = { state.mergeExternalVersion(document) }) { Text("合并") }
    }
    if (showDiff) {
        AlertDialog(
            onDismissRequest = { showDiff = false },
            title = { Text("外部修改差异") },
            text = {
                Row(Modifier.fillMaxWidth().height(430.dp)) {
                    DiffVersionPane("本地版本", document.content, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DiffVersionPane("外部版本", document.externalContent.orEmpty(), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
            },
            confirmButton = { Button(onClick = { state.mergeExternalVersion(document); showDiff = false }) { Text("插入冲突标记") } },
            dismissButton = { TextButton(onClick = { showDiff = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DiffVersionPane(title: String, content: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent, modifier = Modifier.padding(9.dp))
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(content, fontFamily = CodeFont, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp))
    }
}

@Composable
private fun SecondaryDocumentPane(state: WorkbenchState, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val document = state.documents.firstOrNull { it.relativePath == state.secondaryPath }
        ?: state.documents.firstOrNull { it.relativePath != state.activePath }
        ?: state.activeDocument
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("辅助分栏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(document?.displayName ?: "选择文档", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.documents.forEach { tab ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(tab.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(documentIcon(tab.kind), null, Modifier.size(16.dp)) },
                            onClick = { state.setSecondaryDocument(tab.relativePath); menuOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { state.execute(WorkbenchCommand.ClearDocumentSplit) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "关闭分栏", Modifier.size(15.dp)) }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        if (document == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有可显示的文档", color = MaterialTheme.colorScheme.outline) }
        else DocumentContent(state, document, onSettings, Modifier.fillMaxSize())
    }
}

@Composable
private fun DocumentTabs(state: WorkbenchState, onCloseTab: (DocumentTab) -> Unit, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(37.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::navigateBack, enabled = state.canNavigateBack, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowBack, "返回", Modifier.size(17.dp)) }
        if (!compact) IconButton(onClick = state::navigateForward, enabled = state.canNavigateForward, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ArrowForward, "前进", Modifier.size(17.dp)) }
        Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            state.documents.forEach { tab ->
                val selected = state.activePath == tab.relativePath
                var tabCoordinates by remember(tab.relativePath) { mutableStateOf<LayoutCoordinates?>(null) }
                var menuOpen by remember(tab.relativePath) { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.height(37.dp).width(190.dp)
                            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { state.execute(WorkbenchCommand.SelectDocument(tab.relativePath)) }
                            .onPointerEvent(PointerEventType.Press) { event -> if (event.buttons.isSecondaryPressed) menuOpen = true }
                            .onGloballyPositioned { tabCoordinates = it }
                            .pointerInput(tab.relativePath) {
                                detectDragGestures(
                                    onDragStart = { local -> tabCoordinates?.localToRoot(local)?.let { state.beginDocumentTabDrag(tab.relativePath, it) } },
                                    onDragEnd = state::finishDocumentTabDrag,
                                    onDragCancel = state::cancelDocumentTabDrag,
                                ) { change, _ ->
                                    change.consume()
                                    tabCoordinates?.localToRoot(change.position)?.let(state::updateDocumentTabDrag)
                                }
                            }
                            .padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(documentIcon(tab.kind), null, Modifier.size(16.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(7.dp))
                        Text(tab.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (tab.dirty) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.secondary, androidx.compose.foundation.shape.CircleShape))
                        else if (!tab.pinned) IconButton(onClick = { state.execute(WorkbenchCommand.PinDocument(tab.relativePath)) }, modifier = Modifier.size(25.dp)) { Icon(Icons.Default.PushPin, "固定预览", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline) }
                        IconButton(onClick = { onCloseTab(tab) }, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.Close, "关闭", Modifier.size(15.dp)) }
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!tab.pinned) androidx.compose.material3.DropdownMenuItem(
                            text = { Text("固定标签") },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) },
                            onClick = { state.execute(WorkbenchCommand.PinDocument(tab.relativePath)); menuOpen = false },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("向右分栏") },
                            leadingIcon = { Icon(Icons.Default.ViewColumn, null) },
                            onClick = { state.execute(WorkbenchCommand.SplitDocument(tab.relativePath, DocumentSplitDirection.RIGHT)); menuOpen = false },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("向下分栏") },
                            leadingIcon = { Icon(Icons.Default.ViewStream, null) },
                            onClick = { state.execute(WorkbenchCommand.SplitDocument(tab.relativePath, DocumentSplitDirection.BOTTOM)); menuOpen = false },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("在独立窗口打开") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                            onClick = { state.execute(WorkbenchCommand.DetachDocument(tab.relativePath)); menuOpen = false },
                        )
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = { onCloseTab(tab); menuOpen = false },
                        )
                    }
                }
                Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (!compact) {
            Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onClick = { state.activePath?.let { state.execute(WorkbenchCommand.DetachDocument(it)) } }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.OpenInNew, "在独立窗口打开", Modifier.size(17.dp)) }
            IconButton(onClick = {
                if (state.documentSplit == DocumentSplit.VERTICAL) state.execute(WorkbenchCommand.ClearDocumentSplit)
                else state.activePath?.let { state.execute(WorkbenchCommand.SplitDocument(it, DocumentSplitDirection.RIGHT)) }
            }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ViewColumn, "垂直分栏", Modifier.size(17.dp), tint = if (state.documentSplit == DocumentSplit.VERTICAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {
                if (state.documentSplit == DocumentSplit.HORIZONTAL) state.execute(WorkbenchCommand.ClearDocumentSplit)
                else state.activePath?.let { state.execute(WorkbenchCommand.SplitDocument(it, DocumentSplitDirection.BOTTOM)) }
            }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.ViewStream, "水平分栏", Modifier.size(17.dp), tint = if (state.documentSplit == DocumentSplit.HORIZONTAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EmptyDocumentArea(state: WorkbenchState, onQuickOpen: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text("工作区已就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("从最近文件继续，或创建一篇新笔记。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::createMarkdown) { Icon(Icons.Default.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("新建笔记") }
                TextButton(onClick = onQuickOpen) { Icon(Icons.Default.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("快速打开") }
            }
            if (state.recentDocuments.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("最近文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                state.recentDocuments.take(5).forEach { path ->
                    Row(
                        Modifier.widthIn(max = 460.dp).fillMaxWidth().height(34.dp).clickable { state.navigateTo(path) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(7.dp))
                        Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownDocument(state: WorkbenchState, tab: DocumentTab, compact: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        if (compact) CompactMarkdownModeBar(state, tab) else MarkdownToolbar(state, tab)
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab.mode) {
                EditorMode.EDIT -> StructuredMarkdownEditor(state, tab)
                EditorMode.SOURCE -> MarkdownEditor(state, tab, sourceMode = true)
                EditorMode.READ -> MarkdownReader(state, tab)
            }
        }
        if (compact && tab.mode != EditorMode.READ) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            CompactMarkdownAccessory(state, tab)
        }
    }
}

@Composable
private fun CompactMarkdownModeBar(state: WorkbenchState, tab: DocumentTab) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSelector(tab.mode) { state.setEditorMode(it, tab) }
        Spacer(Modifier.weight(1f))
        when {
            tab.saveError != null -> Text("保存失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            tab.dirty -> Text("保存中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        EditorTool(Icons.Default.Save, "保存", { state.execute(WorkbenchCommand.SaveDocument, tab) })
    }
}

@Composable
private fun CompactMarkdownAccessory(state: WorkbenchState, tab: DocumentTab) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorTool(Icons.Default.Undo, "撤销", { state.execute(WorkbenchCommand.Undo, tab) })
        EditorTool(Icons.Default.Redo, "重做", { state.execute(WorkbenchCommand.Redo, tab) })
        Divider(Modifier.height(22.dp).width(1.dp).padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
        listOf(
            Icons.Default.FormatBold to EditorCommand.BOLD,
            Icons.Default.FormatItalic to EditorCommand.ITALIC,
            Icons.Default.InsertLink to EditorCommand.LINK,
            Icons.Default.FormatListBulleted to EditorCommand.BULLET_LIST,
            Icons.Default.FormatListNumbered to EditorCommand.NUMBERED_LIST,
            Icons.Default.FormatQuote to EditorCommand.QUOTE,
            Icons.Default.Functions to EditorCommand.MATH_BLOCK,
        ).forEach { (icon, command) ->
            EditorTool(icon, editorCommandLabel(command), { state.execute(WorkbenchCommand.EditMarkdown(command), tab) })
        }
    }
}

private fun editorCommandLabel(command: EditorCommand): String = when (command) {
    EditorCommand.BOLD -> "粗体"
    EditorCommand.ITALIC -> "斜体"
    EditorCommand.INLINE_CODE -> "行内代码"
    EditorCommand.LINK -> "链接"
    EditorCommand.HEADING_1 -> "一级标题"
    EditorCommand.HEADING_2 -> "二级标题"
    EditorCommand.BULLET_LIST -> "无序列表"
    EditorCommand.NUMBERED_LIST -> "有序列表"
    EditorCommand.QUOTE -> "引用"
    EditorCommand.CODE_BLOCK -> "代码块"
    EditorCommand.MATH_BLOCK -> "公式块"
}

@Composable
private fun MarkdownToolbar(state: WorkbenchState, tab: DocumentTab) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSelector(tab.mode) { state.setEditorMode(it, tab) }
        Divider(Modifier.height(22.dp).width(1.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (tab.mode != EditorMode.READ) {
            EditorTool(Icons.Default.Undo, "撤销", { state.execute(WorkbenchCommand.Undo, tab) })
            EditorTool(Icons.Default.Redo, "重做", { state.execute(WorkbenchCommand.Redo, tab) })
            val node = if (tab.mode == EditorMode.SOURCE) "source" else tab.activeNodeType
            if (node !in setOf("code", "math", "frontmatter", "table")) {
                EditorTool(Icons.Default.FormatBold, "粗体", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.BOLD), tab) })
                EditorTool(Icons.Default.FormatItalic, "斜体", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.ITALIC), tab) })
                EditorTool(Icons.Default.Code, "行内代码", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.INLINE_CODE), tab) })
                EditorTool(Icons.Default.InsertLink, "链接", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.LINK), tab) })
            }
            if (node in setOf("source", "paragraph", "heading")) {
                EditorTool(Icons.Default.LooksOne, "一级标题", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.HEADING_1), tab) })
                EditorTool(Icons.Default.LooksTwo, "二级标题", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.HEADING_2), tab) })
            }
            if (node in setOf("source", "paragraph", "list")) {
                EditorTool(Icons.Default.FormatListBulleted, "无序列表", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.BULLET_LIST), tab) })
                EditorTool(Icons.Default.FormatListNumbered, "有序列表", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.NUMBERED_LIST), tab) })
            }
            if (node in setOf("source", "paragraph", "quote", "callout")) EditorTool(Icons.Default.FormatQuote, "引用", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.QUOTE), tab) })
            if (node in setOf("source", "paragraph", "math")) EditorTool(Icons.Default.Functions, "公式块", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.MATH_BLOCK), tab) })
        }
        Spacer(Modifier.weight(1f))
        when {
            tab.saveError != null -> Text("保存失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            tab.dirty -> Text("保存中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        EditorTool(Icons.Default.Save, "保存", { state.execute(WorkbenchCommand.SaveDocument, tab) })
    }
}

@Composable
private fun ModeSelector(active: EditorMode, onSelect: (EditorMode) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
        Row {
            listOf(EditorMode.EDIT to "编辑", EditorMode.SOURCE to "源码", EditorMode.READ to "阅读").forEach { (mode, label) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (mode == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(if (mode == active) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .clickable { onSelect(mode) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private data class MarkdownSourceLine(val start: Int, val end: Int, val text: String)

private data class MarkdownEditNode(
    val start: Int,
    val end: Int,
    val type: String,
    val source: String,
    val prefix: String = "",
    val suffix: String = "",
) {
    val body: String get() = source.removePrefix(prefix).removeSuffix(suffix)
    fun rebuild(body: String): String = prefix + body + suffix
}

@Composable
private fun StructuredMarkdownEditor(state: WorkbenchState, tab: DocumentTab) {
    val nodes = remember(tab.content) { parseMarkdownEditNodes(tab.content) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = tab.editorBlockIndex.coerceIn(0, (nodes.lastIndex).coerceAtLeast(0)))
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { tab.editorBlockIndex = it }
    }
    if (nodes.isEmpty()) {
        StructuredNodeEditor(state, tab, MarkdownEditNode(0, 0, "paragraph", ""), active = true)
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(nodes, key = { "${it.start}:${it.type}" }) { node ->
            StructuredNodeEditor(state, tab, node, active = tab.cursor.start in node.start..node.end)
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun StructuredNodeEditor(state: WorkbenchState, tab: DocumentTab, node: MarkdownEditNode, active: Boolean) {
    val initialSelectionStart = (tab.cursor.start - node.start - node.prefix.length).coerceIn(0, node.body.length)
    val initialSelectionEnd = (tab.cursor.end - node.start - node.prefix.length).coerceIn(initialSelectionStart, node.body.length)
    var value by remember(tab, node.start, node.type) { mutableStateOf(TextFieldValue(node.body, TextRange(initialSelectionStart, initialSelectionEnd))) }
    LaunchedEffect(node.body) {
        if (value.text != node.body) value = TextFieldValue(node.body, value.selection.coerceIn(node.body.length))
    }
    val accent = nodeAccent(node.type)
    val completions = if (active) state.markdownCompletions(tab) else emptyList()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(76.dp).padding(top = 9.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(nodeIcon(node.type), null, Modifier.size(14.dp), tint = accent)
                Spacer(Modifier.width(4.dp))
                Text(nodeLabel(node.type), style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            color = nodeBackground(node.type),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            modifier = Modifier.weight(1f).border(if (active) 1.dp else 0.dp, if (active) accent.copy(alpha = 0.55f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (node.type == "code" || node.type == "mermaid") {
                    Text(node.prefix.trim().removePrefix("```" ).ifBlank { if (node.type == "mermaid") "mermaid" else "code" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(5.dp))
                } else if (node.type == "callout") {
                    Text(node.prefix.lineSequence().firstOrNull()?.removePrefix("> ").orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(4.dp))
                }
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        value = next
                        val replacement = node.rebuild(next.text)
                        val updated = tab.content.replaceRange(node.start, node.end, replacement)
                        val globalStart = node.start + node.prefix.length + next.selection.start
                        val globalEnd = node.start + node.prefix.length + next.selection.end
                        state.updateContent(tab, updated, TextRange(globalStart, globalEnd))
                    },
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { focus -> if (focus.isFocused) tab.activeNodeType = node.type }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && completions.isNotEmpty() && event.key in setOf(Key.Tab, Key.Enter)) {
                                state.applyMarkdownCompletion(tab, completions.first())
                                true
                            } else false
                        },
                    textStyle = nodeTextStyle(node.type),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                )
                if (completions.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    MarkdownCompletionPopup(completions, onSelect = { state.applyMarkdownCompletion(tab, it) })
                }
            }
        }
    }
}

private fun parseMarkdownEditNodes(source: String): List<MarkdownEditNode> {
    if (source.isEmpty()) return listOf(MarkdownEditNode(0, 0, "paragraph", ""))
    val lines = sourceLines(source)
    val nodes = mutableListOf<MarkdownEditNode>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.text.trimStart()
        if (trimmed.isBlank()) { index++; continue }

        if (index == 0 && trimmed == "---") {
            val endIndex = ((index + 1) until lines.size).firstOrNull { lines[it].text.trim() == "---" } ?: index
            nodes += delimitedNode(source, lines, index, endIndex, "frontmatter")
            index = endIndex + 1
            continue
        }
        if (trimmed.startsWith("```")) {
            val endIndex = ((index + 1) until lines.size).firstOrNull { lines[it].text.trimStart().startsWith("```") } ?: lines.lastIndex
            val type = if (trimmed.removePrefix("```").trim().equals("mermaid", true)) "mermaid" else "code"
            nodes += delimitedNode(source, lines, index, endIndex, type)
            index = endIndex + 1
            continue
        }
        if (trimmed == "$$") {
            val endIndex = ((index + 1) until lines.size).firstOrNull { lines[it].text.trim() == "$$" } ?: lines.lastIndex
            nodes += delimitedNode(source, lines, index, endIndex, "math")
            index = endIndex + 1
            continue
        }

        val type = markdownLineType(trimmed, lines, index)
        val endIndex = when (type) {
            "heading", "divider" -> index
            else -> {
                var end = index
                while (end + 1 < lines.size && lines[end + 1].text.isNotBlank() && markdownLineType(lines[end + 1].text.trimStart(), lines, end + 1) == type) end++
                end
            }
        }
        val start = line.start
        val end = lines[endIndex].end
        val raw = source.substring(start, end)
        nodes += when (type) {
            "heading" -> {
                val marker = trimmed.takeWhile { it == '#' } + " "
                val leading = line.text.substringBefore(trimmed) + marker
                val suffix = lineEnding(raw)
                MarkdownEditNode(start, end, type, raw, leading, suffix)
            }
            "callout" -> {
                val prefix = source.substring(start, line.end)
                MarkdownEditNode(start, end, type, raw, prefix, "")
            }
            else -> MarkdownEditNode(start, end, type, raw)
        }
        index = endIndex + 1
    }
    return nodes
}

private fun sourceLines(source: String): List<MarkdownSourceLine> {
    val lines = mutableListOf<MarkdownSourceLine>()
    var start = 0
    while (start < source.length) {
        val newline = source.indexOf('\n', start)
        val end = if (newline < 0) source.length else newline + 1
        val text = source.substring(start, end).removeSuffix("\n").removeSuffix("\r")
        lines += MarkdownSourceLine(start, end, text)
        start = end
    }
    return lines
}

private fun delimitedNode(source: String, lines: List<MarkdownSourceLine>, startIndex: Int, endIndex: Int, type: String): MarkdownEditNode {
    val start = lines[startIndex].start
    val end = lines[endIndex].end
    val prefix = source.substring(start, lines[startIndex].end)
    val suffix = if (endIndex > startIndex) source.substring(lines[endIndex].start, end) else ""
    return MarkdownEditNode(start, end, type, source.substring(start, end), prefix, suffix)
}

private fun markdownLineType(trimmed: String, lines: List<MarkdownSourceLine>, index: Int): String = when {
    Regex("^#{1,6}\\s+").containsMatchIn(trimmed) -> "heading"
    trimmed.startsWith("> [!") -> "callout"
    trimmed.startsWith(">") -> "quote"
    Regex("^(?:[-*+] |\\d+[.)] )").containsMatchIn(trimmed) -> "list"
    trimmed in setOf("---", "***", "___") -> "divider"
    trimmed.contains('|') && (index + 1 < lines.size && lines[index + 1].text.contains('|')) -> "table"
    trimmed.startsWith(":::") || trimmed.startsWith("<") -> "extension"
    else -> "paragraph"
}

private fun lineEnding(source: String): String = when {
    source.endsWith("\r\n") -> "\r\n"
    source.endsWith('\n') -> "\n"
    else -> ""
}

@Composable
private fun nodeTextStyle(type: String): TextStyle = when (type) {
    "heading" -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    "code", "mermaid", "frontmatter", "table", "math", "extension" -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = CodeFont)
    "quote", "callout" -> TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 23.sp)
    else -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 25.sp)
}

@Composable
private fun nodeBackground(type: String): Color = when (type) {
    "code", "mermaid", "frontmatter", "table", "extension" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    "math" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    "quote", "callout" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else -> Color.Transparent
}

@Composable
private fun nodeAccent(type: String): Color = when (type) {
    "code", "mermaid", "extension" -> MaterialTheme.colorScheme.tertiary
    "math", "table" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun nodeLabel(type: String): String = when (type) {
    "heading" -> "标题"
    "list" -> "列表"
    "quote" -> "引用"
    "callout" -> "Callout"
    "code" -> "代码"
    "mermaid" -> "Mermaid"
    "math" -> "公式"
    "table" -> "表格"
    "frontmatter" -> "属性"
    "divider" -> "分隔线"
    "extension" -> "扩展"
    else -> "段落"
}

private fun nodeIcon(type: String): ImageVector = when (type) {
    "heading" -> Icons.Default.LooksOne
    "list" -> Icons.Default.FormatListBulleted
    "quote" -> Icons.Default.FormatQuote
    "callout" -> Icons.Default.Campaign
    "code", "mermaid", "extension" -> Icons.Default.Code
    "math" -> Icons.Default.Functions
    "table" -> Icons.Default.TableChart
    "frontmatter" -> Icons.Default.Tune
    "divider" -> Icons.Default.HorizontalRule
    else -> Icons.Default.Description
}

@Composable
private fun MarkdownEditor(state: WorkbenchState, tab: DocumentTab, sourceMode: Boolean) {
    var value by remember(tab) { mutableStateOf(TextFieldValue(tab.content, tab.cursor)) }
    LaunchedEffect(tab.content, tab.cursor) {
        if (value.text != tab.content) value = TextFieldValue(tab.content, tab.cursor.coerceIn(tab.content.length))
    }
    Column(Modifier.fillMaxSize()) {
        if (!sourceMode) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("块编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text("段落 · 双链 · 属性 · 块引用 · 扩展节点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        val completions = state.markdownCompletions(tab, value.selection.end)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            BasicTextField(
                value = value,
                onValueChange = { next -> value = next; state.updateContent(tab, next.text, next.selection) },
                modifier = Modifier.fillMaxSize().padding(horizontal = if (sourceMode) 20.dp else 48.dp, vertical = 24.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && completions.isNotEmpty() && event.key in setOf(Key.Tab, Key.Enter)) {
                        state.applyMarkdownCompletion(tab, completions.first())
                        true
                    } else false
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (sourceMode) 13.sp else 15.sp,
                    lineHeight = if (sourceMode) 21.sp else 25.sp,
                    fontFamily = if (sourceMode) CodeFont else FontFamily.Default,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            )
            if (completions.isNotEmpty()) {
                MarkdownCompletionPopup(
                    completions,
                    onSelect = { state.applyMarkdownCompletion(tab, it) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownCompletionPopup(
    completions: List<MarkdownCompletion>,
    onSelect: (MarkdownCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        modifier = modifier.widthIn(min = 240.dp, max = 430.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
    ) {
        Column {
            completions.take(6).forEach { completion ->
                Row(
                    Modifier.fillMaxWidth().height(36.dp).clickable { onSelect(completion) }.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(completionIcon(completion.kind), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(7.dp))
                    Text(completion.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(completion.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun completionIcon(kind: MarkdownCompletionKind): ImageVector = when (kind) {
    MarkdownCompletionKind.WIKI_LINK -> Icons.Default.InsertLink
    MarkdownCompletionKind.BLOCK_REFERENCE -> Icons.Default.FormatQuote
    MarkdownCompletionKind.TAG -> Icons.Default.Tune
    MarkdownCompletionKind.PROPERTY -> Icons.Default.Tune
}

private fun TextRange.coerceIn(length: Int): TextRange = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

@Composable
private fun MarkdownReader(state: WorkbenchState, tab: DocumentTab) {
    val blocks = remember(tab.content) { MarkdownParser.blocks(tab.content) }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        items(blocks) { block -> MarkdownBlockView(state, tab, block) }
    }
}

@Composable
private fun MarkdownBlockView(state: WorkbenchState, tab: DocumentTab, block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.HeadingBlock -> LinkedMarkdownText(
            state,
            tab,
            block.text,
            TextStyle(
                fontSize = when (block.level) { 1 -> 26.sp; 2 -> 21.sp; 3 -> 18.sp; else -> 15.sp },
                lineHeight = when (block.level) { 1 -> 34.sp; 2 -> 29.sp; else -> 24.sp },
                fontWeight = FontWeight.Bold,
            ),
        )
        is MarkdownBlock.Paragraph -> LinkedMarkdownText(state, tab, block.text, TextStyle(fontSize = 15.sp, lineHeight = 25.sp))
        is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)).padding(12.dp)) {
            Box(Modifier.width(3.dp).height(30.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(11.dp))
            LinkedMarkdownText(state, tab, block.text, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is MarkdownBlock.ListItem -> Row {
            Text(if (block.ordered) "1." else "•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(25.dp))
            LinkedMarkdownText(state, tab, block.text, TextStyle(lineHeight = 23.sp), modifier = Modifier.weight(1f))
        }
        is MarkdownBlock.CodeBlock -> Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) { if (!block.language.isNullOrBlank()) Text(block.language!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary); Text(block.code, fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 20.sp) }
        }
        is MarkdownBlock.MathBlock -> Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center) { Text(block.source, fontFamily = CodeFont, color = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        MarkdownBlock.Divider -> Divider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LinkedMarkdownText(
    state: WorkbenchState,
    tab: DocumentTab,
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val target = remember(text) { Regex("!?\\[\\[([^]#^|]+)").find(text)?.groupValues?.getOrNull(1)?.trim() }
    val note = target?.let { state.workspaceIndex.resolve(tab.relativePath, it) }
    val content: @Composable () -> Unit = {
        Text(
            inlineMarkdown(text),
            style = style,
            color = color,
            modifier = modifier.then(if (note != null) Modifier.clickable { state.navigateWikiLink(tab.relativePath, target.orEmpty()) } else Modifier),
        )
    }
    if (note == null) content() else TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Column(Modifier.widthIn(max = 320.dp)) {
                    Text(note.title, fontWeight = FontWeight.SemiBold)
                    note.analysis.headings.take(3).forEach { heading -> Text(heading.text, style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
        state = rememberTooltipState(),
    ) { content() }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    Regex("\\*\\*([^*]+)\\*\\*").findAll(text).forEach { match -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1) }
    Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").findAll(text).forEach { match -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1) }
    Regex("\\[\\[([^]]+)]]").findAll(text).forEach { match -> addStyle(SpanStyle(color = Color(0xFF2F6758), fontWeight = FontWeight.Medium), match.range.first, match.range.last + 1) }
    Regex("(?<![\\p{L}\\p{N}_#])#[\\p{L}\\p{N}_/-]+").findAll(text).forEach { match -> addStyle(SpanStyle(color = Color(0xFF4B6288)), match.range.first, match.range.last + 1) }
}

@Composable
private fun TextDocument(state: WorkbenchState, tab: DocumentTab) {
    var value by remember(tab) { mutableStateOf(TextFieldValue(tab.content, tab.cursor)) }
    LaunchedEffect(tab.content) { if (value.text != tab.content) value = TextFieldValue(tab.content, tab.cursor.coerceIn(tab.content.length)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("纯文本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f)); EditorTool(Icons.Default.Save, "保存", { state.save(tab) })
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        BasicTextField(value, { value = it; state.updateContent(tab, it.text, it.selection) }, Modifier.fillMaxSize().padding(22.dp), textStyle = TextStyle(fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun PdfDocument(state: WorkbenchState, tab: DocumentTab, compact: Boolean = false) {
    val path = state.workspaceRoot!!.resolve(tab.relativePath)
    var pageCount by remember(tab.relativePath) { mutableStateOf(0) }
    var bitmap by remember(tab.relativePath, tab.pdfPage, tab.pdfZoom) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(tab.relativePath) { mutableStateOf<String?>(null) }
    var pageInput by remember(tab.pdfPage) { mutableStateOf((tab.pdfPage + 1).toString()) }
    val rotation = tab.pdfRotation
    var showSearch by remember(tab.relativePath) { mutableStateOf(false) }
    var searchQuery by remember(tab.relativePath) { mutableStateOf("") }
    var searchRequest by remember(tab.relativePath) { mutableStateOf(0) }
    var searchPages by remember(tab.relativePath) { mutableStateOf<List<Int>>(emptyList()) }
    var searchRunning by remember(tab.relativePath) { mutableStateOf(false) }
    val viewMode = runCatching { PdfViewMode.valueOf(tab.pdfViewMode) }.getOrDefault(PdfViewMode.CONTINUOUS)
    val fitMode = runCatching { PdfFitMode.valueOf(tab.pdfFitMode) }.getOrDefault(PdfFitMode.WIDTH)
    val configuredSidePanel = runCatching { PdfSidePanel.valueOf(tab.pdfSidePanel) }.getOrDefault(PdfSidePanel.THUMBNAILS)
    val sidePanel = if (compact) PdfSidePanel.NONE else configuredSidePanel
    var selectedPage by remember(tab.relativePath) { mutableStateOf<Int?>(null) }
    var annotationDialog by remember(tab.relativePath) { mutableStateOf(false) }
    var annotationText by remember(tab.relativePath) { mutableStateOf("") }
    var textSelectionPage by remember(tab.relativePath) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = tab.pdfPage)
    LaunchedEffect(path) {
        try {
            pageCount = runTrackedPdfTask(state, "pdf-load:${tab.relativePath}", "PDF 加载", tab.displayName) {
                withContext(Dispatchers.IO) { Loader.loadPDF(path.toFile()).use { it.numberOfPages } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.message ?: "PDF 加载失败"
        }
    }
    LaunchedEffect(path, tab.pdfPage, tab.pdfZoom, viewMode) {
        if (viewMode != PdfViewMode.SINGLE) return@LaunchedEffect
        bitmap = null
        loadError = null
        try {
            bitmap = runTrackedPdfTask(
                state,
                "pdf-render:${tab.relativePath}:${tab.pdfPage}:${tab.pdfZoom}",
                "PDF 渲染",
                "${tab.displayName} · 第 ${tab.pdfPage + 1} 页",
            ) {
                withContext(Dispatchers.IO) {
                Loader.loadPDF(path.toFile()).use { document ->
                    val safePage = tab.pdfPage.coerceIn(0, (document.numberOfPages - 1).coerceAtLeast(0))
                    val image = PDFRenderer(document).renderImageWithDPI(safePage, 110f * tab.pdfZoom)
                    val output = ByteArrayOutputStream()
                    ImageIO.write(image, "png", output)
                    SkiaImage.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
                }
            }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            loadError = error.message ?: "PDF 渲染失败"
        }
    }
    LaunchedEffect(tab.pdfPage, viewMode, pageCount) {
        if (viewMode == PdfViewMode.CONTINUOUS && pageCount > 0 && listState.firstVisibleItemIndex != tab.pdfPage) {
            listState.animateScrollToItem(tab.pdfPage.coerceIn(0, pageCount - 1))
        }
    }
    LaunchedEffect(listState, viewMode) {
        if (viewMode != PdfViewMode.CONTINUOUS) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { page ->
            state.setPdfPosition(page = page, document = tab)
        }
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest == 0 || searchQuery.isBlank()) return@LaunchedEffect
        searchRunning = true
        try {
            searchPages = runTrackedPdfTask(
                state,
                "pdf-search:${tab.relativePath}:$searchRequest",
                "PDF 搜索",
                "${tab.displayName} · ${searchQuery.trim()}",
            ) {
                withContext(Dispatchers.IO) {
                Loader.loadPDF(path.toFile()).use { document ->
                    val stripper = org.apache.pdfbox.text.PDFTextStripper()
                    (0 until document.numberOfPages).filter { page ->
                        stripper.startPage = page + 1
                        stripper.endPage = page + 1
                        stripper.getText(document).contains(searchQuery.trim(), ignoreCase = true)
                    }
                }
            }
            }
            searchPages.firstOrNull()?.let { state.setPdfPosition(page = it, document = tab) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            searchPages = emptyList()
        } finally {
            searchRunning = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(43.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorTool(Icons.Default.NavigateBefore, "上一页", { if (tab.pdfPage > 0) state.setPdfPosition(page = tab.pdfPage - 1, document = tab) })
            OutlinedTextField(
                pageInput,
                { pageInput = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                modifier = Modifier.width(62.dp).height(35.dp).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                        pageInput.toIntOrNull()?.let { page -> state.setPdfPosition(page = (page - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)), document = tab) }
                        true
                    } else false
                },
                textStyle = MaterialTheme.typography.labelMedium,
            )
            Text(" / ${pageCount.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EditorTool(Icons.Default.NavigateNext, "下一页", {
                val step = if (viewMode == PdfViewMode.DOUBLE) 2 else 1
                if (tab.pdfPage + step < pageCount) state.setPdfPosition(page = tab.pdfPage + step, document = tab)
            })
            Divider(Modifier.height(22.dp).width(1.dp).padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
            EditorTool(Icons.Default.ZoomOut, "缩小", { state.setPdfPosition(zoom = tab.pdfZoom - 0.15f, document = tab) })
            Text("${(tab.pdfZoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(45.dp))
            EditorTool(Icons.Default.ZoomIn, "放大", { state.setPdfPosition(zoom = tab.pdfZoom + 0.15f, document = tab) })
            EditorTool(Icons.Default.WidthFull, "适宽", { state.setPdfFitMode(PdfFitMode.WIDTH.name, tab) })
            EditorTool(Icons.Default.Height, "适高", { state.setPdfFitMode(PdfFitMode.HEIGHT.name, tab) })
            EditorTool(Icons.Default.RotateRight, "顺时针旋转", { tab.pdfRotation = (tab.pdfRotation + 90f) % 360f; state.persistLayout() })
            if (!compact) {
                EditorTool(Icons.Default.ViewSidebar, "缩略图", { tab.pdfSidePanel = (if (sidePanel == PdfSidePanel.THUMBNAILS) PdfSidePanel.NONE else PdfSidePanel.THUMBNAILS).name; state.persistLayout() })
                EditorTool(Icons.Default.Comment, "批注", { tab.pdfSidePanel = (if (sidePanel == PdfSidePanel.ANNOTATIONS) PdfSidePanel.NONE else PdfSidePanel.ANNOTATIONS).name; state.persistLayout() })
            }
            Spacer(Modifier.width(10.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                Row {
                    PdfModeItem("连续", PdfViewMode.CONTINUOUS, viewMode) { tab.pdfViewMode = it.name; state.persistLayout() }
                    PdfModeItem("单页", PdfViewMode.SINGLE, viewMode) { tab.pdfViewMode = it.name; state.persistLayout() }
                    PdfModeItem("双页", PdfViewMode.DOUBLE, viewMode) { tab.pdfViewMode = it.name; state.persistLayout() }
                }
            }
            Spacer(Modifier.width(10.dp))
            EditorTool(Icons.Default.Search, "文档内搜索", { showSearch = !showSearch })
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        if (showSearch) {
            Row(
                Modifier.fillMaxWidth().height(43.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    searchQuery,
                    { searchQuery = it },
                    singleLine = true,
                    placeholder = { Text("在 PDF 中搜索") },
                    modifier = Modifier.width(260.dp).height(35.dp).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { searchRequest++; true } else false
                    },
                    textStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { searchRequest++ }, enabled = searchQuery.isNotBlank() && !searchRunning, modifier = Modifier.height(32.dp)) { Text("查找") }
                Spacer(Modifier.width(10.dp))
                Text(
                    when { searchRunning -> "正在搜索…"; searchRequest == 0 -> ""; searchPages.isEmpty() -> "无结果"; else -> "${searchPages.size} 页命中" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier.fillMaxWidth().height(28.dp).horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("覆盖层", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text("文本选择 · 搜索命中 · 链接 · 高亮 · 批注", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.weight(1f))
            Text("可见页面优先", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        selectedPage?.let { page ->
            Row(
                Modifier.fillMaxWidth().height(39.dp).horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)).padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("第 ${page + 1} 页", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val reference = "[[${tab.relativePath}#page=${page + 1}]]"
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(reference), null)
                }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("复制引用") }
                TextButton(onClick = { state.addPdfAnnotation(tab, page, "highlight", "Page highlight") }) { Icon(Icons.Default.Highlight, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("高亮") }
                TextButton(onClick = { annotationText = ""; annotationDialog = true }) { Icon(Icons.Default.Comment, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("批注") }
                TextButton(onClick = { textSelectionPage = page }) { Icon(Icons.Default.Description, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("选择文本") }
                TextButton(onClick = { state.createPdfReferenceNote(tab, page) }) { Icon(Icons.Default.FormatQuote, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("引用到笔记") }
                TextButton(onClick = {
                    state.addAgentSnippetContext(
                        "${tab.displayName} · 第 ${page + 1} 页",
                        "Source: [[${tab.relativePath}#page=${page + 1}]]",
                    )
                }) { Icon(Icons.Default.SmartToy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("交给 Agent") }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { selectedPage = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "关闭上下文栏", Modifier.size(15.dp)) }
            }
        }
        Row(Modifier.fillMaxSize()) {
            if (sidePanel != PdfSidePanel.NONE) {
                PdfSidePanelView(state, tab, path, pageCount, sidePanel, onPage = { page -> state.setPdfPosition(page = page, document = tab); selectedPage = page })
                Divider(Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            val viewportModifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE4E7E5)).then(
                if (compact) {
                    Modifier.pointerInput(tab.relativePath) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            if (gestureZoom < 0.99f || gestureZoom > 1.01f) {
                                state.setPdfPosition(zoom = tab.pdfZoom * gestureZoom, document = tab)
                            }
                        }
                    }
                } else Modifier
            )
            Box(viewportModifier, contentAlignment = Alignment.Center) {
                when {
                    loadError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(loadError!!, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(10.dp)); TextButton(onClick = { state.setPdfPosition(page = tab.pdfPage, document = tab) }) { Text("重试") } }
                    viewMode == PdfViewMode.CONTINUOUS && pageCount > 0 -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(pageCount) { page -> PdfPageItem(path, page, tab.pdfZoom * if (compact) 0.72f else 1f, rotation, selectedPage == page, Modifier.fillMaxWidth()) { selectedPage = page } }
                    }
                    viewMode == PdfViewMode.DOUBLE && pageCount > 0 -> Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.Top) {
                        PdfPageItem(path, tab.pdfPage.coerceIn(0, pageCount - 1), tab.pdfZoom * 0.68f, rotation, selectedPage == tab.pdfPage, Modifier.weight(1f)) { selectedPage = tab.pdfPage }
                        if (tab.pdfPage + 1 < pageCount) PdfPageItem(path, tab.pdfPage + 1, tab.pdfZoom * 0.68f, rotation, selectedPage == tab.pdfPage + 1, Modifier.weight(1f)) { selectedPage = tab.pdfPage + 1 }
                    }
                    bitmap == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Spacer(Modifier.height(10.dp)); Text("正在渲染当前页面…", style = MaterialTheme.typography.bodySmall) }
                    else -> {
                        val pageBorder = if (selectedPage == tab.pdfPage) MaterialTheme.colorScheme.secondary else Color(0xFFC6CBC8)
                        if (fitMode == PdfFitMode.HEIGHT) {
                            Box(Modifier.fillMaxSize().padding(18.dp).clickable { selectedPage = tab.pdfPage }, contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap!!,
                                    "PDF 第 ${tab.pdfPage + 1} 页",
                                    modifier = Modifier.fillMaxSize().graphicsLayer(rotationZ = rotation).background(Color.White).border(if (selectedPage == tab.pdfPage) 2.dp else 1.dp, pageBorder),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(24.dp).clickable { selectedPage = tab.pdfPage }, contentAlignment = Alignment.TopCenter) {
                                Image(
                                    bitmap!!,
                                    "PDF 第 ${tab.pdfPage + 1} 页",
                                    modifier = (if (fitMode == PdfFitMode.WIDTH) Modifier.fillMaxWidth() else Modifier).graphicsLayer(rotationZ = rotation).background(Color.White).border(if (selectedPage == tab.pdfPage) 2.dp else 1.dp, pageBorder),
                                    contentScale = if (fitMode == PdfFitMode.WIDTH) ContentScale.FillWidth else ContentScale.None,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (annotationDialog) {
        AlertDialog(
            onDismissRequest = { annotationDialog = false },
            title = { Text("添加 PDF 批注") },
            text = { OutlinedTextField(annotationText, { annotationText = it }, label = { Text("批注内容") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { selectedPage?.let { state.addPdfAnnotation(tab, it, "comment", annotationText) }; tab.pdfSidePanel = PdfSidePanel.ANNOTATIONS.name; state.persistLayout(); annotationDialog = false }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { annotationDialog = false }) { Text("取消") } },
        )
    }
    textSelectionPage?.let { page ->
        PdfTextSelectionDialog(
            state = state,
            tab = tab,
            path = path,
            page = page,
            onDismiss = { textSelectionPage = null },
        )
    }
}

@Composable
private fun PdfModeItem(label: String, mode: PdfViewMode, active: PdfViewMode, onSelect: (PdfViewMode) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (mode == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(if (mode == active) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).clickable { onSelect(mode) }.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private suspend fun <T> runTrackedPdfTask(
    state: WorkbenchState,
    id: String,
    title: String,
    detail: String,
    block: suspend () -> T,
): T {
    val job = currentCoroutineContext()[Job]
    state.beginBackgroundTask(id, title, detail, onCancel = job?.let { current -> { current.cancel() } })
    return try {
        block()
    } finally {
        state.finishBackgroundTask(id)
    }
}

@Composable
private fun PdfTextSelectionDialog(
    state: WorkbenchState,
    tab: DocumentTab,
    path: java.nio.file.Path,
    page: Int,
    onDismiss: () -> Unit,
) {
    var value by remember(path, page) { mutableStateOf(TextFieldValue("")) }
    var loading by remember(path, page) { mutableStateOf(true) }
    var error by remember(path, page) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, page) {
        loading = true
        try {
            val extracted = runTrackedPdfTask(state, "pdf-text:${tab.relativePath}:$page", "PDF 文本提取", "${tab.displayName} · 第 ${page + 1} 页") {
                withContext(Dispatchers.IO) {
                Loader.loadPDF(path.toFile()).use { document ->
                    org.apache.pdfbox.text.PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(document).trim()
                }
            }
            }
            value = TextFieldValue(extracted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message
        } finally {
            loading = false
        }
    }
    val selection = value.selection.let { range ->
        val start = minOf(range.start, range.end).coerceIn(0, value.text.length)
        val end = maxOf(range.start, range.end).coerceIn(0, value.text.length)
        value.text.substring(start, end)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第 ${page + 1} 页文本") },
        text = {
            Column(Modifier.height(450.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    value.text.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("此页没有可提取文本。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        readOnly = true,
                        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(selection), null) }, enabled = selection.isNotBlank()) { Text("复制") }
                TextButton(onClick = { state.addPdfAnnotation(tab, page, "highlight", selection); onDismiss() }, enabled = selection.isNotBlank()) { Text("高亮") }
                TextButton(onClick = { state.createPdfReferenceNote(tab, page, selection); onDismiss() }, enabled = selection.isNotBlank()) { Text("引用到笔记") }
                TextButton(onClick = {
                    state.addAgentSnippetContext(
                        "${tab.displayName} · 第 ${page + 1} 页选区",
                        "Source: [[${tab.relativePath}#page=${page + 1}]]\n\n$selection",
                    )
                    onDismiss()
                }, enabled = selection.isNotBlank()) { Text("交给 Agent") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun PdfSidePanelView(
    state: WorkbenchState,
    tab: DocumentTab,
    path: java.nio.file.Path,
    pageCount: Int,
    panel: PdfSidePanel,
    onPage: (Int) -> Unit,
) {
    Column(Modifier.width(184.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (panel == PdfSidePanel.THUMBNAILS) "缩略图" else "批注", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (panel == PdfSidePanel.THUMBNAILS) "$pageCount 页" else "${tab.pdfAnnotations.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        if (panel == PdfSidePanel.THUMBNAILS) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(pageCount) { page -> PdfThumbnail(path, page, tab.pdfPage == page) { onPage(page) } }
            }
        } else if (tab.pdfAnnotations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { Text("选择页面后可添加高亮或批注。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                items(tab.pdfAnnotations, key = { it.id }) { annotation ->
                    Row(Modifier.fillMaxWidth().clickable { onPage(annotation.page) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 3.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (annotation.kind == "highlight") Icons.Default.Highlight else Icons.Default.Comment, null, Modifier.size(16.dp), tint = if (annotation.kind == "highlight") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text("第 ${annotation.page + 1} 页", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            Text(annotation.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { state.removePdfAnnotation(tab, annotation) }, modifier = Modifier.size(27.dp)) { Icon(Icons.Default.DeleteOutline, "删除批注", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnail(path: java.nio.file.Path, page: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(path, page) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, page) {
        runCatching {
            withContext(Dispatchers.IO) {
                Loader.loadPDF(path.toFile()).use { document ->
                    val image = PDFRenderer(document).renderImageWithDPI(page, 28f)
                    val output = ByteArrayOutputStream()
                    ImageIO.write(image, "png", output)
                    SkiaImage.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
                }
            }
        }.onSuccess { bitmap = it }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = Color.White,
            modifier = Modifier.width(118.dp).height(152.dp).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (bitmap == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Image(bitmap!!, "第 ${page + 1} 页缩略图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text("${page + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PdfPageItem(path: java.nio.file.Path, page: Int, zoom: Float, rotation: Float, selected: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: () -> Unit) {
    var bitmap by remember(path, page, zoom) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(path, page) { mutableStateOf(false) }
    LaunchedEffect(path, page, zoom) {
        bitmap = null
        error = false
        runCatching {
            withContext(Dispatchers.IO) {
                Loader.loadPDF(path.toFile()).use { document ->
                    val image = PDFRenderer(document).renderImageWithDPI(page, 96f * zoom)
                    val output = ByteArrayOutputStream()
                    ImageIO.write(image, "png", output)
                    SkiaImage.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
                }
            }
        }.onSuccess { bitmap = it }.onFailure { error = true }
    }
    Column(modifier.clickable(onClick = onSelect), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${page + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(5.dp))
        when {
            error -> Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f), modifier = Modifier.width(360.dp).height(180.dp)) { Box(contentAlignment = Alignment.Center) { Text("此页渲染失败", color = MaterialTheme.colorScheme.error) } }
            bitmap == null -> Surface(color = Color.White, modifier = Modifier.width((680f * zoom).dp).height((880f * zoom).dp)) { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
            else -> Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Image(
                    bitmap!!,
                    "PDF 第 ${page + 1} 页",
                    modifier = Modifier.width((680f * zoom).dp).graphicsLayer(rotationZ = rotation).background(Color.White).border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.secondary else Color(0xFFC6CBC8)),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

@Composable
private fun ImageDocument(state: WorkbenchState, tab: DocumentTab) {
    val path = state.workspaceRoot!!.resolve(tab.relativePath)
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var zoom by remember(path) { mutableStateOf(1f) }
    LaunchedEffect(path) {
        runCatching { withContext(Dispatchers.IO) { SkiaImage.makeFromEncoded(java.nio.file.Files.readAllBytes(path)).toComposeImageBitmap() } }
            .onSuccess { bitmap = it }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            EditorTool(Icons.Default.ZoomOut, "缩小", { zoom = (zoom - 0.1f).coerceAtLeast(0.2f) })
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp))
            EditorTool(Icons.Default.ZoomIn, "放大", { zoom = (zoom + 0.1f).coerceAtMost(4f) })
            EditorTool(Icons.Default.OpenInNew, "在系统中打开", { runCatching { Desktop.getDesktop().open(path.toFile()) } })
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxSize().background(Color(0xFFE8EBE9)).padding(24.dp), contentAlignment = Alignment.Center) {
            when { error != null -> Text(error!!, color = MaterialTheme.colorScheme.error); bitmap == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); else -> Image(bitmap!!, tab.displayName, Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom), contentScale = ContentScale.Fit) }
        }
    }
}

@Composable
private fun UnsupportedDocument(state: WorkbenchState, tab: DocumentTab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp)); Text("无法在工作台中预览 ${tab.displayName}", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(5.dp)); Text("文件保持原样，可使用系统应用打开。", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)); Button(onClick = { state.workspaceRoot?.resolve(tab.relativePath)?.toFile()?.let { runCatching { Desktop.getDesktop().open(it) } } }) { Text("使用系统应用打开") }
        }
    }
}

@Composable
private fun EditorTool(icon: ImageVector, description: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) { Icon(icon, description, modifier = Modifier.size(17.dp)) }
    }
}

private fun documentIcon(kind: DocumentKind): ImageVector = when (kind) {
    DocumentKind.PDF -> Icons.Default.PictureAsPdf
    DocumentKind.IMAGE -> Icons.Default.Image
    DocumentKind.AGENT -> Icons.Default.SmartToy
    else -> Icons.Default.Description
}
