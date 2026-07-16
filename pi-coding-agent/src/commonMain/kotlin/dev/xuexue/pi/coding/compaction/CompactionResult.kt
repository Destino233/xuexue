package dev.xuexue.pi.coding.compaction

data class CompactionResult(
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Long,
    val estimatedTokensAfter: Long? = null,
    val details: CompactionDetails? = null,
)
