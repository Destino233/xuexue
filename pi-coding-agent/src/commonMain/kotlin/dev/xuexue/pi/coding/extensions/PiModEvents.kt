package dev.xuexue.pi.coding.extensions

/** Event registries for a loader. Each event type has an independent ordered catalog. */
class ModEventCatalog(
    private val bus: PiEventBusController = PiEventBusController(),
    private val onError: (ModRun, PiExtensionEventType, Throwable) -> Unit = { _, _, _ -> },
) {
    private val handlers =
        PiExtensionEventType.entries.associateWith {
            ModOwnedRegistry<PiExtensionEventHandler>(ModContributionKind.EVENT)
        }

    fun forRun(owner: ModRun): EventRegistry =
        object : EventRegistry {
            override fun on(
                event: PiExtensionEventType,
                handler: PiExtensionEventHandler,
            ): Registration =
                handlers.getValue(event).register(
                    owner = owner,
                    id = owner.info.id.qualify("event.${event.name.lowercase()}.${nextHandlerId()}"),
                    value = handler,
                )

            override fun on(
                channel: String,
                handler: (Any?) -> Unit,
            ): Registration {
                owner.requireActive()
                val unsubscribe = bus.on(channel, handler)
                val registration =
                    CallbackRegistration(
                        id = owner.info.id.qualify("channel.${nextHandlerId()}"),
                        run = owner,
                        kind = ModContributionKind.EVENT,
                        unregisterAction = unsubscribe,
                    )
                try {
                    owner.own(registration)
                } catch (error: Throwable) {
                    registration.unregister()
                    throw error
                }
                return registration
            }

            override fun emit(
                channel: String,
                data: Any?,
            ) {
                owner.requireActive()
                bus.emit(channel, data)
            }
        }

    suspend fun emit(
        event: PiExtensionEvent,
        context: PiExtensionContext,
    ): List<PiExtensionEventResult> {
        val results = mutableListOf<PiExtensionEventResult>()
        handlers.getValue(event.type).snapshot().forEach { entry ->
            val owner = entry.owner
            val run = findRun(owner.runId)
            if (run == null || (run.state != ModRunState.STARTING && run.state != ModRunState.RUNNING)) {
                return@forEach
            }
            try {
                entry.value.handle(event, context)?.let(results::add)
            } catch (error: Throwable) {
                onError(run, event.type, error)
            }
        }
        return results
    }

    /** Registers a run lookup so stale handlers can be skipped without retaining old contexts. */
    fun track(run: ModRun) {
        trackedRuns[run.runId] = run
    }

    fun untrack(run: ModRun) {
        trackedRuns.remove(run.runId)
    }

    fun clear() {
        handlers.values.forEach { registry -> registry.clear() }
        bus.clear()
        trackedRuns.clear()
    }

    private val trackedRuns = mutableMapOf<ModRunId, ModRun>()

    private fun findRun(runId: ModRunId): ModRun? = trackedRuns[runId]

    private var handlerSequence: Long = 0

    private fun nextHandlerId(): Long {
        handlerSequence += 1
        return handlerSequence
    }
}

internal class CallbackRegistration(
    override val id: String,
    private val run: ModRun,
    override val kind: ModContributionKind,
    private val unregisterAction: () -> Unit,
) : Registration {
    private var active = true

    override val isActive: Boolean
        get() = active

    override val owner: ModRunId
        get() = run.runId

    override fun unregister() {
        if (!active) return
        active = false
        runCatching(unregisterAction)
        run.forget(this)
    }
}
