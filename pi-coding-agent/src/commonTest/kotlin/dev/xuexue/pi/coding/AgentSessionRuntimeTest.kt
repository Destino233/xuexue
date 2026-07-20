package dev.xuexue.pi.coding

import dev.xuexue.pi.coding.extensions.KotlinPiExtension
import dev.xuexue.pi.coding.extensions.PiExtensionEvent
import dev.xuexue.pi.coding.extensions.PiExtensionEventHandler
import dev.xuexue.pi.coding.extensions.PiExtensionEventResult
import dev.xuexue.pi.coding.extensions.PiExtensionEventType
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentSessionRuntimeTest {
    @Test
    fun runtimeReplacesSessionsThroughOneFactoryAndKeepsLifecycleBoundaries() =
        runTest {
            val first = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val created = mutableListOf<String>()
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(
                        session = first.session,
                        services = AgentSessionRuntimeServices("/workspace", "/agent"),
                    ),
                    AgentSessionRuntimeFactory { options ->
                        val harness = AgentSessionHarness(sessionManager = options.sessionManager)
                        created += options.reason.name
                        AgentSessionRuntimeResult(
                            session = harness.session,
                            services = AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )

            runtime.start()
            val originalId = runtime.session.sessionId
            runtime.newSession()

            assertNotEquals(originalId, runtime.session.sessionId)
            assertEquals(listOf("NEW"), created)
            runtime.dispose()
        }

    @Test
    fun forkBeforeUserMessageCreatesAbranchedRuntimeWithSelectedText() =
        runTest {
            val first = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(
                        first.session,
                        AgentSessionRuntimeServices("/workspace", "/agent"),
                    ),
                    AgentSessionRuntimeFactory { options ->
                        val harness = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            harness.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start()
            runtime.session.prompt("fork me")
            val userEntry = runtime.session.getUserMessagesForForking().single()
            val originalId = runtime.session.sessionId

            val result = runtime.fork(userEntry.entryId)

            assertEquals("fork me", result.selectedText)
            assertNotEquals(originalId, runtime.session.sessionId)
            runtime.dispose()
        }

    @Test
    fun beforeSwitchAndForkHooksCanCancelReplacement() =
        runTest {
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("cancel-replacement") {
                    on(
                        PiExtensionEventType.SESSION_BEFORE_SWITCH,
                        PiExtensionEventHandler { _, _ -> PiExtensionEventResult.SessionBeforeSwitch(cancel = true) },
                    )
                    on(
                        PiExtensionEventType.SESSION_BEFORE_FORK,
                        PiExtensionEventHandler { _, _ -> PiExtensionEventResult.SessionBeforeFork(cancel = true) },
                    )
                    null
                },
            )
            val first =
                AgentSessionHarness(
                    sessionManager = SessionManager.inMemory("/workspace"),
                    extensionRunner = runner,
                )
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(first.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val harness = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            harness.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start()
            val originalId = runtime.session.sessionId
            assertTrue(runtime.newSession())
            assertEquals(originalId, runtime.session.sessionId)
            runtime.session.prompt("fork me")
            val entry = runtime.session.getUserMessagesForForking().single()
            assertTrue(runtime.fork(entry.entryId).cancelled)
            assertEquals(originalId, runtime.session.sessionId)
            runtime.dispose()
        }
}
