@file:Suppress("NestedBlockDepth", "TooGenericExceptionCaught")

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.coding.resources.ResourceExtensionPaths

private class LoadedExtension(
    val extension: PiExtension,
) {
    val handlers = linkedMapOf<PiExtensionEventType, MutableList<PiExtensionEventHandler>>()
    val tools = linkedMapOf<String, AgentTool>()
    val providers = linkedMapOf<String, PiProvider>()
    val imageProviders = linkedMapOf<String, PiImageProvider>()
    val commands = linkedMapOf<String, PiExtensionCommand>()
    val resources = mutableListOf<ResourceExtensionPaths>()
    var handle: PiExtensionHandle? = null
    var active = true
}

/** Headless, ordered extension lifecycle runner shared by Kotlin and process hosts. */
@Suppress("TooManyFunctions", "LargeClass")
class PiExtensionRunner(
    private val cwd: String,
    private val mode: PiExtensionMode = PiExtensionMode.PRINT,
) {
    private val extensions = mutableListOf<LoadedExtension>()
    private val errors = mutableListOf<PiExtensionError>()

    suspend fun load(extension: PiExtension) {
        val loaded = LoadedExtension(extension)
        val api = createApi(loaded)
        try {
            loaded.handle = extension.activate(api)
            extensions += loaded
        } catch (error: Throwable) {
            loaded.active = false
            errors += PiExtensionError(extension.id, PiExtensionEventType.SESSION_START, error.message.orEmpty())
        }
    }

    suspend fun reload(newExtensions: List<PiExtension>) {
        dispose(SessionShutdownReason.RELOAD)
        errors.clear()
        newExtensions.forEach { load(it) }
    }

    suspend fun dispose(reason: SessionShutdownReason = SessionShutdownReason.QUIT) {
        dispose(reason, null)
    }

    suspend fun dispose(
        reason: SessionShutdownReason,
        targetSessionFile: String?,
    ) {
        emit(PiExtensionEvent.SessionShutdown(reason, targetSessionFile), defaultContext())
        extensions.asReversed().forEach { loaded ->
            loaded.active = false
            try {
                loaded.handle?.dispose()
            } catch (error: Throwable) {
                errors +=
                    PiExtensionError(
                        loaded.extension.id,
                        PiExtensionEventType.SESSION_SHUTDOWN,
                        error.message.orEmpty(),
                    )
            }
        }
        extensions.clear()
    }

    fun getErrors(): List<PiExtensionError> = errors.toList()

    fun hasHandlers(event: PiExtensionEventType): Boolean = extensions.any { it.handlers[event].orEmpty().isNotEmpty() }

    fun getAllRegisteredTools(): List<AgentTool> {
        val selected = linkedMapOf<String, AgentTool>()
        extensions.forEach { loaded ->
            loaded.tools.forEach { (name, tool) -> selected.putIfAbsent(name, tool) }
        }
        return selected.values.toList()
    }

    fun getAllRegisteredProviders(): List<PiProvider> =
        extensions
            .flatMap { loaded -> loaded.providers.values }
            .distinctBy(PiProvider::id)

    fun getAllRegisteredImageProviders(): List<PiImageProvider> =
        extensions
            .flatMap { loaded -> loaded.imageProviders.values }
            .distinctBy(PiImageProvider::id)

    fun getContributedResources(): ResourceExtensionPaths =
        ResourceExtensionPaths(
            skillPaths = extensions.flatMap { loaded -> loaded.resources.flatMap(ResourceExtensionPaths::skillPaths) },
            promptPaths =
                extensions.flatMap { loaded ->
                    loaded.resources.flatMap(ResourceExtensionPaths::promptPaths)
                },
        )

    fun getToolDefinition(name: String): AgentTool? = getAllRegisteredTools().firstOrNull { it.name == name }

    fun getRegisteredCommands(): List<RegisteredExtensionCommand> {
        val commands = extensions.flatMap { loaded -> loaded.commands.values.map { loaded to it } }
        val counts = commands.groupingBy { it.second.name }.eachCount()
        val seen = mutableMapOf<String, Int>()
        val takenInvocationNames = mutableSetOf<String>()
        return commands.map { (loaded, command) ->
            val occurrence = (seen[command.name] ?: 0) + 1
            seen[command.name] = occurrence
            var invocationName =
                if (counts.getValue(command.name) == 1) {
                    command.name
                } else {
                    "${command.name}:$occurrence"
                }
            if (invocationName in takenInvocationNames) {
                var suffix = occurrence
                do {
                    suffix++
                    invocationName = "${command.name}:$suffix"
                } while (invocationName in takenInvocationNames)
            }
            takenInvocationNames += invocationName
            RegisteredExtensionCommand(
                invocationName = invocationName,
                command = command,
                extensionId = loaded.extension.id,
                sourceInfo = loaded.extension.sourceInfo,
            )
        }
    }

    private val cmds: List<RegisteredExtensionCommand>
        get() = getRegisteredCommands()

    fun getCommand(name: String): RegisteredExtensionCommand? = cmds.firstOrNull { it.invocationName == name }

    suspend fun executeCommand(
        invocationName: String,
        args: String = "",
        context: PiExtensionContext = defaultContext(),
    ): Boolean {
        val registered = getCommand(invocationName) ?: return false
        val loaded = extensions.firstOrNull { it.extension.id == registered.extensionId }
        return try {
            registered.command.handler(args, context)
            true
        } catch (error: Throwable) {
            loaded?.let { recordError(it, PiExtensionEventType.COMMAND, error) }
            false
        }
    }

    /** Resolves project trust in extension order; undecided handlers do not stop the chain. */
    suspend fun emitProjectTrust(
        context: PiExtensionContext = defaultContext(),
    ): PiExtensionEventResult.ProjectTrust? {
        extensions.forEach { loaded ->
            loaded.handlers[PiExtensionEventType.PROJECT_TRUST].orEmpty().forEach { handler ->
                try {
                    val result = handler.handle(PiExtensionEvent.ProjectTrust(cwd), context)
                    if (result is PiExtensionEventResult.ProjectTrust &&
                        result.trusted != ProjectTrustDecision.UNDECIDED
                    ) {
                        return result
                    }
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.PROJECT_TRUST, error)
                }
            }
        }
        return null
    }

    suspend fun emit(
        event: PiExtensionEvent,
        context: PiExtensionContext = defaultContext(),
    ): List<PiExtensionEventResult> {
        val results = mutableListOf<PiExtensionEventResult>()
        extensions.forEach { loaded ->
            loaded.handlers[event.type].orEmpty().forEach { handler ->
                try {
                    handler.handle(event, context)?.let(results::add)
                } catch (error: Throwable) {
                    recordError(loaded, event.type, error)
                }
            }
        }
        return results
    }

    suspend fun emitContext(
        messages: List<AgentMessage>,
        context: PiExtensionContext = defaultContext(),
    ): List<AgentMessage> {
        var currentMessages = messages.toList()
        extensions.forEach { loaded ->
            loaded.handlers[PiExtensionEventType.CONTEXT].orEmpty().forEach { handler ->
                try {
                    val result = handler.handle(PiExtensionEvent.Context(currentMessages), context)
                    if (result is PiExtensionEventResult.Context) currentMessages = result.messages.toList()
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.CONTEXT, error)
                }
            }
        }
        return currentMessages
    }

    suspend fun emitBeforeAgentStart(
        prompt: String,
        systemPrompt: String,
        context: PiExtensionContext = defaultContext(),
    ): BeforeAgentStartResult {
        var currentSystemPrompt = systemPrompt
        val messages = mutableListOf<ExtensionMessage>()
        extensions.forEach { loaded ->
            loaded.handlers[PiExtensionEventType.BEFORE_AGENT_START].orEmpty().forEach { handler ->
                try {
                    val result =
                        handler.handle(PiExtensionEvent.BeforeAgentStart(prompt, currentSystemPrompt), context)
                    if (result is PiExtensionEventResult.BeforeAgentStart) {
                        result.message?.let(messages::add)
                        result.systemPrompt?.let { currentSystemPrompt = it }
                    }
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.BEFORE_AGENT_START, error)
                }
            }
        }
        return BeforeAgentStartResult(messages, currentSystemPrompt.takeUnless { it == systemPrompt })
    }

    suspend fun emitResourcesDiscover(
        reason: ResourceDiscoverReason,
        context: PiExtensionContext = defaultContext(),
    ): ResourceExtensionPaths {
        val contributions = mutableListOf<ResourceExtensionPaths>()
        extensions.forEach { loaded ->
            loaded.resources.forEach(contributions::add)
            loaded.handlers[PiExtensionEventType.RESOURCES_DISCOVER].orEmpty().forEach { handler ->
                try {
                    val result =
                        handler.handle(PiExtensionEvent.ResourcesDiscover(cwd, reason), context)
                    if (result is PiExtensionEventResult.Resources) contributions += result.paths
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.RESOURCES_DISCOVER, error)
                }
            }
        }
        return ResourceExtensionPaths(
            skillPaths = contributions.flatMap(ResourceExtensionPaths::skillPaths),
            promptPaths = contributions.flatMap(ResourceExtensionPaths::promptPaths),
        )
    }

    suspend fun emitBeforeSwitch(
        reason: String,
        targetSessionFile: String? = null,
        context: PiExtensionContext = defaultContext(),
    ): Boolean =
        emit(
            PiExtensionEvent.SessionBeforeSwitch(reason, targetSessionFile),
            context,
        ).any { result -> result is PiExtensionEventResult.SessionBeforeSwitch && result.cancel }

    suspend fun emitBeforeFork(
        entryId: String,
        position: String,
        context: PiExtensionContext = defaultContext(),
    ): Boolean =
        emit(
            PiExtensionEvent.SessionBeforeFork(entryId, position),
            context,
        ).any { result -> result is PiExtensionEventResult.SessionBeforeFork && result.cancel }

    suspend fun emitToolCall(
        event: PiExtensionEvent.ToolCall,
        context: PiExtensionContext = defaultContext(),
    ): PiExtensionEventResult.ToolCall? {
        var result: PiExtensionEventResult.ToolCall? = null
        extensions.forEach { loaded ->
            loaded.handlers[PiExtensionEventType.TOOL_CALL].orEmpty().forEach { handler ->
                if (result?.block == true) return@forEach
                try {
                    val value = handler.handle(event, context)
                    if (value is PiExtensionEventResult.ToolCall) result = value
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.TOOL_CALL, error)
                }
            }
        }
        return result
    }

    suspend fun emitToolResult(
        event: PiExtensionEvent.ToolResult,
        context: PiExtensionContext = defaultContext(),
    ): PiExtensionEvent.ToolResult {
        extensions.forEach { loaded ->
            loaded.handlers[PiExtensionEventType.TOOL_RESULT].orEmpty().forEach { handler ->
                try {
                    val value = handler.handle(event, context)
                    if (value is PiExtensionEventResult.ToolResult) {
                        value.content?.let { event.content = it }
                        value.details?.let { event.details = it }
                        value.isError?.let { event.isError = it }
                    }
                } catch (error: Throwable) {
                    recordError(loaded, PiExtensionEventType.TOOL_RESULT, error)
                }
            }
        }
        return event
    }

    private fun createApi(loaded: LoadedExtension): PiExtensionApi =
        object : PiExtensionApi {
            private fun assertActive() {
                check(loaded.active) { "Extension ${loaded.extension.id} is stale after unload" }
            }

            override fun on(
                event: PiExtensionEventType,
                handler: PiExtensionEventHandler,
            ) {
                assertActive()
                loaded.handlers.getOrPut(event) { mutableListOf() } += handler
            }

            override fun registerTool(tool: AgentTool) {
                assertActive()
                loaded.tools[tool.name] = tool
            }

            override fun registerProvider(provider: PiProvider) {
                assertActive()
                loaded.providers[provider.id] = provider
            }

            override fun registerImageProvider(provider: PiImageProvider) {
                assertActive()
                loaded.imageProviders[provider.id] = provider
            }

            override fun registerCommand(command: PiExtensionCommand) {
                assertActive()
                loaded.commands[command.name] = command
            }

            override fun contributeResources(paths: ResourceExtensionPaths) {
                assertActive()
                loaded.resources += paths
            }
        }

    private fun defaultContext(): PiExtensionContext = PiExtensionContext(cwd, mode)

    private fun recordError(
        loaded: LoadedExtension,
        event: PiExtensionEventType,
        error: Throwable,
    ) {
        errors += PiExtensionError(loaded.extension.id, event, error.message.orEmpty())
    }
}

data class BeforeAgentStartResult(
    val messages: List<ExtensionMessage>,
    val systemPrompt: String?,
)
