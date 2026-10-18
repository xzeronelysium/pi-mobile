package dev.pi.mobile.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP surface, projected from what the host actually reported. A server is never shown as
 * connected because it was configured, and a failure keeps its own reason.
 */
class McpUiReducerTest {
  private fun frame(servers: String, errors: String? = null): JSONObject {
    val errorField = if (errors == null) "" else ",\"errors\":[$errors]"
    return JSONObject("{\"servers\":[$servers]$errorField}")
  }

  private val connected =
    """{"id":"echo","name":"Echo","url":"https://mcp.example.com/rpc","state":"connected","serverName":"echo-server","serverVersion":"1.0.0","tools":[{"name":"mcp__echo__echo","tool":"echo","description":"Echo text back"}],"resources":[{"uri":"file:///notes","name":"notes"}]}"""
  private val failed = """{"id":"dead","name":"Dead","url":"http://127.0.0.1:9/mcp","plaintext":true,"state":"failed","error":"Failed to connect"}"""
  private val configured = """{"id":"later","name":"Later","url":"https://later.example.com/rpc","state":"disconnected"}"""

  @Test
  fun aConnectedServerReportsItsOwnToolListAndItsOwnIdentity() {
    val state = McpUiReducer.fromValue(frame(connected))
    val server = state.servers.first { it.id == "echo" }
    assertEquals(McpConnectionState.Connected, server.state)
    assertEquals("echo-server", server.serverName)
    assertEquals("1.0.0", server.serverVersion)
    assertEquals(listOf("mcp__echo__echo"), server.tools.map { it.name })
    assertEquals("echo", server.tools.single().tool)
    assertEquals(listOf("notes"), server.resources.map { it.name })
    assertEquals(1, state.toolCount)
    assertEquals(1, state.connectedCount)
  }

  @Test
  fun aFailedServerKeepsItsReasonAndOffersNoTools() {
    val state = McpUiReducer.fromValue(frame(failed))
    val server = state.servers.first { it.id == "dead" }
    assertEquals(McpConnectionState.Failed, server.state)
    assertEquals("Failed to connect", server.error)
    assertTrue(server.tools.isEmpty())
    assertEquals("failed: Failed to connect", McpUiReducer.statusLine(server))
    // An http URL stays visible, with the fact recorded rather than the server hidden.
    assertTrue(server.plaintext)
  }

  @Test
  fun aConfiguredServerIsNotConnectedAndIsNotAFailure() {
    val state = McpUiReducer.fromValue(frame(configured))
    val server = state.servers.first { it.id == "later" }
    assertEquals(McpConnectionState.Disconnected, server.state)
    assertNull(server.error)
    assertEquals("not connected", McpUiReducer.statusLine(server))
    assertEquals(0, state.connectedCount)
  }

  @Test
  fun theSummarySaysWhatIsConfiguredAndWhatTheAgentCanUse() {
    assertTrue(McpUiReducer.summary(McpState()).contains("reading"))
    assertTrue(McpUiReducer.summary(McpState(loaded = true)).contains("Stdio servers are not supported"))
    val state = McpUiReducer.fromValue(frame("$connected,$failed,$configured"))
    val summary = McpUiReducer.summary(state)
    assertTrue(summary, summary.contains("3 configured"))
    assertTrue(summary, summary.contains("1 connected"))
    assertTrue(summary, summary.contains("1 tools offered to the agent"))
    assertEquals("echo-server 1.0.0 · 1 tool", McpUiReducer.statusLine(state.servers.first { it.id == "echo" }))
  }

  @Test
  fun aDraftIsRefusedBeforeItIsSentAndTheReasonIsSpecific() {
    assertNull(McpUiReducer.addError(McpState(draftName = "Echo", draftUrl = "https://mcp.example.com/rpc")))
    // A URL-less entry names its host instead, exactly as the host does.
    assertNull(McpUiReducer.addError(McpState(draftUrl = "https://mcp.example.com/rpc")))
    assertEquals("A server URL is required", McpUiReducer.addError(McpState(draftName = "Echo")))
    assertEquals("Only http(s) MCP servers are supported", McpUiReducer.addError(McpState(draftName = "Echo", draftUrl = "ftp://x")))
    assertEquals("A server name is required", McpUiReducer.addError(McpState(draftUrl = "https:///rpc")))
  }

  @Test
  fun connectingIsRefusedWhileAReplyStreamsOrWhenAlreadyConnected() {
    val state = McpUiReducer.fromValue(frame("$connected,$configured"))
    assertFalse(McpUiReducer.canConnect(state, state.servers.first { it.id == "echo" }, busy = false))
    assertTrue(McpUiReducer.canDisconnect(busy = false, row = state.servers.first { it.id == "echo" }))
    assertTrue(McpUiReducer.canConnect(state, state.servers.first { it.id == "later" }, busy = false))
    assertFalse(McpUiReducer.canConnect(state, state.servers.first { it.id == "later" }, busy = true))
    assertFalse(McpUiReducer.canDisconnect(busy = true, row = state.servers.first { it.id == "echo" }))
    assertFalse(McpUiReducer.canDisconnect(busy = false, row = state.servers.first { it.id == "later" }))
  }

  @Test
  fun toolRowsAreSearchableByEitherNameAndByServer() {
    val state = McpUiReducer.fromValue(frame(connected))
    assertEquals(1, McpUiReducer.toolRows(state, "").size)
    assertEquals(1, McpUiReducer.toolRows(state, "mcp__echo").size)
    assertEquals(1, McpUiReducer.toolRows(state, "echo text").size)
    assertEquals(1, McpUiReducer.toolRows(state, "Echo").size)
    assertTrue(McpUiReducer.toolRows(state, "nothing").isEmpty())
  }

  @Test
  fun aRowsMissingAnIdOrNameIsNotInvented() {
    val state = McpUiReducer.fromValue(frame("""{"name":"no id"},{"id":"a"},{"id":"b","name":"B"}"""))
    // The two addressable rows survive; the id-less one is dropped rather than given a name.
    assertEquals(listOf("a", "b"), state.servers.map { it.id })
    assertEquals("a", state.servers.first().name)
    assertNull(McpUiReducer.fromValue(null).servers.firstOrNull())
    assertFalse(McpUiReducer.fromValue(null).loaded)
  }

  @Test
  fun hostLevelErrorsAreCarriedThrough() {
    val state = McpUiReducer.fromValue(frame(connected, "\"MCP configuration could not be read: permission_denied\""))
    assertEquals(1, state.errors.size)
    assertTrue(state.errors.single().contains("permission_denied"))
  }
}
