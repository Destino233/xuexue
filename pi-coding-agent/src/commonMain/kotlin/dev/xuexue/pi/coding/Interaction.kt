package dev.xuexue.pi.coding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class InteractionKind {
    CONFIRM,
    SELECT,
    INPUT,
    EDITOR,
    NOTIFY,
}

enum class InteractionSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

sealed interface InteractionRequest {
    val id: String
    val title: String
    val message: String
    val kind: InteractionKind

    data class Confirm(
        override val id: String,
        override val title: String,
        override val message: String,
        val defaultValue: Boolean = false,
        val confirmLabel: String = "Confirm",
        val cancelLabel: String = "Cancel",
    ) : InteractionRequest {
        override val kind: InteractionKind = InteractionKind.CONFIRM
    }

    data class Select(
        override val id: String,
        override val title: String,
        override val message: String,
        val options: List<String>,
        val selectedIndex: Int = 0,
        val allowCancel: Boolean = true,
    ) : InteractionRequest {
        init {
            require(options.isNotEmpty()) { "Select interaction requires at least one option" }
            require(selectedIndex in options.indices) { "Select interaction has an invalid selectedIndex" }
        }

        override val kind: InteractionKind = InteractionKind.SELECT
    }

    data class Input(
        override val id: String,
        override val title: String,
        override val message: String,
        val initialValue: String = "",
        val placeholder: String? = null,
        val secret: Boolean = false,
        val multiline: Boolean = false,
    ) : InteractionRequest {
        override val kind: InteractionKind = InteractionKind.INPUT
    }

    data class Editor(
        override val id: String,
        override val title: String,
        override val message: String,
        val initialValue: String = "",
        val language: String? = null,
    ) : InteractionRequest {
        override val kind: InteractionKind = InteractionKind.EDITOR
    }

    data class Notify(
        override val id: String,
        override val title: String,
        override val message: String,
        val severity: InteractionSeverity = InteractionSeverity.INFO,
        val durationMs: Long? = null,
    ) : InteractionRequest {
        override val kind: InteractionKind = InteractionKind.NOTIFY
    }
}

sealed interface InteractionResponse {
    val requestId: String

    data class Confirm(
        override val requestId: String,
        val accepted: Boolean,
    ) : InteractionResponse

    data class Select(
        override val requestId: String,
        val selectedIndex: Int,
    ) : InteractionResponse

    data class Input(
        override val requestId: String,
        val value: String,
    ) : InteractionResponse

    data class Editor(
        override val requestId: String,
        val value: String,
    ) : InteractionResponse

    data class Cancelled(
        override val requestId: String,
        val reason: String = "cancelled",
    ) : InteractionResponse
}

/** Host-neutral boundary for actions that need a user or an external UI. */
interface InteractionPort {
    suspend fun request(request: InteractionRequest): InteractionResponse

    suspend fun notify(request: InteractionRequest.Notify)
}

object NoOpInteractionPort : InteractionPort {
    override suspend fun request(request: InteractionRequest): InteractionResponse = cancelled(request.id)

    override suspend fun notify(request: InteractionRequest.Notify) = Unit

    private const val NO_INTERACTION_HOST = "no interaction host"

    private fun cancelled(id: String): InteractionResponse = InteractionResponse.Cancelled(id, NO_INTERACTION_HOST)
}

/**
 * Multiplexes pending requests for Compose, RPC, and tests. A request remains visible until its typed response
 * is supplied, and cancellation always releases the suspended caller.
 */
class InteractionRequestBroker : InteractionPort {
    private val lock = Any()
    private val pendingRequests = MutableStateFlow<List<InteractionRequest>>(emptyList())
    private val pending = linkedMapOf<String, PendingInteraction>()
    private val mutableRequestEvents = MutableSharedFlow<InteractionRequest>(replay = 1, extraBufferCapacity = 32)
    private val mutableNotifications = MutableSharedFlow<InteractionRequest.Notify>(extraBufferCapacity = 32)

    val requests: StateFlow<List<InteractionRequest>> = pendingRequests.asStateFlow()
    val requestEvents: SharedFlow<InteractionRequest> = mutableRequestEvents.asSharedFlow()
    val notifications: SharedFlow<InteractionRequest.Notify> = mutableNotifications.asSharedFlow()

    override suspend fun request(request: InteractionRequest): InteractionResponse {
        require(request !is InteractionRequest.Notify) {
            "Notifications are fire-and-forget; use InteractionPort.notify()"
        }
        val deferred = CompletableDeferred<InteractionResponse>()
        synchronized(lock) {
            check(request.id !in pending) { "Duplicate interaction request id: ${request.id}" }
            pending[request.id] = PendingInteraction(request, deferred)
            pendingRequests.update { it + request }
            mutableRequestEvents.tryEmit(request)
        }
        return try {
            deferred.await()
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                pendingRequests.update { current -> current.filterNot { it.id == request.id } }
            }
        }
    }

    override suspend fun notify(request: InteractionRequest.Notify) {
        mutableNotifications.emit(request)
    }

    /** Completes a pending request if the response matches its request type. */
    fun respond(response: InteractionResponse): Boolean {
        val target = synchronized(lock) { pending[response.requestId] }
        return target?.let { pendingInteraction ->
            pendingInteraction
                .takeIf { isCompatible(it.request, response) }
                ?.deferred
                ?.complete(response)
                ?: false
        } ?: false
    }

    fun cancel(
        requestId: String,
        reason: String = "cancelled",
    ): Boolean = respond(InteractionResponse.Cancelled(requestId, reason))

    fun cancelAll(reason: String = "cancelled"): Int {
        val ids = synchronized(lock) { pending.keys.toList() }
        ids.forEach { cancel(it, reason) }
        return ids.size
    }

    private fun isCompatible(
        request: InteractionRequest,
        response: InteractionResponse,
    ): Boolean =
        response is InteractionResponse.Cancelled ||
            when (request) {
                is InteractionRequest.Confirm -> {
                    response is InteractionResponse.Confirm
                }

                is InteractionRequest.Select -> {
                    response is InteractionResponse.Select &&
                        response.selectedIndex in request.options.indices
                }

                is InteractionRequest.Input -> {
                    response is InteractionResponse.Input
                }

                is InteractionRequest.Editor -> {
                    response is InteractionResponse.Editor
                }

                is InteractionRequest.Notify -> {
                    false
                }
            }

    private data class PendingInteraction(
        val request: InteractionRequest,
        val deferred: CompletableDeferred<InteractionResponse>,
    )
}
