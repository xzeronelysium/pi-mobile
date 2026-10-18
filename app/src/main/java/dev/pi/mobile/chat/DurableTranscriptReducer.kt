package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps the durable-native transcript rows produced by `runtime/src/ui.mjs` onto [ChatEntry].
 *
 * Pi Durable entries each carry the pi-ai provider messages they represent, so the JS host
 * projects them into flat rows (`role`, `text`, `toolCallId`, `status`, ...). This reducer is the
 * Kotlin half of that contract: the durable replacement for the CLI-shaped [ChatTranscriptReducer]
 * parsing, with no dependency on CLI `AgentMessage` shapes.
 *
 * Row `key` becomes the [ChatEntry.id], so ids are stable across history rebuilds and Compose keeps
 * its state (scroll position, expanded tools) without extra reconciliation.
 */
object DurableTranscriptReducer {
  fun fromRows(rows: JSONArray?): List<ChatEntry> {
    if (rows == null) return emptyList()
    val out = ArrayList<ChatEntry>(rows.length())
    for (index in 0 until rows.length()) {
      val row = rows.optJSONObject(index) ?: continue
      out.add(entryOf(row, index))
    }
    return out
  }

  /**
   * Apply one `message` frame from `runtime/src/transcript.mjs`.
   *
   * A frame that is a whole-conversation snapshot ([full] and [snapshot]) replaces the durable rows
   * exactly as a fresh whole-history read would. Every other frame — a delta, or a re-seed the frame
   * does not vouch for — is upserted by id in place: it can add and refresh rows, and it can retire
   * the transient streaming rows it names in [removed], but it can never delete a committed row it
   * does not carry. A frame with nothing to apply hands [prior] straight back, so a batch that
   * changed no row costs nothing. Local UI state the transcript does not carry (an expanded tool
   * row) survives.
   *
   * A transient row — the runtime's synthetic `live:*` key, reused by design on every turn — never
   * displaces a committed row that happens to share its id, so a later turn's streaming partial can
   * never stand in for an earlier turn's answer.
   */
  fun applyMessage(
    prior: List<ChatEntry>,
    rows: List<ChatEntry>,
    removed: List<String> = emptyList(),
    full: Boolean = false,
    snapshot: Boolean = false,
  ): List<ChatEntry> {
    if (full && snapshot) return mergeEntries(prior, rows)
    if (rows.isEmpty() && removed.isEmpty()) return prior
    val index = LinkedHashMap<String, ChatEntry>(prior.size + rows.size)
    for (entry in prior) index[entry.id] = entry
    for (row in rows) {
      val existing = index[row.id]
      // A transient row's id belongs to the turn in flight and is reused by the next turn: it may
      // refresh the partial it is carrying on from, but it may never take over a row that describes
      // something else — a committed row, or another call's row.
      if (existing != null && isTransient(row.id) && !describesTheSameRow(existing, row)) continue
      index[row.id] = preserveToolState(existing, row)
    }
    for (id in removed) {
      // Only a transient row may be retired. A frame that names a committed row is not describing
      // the transcript it is holding, and deleting it is exactly the reported "the previous message
      // and its answer vanish".
      val existing = index[id] ?: continue
      if (isTransient(existing.id)) index.remove(id)
    }
    return index.values.toList()
  }

  /**
   * Apply an explicit `history` read. A read that reached the conversation's first entry is
   * authoritative and rebuilds the transcript; a page of a longer conversation is merged, so a
   * frame can never delete a row it does not carry.
   */
  fun applyHistory(prior: List<ChatEntry>, rows: List<ChatEntry>, complete: Boolean): List<ChatEntry> =
    if (complete) mergeEntries(prior, rows) else applyMessage(prior, rows)

  /**
   * True for a row the runtime streams for the turn in flight rather than a durable entry: the
   * runtime's synthetic entry id, so the key always starts with `live:`. Reused on every turn by
   * design, which is exactly why a committed row must never take such an id.
   */
  internal fun isTransient(id: String): Boolean = id.substringBefore(':') == TRANSIENT_ENTRY_ID

