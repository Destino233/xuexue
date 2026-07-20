@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.AssistantMessageEvent
import dev.xuexue.pi.ai.FailureStopReason
import dev.xuexue.pi.ai.UserText
import dev.xuexue.pi.ai.createAssistantMessageEventStream
import dev.xuexue.pi.coding.extensions.ExtensionBranchSummary
import dev.xuexue.pi.coding.extensions.KotlinPiExtension
import dev.xuexue.pi.coding.extensions.PiExtensionEvent
import dev.xuexue.pi.coding.extensions.PiExtensionEventHandler
import dev.xuexue.pi.coding.extensions.PiExtensionEventResult
import dev.xuexue.pi.coding.extensions.PiExtensionEventType
import dev.xuexue.pi.coding.extensions.PiExtensionRunner
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTreeNavigationTest {
    @Test
    fun navigatingToAUserMessageMovesToItsParentAndReturnsEditorText() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val target =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            manager.appendMessage(testAssistant("answer"))
            val session = AgentSessionHarness(sessionManager = manager).session

            val result = session.navigateTree(target)

            assertFalse(result.cancelled)
            assertEquals("first", result.editorText)
            assertNull(manager.getLeafId())
            assertTrue(session.messages.isEmpty())
        }

    @Test
    fun navigatingToACustomMessageMovesToItsParentAndReturnsEditorText() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val parent =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            val target = manager.appendCustomMessageEntry("note", UserText("custom"), display = true)
            manager.appendMessage(testAssistant("answer"))
            val session = AgentSessionHarness(sessionManager = manager).session

            val result = session.navigateTree(target)

            assertFalse(result.cancelled)
            assertEquals("custom", result.editorText)
            assertEquals(parent, manager.getLeafId())
        }

    @Test
    fun navigatingToAnAssistantMessageKeepsThatNodeAsTheLeaf() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            manager.appendMessage(
                dev.xuexue.pi.ai
                    .UserMessage(UserText("first"), 1),
            )
            val target = manager.appendMessage(testAssistant("answer"))
            manager.appendMessage(
                dev.xuexue.pi.ai
                    .UserMessage(UserText("later"), 3),
            )
            val session = AgentSessionHarness(sessionManager = manager).session

            val result = session.navigateTree(target)

            assertFalse(result.cancelled)
            assertNull(result.editorText)
            assertEquals(target, manager.getLeafId())
            assertEquals(2, session.messages.size)
        }

    @Test
    fun generatedSummaryIsAttachedAtTheNavigationTargetAndTracksFiles() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val target =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            manager.appendMessage(testAssistant("answer"))
            manager.appendMessage(
                dev.xuexue.pi.ai
                    .UserMessage(UserText("later"), 3),
            )
            val session =
                AgentSessionHarness(
                    responses = listOf({ _ -> testAssistant("summary") }),
                    sessionManager = manager,
                    apiKey = "key",
                ).session

            val result = session.navigateTree(target, NavigateTreeOptions(summarize = true))

            assertFalse(result.cancelled)
            val summary = assertIs<BranchSummaryEntry>(result.summaryEntry)
            assertNull(summary.parentId)
            assertEquals(false, summary.fromHook)
            assertTrue(summary.summary.startsWith("The user explored a different conversation branch"))
            assertEquals(summary.id, manager.getLeafId())
        }

    @Test
    fun extensionCanCancelNavigationWithoutChangingTheSession() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val target =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            val oldLeaf = manager.appendMessage(testAssistant("answer"))
            val runner = PiExtensionRunner("/tmp")
            runner.load(
                KotlinPiExtension("cancel") {
                    on(
                        PiExtensionEventType.SESSION_BEFORE_TREE,
                        PiExtensionEventHandler { _, _ ->
                            PiExtensionEventResult.SessionBeforeTree(cancel = true)
                        },
                    )
                    null
                },
            )
            val session = AgentSessionHarness(sessionManager = manager, extensionRunner = runner).session

            val result = session.navigateTree(target)

            assertTrue(result.cancelled)
            assertEquals(oldLeaf, manager.getLeafId())
            assertFalse(session.isCompacting)
        }

    @Test
    fun extensionCanReplaceTheSummaryAndObserveTheCompletedNavigation() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val target =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            manager.appendMessage(testAssistant("answer"))
            manager.appendMessage(
                dev.xuexue.pi.ai
                    .UserMessage(UserText("later"), 3),
            )
            val completedEvents = mutableListOf<PiExtensionEvent.SessionTree>()
            val runner = PiExtensionRunner("/tmp")
            runner.load(
                KotlinPiExtension("summary") {
                    on(
                        PiExtensionEventType.SESSION_BEFORE_TREE,
                        PiExtensionEventHandler { event, _ ->
                            val before = assertIs<PiExtensionEvent.SessionBeforeTree>(event)
                            assertTrue(before.preparation.userWantsSummary)
                            PiExtensionEventResult.SessionBeforeTree(
                                summary =
                                    ExtensionBranchSummary(
                                        summary = "extension summary",
                                        details = JsonObject(emptyMap()),
                                    ),
                                label = "checkpoint",
                            )
                        },
                    )
                    on(
                        PiExtensionEventType.SESSION_TREE,
                        PiExtensionEventHandler { event, _ ->
                            completedEvents += assertIs<PiExtensionEvent.SessionTree>(event)
                            null
                        },
                    )
                    null
                },
            )
            val session = AgentSessionHarness(sessionManager = manager, extensionRunner = runner).session

            val result = session.navigateTree(target, NavigateTreeOptions(summarize = true))

            val summary = assertIs<BranchSummaryEntry>(result.summaryEntry)
            assertEquals("extension summary", summary.summary)
            assertEquals(true, summary.fromHook)
            assertEquals("checkpoint", manager.getLabel(summary.id))
            assertEquals(summary.id, completedEvents.single().summaryEntry?.id)
            assertEquals(true, completedEvents.single().fromExtension)
        }

    @Test
    fun abortingBranchSummaryLeavesTheCurrentBranchUnchanged() =
        runTest {
            val manager = SessionManager.inMemory("/tmp")
            val target =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("first"), 1),
                )
            manager.appendMessage(testAssistant("answer"))
            val oldLeaf =
                manager.appendMessage(
                    dev.xuexue.pi.ai
                        .UserMessage(UserText("later"), 3),
                )
            val started = CompletableDeferred<Unit>()
            val stream =
                StreamFunction { _, _, options ->
                    createAssistantMessageEventStream().also { events ->
                        launch {
                            started.complete(Unit)
                            awaitAbort(requireNotNull(options.stream.signal))
                            events.push(
                                AssistantMessageEvent.Error(
                                    FailureStopReason.ABORTED,
                                    testAssistant("", stopReason = dev.xuexue.pi.ai.StopReason.ABORTED),
                                ),
                            )
                        }
                    }
                }
            val session =
                AgentSessionHarness(
                    responses = emptyList(),
                    sessionManager = manager,
                    streamFunction = stream,
                    apiKey = "key",
                ).session
            val navigation = async { session.navigateTree(target, NavigateTreeOptions(summarize = true)) }
            runCurrent()
            started.await()

            session.abortBranchSummary()
            val result = navigation.await()

            assertTrue(result.cancelled)
            assertEquals(true, result.aborted)
            assertEquals(oldLeaf, manager.getLeafId())
            assertFalse(session.isCompacting)
        }

    private suspend fun awaitAbort(signal: AbortSignal) {
        if (signal.isAborted) return
        suspendCancellableCoroutine { continuation ->
            val subscription = signal.onAbort { continuation.resume(Unit) }
            continuation.invokeOnCancellation { subscription.dispose() }
        }
    }
}
