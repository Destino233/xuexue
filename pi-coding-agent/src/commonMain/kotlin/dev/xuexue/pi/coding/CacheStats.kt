package dev.xuexue.pi.coding

import dev.xuexue.pi.ai.AssistantMessage
import dev.xuexue.pi.coding.session.BranchSummaryEntry
import dev.xuexue.pi.coding.session.CompactionEntry
import dev.xuexue.pi.coding.session.SessionEntry
import dev.xuexue.pi.coding.session.SessionMessageEntry
import kotlin.math.max
import kotlin.math.min

/**
 * Prompt-cache TTL: idle gaps longer than this are worth mentioning as the
 * likely cause of a miss. Anthropic's default cache TTL is 5 minutes.
 */
const val CACHE_TTL_MS: Long = 5 * 60 * 1000

/** Per-turn misses at or below this are cache breakpoint granularity noise. */
private const val NOISE_FLOOR_TOKENS = 1024

/** A counted cache miss on a single assistant message. */
data class CacheMiss(
    /** Prompt tokens that were in the previous turn's prompt but not read from cache. */
    val missedTokens: Long,
    /** Extra dollars paid vs. a full cache hit; 0 when pricing is unknown. */
    val missedCost: Double,
    /** Milliseconds since the previous request (which last refreshed the cache). */
    val idleMs: Long,
    /** True when the model changed relative to the previous request. */
    val modelChanged: Boolean,
)

data class CacheWasteTotals(
    val missedTokens: Long = 0,
    val missedCost: Double = 0.0,
    /** Number of counted misses (turns above the noise floor). */
    val missCount: Int = 0,
)

/** Minimal pricing lookup, satisfied by ModelRegistry. Cost is $/million tokens. */
fun interface ModelPriceSource {
    fun find(
        provider: String,
        modelId: String,
    ): ModelPrice?
}

data class ModelPrice(
    val cacheRead: Double,
)

/** The last request seen by the scan; everything in its prompt should be cached. */
private data class PreviousRequest(
    val promptTokens: Long,
    val modelKey: String,
    val timestamp: Long,
    /**
     * Sticky: some earlier request in this scan segment reported cache activity.
     * Distinguishes a total miss on a cache-read-only provider (OpenAI-style,
     * writes unreported) from a provider that never reports caching at all.
     */
    val reportedCache: Boolean,
)

/**
 * Compute the cache miss for one assistant message relative to the previous
 * request. Returns null when nothing is counted: first turn, after a reset,
 * no cache activity ever reported (provider without cache support), or miss
 * below the noise floor.
 */
private fun detectMiss(
    prev: PreviousRequest?,
    message: AssistantMessage,
    models: ModelPriceSource,
): CacheMiss? {
    val usage = message.usage
    val promptTokens = usage.input + usage.cacheRead + usage.cacheWrite
    // A zero-cache turn only counts when cache activity was reported before:
    // on cache-read-only providers that is a total miss, while on providers
    // that never report caching it means nothing.
    if (prev == null || promptTokens <= 0 || (usage.cacheRead + usage.cacheWrite == 0L && !prev.reportedCache)) {
        return null
    }

    val missedTokens = min(prev.promptTokens, promptTokens) - usage.cacheRead
    if (missedTokens <= NOISE_FLOOR_TOKENS) return null

    // Extra cost = missed tokens billed at the actual paid rate (input/cacheWrite,
    // incl. write premium) instead of the cache-read rate. Missed tokens can only
    // land in the input or cacheWrite buckets, so the paid rate comes straight
    // from this message's own cost breakdown.
    val paidTokens = usage.input + usage.cacheWrite
    val paidPerToken = if (paidTokens > 0) (usage.cost.input + usage.cost.cacheWrite) / paidTokens else 0.0
    val readPerToken =
        if (usage.cacheRead > 0) {
            usage.cost.cacheRead / usage.cacheRead
        } else {
            (models.find(message.provider, message.model)?.cacheRead ?: 0.0) / 1_000_000
        }

    return CacheMiss(
        missedTokens = missedTokens,
        missedCost = missedTokens * max(0.0, paidPerToken - readPerToken),
        idleMs = max(0L, message.timestamp - prev.timestamp),
        modelChanged = "${message.provider}/${message.model}" != prev.modelKey,
    )
}

private fun asPreviousRequest(
    message: AssistantMessage,
    reportedCache: Boolean,
): PreviousRequest? {
    val usage = message.usage
    val promptTokens = usage.input + usage.cacheRead + usage.cacheWrite
    if (promptTokens <= 0) return null
    return PreviousRequest(
        promptTokens = promptTokens,
        modelKey = "${message.provider}/${message.model}",
        timestamp = message.timestamp,
        reportedCache = reportedCache || usage.cacheRead + usage.cacheWrite > 0,
    )
}

private data class ScanResult(
    val prev: PreviousRequest?,
    val totals: CacheWasteTotals,
    val misses: Map<AssistantMessage, CacheMiss>,
)

private fun scan(
    entries: List<SessionEntry>,
    models: ModelPriceSource,
): ScanResult {
    var prev: PreviousRequest? = null
    var missedTokens = 0L
    var missedCost = 0.0
    var missCount = 0
    val misses = linkedMapOf<AssistantMessage, CacheMiss>()

    for (entry in entries) {
        if (entry is CompactionEntry || entry is BranchSummaryEntry) {
            // The context legitimately changed; the next turn's prompt is new content,
            // not re-billed content. Model switches are NOT exempt: they re-bill the
            // full prompt and should be counted.
            prev = null
            continue
        }
        if (entry is SessionMessageEntry) {
            val message = entry.message
            if (message is AssistantMessage) {
                val miss = detectMiss(prev, message, models)
                if (miss != null) {
                    missedTokens += miss.missedTokens
                    missedCost += miss.missedCost
                    missCount += 1
                    misses[message] = miss
                }
                prev = asPreviousRequest(message, prev?.reportedCache ?: false) ?: prev
            }
        }
    }
    return ScanResult(
        prev = prev,
        totals = CacheWasteTotals(missedTokens, missedCost, missCount),
        misses = misses,
    )
}

/**
 * Cumulative cache waste across a session: prompt tokens that should have been
 * cache reads (they were in the previous turn's prompt) but were re-billed.
 */
fun computeCacheWaste(
    entries: List<SessionEntry>,
    models: ModelPriceSource,
): CacheWasteTotals = scan(entries, models).totals

/**
 * All counted cache misses across a session, keyed by the assistant message
 * that paid for them. Used to re-derive transcript notices when rebuilding the
 * chat from entries (resume, post-compaction rebuild).
 */
fun collectCacheMisses(
    entries: List<SessionEntry>,
    models: ModelPriceSource,
): Map<AssistantMessage, CacheMiss> = scan(entries, models).misses

/**
 * Detect a cache miss on a just-completed assistant message.
 * `entries` must not yet contain `message` (message_end fires before persistence).
 */
fun detectCacheMiss(
    entries: List<SessionEntry>,
    message: AssistantMessage,
    models: ModelPriceSource,
): CacheMiss? = detectMiss(scan(entries, models).prev, message, models)
