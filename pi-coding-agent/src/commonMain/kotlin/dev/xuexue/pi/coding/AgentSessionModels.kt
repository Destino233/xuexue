package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentState
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.UserContent
import dev.xuexue.pi.coding.compaction.CompactionSettings
import dev.xuexue.pi.coding.extensions.PiExtensionMode
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.PiModLoader
import dev.xuexue.pi.coding.packages.PiPackageManager
import dev.xuexue.pi.coding.resources.DefaultResourceLoader
import dev.xuexue.pi.coding.resources.SystemPromptDocumentation
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/** Platform capability used by the headless bash command and the Pi bash tool. */
interface AgentSessionBashExecutor {
    suspend fun execute(
        command: String,
        cwd: String,
        excludeFromContext: Boolean? = null,
    ): BashResult

    fun abort()
}

data class AgentSessionConfig(
    val agent: dev.xuexue.pi.agent.Agent,
    val sessionManager: SessionManager,
    val settingsManager: AgentSessionSettings = InMemoryAgentSessionSettings(),
    val resourceLoader: DefaultResourceLoader? = null,
    val extensionRunner: PiExtensionRunner? = null,
    val modLoader: PiModLoader? = null,
    val packageManager: PiPackageManager? = null,
    val bashExecutor: AgentSessionBashExecutor? = null,
    val extensionMode: PiExtensionMode = PiExtensionMode.PRINT,
    val interactionPort: InteractionPort = NoOpInteractionPort,
    val systemPromptDocumentation: SystemPromptDocumentation =
        SystemPromptDocumentation(
            readmePath = "README.md",
            docsPath = "docs",
            examplesPath = "examples",
        ),
    val currentDate: () -> String = {
        Clock.System
            .now()
            .toString()
            .take(10)
    },
    val htmlExporter: AgentSessionHtmlExporter? = null,
    /** Model registry used for authenticated model discovery and cycling. */
    val modelRegistry: ModelRegistry? = null,
    /** Ordered model scope used by Pi's --models/cycle-model behavior. */
    val scopedModels: List<ScopedModel> = emptyList(),
)

/** A model in the ordered set used by `cycle_model`. */
data class ScopedModel(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel? = null,
)

/** Result returned after a model cycle succeeds. */
data class ModelCycleResult(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel,
    val isScoped: Boolean,
)

enum class ModelCycleDirection {
    FORWARD,
    BACKWARD,
}

data class RetrySettings(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 2_000,
)

data class BranchSummarySettings(
    val reserveTokens: Long = 16_384,
    val skipPrompt: Boolean = false,
)

interface AgentSessionSettings {
    fun getRetrySettings(): RetrySettings

    fun setRetryEnabled(enabled: Boolean)

    fun getCompactionSettings(): CompactionSettings

    fun setCompactionEnabled(enabled: Boolean)

    fun getBranchSummarySettings(): BranchSummarySettings = BranchSummarySettings()
}

class InMemoryAgentSessionSettings(
    retrySettings: RetrySettings = RetrySettings(),
    compactionSettings: CompactionSettings = CompactionSettings(),
    branchSummarySettings: BranchSummarySettings = BranchSummarySettings(),
) : AgentSessionSettings {
    private var retrySettings = retrySettings
    private var compactionSettings = compactionSettings
    private val branchSummarySettings = branchSummarySettings

    override fun getRetrySettings(): RetrySettings = retrySettings

    override fun setRetryEnabled(enabled: Boolean) {
        retrySettings = retrySettings.copy(enabled = enabled)
    }

    override fun getCompactionSettings(): CompactionSettings = compactionSettings

    override fun setCompactionEnabled(enabled: Boolean) {
        compactionSettings = compactionSettings.copy(enabled = enabled)
    }

    override fun getBranchSummarySettings(): BranchSummarySettings = branchSummarySettings
}

enum class StreamingBehavior {
    STEER,
    FOLLOW_UP,
}

data class PromptOptions(
    val images: List<ImageContent> = emptyList(),
    val streamingBehavior: StreamingBehavior? = null,
    val preflightResult: ((Boolean) -> Unit)? = null,
    val expandPromptTemplates: Boolean = true,
)

enum class CustomMessageDelivery {
    STEER,
    FOLLOW_UP,
    NEXT_TURN,
}

data class CustomMessageInput(
    val customType: String,
    val content: UserContent,
    val display: Boolean,
    val details: JsonElement? = null,
)

data class CustomMessageOptions(
    val triggerTurn: Boolean = false,
    val deliverAs: CustomMessageDelivery? = null,
)

data class ClearedAgentQueue(
    val steering: List<String>,
    val followUp: List<String>,
)

data class BashResult(
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean,
    val truncated: Boolean,
    val fullOutputPath: String? = null,
)

data class AgentSessionState(
    val agent: AgentState,
    val isStreaming: Boolean,
    val steeringMessages: List<String>,
    val followUpMessages: List<String>,
    val sessionFile: String?,
    val sessionId: String,
    val sessionName: String?,
    val retryAttempt: Int,
    val isRetrying: Boolean,
    val isCompacting: Boolean,
) {
    val isIdle: Boolean
        get() = !isStreaming

    val pendingMessageCount: Int
        get() = steeringMessages.size + followUpMessages.size
}

data class SessionTokenStats(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val total: Long,
)

data class ContextUsage(
    val tokens: Long?,
    val contextWindow: Long,
    val percent: Double?,
)

data class SessionStats(
    val sessionFile: String?,
    val sessionId: String,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val toolResults: Int,
    val totalMessages: Int,
    val tokens: SessionTokenStats,
    val cost: Double,
    val contextUsage: ContextUsage?,
)

data class ForkableUserMessage(
    val entryId: String,
    val text: String,
)

data class NavigateTreeOptions(
    val summarize: Boolean = false,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean? = null,
    val label: String? = null,
)

data class NavigateTreeResult(
    val editorText: String? = null,
    val cancelled: Boolean,
    val aborted: Boolean? = null,
    val summaryEntry: BranchSummaryEntry? = null,
)

data class ParsedSkillBlock(
    val name: String,
    val location: String,
    val content: String,
    val userMessage: String?,
)

fun parseSkillBlock(text: String): ParsedSkillBlock? {
    val match = SKILL_BLOCK.matchEntire(text) ?: return null
    return ParsedSkillBlock(
        name = match.groupValues[1],
        location = match.groupValues[2],
        content = match.groupValues[3],
        userMessage =
            match.groups[4]
                ?.value
                ?.trim()
                ?.ifEmpty { null },
    )
}

private val SKILL_BLOCK =
    Regex(
        """^<skill name="([^"]+)" location="([^"]+)">\n([\s\S]*?)\n</skill>(?:\n\n([\s\S]+))?$""",
    )

internal fun AgentSessionConfig.initialState(isStreaming: Boolean = false): AgentSessionState =
    AgentSessionState(
        agent = agent.currentState,
        isStreaming = isStreaming,
        steeringMessages = emptyList(),
        followUpMessages = emptyList(),
        sessionFile = sessionManager.getSessionFile(),
        sessionId = sessionManager.getSessionId(),
        sessionName = sessionManager.getSessionName(),
        retryAttempt = 0,
        isRetrying = false,
        isCompacting = false,
    )

internal val AgentSessionState.model: Model
    get() = agent.model
