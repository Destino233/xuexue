@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AfterToolCallContext
import dev.xuexue.pi.agent.AfterToolCallResult
import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.BeforeToolCallContext
import dev.xuexue.pi.agent.BeforeToolCallResult
import dev.xuexue.pi.agent.ContextTransformer
import dev.xuexue.pi.agent.ContextualPrepareNextTurn
import dev.xuexue.pi.agent.ShouldStopAfterTurnContext
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.coding.AgentSessionState
import dev.xuexue.pi.coding.CustomMessageInput
import dev.xuexue.pi.coding.CustomMessageOptions
import dev.xuexue.pi.coding.InteractionPort
import dev.xuexue.pi.coding.NoOpInteractionPort
import dev.xuexue.pi.coding.StreamingBehavior
import dev.xuexue.pi.coding.exec.PiCommandExecutor
import dev.xuexue.pi.coding.exec.UnavailablePiCommandExecutor
import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.concurrent.atomics.AtomicReference

data class ModRunSnapshot(
    val info: ModInfo,
    val packageInfo: ModPackage,
    val state: ModRunState,
    val diagnostics: List<ModRunDiagnostic> = emptyList(),
)

data class ModLoadResult(
    val run: ModRunSnapshot?,
    val diagnostics: List<ModRunDiagnostic>,
) {
    val loaded: Boolean
        get() = run?.state == ModRunState.RUNNING
}

/**
 * Dynamic mod host. The loader owns package runs and all contribution catalogs;
 * a run can only mutate entries carrying its own ModRunId.
 */
