package dev.pi.mobile.chat

import org.json.JSONObject

/**
 * The one-line form of a tool call, shared by the transcript's compact tool row and by the
 * preview line of a folded agent-work group.
 *
 * Restored from the pre-cut `ChatTranscriptReducer.toolSummary`/`extractToolDetail`: the collapsed
 * row is `name · status · command/path`, and the detail is taken from a real argument field rather
 * than from pretty-printed JSON's first line (which would render a lone `{`). Pure and dependency
 * free, so it is exercised off-device.
 */
object ToolSummary {
  /** Field names, in priority order, whose value reads as the call's subject. */
  private val DETAIL_KEYS = listOf(
    "command", "path", "file", "filepath", "file_path", "target", "query", "pattern", "url",
  )

  /** The longest detail the collapsed line shows before it is capped with an ellipsis. */
  const val MAX_DETAIL = 72

  fun statusLabel(status: ChatEntry.ToolStatus): String = when (status) {
    ChatEntry.ToolStatus.Pending -> "pending"
    ChatEntry.ToolStatus.Running -> "running"
    ChatEntry.ToolStatus.Ok -> "ok"
    ChatEntry.ToolStatus.Error -> "error"
  }

  /** `name · status · detail`, with any blank part omitted rather than left as a stray separator. */
  fun summary(name: String, arguments: String, statusLabel: String? = null): String {
    val detail = detail(arguments)
    return buildString {
      append(name)
      if (!statusLabel.isNullOrBlank()) {
        append(" · ")
        append(statusLabel)
      }
      if (detail.isNotBlank()) {
        append(" · ")
        append(if (detail.length > MAX_DETAIL) detail.take(MAX_DETAIL) + "…" else detail)
      }
    }
  }

  /** The argument value that names what the call acts on, or "" when nothing readable is present. */
  fun detail(arguments: String): String {
    val raw = arguments.trim()
    if (raw.isEmpty()) return ""
    // An empty object/array is not a detail; do not render a stray `{}` after the status.
    if (raw == "{}" || raw == "[]") return ""
    runCatching {
      val obj = JSONObject(raw)
      if (obj.length() == 0) return ""
      DETAIL_KEYS
        .mapNotNull { key -> obj.opt(key)?.toString()?.takeIf { it.isNotBlank() } }
        .firstOrNull()
        ?.let { return it.trim() }
      // Fall back to the first non-object primitive string value.
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key)
        if (value is String && value.isNotBlank() &&
          !value.trimStart().startsWith("{") && !value.trimStart().startsWith("[")
        ) {
          return value.trim()
        }
      }
    }
    // Pretty-printed multi-line JSON: skip brace/bracket-only and key-only lines.
    val line = raw.lineSequence()
      .map { it.trim() }
      .firstOrNull { candidate ->
        candidate.isNotEmpty() &&
          candidate != "{" && candidate != "}" && candidate != "[" && candidate != "]" &&
          !candidate.matches(Regex("""^"[^"]+"\s*:?\s*$"""))
      }
      ?: return ""
    val quoted = Regex("""^"[^"]+"\s*:\s*"(.*)"\s*,?\s*$""").matchEntire(line)
    if (quoted != null) return quoted.groupValues[1]
    val bare = Regex("""^"[^"]+"\s*:\s*(.+?)\s*,?\s*$""").matchEntire(line)
    if (bare != null) {
      val value = bare.groupValues[1].trim().trim('"')
      if (value != "{" && value != "[") return value
    }
    return line.trim('"', ',', ' ')
  }
}
