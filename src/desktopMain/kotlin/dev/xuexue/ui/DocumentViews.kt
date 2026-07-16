@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.xuexue.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertLink
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.LooksTwo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.WidthFull
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xuexue.DocumentDropTarget
import dev.xuexue.DocumentSplit
import dev.xuexue.DocumentTab
import dev.xuexue.WorkbenchState
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.DocumentSplitDirection
import dev.xuexue.core.EditorCommand
import dev.xuexue.core.EditorMode
import dev.xuexue.core.MarkdownBlock
import dev.xuexue.core.MarkdownCompletion
import dev.xuexue.core.MarkdownCompletionKind
import dev.xuexue.core.MarkdownEditNode
import dev.xuexue.core.MarkdownEditing
import dev.xuexue.core.MarkdownParser
import dev.xuexue.core.WorkbenchCommand
import dev.xuexue.platform.PdfRasterPurpose
import dev.xuexue.platform.PdfSearchMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JFileChooser
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

private const val DOCUMENT_DROP_PRIMARY_FRACTION = 0.68f

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
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (state.documents.isNotEmpty()) {
                DocumentTabs(state, onCloseTab, compact)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            DocumentWorkspaceContent(
                state = state,
                onSettings = onSettings,
                onQuickOpen = onQuickOpen,
                compact = compact,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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
            label = "document drop overlay",
        ) { payload ->
            if (payload != null) {
                DocumentDropOverlay(state, payload.displayName)
            }
        }
        MotionVisibility(
            visible = state.documentTabDragPath != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(WorkbenchMotion.fastTween()),
            exit = fadeOut(WorkbenchMotion.exitTween()),
        ) {
            DocumentTabDockPreview(state.documentTabDropTarget)
        }
    }
}

@Composable
private fun DocumentWorkspaceContent(
    state: WorkbenchState,
    onSettings: () -> Unit,
    onQuickOpen: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        androidx.compose.runtime.key(state.activeDocument?.relativePath) {
            val relativePath = state.activeDocument?.relativePath
            val document = state.documents.firstOrNull { it.relativePath == relativePath }
            when {
                document == null -> EmptyDocumentArea(state, onQuickOpen)
                compact -> DocumentContent(state, document, onSettings, Modifier.fillMaxSize(), compact = true)
                else -> DocumentSplitContent(state, document, onSettings)
            }
        }
    }
}

