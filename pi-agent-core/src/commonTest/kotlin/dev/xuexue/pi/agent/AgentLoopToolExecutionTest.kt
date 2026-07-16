package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.Message
import dev.xuexue.pi.ai.TextContent
import dev.xuexue.pi.ai.ToolResultMessage
import dev.xuexue.pi.ai.UserMessage
import dev.xuexue.pi.ai.UserText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLoopToolExecutionTest {
    @Test
    fun executesMutatedBeforeToolCallArgumentsWithoutRevalidation() =
        runTest {
            val executed = mutableListOf<String>()
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    validateArguments = { arguments ->
                        require(arguments["value"]?.jsonPrimitive?.isString == true)
                        arguments
                    },
                    execute = { _, params, _, _ ->
                        executed += params.getValue("value").jsonPrimitive.content
                        toolResult("echoed")
                    },
                )
            var callIndex = 0
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun beforeToolCall(
                        context: BeforeToolCallContext,
                        signal: dev.xuexue.pi.ai.AbortSignal?,
                    ): BeforeToolCallResult? {
                        context.args["value"] = JsonPrimitive(123)
                        return null
                    }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("echo")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall(
                                                "tool-1",
                                                "echo",
                                                buildJsonObject { put("value", "hello") },
                                            ),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )

            stream.asFlow().toList()

            assertEquals(listOf("123"), executed)
        }

    @Test
    fun preparesToolArgumentsBeforeValidation() =
        runTest {
            var validated = false
            var executedEdits = 0
            val tool =
                AgentTool(
                    name = "edit",
                    label = "Edit",
                    description = "Edit tool",
                    parameters = buildJsonObject { put("type", "object") },
                    prepareArguments = { arguments ->
                        buildJsonObject {
                            putJsonArray("edits") {
                                add(
                                    buildJsonObject {
                                        put("oldText", arguments.getValue("oldText"))
                                        put("newText", arguments.getValue("newText"))
                                    },
                                )
                            }
                        }
                    },
                    validateArguments = { arguments ->
                        validated = true
                        requireNotNull(arguments["edits"])
                        arguments
                    },
                    execute = { _, params, _, _ ->
                        executedEdits = params.getValue("edits").jsonArray.size
                        toolResult("edited")
                    },
                )
            var callIndex = 0
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("edit")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    testConfig(),
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall(
                                                "tool-1",
                                                "edit",
                                                buildJsonObject {
                                                    put("oldText", "before")
                                                    put("newText", "after")
                                                },
                                            ),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )

            stream.asFlow().toList()

            assertTrue(validated)
            assertEquals(1, executedEdits)
        }

    @Test
    fun emitsParallelToolEndsByCompletionButPersistsResultsBySourceOrder() =
        runTest {
            var firstResolved = false
            var parallelObserved = false
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, params, _, _ ->
                        if (params.getValue("value").jsonPrimitive.content == "first") {
                            delay(10)
                            firstResolved = true
                        } else if (!firstResolved) {
                            parallelObserved = true
                        }
                        toolResult(params.getValue("value").jsonPrimitive.content)
                    },
                )
            var callIndex = 0
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("both")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    testConfig(toolExecution = ToolExecutionMode.PARALLEL),
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall("tool-1", "echo", valueArguments("first")),
                                            testToolCall("tool-2", "echo", valueArguments("second")),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            val events = stream.asFlow().toList()

            val endIds = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().map { it.toolCallId }
            val resultIds =
                events
                    .filterIsInstance<AgentEvent.MessageEnd>()
                    .mapNotNull { (it.message as? ToolResultMessage)?.toolCallId }
            val turnResultIds =
                events
                    .filterIsInstance<AgentEvent.TurnEnd>()
                    .single { it.toolResults.isNotEmpty() }
                    .toolResults
                    .map { it.toolCallId }

            assertTrue(parallelObserved)
            assertEquals(listOf("tool-2", "tool-1"), endIds)
            assertEquals(listOf("tool-1", "tool-2"), resultIds)
            assertEquals(listOf("tool-1", "tool-2"), turnResultIds)
        }

    @Test
    fun preparesAllParallelCallsBeforeStartingAnyTool() =
        runTest {
            val phases = mutableListOf<String>()
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, params, _, _ ->
                        phases += "execute:${params.getValue("value").jsonPrimitive.content}"
                        toolResult("ok")
                    },
                )
            var callIndex = 0
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun beforeToolCall(
                        context: BeforeToolCallContext,
                        signal: dev.xuexue.pi.ai.AbortSignal?,
                    ): BeforeToolCallResult? {
                        phases += "prepare:${context.toolCall.id}"
                        return null
                    }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("both")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall("tool-1", "echo", valueArguments("first")),
                                            testToolCall("tool-2", "echo", valueArguments("second")),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            stream.asFlow().toList()

            assertEquals(
                listOf("prepare:tool-1", "prepare:tool-2", "execute:first", "execute:second"),
                phases,
            )
        }

    @Test
    fun injectsSteeringMessagesAfterAllToolCallsComplete() =
        runTest {
            val executed = mutableListOf<String>()
            val queued = testUser("interrupt", timestamp = 3)
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, params, _, _ ->
                        executed += params.getValue("value").jsonPrimitive.content
                        toolResult("ok")
                    },
                )
            var delivered = false
            var callIndex = 0
            var sawQueued = false
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()
                    override val toolExecution = ToolExecutionMode.SEQUENTIAL

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun getSteeringMessages(): List<AgentMessage> =
                        if (executed.size >= 2 && !delivered) {
                            delivered = true
                            listOf(queued)
                        } else {
                            emptyList()
                        }
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("start")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, context, _ ->
                            if (callIndex++ == 1) {
                                sawQueued =
                                    context.messages.filterIsInstance<UserMessage>().any { message ->
                                        (message.content as UserText).value == "interrupt"
                                    }
                            }
                            if (callIndex == 1) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall("tool-1", "echo", valueArguments("first")),
                                            testToolCall("tool-2", "echo", valueArguments("second")),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            val events = stream.asFlow().toList()

            val sequence =
                events.filterIsInstance<AgentEvent.MessageStart>().mapNotNull { event ->
                    when (val message = event.message) {
                        is ToolResultMessage -> "tool:${message.toolCallId}"
                        is UserMessage -> (message.content as? UserText)?.value
                        else -> null
                    }
                }
            assertEquals(listOf("first", "second"), executed)
            assertTrue(sawQueued)
            assertTrue(sequence.indexOf("tool:tool-1") < sequence.indexOf("interrupt"))
            assertTrue(sequence.indexOf("tool:tool-2") < sequence.indexOf("interrupt"))
        }

    @Test
    fun forcesSequentialExecutionWhenToolDeclaresSequentialMode() =
        runTest {
            var firstResolved = false
            var parallelObserved = false
            val tool =
                AgentTool(
                    name = "slow",
                    label = "Slow",
                    description = "Slow tool",
                    parameters = buildJsonObject { put("type", "object") },
                    executionMode = ToolExecutionMode.SEQUENTIAL,
                    execute = { _, params, _, _ ->
                        if (params.getValue("value").jsonPrimitive.content == "first") {
                            delay(10)
                            firstResolved = true
                        } else if (!firstResolved) {
                            parallelObserved = true
                        }
                        toolResult("ok")
                    },
                )
            val events = runTwoToolCallLoop(tool, ToolExecutionMode.PARALLEL)

            assertFalse(parallelObserved)
            assertEquals(
                listOf("tool-1", "tool-2"),
                events
                    .filterIsInstance<AgentEvent.MessageEnd>()
                    .mapNotNull { (it.message as? ToolResultMessage)?.toolCallId },
            )
        }

    @Test
    fun anySequentialToolForcesWholeBatchToRunSequentially() =
        runTest {
            val executionOrder = mutableListOf<String>()
            val slow =
                AgentTool(
                    name = "slow",
                    label = "Slow",
                    description = "Slow tool",
                    parameters = buildJsonObject { put("type", "object") },
                    executionMode = ToolExecutionMode.SEQUENTIAL,
                    execute = { _, params, _, _ ->
                        executionOrder += "slow:start"
                        delay(10)
                        executionOrder += "slow:end"
                        toolResult("ok")
                    },
                )
            val fast =
                AgentTool(
                    name = "fast",
                    label = "Fast",
                    description = "Fast tool",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, _ ->
                        executionOrder += "fast"
                        toolResult("ok")
                    },
                )
            var callIndex = 0
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("both")),
                    AgentContext("", mutableListOf(), listOf(slow, fast)),
                    testConfig(),
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall("tool-1", "slow", valueArguments("a")),
                                            testToolCall("tool-2", "fast", valueArguments("b")),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            stream.asFlow().toList()

            assertEquals(listOf("slow:start", "slow:end", "fast"), executionOrder)
        }

    @Test
    fun allowsParallelExecutionWhenAllToolsDeclareParallelMode() =
        runTest {
            var firstResolved = false
            var parallelObserved = false
            val tool =
                AgentTool(
                    name = "echo",
                    label = "Echo",
                    description = "Echo tool",
                    parameters = buildJsonObject { put("type", "object") },
                    executionMode = ToolExecutionMode.PARALLEL,
                    execute = { _, params, _, _ ->
                        if (params.getValue("value").jsonPrimitive.content == "first") {
                            delay(10)
                            firstResolved = true
                        } else if (!firstResolved) {
                            parallelObserved = true
                        }
                        toolResult("ok")
                    },
                )
            runTwoToolCallLoop(tool)

            assertTrue(parallelObserved)
        }

    @Test
    fun stopsAfterToolBatchWhenEveryResultTerminates() =
        runTest {
            val tool = terminatingTool { true }
            var llmCalls = 0
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("run")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    testConfig(),
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            llmCalls++
                            testCompleted(
                                testAssistant(
                                    listOf(testToolCall("tool-1", "echo", valueArguments("hello"))),
                                    stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                ),
                            )
                        },
                )
            val events = stream.asFlow().toList()

            assertEquals(1, llmCalls)
            assertEquals(listOf("user", "assistant", "toolResult"), stream.result().map { it.testRole() })
            assertEquals(1, events.filterIsInstance<AgentEvent.TurnEnd>().size)
        }

    @Test
    fun continuesAfterParallelBatchWhenNotEveryResultTerminates() =
        runTest {
            val tool = terminatingTool { value -> value == "first" }
            var callIndex = 0
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("both")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    testConfig(toolExecution = ToolExecutionMode.PARALLEL),
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            if (callIndex++ == 0) {
                                testCompleted(
                                    testAssistant(
                                        listOf(
                                            testToolCall("tool-1", "echo", valueArguments("first")),
                                            testToolCall("tool-2", "echo", valueArguments("second")),
                                        ),
                                        stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                    ),
                                )
                            } else {
                                testCompleted(testAssistant(listOf(TextContent("done"))))
                            }
                        },
                )
            stream.asFlow().toList()

            assertEquals(2, callIndex)
            assertEquals(
                listOf("user", "assistant", "toolResult", "toolResult", "assistant"),
                stream.result().map { it.testRole() },
            )
        }

    @Test
    fun afterToolCallCanMarkBatchAsTerminating() =
        runTest {
            val tool = terminatingTool { false }
            var llmCalls = 0
            val config =
                object : AgentLoopConfig {
                    override val model = testModel()

                    override suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = testLlm(messages)

                    override suspend fun afterToolCall(
                        context: AfterToolCallContext,
                        signal: dev.xuexue.pi.ai.AbortSignal?,
                    ): AfterToolCallResult = AfterToolCallResult(terminate = true)
                }
            val stream =
                agentLoop(
                    this,
                    listOf(testUser("run")),
                    AgentContext("", mutableListOf(), listOf(tool)),
                    config,
                    streamFunction =
                        StreamFunction { _, _, _ ->
                            llmCalls++
                            testCompleted(
                                testAssistant(
                                    listOf(testToolCall("tool-1", "echo", valueArguments("hello"))),
                                    stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                ),
                            )
                        },
                )
            stream.asFlow().toList()

            assertEquals(1, llmCalls)
        }

    private suspend fun CoroutineScope.runTwoToolCallLoop(
        tool: AgentTool,
        toolExecution: ToolExecutionMode? = null,
    ): List<AgentEvent> {
        var callIndex = 0
        val stream =
            agentLoop(
                this,
                listOf(testUser("both")),
                AgentContext("", mutableListOf(), listOf(tool)),
                testConfig(toolExecution),
                streamFunction =
                    StreamFunction { _, _, _ ->
                        if (callIndex++ == 0) {
                            testCompleted(
                                testAssistant(
                                    listOf(
                                        testToolCall("tool-1", tool.name, valueArguments("first")),
                                        testToolCall("tool-2", tool.name, valueArguments("second")),
                                    ),
                                    stopReason = dev.xuexue.pi.ai.StopReason.TOOL_USE,
                                ),
                            )
                        } else {
                            testCompleted(testAssistant(listOf(TextContent("done"))))
                        }
                    },
            )
        return stream.asFlow().toList()
    }

    private fun terminatingTool(terminate: (String) -> Boolean): AgentTool =
        AgentTool(
            name = "echo",
            label = "Echo",
            description = "Echo tool",
            parameters = buildJsonObject { put("type", "object") },
            execute = { _, params, _, _ ->
                val value = params.getValue("value").jsonPrimitive.content
                toolResult(value, terminate(value))
            },
        )

    private fun toolResult(
        text: String,
        terminate: Boolean? = null,
    ): AgentToolResult =
        AgentToolResult(
            content = listOf(TextContent(text)),
            details = buildJsonObject {},
            terminate = terminate,
        )

    private fun valueArguments(value: String) = buildJsonObject { put("value", value) }
}
