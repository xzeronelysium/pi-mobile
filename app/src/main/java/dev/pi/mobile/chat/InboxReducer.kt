package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * One input waiting its turn, from the `inbox` bridge payload.
 *
 * [kind] distinguishes what was queued (a message or a steer); [status] is the host's own label.
 */
data class QueuedInput(
  val id: String,
  val kind: String,
  val text: String,
  val status: String,
)

/** The queued-input inbox: the rows plus the badge count the composer shows. */
data class InboxState(val queued: List<QueuedInput> = emptyList()) {
  val count: Int get() = queued.size
}

/**
 * Pure projection of the `inbox` bridge payload: queued-input rows, their badge count, and
 * withdrawal by id. Rows without an id are dropped because they could never be withdrawn.
 */
object InboxReducer {
  fun fromValue(value: JSONObject?): InboxState {
    val array = value?.optJSONArray("queued") ?: return InboxState()
    return InboxState(queuedOf(array))
  }

  /** Remove one queued input. An unknown or blank id changes nothing. */
  fun withdraw(state: InboxState, id: String): InboxState =
    if (id.isBlank()) state else state.copy(queued = state.queued.filterNot { it.id == id })

  /** The badge text: empty when nothing is queued, capped so a long queue cannot overflow. */
  fun badge(state: InboxState): String = when {
    state.count <= 0 -> ""
    state.count > MAX_BADGE -> "$MAX_BADGE+"
    else -> state.count.toString()
  }

  /** A row's compact label: the first line of its text, or the kind when there is no text. */
  fun rowLabel(item: QueuedInput): String =
    item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: item.kind

  private fun queuedOf(array: JSONArray): List<QueuedInput> = buildList {
    for (index in 0 until array.length()) {
      val row = array.optJSONObject(index) ?: continue
      val id = row.optString("id")
      if (id.isBlank()) continue
      add(
        QueuedInput(
          id = id,
          kind = row.optString("kind").ifBlank { "input" },
          text = text(row.optString("text")),
          status = row.optString("status").ifBlank { "queued" },
        ),
      )
    }
  }

  /** A JSON `null` (which `optString` may report as the text "null") is an absent value. */
  private fun text(value: String): String = if (value == "null") "" else value

  private const val MAX_BADGE = 9
}