class PiModLoader(
    val cwd: String,
    val mode: PiExtensionMode = PiExtensionMode.PRINT,
    val events: PiEventBusController = PiEventBusController(),
    private val commandExecutor: PiCommandExecutor = UnavailablePiCommandExecutor,
    private val projectTrusted: Boolean = true,
    private val interaction: InteractionPort = NoOpInteractionPort,
) {
    private val lock = Mutex()
    private val loaded = linkedMapOf<ModId, LoadedMod>()
    private val diagnostics = mutableListOf<ModRunDiagnostic>()
    private val sessionApi = AtomicReference<SessionApi>(UnavailableSessionApi)

    private val toolCatalog = ModOwnedRegistry<AgentTool>(ModContributionKind.TOOL)
    private val commandCatalog = ModOwnedRegistry<ModCommand>(ModContributionKind.COMMAND)
    private val providerCatalog = ModOwnedRegistry<PiProvider>(ModContributionKind.PROVIDER)
    private val imageProviderCatalog = ModOwnedRegistry<PiImageProvider>(ModContributionKind.IMAGE_PROVIDER)
    private val resourceCatalog = ModOwnedRegistry<ResourceExtensionPaths>(ModContributionKind.RESOURCE)
    private val runtimeCatalog = ModRuntimeCatalog()
    private val uiCatalog = ModUiCatalog()
    private val eventCatalog =
        ModEventCatalog(
            bus = events,
            onError = { run, event, error ->
                diagnostics +=
                    ModRunDiagnostic(
                        runId = run.runId,
                        state = run.state,
                        message = "${event.name.lowercase()} handler failed",
                        cause = error.message,
                    )
            },
        )

    /** Binds this loader to a live AgentSession without invalidating existing mod contexts. */
    fun bindSession(session: SessionApi) {
        sessionApi.store(session)
    }

    fun unbindSession() {
        sessionApi.store(UnavailableSessionApi)
    }

    /** Live UI contributions for Compose or another host renderer. */
    val uiSnapshot: StateFlow<ModUiSnapshot>
        get() = uiCatalog.snapshot

    /** Enables a package, replacing a previous active run with the same ID. */
    suspend fun load(
        mod: PiMod,
        packageInfo: ModPackage = mod.defaultPackage(),
        trusted: Boolean = projectTrusted,
    ): ModLoadResult =
        lock.withLock {
            require(packageInfo.id == mod.id) {
                "Mod package id ${packageInfo.id} does not match implementation ${mod.id}"
            }
            loaded.remove(packageInfo.id)?.let { previous ->
                eventCatalog.untrack(previous.run)
                previous.run.stop(previous.handle?.let { handle -> { handle.disable() } })
                uiCatalog.refresh()
            }
            val runDiagnostics = mutableListOf<ModRunDiagnostic>()
            val run = ModRun.create(packageInfo, trusted, runDiagnostics)
            eventCatalog.track(run)
            val context = createContext(run)
            run.beginStart()
            val active = LoadedMod(mod, run)
            loaded[packageInfo.id] = active
            try {
                active.handle = mod.enable(context)
                run.markRunning()
                ModLoadResult(snapshot(run, runDiagnostics), runDiagnostics.toList())
            } catch (error: Throwable) {
                run.stop()
                run.markFailed("Mod enable failed", error)
                loaded.remove(packageInfo.id)
                eventCatalog.untrack(run)
                uiCatalog.refresh()
                diagnostics += runDiagnostics
                ModLoadResult(snapshot(run, runDiagnostics), runDiagnostics.toList())
            }
        }

    /** Loads a legacy extension through the new ownership-aware runtime. */
    suspend fun load(
        extension: PiExtension,
        packageInfo: ModPackage = extension.defaultPackage(),
        trusted: Boolean = projectTrusted,
    ): ModLoadResult = load(LegacyPiExtensionMod(extension, this), packageInfo, trusted)

    suspend fun disable(id: ModId): Boolean =
        lock.withLock {
            val active = loaded.remove(id) ?: return@withLock false
            eventCatalog.untrack(active.run)
            active.run.stop(active.handle?.let { handle -> { handle.disable() } })
            uiCatalog.refresh()
            true
        }

    suspend fun disable(runId: ModRunId): Boolean =
        lock.withLock {
            val entry = loaded.entries.firstOrNull { it.value.run.runId == runId } ?: return@withLock false
            loaded.remove(entry.key)
            eventCatalog.untrack(entry.value.run)
            entry.value.run.stop(entry.value.handle?.let { handle -> { handle.disable() } })
            uiCatalog.refresh()
            true
        }

    suspend fun reload(mods: List<Pair<PiMod, ModPackage>> = emptyList()): List<ModLoadResult> {
        lock.withLock {
            loaded.values.forEach { active ->
                eventCatalog.untrack(active.run)
                active.run.stop(active.handle?.let { handle -> { handle.disable() } })
            }
            loaded.clear()
            uiCatalog.refresh()
        }
        return mods.map { (mod, packageInfo) -> load(mod, packageInfo) }
    }

    suspend fun emit(
        event: PiExtensionEvent,
        context: PiExtensionContext = defaultExtensionContext(),
    ): List<PiExtensionEventResult> = eventCatalog.emit(event, context)

    fun getRuns(): List<ModRunSnapshot> = loaded.values.map { active -> snapshot(active.run, emptyList()) }

    fun getDiagnostics(): List<ModRunDiagnostic> = diagnostics.toList()

    fun getAllRegisteredTools(): List<AgentTool> =
        toolCatalog
            .snapshot()
            .map(RegisteredModEntry<AgentTool>::value)

    fun getAllRegisteredProviders(): List<PiProvider> =
        providerCatalog
            .snapshot()
            .map(RegisteredModEntry<PiProvider>::value)
            .distinctBy(PiProvider::id)

    fun getAllRegisteredImageProviders(): List<PiImageProvider> =
        imageProviderCatalog
            .snapshot()
            .map(RegisteredModEntry<PiImageProvider>::value)
            .distinctBy(PiImageProvider::id)

    fun getContributedResources(): ResourceExtensionPaths {
        val values = resourceCatalog.snapshot().map(RegisteredModEntry<ResourceExtensionPaths>::value)
        return ResourceExtensionPaths(
            skillPaths = values.flatMap(ResourceExtensionPaths::skillPaths),
            promptPaths = values.flatMap(ResourceExtensionPaths::promptPaths),
        )
    }

    fun getToolDefinition(id: String): AgentTool? = toolCatalog.get(id)

    fun getUiPanels(): List<RegisteredModEntry<UiPanelContribution>> = uiCatalog.panels()

    fun getUiActions(): List<RegisteredModEntry<UiActionContribution>> = uiCatalog.actions()

    /** Invokes an active UI action by its qualified contribution ID. */
    suspend fun invokeUiAction(id: String): Boolean {
        val action = uiCatalog.actions().firstOrNull { it.id == id } ?: return false
        val active = loaded[action.owner.id] ?: return false
        if (active.run.runId != action.owner.runId || !action.value.enabled) return false
        return runCatching {
            action.value.invoke(createContext(active.run))
        }.isSuccess
    }

    internal fun hasRuntimeHooks(): Boolean = runtimeCatalog.hasHooks()

    internal suspend fun transformRuntimeContext(
        messages: List<AgentMessage>,
        signal: AbortSignal?,
    ): List<AgentMessage> = runtimeCatalog.transformContext(messages, signal)

    internal suspend fun beforeRuntimeToolCall(
        context: BeforeToolCallContext,
        signal: AbortSignal?,
    ): BeforeToolCallResult? = runtimeCatalog.beforeToolCall(context, signal)

    internal suspend fun afterRuntimeToolCall(
        context: AfterToolCallContext,
        signal: AbortSignal?,
    ): AfterToolCallResult? = runtimeCatalog.afterToolCall(context, signal)

    internal suspend fun shouldStopAfterRuntimeTurn(context: ShouldStopAfterTurnContext): Boolean = runtimeCatalog.shouldStopAfterTurn(context)

    internal suspend fun prepareRuntimeNextTurn(
        context: ShouldStopAfterTurnContext,
        signal: AbortSignal?,
    ): dev.xuexue.pi.agent.AgentLoopTurnUpdate? = runtimeCatalog.prepareNextTurn(context, signal)

    fun getRegisteredCommands(): List<RegisteredModCommand> =
        commandCatalog.snapshot().map { entry ->
            RegisteredModCommand(
                id = entry.id,
                command = entry.value,
                owner = entry.owner,
            )
        }

    suspend fun executeCommand(
        id: String,
        args: String = "",
        context: ModContext? = null,
    ): Boolean {
        val command = commandCatalog.snapshot().firstOrNull { it.id == id } ?: return false
        val active = loaded[command.owner.id] ?: return false
        if (active.run.runId != command.owner.runId) return false
        return runCatching {
            command.value.handler(args, context ?: createContext(active.run))
        }.isSuccess
    }

    internal fun legacyApi(run: ModRun): PiExtensionApi =
        object : PiExtensionApi {
            private fun assertActive() = run.requireActive()

            override fun on(
                event: PiExtensionEventType,
                handler: PiExtensionEventHandler,
            ) {
                assertActive()
                eventCatalog.forRun(run).on(event, handler)
            }

            override fun registerTool(tool: AgentTool) {
                assertActive()
                registerLegacyContribution(toolCatalog, run, tool.name, tool)
            }

            override fun registerProvider(provider: PiProvider) {
                assertActive()
                registerLegacyContribution(providerCatalog, run, provider.id, provider)
            }

            override fun registerImageProvider(provider: PiImageProvider) {
                assertActive()
                registerLegacyContribution(imageProviderCatalog, run, provider.id, provider)
            }

            override fun registerCommand(command: PiExtensionCommand) {
                assertActive()
                registerLegacyContribution(
                    commandCatalog,
                    run,
                    command.name,
                    ModCommand(command.description) { args, _ ->
                        command.handler(args, defaultExtensionContext())
                    },
                )
            }

            override fun contributeResources(paths: ResourceExtensionPaths) {
                assertActive()
                resourceCatalog.register(run, "${run.info.id.value}:resources", paths, replace = true)
            }

            override val events: PiEventBus
                get() =
                    object : PiEventBus {
                        override fun emit(
                            channel: String,
                            data: Any?,
                        ) {
                            assertActive()
                            this@PiModLoader.events.emit(channel, data)
                        }

                        override fun on(
                            channel: String,
                            handler: (Any?) -> Unit,
                        ): () -> Unit {
                            assertActive()
                            val registration = eventCatalog.forRun(run).on(channel, handler)
                            return registration::unregister
                        }
                    }

            override val exec: PiCommandExecutor
                get() {
                    assertActive()
                    return commandExecutor
                }
        }

    private fun createContext(run: ModRun): ModContext =
        LoaderModContext(
            run = run,
            mod = run.info,
            scope = run.scope,
            events = eventCatalog.forRun(run),
            tools = ToolRegistry(toolCatalog, run),
            commands = CommandRegistry(commandCatalog, run),
            providers = ProviderRegistry(providerCatalog, run),
            imageProviders = ImageProviderRegistry(imageProviderCatalog, run),
            resources = ResourceRegistry(resourceCatalog, run),
            session = DelegatingSessionApi(sessionApi),
            runtime = runtimeCatalog.forRun(run),
            ui = uiCatalog.forRun(run),
            storage = InMemoryStorageApi(),
            permissions = DefaultPermissionBroker(run.packageInfo.permissions, run.info.trusted),
            diagnostics = RunDiagnosticsRegistry(run, diagnostics),
        )

    private fun defaultExtensionContext(): PiExtensionContext =
        PiExtensionContext(
            cwd = cwd,
            mode = mode,
            isProjectTrusted = { projectTrusted },
            interaction = interaction,
        )

    private fun snapshot(
        run: ModRun,
        runDiagnostics: List<ModRunDiagnostic>,
    ): ModRunSnapshot =
        ModRunSnapshot(
            info = run.info,
            packageInfo = run.packageInfo,
            state = run.state,
            diagnostics = runDiagnostics.toList(),
        )

    private data class LoadedMod(
        val mod: PiMod,
        val run: ModRun,
        var handle: ModHandle? = null,
    )

    private fun <T> registerLegacyContribution(
        catalog: ModOwnedRegistry<T>,
        run: ModRun,
        id: String,
        value: T,
    ) {
        runCatching { catalog.register(run, id, value) }
            .onFailure { error ->
                if (error !is IllegalStateException || !error.message.orEmpty().contains("already registered")) {
                    throw error
                }
                diagnostics +=
                    ModRunDiagnostic(
                        runId = run.runId,
                        state = run.state,
                        message = "Legacy contribution '$id' was ignored because it is already registered",
                        cause = error.message,
                    )
            }
    }
}

