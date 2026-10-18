package dev.pi.mobile.backend

import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Provider HTTP, performed outside the WebView.
 *
 * pi-ai issues every provider request with `options?.fetch ?? globalThis.fetch`. In the WebView that
 * is the browser's own fetch, and the document origin is `https://pi.local`, which no provider
 * grants via CORS — so the request is refused before a model call, and the OAuth token exchange
 * fails the same way. The runtime hands those requests here instead (`runtime/src/fetch.mjs`), and
 * this class owns the socket:
 *
 * - the response head (status, headers) is reported before the body, so the JS response resolves as
 *   soon as the provider has answered;
 * - the body is streamed back in chunks, so server-sent events still arrive incrementally;
 * - a request can be cancelled by id from any thread, which releases the socket;
 * - the body is capped, and a capped body is reported as truncated rather than as a clean end;
 * - a connection-level failure is reported with a code, never with the caller's request headers.
 *
 * Blocking. [start] hands the exchange to [workers], so nothing here ever runs on the main thread.
 * The seam is [Connection]: production builds it from [java.net.HttpURLConnection] in [network],
 * tests inject a fake so the whole exchange is exercised without a socket.
 */
class HttpTransport(
    private val connect: (Request) -> Connection,
    private val workers: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pi-http").apply { isDaemon = true }
    },
) : Closeable {

    /** One request, as the bridge described it. [headers] are the caller's, before normalization. */
    class Request(
        method: String,
        val url: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray? = null,
        val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        val maxBytes: Long = DEFAULT_MAX_BYTES,
    ) {
        /** Normalized once, so every connection implementation sees the same shape. */
        val method: String = method.trim().uppercase()

        init {
            require(url.startsWith("http://") || url.startsWith("https://")) { "Not an HTTP request" }
            require(method.isNotBlank()) { "Method required" }
        }
    }

    /** The response head: what the provider answered, before any body byte is read. */
    class Response(val status: Int, val statusText: String, val headers: List<Pair<String, String>>)

    /**
     * The injectable seam. [response] sends the request; [read] streams the body. Both block, and
     * [close] is safe to call from another thread while [read] is in flight — that is what makes
     * cancellation work.
     */
    interface Connection : Closeable {
        fun response(): Response

        /** Copies body bytes into [into]; returns the count, or -1 at the end of the body. */
        fun read(into: ByteArray): Int
    }

    /** What the host forwards to JS. No event carries a request header. */
    sealed interface Event {
        data class Head(val status: Int, val statusText: String, val headers: List<Pair<String, String>>) : Event
        data class Chunk(val seq: Int, val bytes: ByteArray) : Event
        data class End(val seq: Int, val truncated: Boolean, val cancelled: Boolean) : Event
        data class Failure(val code: String, val message: String) : Event
    }

    private class Cancellation {
        @Volatile var cancelled = false

        @Volatile var connection: Connection? = null
    }

    private val active = ConcurrentHashMap<String, Cancellation>()

    /**
     * Begin one exchange under [requestId]. Returns false when that id is already in flight, which
     * is the only way a caller can be told its request was refused before anything was sent.
     */
    fun start(requestId: String, request: Request, onEvent: (Event) -> Unit): Boolean {
        val token = Cancellation()
        if (active.putIfAbsent(requestId, token) != null) return false
        workers.execute {
            try {
                exchange(request, token, onEvent)
            } finally {
                if (active[requestId] === token) active.remove(requestId)
            }
        }
        return true
    }

    /** Stop the request with [requestId]: the socket is released and the exchange ends cancelled. */
    fun cancel(requestId: String) {
        val token = active[requestId] ?: return
        token.cancelled = true
        token.connection?.let { connection -> runCatching { connection.close() } }
    }

    fun isActive(requestId: String): Boolean = active.containsKey(requestId)

    private fun exchange(request: Request, token: Cancellation, onEvent: (Event) -> Unit) {
        var connection: Connection? = null
        var seq = 0
        var delivered = 0L
        try {
            if (token.cancelled) {
                onEvent(Event.End(0, truncated = false, cancelled = true))
                return
            }
            val opened = connect(request)
            connection = opened
            token.connection = opened
            val response = opened.response()
            onEvent(Event.Head(response.status, response.statusText, response.headers))
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                if (token.cancelled) {
                    onEvent(Event.End(seq, truncated = false, cancelled = true))
                    return
                }
                val count = opened.read(buffer)
                if (count < 0) {
                    onEvent(Event.End(seq, truncated = false, cancelled = false))
                    return
                }
                if (count == 0) continue
                val room = request.maxBytes - delivered
                if (room <= 0L) {
                    onEvent(Event.End(seq, truncated = true, cancelled = false))
                    return
                }
                val keep = minOf(count.toLong(), room).toInt()
                onEvent(Event.Chunk(seq++, buffer.copyOf(keep)))
                delivered += keep
                if (keep < count) {
                    onEvent(Event.End(seq, truncated = true, cancelled = false))
                    return
                }
            }
        } catch (error: Exception) {
            // A cancelled read fails with whatever the released socket throws; that is a cancellation,
            // not a provider error.
            if (token.cancelled || error is InterruptedException) {
                onEvent(Event.End(seq, truncated = false, cancelled = true))
            } else {
                onEvent(Event.Failure(codeOf(error), scrub(error.message ?: error.javaClass.simpleName, request.headers)))
            }
        } finally {
            connection?.let { opened -> runCatching { opened.close() } }
        }
    }

    override fun close() {
        active.keys.forEach(::cancel)
        (workers as? ExecutorService)?.shutdownNow()
    }

    companion object {
        const val CHUNK_BYTES = 16 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 30_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 120_000
        /** A provider stream may run long, but never unbounded. */
        const val MAX_TIMEOUT_MILLIS = 600_000
        /** The largest request body the transport will send. */
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        const val MAX_MAX_BYTES = 128L * 1024 * 1024

        /**
         * Headers the connection owns. `accept-encoding` is dropped so the connection adds its own
         * and transparently decodes the body: forwarding a caller's value would hand us compressed
         * bytes that nothing downstream inflates.
         */
        private val MANAGED_REQUEST_HEADERS =
            setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding")

        /** Header names whose values are credentials, never to be echoed into a failure. */
        private val SECRET_HEADERS =
            setOf("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key", "x-goog-api-key", "x-amz-security-token")

        fun forwardHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
            headers.filterNot { (name, _) -> name.lowercase() in MANAGED_REQUEST_HEADERS }

        /** Remove any credential value [headers] carried from [message]; diagnosis stays readable. */
        fun scrub(message: String, headers: List<Pair<String, String>>): String {
            var result = message
            for ((name, value) in headers) {
                if (value.length < 8 || name.lowercase() !in SECRET_HEADERS) continue
                if (result.contains(value)) result = result.replace(value, "[redacted]")
            }
            return result
        }

        /** The real transport: one `HttpURLConnection` per request, no third-party dependency. */
        fun network(): HttpTransport = HttpTransport({ request -> UrlConnection(request) })
    }

    private fun codeOf(error: Exception): String = when (error) {
        is SocketTimeoutException -> "timeout"
        is UnknownHostException -> "dns"
        is ConnectException -> "connect"
        is NoRouteToHostException -> "unreachable"
        is SSLException -> "tls"
        is IOException -> "io"
        is IllegalArgumentException -> "invalid"
        else -> "unknown"
    }

    /** `HttpURLConnection`, configured from one [Request]. Blocking on the exchange's own thread. */
    private class UrlConnection(private val request: Request) : Connection {
        private val connection: HttpURLConnection = (URL(request.url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Unsupported protocol")
        private var stream: java.io.InputStream? = null

        override fun response(): Response {
            connection.requestMethod = request.method.uppercase()
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.instanceFollowRedirects = true
            forwardHeaders(request.headers).forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            } else {
                connection.connect()
            }
            val status = connection.responseCode
            // A 4xx/5xx body is on the error stream; without it pi-ai cannot report the provider's
            // own error text, which is the difference between "429: quota exceeded" and a blank.
            stream = if (status >= 400) connection.errorStream else connection.inputStream
            val headers = mutableListOf<Pair<String, String>>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null && !values.isNullOrEmpty()) headers += name to values.joinToString(", ")
            }
            return Response(status, connection.responseMessage ?: "", headers)
        }

        override fun read(into: ByteArray): Int = stream?.read(into) ?: -1

        override fun close() {
            runCatching { stream?.close() }
            runCatching { connection.disconnect() }
        }
    }
}
