package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.ai.ToolResultContent
import dev.xuexue.pi.coding.AgentSessionState
import dev.xuexue.pi.coding.InteractionPort
import dev.xuexue.pi.coding.NoOpInteractionPort
import dev.xuexue.pi.coding.exec.PiCommandExecutor
import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import dev.xuexue.pi.coding.resources.ResourceSourceInfo
import dev.xuexue.pi.coding.resources.syntheticResourceSourceInfo
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.SessionEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class PiExtensionMode {
    TUI,
    RPC,
    JSON,
    PRINT,
}

enum class PiExtensionEventType {
    PROJECT_TRUST,
    RESOURCES_DISCOVER,
    SESSION_START,
    SESSION_SHUTDOWN,
    SESSION_BEFORE_SWITCH,
    SESSION_BEFORE_FORK,
    SESSION_BEFORE_TREE,
    SESSION_TREE,
    CONTEXT,
    BEFORE_AGENT_START,
    AGENT_START,
    AGENT_END,
    AGENT_SETTLED,
    TURN_START,
    TURN_END,
    MESSAGE_START,
    MESSAGE_UPDATE,
    MESSAGE_END,
    TOOL_EXECUTION_START,
    TOOL_EXECUTION_UPDATE,
    TOOL_EXECUTION_END,
    TOOL_CALL,
    TOOL_RESULT,
    COMMAND,
}

sealed interface PiExtensionEvent {
    val type: PiExtensionEventType

    data class ProjectTrust(
        val cwd: String,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.PROJECT_TRUST
    }

    data class ResourcesDiscover(
        val cwd: String,
        val reason: ResourceDiscoverReason,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.RESOURCES_DISCOVER
    }

    data class SessionStart(
        val reason: SessionStartReason,
        val previousSessionFile: String? = null,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_START
    }

    data class SessionShutdown(
        val reason: SessionShutdownReason,
        val targetSessionFile: String? = null,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_SHUTDOWN
    }

    data class SessionBeforeSwitch(
        val reason: String,
        val targetSessionFile: String? = null,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_BEFORE_SWITCH
    }

    data class SessionBeforeFork(
        val entryId: String,
        val position: String,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_BEFORE_FORK
    }

    data class SessionBeforeTree(
        val preparation: TreePreparation,
        val signal: AbortSignal,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_BEFORE_TREE
    }

    data class SessionTree(
        val newLeafId: String?,
        val oldLeafId: String?,
        val summaryEntry: BranchSummaryEntry? = null,
        val fromExtension: Boolean? = null,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.SESSION_TREE
    }

    data class Context(
        val messages: List<AgentMessage>,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.CONTEXT
    }

    data class BeforeAgentStart(
        val prompt: String,
        val systemPrompt: String,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.BEFORE_AGENT_START
    }

    data object AgentStart : PiExtensionEvent {
        override val type = PiExtensionEventType.AGENT_START
    }

    data class AgentEnd(
        val messages: List<AgentMessage>,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.AGENT_END
    }

    data object AgentSettled : PiExtensionEvent {
        override val type = PiExtensionEventType.AGENT_SETTLED
    }

    data object TurnStart : PiExtensionEvent {
        override val type = PiExtensionEventType.TURN_START
    }

    data class TurnEnd(
        val message: AgentMessage,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TURN_END
    }

    data class MessageStart(
        val message: AgentMessage,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.MESSAGE_START
    }

    data class MessageUpdate(
        val message: AgentMessage,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.MESSAGE_UPDATE
    }

    data class MessageEnd(
        val message: AgentMessage,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.MESSAGE_END
    }

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TOOL_EXECUTION_START
    }

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TOOL_EXECUTION_UPDATE
    }

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TOOL_EXECUTION_END
    }

    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TOOL_CALL
    }

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        var content: List<ToolResultContent>,
        var details: JsonElement?,
        var isError: Boolean,
    ) : PiExtensionEvent {
        override val type = PiExtensionEventType.TOOL_RESULT
    }
}

enum class ResourceDiscoverReason {
    STARTUP,
    RELOAD,
}

enum class SessionStartReason {
    STARTUP,
    RELOAD,
    NEW,
    RESUME,
    FORK,
}

enum class SessionShutdownReason {
    QUIT,
    RELOAD,
    NEW,
    RESUME,
    FORK,
}

sealed interface PiExtensionEventResult {
    data class ProjectTrust(
        val trusted: ProjectTrustDecision,
        val remember: Boolean = false,
    ) : PiExtensionEventResult