data class RegisteredModCommand(
    val id: String,
    val command: ModCommand,
    val owner: ModInfo,
)

private class LegacyPiExtensionMod(
    private val extension: PiExtension,
    private val loader: PiModLoader,
) : PiMod {
    override val id: ModId = ModId.parse(extension.id)
    override val version: ModVersion = ModVersion("0.0.0")

    override suspend fun enable(context: ModContext): ModHandle? {
        val handle = extension.activate(loader.legacyApi((context as LoaderModContext).run))
        return handle?.let { PiModHandleAdapter(it) }
    }
}

private class PiModHandleAdapter(
    private val delegate: PiExtensionHandle,
) : ModHandle {
    override suspend fun disable() {
        delegate.dispose()
    }
}

private class LoaderModContext(
    val run: ModRun,
    override val mod: ModInfo,
    override val scope: kotlinx.coroutines.CoroutineScope,
    override val events: EventRegistry,
    override val tools: ToolRegistry,
    override val commands: CommandRegistry,
    override val providers: ProviderRegistry,
    override val imageProviders: ImageProviderRegistry,
    override val resources: ResourceRegistry,
    override val session: SessionApi,
    override val runtime: RuntimeRegistry,
    override val ui: UiRegistry,
    override val storage: StorageApi,
    override val permissions: PermissionBroker,
    override val diagnostics: DiagnosticsRegistry,
) : ModContext

