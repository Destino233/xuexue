package dev.xuexue.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AssistantMessageEvent {
    @Serializable
    @SerialName("start")
    data class Start(
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("text_start")
    data class TextStart(
        val contentIndex: Int,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        val contentIndex: Int,
        val delta: String,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("text_end")
    data class TextEnd(
        val contentIndex: Int,
        val content: String,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_start")
    data class ThinkingStart(
        val contentIndex: Int,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(
        val contentIndex: Int,
        val delta: String,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_end")
    data class ThinkingEnd(
        val contentIndex: Int,
        val content: String,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_start")
    data class ToolCallStart(
        val contentIndex: Int,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_delta")
    data class ToolCallDelta(
        val contentIndex: Int,
        val delta: String,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_end")
    data class ToolCallEnd(
        val contentIndex: Int,
        val toolCall: ToolCall,
        val partial: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("done")
    data class Done(
        val reason: SuccessfulStopReason,
        val message: AssistantMessage,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: FailureStopReason,
        val error: AssistantMessage,
    ) : AssistantMessageEvent
}

@Serializable
enum class SuccessfulStopReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("toolUse")
    TOOL_USE,
}

@Serializable
enum class FailureStopReason {
    @SerialName("aborted")
    ABORTED,

    @SerialName("error")
    ERROR,
}