    data class SessionBeforeSwitch(
        val cancel: Boolean = false,
    ) : PiExtensionEventResult

    data class SessionBeforeFork(
        val cancel: Boolean = false,
    ) : PiExtensionEventResult

    data class SessionBeforeTree(
        val cancel: Boolean = false,
        val summary: ExtensionBranchSummary? = null,
        val customInstructions: String? = null,
        val replaceInstructions: Boolean? = null,
        val label: String? = null,
    ) : PiExtensionEventResult

    data class Resources(
        val paths: ResourceExtensionPaths,
    ) : PiExtensionEventResult

    data class Context(
        val messages: List<AgentMessage>,
    ) : PiExtensionEventResult

    data class BeforeAgentStart(
        val message: ExtensionMessage? = null,
        val systemPrompt: String? = null,
    ) : PiExtensionEventResult

    data class ToolCall(
        val block: Boolean = false,
        val reason: String? = null,
    ) : PiExtensionEventResult

    data class ToolResult(
        val content: List<ToolResultContent>? = null,
        val details: JsonElement? = null,
        val isError: Boolean? = null,
    ) : PiExtensionEventResult
}

data class TreePreparation(
    val targetId: String,
    val oldLeafId: String?,
    val commonAncestorId: String?,
    val entriesToSummarize: List<SessionEntry>,
    val userWantsSummary: Boolean,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean? = null,
    val label: String? = null,
)

data class ExtensionBranchSummary(
    val summary: String,
    val details: JsonElement? = null,
)

enum class ProjectTrustDecision {
    YES,
    NO,
    UNDECIDED,
}

data class ExtensionMessage(
    val customType: String,
    val content: dev.xuexue.pi.ai.UserContent,
    val display: Boolean = true,
    val details: JsonElement? = null,
)

data class PiExtensionError(
    val extensionId: String,
    val event: PiExtensionEventType,
    val message: String,
)

data class PiExtensionContext(
    val cwd: String,
    val mode: PiExtensionMode,
    val isProjectTrusted: () -> Boolean = { true },
    val isIdle: () -> Boolean = { true },
    val abort: () -> Unit = {},
    val hasPendingMessages: () -> Boolean = { false },
    val shutdown: () -> Unit = {},
    val getSystemPrompt: () -> String = { "" },
    val getSessionState: () -> AgentSessionState? = { null },
    val reload: suspend () -> Unit = {},
    val interaction: InteractionPort = NoOpInteractionPort,
)

fun interface PiExtensionEventHandler {
    suspend fun handle(
        event: PiExtensionEvent,
        context: PiExtensionContext,
    ): PiExtensionEventResult?
}

data class PiExtensionCommand(
    val name: String,
    val description: String? = null,
    val handler: suspend (args: String, context: PiExtensionContext) -> Unit,
)

data class RegisteredExtensionCommand(
    val invocationName: String,
    val command: PiExtensionCommand,
    val extensionId: String,
    val sourceInfo: ResourceSourceInfo = syntheticResourceSourceInfo("<extension>"),
)

interface PiExtensionApi {
    fun on(
        event: PiExtensionEventType,
        handler: PiExtensionEventHandler,
    )

    fun registerTool(tool: AgentTool)

    fun registerProvider(provider: PiProvider)

    fun registerImageProvider(provider: PiImageProvider)

    fun registerCommand(command: PiExtensionCommand)

    fun contributeResources(paths: ResourceExtensionPaths)

    /** Shared custom event channels; subscriptions are scoped to this extension. */
    val events: PiEventBus

    /** Executes a program without implicitly invoking a shell. */
    val exec: PiCommandExecutor
}

fun interface PiExtensionHandle {
    suspend fun dispose()
}

interface PiExtension {
    val id: String

    /** Source metadata is attached to all extension-owned commands and tools. */
    val sourceInfo: ResourceSourceInfo
        get() = syntheticResourceSourceInfo("<$id>", source = "extension:$id")

    suspend fun activate(api: PiExtensionApi): PiExtensionHandle?
}

class KotlinPiExtension(
    override val id: String,
    override val sourceInfo: ResourceSourceInfo = syntheticResourceSourceInfo("<$id>", source = "extension:$id"),
    private val activateBlock: suspend PiExtensionApi.() -> PiExtensionHandle? = { null },
) : PiExtension {
    override suspend fun activate(api: PiExtensionApi): PiExtensionHandle? = api.activateBlock()
}
