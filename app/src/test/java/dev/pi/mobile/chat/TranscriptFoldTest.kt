package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFoldTest {
  private fun tool(id: String, expanded: Boolean = false) =
    ChatEntry.Tool(id = id, toolCallId = id, name = "bash", arguments = "{}", expanded = expanded)

  @Test
  fun aWorkRunTogglesOpenAndClosedByItsStableKey() {
    var state = TranscriptFoldState()
    assertFalse(state.isWorkExpanded("work-c1"))
    state = TranscriptFoldReducer.toggleWork(state, "work-c1")
    assertTrue(state.isWorkExpanded("work-c1"))
    state = TranscriptFoldReducer.toggleWork(state, "work-c1")
    assertFalse(state.isWorkExpanded("work-c1"))
  }

  @Test
  fun reconcileDropsKeysForRunsThatAreNoLongerInTheTranscript() {
    val rows = TranscriptGrouping.group(listOf(tool("c1"), tool("c2"), ChatEntry.Assistant(id = "a", markdown = "hi")))
    val state = TranscriptFoldState(expandedWorkKeys = setOf("work-c1", "work-gone"))
    val next = TranscriptFoldReducer.reconcile(state, rows)
    assertTrue(next.isWorkExpanded("work-c1"))
    assertFalse(next.isWorkExpanded("work-gone"))
  }

  @Test
  fun aToolRowOpensIndependentlyOfTheGroupThatHoldsIt() {
    val entries = listOf(tool("c1"), tool("c2"))
    val toggled = TranscriptFoldReducer.toggleTool(entries, "c2")
    assertEquals(false, toggled[0].let { (it as ChatEntry.Tool).expanded })
    assertEquals(true, toggled[1].let { (it as ChatEntry.Tool).expanded })
    // A new list is returned; the caller's list is untouched.
    assertFalse((entries[1] as ChatEntry.Tool).expanded)
    assertNotSame(entries, toggled)

    val closed = TranscriptFoldReducer.toggleTool(toggled, "c2")
    assertEquals(false, (closed[1] as ChatEntry.Tool).expanded)
  }

  @Test
  fun togglingAnUnknownToolIsANoOp() {
    val entries = listOf(tool("c1"), ChatEntry.User(id = "u", text = "hi"))
    assertEquals(entries, TranscriptFoldReducer.toggleTool(entries, "missing"))
  }

  @Test
  fun expandedToolIdsReadsTheRowsTheReducerKeepsAcrossRebuilds() {
    val entries = listOf(tool("c1", expanded = true), tool("c2"), ChatEntry.Thinking(id = "t", text = "hmm"))
    assertEquals(setOf("c1"), TranscriptFoldReducer.expandedToolIds(entries))
  }
}
