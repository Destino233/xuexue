@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.xuexue.pi.coding

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractionTest {
    @Test
    fun brokerPublishesPendingRequestAndResolvesTypedResponse() =
        runTest {
            val broker = InteractionRequestBroker()
            val request =
                InteractionRequest.Select(
                    id = "select-1",
                    title = "Choose",
                    message = "Pick a value",
                    options = listOf("one", "two"),
                )
            val response = async { broker.request(request) }
            runCurrent()

            assertEquals(listOf(request), broker.requests.value)
            assertTrue(broker.respond(InteractionResponse.Select("select-1", 1)))
            assertEquals(1, (response.await() as InteractionResponse.Select).selectedIndex)
            assertTrue(broker.requests.value.isEmpty())
        }

    @Test
    fun brokerRejectsMismatchedResponsesAndSupportsCancellation() =
        runTest {
            val broker = InteractionRequestBroker()
            val request = InteractionRequest.Input("input-1", "Input", "Value")
            val response = async { broker.request(request) }
            runCurrent()

            assertFalse(broker.respond(InteractionResponse.Confirm("input-1", accepted = true)))
            assertTrue(broker.cancel("input-1", "test cancellation"))
            assertEquals(
                InteractionResponse.Cancelled("input-1", "test cancellation"),
                response.await(),
            )
        }

    @Test
    fun notificationsAreDeliveredWithoutBlockingRequests() =
        runTest {
            val broker = InteractionRequestBroker()
            val notification =
                InteractionRequest.Notify(
                    id = "notice-1",
                    title = "Done",
                    message = "Completed",
                    severity = InteractionSeverity.SUCCESS,
                )

            val observed = async { broker.notifications.first() }
            runCurrent()
            broker.notify(notification)

            assertEquals(notification, observed.await())
        }
}
