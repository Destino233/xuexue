@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding.rpc

import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.AuthCredential
import dev.xuexue.pi.ai.InMemoryAuthStore
import dev.xuexue.pi.ai.ModelRegistry
import dev.xuexue.pi.coding.AgentSession
import dev.xuexue.pi.coding.AgentSessionBashExecutor
import dev.xuexue.pi.coding.AgentSessionConfig
import dev.xuexue.pi.coding.AgentSessionEvent
import dev.xuexue.pi.coding.AgentSessionHarness
import dev.xuexue.pi.coding.AgentSessionHtmlExporter
import dev.xuexue.pi.coding.AgentSessionRuntime
import dev.xuexue.pi.coding.AgentSessionRuntimeFactory
import dev.xuexue.pi.coding.AgentSessionRuntimeResult
import dev.xuexue.pi.coding.AgentSessionRuntimeServices
import dev.xuexue.pi.coding.BashResult
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionRequestBroker
import dev.xuexue.pi.coding.InteractionResponse
import dev.xuexue.pi.coding.extensions.KotlinPiExtension
import dev.xuexue.pi.coding.extensions.PiExtensionCommand
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.extensions.SessionStartReason
import dev.xuexue.pi.coding.renderSessionHtml
import dev.xuexue.pi.coding.resources.ResourceOrigin
import dev.xuexue.pi.coding.resources.ResourceScope
import dev.xuexue.pi.coding.resources.syntheticResourceSourceInfo
import dev.xuexue.pi.coding.session.BashExecutionMessage
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiRpcProtocolTest {
    @Test
    fun codecAcceptsCanonicalAndFlatPiRequests() {
        val canonical = PiRpcCodec.decodeRequest("""{"type":"request","id":"1","command":"state","payload":{}}""")
        assertEquals(PiRpcRequest("1", "state"), canonical)

        val flat = PiRpcCodec.decodeRequest("""{"type":"prompt","id":"2","message":"hello"}""")
        assertEquals("prompt", flat.command)
        assertEquals("hello", flat.payload["message"]?.jsonPrimitive?.content)

        val encoded = PiRpcCodec.encodeResponse(PiRpcResponse("3", "state", success = true))
        assertEquals(
            "response",
            PiRpcCodec.json
                .parseToJsonElement(encoded)
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )

        val flatEncoded = PiRpcCodec.json.parseToJsonElement(PiRpcCodec.encodeRequest(flat)).jsonObject
        assertEquals("prompt", flatEncoded["type"]?.jsonPrimitive?.content)
        assertEquals("hello", flatEncoded["message"]?.jsonPrimitive?.content)
        assertEquals(null, flatEncoded["payload"])

        val event = PiRpcCodec.json.parseToJsonElement(PiRpcCodec.encodeEvent(PiRpcEvent("agent_start"))).jsonObject
        assertEquals("agent_start", event["type"]?.jsonPrimitive?.content)
        assertEquals(null, event["event"])

        val explicitNull =
            PiRpcCodec.json
                .parseToJsonElement(
                    PiRpcCodec.encodeResponse(
                        PiRpcResponse("4", "cycle_model", success = true, dataPresent = true),
                    ),
                ).jsonObject
        assertEquals(JsonNull, explicitNull["data"])
    }

    @Test
    fun codecPreservesAssistantMessageUpdateEvent() {
        val partial =
            dev.xuexue.pi.coding
                .testAssistant("partial")
        val event =
            PiRpcCodec.json
                .parseToJsonElement(
                    PiRpcCodec.encodeEvent(
                        PiRpcCodec.event(
                            AgentSessionEvent.MessageUpdate(
                                message = partial,
                                assistantMessageEvent =
                                    AssistantMessageEvent.TextDelta(
                                        contentIndex = 0,
                                        delta = "part",
                                        partial = partial,
                                    ),
                            ),
                        ),
                    ),
                ).jsonObject

        assertEquals("message_update", event["type"]?.jsonPrimitive?.content)
        assertEquals(
            "text_delta",
            event["assistantMessageEvent"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun handlerDispatchesStateAndPromptThroughTheRuntime() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val handler = AgentSessionRpcHandler(runtime)

            val stateResponse = handler.handleLine("""{"type":"get_state","id":"state-1"}""")
            val stateJson = PiRpcCodec.json.parseToJsonElement(stateResponse).jsonObject
            assertEquals("state-1", stateJson["id"]?.jsonPrimitive?.content)
            assertTrue(stateJson["success"]?.jsonPrimitive?.content == "true")

            val promptResponse = handler.handleLine("""{"type":"prompt","id":"prompt-1","message":"hello"}""")
            val promptJson = PiRpcCodec.json.parseToJsonElement(promptResponse).jsonObject
            assertTrue(promptJson["success"]?.jsonPrimitive?.content == "true")
            assertEquals(2, runtime.session.messages.size)

            handler.close()
            runtime.dispose()
        }

    @Test
    fun cycleModelReturnsExplicitNullWhenThereIsNoAvailableNextModel() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)

            val response =
                PiRpcCodec.json
                    .parseToJsonElement(
                        AgentSessionRpcHandler(runtime).handleLine(
                            """{"type":"cycle_model","id":"cycle-1"}""",
                        ),
                    ).jsonObject

            assertEquals(true, response["success"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals(JsonNull, response["data"])
            runtime.dispose()
        }

    @Test
    fun cycleModelUsesTheRpcRegistryAndReturnsPiResultShape() =
        runTest {
            val first =
                dev.xuexue.pi.coding
                    .testModel()
            val second =
                dev.xuexue.pi.coding
                    .testModel(id = "second")
            val registry =
                ModelRegistry(
                    authStore = InMemoryAuthStore(initial = mapOf("openai" to AuthCredential.ApiKey("key"))),
                    builtInModels = listOf(first, second),
                )
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)

            val response =
                PiRpcCodec.json
                    .parseToJsonElement(
                        AgentSessionRpcHandler(runtime, modelRegistry = registry).handleLine(
                            """{"type":"cycle_model","id":"cycle-2"}""",
                        ),
                    ).jsonObject

            assertEquals(
                "second",
                response["data"]
                    ?.jsonObject
                    ?.get("model")
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "false",
                response["data"]
                    ?.jsonObject
                    ?.get("isScoped")
                    ?.jsonPrimitive
                    ?.content,
            )
            runtime.dispose()
        }

    @Test
    fun serverPromptReturnsResponseAsynchronouslyAfterPreflight() =
        runBlocking {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val handler = AgentSessionRpcHandler(runtime)
            val event =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    handler.events.first {
                        it.event == "response" && it.payload["command"]?.jsonPrimitive?.content == "prompt"
                    }
                }
            assertEquals(
                null,
                handler.handleLineForServer("""{"type":"prompt","id":"prompt-async","message":"hello"}"""),
            )
            val response = withTimeout(2_000) { event.await() }
            assertEquals("prompt-async", response.payload["id"]?.jsonPrimitive?.content)
            assertTrue(response.payload["success"]?.jsonPrimitive?.content == "true")
            handler.close()
            runtime.dispose()
        }

    @Test
    fun handlerExecutesAndPersistsBashResults() =
        runTest {
            val executor =
                object : AgentSessionBashExecutor {
                    override suspend fun execute(
                        command: String,
                        cwd: String,
                        excludeFromContext: Boolean?,
                    ): BashResult = BashResult("done:$command@$cwd", exitCode = 0, cancelled = false, truncated = false)

                    override fun abort() = Unit
                }
            val harness =
                AgentSessionHarness(
                    sessionManager = SessionManager.inMemory("/workspace"),
                    bashExecutor = executor,
                )
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager, bashExecutor = executor)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val handler = AgentSessionRpcHandler(runtime)

            val response =
                PiRpcCodec.json
                    .parseToJsonElement(
                        handler.handleLine(
                            """{"type":"bash","id":"bash-1","command":"printf hi","excludeFromContext":true}""",
                        ),
                    ).jsonObject

            assertTrue(response["success"]?.jsonPrimitive?.content == "true", response.toString())
            assertEquals(
                "done:printf hi@/workspace",
                response["data"]
                    ?.jsonObject
                    ?.get("output")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertTrue(
                runtime.session.messages.any {
                    (it as? BashExecutionMessage)?.excludeFromContext == true
                },
            )

            handler.close()
            runtime.dispose()
        }

    @Test
    fun handlerUsesPiExtensionUiRequestAndResponseShapes() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val broker = InteractionRequestBroker()
            val handler = AgentSessionRpcHandler(runtime, broker)
            val uiEvent = async { handler.events.first { it.event == "extension_ui_request" } }
            val pending =
                async {
                    broker.request(
                        InteractionRequest.Confirm(
                            id = "confirm-1",
                            title = "Confirm",
                            message = "Continue?",
                        ),
                    )
                }
            runCurrent()
            val event = uiEvent.await()
            assertEquals("confirm", event.payload["method"]?.jsonPrimitive?.content)
            assertEquals("confirm-1", event.payload["id"]?.jsonPrimitive?.content)

            handler.handleLine("""{"type":"extension_ui_response","id":"confirm-1","confirmed":true}""")
            assertEquals(InteractionResponse.Confirm("confirm-1", true), pending.await())
            handler.close()
            runtime.dispose()
        }

    @Test
    fun getCommandsUsesPiSourceInfoForExtensionCommands() =
        runTest {
            val extensionRunner = PiExtensionRunner("/workspace")
            extensionRunner.load(
                KotlinPiExtension(
                    id = "review",
                    sourceInfo =
                        syntheticResourceSourceInfo(
                            path = "/workspace/.xuexue/pi/extensions/review.jar",
                            source = "local",
                            scope = ResourceScope.PROJECT,
                            origin = ResourceOrigin.PACKAGE,
                            baseDirectory = "/workspace/.xuexue/pi/extensions",
                        ),
                ) {
                    registerCommand(PiExtensionCommand("review") { _, _ -> })
                    null
                },
            )
            val harness =
                AgentSessionHarness(
                    sessionManager = SessionManager.inMemory("/workspace"),
                    extensionRunner = extensionRunner,
                )
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(harness.session, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)

            val response =
                PiRpcCodec.json
                    .parseToJsonElement(AgentSessionRpcHandler(runtime).handleLine("""{"type":"get_commands"}"""))
                    .jsonObject
            val command =
                response["data"]
                    ?.jsonObject
                    ?.get("commands")
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
            val sourceInfo =
                command
                    ?.get("sourceInfo")
                    ?.jsonObject
            assertEquals("review", command?.get("name")?.jsonPrimitive?.content)
            assertEquals("/workspace/.xuexue/pi/extensions/review.jar", sourceInfo?.get("path")?.jsonPrimitive?.content)
            assertEquals("project", sourceInfo?.get("scope")?.jsonPrimitive?.content)
            assertEquals("package", sourceInfo?.get("origin")?.jsonPrimitive?.content)
            runtime.dispose()
        }

    @Test
    fun exportHtmlResponseUsesConfiguredHostExporter() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            val exported =
                AgentSession(
                    AgentSessionConfig(
                        agent = harness.agent,
                        sessionManager = harness.manager,
                        htmlExporter = AgentSessionHtmlExporter { _, outputPath -> outputPath ?: "generated.html" },
                    ),
                )
            val runtime =
                AgentSessionRuntime(
                    AgentSessionRuntimeResult(exported, AgentSessionRuntimeServices("/workspace", "/agent")),
                    AgentSessionRuntimeFactory { options ->
                        val next = AgentSessionHarness(sessionManager = options.sessionManager)
                        AgentSessionRuntimeResult(
                            next.session,
                            AgentSessionRuntimeServices(options.cwd, options.agentDirectory),
                        )
                    },
                )
            runtime.start(SessionStartReason.STARTUP)
            val response =
                PiRpcCodec.json
                    .parseToJsonElement(
                        AgentSessionRpcHandler(runtime).handleLine(
                            """{"type":"export_html","id":"export-1","outputPath":"/tmp/transcript.html"}""",
                        ),
                    ).jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.content == "true")
            assertEquals(
                "/tmp/transcript.html",
                response["data"]
                    ?.jsonObject
                    ?.get("path")
                    ?.jsonPrimitive
                    ?.content,
            )
            runtime.dispose()
        }

    @Test
    fun htmlRendererEscapesMarkupAndKeepsWhitespace() =
        runTest {
            val harness = AgentSessionHarness(sessionManager = SessionManager.inMemory("/workspace"))
            harness.session.prompt("<script>alert(1)</script>\nsecond line")
            val html = renderSessionHtml(harness.session)
            assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
            assertTrue("second line" in html)
            assertTrue("<script>alert(1)</script>" !in html)
        }
}
