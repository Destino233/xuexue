package dev.xuexue.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

typealias Api = String
typealias ProviderId = String
typealias ImagesApi = String
typealias ImagesProviderId = String

@Serializable
enum class ThinkingLevel {
    @SerialName("minimal")
    MINIMAL,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("xhigh")
    XHIGH,

    @SerialName("max")
    MAX,
}

@Serializable
enum class ModelThinkingLevel {
    @SerialName("off")
    OFF,

    @SerialName("minimal")
    MINIMAL,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("xhigh")
    XHIGH,

    @SerialName("max")
    MAX,
}

@Serializable
enum class CacheRetention {
    @SerialName("none")
    NONE,

    @SerialName("short")
    SHORT,

    @SerialName("long")
    LONG,
}

@Serializable
enum class Transport {
    @SerialName("sse")
    SSE,

    @SerialName("websocket")
    WEBSOCKET,

    @SerialName("websocket-cached")
    WEBSOCKET_CACHED,

    @SerialName("auto")
    AUTO,
}

typealias ProviderEnv = Map<String, String>
typealias ProviderHeaders = Map<String, String?>

data class ProviderResponse(
    val status: Int,
    val headers: Map<String, String>,
)

typealias PayloadTransform = suspend (payload: Any?, model: Model) -> Any?
typealias ResponseObserver = suspend (response: ProviderResponse, model: Model) -> Unit

data class StreamOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val transport: Transport? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val onPayload: PayloadTransform? = null,
    val onResponse: ResponseObserver? = null,
    val headers: ProviderHeaders? = null,
    val timeoutMs: Long? = null,
    val websocketConnectTimeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonObject? = null,
    val env: ProviderEnv? = null,
)

data class SimpleStreamOptions(
    val stream: StreamOptions = StreamOptions(),
    val reasoning: ThinkingLevel? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
)

data class ThinkingBudgets(
    val minimal: Int? = null,
    val low: Int? = null,
    val medium: Int? = null,
    val high: Int? = null,
)

interface AbortSignal {
    val isAborted: Boolean
    val reason: Any?

    fun onAbort(listener: () -> Unit): AbortSubscription
}

fun interface AbortSubscription {
    fun dispose()
}

@Serializable
data class ModelCostRates(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

@Serializable
data class ModelCostTier(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val inputTokensAbove: Long,
)

@Serializable
data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val tiers: List<ModelCostTier>? = null,
)

@Serializable
data class Model(
    val id: String,
    val name: String,
    val api: Api,
    val provider: ProviderId,
    val baseUrl: String,
    val reasoning: Boolean,
    val thinkingLevelMap: Map<ModelThinkingLevel, String?>? = null,
    val input: List<ModelInput>,
    val cost: ModelCost,
    val contextWindow: Long,
    val maxTokens: Long,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

@Serializable
enum class ModelInput {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,
}

@Serializable
data class ImagesModel(
    val id: String,
    val name: String,
    val api: ImagesApi,
    val provider: ImagesProviderId,
    val baseUrl: String,
    val input: List<ModelInput>,
    val output: List<ModelInput>,
    val cost: ModelCost,
    val headers: Map<String, String>? = null,
)
