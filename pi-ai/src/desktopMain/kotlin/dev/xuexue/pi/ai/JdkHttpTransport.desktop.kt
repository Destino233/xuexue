@file:Suppress("CyclomaticComplexMethod", "InjectDispatcher")

package dev.xuexue.pi.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun defaultHttpTransport(): HttpTransport = JdkHttpTransport()

/** Parse a proxy URL into an HTTP proxy address, mirroring Pi's applyHttpProxySettings semantics. */
internal fun parseProxyAddress(value: String): InetSocketAddress? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        val uri = URI.create(if ("://" in trimmed) trimmed else "http://$trimmed")
        InetSocketAddress(uri.host, uri.port.takeIf { it > 0 } ?: 80)
    }.getOrNull()
}

/** Resolve the env proxy for a URI, mirroring Pi's applyHttpProxySettings semantics. */
internal fun envProxySelector(): ProxySelector? {
    val proxyUrl =
        System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: System.getenv("HTTP_PROXY")
            ?: System.getenv("http_proxy") ?: return null
    val address = parseProxyAddress(proxyUrl) ?: return null
    return object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, address as SocketAddress))

        override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: java.io.IOException?,
        ) = Unit
    }
}

private class JdkHttpTransport : HttpTransport {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .apply { envProxySelector()?.let(::proxy) }
            .build()

    override suspend fun execute(request: dev.xuexue.pi.ai.HttpRequest): dev.xuexue.pi.ai.HttpResponse {
        require(request.method == "POST" || request.method == "GET") { "Unsupported HTTP method: ${request.method}" }
        val builder = HttpRequest.newBuilder(URI.create(request.url))
        request.timeoutMs?.takeIf { it > 0 }?.let { builder.timeout(Duration.ofMillis(it)) }
        request.headers.forEach { (name, value) ->
            // java.net.http synthesizes Host from the URI and rejects an explicit
            // restricted Host header; SigV4 still signs the same canonical host.
            if (!name.equals("host", ignoreCase = true)) builder.header(name, value)
        }
        if (request.method == "GET") {
            builder.GET()
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(request.body.orEmpty()))
        }
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val signalSubscription = request.signal?.onAbort { future.cancel(true) }
        val response =
            try {
                future.await()
            } finally {
                signalSubscription?.dispose()
            }
        val body = AtomicReference<InputStream?>()
        val bodySubscription = request.signal?.onAbort { body.get()?.close() }
        val eventStream =
            response
                .headers()
                .firstValue("content-type")
                .orElse("")
                .contains("amazon.eventstream", ignoreCase = true)
        val lines: Flow<String> =
            if (eventStream) {
                emptyFlow()
            } else {
                flow {
                    try {
                        response.body().use { input ->
                            body.set(input)
                            input.bufferedReader(Charsets.UTF_8).useLines { sequence ->
                                sequence.forEach { line -> emit(line) }
                            }
                        }
                    } finally {
                        body.set(null)
                        bodySubscription?.dispose()
                    }
                }.flowOn(Dispatchers.IO)
            }
        val bytes: Flow<ByteArray>? =
            if (eventStream) {
                flow {
                    try {
                        response.body().use { input ->
                            body.set(input)
                            val buffer = ByteArray(DEFAULT_BINARY_CHUNK_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > 0) emit(buffer.copyOf(count))
                            }
                        }
                    } finally {
                        body.set(null)
                        bodySubscription?.dispose()
                    }
                }.flowOn(Dispatchers.IO)
            } else {
                null
            }
        return dev.xuexue.pi.ai.HttpResponse(
            status = response.statusCode(),
            headers = response.headers().map().mapValues { (_, values) -> values.joinToString(",") },
            lines = lines,
            bytes = bytes,
        )
    }

    private companion object {
        const val DEFAULT_BINARY_CHUNK_SIZE = 16 * 1024
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error != null) {
                continuation.resumeWithException(error)
            } else {
                continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
