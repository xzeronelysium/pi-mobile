package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGroupingTest {
  private fun user(id: String = "u") = ChatEntry.User(id = id, text = "hi")
  private fun assistant(id: String = "a") = ChatEntry.Assistant(id = id, markdown = "hello")
  private fun thinking(id: String) = ChatEntry.Thinking(id = id, text = "hmm")
  private fun tool(id: String, name: String = "bash", status: ChatEntry.ToolStatus = ChatEntry.ToolStatus.Ok) =
    ChatEntry.Tool(id = id, toolCallId = id, name = name, arguments = "{}", status = status)

  @Test
  fun consecutiveWorkFoldsIntoOneRow() {
    val rows = TranscriptGrouping.group(
      listOf(user("u1"), thinking("t1"), tool("c1"), thinking("t2"), assistant("a1")),
    )
    assertEquals(3, rows.size)
    assertEquals("u1", (rows[0] as TranscriptRow.Message).entry.id)
    val work = rows[1] as TranscriptRow.Work
    assertEquals("t1", work.id)
    assertEquals(3, work.entries.size)
    assertEquals("a1", (rows[2] as TranscriptRow.Message).entry.id)
  }

  @Test
  fun loneToolStaysInlineAndDoesNotFold() {
    val rows = TranscriptGrouping.group(listOf(user("u1"), tool("c1"), assistant("a1")))
    assertEquals(3, rows.size)
    assertTrue(rows.all { it is TranscriptRow.Message })
  }

  @Test
  fun separateRunsDoNotMergeAcrossMessages() {
    val rows = TranscriptGrouping.group(
      listOf(tool("c1"), tool("c2"), assistant("a1"), tool("c3"), tool("c4")),
    )
    assertEquals(3, rows.size)
    assertEquals(listOf("c1", "c2"), (rows[0] as TranscriptRow.Work).entries.map { it.id })
    assertEquals("a1", (rows[1] as TranscriptRow.Message).entry.id)
    assertEquals(listOf("c3", "c4"), (rows[2] as TranscriptRow.Work).entries.map { it.id })
  }

  @Test
  fun labelCountsToolsAndThinking() {
    assertEquals("2 tools · 1 thinking", TranscriptGrouping.label(listOf(tool("a"), tool("b"), thinking("c"))))
    assertEquals("1 tool", TranscriptGrouping.label(listOf(tool("a"))))
    assertEquals("work", TranscriptGrouping.label(emptyList()))
  }

  @Test
  fun errorAndRunningFlagsReflectFoldedEntries() {
    val ok = listOf(tool("a"), tool("b"))
    assertFalse(TranscriptGrouping.hasError(ok))
    assertFalse(TranscriptGrouping.isRunning(ok))

    val bad = listOf(tool("a"), tool("b", status = ChatEntry.ToolStatus.Error))
    assertTrue(TranscriptGrouping.hasError(bad))

    // Adapted to the durable model: ChatEntry.Thinking carries no streaming flag, so an in-flight
    // run is detected from tool status (Pending/Running) only.
    val live = listOf(thinking("t"), tool("c", status = ChatEntry.ToolStatus.Running))
    assertTrue(TranscriptGrouping.isRunning(live))
    assertFalse(TranscriptGrouping.isRunning(listOf(thinking("t"))))
  }

  @Test
  fun toolPreviewListsNamesAndTruncates() {
    val entries = listOf(tool("a", "bash"), tool("b", "read"), tool("c", "write"), tool("d", "grep"), tool("e", "ls"))
    assertEquals("bash, read, write, grep +1", TranscriptGrouping.toolPreview(entries))
    assertEquals("", TranscriptGrouping.toolPreview(listOf(thinking("t"))))
  }

  @Test
  fun keysAreStableAcrossStreamingRegroup() {
    val before = TranscriptGrouping.group(listOf(user("u1"), tool("c1"), tool("c2")))
    val after = TranscriptGrouping.group(listOf(user("u1"), tool("c1"), tool("c2"), tool("c3")))
    assertEquals(before[1].key, after[1].key)
  }
}
