package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.ToolResultMessage
import kotlin.time.Clock

internal suspend fun emitToolExecutionEnd(
    finalized: FinalizedToolCallOutcome,
    emit: AgentEventSink,
) {
    emit.emit(
        AgentEvent.ToolExecutionEnd(
            finalized.toolCall.id,
            finalized.toolCall.name,
            finalized.result,
            finalized.isError,
        ),
    )
}

internal fun createToolResultMessage(finalized: FinalizedToolCallOutcome): ToolResultMessage =
    ToolResultMessage(
        toolCallId = finalized.toolCall.id,
        toolName = finalized.toolCall.name,
        content = finalized.result.content,
        details = finalized.result.details,
        addedToolNames = finalized.result.addedToolNames?.takeIf(List<String>::isNotEmpty),
        isError = finalized.isError,
        timestamp = Clock.System.now().toEpochMilliseconds(),
    )

internal suspend fun emitToolResultMessage(
    message: ToolResultMessage,
    emit: AgentEventSink,
) {
    emit.emit(AgentEvent.MessageStart(message))
    emit.emit(AgentEvent.MessageEnd(message))
}
