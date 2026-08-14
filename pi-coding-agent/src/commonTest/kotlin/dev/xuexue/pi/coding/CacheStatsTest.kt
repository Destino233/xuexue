package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.ai.StopReason
import dev.xuexue.pi.ai.Usage
import dev.xuexue.pi.ai.UsageCost
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheStatsTest {
    private val models = ModelPriceSource { _, _ -> ModelPrice(cacheRead = 0.3) }

    private fun assistant(
        input: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        inputCost: Double = 0.0,
        cacheReadCost: Double = 0.0,
        cacheWriteCost: Double = 0.0,
        model: String = "test-model",
        timestamp: Long = 0,
    ): AssistantMessage =
        AssistantMessage(
            content = emptyList(),
            api = "anthropic-messages",
            provider = "test",
            model = model,
            usage =
                Usage(
                    input = input,
                    output = 10,
                    cacheRead = cacheRead,
                    cacheWrite = cacheWrite,
                    totalTokens = 0,
                    cost = UsageCost(inputCost, 0.0, cacheReadCost, cacheWriteCost, 0.0),
                ),
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )

    private fun entry(message: AssistantMessage): SessionEntry = SessionMessageEntry("x", null, "", message)

    // Turn 1: fresh 100k cache write at $3.75/M
    private val turn1 = assistant(cacheWrite = 100_000, cacheWriteCost = 0.375, timestamp = 0)

    // Turn 2: healthy, everything read back at $0.30/M
    private val turn2 =
        assistant(
            cacheRead = 100_000,
            cacheWrite = 5_000,
            cacheReadCost = 0.03,
            cacheWriteCost = 0.019,
            timestamp = 60_000,
        )

    @Test
    fun accumulatesMissedTokensAndCostAcrossTurns() {
        // Turn 3: full miss, previous 105k prompt re-billed at $3.75/M write
        val turn3 = assistant(cacheWrite = 110_000, cacheWriteCost = 0.4125, timestamp = 120_000)
        val totals = computeCacheWaste(listOf(entry(turn1), entry(turn2), entry(turn3)), models)
        assertEquals(105_000, totals.missedTokens)
        // 105k at ($3.75 - $0.30)/M
        assertEquals(0.36225, totals.missedCost, 1e-5)
    }

    @Test
    fun countsNothingForHealthySessions() {
        val totals = computeCacheWaste(listOf(entry(turn1), entry(turn2)), models)
        assertEquals(0, totals.missedTokens)
        assertEquals(0.0, totals.missedCost)
    }

    @Test
    fun skipsTheTurnAfterACompactionReset() {
        val reset = CompactionEntry("c", null, "", "summary", "first", 0)
        val afterReset = assistant(cacheWrite = 20_000, cacheWriteCost = 0.075)
        val totals = computeCacheWaste(listOf(entry(turn1), reset, entry(afterReset)), models)
        assertEquals(0, totals.missedTokens)
    }

    @Test
    fun countsMissesCausedByModelSwitches() {
        val otherModel = assistant(cacheWrite = 100_000, cacheWriteCost = 0.375, model = "other-model")
        val totals = computeCacheWaste(listOf(entry(turn1), entry(otherModel)), models)
        assertEquals(100_000, totals.missedTokens)
        assertEquals(1, totals.missCount)
    }

    @Test
    fun skipsProvidersThatReportNoCacheActivity() {
        val a = assistant(input = 100_000)
        val b = assistant(input = 110_000)
        val totals = computeCacheWaste(listOf(entry(a), entry(b)), models)
        assertEquals(0, totals.missedTokens)
    }

    @Test
    fun mapsCountedMissesToTheirAssistantMessages() {
        val missTurn = assistant(cacheWrite = 110_000, cacheWriteCost = 0.4125, timestamp = 120_000)
        val misses = collectCacheMisses(listOf(entry(turn1), entry(turn2), entry(missTurn)), models)
        assertEquals(1, misses.size)
        assertEquals(105_000, misses[missTurn]?.missedTokens)
    }

    @Test
    fun detectsAMissOnAJustCompletedMessageWithIdleTime() {
        val missMessage = assistant(cacheWrite = 110_000, cacheWriteCost = 0.4125, timestamp = 600_000)
        val miss = requireNotNull(detectCacheMiss(listOf(entry(turn1), entry(turn2)), missMessage, models))
        assertEquals(105_000, miss.missedTokens)
        assertEquals(0.36225, miss.missedCost, 1e-5)
        // 600s - 60s since the previous request
        assertEquals(540_000, miss.idleMs)
        assertEquals(false, miss.modelChanged)
    }

    @Test
    fun flagsModelSwitchesOnDetectedMisses() {
        val otherModel =
            assistant(cacheWrite = 110_000, cacheWriteCost = 0.4125, model = "other-model", timestamp = 120_000)
        val miss = detectCacheMiss(listOf(entry(turn1), entry(turn2)), otherModel, models)
        assertEquals(105_000, miss?.missedTokens)
        assertEquals(true, miss?.modelChanged)
    }

    @Test
    fun returnsNullForHealthyTurns() {
        val healthy =
            assistant(
                cacheRead = 105_000,
                cacheWrite = 2_000,
                cacheReadCost = 0.0315,
                cacheWriteCost = 0.0075,
                timestamp = 120_000,
            )
        assertNull(detectCacheMiss(listOf(entry(turn1), entry(turn2)), healthy, models))
    }

    @Test
    fun returnsNullForTheFirstTurnOfASession() {
        assertNull(detectCacheMiss(emptyList(), turn1, models))
    }
}
