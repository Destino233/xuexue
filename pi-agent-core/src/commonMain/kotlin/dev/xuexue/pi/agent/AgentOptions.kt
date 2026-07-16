package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.PayloadTransform
import dev.xuexue.pi.ai.ResponseObserver
import dev.xuexue.pi.ai.ThinkingBudgets
import dev.xuexue.pi.ai.Transport

typealias MessageConverter = suspend (messages: List<AgentMessage>) -> List<Message>
typealias ContextTransformer = suspend (messages: List<AgentMessage>, signal: AbortSignal?) -> List<AgentMessage>
typealias ApiKeyResolver = suspend (provider: String) -> String?
typealias BeforeToolCallHook = suspend (context: BeforeToolCallContext, signal: AbortSignal?) -> BeforeToolCallResult?
typealias AfterToolCallHook = suspend (context: AfterToolCallContext, signal: AbortSignal?) -> AfterToolCallResult?
typealias LegacyPrepareNextTurn = suspend (signal: AbortSignal?) -> AgentLoopTurnUpdate?
typealias ContextualPrepareNextTurn =
    suspend (context: ShouldStopAfterTurnContext, signal: AbortSignal?) -> AgentLoopTurnUpdate?

data class AgentOptions(
    val initialState: AgentInitialState? = null,
    val convertToLlm: MessageConverter? = null,
    val transformContext: ContextTransformer? = null,
    val streamFunction: StreamFunction? = null,
    val getApiKey: ApiKeyResolver? = null,
    val onPayload: PayloadTransform? = null,
    val onResponse: ResponseObserver? = null,
    val beforeToolCall: BeforeToolCallHook? = null,
    val afterToolCall: AfterToolCallHook? = null,
    val prepareNextTurn: LegacyPrepareNextTurn? = null,
    val prepareNextTurnWithContext: ContextualPrepareNextTurn? = null,
    val steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val sessionId: String? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val transport: Transport = Transport.AUTO,
    val maxRetryDelayMs: Long? = null,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
)
