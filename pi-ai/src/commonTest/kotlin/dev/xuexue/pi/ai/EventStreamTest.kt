package dev.xuexue.pi.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EventStreamTest {
    @Test
    fun doneEventIsDeliveredAndResolvesFinalMessage() =
        runTest {
            val partial = assistantMessage(StopReason.STOP)
            val final = assistantMessage(StopReason.TOOL_USE)
            val stream = createAssistantMessageEventStream()
            val received = async { stream.asFlow().toList() }

            stream.push(AssistantMessageEvent.Start(partial))
            stream.push(AssistantMessageEvent.Done(SuccessfulStopReason.TOOL_USE, final))
            stream.push(AssistantMessageEvent.TextDelta(0, "ignored", final))

            assertEquals(
                listOf(
                    AssistantMessageEvent.Start(partial),
                    AssistantMessageEvent.Done(SuccessfulStopReason.TOOL_USE, final),
                ),
                received.await(),
            )
            assertEquals(final, stream.result())
        }

    @Test
    fun errorEventResolvesItsAssistantMessage() =
        runTest {
            val failure = assistantMessage(StopReason.ERROR, "request failed")
            val stream = createAssistantMessageEventStream()

            stream.push(AssistantMessageEvent.Error(FailureStopReason.ERROR, failure))

            assertEquals(failure, stream.result())
            assertEquals(
                listOf(AssistantMessageEvent.Error(FailureStopReason.ERROR, failure)),
                stream.asFlow().toList(),
            )
        }

    @Test
    fun explicitEndClosesIterationAndCanProvideResult() =
        runTest {
            val final = assistantMessage(StopReason.STOP)
            val stream =
                EventStream<String, AssistantMessage>(
                    isComplete = { false },
                    extractResult = { error("No terminal event expected: $it") },
                )

            stream.push("partial")
            stream.end(final)

            assertEquals(listOf("partial"), stream.asFlow().toList())
            assertEquals(final, stream.result())
        }

    private fun assistantMessage(
        stopReason: StopReason,
        errorMessage: String? = null,
    ): AssistantMessage =
        AssistantMessage(
            content = emptyList(),
            api = "openai-responses",
            provider = "openai",
            model = "gpt-test",
            usage =
                Usage(
                    input = 0,
                    output = 0,
                    cacheRead = 0,
                    cacheWrite = 0,
                    totalTokens = 0,
                    cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
                ),
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = 1,
        )
}
