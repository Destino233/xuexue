package dev.xuexue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.xuexue.core.DocumentKind
import dev.xuexue.core.EditorMode
import dev.xuexue.core.PiAgentMessage
import dev.xuexue.platform.AgentMessage
import dev.xuexue.platform.PdfOutlineDocument
import kotlinx.serialization.Serializable
import java.util.Locale

const val AGENT_DOCUMENT_PATH = "xuexue://agent"

class DocumentTab(
    relativePath: String,
    val kind: DocumentKind,
    content: String,
    pinned: Boolean,
    modifiedAt: Long = 0L,
) {
    var relativePath by mutableStateOf(relativePath)
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var mode by mutableStateOf(if (kind == DocumentKind.MARKDOWN) EditorMode.EDIT else EditorMode.SOURCE)
    var pinned by mutableStateOf(pinned)
    var cursor by mutableStateOf(TextRange.Zero)
    var pdfPage by mutableStateOf(0)
    var pdfScrollOffset by mutableStateOf(0)
    var pdfHorizontalOffset by mutableStateOf(0)
    var pdfVerticalOffset by mutableStateOf(0)
    var pdfNavigationSerial by mutableStateOf(0)
    var pdfNavigationTopNormalized by mutableStateOf<Float?>(null)
    var pdfZoom by mutableStateOf(1f)
    var pdfFitMode by mutableStateOf("WIDTH")
    var pdfViewMode by mutableStateOf("CONTINUOUS")
    var pdfSidePanel by mutableStateOf("THUMBNAILS")
    var pdfRotation by mutableStateOf(0f)
    var pdfColorMode by mutableStateOf("NORMAL")
    var pdfOutline by mutableStateOf<PdfOutlineDocument?>(null)
    var pdfStaleOutline by mutableStateOf<PdfOutlineDocument?>(null)
    var pdfOutlineError by mutableStateOf<String?>(null)
    var pdfOutlineAnalyzing by mutableStateOf(false)
    var lastKnownModified by mutableStateOf(modifiedAt)
    var externalContent by mutableStateOf<String?>(null)
    var externalDeleted by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var activeNodeType by mutableStateOf("paragraph")
    var editorBlockIndex by mutableStateOf(0)
    val pdfAnnotations = mutableStateListOf<PdfAnnotation>()
    val undo = ArrayDeque<String>()
    val redo = ArrayDeque<String>()

    val displayName: String get() = if (kind == DocumentKind.AGENT) "Agent" else relativePath.substringAfterLast('/')
    val dirty: Boolean get() = content != savedContent
}

@Serializable
data class PdfAnnotation(
    val id: Long,
    val page: Int,
    val kind: String,
    val text: String,
    val regions: List<PdfHighlightRegion> = emptyList(),
)

@Serializable
data class PdfHighlightRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

class AgentSessionState(
    val id: String,
    name: String,
    messages: List<AgentMessage> = emptyList(),
    protocolMessages: List<PiAgentMessage> = emptyList(),
    inputTokens: Int = 0,
    outputTokens: Int = 0,
    costUsd: Double? = null,
) {
    var name by mutableStateOf(name)
    val messages = mutableStateListOf<AgentMessage>().apply { addAll(messages) }
    val protocolMessages = mutableStateListOf<PiAgentMessage>().apply { addAll(protocolMessages) }
    var inputTokens by mutableStateOf(inputTokens)
    var outputTokens by mutableStateOf(outputTokens)
    var costUsd by mutableStateOf(costUsd)
}

@Serializable
data class PersistedAgentSession(
    val id: String,
    val name: String,
    val messages: List<AgentMessage>,
    val protocolMessages: List<PiAgentMessage> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double? = null,
)

data class WorkspaceDragPayload(
    val relativePath: String,
    val displayName: String,
)

data class AgentContextSnippet(
    val id: Long,
    val title: String,
    val content: String,
)

enum class ToolPanelDock { LEFT, RIGHT, BOTTOM, FLOAT }

enum class DocumentSplit { NONE, VERTICAL, HORIZONTAL }

enum class DocumentDropTarget { PRIMARY, RIGHT, BOTTOM, DETACH }

enum class BuiltInLayoutPreset { READING, WRITING, AGENT }

internal enum class RemotePdfDownloadPhase { DOWNLOADING, PAUSED, FAILED }

internal data class RemotePdfDownloadState(
    val relativePath: String,
    val sourceUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val phase: RemotePdfDownloadPhase,
    val error: String? = null,
) {
    val progress: Float
        get() =
            if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }

    val byteProgress: String
        get() = "${formatDownloadBytes(downloadedBytes)} / ${formatDownloadBytes(totalBytes)}"
}

private fun formatDownloadBytes(value: Long): String =
    when {
        value >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", value / (1024f * 1024f))
        value >= 1024L -> String.format(Locale.ROOT, "%.1f KB", value / 1024f)
        else -> "$value B"
    }
