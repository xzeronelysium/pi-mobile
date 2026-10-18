package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One conversation the history drawer can open.
 *
 * Mirrors the `conversations` payload rows (`{id, name, created, modified, messageCount, owner,
 * current}`) the host pushes over the bridge. Timestamps are epoch milliseconds.
 */
data class SessionRow(
  val id: String,
  val name: String,
  val created: Long = 0L,
  val modified: Long = 0L,
  val messageCount: Int = 0,
  val owner: String? = null,
  val current: Boolean = false,
)

/** The drawer's session list: which session is selected plus the rows in display order. */
data class SessionList(
  val currentId: String? = null,
  val rows: List<SessionRow> = emptyList(),
) {
  val current: SessionRow? get() = rows.firstOrNull { it.id == currentId }

  fun search(query: String): List<SessionRow> = SessionListReducer.search(this, query)
}

/** One age band of the drawer's list: its header text and the rows under it, in display order. */
data class SessionGroup(val header: String, val rows: List<SessionRow>)

/**
 * Pure projection of the `conversations` bridge payload: selection, name search, stable ordering
 * (current first, then most recently modified) and the age/detail text the drawer shows.
 *
 * `now` is always injected so the relative-time text is deterministic under test.
 */
object SessionListReducer {
  fun fromValue(value: JSONObject?): SessionList {
    if (value == null) return SessionList()
    val rows = rowsOf(value.optJSONArray("rows"))
    val declared = text(value.optString("current"))
    val currentId = declared ?: rows.firstOrNull { it.current }?.id
    return SessionList(currentId, order(rows, currentId))
  }

  /** Current first, then most recently modified; equal keys keep payload order (stable sort). */
  fun order(rows: List<SessionRow>, currentId: String?): List<SessionRow> =
    rows.sortedWith(
      compareByDescending<SessionRow> { it.id == currentId }.thenByDescending { it.modified },
    )

  /** Filter by session name. A blank query returns the list untouched. */
  fun search(state: SessionList, query: String): List<SessionRow> {
    val needle = query.trim().lowercase(Locale.US)
    if (needle.isEmpty()) return state.rows
    return state.rows.filter { it.name.lowercase(Locale.US).contains(needle) }
  }

  /** Select a session (or clear the selection with null). An unknown id selects nothing. */
  fun select(state: SessionList, id: String?): SessionList {
    val target = id?.takeIf { candidate -> state.rows.any { it.id == candidate } }
    val rows = state.rows.map { it.copy(current = it.id == target) }
    return SessionList(target, order(rows, target))
  }

  /**
   * Compact age of a session in the drawer's own vocabulary: `now`, `5m ago`, `2h ago`,
   * `Yesterday`, the weekday (this week), then `MMM d`. A missing timestamp is reported as empty,
   * never fabricated, and a clock-skewed future row reads as `now` rather than a negative age.
   */
  fun relativeTime(modified: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (modified <= 0L) return ""
    val elapsed = now - modified
    if (elapsed < MINUTE) return "now"
    val minutes = elapsed / MINUTE
    if (minutes < 60L) return "${minutes}m ago"
    if (elapsed < DAY) return "${elapsed / HOUR}h ago"
    if (elapsed < 2 * DAY) return "Yesterday"
    if (elapsed < WEEK) return weekday(modified, zone)
    return shortDate(modified, zone)
  }

  /**
   * The secondary line under a session: the age, then the message count — `2h ago · 5 messages`.
   * A row with no timestamp says only what it knows, never a bare leading separator.
   */
  fun detail(row: SessionRow, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val count = row.messageCount.coerceAtLeast(0)
    val messages = if (count == 1) "1 message" else "$count messages"
    val whenLabel = relativeTime(row.modified, now, zone)
    return if (whenLabel.isEmpty()) messages else "$whenLabel · $messages"
  }

  /**
   * Four stable age bands — Today / Yesterday / This week / Older — computed from each row's epoch
   * milliseconds. Input order (current first, then most recently modified) is preserved inside a
   * band; a band vanishes when it is empty. A row with no timestamp, or one clock-skewed into the
   * future, lands in Older rather than masquerading as current activity.
   */
  fun grouped(rows: List<SessionRow>, now: Long): List<SessionGroup> {
    if (rows.isEmpty()) return emptyList()
    val today = ArrayList<SessionRow>()
    val yesterday = ArrayList<SessionRow>()
    val thisWeek = ArrayList<SessionRow>()
    val older = ArrayList<SessionRow>()
    for (row in rows) {
      when {
        row.modified <= 0L || row.modified > now -> older.add(row)
        now - row.modified < DAY -> today.add(row)
        now - row.modified < 2 * DAY -> yesterday.add(row)
        now - row.modified < WEEK -> thisWeek.add(row)
        else -> older.add(row)
      }
    }
    return buildList(4) {
      if (today.isNotEmpty()) add(SessionGroup("Today", today))
      if (yesterday.isNotEmpty()) add(SessionGroup("Yesterday", yesterday))
      if (thisWeek.isNotEmpty()) add(SessionGroup("This week", thisWeek))
      if (older.isNotEmpty()) add(SessionGroup("Older", older))
    }
  }

  private fun rowsOf(array: JSONArray?): List<SessionRow> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val id = row.optString("id")
        if (id.isBlank()) continue
        add(
          SessionRow(
            id = id,
            name = row.optString("name").ifBlank { id },
            created = row.optLong("created").coerceAtLeast(0L),
            modified = row.optLong("modified").coerceAtLeast(0L),
            messageCount = row.optInt("messageCount").coerceAtLeast(0),
            owner = text(row.optString("owner")),
            current = row.optBoolean("current"),
          ),
        )
      }
    }
  }

  /** A JSON `null` (which `optString` may report as the text "null") is an absent value. */
  private fun text(value: String): String? = value.takeIf { it.isNotBlank() && it != "null" }

  /** `MMM d` in the caller's zone, built per call: `SimpleDateFormat` is neither thread-safe nor free. */
  private fun shortDate(modified: Long, zone: TimeZone): String {
    val format = SimpleDateFormat("MMM d", Locale.US)
    format.timeZone = zone
    return format.format(Date(modified))
  }

  /** Monday-first weekday name for a timestamp in the caller's zone. */
  private fun weekday(modified: Long, zone: TimeZone): String {
    val calendar = Calendar.getInstance(zone)
    calendar.timeInMillis = modified
    // Calendar: 1 = Sunday … 7 = Saturday → index into a Monday-first array.
    return WEEKDAYS[(calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7]
  }

  private val WEEKDAYS = arrayOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
  )

  private const val MINUTE = 60_000L
  private const val HOUR = 60L * MINUTE
  private const val DAY = 24L * HOUR
  private const val WEEK = 7L * DAY
}
