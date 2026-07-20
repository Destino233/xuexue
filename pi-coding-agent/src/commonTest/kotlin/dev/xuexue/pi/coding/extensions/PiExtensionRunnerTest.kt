package dev.xuexue.pi.coding.extensions

import dev.xuexue.pi.agent.AgentTool
import dev.xuexue.pi.agent.AgentToolResult
import dev.xuexue.pi.ai.AssistantImages
import dev.xuexue.pi.ai.AssistantMessageEventStream
import dev.xuexue.pi.ai.Context
import dev.xuexue.pi.ai.ImagesContext
import dev.xuexue.pi.ai.ImagesModel
import dev.xuexue.pi.ai.ImagesOptions
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.PiImageProvider
import dev.xuexue.pi.ai.PiProvider
import dev.xuexue.pi.ai.SimpleStreamOptions
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PiExtensionRunnerTest {
    @Test
    fun handlersAreSequentialAndBeforeAgentStartResultsAreChained() =
        runTest {
            val runner = PiExtensionRunner("/workspace")
            runner.load(extension("one", "one"))
            runner.load(extension("two", "two"))

            val contextMessages = runner.emitContext(listOf(user("base")))
            val beforeStart = runner.emitBeforeAgentStart("prompt", "system")

            assertEquals(listOf("base", "one", "two"), contextMessages.map(::text))
            assertEquals("system|one|two", beforeStart.systemPrompt)
            assertEquals(listOf("one", "two"), beforeStart.messages.map { text(it.content) })
        }

    @Test
    fun duplicateToolsUseFirstRegistrationAndCommandsGetStableSuffixes() =
        runTest {
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("one") {
                    registerTool(testTool("same"))
                    registerCommand(PiExtensionCommand("inspect") { _, _ -> })
                    null
                },
            )
            runner.load(
                KotlinPiExtension("two") {
                    registerTool(testTool("same"))
                    registerCommand(PiExtensionCommand("inspect") { _, _ -> })
                    null
                },
            )

            assertEquals("same", runner.getAllRegisteredTools().single().name)
            assertEquals(listOf("inspect:1", "inspect:2"), runner.getRegisteredCommands().map { it.invocationName })
        }

    @Test
    fun extensionsCanContributeProvidersWithoutOwningTheRuntime() =
        runTest {
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("providers") {
                    registerProvider(testProvider())
                    registerImageProvider(testImageProvider())
                    null
                },
            )

            assertEquals(listOf("extension-provider"), runner.getAllRegisteredProviders().map { it.id })
            assertEquals(listOf("extension-images"), runner.getAllRegisteredImageProviders().map { it.id })
        }

    @Test
    fun commandNamesAvoidCollisionsWithExplicitSuffixesAndCommandsExecuteThroughRunner() =
        runTest {
            val executed = mutableListOf<String>()
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("first") {
                    registerCommand(PiExtensionCommand("inspect") { args, _ -> executed += "inspect:$args" })
                    registerCommand(PiExtensionCommand("inspect:1") { args, _ -> executed += "explicit:$args" })
                    null
                },
            )
            runner.load(
                KotlinPiExtension("second") {
                    registerCommand(PiExtensionCommand("inspect") { args, _ -> executed += "second:$args" })
                    null
                },
            )

            assertEquals(
                listOf("inspect:1", "inspect:1:2", "inspect:2"),
                runner.getRegisteredCommands().map { it.invocationName },
            )
            assertTrue(runner.executeCommand("inspect:1", "alpha"))
            assertTrue(runner.executeCommand("inspect:2", "beta"))
            assertTrue(runner.executeCommand("inspect:1:2", "gamma"))
            assertEquals(listOf("inspect:alpha", "second:beta", "explicit:gamma"), executed)
            assertTrue(!runner.executeCommand("missing"))
        }

    @Test
    fun projectTrustUsesFirstDecisiveDecisionAndContinuesAfterUndecided() =
        runTest {
            val calls = mutableListOf<String>()
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("undecided") {
                    on(PiExtensionEventType.PROJECT_TRUST) { _, _ ->
                        calls += "undecided"
                        PiExtensionEventResult.ProjectTrust(ProjectTrustDecision.UNDECIDED)
                    }
                    null
                },
            )
            runner.load(
                KotlinPiExtension("decision") {
                    on(PiExtensionEventType.PROJECT_TRUST) { _, _ ->
                        calls += "decision"
                        PiExtensionEventResult.ProjectTrust(ProjectTrustDecision.YES, remember = true)
                    }
                    null
                },
            )
            runner.load(
                KotlinPiExtension("never-called") {
                    on(PiExtensionEventType.PROJECT_TRUST) { _, _ ->
                        calls += "never-called"
                        PiExtensionEventResult.ProjectTrust(ProjectTrustDecision.NO)
                    }
                    null
                },
            )

            val result = runner.emitProjectTrust()
            assertEquals(ProjectTrustDecision.YES, result?.trusted)
            assertTrue(result?.remember == true)
            assertEquals(listOf("undecided", "decision"), calls)
        }

    @Test
    fun handlerFailuresAreIsolatedAndDisposedApisBecomeStale() =
        runTest {
            var capturedApi: PiExtensionApi? = null
            val runner = PiExtensionRunner("/workspace")
            runner.load(
                KotlinPiExtension("broken") {
                    capturedApi = this
                    on(PiExtensionEventType.AGENT_START, PiExtensionEventHandler { _, _ -> error("boom") })
                    null
                },
            )

            runner.emit(PiExtensionEvent.AgentStart)
            assertTrue(runner.getErrors().any { it.message == "boom" })

            runner.dispose()
            assertFailsWith<IllegalStateException> {
                capturedApi?.registerCommand(PiExtensionCommand("stale") { _, _ -> })
            }
        }

    @Test
    fun extensionEventSubscriptionsAreRemovedWhenRunnerDisposes() =
        runTest {
            val received = mutableListOf<Any?>()
            val bus = PiEventBusController()
            val runner = PiExtensionRunner("/workspace", events = bus)
            runner.load(
                KotlinPiExtension("events") {
                    val unsubscribe = events.on("custom") { received += it }
                    PiExtensionHandle { unsubscribe() }
                },
            )

            bus.emit("custom", "before")
            runner.dispose()
            bus.emit("custom", "after")

            assertEquals(listOf<Any?>("before"), received)
        }

    private fun extension(
        id: String,
        suffix: String,
    ) = KotlinPiExtension(id) {
        on(
            PiExtensionEventType.CONTEXT,
            PiExtensionEventHandler { event, _ ->
                val context = event as PiExtensionEvent.Context
                PiExtensionEventResult.Context(context.messages + user(suffix))
            },
        )
        on(
            PiExtensionEventType.BEFORE_AGENT_START,
            PiExtensionEventHandler { event, _ ->
                val beforeStart = event as PiExtensionEvent.BeforeAgentStart
                PiExtensionEventResult.BeforeAgentStart(
                    message = ExtensionMessage("test", UserText(suffix)),
                    systemPrompt = "${beforeStart.systemPrompt}|$suffix",
                )
            },
        )
        null
    }

    private fun testTool(name: String) =
        AgentTool(
            name = name,
            label = name,
            description = name,
            parameters = buildJsonObject {},
            execute = { _, _, _, _ -> AgentToolResult(listOf(TextContent("ok")), buildJsonObject {}) },
        )

    private fun testProvider() =
        object : PiProvider {
            override val id = "extension-provider"
            override val name = "Extension provider"
            override val supportedApis = setOf("extension-api")

            override suspend fun stream(
                model: Model,
                context: Context,
                options: SimpleStreamOptions,
            ): AssistantMessageEventStream = error("test provider is not invoked")
        }

    private fun testImageProvider() =
        object : PiImageProvider {
            override val id = "extension-images"
            override val name = "Extension images"
            override val supportedApis = setOf("extension-images-api")

            override suspend fun generateImages(
                model: ImagesModel,
                context: ImagesContext,
                options: ImagesOptions,
            ): AssistantImages = error("test image provider is not invoked")
        }

    private fun user(text: String) = UserMessage(UserText(text), 1)

    private fun text(message: dev.xuexue.pi.ai.AgentMessage): String =
        when (message) {
            is UserMessage -> (message.content as UserText).value
            else -> error("Unexpected message type")
        }

    private fun text(content: dev.xuexue.pi.ai.UserContent): String = (content as UserText).value
}
