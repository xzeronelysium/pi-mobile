package dev.pi.mobile.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LoopbackListenerTest {
  /** Reads the whole HTTP response off the socket; the listener closes it after replying. */
  private fun browserCall(port: Int, request: String): CompletableFuture<String> {
    val response = CompletableFuture<String>()
    Thread {
      runCatching {
        Socket("127.0.0.1", port).use { socket ->
          socket.getOutputStream().apply { write(request.toByteArray()); flush() }
          response.complete(socket.getInputStream().readBytes().toString(Charsets.UTF_8))
        }
      }.onFailure(response::completeExceptionally)
    }.apply { isDaemon = true }.start()
    return response
  }

  @Test fun deliversTheBrowserCallbackAndReturnsTheHandlersResponse() {
    val received = CompletableFuture<LoopbackListener.Request>()
    val listener = LoopbackListener { _, request -> received.complete(request) }
    val port = listener.open("oauth-test", "127.0.0.1", 0)
    try {
      assertTrue(port in 1..65535)
      val response = browserCall(port, "GET /callback?code=abc&state=xyz HTTP/1.1\r\nHost: localhost\r\nAccept: text/html\r\n\r\n")
      val request = received.get(5, TimeUnit.SECONDS)
      assertEquals("GET", request.method)
      assertEquals("/callback?code=abc&state=xyz", request.target)
      assertEquals("localhost", request.headers["host"])
      // The shim answers only after the provider flow finished the code exchange, so the socket has
      // to stay open until then.
      assertTrue(response.isDone.not())
      listener.respond(request.id, 200, "text/html; charset=utf-8", "<html>Signed in.</html>")
      val text = response.get(5, TimeUnit.SECONDS)
      assertTrue(text.startsWith("HTTP/1.1 200 OK"))
      assertTrue(text.contains("Content-Type: text/html; charset=utf-8"))
      assertTrue(text.contains("Content-Length: 23"))
      assertTrue(text.contains("Connection: close"))
      assertTrue(text.endsWith("<html>Signed in.</html>"))
    } finally { listener.close() }
  }

  @Test fun countsBytesNotCharactersInTheResponseBody() {
    val received = CompletableFuture<LoopbackListener.Request>()
    val listener = LoopbackListener { _, request -> received.complete(request) }
    val port = listener.open("oauth-utf8", "127.0.0.1", 0)
    try {
      val response = browserCall(port, "GET /callback?code=1 HTTP/1.1\r\nHost: localhost\r\n\r\n")
      val request = received.get(5, TimeUnit.SECONDS)
      val body = "ok ✓ pi"
      listener.respond(request.id, 200, "text/html; charset=utf-8", body)
      val text = response.get(5, TimeUnit.SECONDS)
      assertTrue(text.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}"))
    } finally { listener.close() }
  }

  @Test fun aSecondListenerIdIsRejectedRatherThanShadowingTheFirst() {
    val listener = LoopbackListener { _, _ -> }
    val port = listener.open("oauth-dup", "127.0.0.1", 0)
    try {
      assertTrue(port > 0)
      try {
        listener.open("oauth-dup", "127.0.0.1", 0)
        throw AssertionError("Expected a duplicate listener id to be rejected")
      } catch (expected: IllegalStateException) {
        assertTrue(expected.message!!.contains("already open"))
      }
    } finally { listener.close() }
  }

  @Test fun closingAListenerStopsAcceptingNewConnections() {
    val listener = LoopbackListener { _, _ -> }
    val port = listener.open("oauth-close", "127.0.0.1", 0)
    listener.close("oauth-close")
    // A fresh bind on the same id is allowed again, which proves the first one was released.
    val second = listener.open("oauth-close", "127.0.0.1", 0)
    assertTrue(second > 0)
    listener.close()
  }

  @Test fun droppingConnectionsClosesTheBrowserAndLeavesTheListenerAccepting() {
    val requests = java.util.concurrent.LinkedBlockingQueue<LoopbackListener.Request>()
    val listener = LoopbackListener { _, request -> requests.put(request) }
    val port = listener.open("oauth-drop", "127.0.0.1", 0)
    try {
      val abandoned = browserCall(port, "GET /callback?code=stale HTTP/1.1\r\nHost: localhost\r\n\r\n")
      val stale = requests.poll(5, TimeUnit.SECONDS)
      assertNotNull(stale)
      listener.dropConnections("oauth-drop")
      // The browser's socket is closed instead of left hanging on a request nobody will answer.
      val text = abandoned.get(5, TimeUnit.SECONDS)
      assertEquals("", text)
      // Answering after the drop is a no-op rather than writing to a socket that is gone.
      listener.respond(stale.id, 200, "text/plain", "late")
      // The listener still accepts the next sign-in's callback.
      val fresh = browserCall(port, "GET /callback?code=fresh HTTP/1.1\r\nHost: localhost\r\n\r\n")
      val next = requests.poll(5, TimeUnit.SECONDS)
      assertNotNull(next)
      listener.respond(next.id, 200, "text/plain", "ok")
      assertTrue(fresh.get(5, TimeUnit.SECONDS).endsWith("ok"))
    } finally { listener.close() }
  }
}
