package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.ThinkingLevel
import dev.xuexue.pi.ai.Tool

internal fun AssistantMessageEvent.partialMessage(): AssistantMessage? =
    when (this) {
        is AssistantMessageEvent.Start -> partial

        is AssistantMessageEvent.TextStart -> partial

        is AssistantMessageEvent.TextDelta -> partial

        is AssistantMessageEvent.TextEnd -> partial

        is AssistantMessageEvent.ThinkingStart -> partial

        is AssistantMessageEvent.ThinkingDelta -> partial

        is AssistantMessageEvent.ThinkingEnd -> partial

        is AssistantMessageEvent.ToolCallStart -> partial

        is AssistantMessageEvent.ToolCallDelta -> partial

        is AssistantMessageEvent.ToolCallEnd -> partial

        is AssistantMessageEvent.Done,
        is AssistantMessageEvent.Error,
        -> null
    }

internal fun AgentTool.asLlmTool(): Tool = Tool(name, description, parameters)

internal fun ModelThinkingLevel.toAiThinkingLevel(): ThinkingLevel? =
    when (this) {
        ModelThinkingLevel.OFF -> null
        ModelThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
        ModelThinkingLevel.LOW -> ThinkingLevel.LOW
        ModelThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
        ModelThinkingLevel.HIGH -> ThinkingLevel.HIGH
        ModelThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
        ModelThinkingLevel.MAX -> ThinkingLevel.MAX
    }
