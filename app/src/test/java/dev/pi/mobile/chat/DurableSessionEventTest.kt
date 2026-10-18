package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session frames `runtime/src/host.mjs` emits, as its own payloads, landing in the typed events
 * the drawer, dock and queue dialog render. Each frame is projected by the sibling reducer that owns
 * its shape.
 */
class DurableSessionEventTest {
  private fun value(vararg keys: Pair<String, Any>): JSONObject =
    JSONObject().apply { keys.forEach { (key, item) -> put(key, item) } }

  @Test fun theConversationListFrameCarriesTheRowFieldsTheDrawerReads() {
    val rows = JSONArray().put(
      JSONObject().put("id", "b").put("name", "Beta").put("created", 1L).put("modified", 9L)
        .put("messageCount", 4).put("owner", "task-1").put("current", true).put("truncated", false),
    )
    val event = DurableEvent.parse(JSONObject().put("event", "conversations").put("value", value("current" to "b", "rows" to rows)))
    val conversations = event as DurableEvent.Conversations
    assertEquals("b", conversations.list.currentId)
    val row = conversations.list.rows.single()
    assertEquals("Beta", row.name)
    assertEquals(1L, row.created)
    assertEquals(9L, row.modified)
    assertEquals(4, row.messageCount)
    assertEquals("task-1", row.owner)
  }

  /** The tree frame has no depth: the reducer computes it, or nothing would nest. */
  @Test fun theTreeFrameHasNoDepthAndTheReducerComputesIt() {
    val nodes = JSONArray()
      .put(JSONObject().put("id", "1").put("parentId", JSONObject.NULL).put("entryId", JSONObject.NULL).put("title", "Root").put("current", true))
      .put(JSONObject().put("id", "2").put("parentId", "1").put("entryId", "7").put("title", "Fork").put("current", false))
    val event = DurableEvent.parse(JSONObject().put("event", "tree").put("value", value("nodes" to nodes))) as DurableEvent.Tree
    assertEquals(2, event.tree.rows.size)
    assertEquals(0, event.tree.rows[0].depth)
    assertEquals(1, event.tree.rows[1].depth)
    assertEquals("7", event.tree.rows[1].entryId)
    assertEquals("1", event.tree.currentId)
  }

  @Test fun theUsageFrameFeedsTheDockLine() {
    val models = JSONArray().put(
      JSONObject().put("provider", "anthropic").put("modelId", "m").put("input", 1200).put("output", 340)
        .put("cacheRead", 10).put("cacheWrite", 20).put("cost", 0.02),
    )
    val event = DurableEvent.parse(
      JSONObject().put("event", "usage").put(
        "value",
        value(
          "models" to models,
          "tools" to JSONArray().put(JSONObject().put("name", "bash")),
          "context" to JSONObject().put("used", 24_000).put("window", 200_000),
        ),
      ),
    ) as DurableEvent.Usage
    assertEquals(1200L, event.usage.models.single().input)
    assertEquals("bash", event.usage.tools.single().name)
    assertEquals(24_000L, event.usage.context.used)
    assertEquals("1.2k in · 340 out · $0.02 · 12% of 200k", UsageReducer.summary(event.usage))
  }

  @Test fun theInboxFrameKeepsItsStringSubmissionIds() {
    val queued = JSONArray().put(JSONObject().put("id", "41").put("kind", "steer").put("text", "hold on").put("status", "queued"))
    val event = DurableEvent.parse(JSONObject().put("event", "inbox").put("value", value("queued" to queued))) as DurableEvent.Inbox
    assertEquals("41", event.inbox.queued.single().id)
    assertEquals("hold on", event.inbox.queued.single().text)
    assertEquals("1", InboxReducer.badge(event.inbox))
  }

  /** The exporter inlines the document and names no file: the name is derived from real fields. */
  @Test fun theExportFrameCarriesItsDocumentAndIsNamedForWhatCameBack() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "export").put(
        "value",
        value("format" to "markdown", "conversationId" to "7", "content" to "# Pi conversation 7", "bytes" to 19, "truncated" to true),
      ),
    ) as DurableEvent.Export
    assertEquals("markdown", event.export.format)
    assertEquals("# Pi conversation 7", event.export.text)
    assertEquals("conversation-7.md", event.export.filename)
    assertTrue(event.export.truncated)
    assertTrue(event.export.hasContent)
  }

  @Test fun anEncodedExportIsReadAsBytes() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "export").put(
        "value",
        value("format" to "jsonl", "filename" to "conversation-7.jsonl", "mime" to "application/jsonl", "base64" to "eyJyb2xlIjoi",
          "conversationId" to "7", "truncated" to false),
      ),
    ) as DurableEvent.Export
    assertEquals("conversation-7.jsonl", event.export.filename)
    assertEquals("application/jsonl", event.export.mime)
    assertEquals("eyJyb2xlIjoi", event.export.base64)
    assertEquals("", event.export.text)
    assertFalse(event.export.truncated)
  }

  @Test fun anActivationNamesTheConversationNowBeingRead() {
    val event = DurableEvent.parse(JSONObject().put("event", "session").put("conversationId", "9")) as DurableEvent.Switched
    assertEquals("9", event.id)
  }

  @Test fun aCompactionArrivesAsAnAdmissionAndThenAsItsOwnOutcome() {
    val admitted = DurableEvent.parse(
      JSONObject().put("event", "session.compacted").put("value", value("taskId" to "12", "status" to "admitted", "instructions" to "keep the api")),
    ) as DurableEvent.Compacted
    assertEquals("admitted", admitted.status)
    assertEquals("12", admitted.taskId)
    assertNull(admitted.error)

    val failed = DurableEvent.parse(
      JSONObject().put("event", "session.compacted").put("value", value("taskId" to "12", "status" to "failed", "error" to "no model")),
    ) as DurableEvent.Compacted
    assertEquals("failed", failed.status)
    assertEquals("no model", failed.error)

    val completed = DurableEvent.parse(
      JSONObject().put("event", "session.compacted").put("value", value("taskId" to "12", "status" to "completed", "entryId" to "21")),
    ) as DurableEvent.Compacted
    assertEquals("21", completed.entryId)
  }

  @Test fun theOperationsWithoutAFrameOfTheirOwnMarkTheSurfaceStale() {
    for (name in listOf("queued", "withdrawn", "session.reset", "written", "session.renamed")) {
      val event = DurableEvent.parse(JSONObject().put("event", name).put("value", value("id" to "1"))) as DurableEvent.SessionChanged
      assertEquals(name, event.name)
    }
  }

  @Test fun anUnrelatedEventIsNeverGuessedIntoARealOne() {
    // `switched` is not a frame this build knows (the real activation frame is `session`), and an
    // invented name matches none: neither is guessed into a product event. Both are surfaced as
    // [DurableEvent.Unknown] with their own type rather than dropped.
    val unrelated = DurableEvent.parse(JSONObject().put("event", "something.new")) as DurableEvent.Unknown
    assertEquals("something.new", unrelated.type)
    val switched = DurableEvent.parse(JSONObject().put("event", "switched")) as DurableEvent.Unknown
    assertEquals("switched", switched.type)
  }
}
