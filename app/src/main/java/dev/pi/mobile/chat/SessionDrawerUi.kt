package dev.pi.mobile.chat

/**
 * The history drawer's own state: whether it is open, what is typed into search, and which section
 * of it is expanded.
 *
 * Two rules matter and both are tested in `SessionDrawerUiTest`:
 *
 * 1. The drawer opens only from the history glyph. [SessionDrawerReducer.open] takes the intent as
 *    an argument (`fromTreeAction`) because a tree/fork call path is the only thing that wants the
 *    tree section already expanded — the flag is *not* persisted: [SessionDrawerReducer.close]
 *    drops it, so the next open from the glyph is collapsed again.
 * 2. The session list is not re-derived here. Rows, ordering, search and the relative-age text all
 *    come from the sibling [SessionListReducer]; these functions only decide what to ask it for.
 */
data class SessionDrawerState(
  val open: Boolean = false,
  val query: String = "",
  val treeExpanded: Boolean = false,
  val overflowOpen: Boolean = false,
  /** Session whose rename dialog is open, if any. */
  val renamingId: String? = null,
  /** The name typed into that dialog. */
  val renaming: String = "",
)

object SessionDrawerReducer {
  fun open(state: SessionDrawerState, fromTreeAction: Boolean): SessionDrawerState =
    state.copy(open = true, treeExpanded = fromTreeAction, overflowOpen = false)

  /** Closing also abandons the auto-expand intent and any open rename. */
  fun close(state: SessionDrawerState): SessionDrawerState = state.copy(
    open = false,
    treeExpanded = false,
    overflowOpen = false,
    renamingId = null,
    renaming = "",
  )

  fun setQuery(state: SessionDrawerState, query: String): SessionDrawerState = state.copy(query = query)

  fun toggleTree(state: SessionDrawerState): SessionDrawerState = state.copy(treeExpanded = !state.treeExpanded)

  fun setOverflow(state: SessionDrawerState, open: Boolean): SessionDrawerState = state.copy(overflowOpen = open)

  fun startRename(state: SessionDrawerState, row: SessionRow): SessionDrawerState =
    state.copy(overflowOpen = false, renamingId = row.id, renaming = row.name)

  fun setRenaming(state: SessionDrawerState, text: String): SessionDrawerState = state.copy(renaming = text)

  fun cancelRename(state: SessionDrawerState): SessionDrawerState = state.copy(renamingId = null, renaming = "")

  /** The rows to render: the sibling reducer's ordering with its client-side name search applied. */
  fun rows(list: SessionList, query: String): List<SessionRow> = SessionListReducer.search(list, query)

  /**
   * The list banded by age — Today / Yesterday / This week / Older — with the search applied first.
   * The bands are computed from each row's epoch milliseconds, not from a parsed timestamp string.
   */
  fun groups(list: SessionList, query: String, now: Long): List<SessionGroup> =
    SessionListReducer.grouped(rows(list, query), now)

  /** A row's secondary line: message count and relative age, never a raw ISO timestamp. */
  fun rowDetail(row: SessionRow, now: Long): String = SessionListReducer.detail(row, now)

  /** Switching would race the run in flight, and re-opening the current session does nothing. */
  fun canSwitch(busy: Boolean, row: SessionRow): Boolean = !busy && !row.current

  /** Export needs a current session and an idle runtime; the host exports the active one. */
  fun canExport(busy: Boolean, list: SessionList): Boolean = !busy && list.currentId != null

  /** The tree section renders nothing until it is expanded. */
  fun treeRows(expanded: Boolean, tree: ConversationTree?): List<TreeNodeRow> =
    if (expanded) tree?.rows.orEmpty() else emptyList()

  /** A fork needs the visible entry it forks at. */
  fun canFork(busy: Boolean, node: TreeNodeRow): Boolean = !busy && !node.entryId.isNullOrBlank()

  fun currentName(list: SessionList): String? = list.current?.name?.takeIf { it.isNotBlank() }

  /** The workspace line names the directory the tools actually run in, and says so when it is ours. */
  fun workspaceLine(label: String?, allFilesAccess: Boolean): String =
    (label ?: "app private") + if (allFilesAccess) "" else " · app-private"
}
