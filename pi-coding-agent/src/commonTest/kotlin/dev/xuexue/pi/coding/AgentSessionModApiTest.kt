package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.extensions.ModContext
import dev.xuexue.pi.coding.extensions.ModHandle
import dev.xuexue.pi.coding.extensions.ModId
import dev.xuexue.pi.coding.extensions.ModVersion
import dev.xuexue.pi.coding.extensions.PiMod
import dev.xuexue.pi.coding.extensions.PiModLoader
import dev.xuexue.pi.coding.session.CustomMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSessionModApiTest {
    @Test
    fun modContextSessionIsBoundToTheLiveAgentSession() =
        runTest {
            val loader = PiModLoader("/workspace")
            val harness = AgentSessionHarness(modLoader = loader)
            var context: ModContext? = null
            val mod =
                object : PiMod {
                    override val id = ModId("example.session")
                    override val version = ModVersion("1.0.0")

                    override suspend fun enable(modContext: ModContext): ModHandle? {
                        context = modContext
                        return null
                    }
                }

            val result = loader.load(mod)
            val session = assertNotNull(context).session

            assertTrue(result.loaded)
            assertEquals(harness.session.sessionId, session.state?.sessionId)
            session.sendCustomMessage(
                CustomMessageInput(
                    customType = "example.notice",
                    content = UserText("from mod"),
                    display = true,
                ),
            )

            val message = harness.session.messages.last() as CustomMessage
            assertEquals("example.notice", message.customType)
            assertEquals("from mod", (message.content as UserText).value)
        }

    @Test
    fun runtimeModTransformsContextBeforeTheModelCall() =
        runTest {
            var observed = ""
            val loader = PiModLoader("/workspace")
            val harness =
                AgentSessionHarness(
                    modLoader = loader,
                    responses =
                        listOf(
                            { context: Context ->
                                observed = context.messages.toString()
                                testAssistant("ok")
                            },
                        ),
                )
            loader.load(
                object : PiMod {
                    override val id = ModId("example.runtime")
                    override val version = ModVersion("1.0.0")

                    override suspend fun enable(context: ModContext): ModHandle? {
                        context.runtime.registerContextTransformer(
                            id = "append",
                            transformer = { messages, _ ->
                                messages + UserMessage(UserText("runtime"), 9)
                            },
                        )
                        return null
                    }
                },
            )

            harness.session.prompt("hello")

            assertTrue(observed.contains("runtime"))
        }
}
