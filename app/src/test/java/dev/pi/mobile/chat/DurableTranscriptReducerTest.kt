package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DurableTranscriptReducerTest {
  private fun rows(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }

  private fun row(role: String, key: String, vararg pairs: Pair<String, Any>): JSONObject =
    JSONObject().put("role", role).put("key", key).apply { pairs.forEach { (k, v) -> put(k, v) } }

  @Test fun mapsEveryDurableRoleOntoATranscriptEntry() {
    val entries = DurableTranscriptReducer.fromRows(
      rows(
        row("user", "1:user", "text" to "read data.txt"),
        row("thinking", "2:think0", "text" to "considering"),
        row("assistant", "2:text1", "text" to "working"),
        row("tool", "2:tool2", "toolCallId" to "call-1", "name" to "read", "arguments" to "{\"path\":\"data.txt\"}", "status" to "ok", "output" to "hello"),
        row("notice", "3:notice", "text" to "Context compacted."),
      ),
    )

    assertTrue(entries[0] is ChatEntry.User)
    assertEquals("read data.txt", (entries[0] as ChatEntry.User).text)
    // Row keys become ids, so Compose state survives a rebuild.
    assertEquals("1:user", entries[0].id)

    assertEquals("considering", (entries[1] as ChatEntry.Thinking).text)
    assertEquals("working", (entries[2] as ChatEntry.Assistant).markdown)

    val tool = entries[3] as ChatEntry.Tool
    assertEquals("call-1", tool.toolCallId)
    assertEquals("read", tool.name)
    assertEquals("{\"path\":\"data.txt\"}", tool.arguments)
    assertEquals(ChatEntry.ToolStatus.Ok, tool.status)
    assertEquals("hello", tool.output)

    assertEquals("Context compacted.", (entries[4] as ChatEntry.SystemNotice).text)
  }

  @Test fun toolStatusesAndMissingFieldsDegradeSafely() {
    val entries = DurableTranscriptReducer.fromRows(
      rows(
        row("tool", "a", "status" to "error", "output" to "boom"),
        row("tool", "b", "status" to "running"),
        row("tool", "c"),
        row("something-new", "d", "text" to "surprise"),
      ),
    )

    assertEquals(ChatEntry.ToolStatus.Error, (entries[0] as ChatEntry.Tool).status)
    assertEquals("boom", (entries[0] as ChatEntry.Tool).output)
    assertEquals(ChatEntry.ToolStatus.Running, (entries[1] as ChatEntry.Tool).status)
    assertEquals(ChatEntry.ToolStatus.Pending, (entries[2] as ChatEntry.Tool).status)
    // A tool with no name still renders as a tool, not a blank row.
    assertEquals("tool", (entries[2] as ChatEntry.Tool).name)
    // An unrecognised role degrades to a visible notice rather than vanishing.
    assertEquals("surprise", (entries[3] as ChatEntry.SystemNotice).text)
  }

  @Test fun mergePreservesExpandedToolsAndLocalNotices() {
    val prior = listOf(
      ChatEntry.Tool(id = "local-tool-id", toolCallId = "call-1", name = "read", arguments = "{}", status = ChatEntry.ToolStatus.Ok, output = "old", expanded = true),
      ChatEntry.SystemNotice(id = "local-notice", text = "Queued follow-up"),
    )

    val merged = DurableTranscriptReducer.mergeEntries(
      prior,
      DurableTranscriptReducer.fromRows(
        rows(
          row("user", "1:user", "text" to "go"),
          row("tool", "2:tool0", "toolCallId" to "call-1", "name" to "read", "status" to "ok", "output" to "fresh"),
        ),
      ),
    )

    val tool = merged.first { it is ChatEntry.Tool } as ChatEntry.Tool
    assertEquals("fresh", tool.output)
    assertTrue("expanded state survives the rebuild", tool.expanded)
    assertEquals("prior id is reused so the row does not remount", "local-tool-id", tool.id)

    assertTrue(merged.any { it is ChatEntry.SystemNotice && it.text == "Queued follow-up" })
  }

  @Test fun emptyOrNullRowsProduceNoEntries() {
    assertTrue(DurableTranscriptReducer.fromRows(null).isEmpty())
    assertTrue(DurableTranscriptReducer.fromRows(JSONArray()).isEmpty())
    // Local notices still survive a merge against no rows at all.
    val notices = DurableTranscriptReducer.mergeEntries(listOf(ChatEntry.SystemNotice(text = "hi")), emptyList())
    assertEquals(1, notices.size)
  }

  @Test fun anIncrementalFrameUpsertsItsOwnRowAndLeavesTheRestUntouched() {
    val prior = listOf(
      ChatEntry.User(id = "1:user", text = "read data.txt"),
      ChatEntry.Assistant(id = "live:text0", markdown = "Hel"),
    )

    val next = DurableTranscriptReducer.applyMessage(
      prior,
      rows = listOf(ChatEntry.Assistant(id = "live:text0", markdown = "Hello")),
    )

    assertEquals(2, next.size)
    // The streamed row is updated in place; nothing else is rebuilt.
    assertSame("the untouched user row is the very same instance", prior[0], next[0])
    assertEquals("Hello", (next[1] as ChatEntry.Assistant).markdown)
    assertEquals("live:text0", next[1].id)
  }

  @Test fun anIncrementalToolResultFoldsOntoTheCallRowItAnswers() {
    val started = DurableTranscriptReducer.applyMessage(
      emptyList(),
      rows = listOf(
        ChatEntry.Tool(id = "5:tool0", toolCallId = "call-1", name = "read", arguments = "{\"path\":\"data.txt\"}", status = ChatEntry.ToolStatus.Pending, expanded = true),
      ),
    )
    assertEquals(1, started.size)

    // The tool result arrives as a later frame naming the same durable row.
    val folded = DurableTranscriptReducer.applyMessage(
      started,
      rows = listOf(
        ChatEntry.Tool(id = "5:tool0", toolCallId = "call-1", name = "read", arguments = "{\"path\":\"data.txt\"}", status = ChatEntry.ToolStatus.Ok, output = "hello"),
      ),
    )

    val tools = folded.filterIsInstance<ChatEntry.Tool>()
    assertEquals("the result attaches to the call's row, it does not open a second one", 1, tools.size)
    assertEquals("5:tool0", tools[0].id)
    assertEquals("call-1", tools[0].toolCallId)
    assertEquals(ChatEntry.ToolStatus.Ok, tools[0].status)
    assertEquals("hello", tools[0].output)
    // Expanded state is UI-local and survives the upsert.
    assertTrue(tools[0].expanded)
  }

  @Test fun anAppendedEntryAppearsWithoutRebuildingTheTranscript() {
    val prior = listOf(ChatEntry.User(id = "1:user", text = "go"))
    val next = DurableTranscriptReducer.applyMessage(prior, rows = listOf(ChatEntry.SystemNotice(id = "9:notice", text = "Context compacted.")))
    assertEquals(2, next.size)
    assertSame(prior[0], next[0])
    assertEquals("9:notice", next[1].id)
  }

  @Test fun aFrameWithNothingToApplyIsNotRedundantWork() {
    val prior = listOf(ChatEntry.User(id = "1:user", text = "go"))
    // A batch that changed no row hands the caller the very same list back.
    assertSame(prior, DurableTranscriptReducer.applyMessage(prior, rows = emptyList()))
    // A transient streaming row retired in the same frame never lands at all.
    val retired = DurableTranscriptReducer.applyMessage(
      prior,
      rows = listOf(ChatEntry.Assistant(id = "live:text0", markdown = "x")),
      removed = listOf("live:text0"),
    )
    assertEquals(prior, retired)
  }

  @Test fun theIncrementalTranscriptEqualsTheFullHistoryProjectionOnceSettled() {
    // The same conversation read two ways: a whole-history projection, and the incremental frames
    // the streaming turn emits. Once the turn settles the two must be row-for-row identical.
    val userRow = row("user", "1:user", "text" to "read data.txt")
    val answerRow = row("assistant", "2:text0", "text" to "It says hello.")
    val liveRow = row("assistant", "live:text0", "text" to "It says")
    val full = DurableTranscriptReducer.fromRows(rows(userRow, answerRow))

    var transcript: List<ChatEntry> = emptyList()
    transcript = DurableTranscriptReducer.applyMessage(transcript, DurableTranscriptReducer.fromRows(rows(userRow)))
    assertEquals(1, transcript.size)
    transcript = DurableTranscriptReducer.applyMessage(transcript, DurableTranscriptReducer.fromRows(rows(liveRow)))
    transcript = DurableTranscriptReducer.applyMessage(
      transcript,
      DurableTranscriptReducer.fromRows(rows(row("assistant", "live:text0", "text" to "It says hello."))),
    )
    assertEquals("the streamed row carries the partial, not the final answer", "It says hello.", (transcript[1] as ChatEntry.Assistant).markdown)
    transcript = DurableTranscriptReducer.applyMessage(
      transcript,
      DurableTranscriptReducer.fromRows(rows(answerRow)),
      removed = listOf("live:text0"),
    )

    assertEquals(full, transcript)
  }
}
