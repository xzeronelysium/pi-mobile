package dev.pi.mobile.backend

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The loopback TCP listener behind pi-ai's browser sign-in flows.
 *
 * The upstream flows call `startOAuthCallbackServer`, which uses `node:http.createServer`. A WebView
 * cannot listen on a socket, so the listener lives here and the JS shim
 * (`runtime/src/shims/node-http.mjs`) speaks to it over the bridge: `oauth.listen` binds, each
 * accepted browser request is delivered as an `oauth.request` event, and the shim's
 * `response.end()` returns through `respond`.
 *
 * Only one sign-in runs at a time, but listeners are keyed by the shim's server id so a cancelled
 * login cannot steal a later login's callback. Pure JVM — no Android types — so request parsing and
 * response writing are unit-tested off-device.
 */
class LoopbackListener(
  private val onRequest: (serverId: String, request: Request) -> Unit,
) : Closeable {
  data class Request(val id: Long, val method: String, val target: String, val headers: Map<String, String>)

  private class Connection(val socket: Socket) {
    fun close() { runCatching { socket.close() } }
  }

  private val servers = ConcurrentHashMap<String, ServerSocket>()
  private val threads = ConcurrentHashMap<String, Thread>()
  private val pending = ConcurrentHashMap<Long, Connection>()
  private val owners = ConcurrentHashMap<Long, String>()
  private val ids = AtomicLong()
  private val running = AtomicBoolean(true)

  /** Bind `host:port` (`0` picks a free port) and start accepting. Returns the bound port. */
  fun open(serverId: String, host: String, port: Int): Int {
    check(running.get()) { "Sign-in listener is closed" }
    check(!servers.containsKey(serverId)) { "Sign-in listener already open: $serverId" }
    val server = ServerSocket()
    server.reuseAddress = true
    server.bind(InetSocketAddress(InetAddress.getByName(host), port.coerceIn(0, 65535)))
    servers[serverId] = server
    val thread = Thread({ accept(serverId, server) }, "pi-oauth-$serverId").apply { isDaemon = true }
    threads[serverId] = thread
    thread.start()
    return server.localPort
  }

  /** Write the HTTP response the browser page sees and close that connection. */
  fun respond(requestId: Long, status: Int, contentType: String, body: String) {
    owners.remove(requestId)
    val connection = pending.remove(requestId) ?: return
    val bytes = body.toByteArray(Charsets.UTF_8)
    val head = buildString {
      append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
      append("Content-Type: ").append(contentType).append("\r\n")
      append("Content-Length: ").append(bytes.size).append("\r\n")
      append("Cache-Control: no-store\r\n")
      append("Connection: close\r\n\r\n")
    }
    runCatching {
      connection.socket.getOutputStream().apply {
        write(head.toByteArray(Charsets.US_ASCII))
        write(bytes)
        flush()
      }
    }
    connection.close()
  }

  /** Stop accepting and drop this listener's open connections. */
  fun close(serverId: String) {
    servers.remove(serverId)?.let { runCatching { it.close() } }
    threads.remove(serverId)
    dropConnections(serverId)
  }

  /** Drop delivered-but-unanswered requests, leaving the listener accepting. */
  fun dropConnections(serverId: String) {
    for (id in owners.keys.toList()) {
      if (owners[id] == serverId) {
        owners.remove(id)
        pending.remove(id)?.close()
      }
    }
  }

  override fun close() {
    running.set(false)
    servers.keys.toList().forEach(::close)
    for (id in pending.keys.toList()) {
      owners.remove(id)
      pending.remove(id)?.close()
    }
  }

  private fun accept(serverId: String, server: ServerSocket) {
    while (running.get() && !server.isClosed) {
      val socket = try {
        server.accept()
      } catch (error: SocketException) {
        return
      } catch (error: Exception) {
        continue
      }
      try {
        socket.soTimeout = READ_TIMEOUT_MS
        val parsed = read(socket)
        if (parsed == null) {
          socket.close()
          continue
        }
        val id = ids.incrementAndGet()
        pending[id] = Connection(socket)
        owners[id] = serverId
        onRequest(serverId, Request(id, parsed.method, parsed.target, parsed.headers))
      } catch (error: SocketTimeoutException) {
        respondDirect(socket, 408, "Request timed out.")
      } catch (error: Exception) {
        runCatching { socket.close() }
      }
    }
  }

  private fun respondDirect(socket: Socket, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    runCatching {
      socket.getOutputStream().apply {
        write("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        write(bytes)
        flush()
      }
    }
    runCatching { socket.close() }
  }

  private class Parsed(val method: String, val target: String, val headers: Map<String, String>)

  private fun read(socket: Socket): Parsed? {
    val input = socket.getInputStream()
    val head = readHead(input) ?: return null
    val lines = head.split("\r\n")
    val requestLine = lines.firstOrNull() ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) return null
    val headers = LinkedHashMap<String, String>()
    for (index in 1 until lines.size) {
      val line = lines[index]
      val separator = line.indexOf(':')
      if (separator <= 0) continue
      headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
    }
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length > 0) {
      var remaining = length.coerceAtMost(MAX_BODY_BYTES)
      val buffer = ByteArray(4096)
      while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        remaining -= read
      }
    }
    return Parsed(parts[0], parts[1], headers)
  }

  private fun readHead(input: InputStream): String? {
    val buffer = StringBuilder()
    while (buffer.length <= MAX_HEAD_BYTES) {
      val next = input.read()
      if (next < 0) return if (buffer.isEmpty()) null else buffer.toString().removeSuffix("\r\n\r\n")
      buffer.append(next.toChar())
      if (buffer.length >= 4 && buffer[buffer.length - 4] == '\r' && buffer[buffer.length - 3] == '\n' &&
        buffer[buffer.length - 2] == '\r' && buffer[buffer.length - 1] == '\n'
      ) {
        return buffer.substring(0, buffer.length - 4)
      }
    }
    return null
  }

  private fun reason(status: Int) = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    404 -> "Not Found"
    408 -> "Request Timeout"
    409 -> "Conflict"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    else -> "Status"
  }

  private companion object {
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_HEAD_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 1024 * 1024
  }
}
