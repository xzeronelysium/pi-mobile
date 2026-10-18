package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue, compact, notice and rename-session: the decisions each dialog makes before it calls out. */
class SessionDialogsUiTest {
  private fun inbox(vararg ids: String): InboxState = InboxReducer.fromValue(
    JSONObject().put(
      "queued",
      JSONArray().apply { ids.forEach { put(JSONObject().put("id", it).put("kind", "steer").put("text", "t$it").put("status", "queued")) } },
    ),
  )

  @Test fun renameRefusesAnEmptyOrUnchangedName() {
    assertFalse(SessionDialogsReducer.canRename("Beta", "  "))
    assertFalse(SessionDialogsReducer.canRename("Beta", "Beta"))
    assertTrue(SessionDialogsReducer.canRename("Beta", " Beta 1 "))
  }

  @Test fun renameIsTrimmedBeforeItReachesTheRuntime() {
    assertEquals("Beta 1", SessionDialogsReducer.renameArg("  Beta 1  "))
  }

  @Test fun compactionNeedsAnIdleReadyRuntime() {
    assertFalse(SessionDialogsReducer.canCompact(busy = true, ready = true))
    assertFalse(SessionDialogsReducer.canCompact(busy = false, ready = false))
    assertTrue(SessionDialogsReducer.canCompact(busy = false, ready = true))
  }

  @Test fun compactionInstructionsAreOptional() {
    assertNull(SessionDialogsReducer.compactNote("   "))
    assertEquals("keep the API section", SessionDialogsReducer.compactNote("  keep the API section "))
  }

  /** The dialog reads as two labelled bands — Steering and Follow-up — both always present. */
  @Test fun theQueueIsSplitIntoItsTwoJoinModes() {
    val mixed = InboxReducer.fromValue(
      JSONObject().put(
        "queued",
        JSONArray()
          .put(JSONObject().put("id", "1").put("kind", "steer").put("text", "hold on").put("status", "queued"))
          .put(JSONObject().put("id", "2").put("kind", "follow-up").put("text", "then this").put("status", "queued"))
          .put(JSONObject().put("id", "3").put("kind", "input").put("text", "and this").put("status", "queued")),
      ),
    )
    val sections = SessionDialogsReducer.queueSections(mixed)
    assertEquals(listOf("Steering", "Follow-up"), sections.map { it.title })
    // A kind that names neither join mode is listed under the default join, Steering.
    assertEquals(listOf("1", "3"), sections[0].rows.map { it.id })
    assertEquals(listOf("2"), sections[1].rows.map { it.id })
    // Both bands stay present on an empty queue, so the dialog always names both ways in.
    val empty = SessionDialogsReducer.queueSections(InboxState())
    assertEquals(listOf("Steering", "Follow-up"), empty.map { it.title })
    assertTrue(empty.all { it.rows.isEmpty() })
  }

  @Test fun withdrawingDropsExactlyThatRow() {
    assertEquals(listOf("2"), SessionDialogsReducer.withdraw(inbox("1", "2"), "1").queued.map { it.id })
  }

  @Test fun anExportedFileNameCannotEscapeTheDirectoryItIsWrittenTo() {
    assertEquals("conversation-7.html", SessionExportUi.safeFileName("conversation-7.html", format = "html"))
    assertEquals("passwd", SessionExportUi.safeFileName("../../etc/passwd", format = "html"))
    assertEquals("conversation.jsonl", SessionExportUi.safeFileName("", format = "jsonl"))
    assertEquals("conversation.md", SessionExportUi.safeFileName(null, format = "markdown"))
  }

  @Test fun theExportExtensionFollowsTheFormatActuallyReturned() {
    assertEquals("html", SessionExportUi.extension("html"))
    assertEquals("jsonl", SessionExportUi.extension("jsonl"))
    assertEquals("txt", SessionExportUi.extension("something-else"))
  }
}
