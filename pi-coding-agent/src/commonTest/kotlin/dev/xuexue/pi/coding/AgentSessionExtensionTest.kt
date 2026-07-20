package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserBlocks
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.extensions.ExtensionMessage
import dev.xuexue.pi.coding.extensions.KotlinPiExtension
import dev.xuexue.pi.coding.extensions.PiExtensionEvent
import dev.xuexue.pi.coding.extensions.PiExtensionEventHandler
import dev.xuexue.pi.coding.extensions.PiExtensionEventResult
import dev.xuexue.pi.coding.extensions.PiExtensionEventType
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSessionExtensionTest {
    @Test
    fun sessionInstallsExtensionToolsAndChainsContextAndBeforeStartHooks() =
        runTest {
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("session-extension") {
                    registerTool(extensionTool())
                    on(
                        PiExtensionEventType.CONTEXT,
                        PiExtensionEventHandler { event, _ ->
                            val context = event as PiExtensionEvent.Context
                            PiExtensionEventResult.Context(context.messages + UserMessage(UserText("context"), 3))
                        },
                    )
                    on(
                        PiExtensionEventType.BEFORE_AGENT_START,
                        PiExtensionEventHandler { _, _ ->
                            PiExtensionEventResult.BeforeAgentStart(
                                message = ExtensionMessage("extension", UserText("injected")),
                                systemPrompt = "Test|extension",
                            )
                        },
                    )
                    null
                },
            )
            val contexts = mutableListOf<dev.xuexue.pi.ai.Context>()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf({ context ->
                            contexts += context
                            testAssistant("done")
                        }),
                    extensionRunner = runner,
                )

            harness.session.prompt("hello")

            assertTrue(
                harness.session.agent.currentState.tools
                    .any { it.name == "extension-tool" },
            )
            assertEquals("Test", harness.session.systemPrompt)
            assertEquals(
                listOf("hello", "injected", "context"),
                contexts.single().messages.map(::messageText),
            )
        }

    private fun extensionTool() =
        AgentTool(
            name = "extension-tool",
            label = "Extension tool",
            description = "Extension tool",
            parameters = buildJsonObject {},
            execute = { _, _, _, _ -> AgentToolResult(listOf(TextContent("ok")), buildJsonObject {}) },
        )

    private fun messageText(message: dev.xuexue.pi.ai.AgentMessage): String =
        when (message) {
            is UserMessage -> {
                when (val content = message.content) {
                    is UserText -> content.value
                    is UserBlocks -> content.value.filterIsInstance<TextContent>().joinToString("") { it.text }
                }
            }

            else -> {
                error("Unexpected message type")
            }
        }
}