private object UnavailableSessionApi : SessionApi {
    override val state: AgentSessionState? = null
    override val isIdle: Boolean = true

    override suspend fun prompt(
        text: String,
        images: List<dev.xuexue.pi.ai.ImageContent>,
        behavior: StreamingBehavior?,
    ) = error("Session API is not attached to this mod host")

    override suspend fun sendUserMessage(
        text: String,
        options: CustomMessageOptions,
    ) = error("Session API is not attached to this mod host")

    override suspend fun sendCustomMessage(
        message: CustomMessageInput,
        options: CustomMessageOptions,
    ) = error("Session API is not attached to this mod host")

    override fun steer(message: AgentMessage) = error("Session API is not attached to this mod host")

    override fun followUp(message: AgentMessage) = error("Session API is not attached to this mod host")

    override suspend fun abort() = error("Session API is not attached to this mod host")

    override fun appendCustomEntry(message: CustomMessageInput): String? = error("Session API is not attached to this mod host")
}

private class DelegatingSessionApi(
    private val delegate: AtomicReference<SessionApi>,
) : SessionApi {
    private fun current(): SessionApi = delegate.load()

    override val state: AgentSessionState?
        get() = current().state

    override val isIdle: Boolean
        get() = current().isIdle

    override suspend fun prompt(
        text: String,
        images: List<dev.xuexue.pi.ai.ImageContent>,
        behavior: StreamingBehavior?,
    ) = current().prompt(text, images, behavior)

    override suspend fun sendUserMessage(
        text: String,
        options: CustomMessageOptions,
    ) = current().sendUserMessage(text, options)

    override suspend fun sendCustomMessage(
        message: CustomMessageInput,
        options: CustomMessageOptions,
    ) = current().sendCustomMessage(message, options)

    override fun steer(message: AgentMessage) = current().steer(message)

    override fun followUp(message: AgentMessage) = current().followUp(message)

    override suspend fun abort() = current().abort()

    override fun appendCustomEntry(message: CustomMessageInput): String? = current().appendCustomEntry(message)
}

