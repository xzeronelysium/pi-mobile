package dev.pi.mobile.chat

import java.util.Locale
import org.json.JSONObject

/**
 * The MCP server surface, as a pure projection of the host's `mcp` frame (`runtime/src/mcp.mjs`).
 *
 * Every field is what the host actually reported. A server that failed keeps its own reason and
 * reports no tools — a server is never shown as connected because it was configured. A server
 * whose URL is plain `http` carries that fact, because the page the runtime runs on only permits
 * `https:` connections.
 */
enum class McpConnectionState(val wire: String, val label: String) {
  Connected("connected", "connected"),
  Disconnected("disconnected", "not connected"),
  Failed("failed", "failed"),
  ;

  companion object {
    fun ofWire(value: String?): McpConnectionState? = entries.firstOrNull { it.wire == value }
  }
}

/** One tool a connected server publishes. [name] is the model-facing name; [tool] the server's own. */
data class McpToolRow(val name: String, val tool: String, val description: String = "")

/** One resource a connected server lists. */
data class McpResourceRow(val uri: String, val name: String = "")

/** One configured server and what it is doing right now. */
data class McpServerRow(
  val id: String,
  val name: String,
  val url: String,
  val state: McpConnectionState = McpConnectionState.Disconnected,
  val error: String? = null,
  val serverName: String? = null,
  val serverVersion: String? = null,
  val plaintext: Boolean = false,
  val tools: List<McpToolRow> = emptyList(),
  val resources: List<McpResourceRow> = emptyList(),
) {
  val toolCount: Int get() = tools.size
}

/** The whole MCP surface plus what the user is typing. */
data class McpState(
  val servers: List<McpServerRow> = emptyList(),
  val errors: List<String> = emptyList(),
  val draftName: String = "",
  val draftUrl: String = "",
  val loaded: Boolean = false,
) {
  val connectedCount: Int get() = servers.count { it.state == McpConnectionState.Connected }
  val toolCount: Int get() = servers.sumOf { it.toolCount }
}

object McpUiReducer {
  /** Transport notes the sheets show; the page's CSP is `connect-src https:`. */
  const val PLAINTEXT_NOTE = "http:// servers are refused by this page's CSP unless the Kotlin transport carries them"

  fun fromValue(value: JSONObject?): McpState {
    if (value == null) return McpState()
    val servers = value.optJSONArray("servers")
    return McpState(
      servers = buildList {
        if (servers == null) return@buildList
        for (index in 0 until servers.length()) {
          val row = servers.optJSONObject(index) ?: continue
          val id = row.optString("id")
          if (id.isBlank()) continue
          add(
            McpServerRow(
              id = id,
              name = row.optString("name").takeIf { it.isNotBlank() } ?: id,
              url = row.optString("url"),
              state = McpConnectionState.ofWire(row.optString("state")) ?: McpConnectionState.Disconnected,
              error = row.optString("error").takeIf { it.isNotBlank() && it != "null" },
              serverName = text(row.optString("serverName")),
              serverVersion = text(row.optString("serverVersion")),
              plaintext = row.optBoolean("plaintext"),
              tools = toolsOf(row),
              resources = resourcesOf(row),
            ),
          )
        }
      },
      errors = strings(value.optJSONArray("errors")),
      loaded = true,
    )
  }

  fun setDraftName(state: McpState, name: String): McpState = state.copy(draftName = name)

  fun setDraftUrl(state: McpState, url: String): McpState = state.copy(draftUrl = url)

  /**
   * Whether the draft can be saved at all. The host validates again — this only stops an obviously
   * unusable value from being sent, and says why rather than failing silently.
   */
  fun addError(state: McpState): String? {
    val name = state.draftName.trim()
    val url = state.draftUrl.trim()
    if (url.isEmpty()) return "A server URL is required"
    if (!url.startsWith("https://") && !url.startsWith("http://")) return "Only http(s) MCP servers are supported"
    if (name.isEmpty() && url.removePrefix("https://").removePrefix("http://").substringBefore('/').isEmpty()) return "A server name is required"
    return null
  }

  /** Connecting is refused while a reply streams: the registry cannot change mid-round. */
  fun canConnect(state: McpState, row: McpServerRow, busy: Boolean): Boolean = !busy && row.state != McpConnectionState.Connected

  fun canDisconnect(busy: Boolean, row: McpServerRow): Boolean = !busy && row.state == McpConnectionState.Connected

  fun statusLine(row: McpServerRow): String = when (row.state) {
    McpConnectionState.Connected -> buildString {
      append(row.serverName ?: row.name)
      row.serverVersion?.let { append(" $it") }
      append(" · ${row.toolCount} tool${if (row.toolCount == 1) "" else "s"}")
    }
    McpConnectionState.Failed -> "failed: ${row.error ?: "unknown reason"}"
    McpConnectionState.Disconnected -> "not connected"
  }

  /** The header line: what is configured and how much of it the agent can actually use. */
  fun summary(state: McpState): String = when {
    !state.loaded -> "reading the server list…"
    state.servers.isEmpty() -> "No MCP servers configured. Stdio servers are not supported on this build."
    else -> "${state.servers.size} configured · ${state.connectedCount} connected · ${state.toolCount} tools offered to the agent"
  }

  /** The tool rows, searchable by either the model-facing or the server's own name. */
  fun toolRows(state: McpState, query: String): List<Pair<McpServerRow, McpToolRow>> {
    val needle = query.trim().lowercase(Locale.US)
    val all = state.servers.flatMap { server -> server.tools.map { server to it } }
    if (needle.isEmpty()) return all
    return all.filter { (server, tool) ->
      tool.name.lowercase(Locale.US).contains(needle) ||
        tool.tool.lowercase(Locale.US).contains(needle) ||
        tool.description.lowercase(Locale.US).contains(needle) ||
        server.name.lowercase(Locale.US).contains(needle)
    }
  }

  private fun toolsOf(row: JSONObject): List<McpToolRow> {
    val array = row.optJSONArray("tools") ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val tool = array.optJSONObject(index) ?: continue
        val name = tool.optString("name")
        if (name.isBlank()) continue
        add(McpToolRow(name = name, tool = tool.optString("tool").ifBlank { name }, description = tool.optString("description")))
      }
    }
  }

  private fun resourcesOf(row: JSONObject): List<McpResourceRow> {
    val array = row.optJSONArray("resources") ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val resource = array.optJSONObject(index) ?: continue
        val uri = resource.optString("uri")
        if (uri.isBlank()) continue
        add(McpResourceRow(uri = uri, name = resource.optString("name").ifBlank { uri }))
      }
    }
  }

  private fun strings(array: org.json.JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val value = array.optString(index)
        if (value.isNotBlank() && value != "null") add(value)
      }
    }
  }

  private fun text(value: String): String? = value.takeIf { it.isNotBlank() && it != "null" }
}
