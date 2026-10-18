package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queued inputs waiting their turn, plus the badge count and withdrawal the composer needs. */
class InboxReducerTest {
  private fun value(vararg queued: JSONObject): JSONObject {
    val array = JSONArray()
    queued.forEach { array.put(it) }
    return JSONObject().put("queued", array)
  }

  private fun item(id: String, kind: String = "message", text: String = "hello", status: String = "queued"): JSONObject =
    JSONObject().put("id", id).put("kind", kind).put("text", text).put("status", status)

  @Test fun parsesEveryFieldOfAQueuedInput() {
    val state = InboxReducer.fromValue(value(item("q1", "steer", "stop and run tests", "waiting")))
    val only = state.queued.single()
    assertEquals("q1", only.id)
    assertEquals("steer", only.kind)
    assertEquals("stop and run tests", only.text)
    assertEquals("waiting", only.status)
    assertEquals(1, state.count)
  }

  @Test fun anAbsentOrPartialPayloadIsAnEmptyInboxRatherThanACrash() {
    assertTrue(InboxReducer.fromValue(null).queued.isEmpty())
    assertEquals(0, InboxReducer.fromValue(null).count)
    assertEquals(0, InboxReducer.fromValue(JSONObject()).count)
    // Rows without an id cannot be withdrawn, so they are dropped rather than shown unremovable.
    assertEquals(listOf("keep"), InboxReducer.fromValue(value(item(""), item("keep"))).queued.map { it.id })
  }

  @Test fun blankKindAndStatusFallBackToUsableLabels() {
    val state = InboxReducer.fromValue(value(JSONObject().put("id", "q1").put("text", "hi")))
    assertEquals("input", state.queued.single().kind)
    assertEquals("queued", state.queued.single().status)
  }

  @Test fun theCountFeedsTheBadgeAndCapsAtNinePlus() {
    assertEquals("", InboxReducer.badge(InboxReducer.fromValue(null)))
    assertEquals("1", InboxReducer.badge(InboxReducer.fromValue(value(item("a")))))
    assertEquals("9+", InboxReducer.badge(InboxReducer.fromValue(value(item("a"), item("b"), item("c"), item("d"), item("e"), item("f"), item("g"), item("h"), item("i"), item("j")))))
  }

  @Test fun withdrawalRemovesExactlyThatQueuedInput() {
    val state = InboxReducer.fromValue(value(item("a"), item("b"), item("c")))
    val after = InboxReducer.withdraw(state, "b")
    assertEquals(listOf("a", "c"), after.queued.map { it.id })
    assertEquals(2, after.count)
    // The original state is immutable.
    assertEquals(3, state.count)
    // Withdrawing something already gone changes nothing.
    assertEquals(after.queued, InboxReducer.withdraw(after, "b").queued)
    assertEquals(after.queued, InboxReducer.withdraw(after, "").queued)
    assertFalse(InboxReducer.withdraw(state, "c").queued.any { it.id == "c" })
  }

  @Test fun aRowReadsAsItsFirstLineOfTextOrFallsBackToTheKind() {
    val state = InboxReducer.fromValue(value(item("a", "message", "fix the build\nsecond line"), item("b", "steer", "   ")))
    assertEquals("fix the build", InboxReducer.rowLabel(state.queued[0]))
    assertEquals("steer", InboxReducer.rowLabel(state.queued[1]))
  }
}