private object UnavailableRuntimeRegistry : RuntimeRegistry {
    override fun registerContextTransformer(
        id: String,
        transformer: ContextTransformer,
        priority: Int,
    ): Registration = unsupported()

    override fun registerBeforeToolCall(
        id: String,
        hook: suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?,
        priority: Int,
    ): Registration = unsupported()

    override fun registerAfterToolCall(
        id: String,
        hook: suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?,
        priority: Int,
    ): Registration = unsupported()

    override fun registerContinuationPolicy(
        id: String,
        policy: suspend (ShouldStopAfterTurnContext) -> Boolean,
        priority: Int,
    ): Registration = unsupported()

    override fun registerPrepareNextTurn(
        id: String,
        hook: ContextualPrepareNextTurn,
        priority: Int,
    ): Registration = unsupported()

    private fun unsupported(): Nothing = error("Runtime API is not attached to this mod host")
}

private object EmptyUiRegistry : UiRegistry {
    override fun registerPanel(contribution: UiPanelContribution): Registration = error("UI API is not attached to this mod host")

    override fun registerAction(contribution: UiActionContribution): Registration = error("UI API is not attached to this mod host")

    override fun panels(): List<RegisteredModEntry<UiPanelContribution>> = emptyList()

    override fun actions(): List<RegisteredModEntry<UiActionContribution>> = emptyList()
}

private class InMemoryStorageApi : StorageApi {
    private val values = mutableMapOf<String, JsonElement>()

    override suspend fun read(key: String): JsonElement? = values[key]

    override suspend fun write(
        key: String,
        value: JsonElement,
    ) {
        require(key.isNotBlank()) { "Storage key must not be blank" }
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}

private class DefaultPermissionBroker(
    private val declared: Set<String>,
    private val trusted: Boolean,
) : PermissionBroker {
    override fun check(permission: String): PermissionDecision =
        when {
            !trusted -> PermissionDecision.DENIED
            permission !in declared -> PermissionDecision.DENIED
            else -> PermissionDecision.GRANTED
        }

    override suspend fun request(permission: String): PermissionDecision = check(permission)
}

private class RunDiagnosticsRegistry(
    private val run: ModRun,
    private val sink: MutableList<ModRunDiagnostic>,
) : DiagnosticsRegistry {
    private val entries = mutableListOf<ModDiagnostic>()

    override fun report(
        severity: ModDiagnosticSeverity,
        message: String,
        details: JsonElement?,
    ) {
        val diagnostic = ModDiagnostic(run.runId, severity, message, details)
        entries += diagnostic
        sink +=
            ModRunDiagnostic(
                runId = run.runId,
                state = run.state,
                message = message,
            )
    }

    override fun entries(): List<ModDiagnostic> = entries.toList()
}

private fun PiMod.defaultPackage(): ModPackage =
    ModPackage(
        id = id,
        version = version,
        apiVersion = PI_PLUGIN_API_VERSION,
        runtime = PiPluginRuntime.JVM,
    )

private fun PiExtension.defaultPackage(): ModPackage =
    ModPackage(
        id = ModId.parse(id),
        version = ModVersion("0.0.0"),
        apiVersion = PI_PLUGIN_API_VERSION,
        runtime = PiPluginRuntime.JVM,
        sourcePath = sourceInfo.path,
    )
