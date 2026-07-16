@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentToolUpdateTest {
    @Test
    fun ignoresToolUpdatesAfterExecutionSettles() =
        runTest {
            var delayedUpdate: AgentToolUpdateCallback? = null
            val tool =
                AgentTool(
                    name = "delayed_tool",
                    label = "Delayed Tool",
                    description = "Captures progress callbacks",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, onUpdate ->
                        delayedUpdate = onUpdate
                        onUpdate?.update(toolResult("running"))
                        toolResult("done", terminate = true)
                    },
                )
            val agent = toolAgent(listOf(tool), listOf(testToolCall("call-1", "delayed_tool", buildJsonObject {})))
            val events = mutableListOf<AgentEvent>()
            agent.subscribe { event, _ -> events += event }

            agent.prompt("run tool")
            val eventCount = events.size
            delayedUpdate?.update(toolResult("late"))
            runCurrent()

            assertEquals(1, events.filterIsInstance<AgentEvent.ToolExecutionUpdate>().size)
            assertEquals(eventCount, events.size)
        }

    @Test
    fun ignoresSettledParallelToolUpdateWhileAnotherToolRuns() =
        runTest {
            val slowStarted = CompletableDeferred<Unit>()
            val settledEnded = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            var settledUpdate: AgentToolUpdateCallback? = null
            val settledTool =
                AgentTool(
                    name = "settled_tool",
                    label = "Settled Tool",
                    description = "Settles immediately",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, onUpdate ->
                        settledUpdate = onUpdate
                        toolResult("done", terminate = true)
                    },
                )
            val slowTool =
                AgentTool(
                    name = "slow_tool",
                    label = "Slow Tool",
                    description = "Keeps the run active",
                    parameters = buildJsonObject { put("type", "object") },
                    execute = { _, _, _, _ ->
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                        toolResult("done", terminate = true)
                    },
                )
            val calls =
                listOf(
                    testToolCall("call-1", "settled_tool", buildJsonObject {}),
                    testToolCall("call-2", "slow_tool", buildJsonObject {}),
                )
            val agent = toolAgent(listOf(settledTool, slowTool), calls)
            val events = mutableListOf<AgentEvent>()
            agent.subscribe { event, _ ->
                events += event
                if (event is AgentEvent.ToolExecutionEnd && event.toolCallId == "call-1") {
                    settledEnded.complete(Unit)
                }
            }

            val promptJob = launch { agent.prompt("run tools") }
            slowStarted.await()
            settledEnded.await()
            val eventCount = events.size
            settledUpdate?.update(toolResult("late"))
            runCurrent()

            assertEquals(eventCount, events.size)
            releaseSlow.complete(Unit)
            promptJob.join()
            assertEquals(0, events.filterIsInstance<AgentEvent.ToolExecutionUpdate>().size)
        }

    private fun toolAgent(
        tools: List<AgentTool>,
        calls: List<dev.xuexue.pi.ai.ToolCall>,
    ): Agent =
        Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = tools),
                streamFunction =
                    testCompletedStream(
                        testAssistant(
                            calls,
                            stopReason = StopReason.TOOL_USE,
                        ),
                    ),
            ),
        )

    private fun toolResult(
        status: String,
        terminate: Boolean? = null,
    ): AgentToolResult =
        AgentToolResult(
            content = listOf(TextContent(status)),
            details = buildJsonObject { put("status", status) },
            terminate = terminate,
        )
}
