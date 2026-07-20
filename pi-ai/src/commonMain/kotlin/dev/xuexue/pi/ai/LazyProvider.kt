@file:Suppress("TooGenericExceptionCaught")

package dev.xuexue.pi.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider wrapper with Pi's lazy API semantics: the implementation is loaded
 * on the first request, concurrent first requests share one load, and the
 * resolved implementation is reused for the lifetime of this registration.
 */
class LazyPiProvider(
    override val id: ProviderId,
    override val name: String,
    override val supportedApis: Set<Api>,
    private val load: suspend () -> PiProvider,
) : PiProvider {
    private val mutex = Mutex()
    private var resolved: PiProvider? = null
    private var loadFailure: Throwable? = null

    override suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        lazyProviderStream(model) {
            resolve().stream(model, context, options)
        }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        lazyProviderStream(model) {
            resolve().streamSimple(model, context, options)
        }

    private suspend fun resolve(): PiProvider =
        mutex.withLock {
            resolved
                ?: loadFailure?.let { throw it }
                ?: runCatching { load() }
                    .onSuccess { provider ->
                        check(provider.id == id) {
                            "Lazy provider $id loaded implementation ${provider.id}"
                        }
                        check(supportedApis.intersect(provider.supportedApis).isNotEmpty()) {
                            "Lazy provider $id loaded without a supported API"
                        }
                    }.onFailure { error -> loadFailure = error }
                    .getOrThrow()
                    .also { provider -> resolved = provider }
        }
}

/** Lazy image-generation provider with the same load/cache rules. */
class LazyPiImageProvider(
    override val id: ImagesProviderId,
    override val name: String,
    override val supportedApis: Set<ImagesApi>,
    private val load: suspend () -> PiImageProvider,
) : PiImageProvider {
    private val mutex = Mutex()
    private var resolved: PiImageProvider? = null
    private var loadFailure: Throwable? = null

    override suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages = resolve().generateImages(model, context, options)

    private suspend fun resolve(): PiImageProvider =
        mutex.withLock {
            resolved
                ?: loadFailure?.let { throw it }
                ?: runCatching { load() }
                    .onSuccess { provider ->
                        check(provider.id == id) {
                            "Lazy image provider $id loaded implementation ${provider.id}"
                        }
                        check(supportedApis.intersect(provider.supportedApis).isNotEmpty()) {
                            "Lazy image provider $id loaded without a supported API"
                        }
                    }.onFailure { error -> loadFailure = error }
                    .getOrThrow()
                    .also { provider -> resolved = provider }
        }
}

/**
 * Keeps provider setup failures inside the returned stream, matching Pi's
 * lazyStream contract instead of throwing before callers can observe an event.
 */
private suspend fun lazyProviderStream(
    model: Model,
    setup: suspend () -> AssistantMessageEventStream,
): AssistantMessageEventStream {
    val outer = createAssistantMessageEventStream()
    val scope = CoroutineScope(currentCoroutineContext())
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            setup().asFlow().collect(outer::push)
            outer.end()
        } catch (error: CancellationException) {
            outer.push(lazyProviderError(model, error.message ?: "Request was aborted", aborted = true))
        } catch (error: Throwable) {
            outer.push(lazyProviderError(model, error.message ?: error.toString(), aborted = false))
        }
    }
    return outer
}

private fun lazyProviderError(
    model: Model,
    message: String,
    aborted: Boolean,
): AssistantMessageEvent.Error {
    val stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR
    val assistant =
        AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = emptyUsage(),
            stopReason = stopReason,
            errorMessage = message,
            timestamp =
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds(),
        )
    return AssistantMessageEvent.Error(
        reason = if (aborted) FailureStopReason.ABORTED else FailureStopReason.ERROR,
        error = assistant,
    )
}
