package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The session list the history drawer renders: selection, search, ordering and age text. */
class SessionListReducerTest {
  private fun value(current: String?, vararg rows: JSONObject): JSONObject {
    val array = JSONArray()
    rows.forEach { array.put(it) }
    return JSONObject().put("current", current).put("rows", array)
  }

  private fun row(
    id: String,
    name: String = id,
    created: Long = 0L,
    modified: Long = 0L,
    messageCount: Int = 0,
    owner: String? = null,
    current: Boolean = false,
  ): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("created", created)
    put("modified", modified)
    put("messageCount", messageCount)
    if (owner != null) put("owner", owner)
    put("current", current)
  }

  private val hour = 3_600_000L

  @Test fun parsesEveryFieldOfThePayload() {
    val state = SessionListReducer.fromValue(value("b", row("b", "Beta", created = 10L, modified = 900L, messageCount = 4, owner = "task-1", current = true)))
    assertEquals("b", state.currentId)
    assertEquals(1, state.rows.size)
    val only = state.rows.single()
    assertEquals("b", only.id)
    assertEquals("Beta", only.name)
    assertEquals(10L, only.created)
    assertEquals(900L, only.modified)
    assertEquals(4, only.messageCount)
    assertEquals("task-1", only.owner)
    assertTrue(only.current)
  }

  @Test fun anAbsentOrNullPayloadIsAnEmptyListRatherThanACrash() {
    assertTrue(SessionListReducer.fromValue(null).rows.isEmpty())
    val missing = SessionListReducer.fromValue(JSONObject())
    assertTrue(missing.rows.isEmpty())
    assertNull(missing.currentId)
    assertEquals("", SessionListReducer.relativeTime(0L, 1_000L))
    assertEquals("0 messages", SessionListReducer.detail(SessionRow("x", "x"), 1_000L))
  }

  @Test fun rowsWithoutAnIdAreDroppedAndABlankNameFallsBackToTheId() {
    val state = SessionListReducer.fromValue(value(null, row("", "nameless"), row("keep")))
    assertEquals(listOf("keep"), state.rows.map { it.id })
    assertEquals("keep", state.rows.single().name)
  }

  @Test fun theCurrentSessionSortsFirstThenMostRecentlyModified() {
    // The payload order is deliberately not the display order.
    val state = SessionListReducer.fromValue(
      value(
        "a",
        row("c", modified = 300L),
        row("a", modified = 100L),
        row("b", modified = 500L),
      ),
    )
    assertEquals(listOf("a", "b", "c"), state.rows.map { it.id })
  }

  @Test fun aPayloadWithoutACurrentIdFallsBackToTheRowFlaggedCurrent() {
    val state = SessionListReducer.fromValue(value(null, row("a", modified = 500L), row("b", modified = 100L, current = true)))
    assertEquals("b", state.currentId)
    assertEquals("b", state.rows.first().id)
  }

  @Test fun tiesKeepThePayloadOrderSoTheListNeverJitters() {
    val state = SessionListReducer.fromValue(value(null, row("a", modified = 100L), row("b", modified = 100L), row("c", modified = 100L)))
    assertEquals(listOf("a", "b", "c"), state.rows.map { it.id })
  }

  @Test fun searchFiltersByNameCaseInsensitively() {
    val state = SessionListReducer.fromValue(value(null, row("a", "Refactor backend"), row("b", "Docs"), row("c", "refactor UI")))
    assertEquals(listOf("a", "c"), SessionListReducer.search(state, "REFACTOR").map { it.id })
    assertEquals(state.rows, SessionListReducer.search(state, "   "))
    assertTrue(SessionListReducer.search(state, "nothing").isEmpty())
  }

  @Test fun selectingASessionMarksExactlyOneRowCurrent() {
    val state = SessionListReducer.fromValue(value("a", row("a"), row("b")))
    val selected = SessionListReducer.select(state, "b")
    assertEquals("b", selected.currentId)
    assertEquals(listOf("b"), selected.rows.filter { it.current }.map { it.id })
    // An unknown or cleared id leaves nothing selected rather than a stale row.
    assertNull(SessionListReducer.select(state, "zzz").currentId)
    assertNull(SessionListReducer.select(state, null).currentId)
    assertTrue(SessionListReducer.select(state, "b").rows.none { it.id == "a" && it.current })
  }

  @Test fun relativeTimeUsesTheDrawersOwnVocabulary() {
    // 2026-10-17T12:00:00Z — a Saturday, so the weekday bucket is unambiguous.
    val now = 1_792_238_400_000L
    assertEquals("now", SessionListReducer.relativeTime(now - 30_000L, now))
    assertEquals("5m ago", SessionListReducer.relativeTime(now - 5 * 60_000L, now))
    assertEquals("2h ago", SessionListReducer.relativeTime(now - 2 * hour, now))
    assertEquals("Yesterday", SessionListReducer.relativeTime(now - 30 * hour, now))
    // This week reads as the weekday name; anything older as a short date. Both in an explicit zone.
    assertEquals("Wednesday", SessionListReducer.relativeTime(now - 3 * 24 * hour, now, TimeZone.getTimeZone("UTC")))
    assertEquals("Sep 17", SessionListReducer.relativeTime(now - 30 * 24 * hour, now, TimeZone.getTimeZone("UTC")))
    // A missing timestamp is not rendered as a fabricated age.
    assertEquals("", SessionListReducer.relativeTime(0L, now))
  }

  @Test fun aFutureTimestampReadsAsNowRatherThanANegativeAge() {
    val now = 1_792_238_400_000L
    assertEquals("now", SessionListReducer.relativeTime(now + 60_000L, now))
  }

  @Test fun detailReadsAsTheAgeThenTheMessageCount() {
    val now = 1_792_238_400_000L
    assertEquals("2h ago · 1 message", SessionListReducer.detail(SessionRow("a", "A", messageCount = 1, modified = now - 2 * hour), now))
    assertEquals("2h ago · 12 messages", SessionListReducer.detail(SessionRow("a", "A", messageCount = 12, modified = now - 2 * hour, owner = "task-9"), now))
    // The owner is carried by the payload but is not part of a row's secondary line.
    assertFalse(SessionListReducer.detail(SessionRow("a", "A", messageCount = 12, modified = now - 2 * hour, owner = "task-9"), now).contains("task-9"))
    // No timestamp: only what is actually known is shown, never a bare leading separator.
    assertEquals("3 messages", SessionListReducer.detail(SessionRow("a", "A", messageCount = 3), now))
    assertEquals("0 messages", SessionListReducer.detail(SessionRow("a", "A"), now))
  }

  @Test fun groupedBandsTheListByAgeAndKeepsTheOrderInsideABand() {
    val now = 1_792_238_400_000L
    val day = 24 * hour
    val rows = listOf(
      SessionRow("a", "A", modified = now - 5 * 60_000L),
      SessionRow("b", "B", modified = now - 30 * hour),
      SessionRow("c", "C", modified = now - 3 * day),
      SessionRow("d", "D", modified = now - 30 * day),
      SessionRow("e", "E", modified = now - hour),
      // A row with no timestamp, and one clock-skewed into the future, both land in Older.
      SessionRow("f", "F", modified = 0L),
      SessionRow("g", "G", modified = now + hour),
    )
    val groups = SessionListReducer.grouped(rows, now)
    assertEquals(listOf("Today", "Yesterday", "This week", "Older"), groups.map { it.header })
    assertEquals(listOf("a", "e"), groups[0].rows.map { it.id })
    assertEquals(listOf("b"), groups[1].rows.map { it.id })
    assertEquals(listOf("c"), groups[2].rows.map { it.id })
    assertEquals(listOf("d", "f", "g"), groups[3].rows.map { it.id })
    // Empty bands vanish rather than leaving a header with nothing under it.
    assertEquals(listOf("Today"), SessionListReducer.grouped(listOf(SessionRow("a", "A", modified = now - hour)), now).map { it.header })
    assertTrue(SessionListReducer.grouped(emptyList(), now).isEmpty())
  }
}
