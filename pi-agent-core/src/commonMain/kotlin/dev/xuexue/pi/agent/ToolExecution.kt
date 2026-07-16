@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicBoolean

internal suspend fun failTruncatedToolCalls(
    toolCalls: List<ToolCall>,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultMessage>()
    toolCalls.forEach { toolCall ->
        emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val message =
            "Tool call \"${toolCall.name}\" was not executed: the response hit the output token limit, " +
                "so its arguments may be truncated. Re-issue the tool call with complete arguments."
        val finalized = FinalizedToolCallOutcome(toolCall, createErrorToolResult(message), isError = true)
        emitToolExecutionEnd(finalized, emit)
        createToolResultMessage(finalized).also { result ->
            emitToolResultMessage(result, emit)
            messages += result
        }
    }
    return ExecutedToolCallBatch(messages, terminate = false)
}

internal suspend fun executeToolCalls(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
    val hasSequentialTool =
        toolCalls.any { call ->
            currentContext.tools?.find { it.name == call.name }?.executionMode == ToolExecutionMode.SEQUENTIAL
        }
    return if (config.toolExecution == ToolExecutionMode.SEQUENTIAL || hasSequentialTool) {
        executeSequential(currentContext, assistantMessage, toolCalls, config, signal, emit)
    } else {
        executeParallel(currentContext, assistantMessage, toolCalls, config, signal, emit)
    }
}

private suspend fun executeSequential(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val finalizedCalls = mutableListOf<FinalizedToolCallOutcome>()
    val messages = mutableListOf<ToolResultMessage>()
    for (toolCall in toolCalls) {
        emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val finalized = prepareAndExecute(context, assistantMessage, toolCall, config, signal, emit)
        emitToolExecutionEnd(finalized, emit)
        val message = createToolResultMessage(finalized)
        emitToolResultMessage(message, emit)
        finalizedCalls += finalized
        messages += message
        if (signal?.isAborted == true) break
    }
    return ExecutedToolCallBatch(messages, shouldTerminate(finalizedCalls))
}

private suspend fun executeParallel(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch =
    coroutineScope {
        val finalizedCalls = mutableListOf<suspend () -> FinalizedToolCallOutcome>()
        for (toolCall in toolCalls) {
            emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
            when (val preparation = prepareToolCall(context, assistantMessage, toolCall, config, signal)) {
                is ToolCallPreparation.Immediate -> {
                    val finalized = FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
                    emitToolExecutionEnd(finalized, emit)
                    finalizedCalls += { finalized }
                }

                is ToolCallPreparation.Prepared -> {
                    finalizedCalls +=
                        suspend {
                            val executed = executePreparedToolCall(preparation.value, signal, emit)
                            finalizeExecutedToolCall(
                                context,
                                assistantMessage,
                                preparation.value,
                                executed,
                                config,
                                signal,
                            ).also { emitToolExecutionEnd(it, emit) }
                        }
                }
            }
            if (signal?.isAborted == true) break
        }

        val outcomes = finalizedCalls.map { finalize -> async { finalize() } }.awaitAll()
        val messages =
            outcomes.map { finalized ->
                createToolResultMessage(finalized).also { emitToolResultMessage(it, emit) }
            }
        ExecutedToolCallBatch(messages, shouldTerminate(outcomes))
    }

private suspend fun prepareAndExecute(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCall: ToolCall,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): FinalizedToolCallOutcome =
    when (val preparation = prepareToolCall(context, assistantMessage, toolCall, config, signal)) {
        is ToolCallPreparation.Immediate -> {
            FinalizedToolCallOutcome(toolCall, preparation.result, preparation.isError)
        }

        is ToolCallPreparation.Prepared -> {
            val executed = executePreparedToolCall(preparation.value, signal, emit)
            finalizeExecutedToolCall(context, assistantMessage, preparation.value, executed, config, signal)
        }
    }

// Pi converts arbitrary validation and hook failures into tool-result errors.
@Suppress("TooGenericExceptionCaught")
private suspend fun prepareToolCall(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCall: ToolCall,
    config: AgentLoopConfig,
    signal: AbortSignal?,
): ToolCallPreparation {
    val tool =
        context.tools?.find { it.name == toolCall.name }
            ?: return immediateError("Tool ${toolCall.name} not found")
    return try {
        val preparedArguments = tool.prepareArguments?.invoke(toolCall.arguments) ?: toolCall.arguments
        val validatedArguments = tool.validateArguments(preparedArguments).toMutableMap()
        val beforeContext = BeforeToolCallContext(assistantMessage, toolCall, validatedArguments, context)
        val before = config.beforeToolCall(beforeContext, signal)
        when {
            signal?.isAborted == true -> {
                immediateError("Operation aborted")
            }

            before?.block == true -> {
                immediateError(before.reason ?: "Tool execution was blocked")
            }

            else -> {
                ToolCallPreparation.Prepared(
                    PreparedToolCall(toolCall, tool, JsonObject(validatedArguments)),
                )
            }
        }
    } catch (error: Throwable) {
        immediateError(error.message ?: error.toString())
    }
}

// Tool implementations are an untrusted boundary and may throw any Kotlin throwable.
@Suppress("TooGenericExceptionCaught")
private suspend fun executePreparedToolCall(
    prepared: PreparedToolCall,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ExecutedToolCallOutcome =
    coroutineScope {
        val acceptingUpdates = AtomicBoolean(true)
        val updateJobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            val result =
                prepared.tool.execute(
                    prepared.toolCall.id,
                    prepared.args,
                    signal,
                    AgentToolUpdateCallback { partialResult ->
                        if (acceptingUpdates.load()) {
                            updateJobs +=
                                launch {
                                    emit.emit(
                                        AgentEvent.ToolExecutionUpdate(
                                            prepared.toolCall.id,
                                            prepared.toolCall.name,
                                            prepared.toolCall.arguments,
                                            partialResult,
                                        ),
                                    )
                                }
                        }
                    },
                )
            acceptingUpdates.store(false)
            updateJobs.joinAll()
            ExecutedToolCallOutcome(result, isError = false)
        } catch (error: Throwable) {
            acceptingUpdates.store(false)
            updateJobs.joinAll()
            ExecutedToolCallOutcome(createErrorToolResult(error.message ?: error.toString()), isError = true)
        } finally {
            acceptingUpdates.store(false)
        }
    }

// Extension hooks follow Pi's catch-all error conversion contract.
@Suppress("TooGenericExceptionCaught")
private suspend fun finalizeExecutedToolCall(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    prepared: PreparedToolCall,
    executed: ExecutedToolCallOutcome,
    config: AgentLoopConfig,
    signal: AbortSignal?,
): FinalizedToolCallOutcome {
    var result = executed.result
    var isError = executed.isError
    try {
        val afterContext =
            AfterToolCallContext(
                assistantMessage,
                prepared.toolCall,
                prepared.args,
                result,
                isError,
                context,
            )
        config.afterToolCall(afterContext, signal)?.let { override ->
            result =
                result.copy(
                    content = override.content ?: result.content,
                    details = override.details ?: result.details,
                    terminate = override.terminate ?: result.terminate,
                )
            isError = override.isError ?: isError
        }
    } catch (error: Throwable) {
        result = createErrorToolResult(error.message ?: error.toString())
        isError = true
    }
    return FinalizedToolCallOutcome(prepared.toolCall, result, isError)
}
