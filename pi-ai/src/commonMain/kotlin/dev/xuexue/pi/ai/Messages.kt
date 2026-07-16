@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.xuexue.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Usage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val cacheWrite1h: Long? = null,
    val reasoning: Long? = null,
    val totalTokens: Long,
    val cost: UsageCost,
)

@Serializable
data class UsageCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val total: Double,
)

@Serializable
enum class StopReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("toolUse")
    TOOL_USE,

    @SerialName("error")
    ERROR,

    @SerialName("aborted")
    ABORTED,
}

interface AgentMessage {
    val timestamp: Long
}

@Serializable
@JsonClassDiscriminator("role")
sealed interface Message : AgentMessage

@Serializable
@SerialName("user")
data class UserMessage(
    val content: UserContent,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    val content: List<AssistantContent>,
    val api: Api,
    val provider: ProviderId,
    val model: String,
    val responseModel: String? = null,
    val responseId: String? = null,
    val diagnostics: List<AssistantMessageDiagnostic>? = null,
    val usage: Usage,
    val stopReason: StopReason,
    val errorMessage: String? = null,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("toolResult")
data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<ToolResultContent>,
    val details: JsonElement? = null,
    val addedToolNames: List<String>? = null,
    val isError: Boolean,
    override val timestamp: Long,
) : Message

@Serializable(with = UserContentSerializer::class)
sealed interface UserContent

data class UserText(
    val value: String,
) : UserContent

data class UserBlocks(
    val value: List<UserContentBlock>,
) : UserContent

data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
)

data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ImagesContext(
    val input: List<ImageInputContent>,
)

data class AssistantImages(
    val api: ImagesApi,
    val provider: ImagesProviderId,
    val model: String,
    val output: List<ImageOutputContent>,
    val responseId: String? = null,
    val usage: Usage? = null,
    val stopReason: ImagesStopReason,
    val errorMessage: String? = null,
    val timestamp: Long,
)

@Serializable
enum class ImagesStopReason {
    @SerialName("stop")
    STOP,

    @SerialName("error")
    ERROR,

    @SerialName("aborted")
    ABORTED,
}
