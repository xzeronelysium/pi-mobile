package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history drawer's own decisions: when it may open, when the tree section is already expanded,
 * and what a row shows. The list itself is the sibling [SessionListReducer]'s projection, so these
 * tests assert the drawer calls it rather than re-deriving sessions.
 */
class SessionDrawerUiTest {
  private val hour = 3_600_000L

  private fun session(
    id: String,
    name: String = id,
    modified: Long = 0L,
    count: Int = 0,
    current: Boolean = false,
  ): SessionRow = SessionRow(id = id, name = name, modified = modified, messageCount = count, current = current)

  private fun tree(): ConversationTree = ConversationTree(
    listOf(
      TreeNodeRow("n1", parentId = null, entryId = "1", depth = 0, title = "Root", current = true),
      TreeNodeRow("n2", parentId = "n1", entryId = "2", depth = 1, title = "Child"),
    ),
  )

  @Test fun theGlyphOpensTheDrawerWithTheTreeStillCollapsed() {
    val state = SessionDrawerReducer.open(SessionDrawerState(), fromTreeAction = false)
    assertTrue(state.open)
    assertFalse(state.treeExpanded)
  }

  /** Only a tree/fork call path asks for the expanded section; the glyph never does. */
  @Test fun openingFromATreeOrForkActionAutoExpandsTheTree() {
    assertTrue(SessionDrawerReducer.open(SessionDrawerState(), fromTreeAction = true).treeExpanded)
  }

  /**
   * The auto-expand flag is passed in at open time and dies with the drawer: it is not drawer state
   * that survives a close, so the next open from the glyph is collapsed again.
   */
  @Test fun theAutoExpandFlagIsNotPersistedAcrossOpens() {
    val closed = SessionDrawerReducer.close(SessionDrawerReducer.open(SessionDrawerState(), fromTreeAction = true))
    assertFalse(closed.open)
    assertFalse(closed.treeExpanded)
    assertFalse(SessionDrawerReducer.open(closed, fromTreeAction = false).treeExpanded)
  }

  @Test fun closingDropsTheOverflowMenuAndTheRenameTarget() {
    val open = SessionDrawerReducer.open(SessionDrawerState(), fromTreeAction = false)
    val busy = SessionDrawerReducer.startRename(SessionDrawerReducer.setOverflow(open, true), session("a"))
    val closed = SessionDrawerReducer.close(busy)
    assertFalse(closed.overflowOpen)
    assertEquals(null, closed.renamingId)
  }

  @Test fun theTreeToggleFlipsTheFlagAndNothingElse() {
    val open = SessionDrawerReducer.open(SessionDrawerState(), fromTreeAction = false)
    assertTrue(SessionDrawerReducer.toggleTree(open).treeExpanded)
    assertFalse(SessionDrawerReducer.toggleTree(SessionDrawerReducer.toggleTree(open)).treeExpanded)
  }

  @Test fun rowsAreTheReducersSearchAppliedToItsOrder() {
    // The list arrives already ordered by the reducer: current first, then most recently modified.
    val list = SessionList(
      currentId = "b",
      rows = SessionListReducer.order(
        listOf(session("a", "Alpha", 10L), session("b", "Beta", 5L), session("c", "Gamma", 99L)),
        currentId = "b",
      ),
    )
    assertEquals(listOf("b", "c", "a"), SessionDrawerReducer.rows(list, "").map { it.id })
    assertEquals(listOf("c"), SessionDrawerReducer.rows(list, "gam").map { it.id })
    assertEquals(emptyList<String>(), SessionDrawerReducer.rows(list, "nothing").map { it.id })
  }

  /** Relative age and message count are computed in Kotlin; a raw ISO timestamp never reaches a row. */
  @Test fun aRowShowsTheAgeThenTheCountAndNeverAnIsoTimestamp() {
    val row = session("a", "Alpha", modified = hour, count = 3)
    val detail = SessionDrawerReducer.rowDetail(row, now = 3 * hour)
    assertEquals("2h ago · 3 messages", detail)
    assertFalse(detail.contains("T"))
    assertFalse(detail.contains(":"))
    assertFalse(detail.contains("-"))
  }

  @Test fun aRowWithoutATimestampPromisesNothing() {
    assertEquals("0 messages", SessionDrawerReducer.rowDetail(session("a", "Alpha"), now = 10_000L))
  }

  /** The list the panel draws is banded by age, with the search applied before the banding. */
  @Test fun theListIsBandedByAgeAfterTheSearch() {
    val now = 30 * 24 * hour
    val list = SessionList(
      currentId = null,
      rows = listOf(
        session("a", "Alpha", modified = now - hour),
        session("b", "Beta", modified = now - 30 * hour),
        session("c", "Gamma", modified = now - 3 * 24 * hour),
      ),
    )
    val groups = SessionDrawerReducer.groups(list, query = "", now = now)
    assertEquals(listOf("Today", "Yesterday", "This week"), groups.map { it.header })
    assertEquals(listOf("b"), SessionDrawerReducer.groups(list, query = "bet", now = now).single().rows.map { it.id })
    assertTrue(SessionDrawerReducer.groups(list, query = "nothing", now = now).isEmpty())
  }

  @Test fun switchingIsRefusedForTheCurrentSessionAndWhileBusy() {
    assertFalse(SessionDrawerReducer.canSwitch(busy = false, row = session("a", current = true)))
    assertFalse(SessionDrawerReducer.canSwitch(busy = true, row = session("a")))
    assertTrue(SessionDrawerReducer.canSwitch(busy = false, row = session("a")))
  }

  @Test fun exportsNeedACurrentSessionAndAnIdleRuntime() {
    val list = SessionList(currentId = "a", rows = listOf(session("a")))
    assertTrue(SessionDrawerReducer.canExport(busy = false, list = list))
    assertFalse(SessionDrawerReducer.canExport(busy = true, list = list))
    assertFalse(SessionDrawerReducer.canExport(busy = false, list = SessionList()))
  }

  @Test fun theTreeSectionRendersNothingUntilItIsExpanded() {
    assertTrue(SessionDrawerReducer.treeRows(expanded = false, tree = tree()).isEmpty())
    assertEquals(2, SessionDrawerReducer.treeRows(expanded = true, tree = tree()).size)
  }

  @Test fun forkingNeedsAnEntryIdAndAnIdleRuntime() {
    val leaf = TreeNodeRow("n2", parentId = "n1", entryId = "2", depth = 1, title = "Child")
    val rootOnly = TreeNodeRow("n1", parentId = null, entryId = null, depth = 0, title = "Root")
    assertTrue(SessionDrawerReducer.canFork(busy = false, node = leaf))
    assertFalse(SessionDrawerReducer.canFork(busy = false, node = rootOnly))
    assertFalse(SessionDrawerReducer.canFork(busy = true, node = leaf))
  }

  @Test fun theCurrentNameFallsBackToTheWorkspaceLineRatherThanBlank() {
    assertEquals("Beta", SessionDrawerReducer.currentName(SessionList(currentId = "b", rows = listOf(session("b", "Beta")))))
    assertEquals(null, SessionDrawerReducer.currentName(SessionList()))
  }

  @Test fun workspaceLineNamesTheDirectoryToolsActuallyRunIn() {
    assertEquals("/home/u/workspace", SessionDrawerReducer.workspaceLine("/home/u/workspace", allFilesAccess = true))
    assertEquals("app private · app-private", SessionDrawerReducer.workspaceLine(null, allFilesAccess = false))
  }
}
