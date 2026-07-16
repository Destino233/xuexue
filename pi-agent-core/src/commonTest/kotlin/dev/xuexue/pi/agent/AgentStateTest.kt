@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.agent

import dev.xuexue.pi.ai.AgentMessage
import dev.xuexue.pi.ai.ModelThinkingLevel
import dev.xuexue.pi.ai.TextContent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentStateTest {
    @Test
    fun createsDefaultState() {
        val state = Agent().currentState

        assertEquals("", state.systemPrompt)
        assertEquals("unknown", state.model.id)
        assertEquals(ModelThinkingLevel.OFF, state.thinkingLevel)
        assertEquals(emptyList(), state.tools)
        assertEquals(emptyList(), state.messages)
        assertFalse(state.isStreaming)
        assertNull(state.streamingMessage)
        assertEquals(emptySet(), state.pendingToolCalls)
        assertNull(state.errorMessage)
        assertEquals(0, state.queuedSteeringMessages)
        assertEquals(0, state.queuedFollowUpMessages)
    }

    @Test
    fun createsCustomInitialStateWithCopiedCollections() {
        val model = testModel("custom")
        val tools = mutableListOf(testTool())
        val messages = mutableListOf<AgentMessage>(testUser("initial"))
        val agent =
            Agent(
                AgentOptions(
                    initialState =
                        AgentInitialState(
                            systemPrompt = "You are helpful.",
                            model = model,
                            thinkingLevel = ModelThinkingLevel.LOW,
                            tools = tools,
                            messages = messages,
                        ),
                ),
            )

        assertEquals("You are helpful.", agent.currentState.systemPrompt)
        assertEquals(model, agent.currentState.model)
        assertEquals(ModelThinkingLevel.LOW, agent.currentState.thinkingLevel)
        assertEquals(tools, agent.currentState.tools)
        assertEquals(messages, agent.currentState.messages)
        assertNotSame(tools, agent.currentState.tools)
        assertNotSame(messages, agent.currentState.messages)
    }

    @Test
    fun mutatorsCopyCollectionsAndDoNotEmitAgentEvents() {
        val agent = Agent()
        var eventCount = 0
        val subscription = agent.subscribe { _, _ -> eventCount++ }
        val model = testModel("replacement")
        val tools = mutableListOf(testTool())
        val messages = mutableListOf<AgentMessage>(testUser("one"))

        agent.setSystemPrompt("Custom prompt")
        agent.setModel(model)
        agent.setThinkingLevel(ModelThinkingLevel.HIGH)
        agent.setTools(tools)
        agent.replaceMessages(messages)
        agent.appendMessage(testUser("two", timestamp = 2))

        assertEquals("Custom prompt", agent.currentState.systemPrompt)
        assertEquals(model, agent.currentState.model)
        assertEquals(ModelThinkingLevel.HIGH, agent.currentState.thinkingLevel)
        assertEquals(tools, agent.currentState.tools)
        assertNotSame(tools, agent.currentState.tools)
        assertNotSame(messages, agent.currentState.messages)
        assertEquals(2, agent.currentState.messages.size)
        assertEquals(0, eventCount)

        subscription.dispose()
        agent.setSystemPrompt("After unsubscribe")
        assertEquals(0, eventCount)
        agent.clearMessages()
        assertEquals(emptyList(), agent.currentState.messages)
    }

    @Test
    fun queueCommandsAreObservableThroughStateFlow() =
        runTest {
            val agent = Agent()
            val observed = mutableListOf<AgentState>()
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.state.collect { observed += it }
                }

            agent.steer(testUser("steer"))
            agent.followUp(testUser("follow"))
            runCurrent()

            assertEquals(1, agent.currentState.queuedSteeringMessages)
            assertEquals(1, agent.currentState.queuedFollowUpMessages)
            assertTrue(observed.any { it.queuedSteeringMessages == 1 })
            assertTrue(observed.any { it.queuedFollowUpMessages == 1 })

            agent.clearAllQueues()
            runCurrent()
            assertEquals(0, agent.currentState.queuedSteeringMessages)
            assertEquals(0, agent.currentState.queuedFollowUpMessages)
            collector.cancelAndJoin()
        }

    @Test
    fun sharedEventFlowAndSubscribersObserveSameSequence() =
        runTest {
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction = testCompletedStream(testAssistant(listOf(TextContent("ok")))),
                    ),
                )
            val flowEvents = mutableListOf<String>()
            val listenerEvents = mutableListOf<String>()
            val collector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    agent.events.collect { flowEvents += it.testTypeName() }
                }
            agent.subscribe { event, _ -> listenerEvents += event.testTypeName() }

            agent.prompt("hello")
            runCurrent()

            assertEquals(listenerEvents, flowEvents)
            assertEquals(
                listOf(
                    "agent_start",
                    "turn_start",
                    "message_start",
                    "message_end",
                    "message_start",
                    "message_end",
                    "turn_end",
                    "agent_end",
                ),
                flowEvents,
            )
            assertFalse(agent.currentState.isStreaming)
            collector.cancelAndJoin()
        }

    private fun testTool(): AgentTool =
        AgentTool(
            name = "test",
            label = "Test",
            description = "Test tool",
            parameters = buildJsonObject { put("type", "object") },
            execute = { _, _, _, _ ->
                AgentToolResult(listOf(TextContent("ok")), buildJsonObject {})
            },
        )
}
