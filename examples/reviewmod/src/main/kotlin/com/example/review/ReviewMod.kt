package com.example.review

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.CustomMessageInput
import dev.xuexue.pi.coding.extensions.ModContext
import dev.xuexue.pi.coding.extensions.ModHandle
import dev.xuexue.pi.coding.extensions.ModId
import dev.xuexue.pi.coding.extensions.ModVersion
import dev.xuexue.pi.coding.extensions.PiExtensionEvent
import dev.xuexue.pi.coding.extensions.PiExtensionEventHandler
import dev.xuexue.pi.coding.extensions.PiExtensionEventResult
import dev.xuexue.pi.coding.extensions.PiExtensionEventType
import dev.xuexue.pi.coding.extensions.PiMod
import dev.xuexue.pi.coding.extensions.UiPanelContribution
import dev.xuexue.pi.coding.extensions.UiSlot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A complete Feature Mod: two tools, a command, an event hook, Session API and a panel. */
class ReviewMod : PiMod {
    override val id = ModId("com.example.review")
    override val version = ModVersion("0.1.0")

    override suspend fun enable(context: ModContext): ModHandle? {
        context.tools.register("review", reviewTool(context))
        context.tools.register("explain", explainTool)
        context.commands.register(
            "review",
            dev.xuexue.pi.coding.extensions.ModCommand("Review the active document") { _, modContext ->
                modContext.session.sendCustomMessage(
                    CustomMessageInput("review.command", UserText("Review requested by command"), display = true),
                )
            },
        )
        context.events.on(
            PiExtensionEventType.TOOL_RESULT,
            PiExtensionEventHandler { event, _ ->
                val result = event as PiExtensionEvent.ToolResult
                PiExtensionEventResult.ToolResult(
                    details = buildJsonObject {
                        put("reviewMod", true)
                        put("tool", result.toolName)
                    },
                )
            },
        )
        context.runtime.registerContextTransformer("tag-context") { messages, _ -> messages }
        context.ui.registerPanel(
            UiPanelContribution(
                id = "panel",
                title = "Review",
                slot = UiSlot.RIGHT_DOCK,
                state = buildJsonObject { put("status", "ready") },
            ),
        )
        return ModHandle { }
    }

    private fun reviewTool(context: ModContext) =
        AgentTool(
            name = "review",
            label = "Review",
            description = "Review text and report actionable issues",
            parameters = buildJsonObject { put("type", "object") },
            execute = { _, params, _, _ ->
                if (context.permissions.request("workspace.read") != dev.xuexue.pi.coding.extensions.PermissionDecision.GRANTED) {
                    AgentToolResult(listOf(TextContent("Permission denied")), buildJsonObject {})
                } else {
                    val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    context.session.sendCustomMessage(
                        CustomMessageInput("review.result", UserText("Reviewed ${text.length} characters"), display = false),
                    )
                    AgentToolResult(
                        content = listOf(TextContent(if (text.isBlank()) "No text supplied" else "Review complete")),
                        details = buildJsonObject { put("characters", text.length) },
                    )
                }
            },
        )

    private val explainTool =
        AgentTool(
            name = "explain",
            label = "Explain review",
            description = "Explain a review finding",
            parameters = buildJsonObject { put("type", "object") },
            execute = { _, _, _, _ ->
                AgentToolResult(listOf(TextContent("Review findings are explained in context.")), buildJsonObject {})
            },
        )
}