@Composable
private fun DocumentSplitContent(
    state: WorkbenchState,
    document: DocumentTab,
    onSettings: () -> Unit,
) {
    when (state.documentSplit) {
        DocumentSplit.NONE -> {
            DocumentContent(state, document, onSettings, Modifier.fillMaxSize())
        }

        DocumentSplit.VERTICAL -> {
            Row(Modifier.fillMaxSize()) {
                DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(
                    Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SecondaryDocumentPane(state, onSettings, Modifier.weight(1f).fillMaxHeight())
            }
        }

        DocumentSplit.HORIZONTAL -> {
            Column(Modifier.fillMaxSize()) {
                DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(
                    Modifier.fillMaxWidth().height(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SecondaryDocumentPane(state, onSettings, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DocumentDropOverlay(
    state: WorkbenchState,
    displayName: String,
) {
    val active = state.documentDropBounds?.contains(state.dragPosition) == true
    val overlayColor by
        animateColorAsState(
            targetValue = MaterialTheme.colorScheme.primary.copy(alpha = if (active) 0.13f else 0.05f),
            animationSpec = WorkbenchMotion.fastTween(),
            label = "document drop overlay color",
        )
    Box(
        Modifier.fillMaxSize().background(overlayColor),
        contentAlignment = Alignment.Center,
    ) {
        MotionDropTarget(
            active = active,
            accent = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                "释放以插入 [[$displayName]]",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun DocumentTabDockPreview(active: DocumentDropTarget?) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f))) {
        val mainWidth = maxWidth * DOCUMENT_DROP_PRIMARY_FRACTION
        val mainHeight = maxHeight * DOCUMENT_DROP_PRIMARY_FRACTION
        DocumentDropPreviewTarget(
            "主文档区",
            active == DocumentDropTarget.PRIMARY,
            Modifier.align(Alignment.TopStart).width(mainWidth).height(mainHeight),
        )
        DocumentDropPreviewTarget(
            "右侧分栏",
            active == DocumentDropTarget.RIGHT,
            Modifier.align(Alignment.CenterEnd).width(maxWidth - mainWidth).fillMaxHeight(),
        )
        DocumentDropPreviewTarget(
            "下方分栏",
            active == DocumentDropTarget.BOTTOM,
            Modifier.align(Alignment.BottomStart).width(mainWidth).height(maxHeight - mainHeight),
        )
    }
}

@Composable
private fun DocumentDropPreviewTarget(
    label: String,
    active: Boolean,
    modifier: Modifier,
) {
    MotionDropTarget(
        active = active,
        accent = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun DocumentContent(
    state: WorkbenchState,
    document: DocumentTab,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        if (document.externalDeleted) {
            ExternalDeletedBanner(state, document)
        } else if (document.externalContent != null) {
            ExternalChangeBanner(state, document)
        } else if (document.saveError != null) {
            SaveFailureBanner(state, document)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (document.kind) {
                DocumentKind.MARKDOWN -> {
                    MarkdownDocument(state, document, compact)
                }

                DocumentKind.TEXT -> {
                    TextDocument(state, document)
                }

                DocumentKind.PDF -> {
                    PdfDocument(state, document, compact)
                }

                DocumentKind.IMAGE -> {
                    ImageDocument(state, document)
                }

                DocumentKind.AGENT -> {
                    AgentPanel(
                        state = state,
                        onClose = { state.closeTab(document, force = true) },
                        onSettings = onSettings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                DocumentKind.UNSUPPORTED -> {
                    UnsupportedDocument(state, document)
                }
            }
        }
    }
}

@Composable
private fun SaveFailureBanner(
    state: WorkbenchState,
    document: DocumentTab,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.09f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("保存失败：${document.saveError}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { state.retrySave(document) }) { Text("重试") }
        TextButton(onClick = {
            val root = state.workspaceRoot ?: return@TextButton
            val chooser =
                JFileChooser(root.toFile()).apply {
                    dialogTitle = "另存为"
                    selectedFile = root.resolve(document.displayName).toFile()
                    fileSelectionMode = JFileChooser.FILES_ONLY
                }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) state.saveAs(document, chooser.selectedFile.toPath())
        }) { Text("另存为") }
    }
}

@Composable
fun DetachedDocumentView(
    state: WorkbenchState,
    document: DocumentTab,
    onSettings: () -> Unit,
    onAttach: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(documentIcon(document.kind), null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(7.dp))
            Text(document.relativePath, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onAttach) {
                Icon(Icons.Default.CloseFullscreen, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("停靠回主窗口")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DocumentContent(state, document, onSettings, Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier
                .fillMaxWidth()
                .height(25.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (document.dirty) "未保存" else "已保存", style = MaterialTheme.typography.labelSmall, color = if (document.dirty) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(document.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExternalDeletedBanner(
    state: WorkbenchState,
    document: DocumentTab,
) {
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
private fun ExternalChangeBanner(
    state: WorkbenchState,
    document: DocumentTab,
) {
    var showDiff by remember(document) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.09f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
                    VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    DiffVersionPane("外部版本", document.externalContent.orEmpty(), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
            },
            confirmButton = {
                Button(onClick = {
                    state.mergeExternalVersion(document)
                    showDiff = false
                }) { Text("插入冲突标记") }
            },
            dismissButton = { TextButton(onClick = { showDiff = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DiffVersionPane(
    title: String,
    content: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent, modifier = Modifier.padding(9.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(content, fontFamily = CodeFont, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp))
    }
}

@Composable
private fun SecondaryDocumentPane(
    state: WorkbenchState,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val document =
        state.documents.firstOrNull { it.relativePath == state.secondaryPath }
            ?: state.documents.firstOrNull { it.relativePath != state.activePath }
            ?: state.activeDocument
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp),
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
                            onClick = {
                                state.setSecondaryDocument(tab.relativePath)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { state.execute(WorkbenchCommand.ClearDocumentSplit) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Close, "关闭分栏", Modifier.size(15.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (document == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有可显示的文档", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            DocumentContent(state, document, onSettings, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DocumentTabs(
    state: WorkbenchState,
    onCloseTab: (DocumentTab) -> Unit,
    compact: Boolean = false,
) {
    var actionsOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::navigateBack, enabled = state.canNavigateBack, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(17.dp)) }
        if (!compact) IconButton(onClick = state::navigateForward, enabled = state.canNavigateForward, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进", Modifier.size(17.dp)) }
        VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            state.documents.filter { it.relativePath !in state.detachedDocuments }.forEach { tab ->
                val selected = state.activePath == tab.relativePath
                var tabCoordinates by remember(tab.relativePath) { mutableStateOf<LayoutCoordinates?>(null) }
                var menuOpen by remember(tab.relativePath) { mutableStateOf(false) }
                val interactionSource = remember(tab.relativePath) { MutableInteractionSource() }
                val hovered by interactionSource.collectIsHoveredAsState()
                val tabColor =
                    when {
                        selected -> MaterialTheme.colorScheme.surface
                        hovered -> MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
                        else -> Color.Transparent
                    }
                val iconColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Box {
                    Row(
                        Modifier
                            .height(38.dp)
                            .width(190.dp)
                            .background(tabColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                state.execute(WorkbenchCommand.SelectDocument(tab.relativePath))
                            }.onPointerEvent(PointerEventType.Press) { event ->
                                if (event.buttons.isSecondaryPressed) menuOpen = true
                            }.onGloballyPositioned { tabCoordinates = it }
                            .pointerInput(tab.relativePath) {
                                detectDragGestures(
                                    onDragStart = { local -> tabCoordinates?.localToRoot(local)?.let { state.beginDocumentTabDrag(tab.relativePath, it) } },
                                    onDragEnd = state::finishDocumentTabDrag,
                                    onDragCancel = state::cancelDocumentTabDrag,
                                ) { change, _ ->
                                    change.consume()
                                    tabCoordinates?.localToRoot(change.position)?.let(state::updateDocumentTabDrag)
                                }
                            }.padding(start = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            documentIcon(tab.kind),
                            null,
                            Modifier.size(16.dp),
                            tint = iconColor,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            tab.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (tab.dirty) {
                            Box(
                                Modifier.size(7.dp).background(
                                    MaterialTheme.colorScheme.secondary,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                            )
                        } else if (!tab.pinned) {
                            IconButton(
                                onClick = { state.execute(WorkbenchCommand.PinDocument(tab.relativePath)) },
                                modifier = Modifier.size(25.dp),
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    "固定预览",
                                    Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        MotionIconButton(onClick = { onCloseTab(tab) }, modifier = Modifier.size(27.dp)) {
                            Icon(Icons.Default.Close, "关闭", Modifier.size(15.dp))
                        }
                    }
                    MotionVisibility(
                        visible = selected,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.CenterHorizontally,
                                animationSpec = WorkbenchMotion.expressiveSpring(),
                            ) + fadeIn(WorkbenchMotion.fastTween()),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.CenterHorizontally,
                                animationSpec = WorkbenchMotion.standardTween(),
                            ) + fadeOut(WorkbenchMotion.exitTween()),
                    ) {
                        Box(
                            Modifier
                                .width(190.dp)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        if (!tab.pinned) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("固定标签") },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = {
                                    state.execute(WorkbenchCommand.PinDocument(tab.relativePath))
                                    menuOpen = false
                                },
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("向右分栏") },
                            leadingIcon = { Icon(Icons.Default.ViewColumn, null) },
                            onClick = {
                                state.execute(
                                    WorkbenchCommand.SplitDocument(tab.relativePath, DocumentSplitDirection.RIGHT),
                                )
                                menuOpen = false
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("向下分栏") },
                            leadingIcon = { Icon(Icons.Default.ViewStream, null) },
                            onClick = {
                                state.execute(
                                    WorkbenchCommand.SplitDocument(tab.relativePath, DocumentSplitDirection.BOTTOM),
                                )
                                menuOpen = false
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("在独立窗口打开") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                            onClick = {
                                state.execute(WorkbenchCommand.DetachDocument(tab.relativePath))
                                menuOpen = false
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = {
                                onCloseTab(tab)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        if (!compact) {
            VerticalDivider(Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box {
                IconButton(onClick = { actionsOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "文档布局选项", Modifier.size(18.dp))
                }
                androidx.compose.material3.DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("在独立窗口打开") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = {
                            state.activePath?.let { state.execute(WorkbenchCommand.DetachDocument(it)) }
                            actionsOpen = false
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("向右分栏") },
                        leadingIcon = { Icon(Icons.Default.ViewColumn, null) },
                        onClick = {
                            state.activePath?.let {
                                state.execute(WorkbenchCommand.SplitDocument(it, DocumentSplitDirection.RIGHT))
                            }
                            actionsOpen = false
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("向下分栏") },
                        leadingIcon = { Icon(Icons.Default.ViewStream, null) },
                        onClick = {
                            state.activePath?.let {
                                state.execute(WorkbenchCommand.SplitDocument(it, DocumentSplitDirection.BOTTOM))
                            }
                            actionsOpen = false
                        },
                    )
                    if (state.documentSplit != DocumentSplit.NONE) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭文档分栏") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = {
                                state.execute(WorkbenchCommand.ClearDocumentSplit)
                                actionsOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDocumentArea(
    state: WorkbenchState,
    onQuickOpen: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text("工作区已就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("从最近文件继续，或创建一篇新笔记。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::createMarkdown) {
                    Icon(Icons.Default.Add, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建笔记")
                }
                TextButton(onClick = onQuickOpen) {
                    Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("快速打开")
                }
            }
            if (state.recentDocuments.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text("最近文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                state.recentDocuments.take(5).forEach { path ->
                    Row(
                        Modifier
                            .widthIn(max = 460.dp)
                            .fillMaxWidth()
                            .height(34.dp)
                            .clickable { state.navigateTo(path) }
                            .padding(horizontal = 8.dp),
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
private fun MarkdownDocument(
    state: WorkbenchState,
    tab: DocumentTab,
    compact: Boolean = false,
) {
    Column(Modifier.fillMaxSize()) {
        if (compact) CompactMarkdownModeBar(state, tab) else MarkdownToolbar(state, tab)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab.mode) {
                EditorMode.EDIT -> StructuredMarkdownEditor(state, tab)
                EditorMode.SOURCE -> MarkdownEditor(state, tab, sourceMode = true)
                EditorMode.READ -> MarkdownReader(state, tab)
            }
        }
        if (compact && tab.mode != EditorMode.READ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CompactMarkdownAccessory(state, tab)
        }
    }
}

@Composable
private fun CompactMarkdownModeBar(
    state: WorkbenchState,
    tab: DocumentTab,
) {
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
private fun CompactMarkdownAccessory(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorTool(Icons.AutoMirrored.Filled.Undo, "撤销", { state.execute(WorkbenchCommand.Undo, tab) })
        EditorTool(Icons.AutoMirrored.Filled.Redo, "重做", { state.execute(WorkbenchCommand.Redo, tab) })
        VerticalDivider(Modifier.height(22.dp).padding(horizontal = 5.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        listOf(
            Icons.Default.FormatBold to EditorCommand.BOLD,
            Icons.Default.FormatItalic to EditorCommand.ITALIC,
            Icons.Default.InsertLink to EditorCommand.LINK,
            Icons.AutoMirrored.Filled.FormatListBulleted to EditorCommand.BULLET_LIST,
            Icons.Default.FormatListNumbered to EditorCommand.NUMBERED_LIST,
            Icons.Default.FormatQuote to EditorCommand.QUOTE,
            Icons.Default.Functions to EditorCommand.MATH_BLOCK,
        ).forEach { (icon, command) ->
            EditorTool(icon, editorCommandLabel(command), { state.execute(WorkbenchCommand.EditMarkdown(command), tab) })
        }
    }
}

private fun editorCommandLabel(command: EditorCommand): String =
    when (command) {
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
private fun MarkdownToolbar(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSelector(tab.mode) { state.setEditorMode(it, tab) }
        VerticalDivider(Modifier.height(22.dp).padding(horizontal = 6.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        if (tab.mode != EditorMode.READ) {
            EditorTool(Icons.AutoMirrored.Filled.Undo, "撤销", { state.execute(WorkbenchCommand.Undo, tab) })
            EditorTool(Icons.AutoMirrored.Filled.Redo, "重做", { state.execute(WorkbenchCommand.Redo, tab) })
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
                EditorTool(Icons.AutoMirrored.Filled.FormatListBulleted, "无序列表", { state.execute(WorkbenchCommand.EditMarkdown(EditorCommand.BULLET_LIST), tab) })
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
private fun ModeSelector(
    active: EditorMode,
    onSelect: (EditorMode) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(5.dp),
        modifier =
            Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape
                    .RoundedCornerShape(5.dp),
            ),
    ) {
        Row {
            listOf(EditorMode.EDIT to "编辑", EditorMode.SOURCE to "源码", EditorMode.READ to "阅读").forEach { (mode, label) ->
                val selected = mode == active
                val interactionSource = remember(mode) { MutableInteractionSource() }
                val hovered by interactionSource.collectIsHoveredAsState()
                val pressed by interactionSource.collectIsPressedAsState()
                val containerColor =
                    when {
                        selected -> MaterialTheme.colorScheme.surface
                        hovered -> MaterialTheme.colorScheme.surface.copy(alpha = 0.52f)
                        else -> Color.Transparent
                    }
                val contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                val scale by
                    animateFloatAsState(
                        targetValue =
                            if (pressed) {
                                0.94f
                            } else if (selected) {
                                1.02f
                            } else {
                                1f
                            },
                        animationSpec = WorkbenchMotion.expressiveSpring(),
                        label = "editor mode scale",
                    )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    modifier =
                        Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }.background(
                                containerColor,
                                androidx.compose.foundation.shape
                                    .RoundedCornerShape(4.dp),
                            ).clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                onSelect(mode)
                            }.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun StructuredMarkdownEditor(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    val nodes = remember(tab.content) { MarkdownEditing.parseNodes(tab.content) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = tab.editorBlockIndex.coerceIn(0, (nodes.lastIndex).coerceAtLeast(0)))
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { tab.editorBlockIndex = it }
    }
    if (nodes.isEmpty()) {
        StructuredNodeEditor(state, tab, MarkdownEditNode(0, 0, "paragraph", ""), active = true)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val horizontalPadding = ((maxWidth - 960.dp) / 2).coerceAtLeast(20.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(nodes, key = { "${it.start}:${it.type}" }) { node ->
                StructuredNodeEditor(
                    state,
                    tab,
                    node,
                    active = nodeOwnsCursor(node, tab.cursor.start, tab.content.length),
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

@Composable
private fun StructuredNodeEditor(
    state: WorkbenchState,
    tab: DocumentTab,
    node: MarkdownEditNode,
    active: Boolean,
) {
    val initialSelectionStart = (tab.cursor.start - node.start - node.prefix.length).coerceIn(0, node.body.length)
    val initialSelectionEnd = (tab.cursor.end - node.start - node.prefix.length).coerceIn(initialSelectionStart, node.body.length)
    var value by remember(tab, node.start, node.type) { mutableStateOf(TextFieldValue(node.body, TextRange(initialSelectionStart, initialSelectionEnd))) }
    val focusRequester = remember(tab, node.start, node.type) { FocusRequester() }
    val localSelection = TextRange(initialSelectionStart, initialSelectionEnd)
    LaunchedEffect(node.body, tab.content, tab.cursor, active) {
        if (value.text != node.body || (active && value.selection != localSelection)) {
            val selection = if (active) localSelection else value.selection.coerceIn(node.body.length)
            value = TextFieldValue(node.body, selection)
        }
    }
    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus()
    }
    val accent = nodeAccent(node.type)
    val completions = if (active) state.markdownCompletions(tab) else emptyList()
    val borderColor by
        animateColorAsState(
            targetValue = if (active) accent.copy(alpha = 0.58f) else Color.Transparent,
            animationSpec = WorkbenchMotion.fastTween(),
            label = "markdown node focus",
        )
    Surface(
        color = nodeBackground(node.type),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        modifier =
            Modifier.fillMaxWidth().border(
                1.dp,
                borderColor,
                androidx.compose.foundation.shape
                    .RoundedCornerShape(4.dp),
            ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            StructuredNodePrefix(node)
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    val previous = value
                    value = next
                    if (!applyStructuredNodeValue(state, tab, node, previous, next)) {
                        value = TextFieldValue(node.body, localSelection)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus -> if (focus.isFocused) tab.activeNodeType = node.type }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && completions.isNotEmpty() && event.key in setOf(Key.Tab, Key.Enter)) {
                                state.applyMarkdownCompletion(tab, completions.first())
                                true
                            } else {
                                false
                            }
                        },
                textStyle = nodeTextStyle(node.type),
                cursorBrush =
                    androidx.compose.ui.graphics
                        .SolidColor(accent),
            )
            if (completions.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                MarkdownCompletionPopup(completions, onSelect = { state.applyMarkdownCompletion(tab, it) })
            }
        }
    }
}

private fun applyStructuredNodeValue(
    state: WorkbenchState,
    tab: DocumentTab,
    node: MarkdownEditNode,
    previous: TextFieldValue,
    next: TextFieldValue,
): Boolean {
    val globalStart = node.start + node.prefix.length + next.selection.start
    val globalEnd = node.start + node.prefix.length + next.selection.end
    val globalSelection = TextRange(globalStart, globalEnd)
    val updated =
        if (next.text == previous.text) {
            tab.content
        } else {
            MarkdownEditing.replaceNodeBody(
                source = tab.content,
                start = node.start,
                prefix = node.prefix,
                suffix = node.suffix,
                currentBody = previous.text,
                replacementBody = next.text,
            )
        }
    return if (updated == null) {
        false
    } else {
        state.updateContent(tab, updated, globalSelection)
        true
    }
}

private fun nodeOwnsCursor(
    node: MarkdownEditNode,
    cursor: Int,
    documentLength: Int,
): Boolean = cursor in node.start until node.end || (cursor == node.end && node.end == documentLength)

@Composable
private fun StructuredNodePrefix(node: MarkdownEditNode) {
    if (node.type == "code" || node.type == "mermaid") {
        Text(
            node.prefix
                .trim()
                .removePrefix("```")
                .ifBlank { if (node.type == "mermaid") "mermaid" else "code" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(5.dp))
    } else if (node.type == "callout") {
        Text(
            node.prefix
                .lineSequence()
                .firstOrNull()
                ?.removePrefix("> ")
                .orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun nodeTextStyle(type: String): TextStyle =
    when (type) {
        "heading" -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        "code", "mermaid", "frontmatter", "table", "math", "extension" -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = CodeFont)
        "quote", "callout" -> TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 23.sp)
        else -> TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 25.sp)
    }

@Composable
private fun nodeBackground(type: String): Color =
    when (type) {
        "code", "mermaid", "frontmatter", "table", "extension" -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        "math" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        "quote", "callout" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

@Composable
private fun nodeAccent(type: String): Color =
    when (type) {
        "code", "mermaid", "extension" -> MaterialTheme.colorScheme.tertiary
        "math", "table" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
@Suppress("LongMethod")
private fun MarkdownEditor(
    state: WorkbenchState,
    tab: DocumentTab,
    sourceMode: Boolean,
) {
    var value by remember(tab) { mutableStateOf(TextFieldValue(tab.content, tab.cursor)) }
    var cursorBounds by remember(tab) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(tab.content, tab.cursor) {
        val selection = tab.cursor.coerceIn(tab.content.length)
        if (value.text != tab.content || value.selection != selection) {
            value = TextFieldValue(tab.content, selection)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!sourceMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                    .padding(horizontal = 14.dp),
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
                onValueChange = { next ->
                    value = next
                    state.updateContent(tab, next.text, next.selection)
                },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 960.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = if (sourceMode) 20.dp else 48.dp, vertical = 28.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && completions.isNotEmpty() && event.key in setOf(Key.Tab, Key.Enter)) {
                                state.applyMarkdownCompletion(tab, completions.first())
                                true
                            } else {
                                false
                            }
                        },
                textStyle =
                    TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = if (sourceMode) 13.sp else 15.sp,
                        lineHeight = if (sourceMode) 21.sp else 25.sp,
                        fontFamily = if (sourceMode) CodeFont else FontFamily.Default,
                    ),
                cursorBrush =
                    androidx.compose.ui.graphics
                        .SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout ->
                    cursorBounds = layout.getCursorRect(value.selection.end)
                },
            )
            if (completions.isNotEmpty()) {
                MarkdownCompletionPopup(
                    completions,
                    onSelect = { state.applyMarkdownCompletion(tab, it) },
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                val bounds = cursorBounds
                                if (bounds == null) {
                                    IntOffset.Zero
                                } else {
                                    IntOffset(bounds.left.roundToInt(), bounds.bottom.roundToInt())
                                }
                            },
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
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        modifier =
            modifier.widthIn(min = 156.dp, max = 430.dp).border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape
                    .RoundedCornerShape(4.dp),
            ),
    ) {
        Column {
            completions.take(6).forEach { completion ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { onSelect(completion) }
                        .padding(horizontal = 9.dp),
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

private fun completionIcon(kind: MarkdownCompletionKind): ImageVector =
    when (kind) {
        MarkdownCompletionKind.WIKI_LINK -> Icons.Default.InsertLink
        MarkdownCompletionKind.BLOCK_REFERENCE -> Icons.Default.FormatQuote
        MarkdownCompletionKind.TAG -> Icons.Default.Tune
        MarkdownCompletionKind.PROPERTY -> Icons.Default.Tune
    }

private fun TextRange.coerceIn(length: Int): TextRange = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

@Composable
private fun MarkdownReader(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    val blocks = remember(tab.content) { MarkdownParser.blocks(tab.content) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val horizontalPadding = ((maxWidth - 840.dp) / 2).coerceAtLeast(36.dp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = horizontalPadding, vertical = 38.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(blocks) { block -> MarkdownBlockView(state, tab, block) }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    state: WorkbenchState,
    tab: DocumentTab,
    block: MarkdownBlock,
) {
    when (block) {
        is MarkdownBlock.HeadingBlock -> {
            MarkdownHeadingBlockView(state, tab, block)
        }

        is MarkdownBlock.Paragraph -> {
            MarkdownParagraphBlockView(state, tab, block)
        }

        is MarkdownBlock.Quote -> {
            MarkdownQuoteBlockView(state, tab, block)
        }

        is MarkdownBlock.ListItem -> {
            MarkdownListBlockView(state, tab, block)
        }

        is MarkdownBlock.CodeBlock -> {
            MarkdownCodeBlockView(block)
        }

        is MarkdownBlock.MathBlock -> {
            MarkdownMathBlockView(block)
        }

        MarkdownBlock.Divider -> {
            HorizontalDivider(
                Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun MarkdownHeadingBlockView(
    state: WorkbenchState,
    tab: DocumentTab,
    block: MarkdownBlock.HeadingBlock,
) {
    LinkedMarkdownText(
        state,
        tab,
        block.text,
        TextStyle(
            fontSize =
                when (block.level) {
                    1 -> 26.sp
                    2 -> 21.sp
                    3 -> 18.sp
                    else -> 15.sp
                },
            lineHeight =
                when (block.level) {
                    1 -> 34.sp
                    2 -> 29.sp
                    else -> 24.sp
                },
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun MarkdownParagraphBlockView(
    state: WorkbenchState,
    tab: DocumentTab,
    block: MarkdownBlock.Paragraph,
) {
    LinkedMarkdownText(state, tab, block.text, TextStyle(fontSize = 15.sp, lineHeight = 25.sp))
}

@Composable
private fun MarkdownQuoteBlockView(
    state: WorkbenchState,
    tab: DocumentTab,
    block: MarkdownBlock.Quote,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(12.dp),
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(11.dp))
        LinkedMarkdownText(
            state,
            tab,
            block.text,
            MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarkdownListBlockView(
    state: WorkbenchState,
    tab: DocumentTab,
    block: MarkdownBlock.ListItem,
) {
    Row {
        Text(
            if (block.ordered) "${block.ordinal ?: 1}." else "•",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(25.dp),
        )
        LinkedMarkdownText(
            state,
            tab,
            block.text,
            TextStyle(lineHeight = 23.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MarkdownCodeBlockView(block: MarkdownBlock.CodeBlock) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            block.language?.takeIf(String::isNotBlank)?.let { language ->
                Text(language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(block.code, fontFamily = CodeFont, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun MarkdownMathBlockView(block: MarkdownBlock.MathBlock) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                block.source,
                fontFamily = CodeFont,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
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
    val rendered = remember(text) { inlineMarkdown(text) }
    ClickableText(
        text = rendered,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            rendered
                .getStringAnnotations("WIKI", offset, offset)
                .firstOrNull()
                ?.item
                ?.let { target -> state.navigateWikiLink(tab.relativePath, target) }
        },
    )
}

private fun inlineMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        val token =
            Regex(
                "!?\\[\\[([^]]+)]]|\\*\\*([^*]+)\\*\\*|(?<!\\*)\\*([^*]+)\\*(?!\\*)|" +
                    "(?<![\\p{L}\\p{N}_#])#[\\p{L}\\p{N}_/-]+",
            )
        var cursor = 0
        token.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            when {
                match.value.contains("[[") -> {
                    val rawTarget = match.groupValues[1]
                    val target = rawTarget.split('#', '^', '|', limit = 2).first().trim()
                    val label = rawTarget.substringAfter('|', target).trim().ifBlank { target }
                    val start = length
                    append(label)
                    addStyle(SpanStyle(color = Color(0xFF2F6758), fontWeight = FontWeight.Medium), start, length)
                    addStringAnnotation("WIKI", target, start, length)
                }

                match.groupValues[2].isNotEmpty() -> {
                    val start = length
                    append(match.groupValues[2])
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }

                match.groupValues[3].isNotEmpty() -> {
                    val start = length
                    append(match.groupValues[3])
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }

                else -> {
                    val start = length
                    append(match.value)
                    addStyle(SpanStyle(color = Color(0xFF4B6288)), start, length)
                }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }

@Composable
private fun TextDocument(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    var value by remember(tab) { mutableStateOf(TextFieldValue(tab.content, tab.cursor)) }
    LaunchedEffect(tab.content) { if (value.text != tab.content) value = TextFieldValue(tab.content, tab.cursor.coerceIn(tab.content.length)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("纯文本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            EditorTool(Icons.Default.Save, "保存", { state.save(tab) })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BasicTextField(
            value,
            {
                value = it
                state.updateContent(tab, it.text, it.selection)
            },
            Modifier.fillMaxSize().padding(22.dp),
            textStyle =
                TextStyle(
                    fontFamily = CodeFont,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush =
                androidx.compose.ui.graphics
                    .SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ImageDocument(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    val relativePath = tab.relativePath
    var resolvedPath by remember(relativePath) { mutableStateOf<java.nio.file.Path?>(null) }
    var bitmap by remember(relativePath) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(relativePath) { mutableStateOf<String?>(null) }
    var zoom by remember(relativePath) { mutableStateOf(1f) }
    var pan by remember(relativePath) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(relativePath) {
        runCatching {
            withContext(Dispatchers.IO) {
                val path = state.resolveWorkspacePath(relativePath) ?: error("Image is no longer available.")
                path to
                    SkiaImage
                        .makeFromEncoded(
                            java.nio.file.Files
                                .readAllBytes(path),
                        ).toComposeImageBitmap()
            }
        }.onSuccess { (path, image) ->
            resolvedPath = path
            bitmap = image
        }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("图片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            EditorTool(Icons.Default.ZoomOut, "缩小", {
                zoom = (zoom - 0.1f).coerceAtLeast(0.2f)
                if (zoom <= 1f) pan = Offset.Zero
            })
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp))
            EditorTool(Icons.Default.ZoomIn, "放大", { zoom = (zoom + 0.1f).coerceAtMost(4f) })
            EditorTool(Icons.AutoMirrored.Filled.OpenInNew, "在系统中打开", { resolvedPath?.let { runCatching { Desktop.getDesktop().open(it.toFile()) } } })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFE8EBE9))
                .clipToBounds()
                .pointerInput(relativePath) {
                    detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                        val nextZoom = (zoom * gestureZoom).coerceIn(0.2f, 4f)
                        zoom = nextZoom
                        pan = if (nextZoom <= 1f) Offset.Zero else pan + gesturePan
                    }
                }.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                bitmap == null -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                else -> {
                    Image(
                        bitmap!!,
                        tab.displayName,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedDocument(
    state: WorkbenchState,
    tab: DocumentTab,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            Text("无法在工作台中预览 ${tab.displayName}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("文件保持原样，可使用系统应用打开。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    state.resolveWorkspacePath(tab.relativePath)?.toFile()?.let {
                        runCatching { Desktop.getDesktop().open(it) }
                    }
                },
            ) {
                Text("使用系统应用打开")
            }
        }
    }
}

@Composable
internal fun EditorTool(
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
            Icon(icon, description, modifier = Modifier.size(17.dp))
        }
    }
}

private fun documentIcon(kind: DocumentKind): ImageVector =
    when (kind) {
        DocumentKind.PDF -> Icons.Default.PictureAsPdf
        DocumentKind.IMAGE -> Icons.Default.Image
        DocumentKind.AGENT -> Icons.Default.SmartToy
        else -> Icons.Default.Description
    }
