package dev.xuexue.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface ContentBlock

@Serializable
sealed interface UserContentBlock : ContentBlock

@Serializable
sealed interface AssistantContent : ContentBlock

@Serializable
sealed interface ToolResultContent : ContentBlock

@Serializable
sealed interface ImageInputContent : ContentBlock

@Serializable
sealed interface ImageOutputContent : ContentBlock

@Serializable
@SerialName("text")
data class TextContent(
    val text: String,
    val textSignature: String? = null,
) : UserContentBlock,
    AssistantContent,
    ToolResultContent,
    ImageInputContent,
    ImageOutputContent

@Serializable
@SerialName("thinking")
data class ThinkingContent(
    val thinking: String,
    val thinkingSignature: String? = null,
    val redacted: Boolean? = null,
) : AssistantContent

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String,
    val mimeType: String,
) : UserContentBlock,
    ToolResultContent,
    ImageInputContent,
    ImageOutputContent

@Serializable
@SerialName("toolCall")
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val thoughtSignature: String? = null,
) : AssistantContent

@Serializable
data class TextSignatureV1(
    val v: Int = 1,
    val id: String,
    val phase: TextSignaturePhase? = null,
)

@Serializable
enum class TextSignaturePhase {
    @SerialName("commentary")
    COMMENTARY,

    @SerialName("final_answer")
    FINAL_ANSWER,
}
