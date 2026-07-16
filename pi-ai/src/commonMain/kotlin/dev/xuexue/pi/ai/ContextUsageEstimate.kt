package dev.xuexue.pi.ai

data class ContextUsageEstimate(
    val tokens: Long,
    val usageTokens: Long,
    val trailingTokens: Long,
    val lastUsageIndex: Int?,
)
