package dev.xuexue.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderUtilitiesTest {
    @Test
    fun shortHashMatchesPiForUtf16CodeUnits() {
        assertEquals("k4n83c7h0j2b", shortHash(""))
        assertEquals("1h6qa0qrowduu", shortHash("hello"))
        assertEquals("n7rb4n1m39uz8", shortHash("hello world"))
        assertEquals("exhn2z14bn7rf", shortHash("x🚀y"))
        assertEquals("120u02ot2nba6", shortHash("x${0xD83D.toChar()}y"))
    }

    @Test
    fun providerHeadersToRecordDropsOnlyNullHeaders() {
        assertEquals(
            mapOf("Content-Type" to "application/json", "X-Trace" to "value"),
            headersToRecord(
                listOf("Content-Type" to "application/json", "X-Trace" to "value"),
            ),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer token", "Empty" to ""),
            providerHeadersToRecord(
                mapOf("Authorization" to "Bearer token", "Removed" to null, "Empty" to ""),
            ),
        )
        assertNull(providerHeadersToRecord(null))
        assertNull(providerHeadersToRecord(mapOf("Removed" to null)))
    }

    @Test
    fun requestEnvironmentTakesPrecedenceAndEmptyValuesFallThrough() {
        assertEquals("override", getProviderEnvValue("PATH", mapOf("PATH" to "override")))
        assertEquals(ambientProviderEnvironment()["PATH"], getProviderEnvValue("PATH", mapOf("PATH" to "")))
    }
}
