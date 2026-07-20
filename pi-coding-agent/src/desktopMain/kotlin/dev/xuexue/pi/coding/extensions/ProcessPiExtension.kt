@file:Suppress(
    "InjectDispatcher",
    "LongMethod",
    "MatchingDeclarationName",
    "MaxLineLength",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantImages
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.AssistantMessageEventStream
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.ImageOutputContent
import dev.xuexue.pi.ai.ImagesContext
import dev.xuexue.pi.ai.ImagesModel
import dev.xuexue.pi.ai.ImagesOptions
import dev.xuexue.pi.ai.ImagesStopReason
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiJson
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ThinkingContent
import dev.xuexue.pi.ai.ToolCall
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionResponse
import dev.xuexue.pi.coding.InteractionSeverity
import dev.xuexue.pi.coding.resources.ResourceExtensionPaths
import dev.xuexue.pi.coding.resources.ResourceOrigin
import dev.xuexue.pi.coding.resources.ResourcePathContribution
import dev.xuexue.pi.coding.resources.ResourcePathMetadata
import dev.xuexue.pi.coding.resources.ResourceScope
import dev.xuexue.pi.coding.resources.ResourceSourceInfo
import dev.xuexue.pi.coding.resources.syntheticResourceSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

/** Process-hosted extension using one JSON object per stdin/stdout line. */
class ProcessPiExtension(
    override val id: String,
    private val command: List<String>,
    private val workingDirectory: String,
    private val environment: Map<String, String> = emptyMap(),
    override val sourceInfo: ResourceSourceInfo =
        syntheticResourceSourceInfo(
            path = workingDirectory,
            source = "process:$id",
            baseDirectory = workingDirectory,
        ),
) : PiExtension {
    init {
        require(command.isNotEmpty()) { "Process plugin command must not be empty" }
    }

    override suspend fun activate(api: PiExtensionApi): PiExtensionHandle {
        val bridge = ProcessPluginBridge(id, command, workingDirectory, environment)
        bridge.start()
        try {
            bridge.initialize(api)
            return PiExtensionHandle { bridge.dispose() }
        } catch (error: Throwable) {
            bridge.dispose()
            throw error
        }
    }

    companion object {
        fun fromManifest(
            manifest: PiPluginManifest,
            pluginDirectory: String,
            environment: Map<String, String> = emptyMap(),
            sourceInfo: ResourceSourceInfo =
                syntheticResourceSourceInfo(
                    path = pluginDirectory,
                    source = "local",
                    baseDirectory = pluginDirectory,
                ),
        ): ProcessPiExtension {
            manifest.validate()
            require(manifest.runtime == PiPluginRuntime.PROCESS) {
                "Plugin ${manifest.id} declares runtime ${manifest.runtime}, expected process"
            }
            val executable =
                requireNotNull(manifest.executable) {
                    "Process plugin ${manifest.id} is missing executable"
                }
            val resolved = File(pluginDirectory, executable).takeIf { !File(executable).isAbsolute } ?: File(executable)
            return ProcessPiExtension(
                id = manifest.id,
                command = listOf(resolved.path),
                workingDirectory = pluginDirectory,
                environment = environment,
                sourceInfo = sourceInfo,
            )
        }
    }
}

/** Owns the ordered JSONL reader so tool, event, text and image requests cannot interleave. */
@Suppress("LargeClass")
private class ProcessPluginBridge(
    private val extensionId: String,
    private val command: List<String>,
    private val workingDirectory: String,
    private val environment: Map<String, String>,
) {
    private companion object {
        const val CANCEL_GRACE_TIMEOUT_MS = 2_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private val writerLock = Any()
    private val messages = Channel<JsonObject>(Channel.UNLIMITED)
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var readerJob: kotlinx.coroutines.Job? = null

    fun start() {
        val builder =
            ProcessBuilder(command)
                .directory(File(workingDirectory))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
        environment.forEach { (key, value) -> builder.environment()[key] = value }
        val started = builder.start()
        process = started
        reader = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        val input = requireNotNull(reader)
        readerJob =
            streamScope.launch {
                try {
                    while (true) {
                        val line = input.readLine() ?: break
                        messages.send(json.parseToJsonElement(line).jsonObject)
                    }
                    messages.close()
                } catch (error: Throwable) {
                    messages.close(error)
                }
            }
    }

    suspend fun initialize(api: PiExtensionApi) =
        withContext(Dispatchers.IO) {
            requestMutex.withLock {
                synchronized(writerLock) {
                    sendLocked(
                        buildJsonObject {
                            put("type", "initialize")
                            put("extensionId", extensionId)
                            put("apiVersion", "1")
                        },
                    )
                }
                while (true) {
                    val message = messageObject()
                    when (message["type"]?.jsonPrimitive?.contentOrNull) {
                        "ready" -> break
                        "register_tool" -> api.registerTool(parseTool(message))
                        "register_command" -> api.registerCommand(parseCommand(message))
                        "register_provider" -> api.registerProvider(parseProvider(message))
                        "register_image_provider" -> api.registerImageProvider(parseImageProvider(message))
                        "subscribe" -> registerSubscription(api, message)
                        "contribute_resources" -> api.contributeResources(parseResources(message))
                        "error" -> error(message["message"]?.jsonPrimitive?.contentOrNull ?: "Process plugin failed")
                        else -> error("Unknown process plugin message: ${message["type"]}")
                    }
                }
            }
        }

    suspend fun dispose() =
        withContext(Dispatchers.IO) {
            val current =
                synchronized(writerLock) {
                    val running = process ?: return@synchronized null
                    runCatching {
                        sendLocked(buildJsonObject { put("type", "shutdown") })
                    }
                    running
                }
            current?.destroy()
            if (current != null && !current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly()
            }
            terminateProcessHandles()
            streamScope.cancel()
        }

    private fun parseProvider(message: JsonObject): PiProvider {
        val providerId = requireString(message, "id")
        return ProcessPiProvider(
            id = providerId,
            name = message["name"]?.jsonPrimitive?.contentOrNull ?: providerId,
            supportedApis = message["supportedApis"]?.asStringList().orEmpty().toSet(),
            bridge = this,
        )
    }

    private fun parseImageProvider(message: JsonObject): PiImageProvider {
        val providerId = requireString(message, "id")
        return ProcessPiImageProvider(
            id = providerId,
            name = message["name"]?.jsonPrimitive?.contentOrNull ?: providerId,
            supportedApis = message["supportedApis"]?.asStringList().orEmpty().toSet(),
            bridge = this,
        )
    }

    fun streamProvider(
        providerId: String,
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        if (options.stream.signal?.isAborted == true) {
            stream.push(providerError(model, "Operation aborted", aborted = true))
            return stream
        }
        val requestId = UUID.randomUUID().toString()
        val started = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val subscription =
            options.stream.signal?.onAbort {
                if (started.get()) sendCancellation(requestId, cancelSent)
            }
        val job =
            streamScope.launch {
                try {
                    withOptionalTimeout(options.stream.timeoutMs) {
                        requestMutex.withLock {
                            if (options.stream.signal?.isAborted == true) {
                                throw CancellationException("Operation aborted")
                            }
                            synchronized(writerLock) {
                                sendLocked(providerStreamRequest(providerId, model, context, options, requestId))
                            }
                            started.set(true)
                            if (options.stream.signal?.isAborted == true) sendCancellation(requestId, cancelSent)
                            readProviderResponses(requestId, model, options.stream.signal, stream)
                            acknowledgeCancellation(requestId)
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    if (started.get()) sendCancellation(requestId, cancelSent)
                    stream.push(providerError(model, error.message ?: "Process provider timed out"))
                } catch (error: CancellationException) {
                    stream.push(providerError(model, error.message ?: "Operation aborted", aborted = true))
                } catch (error: Throwable) {
                    stream.push(
                        providerError(
                            model,
                            error.message ?: error.toString(),
                            aborted = options.stream.signal?.isAborted == true,
                        ),
                    )
                }
            }
        job.invokeOnCompletion { subscription?.dispose() }
        return stream
    }

    private suspend fun readProviderResponses(
        requestId: String,
        model: Model,
        signal: AbortSignal?,
        stream: AssistantMessageEventStream,
    ) {
        var terminal = false
        while (!terminal) {
            val message = messageObject()
            if (matchesRequest(message, requestId)) {
                if (signal?.isAborted == true) {
                    acknowledgeCancellation(requestId)
                    throw CancellationException("Operation aborted")
                }
                terminal = forwardProviderResponse(message, model, stream)
            }
        }
    }

    private fun forwardProviderResponse(
        message: JsonObject,
        model: Model,
        stream: AssistantMessageEventStream,
    ): Boolean =
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "provider_event", "provider_done" -> {
                val event = parseProviderEvent(message)
                stream.push(event)
                message["type"]?.jsonPrimitive?.contentOrNull == "provider_done" ||
                    event is AssistantMessageEvent.Done ||
                    event is AssistantMessageEvent.Error
            }

            "provider_error" -> {
                stream.push(
                    providerError(
                        model,
                        message["message"]?.jsonPrimitive?.contentOrNull ?: "Process provider failed",
                    ),
                )
                true
            }

            else -> {
                error("Unknown process provider message: ${message["type"]}")
            }
        }

    suspend fun generateImages(
        providerId: String,
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages {
        if (options.signal?.isAborted == true) return imageError(model, "Operation aborted", aborted = true)
        val requestId = UUID.randomUUID().toString()
        val started = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val subscription =
            options.signal?.onAbort {
                if (started.get()) sendCancellation(requestId, cancelSent)
            }
        return try {
            withOptionalTimeout(options.timeoutMs) {
                withContext(Dispatchers.IO) {
                    requestMutex.withLock {
                        if (options.signal?.isAborted == true) {
                            imageError(model, "Operation aborted", aborted = true)
                        } else {
                            synchronized(writerLock) {
                                sendLocked(imageProviderRequest(providerId, model, context, options, requestId))
                            }
                            started.set(true)
                            if (options.signal?.isAborted == true) sendCancellation(requestId, cancelSent)
                            val result = readImageProviderResponse(model, requestId, options.signal)
                            acknowledgeCancellation(requestId)
                            result
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            if (started.get()) sendCancellation(requestId, cancelSent)
            imageError(model, error.message ?: "Process image provider timed out")
        } catch (error: CancellationException) {
            imageError(model, error.message ?: "Operation aborted", aborted = true)
        } catch (error: Throwable) {
            imageError(
                model,
                error.message ?: error.toString(),
                aborted = options.signal?.isAborted == true,
            )
        } finally {
            subscription?.dispose()
        }
    }

    private suspend fun readImageProviderResponse(
        model: ImagesModel,
        requestId: String,
        signal: AbortSignal?,
    ): AssistantImages {
        var result: AssistantImages? = null
        while (result == null) {
            val message = messageObject()
            result =
                if (!matchesRequest(message, requestId)) {
                    null
                } else if (signal?.isAborted == true) {
                    acknowledgeCancellation(requestId)
                    imageError(model, "Operation aborted", aborted = true)
                } else {
                    parseImageProviderResponse(message, model)
                }
        }
        return result
    }

    private fun parseImageProviderResponse(
        message: JsonObject,
        model: ImagesModel,
    ): AssistantImages =
        when (message["type"]?.jsonPrimitive?.contentOrNull) {
            "image_result" -> {
                val payload = message["result"] ?: message["payload"]
                parseAssistantImages(
                    model,
                    requireNotNull(payload) { "Process image provider result is missing result" },
                )
            }

            "image_error" -> {
                imageError(
                    model,
                    message["message"]?.jsonPrimitive?.contentOrNull ?: "Process image provider failed",
                )
            }

            else -> {
                error("Unknown process image provider message: ${message["type"]}")
            }
        }

    private fun parseAssistantImages(
        model: ImagesModel,
        payload: JsonElement,
    ): AssistantImages {
        val value = payload.jsonObject
        val stopReason =
            when (value["stopReason"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "stop" -> ImagesStopReason.STOP
                "aborted" -> ImagesStopReason.ABORTED
                else -> ImagesStopReason.ERROR
            }
        return AssistantImages(
            api = value["api"]?.jsonPrimitive?.contentOrNull ?: model.api,
            provider = value["provider"]?.jsonPrimitive?.contentOrNull ?: model.provider,
            model = value["model"]?.jsonPrimitive?.contentOrNull ?: model.id,
            output = value["output"]?.jsonArray?.mapNotNull(::parseImageOutput).orEmpty(),
            responseId = value["responseId"]?.jsonPrimitive?.contentOrNull,
            usage = value["usage"]?.let { PiJson.decodeFromJsonElement(Usage.serializer(), it) },
            stopReason = stopReason,
            errorMessage = value["errorMessage"]?.jsonPrimitive?.contentOrNull,
            timestamp = value["timestamp"]?.jsonPrimitive?.longOrNull ?: Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun parseImageOutput(element: JsonElement): ImageOutputContent? {
        val value = element.jsonObject
        return when (value["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> {
                TextContent(value["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }

            "image" -> {
                ImageContent(
                    data = value["data"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mimeType = value["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }

            else -> {
                null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun imageProviderRequest(
        providerId: String,
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
        requestId: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "image_generate")
            put("requestId", requestId)
            put("providerId", providerId)
            put(
                "model",
                buildJsonObject {
                    put("id", model.id)
                    put("name", model.name)
                    put("api", model.api)
                    put("provider", model.provider)
                    put("baseUrl", model.baseUrl)
                    put(
                        "input",
                        buildJsonArray {
                            model.input.forEach { add(JsonPrimitive(it.name.lowercase())) }
                        },
                    )
                    put(
                        "output",
                        buildJsonArray {
                            model.output.forEach { add(JsonPrimitive(it.name.lowercase())) }
                        },
                    )
                    put("cost", PiJson.encodeToJsonElement(ModelCost.serializer(), model.cost))
                    model.headers?.let { headers ->
                        put(
                            "headers",
                            buildJsonObject {
                                headers.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                },
            )
            put(
                "context",
                buildJsonObject {
                    put(
                        "input",
                        buildJsonArray {
                            context.input.forEach { block ->
                                when (block) {
                                    is TextContent -> {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", block.text)
                                            },
                                        )
                                    }

                                    is ImageContent -> {
                                        add(
                                            buildJsonObject {
                                                put("type", "image")
                                                put("data", block.data)
                                                put("mimeType", block.mimeType)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                },
            )
            put(
                "options",
                buildJsonObject {
                    options.apiKey?.let { put("apiKey", it) }
                    options.headers?.let { headers ->
                        put(
                            "headers",
                            buildJsonObject {
                                headers.forEach { (key, value) -> value?.let { put(key, it) } }
                            },
                        )
                    }
                    options.timeoutMs?.let { put("timeoutMs", it) }
                    options.maxRetries?.let { put("maxRetries", it) }
                    options.maxRetryDelayMs?.let { put("maxRetryDelayMs", it) }
                    options.env?.let { environment ->
                        put(
                            "env",
                            buildJsonObject {
                                environment.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                    put("signalAborted", options.signal?.isAborted ?: false)
                },
            )
        }

    private fun imageError(
        model: ImagesModel,
        message: String,
        aborted: Boolean = false,
    ): AssistantImages =
        AssistantImages(
            api = model.api,
            provider = model.provider,
            model = model.id,
            output = emptyList(),
            stopReason = if (aborted) ImagesStopReason.ABORTED else ImagesStopReason.ERROR,
            errorMessage = message,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )

    private fun providerStreamRequest(
        providerId: String,
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
        requestId: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "provider_stream")
            put("requestId", requestId)
            put("providerId", providerId)
            put("model", PiJson.encodeToJsonElement(Model.serializer(), model))
            put("context", providerContextPayload(context))
            put("options", providerOptionsPayload(options))
        }

    private fun providerContextPayload(context: Context): JsonObject =
        buildJsonObject {
            context.systemPrompt?.let { put("systemPrompt", it) }
            put(
                "messages",
                buildJsonArray {
                    context.messages.forEach { message ->
                        add(PiJson.encodeToJsonElement(Message.serializer(), message))
                    }
                },
            )
            put(
                "tools",
                buildJsonArray {
                    context.tools.orEmpty().forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            },
                        )
                    }
                },
            )
        }

    @Suppress("CyclomaticComplexMethod")
    private fun providerOptionsPayload(options: SimpleStreamOptions): JsonObject =
        buildJsonObject {
            options.reasoning?.let { put("reasoning", it.name.lowercase()) }
            options.thinkingBudgets?.let { budgets ->
                put(
                    "thinkingBudgets",
                    buildJsonObject {
                        budgets.minimal?.let { put("minimal", it) }
                        budgets.low?.let { put("low", it) }
                        budgets.medium?.let { put("medium", it) }
                        budgets.high?.let { put("high", it) }
                    },
                )
            }
            put(
                "stream",
                buildJsonObject {
                    options.stream.temperature?.let { put("temperature", it) }
                    options.stream.maxTokens?.let { put("maxTokens", it) }
                    options.stream.apiKey?.let { put("apiKey", it) }
                    options.stream.transport?.let { put("transport", it.name.lowercase()) }
                    options.stream.cacheRetention?.let { put("cacheRetention", it.name.lowercase()) }
                    options.stream.sessionId?.let { put("sessionId", it) }
                    options.stream.timeoutMs?.let { put("timeoutMs", it) }
                    options.stream.websocketConnectTimeoutMs?.let { put("websocketConnectTimeoutMs", it) }
                    options.stream.maxRetries?.let { put("maxRetries", it) }
                    options.stream.maxRetryDelayMs?.let { put("maxRetryDelayMs", it) }
                    options.stream.headers?.let { headers ->
                        put(
                            "headers",
                            buildJsonObject {
                                headers.forEach { (key, value) -> value?.let { put(key, it) } }
                            },
                        )
                    }
                    options.stream.metadata?.let { put("metadata", it) }
                    options.stream.env?.let { environment ->
                        put(
                            "env",
                            buildJsonObject {
                                environment.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    }
                    put("signalAborted", options.stream.signal?.isAborted ?: false)
                },
            )
        }

    private fun parseProviderEvent(message: JsonObject): AssistantMessageEvent {
        val payload = message["event"] ?: message["payload"] ?: error("Process provider event is missing event")
        return PiJson.decodeFromJsonElement(AssistantMessageEvent.serializer(), payload)
    }

    private fun providerError(
        model: Model,
        message: String,
        aborted: Boolean = false,
    ): AssistantMessageEvent.Error {
        val error =
            AssistantMessage(
                content = emptyList(),
                api = model.api,
                provider = model.provider,
                model = model.id,
                usage = Usage(0, 0, 0, 0, totalTokens = 0, cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
                stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
                errorMessage = message,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        return AssistantMessageEvent.Error(
            reason = if (aborted) FailureStopReason.ABORTED else FailureStopReason.ERROR,
            error = error,
        )
    }

    private fun parseTool(message: JsonObject): AgentTool {
        val name = requireString(message, "name")
        val parameters = message["parameters"]?.jsonObject ?: buildJsonObject {}
        return AgentTool(
            name = name,
            label = message["label"]?.jsonPrimitive?.contentOrNull ?: name,
            description = message["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            parameters = parameters,
            promptSnippet = message["promptSnippet"]?.jsonPrimitive?.contentOrNull,
            promptGuidelines = message["promptGuidelines"]?.asStringList().orEmpty(),
            execute = { toolCallId, params, signal, _ -> invokeTool(toolCallId, name, params, signal) },
        )
    }

    private fun parseCommand(message: JsonObject): PiExtensionCommand {
        val name = requireString(message, "name")
        return PiExtensionCommand(
            name = name,
            description = message["description"]?.jsonPrimitive?.contentOrNull,
            handler = { args, _ -> invokeCommand(name, args) },
        )
    }

    private fun parseResources(message: JsonObject): ResourceExtensionPaths {
        val metadata =
            ResourcePathMetadata(
                source = "process:$extensionId",
                scope = ResourceScope.TEMPORARY,
                origin = ResourceOrigin.PACKAGE,
            )

        fun paths(name: String) =
            message[name]
                ?.asStringList()
                .orEmpty()
                .map { ResourcePathContribution(it, metadata) }
        return ResourceExtensionPaths(skillPaths = paths("skillPaths"), promptPaths = paths("promptPaths"))
    }

    private fun registerSubscription(
        api: PiExtensionApi,
        message: JsonObject,
    ) {
        val eventName = requireString(message, "event")
        val eventType =
            PiExtensionEventType.entries.firstOrNull { it.name.equals(eventName, ignoreCase = true) }
                ?: error("Unknown process plugin event: $eventName")
        api.on(eventType) { event, context -> requestEvent(event, context) }
    }

    private suspend fun invokeTool(
        toolCallId: String,
        name: String,
        params: JsonObject,
        signal: AbortSignal?,
    ): AgentToolResult =
        withContext(Dispatchers.IO) {
            val response =
                request(
                    buildJsonObject {
                        put("type", "tool_call")
                        put("requestId", UUID.randomUUID().toString())
                        put("toolCallId", toolCallId)
                        put("name", name)
                        put("params", params)
                    },
                    signal = signal,
                )
            val content: List<dev.xuexue.pi.ai.ToolResultContent> =
                response["content"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { block -> block.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .map(::TextContent)
            AgentToolResult(
                content = if (content.isEmpty()) listOf(TextContent("")) else content,
                details = response["details"] ?: buildJsonObject {},
                terminate = response["terminate"]?.jsonPrimitive?.booleanOrNull,
            )
        }

    private suspend fun invokeCommand(
        name: String,
        args: String,
    ) {
        withContext(Dispatchers.IO) {
            request(
                buildJsonObject {
                    put("type", "command")
                    put("requestId", UUID.randomUUID().toString())
                    put("name", name)
                    put("args", args)
                },
            )
        }
    }

    private suspend fun requestEvent(
        event: PiExtensionEvent,
        context: PiExtensionContext,
    ): PiExtensionEventResult? =
        withContext(Dispatchers.IO) {
            val response =
                request(
                    buildJsonObject {
                        put("type", "event")
                        put("requestId", UUID.randomUUID().toString())
                        put("event", event.type.name.lowercase())
                        put("payload", eventPayload(event))
                    },
                    context,
                )
            when (response["result"]?.jsonPrimitive?.contentOrNull) {
                "block" -> {
                    PiExtensionEventResult.ToolCall(
                        block = true,
                        reason = response["reason"]?.jsonPrimitive?.contentOrNull,
                    )
                }

                "system_prompt" -> {
                    PiExtensionEventResult.BeforeAgentStart(
                        systemPrompt = response["systemPrompt"]?.jsonPrimitive?.contentOrNull,
                    )
                }

                "cancel" -> {
                    when (event.type) {
                        PiExtensionEventType.SESSION_BEFORE_SWITCH -> PiExtensionEventResult.SessionBeforeSwitch(cancel = true)
                        PiExtensionEventType.SESSION_BEFORE_FORK -> PiExtensionEventResult.SessionBeforeFork(cancel = true)
                        else -> null
                    }
                }

                else -> {
                    null
                }
            }
        }

    // Keep the wire payload exhaustive so process plugins can observe the same event data as Kotlin plugins.
    @Suppress("CyclomaticComplexMethod")
    private fun eventPayload(event: PiExtensionEvent): JsonObject =
        buildJsonObject {
            when (event) {
                is PiExtensionEvent.ProjectTrust -> {
                    put("cwd", event.cwd)
                }

                is PiExtensionEvent.ResourcesDiscover -> {
                    put("cwd", event.cwd)
                    put("reason", event.reason.name.lowercase())
                }

                is PiExtensionEvent.SessionStart -> {
                    put("reason", event.reason.name.lowercase())
                    event.previousSessionFile?.let { put("previousSessionFile", it) }
                }

                is PiExtensionEvent.SessionShutdown -> {
                    put("reason", event.reason.name.lowercase())
                    event.targetSessionFile?.let { put("targetSessionFile", it) }
                }

                is PiExtensionEvent.SessionBeforeSwitch -> {
                    put("reason", event.reason)
                    event.targetSessionFile?.let { put("targetSessionFile", it) }
                }

                is PiExtensionEvent.SessionBeforeFork -> {
                    put("entryId", event.entryId)
                    put("position", event.position)
                }

                is PiExtensionEvent.Context -> {
                    put("messages", messagesPayload(event.messages))
                }

                is PiExtensionEvent.BeforeAgentStart -> {
                    put("prompt", event.prompt)
                    put("systemPrompt", event.systemPrompt)
                }

                PiExtensionEvent.AgentStart,
                PiExtensionEvent.AgentSettled,
                PiExtensionEvent.TurnStart,
                -> {
                    Unit
                }

                is PiExtensionEvent.AgentEnd -> {
                    put("messages", messagesPayload(event.messages))
                }

                is PiExtensionEvent.TurnEnd -> {
                    put("message", messagePayload(event.message))
                }

                is PiExtensionEvent.MessageStart -> {
                    put("message", messagePayload(event.message))
                }

                is PiExtensionEvent.MessageUpdate -> {
                    put("message", messagePayload(event.message))
                }

                is PiExtensionEvent.MessageEnd -> {
                    put("message", messagePayload(event.message))
                }

                is PiExtensionEvent.ToolExecutionStart -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                }

                is PiExtensionEvent.ToolExecutionUpdate -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                    put("partialResult", toolResultPayload(event.partialResult))
                }

                is PiExtensionEvent.ToolExecutionEnd -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("result", toolResultPayload(event.result))
                    put("isError", event.isError)
                }

                is PiExtensionEvent.ToolCall -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                }

                is PiExtensionEvent.ToolResult -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("content", contentPayload(event.content))
                    event.details?.let { put("details", it) }
                    put("isError", event.isError)
                }
            }
        }

    private fun messagesPayload(messages: List<AgentMessage>): JsonArray =
        buildJsonArray {
            messages.forEach { add(messagePayload(it)) }
        }

    private fun messagePayload(message: AgentMessage): JsonObject =
        buildJsonObject {
            put("timestamp", message.timestamp)
            when (message) {
                is UserMessage -> {
                    put("role", "user")
                    put("content", userMessageText(message))
                }

                is AssistantMessage -> {
                    put("role", "assistant")
                    put("content", assistantMessageText(message))
                    put("stopReason", message.stopReason.name.lowercase())
                    message.errorMessage?.let { put("errorMessage", it) }
                }

                is ToolResultMessage -> {
                    put("role", "toolResult")
                    put("toolCallId", message.toolCallId)
                    put("toolName", message.toolName)
                    put("content", contentPayload(message.content))
                    put("isError", message.isError)
                }

                else -> {
                    put("role", message::class.simpleName.orEmpty())
                }
            }
        }

    private fun userMessageText(message: UserMessage): String =
        when (val content = message.content) {
            is UserText -> content.value
            is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

    private fun assistantMessageText(message: AssistantMessage): String =
        message.content.joinToString("") { block ->
            when (block) {
                is TextContent -> block.text
                is ThinkingContent -> block.thinking
                is ToolCall -> block.name
                else -> ""
            }
        }

    private fun contentPayload(content: List<dev.xuexue.pi.ai.ToolResultContent>): JsonArray =
        buildJsonArray {
            content.forEach { block ->
                when (block) {
                    is TextContent -> {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", block.text)
                            },
                        )
                    }

                    else -> {
                        add(buildJsonObject { put("type", "image") })
                    }
                }
            }
        }

    private fun toolResultPayload(result: AgentToolResult): JsonObject =
        buildJsonObject {
            put("content", contentPayload(result.content))
            put("details", result.details)
            result.terminate?.let { put("terminate", it) }
        }

    private suspend fun request(
        message: JsonObject,
        context: PiExtensionContext? = null,
        signal: AbortSignal? = null,
    ): JsonObject {
        val requestId = message["requestId"]?.jsonPrimitive?.contentOrNull
        val started = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val subscription =
            signal?.onAbort {
                if (started.get() && requestId != null) sendCancellation(requestId, cancelSent)
            }
        try {
            return requestMutex.withLock {
                if (signal?.isAborted == true) throw CancellationException("Operation aborted")
                synchronized(writerLock) {
                    sendLocked(message)
                }
                started.set(true)
                if (signal?.isAborted == true && requestId != null) sendCancellation(requestId, cancelSent)
                var terminal: JsonObject? = null
                while (terminal == null) {
                    val response = messageObject()
                    terminal = handleRequestResponse(response, requestId, context, signal)
                }
                requireNotNull(terminal).also { requestId?.let(::acknowledgeCancellation) }
            }
        } catch (error: Throwable) {
            rethrowRequestError(error, signal)
        } finally {
            subscription?.dispose()
        }
    }

    private fun rethrowRequestError(
        error: Throwable,
        signal: AbortSignal?,
    ): Nothing {
        if (signal?.isAborted == true) throw CancellationException("Operation aborted")
        throw error
    }

    private suspend fun handleRequestResponse(
        response: JsonObject,
        requestId: String?,
        context: PiExtensionContext?,
        signal: AbortSignal?,
    ): JsonObject? =
        when (response["type"]?.jsonPrimitive?.contentOrNull) {
            "interaction_request" -> {
                handleInteractionRequest(response, context)
                null
            }

            "notify" -> {
                val interaction =
                    context?.interaction
                        ?: error("Process plugin sent notification without a host")
                interaction.notify(parseNotify(response["request"]?.jsonObject ?: response))
                null
            }

            else -> {
                if (!matchesRequest(response, requestId)) {
                    null
                } else {
                    if (signal?.isAborted == true) {
                        requestId?.let(::acknowledgeCancellation)
                        throw CancellationException("Operation aborted")
                    }
                    response
                }
            }
        }

    private suspend fun handleInteractionRequest(
        response: JsonObject,
        context: PiExtensionContext?,
    ) {
        val interaction =
            context?.interaction
                ?: error("Process plugin requested interaction without a host")
        val request = parseInteractionRequest(response["request"]?.jsonObject ?: response)
        val result = interaction.request(request)
        synchronized(writerLock) {
            sendLocked(
                buildJsonObject {
                    put("type", "interaction_response")
                    put("requestId", request.id)
                    put("response", interactionResponsePayload(result))
                },
            )
        }
    }

    private fun matchesRequest(
        response: JsonObject,
        requestId: String?,
    ): Boolean {
        val responseId = response["requestId"]?.jsonPrimitive?.contentOrNull
        return requestId == null || responseId == null || responseId == requestId
    }

    private fun sendCancellation(
        requestId: String,
        cancelSent: AtomicBoolean,
    ) {
        if (!cancelSent.compareAndSet(false, true)) return
        var sent = false
        synchronized(writerLock) {
            if (process == null) return@synchronized
            runCatching {
                sendLocked(
                    buildJsonObject {
                        put("type", "cancel")
                        put("requestId", requestId)
                    },
                )
                sent = true
            }
        }
        if (sent) {
            cancelledRequests += requestId
            streamScope.launch {
                delay(CANCEL_GRACE_TIMEOUT_MS)
                if (cancelledRequests.remove(requestId)) {
                    terminateProcessHandles("Process plugin did not acknowledge cancellation")
                }
            }
        }
    }

    private fun acknowledgeCancellation(requestId: String) {
        cancelledRequests.remove(requestId)
    }

    private fun terminateProcessHandles(reason: String = "Process plugin terminated") {
        val handles =
            synchronized(writerLock) {
                val captured = Triple(process, reader, writer)
                process = null
                writer = null
                reader = null
                captured
            }
        val current = handles.first
        if (current != null) {
            runCatching { current.destroy() }
            runCatching {
                if (!current.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly()
                }
            }
        }
        runCatching { handles.third?.close() }
        runCatching { handles.second?.close() }
        messages.close(IllegalStateException(reason))
        readerJob?.cancel()
    }

    private suspend fun <T> withOptionalTimeout(
        timeoutMs: Long?,
        block: suspend () -> T,
    ): T =
        if (timeoutMs == null) {
            block()
        } else {
            withTimeout(timeoutMs) { block() }
        }

    private fun sendLocked(message: JsonObject) {
        val output = writer ?: error("Process plugin is not running")
        output.write(message.toString())
        output.newLine()
        output.flush()
    }

    private suspend fun messageObject(): JsonObject {
        val result = messages.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull() ?: error("Process plugin exited unexpectedly")
    }

    private fun requireString(
        message: JsonObject,
        name: String,
    ): String =
        message[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
            ?: error("Process plugin message is missing $name")

    private fun JsonElement.asStringList(): List<String> {
        val array = this as? JsonArray
        return array?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }

    private fun parseInteractionRequest(message: JsonObject): InteractionRequest {
        val id = requireAnyString(message, "id", "requestId")
        val title = message["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body = message["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (message["kind"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "confirm" -> {
                InteractionRequest.Confirm(
                    id = id,
                    title = title,
                    message = body,
                    defaultValue = message["defaultValue"]?.jsonPrimitive?.booleanOrNull ?: false,
                    confirmLabel = message["confirmLabel"]?.jsonPrimitive?.contentOrNull ?: "Confirm",
                    cancelLabel = message["cancelLabel"]?.jsonPrimitive?.contentOrNull ?: "Cancel",
                )
            }

            "select" -> {
                InteractionRequest.Select(
                    id = id,
                    title = title,
                    message = body,
                    options = message["options"]?.asStringList().orEmpty(),
                    selectedIndex = message["selectedIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                    allowCancel = message["allowCancel"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }

            "input" -> {
                InteractionRequest.Input(
                    id = id,
                    title = title,
                    message = body,
                    initialValue = message["initialValue"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    placeholder = message["placeholder"]?.jsonPrimitive?.contentOrNull,
                    secret = message["secret"]?.jsonPrimitive?.booleanOrNull ?: false,
                    multiline = message["multiline"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            "editor" -> {
                InteractionRequest.Editor(
                    id = id,
                    title = title,
                    message = body,
                    initialValue = message["initialValue"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    language = message["language"]?.jsonPrimitive?.contentOrNull,
                )
            }

            else -> {
                error("Unknown process interaction kind: ${message["kind"]}")
            }
        }
    }

    private fun parseNotify(message: JsonObject): InteractionRequest.Notify =
        InteractionRequest.Notify(
            id = requireAnyString(message, "id", "requestId"),
            title = message["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            message = message["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            severity =
                message["severity"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    InteractionSeverity.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                } ?: InteractionSeverity.INFO,
            durationMs = message["durationMs"]?.jsonPrimitive?.longOrNull,
        )

    private fun requireAnyString(
        message: JsonObject,
        vararg names: String,
    ): String =
        names.firstNotNullOfOrNull { name -> message[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
            ?: error("Process plugin message is missing ${names.joinToString(" or ")}")

    private fun interactionResponsePayload(response: InteractionResponse): JsonObject =
        buildJsonObject {
            when (response) {
                is InteractionResponse.Confirm -> {
                    put("kind", "confirm")
                    put("accepted", response.accepted)
                }

                is InteractionResponse.Select -> {
                    put("kind", "select")
                    put("selectedIndex", response.selectedIndex)
                }

                is InteractionResponse.Input -> {
                    put("kind", "input")
                    put("value", response.value)
                }

                is InteractionResponse.Editor -> {
                    put("kind", "editor")
                    put("value", response.value)
                }

                is InteractionResponse.Cancelled -> {
                    put("kind", "cancelled")
                    put("reason", response.reason)
                }
            }
        }
}

private class ProcessPiProvider(
    override val id: String,
    override val name: String,
    override val supportedApis: Set<String>,
    private val bridge: ProcessPluginBridge,
) : PiProvider {
    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = bridge.streamProvider(id, model, context, options)
}

private class ProcessPiImageProvider(
    override val id: String,
    override val name: String,
    override val supportedApis: Set<String>,
    private val bridge: ProcessPluginBridge,
) : PiImageProvider {
    override suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages = bridge.generateImages(id, model, context, options)
}
