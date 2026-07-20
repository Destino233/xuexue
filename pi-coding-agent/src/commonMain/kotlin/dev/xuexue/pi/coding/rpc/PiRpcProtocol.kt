// The codec mirrors every public session event so the wire contract stays explicit and reviewable.
@file:Suppress("TooManyFunctions", "CyclomaticComplexMethod", "LongMethod")

package dev.xuexue.pi.coding.rpc

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.coding.AgentSession
import dev.xuexue.pi.coding.AgentSessionEvent
import dev.xuexue.pi.coding.AgentSessionState
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionResponse
import dev.xuexue.pi.coding.session.encodeAgentMessageForWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PiRpcRequest(
    val id: String?,
    val command: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

data class PiRpcResponse(
    val id: String?,
    val command: String,
    val success: Boolean,
    val payload: JsonObject = JsonObject(emptyMap()),
    val error: String? = null,
    /** Optional non-object response data. Set [dataPresent] for an explicit JSON null. */
    val data: JsonElement? = null,
    val dataPresent: Boolean = false,
)

data class PiRpcEvent(
    val event: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/** Canonical JSONL codec. It also accepts Pi-style flat command objects for interop. */
object PiRpcCodec {
    val json: Json =
        Json {
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    fun decodeRequest(line: String): PiRpcRequest {
        val objectValue = json.parseToJsonElement(line).jsonObject
        val type = objectValue.string("type")
        val command =
            when (type) {
                "request", "command" -> objectValue.string("command") ?: objectValue.string("method")
                else -> type
            }?.trim().orEmpty()
        require(command.isNotEmpty()) { "RPC request is missing command/type" }
        val id = objectValue.string("id")
        val payload =
            objectValue["payload"] as? JsonObject
                ?: buildJsonObject {
                    objectValue.forEach { (key, value) ->
                        val reserved =
                            if (type == "request" || type == "command") {
                                RESERVED_CANONICAL_REQUEST_FIELDS
                            } else {
                                RESERVED_FLAT_REQUEST_FIELDS
                            }
                        if (key !in reserved) put(key, value)
                    }
                }
        return PiRpcRequest(id, command, payload)
    }

    fun encodeRequest(request: PiRpcRequest): String =
        buildJsonObject {
            put("type", request.command)
            request.id?.let { put("id", it) }
            request.payload.forEach { (key, value) -> put(key, value) }
        }.toString()

    fun encodeResponse(response: PiRpcResponse): String =
        buildJsonObject {
            put("type", "response")
            response.id?.let { put("id", it) }
            put("command", response.command)
            put("success", response.success)
            when {
                response.dataPresent -> put("data", response.data ?: JsonNull)
                response.payload.isNotEmpty() -> put("data", response.payload)
            }
            response.error?.let { put("error", it) }
        }.toString()

    fun encodeEvent(event: PiRpcEvent): String =
        buildJsonObject {
            put("type", event.event)
            event.payload.forEach { (key, value) -> put(key, value) }
        }.toString()

    fun responseEvent(response: PiRpcResponse): PiRpcEvent =
        PiRpcEvent(
            event = "response",
            payload =
                buildJsonObject {
                    response.id?.let { put("id", it) }
                    put("command", response.command)
                    put("success", response.success)
                    when {
                        response.dataPresent -> put("data", response.data ?: JsonNull)
                        response.payload.isNotEmpty() -> put("data", response.payload)
                    }
                    response.error?.let { put("error", it) }
                },
        )

    fun event(event: AgentSessionEvent): PiRpcEvent = PiRpcEvent(eventName(event), eventPayload(event))

    fun state(state: AgentSessionState): JsonObject =
        buildJsonObject {
            put("sessionId", state.sessionId)
            state.sessionFile?.let { put("sessionFile", it) }
            state.sessionName?.let { put("sessionName", it) }
            put("isStreaming", state.isStreaming)
            put("isIdle", state.isIdle)
            put("pendingMessageCount", state.pendingMessageCount)
            put("steeringMessages", strings(state.steeringMessages))
            put("followUpMessages", strings(state.followUpMessages))
            put("retryAttempt", state.retryAttempt)
            put("isRetrying", state.isRetrying)
            put("isCompacting", state.isCompacting)
            put("modelId", state.agent.model.id)
            put("provider", state.agent.model.provider)
            put(
                "thinkingLevel",
                state.agent.thinkingLevel.name
                    .lowercase(),
            )
            state.agent.errorMessage?.let { put("error", it) }
        }

    fun interactionRequest(request: InteractionRequest): JsonObject =
        buildJsonObject {
            put("id", request.id)
            put("kind", request.kind.name.lowercase())
            put("title", request.title)
            put("message", request.message)
            when (request) {
                is InteractionRequest.Confirm -> {
                    put("defaultValue", request.defaultValue)
                    put("confirmLabel", request.confirmLabel)
                    put("cancelLabel", request.cancelLabel)
                }

                is InteractionRequest.Select -> {
                    put("options", strings(request.options))
                    put("selectedIndex", request.selectedIndex)
                    put("allowCancel", request.allowCancel)
                }

                is InteractionRequest.Input -> {
                    put("initialValue", request.initialValue)
                    request.placeholder?.let { put("placeholder", it) }
                    put("secret", request.secret)
                    put("multiline", request.multiline)
                }

                is InteractionRequest.Editor -> {
                    put("initialValue", request.initialValue)
                    request.language?.let { put("language", it) }
                }

                is InteractionRequest.Notify -> {
                    put("severity", request.severity.name.lowercase())
                    request.durationMs?.let { put("durationMs", it) }
                }
            }
        }

    fun extensionUiRequest(request: InteractionRequest): JsonObject =
        buildJsonObject {
            put("id", request.id)
            when (request) {
                is InteractionRequest.Confirm -> {
                    put("method", "confirm")
                    put("title", request.title)
                    put("message", request.message)
                }

                is InteractionRequest.Select -> {
                    put("method", "select")
                    put("title", request.title)
                    put("options", strings(request.options))
                }

                is InteractionRequest.Input -> {
                    put("method", "input")
                    put("title", request.title)
                    request.placeholder?.let { put("placeholder", it) }
                }

                is InteractionRequest.Editor -> {
                    put("method", "editor")
                    put("title", request.title)
                    request.initialValue.takeIf(String::isNotEmpty)?.let { put("prefill", it) }
                }

                is InteractionRequest.Notify -> {
                    put("method", "notify")
                    put("message", request.message)
                    put("notifyType", request.severity.name.lowercase())
                }
            }
        }

    fun interactionResponse(response: InteractionResponse): JsonObject =
        buildJsonObject {
            put("requestId", response.requestId)
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

    fun state(session: AgentSession): JsonObject =
        buildJsonObject {
            put(
                "model",
                PiRpcCodec.json
                    .encodeToJsonElement(Model.serializer(), session.model),
            )
            put("thinkingLevel", session.thinkingLevel.name.lowercase())
            put("isStreaming", session.isStreaming)
            put("isCompacting", session.isCompacting)
            put("steeringMode", session.steeringMode.name.lowercase())
            put("followUpMode", session.followUpMode.name.lowercase())
            session.sessionFile?.let { put("sessionFile", it) }
            put("sessionId", session.sessionId)
            session.sessionName?.let { put("sessionName", it) }
            put("autoCompactionEnabled", session.autoCompactionEnabled)
            put("messageCount", session.messages.size)
            put("pendingMessageCount", session.pendingMessageCount)
        }

    private fun eventName(event: AgentSessionEvent): String =
        when (event) {
            AgentSessionEvent.AgentStart -> "agent_start"
            is AgentSessionEvent.AgentEnd -> "agent_end"
            AgentSessionEvent.AgentSettled -> "agent_settled"
            AgentSessionEvent.TurnStart -> "turn_start"
            is AgentSessionEvent.TurnEnd -> "turn_end"
            is AgentSessionEvent.MessageStart -> "message_start"
            is AgentSessionEvent.MessageUpdate -> "message_update"
            is AgentSessionEvent.MessageEnd -> "message_end"
            is AgentSessionEvent.ToolExecutionStart -> "tool_execution_start"
            is AgentSessionEvent.ToolExecutionUpdate -> "tool_execution_update"
            is AgentSessionEvent.ToolExecutionEnd -> "tool_execution_end"
            is AgentSessionEvent.QueueUpdate -> "queue_update"
            is AgentSessionEvent.EntryAppended -> "entry_appended"
            is AgentSessionEvent.SessionInfoChanged -> "session_info_changed"
            is AgentSessionEvent.ThinkingLevelChanged -> "thinking_level_changed"
            is AgentSessionEvent.AutoRetryStart -> "auto_retry_start"
            is AgentSessionEvent.AutoRetryEnd -> "auto_retry_end"
            is AgentSessionEvent.CompactionStart -> "compaction_start"
            is AgentSessionEvent.CompactionEnd -> "compaction_end"
        }

    @Suppress("CyclomaticComplexMethod")
    private fun eventPayload(event: AgentSessionEvent): JsonObject =
        buildJsonObject {
            when (event) {
                AgentSessionEvent.AgentStart,
                AgentSessionEvent.AgentSettled,
                AgentSessionEvent.TurnStart,
                is AgentSessionEvent.CompactionStart,
                -> {}

                is AgentSessionEvent.AgentEnd -> {
                    put("messages", messages(event.messages))
                    put("willRetry", event.willRetry)
                }

                is AgentSessionEvent.TurnEnd -> {
                    put("message", encodeAgentMessageForWire(event.message))
                    put("toolResults", messages(event.toolResults))
                }

                is AgentSessionEvent.MessageStart -> {
                    put("message", encodeAgentMessageForWire(event.message))
                }

                is AgentSessionEvent.MessageUpdate -> {
                    put("message", encodeAgentMessageForWire(event.message))
                    put(
                        "assistantMessageEvent",
                        PiRpcCodec.json.encodeToJsonElement(
                            AssistantMessageEvent.serializer(),
                            event.assistantMessageEvent,
                        ),
                    )
                }

                is AgentSessionEvent.MessageEnd -> {
                    put("message", encodeAgentMessageForWire(event.message))
                }

                is AgentSessionEvent.ToolExecutionStart -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                }

                is AgentSessionEvent.ToolExecutionUpdate -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                    put("partialResult", toolResult(event.partialResult))
                }

                is AgentSessionEvent.ToolExecutionEnd -> {
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("result", toolResult(event.result))
                    put("isError", event.isError)
                }

                is AgentSessionEvent.QueueUpdate -> {
                    put("steering", strings(event.steering))
                    put("followUp", strings(event.followUp))
                }

                is AgentSessionEvent.EntryAppended -> {
                    put("entryId", event.entry.id)
                    event.entry.parentId?.let { put("parentId", it) }
                    put("entryType", event.entry::class.simpleName.orEmpty())
                }

                is AgentSessionEvent.SessionInfoChanged -> {
                    event.name?.let { put("name", it) }
                }

                is AgentSessionEvent.ThinkingLevelChanged -> {
                    put("level", event.level.name.lowercase())
                }

                is AgentSessionEvent.AutoRetryStart -> {
                    put("attempt", event.attempt)
                    put("maxAttempts", event.maxAttempts)
                    put("delayMs", event.delayMs)
                    put("error", event.errorMessage)
                }

                is AgentSessionEvent.AutoRetryEnd -> {
                    put("success", event.success)
                    put("attempt", event.attempt)
                    event.finalError?.let { put("error", it) }
                }

                is AgentSessionEvent.CompactionEnd -> {
                    put("reason", event.reason.name.lowercase())
                    put("aborted", event.aborted)
                    put("willRetry", event.willRetry)
                    event.errorMessage?.let { put("error", it) }
                    event.result?.let {
                        put("tokensBefore", it.tokensBefore)
                        put("estimatedTokensAfter", it.estimatedTokensAfter)
                    }
                }
            }
        }

    private fun messages(messages: List<AgentMessage>): JsonArray = encodeMessages(messages)

    private fun encodeMessages(messages: List<AgentMessage>): JsonArray = JsonArray(messages.map(::wire))

    private fun wire(message: AgentMessage): JsonObject = encodeAgentMessageForWire(message)

    private fun toolResult(result: dev.xuexue.pi.agent.AgentToolResult): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    result.content.forEach { content ->
                        when (content) {
                            is TextContent -> {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", content.text)
                                    },
                                )
                            }

                            else -> {
                                add(buildJsonObject { put("type", "image") })
                            }
                        }
                    }
                },
            )
            put("details", result.details)
            result.terminate?.let { put("terminate", it) }
        }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private val RESERVED_CANONICAL_REQUEST_FIELDS = setOf("type", "id", "command", "method", "payload")
    private val RESERVED_FLAT_REQUEST_FIELDS = setOf("type", "id", "method", "payload")

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
