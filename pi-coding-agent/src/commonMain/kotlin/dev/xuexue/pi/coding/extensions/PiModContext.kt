package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AfterToolCallContext
import dev.xuexue.pi.agent.AfterToolCallResult
import dev.xuexue.pi.agent.BeforeToolCallContext
import dev.xuexue.pi.agent.BeforeToolCallResult
import dev.xuexue.pi.agent.ContextTransformer
import dev.xuexue.pi.agent.ContextualPrepareNextTurn
import dev.xuexue.pi.agent.ShouldStopAfterTurnContext
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.coding.AgentSessionState
import dev.xuexue.pi.coding.CustomMessageInput
import dev.xuexue.pi.coding.CustomMessageOptions
import dev.xuexue.pi.coding.StreamingBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement

/** Context shared by all capability registries exposed to a running mod. */
interface ModContext {
    val mod: ModInfo

    /** Scope cancelled automatically when this mod run stops. */
    val scope: CoroutineScope

    val events: EventRegistry
    val tools: ToolRegistry
    val commands: CommandRegistry
    val providers: ProviderRegistry
    val imageProviders: ImageProviderRegistry
    val resources: ResourceRegistry
    val session: SessionApi
    val runtime: RuntimeRegistry
    val ui: UiRegistry
    val storage: StorageApi
    val permissions: PermissionBroker
    val diagnostics: DiagnosticsRegistry
}

/** Event registration surface shared by JVM and process-backed mods. */
interface EventRegistry {
    fun on(
        event: PiExtensionEventType,
        handler: PiExtensionEventHandler,
    ): Registration

    fun on(
        channel: String,
        handler: (Any?) -> Unit,
    ): Registration

    fun emit(
        channel: String,
        data: Any? = null,
    )
}

/** Session operations intentionally expose Agent messages rather than UI DTOs. */
interface SessionApi {
    val state: AgentSessionState?
    val isIdle: Boolean

    suspend fun prompt(
        text: String,
        images: List<dev.xuexue.pi.ai.ImageContent> = emptyList(),
        behavior: StreamingBehavior? = null,
    )

    suspend fun sendUserMessage(
        text: String,
        options: CustomMessageOptions = CustomMessageOptions(),
    )

    suspend fun sendCustomMessage(
        message: CustomMessageInput,
        options: CustomMessageOptions = CustomMessageOptions(),
    )

    fun steer(message: AgentMessage)

    fun followUp(message: AgentMessage)

    suspend fun abort()

    fun appendCustomEntry(message: CustomMessageInput): String?
}

/** Agent runtime phases that can be extended without replacing the whole loop. */
interface RuntimeRegistry {
    fun registerContextTransformer(
        id: String,
        transformer: ContextTransformer,
        priority: Int = 0,
    ): Registration

    fun registerBeforeToolCall(
        id: String,
        hook: suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?,
        priority: Int = 0,
    ): Registration

    fun registerAfterToolCall(
        id: String,
        hook: suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?,
        priority: Int = 0,
    ): Registration

    fun registerContinuationPolicy(
        id: String,
        policy: suspend (ShouldStopAfterTurnContext) -> Boolean,
        priority: Int = 0,
    ): Registration

    fun registerPrepareNextTurn(
        id: String,
        hook: ContextualPrepareNextTurn,
        priority: Int = 0,
    ): Registration
}

/** UI contributions are data models; Compose renders them in the host. */
interface UiRegistry {
    fun registerPanel(contribution: UiPanelContribution): Registration

    fun registerAction(contribution: UiActionContribution): Registration

    fun panels(): List<RegisteredModEntry<UiPanelContribution>>

    fun actions(): List<RegisteredModEntry<UiActionContribution>>
}

interface StorageApi {
    suspend fun read(key: String): JsonElement?

    suspend fun write(
        key: String,
        value: JsonElement,
    )

    suspend fun remove(key: String)
}

enum class PermissionDecision {
    GRANTED,
    DENIED,
    UNDECIDED,
}

interface PermissionBroker {
    fun check(permission: String): PermissionDecision

    suspend fun request(permission: String): PermissionDecision
}

data class ModDiagnostic(
    val runId: ModRunId,
    val severity: ModDiagnosticSeverity,
    val message: String,
    val details: JsonElement? = null,
)

enum class ModDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

interface DiagnosticsRegistry {
    fun report(
        severity: ModDiagnosticSeverity,
        message: String,
        details: JsonElement? = null,
    )

    fun entries(): List<ModDiagnostic>
}

enum class UiSlot {
    LEFT_DOCK,
    RIGHT_DOCK,
    BOTTOM_DOCK,
    TOOLBAR,
    COMMAND_PALETTE,
    STATUS_BAR,
    AGENT_MESSAGE,
}

data class UiPanelContribution(
    val id: String,
    val title: String,
    val slot: UiSlot,
    val icon: String? = null,
    val order: Int = 0,
    val state: JsonElement? = null,
)

data class UiActionContribution(
    val id: String,
    val label: String,
    val location: UiSlot,
    val icon: String? = null,
    val order: Int = 0,
    val enabled: Boolean = true,
    val invoke: suspend (ModContext) -> Unit,
)
