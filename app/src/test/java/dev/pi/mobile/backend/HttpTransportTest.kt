package dev.pi.mobile.backend

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * The provider HTTP transport the WebView delegates to: request shape, ordered streaming, the body
 * cap, out-of-band cancellation, coded failures and — the one that matters most — the promise that
 * no credential-bearing request header is ever echoed into an event.
 *
 * Everything here runs off-device against an injected connection seam; no test opens a socket.
 */
class HttpTransportTest {

    /** Runs the transport inline so each exchange is deterministic. */
    private val direct = Executor { runnable -> runnable.run() }

    private class Recorder {
        val requests = mutableListOf<HttpTransport.Request>()
        val events = ConcurrentLinkedQueue<HttpTransport.Event>()
        fun sink(): (HttpTransport.Event) -> Unit = { events.add(it) }
        fun list(): List<HttpTransport.Event> = events.toList()
    }

    private class FakeConnection(
        private val body: ByteArray,
        private val status: Int = 200,
        private val statusText: String = "OK",
        private val responseHeaders: List<Pair<String, String>> = listOf("content-type" to "text/event-stream"),
        private val onRead: ((Int) -> Unit)? = null,
        private val gate: CountDownLatch? = null,
    ) : HttpTransport.Connection {
        private val firstRead = CountDownLatch(1)
        var closed = false
            private set
        private var offset = 0
        private var reads = 0

        override fun response() = HttpTransport.Response(status, statusText, responseHeaders)

        /** Blocks until the transport is inside a read, so a cancel provably lands mid-flight. */
        fun awaitFirstRead() = firstRead.await(5, TimeUnit.SECONDS)

        override fun read(into: ByteArray): Int {
            firstRead.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (closed) throw IOException("Connection closed")
            if (offset >= body.size) return -1
            reads += 1
            val count = minOf(into.size, body.size - offset)
            body.copyInto(into, 0, offset, offset + count)
            offset += count
            onRead?.invoke(reads)
            return count
        }

        override fun close() {
            closed = true
            gate?.countDown()
        }
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test fun forwardsTheRequestShapeToTheConnection() {
        val recorder = Recorder()
        val connection = FakeConnection(bytes("body"))
        val transport = HttpTransport({ request -> recorder.requests.add(request); connection }, direct)
        assertTrue(transport.start("r1", HttpTransport.Request(
            method = "post",
            url = "https://api.anthropic.com/v1/messages",
            headers = listOf("authorization" to "Bearer sk-ant-secret", "content-type" to "application/json"),
            body = bytes("{\"model\":\"m\"}"),
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 9_000,
            maxBytes = 1_024,
        ), recorder.sink()))

        val request = recorder.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals(listOf("authorization" to "Bearer sk-ant-secret", "content-type" to "application/json"), request.headers)
        assertEquals("{\"model\":\"m\"}", String(request.body!!, Charsets.UTF_8))
        assertEquals(5_000, request.connectTimeoutMillis)
        assertEquals(9_000, request.readTimeoutMillis)
        assertEquals(1_024L, request.maxBytes)
    }

    @Test fun streamsHeadThenChunksThenEndInOrder() {
        val recorder = Recorder()
        // Two reads: one full chunk and a short tail, so the boundary itself is exercised.
        val payload = "a".repeat(HttpTransport.CHUNK_BYTES) + "b".repeat(10)
        val transport = HttpTransport({ FakeConnection(bytes(payload)) }, direct)
        transport.start("r1", HttpTransport.Request("POST", "https://api.openai.com/v1/responses"), recorder.sink())

        val events = recorder.list()
        assertTrue(events.first() is HttpTransport.Event.Head)
        val head = events.first() as HttpTransport.Event.Head
        assertEquals(200, head.status)
        assertEquals("text/event-stream", head.headers.first { it.first == "content-type" }.second)
        assertTrue(events.last() is HttpTransport.Event.End)
        assertFalse((events.last() as HttpTransport.Event.End).truncated)

        val chunks = events.filterIsInstance<HttpTransport.Event.Chunk>()
        assertEquals(listOf(0, 1), chunks.map { it.seq })
        assertEquals(listOf(HttpTransport.CHUNK_BYTES, 10), chunks.map { it.bytes.size })
        assertEquals(payload, chunks.joinToString("") { String(it.bytes, Charsets.UTF_8) })
        assertEquals((events.last() as HttpTransport.Event.End).seq, chunks.size)
    }

    @Test fun capsTheBodyAndReportsTruncation() {
        val recorder = Recorder()
        val transport = HttpTransport({ FakeConnection(bytes("x".repeat(500))) }, direct)
        transport.start("r1", HttpTransport.Request("GET", "https://api.openai.com/v1/models", maxBytes = 100), recorder.sink())

        val delivered = recorder.list().filterIsInstance<HttpTransport.Event.Chunk>().sumOf { it.bytes.size }
        assertEquals(100, delivered)
        val end = recorder.list().last() as HttpTransport.Event.End
        assertTrue(end.truncated)
        assertFalse(end.cancelled)
    }

    @Test(timeout = 10_000) fun cancelsARunningRequestByItsId() {
        val recorder = Recorder()
        val gate = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pi-http-test").apply { isDaemon = true } }
        try {
            val connection = FakeConnection(bytes("stream"), gate = gate)
            val transport = HttpTransport({ connection }, workers)
            assertTrue(transport.start("r1", HttpTransport.Request("POST", "https://api.openai.com/v1/responses"), recorder.sink()))
            // The first chunk proves the exchange is mid-flight and blocked on the next read.
            assertTrue(connection.awaitFirstRead())
            transport.cancel("r1")
            val deadline = System.nanoTime() + 5_000_000_000
            while (transport.isActive("r1") && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(transport.isActive("r1"))
            val end = recorder.list().last() as HttpTransport.Event.End
            assertTrue("cancel must be reported as cancelled", end.cancelled)
            assertFalse(end.truncated)
            assertTrue("the socket must be released", connection.closed)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun mapsConnectionFailuresToCodedEvents() {
        val recorder = Recorder()
        val transport = HttpTransport({ throw SocketTimeoutException("Read timed out") }, direct)
        transport.start("r1", HttpTransport.Request("POST", "https://api.openai.com/v1/responses"), recorder.sink())
        val failure = recorder.list().single() as HttpTransport.Event.Failure
        assertEquals("timeout", failure.code)
        assertEquals("Read timed out", failure.message)

        val refused = Recorder()
        val second = HttpTransport({ throw IOException("Connection refused") }, direct)
        second.start("r1", HttpTransport.Request("POST", "https://api.openai.com/v1/responses"), refused.sink())
        assertEquals("io", (refused.list().single() as HttpTransport.Event.Failure).code)
    }

    @Test fun neverExposesCredentialHeadersInAnyEvent() {
        val secret = "sk-ant-api03-super-secret-value"
        val recorder = Recorder()
        val transport = HttpTransport({ FakeConnection(bytes("event: ok\n\n")) }, direct)
        transport.start("r1", HttpTransport.Request(
            "POST",
            "https://api.anthropic.com/v1/messages",
            headers = listOf("authorization" to "Bearer $secret", "x-api-key" to secret, "content-type" to "application/json"),
        ), recorder.sink())

        val rendered = recorder.list().joinToString("\n") { event ->
            when (event) {
                is HttpTransport.Event.Head -> "head ${event.status} ${event.statusText} ${event.headers}"
                is HttpTransport.Event.Chunk -> "chunk ${event.seq} ${String(event.bytes, Charsets.UTF_8)}"
                is HttpTransport.Event.End -> "end ${event.seq} ${event.truncated} ${event.cancelled}"
                is HttpTransport.Event.Failure -> "failure ${event.code} ${event.message}"
            }
        }
        assertEquals(false, rendered.contains(secret))
        assertEquals(false, rendered.lowercase().contains("authorization"))
        assertEquals(false, rendered.lowercase().contains("x-api-key"))
    }

    @Test fun scrubsCredentialValuesOutOfFailureText() {
        val secret = "sk-ant-api03-super-secret-value"
        val headers = listOf("Authorization" to "Bearer $secret", "content-type" to "application/json")
        val scrubbed = HttpTransport.scrub("Handshake failed: Authorization: Bearer $secret was rejected", headers)
        assertEquals(false, scrubbed.contains(secret))
        assertEquals(true, scrubbed.contains("[redacted]"))
        // A non-credential header value is not a secret and stays readable for diagnosis.
        assertEquals("content-type application/json", HttpTransport.scrub("content-type application/json", headers))
    }

    @Test fun refusesADuplicateRequestId() {
        val recorder = Recorder()
        val gate = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pi-http-test").apply { isDaemon = true } }
        try {
            val connection = FakeConnection(bytes("x"), gate = gate)
            val transport = HttpTransport({ connection }, workers)
            val request = HttpTransport.Request("GET", "https://api.openai.com/v1/models")
            assertTrue(transport.start("r1", request, recorder.sink()))
            // The first exchange is still running, so its id is still owned.
            assertTrue(connection.awaitFirstRead())
            assertFalse(transport.start("r1", request, recorder.sink()))
            transport.cancel("r1")
            val deadline = System.nanoTime() + 5_000_000_000
            while (transport.isActive("r1") && System.nanoTime() < deadline) Thread.sleep(5)
            // Once it is done the id is free again.
            assertTrue(transport.start("r1", request, recorder.sink()))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun doesNotForwardHeadersTheConnectionOwns() {
        val forwarded = HttpTransport.forwardHeaders(listOf(
            "Host" to "api.openai.com",
            "Content-Length" to "42",
            "accept-encoding" to "gzip",
            "Connection" to "keep-alive",
            "content-type" to "application/json",
            "authorization" to "Bearer sk-secret",
        ))
        assertEquals(listOf("content-type" to "application/json", "authorization" to "Bearer sk-secret"), forwarded)
    }
}
