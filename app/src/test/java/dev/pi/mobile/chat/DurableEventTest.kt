package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DurableEventTest {
  private fun rows() = JSONArray()
    .put(JSONObject().put("role", "user").put("key", "1:user").put("text", "hi"))
    .put(
      JSONObject().put("role", "tool").put("key", "2:tool0")
        .put("toolCallId", "c1").put("name", "read").put("status", "ok").put("output", "body"),
    )

  @Test fun parsesTheTranscriptBearingEvents() {
    val history = DurableEvent.parse(JSONObject().put("event", "history").put("value", JSONObject().put("rows", rows()))) as DurableEvent.History
    assertEquals(2, history.rows.size)
    assertEquals("hi", (history.rows[0] as ChatEntry.User).text)
    assertEquals(ChatEntry.ToolStatus.Ok, (history.rows[1] as ChatEntry.Tool).status)

    val snapshot = DurableEvent.parse(JSONObject().put("event", "snapshot").put("value", JSONObject().put("rows", rows()))) as DurableEvent.Snapshot
    assertEquals(2, snapshot.rows.size)
  }

  @Test fun parsesLifecycleAndFailureEvents() {
    assertEquals(DurableEvent.Loaded("pi-durable", "1.1.0"), DurableEvent.parse(JSONObject().put("event", "loaded").put("runtime", "pi-durable").put("version", "1.1.0")))

    val ready = DurableEvent.parse(JSONObject().put("event", "ready").put("conversationId", 7).put("value", JSONObject())) as DurableEvent.Ready
    assertEquals(7L, ready.conversationId)

    val submitted = DurableEvent.parse(JSONObject().put("event", "submitted").put("requestId", "r1").put("submissionId", 8)) as DurableEvent.Submitted
    assertEquals("r1", submitted.requestId)
    assertEquals(8L, submitted.submissionId)

    val settled = DurableEvent.parse(JSONObject().put("event", "settled").put("requestId", "r1").put("value", JSONObject().put("status", "done"))) as DurableEvent.Settled
    assertEquals("done", settled.status)

    val failure = DurableEvent.parse(JSONObject().put("event", "error").put("operation", "init").put("message", "boom")) as DurableEvent.Failure
    assertEquals("boom", failure.message)
    assertEquals("init", failure.operation)
  }

  @Test fun messageFramesWithoutAProjectionAreNotEvents() {
    // The raw durable events no longer ride the frame: a frame carrying only them has nothing for
    // the UI to apply, so it must not surface as an event at all.
    assertNull(DurableEvent.parse(JSONObject().put("event", "message").put("events", JSONArray().put(JSONObject()).put(JSONObject()))))
    assertNull(DurableEvent.parse(JSONObject().put("event", "message").put("events", JSONArray())))
  }

  @Test fun messageFramesCarryTheIncrementalRowsTheUiMerges() {
    val message = DurableEvent.parse(
      JSONObject().put("event", "message")
        .put("events", JSONArray().put(JSONObject()))
        .put(
          "value",
          JSONObject()
            .put("full", false)
            .put("rows", JSONArray().put(JSONObject().put("role", "assistant").put("key", "live:text0").put("text", "Hel")))
            .put("removed", JSONArray().put("live:tool0")),
        ),
    ) as DurableEvent.Message
    assertFalse("an incremental frame is not a full replace", message.full)
    assertEquals("live:text0", message.rows.single().id)
    assertEquals("Hel", (message.rows.single() as ChatEntry.Assistant).markdown)
    assertEquals(listOf("live:tool0"), message.removed)

    // The seed frame is a snapshot: a full projection the UI replaces the transcript with.
    val seed = DurableEvent.parse(
      JSONObject().put("event", "message")
        .put("events", JSONArray().put(JSONObject()))
        .put("value", JSONObject().put("full", true).put("rows", rows())),
    ) as DurableEvent.Message
    assertTrue(seed.full)
    assertEquals(2, seed.rows.size)
    assertEquals("hi", (seed.rows[0] as ChatEntry.User).text)
    assertTrue(seed.removed.isEmpty())
  }

  @Test fun aMissingEventIsNotAnEventButAnUnrecognisedOneIsSurfaced() {
    // No `event` field at all is not a frame; an error with no operation still surfaces its
    // message; a value-less read degrades rather than crashing.
    assertNull(DurableEvent.parse(JSONObject()))
    // An error with no operation still surfaces its message.
    val failure = DurableEvent.parse(JSONObject().put("event", "error").put("message", "no op")) as DurableEvent.Failure
    assertNull(failure.operation)
    // Events whose value lacks rows degrade to an empty transcript, not a crash.
    assertTrue((DurableEvent.parse(JSONObject().put("event", "history")) as DurableEvent.History).rows.isEmpty())
    // A frame this build has no arm for is never guessed into a real product event, but it is not
    // dropped silently either — it is surfaced as [DurableEvent.Unknown] carrying its own type.
    val unknown = DurableEvent.parse(JSONObject().put("event", "something_new")) as DurableEvent.Unknown
    assertEquals("something_new", unknown.type)
  }

  @Test fun anUnrecognisedFrameIsSurfacedWithItsTypeAndRawPayload() {
    // The flow layer emits frames this build has no arm for (`flow.status`, `flow.step.*`, …). They
    // must reach the caller intact rather than vanishing at `parse`, so a frame the runtime emits
    // can never go silent.
    val event = DurableEvent.parse(
      JSONObject().put("event", "flow.status").put("value", JSONObject().put("threadId", "t1").put("state", "paused")),
    ) as DurableEvent.Unknown
    assertEquals("flow.status", event.type)
    assertTrue("the raw payload is preserved", event.payload.contains("\"state\""))
    assertTrue("including its value", event.payload.contains("paused"))
  }
}
