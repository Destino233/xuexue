package dev.xuexue.platform

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
        onText: (String) -> Unit,
    )
}

class OpenAiCompatibleAgentClient : AgentClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override suspend fun stream(
        messages: List<AgentMessage>,
        config: AgentProviderConfig,
        onText: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(config.endpoint.startsWith("https://") || config.endpoint.startsWith("http://localhost")) {
            "Endpoint must use HTTPS (or localhost for a local provider)."
        }
        require(config.model.isNotBlank()) { "Set a model before sending." }
        require(config.apiKey.isNotBlank()) { "Set an API key before sending." }

        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.trim()))
            put("stream", JsonPrimitive(true))
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(message.role))
                        put("content", JsonPrimitive(message.content))
                    })
                }
            })
        }
        val request = HttpRequest.newBuilder(URI.create(config.endpoint.trim()))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().limit(30).toList().joinToString("\n").take(4000)
            error("Provider returned HTTP ${response.statusCode()}: $errorBody")
        }
        response.body().use { lines ->
            lines.forEach { line ->
                coroutineContext.ensureActive()
                val data = line.removePrefix("data:").trim()
                if (!line.startsWith("data:") || data.isBlank() || data == "[DONE]") return@forEach
                val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@forEach
                val content = root["choices"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("delta")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
                if (!content.isNullOrEmpty()) onText(content)
            }
        }
    }
}
