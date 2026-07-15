package dev.xuexue.core

/** Platform-neutral layout decisions shared by Desktop and future mobile shells. */
enum class WorkbenchWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun workbenchWidthClass(widthDp: Float): WorkbenchWidthClass =
    when {
        widthDp < 900f -> WorkbenchWidthClass.COMPACT
        widthDp < 1_200f -> WorkbenchWidthClass.MEDIUM
        else -> WorkbenchWidthClass.EXPANDED
    }

enum class CompactWorkbenchPage(
    val toolWindow: ToolWindow? = null,
) {
    DOCUMENT,
    FILES(ToolWindow.FILES),
    SEARCH(ToolWindow.SEARCH),
    LINKS(ToolWindow.BACKLINKS),
    OUTLINE(ToolWindow.OUTLINE),
    PROPERTIES(ToolWindow.PROPERTIES),
    GRAPH(ToolWindow.GRAPH),
    TASKS(ToolWindow.TASKS),
    AGENT,
}

enum class WorkbenchFocusTarget {
    ACTIVITY_RAIL,
    TOOL_WINDOW,
    DOCUMENT,
    AGENT,
}

enum class DocumentSplitDirection {
    RIGHT,
    BOTTOM,
}

enum class WorkbenchAuxiliaryPanel {
    NONE,
    TOOL_WINDOW,
    AGENT,
}

fun mediumAuxiliaryPanel(
    toolWindowVisible: Boolean,
    agentVisible: Boolean,
): WorkbenchAuxiliaryPanel =
    when {
        agentVisible -> WorkbenchAuxiliaryPanel.AGENT
        toolWindowVisible -> WorkbenchAuxiliaryPanel.TOOL_WINDOW
        else -> WorkbenchAuxiliaryPanel.NONE
    }

fun initialCompactPage(hasActiveDocument: Boolean): CompactWorkbenchPage =
    when (hasActiveDocument) {
        true -> CompactWorkbenchPage.DOCUMENT
        false -> CompactWorkbenchPage.FILES
    }

/** One command identity is used by shortcuts, toolbars, menus, and the command palette. */
sealed interface WorkbenchCommand {
    data object NewMarkdown : WorkbenchCommand

    data object SaveDocument : WorkbenchCommand

    data object Undo : WorkbenchCommand

    data object Redo : WorkbenchCommand

    data object RefreshWorkspace : WorkbenchCommand

    data object NextDocument : WorkbenchCommand

    data object PreviousDocument : WorkbenchCommand

    data object ShowAgent : WorkbenchCommand

    data object OpenAgentDocument : WorkbenchCommand

    data class OpenTool(
        val tool: ToolWindow,
    ) : WorkbenchCommand

    data class EditMarkdown(
        val command: EditorCommand,
    ) : WorkbenchCommand

    data class Focus(
        val target: WorkbenchFocusTarget,
    ) : WorkbenchCommand

    data class SelectDocument(
        val relativePath: String,
    ) : WorkbenchCommand

    data class PinDocument(
        val relativePath: String,
    ) : WorkbenchCommand

    data class SplitDocument(
        val relativePath: String,
        val direction: DocumentSplitDirection,
    ) : WorkbenchCommand

    data class DetachDocument(
        val relativePath: String,
    ) : WorkbenchCommand

    data object ClearDocumentSplit : WorkbenchCommand

    data class OpenWorkspaceEntry(
        val relativePath: String,
        val pinned: Boolean = false,
    ) : WorkbenchCommand

    data class CreateWorkspaceEntry(
        val parentPath: String,
        val name: String,
        val directory: Boolean,
    ) : WorkbenchCommand

    data class RenameWorkspaceEntry(
        val relativePath: String,
        val newName: String,
    ) : WorkbenchCommand

    data class BatchRenameWorkspaceEntries(
        val renames: Map<String, String>,
    ) : WorkbenchCommand

    data class DeleteWorkspaceEntries(
        val relativePaths: List<String>,
    ) : WorkbenchCommand

    data class CopyWorkspaceEntries(
        val relativePaths: List<String>,
        val targetDirectory: String,
    ) : WorkbenchCommand

    data class MoveWorkspaceEntries(
        val relativePaths: List<String>,
        val targetDirectory: String,
    ) : WorkbenchCommand

    data class AddAgentContext(
        val relativePath: String,
    ) : WorkbenchCommand
}
