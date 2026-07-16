package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.coding.session.CustomMessage
import dev.xuexue.pi.coding.session.CustomMessageEntry
import dev.xuexue.pi.coding.session.SessionMessageEntry
import dev.xuexue.pi.coding.session.type
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionPromptTest {
    @Test
    fun promptPersistsMessagesAndEmitsTheFullLifecycle() =
        runTest {
            val harness = AgentSessionHarness()
            val events = mutableListOf<String>()
            harness.session.subscribe { event -> events += event::class.simpleName.orEmpty() }

            harness.session.prompt("hi")

            assertEquals(listOf("user", "assistant"), harness.session.messages.map(::messageRole))
            assertEquals("hi", messageText(harness.session.messages.first()))
            assertEquals(
                listOf("user", "assistant"),
                harness.manager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .map { messageRole(it.message) },
            )
            assertEquals(
                listOf(
                    "AgentStart",
                    "TurnStart",
                    "MessageStart",
                    "MessageEnd",
                    "MessageStart",
                    "MessageEnd",
                    "TurnEnd",
                    "AgentEnd",
                    "AgentSettled",
                ),
                events,
            )
            assertEquals(0, harness.pendingResponseCount)
            assertTrue(harness.session.isIdle)
        }

    @Test
    fun idleCustomMessageIsPersistedBeforeSyntheticMessageEvents() =
        runTest {
            val harness = AgentSessionHarness()
            val entryCounts = mutableListOf<Int>()
            harness.session.subscribe { event ->
                if (event is AgentSessionEvent.MessageStart || event is AgentSessionEvent.MessageEnd) {
                    entryCounts += harness.manager.getEntries().size
                }
            }

            harness.session.sendCustomMessage(
                CustomMessageInput(
                    customType = "note",
                    content = UserText("remember"),
                    display = true,
                    details = JsonPrimitive(1),
                ),
            )

            val message = assertIs<CustomMessage>(harness.session.messages.single())
            assertEquals("note", message.customType)
            val entry = assertIs<CustomMessageEntry>(harness.manager.getEntries().single())
            assertEquals("note", entry.customType)
            assertEquals(listOf(1, 1), entryCounts)
        }

    @Test
    fun nextTurnCustomMessagesFollowTheUserMessage() =
        runTest {
            var rolesAtProvider = emptyList<String>()
            val harness =
                AgentSessionHarness(
                    responses =
                        listOf(
                            { context ->
                                rolesAtProvider = context.messages.map(::messageRole)
                                testAssistant("done")
                            },
                        ),
                )
            harness.session.sendCustomMessage(
                CustomMessageInput("aside", UserText("carry this"), display = true),
                CustomMessageOptions(deliverAs = CustomMessageDelivery.NEXT_TURN),
            )

            harness.session.prompt("normal")

            assertEquals(listOf("user", "user"), rolesAtProvider)
            assertEquals(listOf("user", "custom", "assistant"), harness.session.messages.map(::messageRole))
            assertEquals(
                listOf("message", "custom_message", "message"),
                harness.manager.getEntries().map { it.type },
            )
        }

    @Test
    fun sessionNamesAndCustomEntriesEmitTheirDedicatedEvents() {
        val harness = AgentSessionHarness()
        val events = mutableListOf<AgentSessionEvent>()
        harness.session.subscribe(events::add)

        harness.session.setSessionName("hello\nworld\r\nagain")
        val customEntryId = harness.session.appendCustomEntry("state", JsonPrimitive("value"))

        assertEquals("hello world again", harness.session.sessionName)
        assertEquals(
            "hello world again",
            assertIs<AgentSessionEvent.SessionInfoChanged>(events[0]).name,
        )
        assertEquals(customEntryId, assertIs<AgentSessionEvent.EntryAppended>(events[1]).entry.id)
    }

    @Test
    fun parsesPiSkillBlocksExactly() {
        val parsed =
            parseSkillBlock(
                """<skill name="review" location="/skills/review/SKILL.md">
instructions
</skill>

src/main.kt""",
            )

        assertEquals("review", parsed?.name)
        assertEquals("/skills/review/SKILL.md", parsed?.location)
        assertEquals("instructions", parsed?.content)
        assertEquals("src/main.kt", parsed?.userMessage)
        assertNull(parseSkillBlock("not a skill block"))
    }
}

internal fun messageRole(message: dev.xuexue.pi.ai.AgentMessage): String =
    when (message) {
        is dev.xuexue.pi.ai.UserMessage -> "user"
        is dev.xuexue.pi.ai.AssistantMessage -> "assistant"
        is dev.xuexue.pi.ai.ToolResultMessage -> "toolResult"
        is CustomMessage -> "custom"
        else -> "other"
    }
