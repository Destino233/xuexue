package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.ModelThinkingLevel

data class AgentState(
    val systemPrompt: String,
    val model: Model,
    val thinkingLevel: ModelThinkingLevel,
    val tools: List<AgentTool>,
    val messages: List<AgentMessage>,
    val isStreaming: Boolean,
    val streamingMessage: AgentMessage?,
    val pendingToolCalls: Set<String>,
    val errorMessage: String?,
    val queuedSteeringMessages: Int,
    val queuedFollowUpMessages: Int,
)

data class AgentInitialState(
    val systemPrompt: String? = null,
    val model: Model? = null,
    val thinkingLevel: ModelThinkingLevel? = null,
    val tools: List<AgentTool>? = null,
    val messages: List<AgentMessage>? = null,
)

internal fun createInitialAgentState(initial: AgentInitialState?): AgentState =
    AgentState(
        systemPrompt = initial?.systemPrompt.orEmpty(),
        model = initial?.model ?: UNKNOWN_AGENT_MODEL,
        thinkingLevel = initial?.thinkingLevel ?: ModelThinkingLevel.OFF,
        tools = initial?.tools?.toList().orEmpty(),
        messages = initial?.messages?.toList().orEmpty(),
        isStreaming = false,
        streamingMessage = null,
        pendingToolCalls = emptySet(),
        errorMessage = null,
        queuedSteeringMessages = 0,
        queuedFollowUpMessages = 0,
    )

val UNKNOWN_AGENT_MODEL =
    Model(
        id = "unknown",
        name = "unknown",
        api = "unknown",
        provider = "unknown",
        baseUrl = "",
        reasoning = false,
        input = emptyList<ModelInput>(),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 0,
        maxTokens = 0,
    )
