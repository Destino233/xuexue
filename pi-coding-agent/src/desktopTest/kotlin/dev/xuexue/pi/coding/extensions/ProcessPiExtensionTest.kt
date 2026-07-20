package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.ai.AbortController
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.ImageContent
import dev.xuexue.pi.ai.ImagesContext
import dev.xuexue.pi.ai.ImagesModel
import dev.xuexue.pi.ai.ImagesOptions
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelCost
import dev.xuexue.pi.ai.ModelInput
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.StreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.InteractionRequest
import dev.xuexue.pi.coding.InteractionRequestBroker
import dev.xuexue.pi.coding.InteractionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessPiExtensionTest {
    @Test
    fun processPluginRegistersImageProviderAndReturnsGeneratedImagesOverJsonl() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-image-provider").toFile()
            val script = directory.resolve("image-provider.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_image_provider","id":"process-images","name":"Process images","supportedApis":["process-images-api"]}'
                      echo '{"type":"ready"}'
                      ;;
                    *image_generate*)
                      echo '{"type":"image_result","requestId":"stale-request","result":{"api":"process-images-api","provider":"process-images","model":"process-image-model","output":[{"type":"image","data":"STALE","mimeType":"image/png"}],"stopReason":"stop","timestamp":1}}'
                      case "${'$'}line" in
                        *draw*)
                          echo '{"type":"image_result","result":{"api":"process-images-api","provider":"process-images","model":"process-image-model","output":[{"type":"image","data":"AAAA","mimeType":"image/png"}],"stopReason":"stop","timestamp":1}}'
                          ;;
                        *)
                          echo '{"type":"image_error","message":"image context was not forwarded"}'
                          ;;
                      esac
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))

                val provider = runner.getAllRegisteredImageProviders().single()
                assertEquals("process-images", provider.id)
                val result =
                    provider.generateImages(
                        imageModel(),
                        ImagesContext(listOf(TextContent("draw"))),
                        ImagesOptions(apiKey = "image-key"),
                    )

                assertEquals("process-images-api", result.api)
                assertEquals(
                    "AAAA",
                    result.output
                        .single()
                        .let { it as dev.xuexue.pi.ai.ImageContent }
                        .data,
                )
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processPluginRegistersProviderAndStreamsAssistantEventsOverJsonl() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-provider").toFile()
            val script = directory.resolve("provider.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_provider","id":"process-provider","name":"Process provider","supportedApis":["process-api"]}'
                      echo '{"type":"ready"}'
                      ;;
                    *provider_stream*)
                      echo '{"type":"provider_event","requestId":"stale-request","event":{"type":"done","reason":"stop","message":{"content":[{"type":"text","text":"stale response"}],"api":"process-api","provider":"process-provider","model":"process-model","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"stop","timestamp":1}}}'
                      case "${'$'}line" in
                        *hello*)
                          echo '{"type":"provider_event","event":{"type":"done","reason":"stop","message":{"content":[{"type":"text","text":"from process"}],"api":"process-api","provider":"process-provider","model":"process-model","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"stop","timestamp":1}}}'
                          ;;
                        *)
                          echo '{"type":"provider_error","message":"context was not forwarded"}'
                          ;;
                      esac
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))

                val provider = runner.getAllRegisteredProviders().single()
                assertEquals("process-provider", provider.id)
                val events =
                    provider
                        .stream(
                            model(),
                            Context(messages = listOf(UserMessage(UserText("hello"), timestamp = 1))),
                            SimpleStreamOptions(stream = StreamOptions(apiKey = "provider-key")),
                        ).asFlow()
                        .toList()

                val event = events.single()
                assertTrue(event is AssistantMessageEvent.Done, "Unexpected process provider event: $event")
                val done: AssistantMessageEvent.Done = event
                assertEquals(
                    "from process",
                    done.message.content
                        .filterIsInstance<TextContent>()
                        .single()
                        .text,
                )
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processPluginRegistersAndExecutesToolOverJsonl() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_tool","name":"process-tool","description":"Process tool","parameters":{}}'
                      echo '{"type":"ready"}'
                      ;;
                    *tool_call*)
                      echo '{"type":"tool_result","requestId":"stale-request","content":[{"type":"text","text":"stale response"}]}'
                      echo '{"type":"tool_result","content":[{"type":"text","text":"from process"}]}'
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))

                val tool = runner.getAllRegisteredTools().single()
                val result = tool.execute("call-1", buildJsonObject {}, null, null)

                assertEquals("process-tool", tool.name)
                assertEquals("from process", (result.content.single() as dev.xuexue.pi.ai.TextContent).text)
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processToolCancellationIsForwardedOverJsonlAndFailsTheInvocation() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin-cancel").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_tool","name":"process-tool","description":"Process tool","parameters":{}}'
                      echo '{"type":"ready"}'
                      ;;
                    *tool_call*)
                      touch tool-started
                      request_id=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*"requestId":"\([^"]*\)".*/\1/p')
                      IFS= read -r cancel_line
                      printf '%s\n' "${'$'}cancel_line" > cancel-received.jsonl
                      printf '{"type":"tool_result","requestId":"%s","content":[{"type":"text","text":"cancelled"}]}\n' "${'$'}request_id"
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))
                val controller = AbortController()
                val invocation =
                    async {
                        runner
                            .getAllRegisteredTools()
                            .single()
                            .execute("call-cancel", buildJsonObject {}, controller.signal, null)
                    }

                awaitFile(directory.resolve("tool-started"))
                controller.abort()

                val failure = runCatching { invocation.await() }.exceptionOrNull()
                assertIs<CancellationException>(failure)
                assertTrue(directory.resolve("cancel-received.jsonl").readText().contains("\"type\":\"cancel\""))
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processToolCancellationTerminatesAnUnresponsivePluginAfterGraceTimeout() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin-unresponsive").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_tool","name":"process-tool","description":"Process tool","parameters":{}}'
                      echo '{"type":"ready"}'
                      ;;
                    *tool_call*)
                      touch tool-started
                      while :; do :; done
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))
                val controller = AbortController()
                val invocation =
                    async {
                        runner
                            .getAllRegisteredTools()
                            .single()
                            .execute("call-cancel", buildJsonObject {}, controller.signal, null)
                    }

                awaitFile(directory.resolve("tool-started"))
                val startedAt = System.nanoTime()
                controller.abort()

                val failure = runCatching { invocation.await() }.exceptionOrNull()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertIs<CancellationException>(failure)
                assertTrue(elapsedMs < 5_000, "Unresponsive plugin cancellation took $elapsedMs ms")
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processProviderTimeoutEndsTheStreamAndAllowsShutdown() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-provider-timeout").toFile()
            val script = directory.resolve("provider.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"register_provider","id":"process-provider","name":"Process provider","supportedApis":["process-api"]}'
                      echo '{"type":"ready"}'
                      ;;
                    *provider_stream*)
                      while :; do :; done
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))
                val events =
                    runner
                        .getAllRegisteredProviders()
                        .single()
                        .stream(
                            model(),
                            Context(messages = listOf(UserMessage(UserText("timeout"), timestamp = 1))),
                            SimpleStreamOptions(stream = StreamOptions(timeoutMs = 50)),
                        ).asFlow()
                        .toList()

                val error = assertIs<AssistantMessageEvent.Error>(events.single())
                assertEquals(FailureStopReason.ERROR, error.reason)
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processPluginCanResolveProjectTrustBeforeProjectPluginsLoad() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin-trust").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"subscribe","event":"PROJECT_TRUST"}'
                      echo '{"type":"ready"}'
                      ;;
                    *'"event":"project_trust"'*)
                      echo '{"type":"event_result","result":"project_trust","trusted":"yes","remember":true}'
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))

                val result = runner.emitProjectTrust(PiExtensionContext(directory.path, PiExtensionMode.RPC))

                assertEquals(ProjectTrustDecision.YES, result?.trusted)
                assertEquals(true, result?.remember)
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processPluginContextAndToolResultUseStructuredMessageWireFormat() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin-structured-events").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"subscribe","event":"CONTEXT"}'
                      echo '{"type":"subscribe","event":"TOOL_RESULT"}'
                      echo '{"type":"subscribe","event":"RESOURCES_DISCOVER"}'
                      echo '{"type":"ready"}'
                      ;;
                    *'"event":"context"'*)
                      case "${'$'}line" in
                        *'"role":"toolResult"'*'"data":"AAAA"'*)
                          echo '{"type":"event_result","result":"context","messages":[{"role":"user","content":"from process","timestamp":7}]}'
                          ;;
                        *)
                          echo '{"type":"event_result","result":"context","messages":[]}'
                          ;;
                      esac
                      ;;
                    *'"event":"tool_result"'*)
                      echo '{"type":"event_result","result":"tool_result","content":[{"type":"image","data":"BBBB","mimeType":"image/png"}],"details":{"source":"process"},"isError":true}'
                      ;;
                    *'"event":"resources_discover"'*)
                      echo '{"type":"event_result","result":"resources","skillPaths":["process-skills"],"promptPaths":["process-prompts"]}'
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val runner = PiExtensionRunner(directory.path)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))
                val contextMessages =
                    runner.emitContext(
                        listOf(
                            ToolResultMessage(
                                toolCallId = "call-1",
                                toolName = "image-tool",
                                content = listOf(ImageContent("AAAA", "image/png")),
                                isError = false,
                                timestamp = 1,
                            ),
                        ),
                    )
                val contextMessage = assertIs<UserMessage>(contextMessages.single())
                assertEquals("from process", assertIs<UserText>(contextMessage.content).value)

                val toolResult =
                    runner.emitToolResult(
                        PiExtensionEvent.ToolResult(
                            toolCallId = "call-1",
                            toolName = "image-tool",
                            content = listOf(TextContent("before")),
                            details = null,
                            isError = false,
                        ),
                    )
                assertEquals("BBBB", assertIs<ImageContent>(toolResult.content.single()).data)
                assertEquals(
                    "process",
                    toolResult.details
                        ?.jsonObject
                        ?.get("source")
                        ?.jsonPrimitive
                        ?.contentOrNull,
                )
                assertTrue(toolResult.isError)
                val resources = runner.emitResourcesDiscover(ResourceDiscoverReason.STARTUP)
                assertEquals("process-skills", resources.skillPaths.single().path)
                assertEquals("process-prompts", resources.promptPaths.single().path)
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun processPluginCanSuspendForAHostInteractionDuringAnEvent() =
        runTest {
            val directory = Files.createTempDirectory("xuexue-process-plugin-interaction").toFile()
            val script = directory.resolve("plugin.sh")
            script.writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *initialize*)
                      echo '{"type":"subscribe","event":"AGENT_START"}'
                      echo '{"type":"ready"}'
                      ;;
                    *'"type":"event"'*)
                      echo '{"type":"interaction_request","kind":"confirm","id":"process-confirm","title":"Confirm","message":"Continue?"}'
                      IFS= read -r response
                      echo '{"type":"event_result"}'
                      ;;
                    *shutdown*)
                      exit 0
                      ;;
                  esac
                done
                """.trimIndent(),
            )
            check(script.setExecutable(true))
            try {
                val broker = InteractionRequestBroker()
                val runner = PiExtensionRunner(directory.path, PiExtensionMode.RPC)
                runner.load(ProcessPiExtension("process", listOf(script.path), directory.path))
                val emit =
                    async {
                        runner.emit(
                            PiExtensionEvent.AgentStart,
                            PiExtensionContext(directory.path, PiExtensionMode.RPC, interaction = broker),
                        )
                    }
                val request = broker.requests.first { it.isNotEmpty() }.first()
                assertTrue(request is InteractionRequest.Confirm)
                assertTrue(broker.respond(InteractionResponse.Confirm(request.id, accepted = true)))
                emit.await()
                runner.dispose()
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun model(): Model =
        Model(
            id = "process-model",
            name = "Process model",
            api = "process-api",
            provider = "process-provider",
            baseUrl = "process://provider",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )

    private fun imageModel(): ImagesModel =
        ImagesModel(
            id = "process-image-model",
            name = "Process image model",
            api = "process-images-api",
            provider = "process-images",
            baseUrl = "process://images",
            input = listOf(ModelInput.TEXT),
            output = listOf(ModelInput.IMAGE),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        )
}

@Suppress("InjectDispatcher") // Polls an external process fixture on the real IO dispatcher.
private suspend fun awaitFile(file: File) {
    withContext(Dispatchers.IO) {
        repeat(500) {
            if (file.exists()) return@withContext
            delay(10)
        }
        error("Process fixture did not create ${file.name}")
    }
}
