package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Transcript continuity: what a frame is allowed to delete.
 *
 * The reader's complaint is that the previous turn and its answer vanish when the next message is
 * sent. Two rules make that impossible:
 *
 *  - only a frame that is a whole-conversation snapshot replaces the transcript; every other frame
 *    upserts the rows it carries and cannot delete a row it does not carry;
 *  - a transient streaming row (the runtime's synthetic `live:*` key, reused by design on every
 *    turn) can never stand in for a committed row, and a committed row never takes a transient id.
 */
class DurableTranscriptContinuityTest {
  private val firstTurn = listOf(
    ChatEntry.User(id = "7:user", text = "first question"),
    ChatEntry.Assistant(id = "10:text0", markdown = "first answer"),
  )

  private fun ids(entries: List<ChatEntry>) = entries.map { it.id }

  @Test fun aSecondTurnIsAddedOnTopOfTheFirstAndBothTurnsSurvive() {
    var transcript = firstTurn
    transcript = DurableTranscriptReducer.applyMessage(transcript, rows = listOf(ChatEntry.User(id = "11:user", text = "second question")))
    transcript = DurableTranscriptReducer.applyMessage(transcript, rows = listOf(ChatEntry.Assistant(id = "14:text0", markdown = "second answer")))

    assertEquals(listOf("7:user", "10:text0", "11:user", "14:text0"), ids(transcript))
    assertEquals("first answer", (transcript[1] as ChatEntry.Assistant).markdown)
    assertEquals("second answer", (transcript[3] as ChatEntry.Assistant).markdown)
  }

  @Test fun aFullFrameThatIsNotAWholeConversationSnapshotCannotDeleteWhatItDoesNotCarry() {
    val prior = firstTurn + ChatEntry.User(id = "11:user", text = "second question")
    val next = DurableTranscriptReducer.applyMessage(
      prior,
      rows = listOf(ChatEntry.User(id = "11:user", text = "second question")),
      full = true,
      snapshot = false,
    )

    assertEquals(ids(prior), ids(next))
    assertEquals("first answer", (next[1] as ChatEntry.Assistant).markdown)
  }

  @Test fun aGenuineWholeConversationSnapshotStillReplaces() {
    val prior = firstTurn + ChatEntry.User(id = "11:user", text = "second question")
    val next = DurableTranscriptReducer.applyMessage(prior, rows = firstTurn, full = true, snapshot = true)

    assertEquals(ids(firstTurn), ids(next))
  }

  @Test fun aFrameCannotRemoveACommittedRowItDoesNotOwn() {
    val prior = listOf(ChatEntry.User(id = "7:user", text = "first question"))
    val next = DurableTranscriptReducer.applyMessage(prior, rows = emptyList(), removed = listOf("7:user"))

    assertEquals(listOf("7:user"), ids(next))
  }

  @Test fun aCommittedRowNeverAdoptsATransientIdFromThePriorTranscript() {
    // A prior transcript can hold a leftover transient tool row. A rebuild must not hand its id to
    // the committed row for the same call, or the next turn's transient row would overwrite it.
    val prior = listOf(
      ChatEntry.Tool(id = "live:tool0", toolCallId = "call-1", name = "read", arguments = "{}", status = ChatEntry.ToolStatus.Pending),
    )
    val rebuilt = listOf(
      ChatEntry.Tool(id = "9:tool0", toolCallId = "call-1", name = "read", arguments = "{}", status = ChatEntry.ToolStatus.Ok, output = "body"),
    )

    val merged = DurableTranscriptReducer.mergeEntries(prior, rebuilt)

    assertEquals(listOf("9:tool0"), ids(merged))
    assertEquals("body", (merged[0] as ChatEntry.Tool).output)
  }

  @Test fun aLaterTurnsTransientRowCannotReplaceACommittedRow() {
    // The shape an older build could leave behind: a committed tool row whose id is a transient key.
    val committed = listOf(
      ChatEntry.Tool(id = "live:tool0", toolCallId = "call-1", name = "read", status = ChatEntry.ToolStatus.Ok, output = "turn one body"),
    )

    val next = DurableTranscriptReducer.applyMessage(
      committed,
      rows = listOf(ChatEntry.Tool(id = "live:tool0", toolCallId = "call-2", name = "bash", status = ChatEntry.ToolStatus.Pending)),
    )

    val tool = next.single() as ChatEntry.Tool
    assertEquals("the committed call survives", "call-1", tool.toolCallId)
    assertEquals("turn one body", tool.output)
    assertEquals(ChatEntry.ToolStatus.Ok, tool.status)
  }

  @Test fun aTransientRowStillReplacesTheTransientRowItCarriesOnFrom() {
    val prior = listOf(
      ChatEntry.User(id = "7:user", text = "first question"),
      ChatEntry.Assistant(id = "live:text0", markdown = "First "),
    )
    val next = DurableTranscriptReducer.applyMessage(prior, rows = listOf(ChatEntry.Assistant(id = "live:text0", markdown = "First answer")))

    assertEquals("First answer", (next[1] as ChatEntry.Assistant).markdown)
  }

  @Test fun anIncompleteHistoryPageMergesAndACompleteOneReplaces() {
    val prior = firstTurn + listOf(
      ChatEntry.User(id = "11:user", text = "second question"),
      ChatEntry.Assistant(id = "14:text0", markdown = "second answer"),
    )

    // A page of a longer conversation: it carries the older rows and must not delete the newer ones.
    val merged = DurableTranscriptReducer.applyHistory(prior, firstTurn, complete = false)
    assertEquals(ids(prior), ids(merged))

    // A read that reached the conversation's first entry is authoritative and replaces.
    val replaced = DurableTranscriptReducer.applyHistory(prior, firstTurn, complete = true)
    assertEquals(ids(firstTurn), ids(replaced))
  }

  @Test fun anIncompleteHistoryPageStillRefreshesTheRowsItCarries() {
    val prior = listOf(ChatEntry.Assistant(id = "10:text0", markdown = "first ans"))
    val merged = DurableTranscriptReducer.applyHistory(prior, listOf(ChatEntry.Assistant(id = "10:text0", markdown = "first answer")), complete = false)

    assertEquals("first answer", (merged.single() as ChatEntry.Assistant).markdown)
  }

  @Test fun aHistoryFrameThatDidNotReachTheFirstEntryIsNotComplete() {
    val paged = DurableEvent.parse(
      JSONObject().put("event", "history").put(
        "value",
        JSONObject().put("rows", JSONArray()).put("next", JSONObject().put("after", 7)),
      ),
    ) as DurableEvent.History
    assertFalse("a page with a continuation is not the whole conversation", paged.complete)

    val whole = DurableEvent.parse(
      JSONObject().put("event", "history").put("value", JSONObject().put("rows", JSONArray())),
    ) as DurableEvent.History
    assertTrue("a read with no continuation is the whole conversation", whole.complete)
  }

  @Test fun theSeedFrameSaysItIsAWholeConversationSnapshot() {
    val message = DurableEvent.parse(
      JSONObject().put("event", "message").put(
        "value",
        JSONObject().put("rows", JSONArray()).put("full", true).put("snapshot", true),
      ),
    ) as DurableEvent.Message

    assertTrue(message.full)
    assertTrue(message.snapshot)
    assertFalse(
      (DurableEvent.parse(JSONObject().put("event", "message").put("value", JSONObject().put("rows", JSONArray()))) as DurableEvent.Message).snapshot,
    )
  }
}
