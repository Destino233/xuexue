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

private data class PrioritizedHook<T>(
    val priority: Int,
    val handler: T,
)

/** Runtime hook catalogs shared by all active mod runs. */
class ModRuntimeCatalog {
    private val contextTransformers = ModOwnedRegistry<PrioritizedHook<ContextTransformer>>(ModContributionKind.RUNTIME)
    private val beforeToolCalls =
        ModOwnedRegistry<PrioritizedHook<suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?>>(
            ModContributionKind.RUNTIME,
        )
    private val afterToolCalls =
        ModOwnedRegistry<PrioritizedHook<suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?>>(
            ModContributionKind.RUNTIME,
        )
    private val continuationPolicies =
        ModOwnedRegistry<PrioritizedHook<suspend (ShouldStopAfterTurnContext) -> Boolean>>(ModContributionKind.RUNTIME)
    private val prepareNextTurns =
        ModOwnedRegistry<PrioritizedHook<ContextualPrepareNextTurn>>(ModContributionKind.RUNTIME)

    fun forRun(owner: ModRun): RuntimeRegistry =
        object : RuntimeRegistry {
            override fun registerContextTransformer(
                id: String,
                transformer: ContextTransformer,
                priority: Int,
            ): Registration =
                contextTransformers.register(
                    owner,
                    owner.info.id.qualify(id),
                    PrioritizedHook(priority, transformer),
                )

            override fun registerBeforeToolCall(
                id: String,
                hook: suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?,
                priority: Int,
            ): Registration = beforeToolCalls.register(owner, owner.info.id.qualify(id), PrioritizedHook(priority, hook))

            override fun registerAfterToolCall(
                id: String,
                hook: suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?,
                priority: Int,
            ): Registration = afterToolCalls.register(owner, owner.info.id.qualify(id), PrioritizedHook(priority, hook))

            override fun registerContinuationPolicy(
                id: String,
                policy: suspend (ShouldStopAfterTurnContext) -> Boolean,
                priority: Int,
            ): Registration = continuationPolicies.register(owner, owner.info.id.qualify(id), PrioritizedHook(priority, policy))

            override fun registerPrepareNextTurn(
                id: String,
                hook: ContextualPrepareNextTurn,
                priority: Int,
            ): Registration = prepareNextTurns.register(owner, owner.info.id.qualify(id), PrioritizedHook(priority, hook))
        }

    suspend fun transformContext(
        messages: List<AgentMessage>,
        signal: AbortSignal?,
    ): List<AgentMessage> =
        contextTransformers
            .snapshot()
            .sortedByDescending { it.value.priority }
            .fold(messages) { current, entry -> entry.value.handler(current, signal) }

    suspend fun beforeToolCall(
        context: BeforeToolCallContext,
        signal: AbortSignal?,
    ): BeforeToolCallResult? {
        var result: BeforeToolCallResult? = null
        beforeToolCalls.snapshot().sortedByDescending { it.value.priority }.forEach { entry ->
            if (result?.block == true) return@forEach
            val next = entry.value.handler(context, signal)
            if (next != null) result = next
        }
        return result
    }

    suspend fun afterToolCall(
        context: AfterToolCallContext,
        signal: AbortSignal?,
    ): AfterToolCallResult? {
        var result: AfterToolCallResult? = null
        afterToolCalls.snapshot().sortedByDescending { it.value.priority }.forEach { entry ->
            val next = entry.value.handler(context, signal) ?: return@forEach
            result =
                AfterToolCallResult(
                    content = next.content ?: result?.content,
                    details = next.details ?: result?.details,
                    isError = next.isError ?: result?.isError,
                    terminate = next.terminate ?: result?.terminate,
                )
        }
        return result
    }

    suspend fun shouldStopAfterTurn(context: ShouldStopAfterTurnContext): Boolean =
        continuationPolicies
            .snapshot()
            .sortedByDescending { it.value.priority }
            .any { entry -> entry.value.handler(context) }

    suspend fun prepareNextTurn(
        context: ShouldStopAfterTurnContext,
        signal: AbortSignal?,
    ): dev.xuexue.pi.agent.AgentLoopTurnUpdate? {
        var update: dev.xuexue.pi.agent.AgentLoopTurnUpdate? = null
        prepareNextTurns.snapshot().sortedByDescending { it.value.priority }.forEach { entry ->
            entry.value.handler(context, signal)?.let { next ->
                update =
                    dev.xuexue.pi.agent.AgentLoopTurnUpdate(
                        context = next.context ?: update?.context,
                        model = next.model ?: update?.model,
                        thinkingLevel = next.thinkingLevel ?: update?.thinkingLevel,
                    )
            }
        }
        return update
    }

    fun hasHooks(): Boolean =
        contextTransformers.snapshot().isNotEmpty() ||
            beforeToolCalls.snapshot().isNotEmpty() ||
            afterToolCalls.snapshot().isNotEmpty() ||
            continuationPolicies.snapshot().isNotEmpty() ||
            prepareNextTurns.snapshot().isNotEmpty()

    fun clear() {
        contextTransformers.clear()
        beforeToolCalls.clear()
        afterToolCalls.clear()
        continuationPolicies.clear()
        prepareNextTurns.clear()
    }
}
