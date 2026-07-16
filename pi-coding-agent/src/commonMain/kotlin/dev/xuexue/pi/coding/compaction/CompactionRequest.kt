package dev.xuexue.pi.coding.compaction

import dev.xuexue.pi.agent.StreamFunction
import dev.xuexue.pi.ai.AbortSignal
import dev.xuexue.pi.ai.Model
import dev.xuexue.pi.ai.ModelThinkingLevel

data class CompactionRequest(
    val model: Model,
    val apiKey: String?,
    val headers: Map<String, String>? = null,
    val customInstructions: String? = null,
    val signal: AbortSignal? = null,
    val thinkingLevel: ModelThinkingLevel? = null,
    val streamFunction: StreamFunction,
    val env: Map<String, String>? = null,
)
