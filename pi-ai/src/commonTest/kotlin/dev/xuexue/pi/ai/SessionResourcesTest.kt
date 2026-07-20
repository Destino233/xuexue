package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionResourcesTest {
    @Test
    fun cleanupRunsAllCallbacksAndRetainsFailures() {
        val calls = mutableListOf<String>()
        val first =
            registerSessionResourceCleanup {
                calls += "first:$it"
                error("first failure")
            }
        val second = registerSessionResourceCleanup { calls += "second:$it" }
        try {
            val error =
                assertFailsWith<SessionResourceCleanupException> {
                    cleanupSessionResources("session-1")
                }
            assertEquals(listOf("first:session-1", "second:session-1"), calls)
            assertEquals(1, error.failures.size)
            assertEquals("first failure", error.failures.single().message)
        } finally {
            first()
            second()
        }
    }

    @Test
    fun unregisterIsIdempotentAndPreventsLaterCleanup() {
        var calls = 0
        val unregister = registerSessionResourceCleanup { calls++ }
        unregister()
        unregister()
        cleanupSessionResources("session-2")
        assertEquals(0, calls)
    }

    @Test
    fun cleanupCanBeCalledWithoutSessionId() {
        var received: String? = "unexpected"
        val unregister = registerSessionResourceCleanup { received = it }
        try {
            cleanupSessionResources()
            assertTrue(received == null)
        } finally {
            unregister()
        }
    }
}