  /**
   * True when an upsert replaces the very row it describes rather than hijacking another one: the
   * same kind of row, and for a tool call the same call. A transient key carries a block position
   * (`live:text0`, `live:tool1`), so a later turn's partial at the same position is a different row
   * unless it is the row the same call already opened.
   */
  private fun describesTheSameRow(existing: ChatEntry, row: ChatEntry): Boolean = when {
    existing::class != row::class -> false
    existing is ChatEntry.Tool && row is ChatEntry.Tool -> existing.toolCallId == row.toolCallId
    else -> true
  }

  /** A rebuilt tool row keeps the state the row it replaces was already carrying. */
  private fun preserveToolState(existing: ChatEntry?, row: ChatEntry): ChatEntry {
    if (row !is ChatEntry.Tool) return row
    val prior = existing as? ChatEntry.Tool ?: return row
    return row.copy(id = prior.id, expanded = prior.expanded)
  }

  /**
   * Rebuild from already-parsed [rebuilt] entries (a host `history`/`snapshot` payload) while
   * preserving local UI state the transcript does not carry: expanded tool rows and locally
   * added notices.
   *
   * Which tool rows are open is [TranscriptFoldReducer.expandedToolIds]' decision, so the same
   * predicate is not written twice.
   */
  fun mergeEntries(prior: List<ChatEntry>, rebuilt: List<ChatEntry>): List<ChatEntry> {
    val expanded = TranscriptFoldReducer.expandedToolIds(prior)
    val priorToolIds = committedToolIds(prior)
    val merged = rebuilt.map { row ->
      if (row !is ChatEntry.Tool) row
      else {
        val keepId = priorToolIds[row.toolCallId]
        if (keepId != null || row.toolCallId in expanded) row.copy(id = keepId ?: row.id, expanded = row.toolCallId in expanded)
        else row
      }
    }.toMutableList()
    val rebuiltNotices = merged.filterIsInstance<ChatEntry.SystemNotice>().map { it.text }.toSet()
    merged += prior.filterIsInstance<ChatEntry.SystemNotice>().filter { it.text !in rebuiltNotices }
    return merged
  }

  /**
   * Committed tool rows by call id, for reusing a row's id across a rebuild so Compose keeps its
   * state. A transient streaming row is left out: its id belongs to the turn in flight and is reused
   * by the next turn, so a committed row must never adopt it.
   */
  private fun committedToolIds(prior: List<ChatEntry>): Map<String, String> =
    prior.asSequence()
      .filterIsInstance<ChatEntry.Tool>()
      .filterNot { isTransient(it.id) }
      .associate { it.toolCallId to it.id }

  private fun entryOf(row: JSONObject, index: Int): ChatEntry {
    val id = row.optString("key").ifBlank { "row-$index" }
    return when (row.optString("role")) {
      "user" -> ChatEntry.User(id = id, text = row.optString("text"))
      "assistant" -> ChatEntry.Assistant(id = id, markdown = row.optString("text"))
      "thinking" -> ChatEntry.Thinking(id = id, text = row.optString("text"))
      "tool" -> ChatEntry.Tool(
        id = id,
        toolCallId = row.optString("toolCallId"),
        name = row.optString("name").ifBlank { "tool" },
        arguments = row.optString("arguments"),
        status = statusOf(row.optString("status")),
        output = row.optString("output"),
      )
      // Unknown or notice rows degrade to a visible notice rather than vanishing.
      else -> ChatEntry.SystemNotice(id = id, text = row.optString("text"))
    }
  }

  private fun statusOf(value: String): ChatEntry.ToolStatus = when (value) {
    "ok" -> ChatEntry.ToolStatus.Ok
    "error" -> ChatEntry.ToolStatus.Error
    "running" -> ChatEntry.ToolStatus.Running
    else -> ChatEntry.ToolStatus.Pending
  }

  /**
   * The synthetic entry id `runtime/src/transcript.mjs` projects the in-flight assistant partial
   * under. It is never a durable id, and the runtime keys the partial's rows with it on every turn,
   * which is why no committed row may share it.
   */
  private const val TRANSIENT_ENTRY_ID = "live"
}
