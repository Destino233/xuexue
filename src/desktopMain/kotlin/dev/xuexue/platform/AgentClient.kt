package dev.xuexue.platform

import dev.xuexue.core.AgentStreamEvent
import dev.xuexue.core.PiAgentMessage
import dev.xuexue.core.PiModelResponse
import dev.xuexue.core.PiToolCall
import dev.xuexue.core.PiToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.coroutineContext

@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
    val error: Boolean = false,
    val decision: String? = null,
    val streamId: String? = null,
)

data class AgentProviderConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
)

interface AgentClient {
    suspend fun stream(
        messages: List<AgentMessage>,
        config: AgentProviderConfig,
        onEvent: (AgentStreamEvent) -> Unit,
    )

    suspend fun complete(
        messages: List<PiAgentMessage>,
        tools: List<PiToolDefinition>,
        config: AgentProviderConfig,
        onEvent: (AgentStreamEvent) -> Unit,
    ): PiModelResponse {
        val text = StringBuilder()
        stream(
            messages.map { message -> AgentMessage(message.role, message.content, error = message.error) },
            config,
        ) { event ->
            if (event is AgentStreamEvent.TextDelta) text.append(event.text)
            onEvent(event)
        }
        return PiModelResponse(content = text.toString())
    }
}

class OpenAiCompatibleAgentClient : AgentClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build()

    override suspend fun stream(
        messages: List<AgentMessage>,
        config: AgentProviderConfig,
        onEvent: (AgentStreamEvent) -> Unit,
    ) {
        complete(
            messages.map { message -> PiAgentMessage(message.role, message.content, error = message.error) },
            emptyList(),
            config,
            onEvent,
        )
    }

    override suspend fun complete(
        messages: List<PiAgentMessage>,
        tools: List<PiToolDefinition>,
        config: AgentProviderConfig,
        onEvent: (AgentStreamEvent) -> Unit,
    ): PiModelResponse =
        withContext(Dispatchers.IO) {
            validate(config)
            val request = request(config, buildRequestBody(config.model, messages, tools))
            consume(client.send(request, HttpResponse.BodyHandlers.ofLines()), onEvent)
        }

    private fun validate(config: AgentProviderConfig) {
        require(config.endpoint.startsWith("https://") || config.endpoint.startsWith("http://localhost")) {
            "Endpoint must use HTTPS (or localhost for a local provider)."
        }
        require(config.model.isNotBlank()) { "Set a model before sending." }
        require(config.apiKey.isNotBlank()) { "Set an API key before sending." }
    }

    private fun request(
        config: AgentProviderConfig,
        body: JsonObject,
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI.create(config.endpoint.trim()))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

    private fun buildRequestBody(
        model: String,
        messages: List<PiAgentMessage>,
        tools: List<PiToolDefinition>,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model.trim()))
            put("stream", JsonPrimitive(true))
            put("messages", buildJsonArray { messages.forEach { message -> add(providerMessage(message)) } })
            if (tools.isNotEmpty()) put("tools", buildJsonArray { tools.forEach { tool -> add(providerTool(tool)) } })
        }

    private fun providerMessage(message: PiAgentMessage): JsonObject =
        buildJsonObject {
            put("role", JsonPrimitive(message.role))
            put("content", JsonPrimitive(message.content))
            if (message.role == "tool") put("tool_call_id", JsonPrimitive(requireNotNull(message.toolCallId)))
            if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray { message.toolCalls.forEach { call -> add(providerToolCall(call)) } })
            }
        }

    private fun providerTool(tool: PiToolDefinition): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("function"))
            put(
                "function",
                buildJsonObject {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put("parameters", tool.parameters)
                },
            )
        }

    private fun providerToolCall(call: PiToolCall): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(call.id))
            put("type", JsonPrimitive("function"))
            put(
                "function",
                buildJsonObject {
                    put("name", JsonPrimitive(call.name))
                    put("arguments", JsonPrimitive(call.arguments.toString()))
                },
            )
        }

    private suspend fun consume(
        response: HttpResponse<java.util.stream.Stream<String>>,
        onEvent: (AgentStreamEvent) -> Unit,
    ): PiModelResponse {
        if (response.statusCode() !in 200..299) {
            error("Provider returned HTTP ${response.statusCode()}: ${errorBody(response)}")
        }
        val state = ResponseState()
        response.body().use { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) consumeLine(iterator.next(), state, onEvent)
        }
        return state.response()
    }

    private fun errorBody(response: HttpResponse<java.util.stream.Stream<String>>): String =
        response
            .body()
            .limit(30)
            .toList()
            .joinToString("\n")
            .take(4_000)

    private suspend fun consumeLine(
        line: String,
        state: ResponseState,
        onEvent: (AgentStreamEvent) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val data = line.removePrefix("data:").trim()
        if (!line.startsWith("data:") || data.isBlank() || data == "[DONE]") return
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val delta =
            root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
        delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
            onEvent(AgentStreamEvent.TextDelta(it))
        }
        val reasoning =
            delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
        reasoning?.takeIf(String::isNotEmpty)?.let { onEvent(AgentStreamEvent.ReasoningDelta(it)) }
        delta?.get("tool_calls")?.jsonArray?.forEach(state::appendToolCall)
        root["usage"]?.jsonObject?.let(state::setUsage)
    }

    private class StreamingToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private inner class ResponseState {
        private val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        private var inputTokens = 0
        private var outputTokens = 0

        fun appendToolCall(element: kotlinx.serialization.json.JsonElement) {
            val tool = element.jsonObject
            val index = tool["index"]?.jsonPrimitive?.intOrNull ?: return
            val current = toolCalls.getOrPut(index) { StreamingToolCall() }
            tool["id"]?.jsonPrimitive?.contentOrNull?.let { current.id = it }
            tool["function"]?.jsonObject?.let { function ->
                function["name"]?.jsonPrimitive?.contentOrNull?.let { current.name += it }
                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { current.arguments.append(it) }
            }
        }

        fun setUsage(usage: JsonObject) {
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: inputTokens
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: outputTokens
        }

        fun response(): PiModelResponse =
            PiModelResponse(
                toolCalls =
                    toolCalls.values.mapIndexedNotNull { index, call ->
                        val name = call.name.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                        val arguments =
                            runCatching { json.parseToJsonElement(call.arguments.toString()).jsonObject }
                                .getOrDefault(buildJsonObject {})
                        PiToolCall(call.id.ifBlank { "call-$index" }, name, arguments)
                    },
                inputTokens = inputTokens.takeIf { it > 0 },
                outputTokens = outputTokens.takeIf { it > 0 },
            )
    }
}
