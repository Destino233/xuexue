// A single RPC facade intentionally owns exhaustive command dispatch at the wire boundary.
@file:Suppress(
    "CyclomaticComplexMethod",
    "InjectDispatcher",
    "LargeClass",
    "MaxLineLength",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package dev.xuexue.pi.coding.rpc

import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.coding.AgentSessionEvent
import dev.xuexue.pi.coding.AgentSessionRuntime
import dev.xuexue.pi.coding.InteractionRequestBroker
import dev.xuexue.pi.coding.InteractionResponse
import dev.xuexue.pi.coding.ModelCycleDirection
import dev.xuexue.pi.coding.SessionForkPosition
import dev.xuexue.pi.coding.StreamingBehavior
import dev.xuexue.pi.coding.session.SessionTreeNode
import dev.xuexue.pi.coding.session.encodeSessionEntryForWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Command adapter used by stdin/stdout JSONL hosts and deterministic RPC tests. */
class AgentSessionRpcHandler(
    private val runtime: AgentSessionRuntime,
    private val interactionBroker: InteractionRequestBroker? = null,
    private val modelRegistry: ModelRegistry? = null,
) {
    private val mutableEvents = MutableSharedFlow<PiRpcEvent>(extraBufferCapacity = 256)
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscription = runtime.session.subscribe(::onSessionEvent)
    private var boundSessionId = runtime.session.sessionId
    private var shutdownRequested = false

    init {
        interactionBroker?.let(::observeInteractions)
    }

    val events: SharedFlow<PiRpcEvent> = mutableEvents.asSharedFlow()

    private val effectiveModelRegistry: ModelRegistry?
        get() = modelRegistry ?: runtime.session.modelRegistry

    suspend fun handleLine(line: String): String {
        val raw = runCatching { PiRpcCodec.json.parseToJsonElement(line).jsonObject }.getOrNull()
        val parsedRequest = runCatching { PiRpcCodec.decodeRequest(line) }
        val response =
            if (raw?.string("type") == "extension_ui_response") {
                handle(
                    PiRpcRequest(
                        id = raw.string("id"),
                        command = "respond",
                        payload = raw,
                    ),
                )
            } else if (parsedRequest.isSuccess) {
                handle(requireNotNull(parsedRequest.getOrNull()))
            } else {
                PiRpcResponse(
                    null,
                    "parse",
                    success = false,
                    error = parsedRequest.exceptionOrNull()?.message ?: "Invalid RPC request",
                )
            }
        return PiRpcCodec.encodeResponse(response)
    }

    /**
     * Server-facing line handler. Prompt responses are emitted on [events] after preflight,
     * while the actual agent run continues asynchronously as in Pi's RPC mode.
     */
    suspend fun handleLineForServer(line: String): String? {
        val raw = runCatching { PiRpcCodec.json.parseToJsonElement(line).jsonObject }.getOrNull()
        if (raw?.string("type") == "extension_ui_response") {
            handleLine(line)
            return null
        }
        val parsed = runCatching { PiRpcCodec.decodeRequest(line) }
        if (parsed.isFailure) {
            return PiRpcCodec.encodeResponse(
                PiRpcResponse(
                    id = null,
                    command = "parse",
                    success = false,
                    error = parsed.exceptionOrNull()?.message ?: "Invalid RPC request",
                ),
            )
        }
        val request = requireNotNull(parsed.getOrNull())
        if (request.command.equals("prompt", ignoreCase = true)) {
            ensureBound()
            launchPrompt(request)
            return null
        }
        return PiRpcCodec.encodeResponse(handle(request))
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(request: PiRpcRequest): PiRpcResponse {
        ensureBound()
        return try {
            when (request.command.lowercase()) {
                "cycle_model", "cycle-model" -> {
                    cycleModelResponse(request)
                }

                "cycle_thinking_level", "cycle-thinking-level" -> {
                    cycleThinkingLevelResponse(request)
                }

                else -> {
                    val payload = dispatch(request)
                    PiRpcResponse(request.id, request.command, success = true, payload = payload)
                }
            }
        } catch (error: Throwable) {
            PiRpcResponse(
                id = request.id,
                command = request.command,
                success = false,
                error = error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }

    private fun cycleModelResponse(request: PiRpcRequest): PiRpcResponse {
        val direction =
            when (request.payload.string("direction")?.lowercase()) {
                "backward", "back", "previous", "prev" -> ModelCycleDirection.BACKWARD
                else -> ModelCycleDirection.FORWARD
            }
        val result = runtime.session.cycleModel(direction, effectiveModelRegistry)
        val data =
            result?.let {
                buildJsonObject {
                    put("model", PiRpcCodec.json.encodeToJsonElement(Model.serializer(), it.model))
                    put("thinkingLevel", it.thinkingLevel.name.lowercase())
                    put("isScoped", it.isScoped)
                }
            }
        return PiRpcResponse(
            id = request.id,
            command = request.command,
            success = true,
            data = data,
            dataPresent = true,
        )
    }

    private fun cycleThinkingLevelResponse(request: PiRpcRequest): PiRpcResponse {
        val level = runtime.session.cycleThinkingLevel()
        val data = level?.let { buildJsonObject { put("level", it.name.lowercase()) } } ?: JsonNull
        return PiRpcResponse(
            id = request.id,
            command = request.command,
            success = true,
            data = data,
            dataPresent = true,
        )
    }

    fun close() {
        subscription.dispose()
        eventScope.cancel()
    }

    private fun observeInteractions(broker: InteractionRequestBroker): Job =
        eventScope.launch {
            launch {
                broker.requestEvents.collect { request ->
                    mutableEvents.emit(PiRpcEvent("extension_ui_request", PiRpcCodec.extensionUiRequest(request)))
                }
            }
            launch {
                broker.notifications.collect { notification ->
                    mutableEvents.emit(PiRpcEvent("extension_ui_request", PiRpcCodec.extensionUiRequest(notification)))
                }
            }
        }

    private fun launchPrompt(request: PiRpcRequest) {
        eventScope.launch {
            val preflight = CompletableDeferred<Throwable?>()
            launch {
                try {
                    runtime.session.prompt(
                        text = request.payload.requiredString("message", "text"),
                        options =
                            dev.xuexue.pi.coding.PromptOptions(
                                images = request.payload.images(),
                                streamingBehavior = request.payload.streamingBehavior(),
                                preflightResult = { succeeded ->
                                    preflight.complete(
                                        if (succeeded) null else IllegalStateException("Prompt rejected"),
                                    )
                                },
                            ),
                    )
                    if (!preflight.isCompleted) preflight.complete(null)
                } catch (error: Throwable) {
                    preflight.complete(error)
                }
            }
            val error = preflight.await()
            mutableEvents.emit(
                PiRpcCodec.responseEvent(
                    if (error == null) {
                        PiRpcResponse(request.id, "prompt", success = true)
                    } else {
                        PiRpcResponse(
                            id = request.id,
                            command = "prompt",
                            success = false,
                            error = error.message ?: error::class.simpleName.orEmpty(),
                        )
                    },
                ),
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun dispatch(request: PiRpcRequest): JsonObject =
        when (request.command.lowercase()) {
            "prompt" -> {
                runtime.session.prompt(
                    text = request.payload.requiredString("message", "text"),
                    options =
                        dev.xuexue.pi.coding.PromptOptions(
                            images = request.payload.images(),
                            streamingBehavior = request.payload.streamingBehavior(),
                        ),
                )
                emptyJson()
            }

            "steer" -> {
                runtime.session.steer(
                    text = request.payload.requiredString("message", "text"),
                    images = request.payload.images(),
                )
                emptyJson()
            }

            "follow_up", "follow-up", "followup" -> {
                runtime.session.followUp(
                    text = request.payload.requiredString("message", "text"),
                    images = request.payload.images(),
                )
                emptyJson()
            }

            "abort", "stop" -> {
                runtime.session.abort()
                emptyJson()
            }

            "get_state", "state" -> {
                PiRpcCodec.state(runtime.session)
            }

            "get_messages", "messages" -> {
                buildJsonObjectWithMessages()
            }

            "new_session", "new-session" -> {
                val cancelled = runtime.newSession(request.payload.string("parentSession"))
                ensureBound()
                buildJsonObject { put("cancelled", cancelled) }
            }

            "switch_session", "switch-session", "resume" -> {
                val cancelled =
                    runtime.switchSession(
                        sessionPath = request.payload.requiredString("sessionPath", "path"),
                        cwdOverride = request.payload.string("cwd"),
                    )
                ensureBound()
                buildJsonObject { put("cancelled", cancelled) }
            }

            "fork" -> {
                val position =
                    request.payload.string("position")?.let { value ->
                        SessionForkPosition.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    } ?: SessionForkPosition.BEFORE
                val result = runtime.fork(request.payload.requiredString("entryId", "entry"), position)
                buildJsonObject {
                    put("cancelled", result.cancelled)
                    result.selectedText?.let { put("text", it) }
                }
            }

            "set_model", "set-model" -> {
                val provider = request.payload.string("provider")
                val modelId = request.payload.string("modelId", "model_id")
                val model =
                    if (provider != null && modelId != null) {
                        effectiveModelRegistry?.find(provider, modelId)
                            ?: error("Unknown model: $provider/$modelId")
                    } else {
                        val modelElement = request.payload["model"] ?: error("RPC set_model requires provider/modelId")
                        PiRpcCodec.json.decodeFromJsonElement(Model.serializer(), modelElement)
                    }
                runtime.session.setModel(model)
                PiRpcCodec.json.encodeToJsonElement(Model.serializer(), runtime.session.model).jsonObject
            }

            "cycle_model", "cycle-model" -> {
                val result = runtime.session.cycleModel()
                if (result == null) {
                    emptyJson()
                } else {
                    buildJsonObject {
                        put(
                            "model",
                            PiRpcCodec.json.encodeToJsonElement(Model.serializer(), result.model),
                        )
                        put("thinkingLevel", result.thinkingLevel.name.lowercase())
                        put("isScoped", result.isScoped)
                    }
                }
            }

            "get_available_models", "get-available-models", "get_models", "get-models" -> {
                val models = effectiveModelRegistry?.getAvailable() ?: listOf(runtime.session.model)
                buildJsonObject {
                    put(
                        "models",
                        buildJsonArray {
                            models.forEach { model ->
                                add(PiRpcCodec.json.encodeToJsonElement(Model.serializer(), model))
                            }
                        },
                    )
                }
            }

            "set_thinking_level", "set-thinking-level" -> {
                val value =
                    request.payload
                        .requiredString("level")
                        .uppercase()
                        .replace('-', '_')
                val level =
                    ModelThinkingLevel.entries.firstOrNull { it.name == value }
                        ?: error("Unknown thinking level: $value")
                runtime.session.setThinkingLevel(level)
                emptyJson()
            }

            "set_steering_mode", "set-steering-mode" -> {
                runtime.session.setSteeringMode(parseQueueMode(request.payload.requiredString("mode")))
                PiRpcCodec.state(runtime.session)
            }

            "set_follow_up_mode", "set-follow-up-mode" -> {
                runtime.session.setFollowUpMode(parseQueueMode(request.payload.requiredString("mode")))
                PiRpcCodec.state(runtime.session)
            }

            "cycle_thinking_level", "cycle-thinking-level" -> {
                val level = runtime.session.cycleThinkingLevel()
                buildJsonObject { level?.let { put("level", it.name.lowercase()) } }
            }

            "set_auto_compaction", "set-auto-compaction" -> {
                runtime.session.setAutoCompactionEnabled(request.payload.requiredBoolean("enabled"))
                emptyJson()
            }

            "set_auto_retry", "set-auto-retry" -> {
                runtime.session.setAutoRetryEnabled(request.payload.requiredBoolean("enabled"))
                emptyJson()
            }

            "abort_retry", "abort-retry" -> {
                runtime.session.abortRetry()
                emptyJson()
            }

            "bash" -> {
                val result =
                    runtime.session.executeBash(
                        command = request.payload.requiredString("command"),
                        excludeFromContext = request.payload.boolean("excludeFromContext", "exclude_from_context"),
                    )
                encodeBashResult(result)
            }

            "abort_bash", "abort-bash" -> {
                runtime.session.abortBash()
                emptyJson()
            }

            "reload_resources", "reload-resources", "reload" -> {
                val diagnostics = runtime.session.reloadResourcesFromExtensions()
                buildJsonObject {
                    put(
                        "diagnostics",
                        buildJsonArray { diagnostics.forEach { add(JsonPrimitive(it.message)) } },
                    )
                }
            }

            "compact" -> {
                val result = runtime.session.compact(request.payload.string("instructions"))
                buildJsonObject {
                    put("summary", result.summary)
                    put("tokensBefore", result.tokensBefore)
                    put("estimatedTokensAfter", result.estimatedTokensAfter)
                }
            }

            "execute_command", "execute-command" -> {
                val executed =
                    runtime.session.executeExtensionCommand(
                        request.payload.requiredString("name", "command"),
                        request.payload.string("args").orEmpty(),
                    )
                buildJsonObject { put("executed", executed) }
            }

            "get_session_stats", "get-session-stats" -> {
                encodeSessionStats(runtime.session.getSessionStats())
            }

            "export_html", "export-html" -> {
                buildJsonObject {
                    put(
                        "path",
                        runtime.session.exportToHtml(request.payload.string("outputPath", "output_path")),
                    )
                }
            }

            "get_fork_messages", "get-fork-messages" -> {
                buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            runtime.session.getUserMessagesForForking().forEach { message ->
                                add(
                                    buildJsonObject {
                                        put("entryId", message.entryId)
                                        put("text", message.text)
                                    },
                                )
                            }
                        },
                    )
                }
            }

            "get_entries", "get-entries" -> {
                val allEntries = runtime.session.sessionManager.getEntries()
                val since = request.payload.string("since")
                val entries =
                    since?.let { marker ->
                        val index = allEntries.indexOfFirst { it.id == marker }
                        if (index < 0) error("Entry not found: $marker") else allEntries.drop(index + 1)
                    } ?: allEntries
                buildJsonObject {
                    put(
                        "entries",
                        buildJsonArray {
                            entries.forEach {
                                add(encodeSessionEntryForWire(it))
                            }
                        },
                    )
                    put("leafId", runtime.session.sessionManager.getLeafId())
                }
            }

            "get_tree", "get-tree" -> {
                buildJsonObject {
                    put("tree", encodeTree(runtime.session.sessionManager.getTree()))
                    put("leafId", runtime.session.sessionManager.getLeafId())
                }
            }

            "get_last_assistant_text", "get-last-assistant-text" -> {
                buildJsonObject { put("text", runtime.session.getLastAssistantText()) }
            }

            "set_session_name", "set-session-name" -> {
                val name = request.payload.requiredString("name").trim()
                require(name.isNotEmpty()) { "Session name cannot be empty" }
                runtime.session.setSessionName(name)
                emptyJson()
            }

            "clone" -> {
                val leaf =
                    runtime.session.sessionManager.getLeafId()
                        ?: error("Cannot clone session without a current entry")
                val result = runtime.fork(leaf, SessionForkPosition.AT)
                ensureBound()
                buildJsonObject { put("cancelled", result.cancelled) }
            }

            "get_commands", "get-commands" -> {
                buildJsonObject {
                    put(
                        "commands",
                        buildJsonArray {
                            runtime.session.getRegisteredExtensionCommands().forEach { command ->
                                add(
                                    buildJsonObject {
                                        put("name", command.invocationName)
                                        command.command.description?.let { put("description", it) }
                                        put("source", "extension")
                                        put("sourceInfo", encodeSourceInfo(command.sourceInfo))
                                    },
                                )
                            }
                            runtime.session.getPromptTemplatesForRpc().forEach { template ->
                                add(
                                    buildJsonObject {
                                        put("name", template.name)
                                        put("description", template.description)
                                        put("source", "prompt")
                                        put("sourceInfo", encodeSourceInfo(template.sourceInfo))
                                    },
                                )
                            }
                            runtime.session.getSkillsForRpc().forEach { skill ->
                                add(
                                    buildJsonObject {
                                        put("name", "skill:${skill.name}")
                                        put("description", skill.description)
                                        put("source", "skill")
                                        put("sourceInfo", encodeSourceInfo(skill.sourceInfo))
                                    },
                                )
                            }
                        },
                    )
                }
            }

            "respond", "interaction_response", "interaction-response" -> {
                val broker = interactionBroker ?: error("RPC interaction responses are not enabled")
                val response = decodeInteractionResponse(request.payload)
                if (!broker.respond(response)) {
                    error("Unknown or incompatible interaction request: ${response.requestId}")
                }
                emptyJson()
            }

            "shutdown", "quit" -> {
                shutdownRequested = true
                runtime.dispose()
                emptyJson()
            }

            else -> {
                error("Unknown RPC command: ${request.command}")
            }
        }

    private fun onSessionEvent(event: AgentSessionEvent) {
        mutableEvents.tryEmit(PiRpcCodec.event(event))
    }

    private fun encodeSourceInfo(
        sourceInfo: dev.xuexue.pi.coding.resources.ResourceSourceInfo,
    ): JsonObject =
        buildJsonObject {
            put("path", sourceInfo.path)
            put("source", sourceInfo.source)
            put("scope", sourceInfo.scope.name.lowercase())
            put("origin", sourceInfo.origin.name.lowercase())
            sourceInfo.baseDirectory?.let { put("baseDir", it) }
        }

    private fun ensureBound() {
        val session = runtime.session
        if (session.sessionId == boundSessionId) return
        subscription.dispose()
        subscription = session.subscribe(::onSessionEvent)
        boundSessionId = session.sessionId
    }

    private fun buildJsonObjectWithMessages(): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    runtime.session.messages.forEach {
                        add(
                            dev.xuexue.pi.coding.session
                                .encodeAgentMessageForWire(it),
                        )
                    }
                },
            )
        }

    private fun decodeInteractionResponse(payload: JsonObject): InteractionResponse {
        val requestId = payload.requiredString("requestId", "id")
        val pendingRequest = interactionBroker?.requests?.value?.firstOrNull { it.id == requestId }
        val kind =
            payload.string("kind", "method")?.lowercase()
                ?: when {
                    payload["confirmed"] != null -> "confirm"
                    payload["value"] != null -> "input"
                    payload["cancelled"] != null -> "cancelled"
                    else -> error("Unknown interaction response kind")
                }.let { inferred ->
                    if (payload["value"] != null && inferred == "input") {
                        pendingRequest?.kind?.name?.lowercase() ?: inferred
                    } else {
                        inferred
                    }
                }
        return when (kind) {
            "confirm" -> {
                InteractionResponse.Confirm(requestId, payload.boolean("accepted", "confirmed") ?: false)
            }

            "select" -> {
                InteractionResponse.Select(
                    requestId,
                    payload.int("selectedIndex") ?: selectIndex(requestId, payload),
                )
            }

            "input" -> {
                InteractionResponse.Input(requestId, payload.string("value").orEmpty())
            }

            "editor" -> {
                InteractionResponse.Editor(requestId, payload.string("value").orEmpty())
            }

            "cancelled", "cancel" -> {
                InteractionResponse.Cancelled(requestId, payload.string("reason").orEmpty())
            }

            else -> {
                error("Unknown interaction response kind")
            }
        }
    }

    private fun parseQueueMode(value: String): dev.xuexue.pi.agent.QueueMode =
        dev.xuexue.pi.agent.QueueMode.entries
            .firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: error("Unknown queue mode: $value")

    private fun JsonObject.requiredString(vararg names: String): String = string(*names) ?: missing(names)

    private fun JsonObject.string(vararg names: String): String? = primitive(names)?.contentOrNull

    private fun JsonObject.boolean(vararg names: String): Boolean? = primitive(names)?.booleanOrNull

    private fun JsonObject.requiredBoolean(name: String): Boolean = boolean(name) ?: error("RPC payload is missing $name")

    private fun JsonObject.primitive(names: Array<out String>): JsonPrimitive? = firstPrimitive(names)

    private fun JsonObject.firstPrimitive(names: Array<out String>): JsonPrimitive? {
        var result: JsonPrimitive? = null
        for (name in names) {
            result = this[name]?.jsonPrimitive
            if (result != null) break
        }
        return result
    }

    private fun missing(names: Array<out String>): Nothing {
        val joined = names.joinToString(" or ")
        throw IllegalArgumentException("RPC payload is missing $joined")
    }

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.streamingBehavior(): StreamingBehavior? =
        string("streamingBehavior")?.let { value ->
            StreamingBehavior.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown streaming behavior: $value")
        }

    private fun JsonObject.images(): List<ImageContent> =
        (this["images"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { element ->
                runCatching {
                    PiRpcCodec.json.decodeFromJsonElement(ImageContent.serializer(), element)
                }.getOrNull()
            }.orEmpty()

    private fun emptyJson(): JsonObject = JsonObject(emptyMap())

    fun isShutdownRequested(): Boolean = shutdownRequested

    private fun selectIndex(
        requestId: String,
        payload: JsonObject,
    ): Int {
        val value = payload.string("value") ?: error("Missing selected value")
        val request = interactionBroker?.requests?.value?.firstOrNull { it.id == requestId }
        return (request as? dev.xuexue.pi.coding.InteractionRequest.Select)
            ?.options
            ?.indexOf(value)
            ?.takeIf { it >= 0 }
            ?: error("Unknown selected value: $value")
    }

    private fun encodeSessionStats(stats: dev.xuexue.pi.coding.SessionStats): JsonObject =
        buildJsonObject {
            stats.sessionFile?.let { put("sessionFile", it) }
            put("sessionId", stats.sessionId)
            put("userMessages", stats.userMessages)
            put("assistantMessages", stats.assistantMessages)
            put("toolCalls", stats.toolCalls)
            put("toolResults", stats.toolResults)
            put("totalMessages", stats.totalMessages)
            put(
                "tokens",
                buildJsonObject {
                    put("input", stats.tokens.input)
                    put("output", stats.tokens.output)
                    put("cacheRead", stats.tokens.cacheRead)
                    put("cacheWrite", stats.tokens.cacheWrite)
                    put("total", stats.tokens.total)
                },
            )
            put("cost", stats.cost)
        }

    private fun encodeBashResult(result: dev.xuexue.pi.coding.BashResult): JsonObject =
        buildJsonObject {
            put("output", result.output)
            result.exitCode?.let { put("exitCode", it) }
            put("cancelled", result.cancelled)
            put("truncated", result.truncated)
            result.fullOutputPath?.let { put("fullOutputPath", it) }
        }

    private fun encodeTree(
        nodes: List<SessionTreeNode>,
    ): kotlinx.serialization.json.JsonArray =
        buildJsonArray {
            nodes.forEach { node ->
                add(
                    buildJsonObject {
                        put("entry", encodeSessionEntryForWire(node.entry))
                        node.label?.let { put("label", it) }
                        put("children", encodeTree(node.children))
                    },
                )
            }
        }
}
