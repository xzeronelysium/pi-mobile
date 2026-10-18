package dev.pi.mobile.chat

/**
 * Which folded agent-work runs the reader has opened, plus the one-tool-per-row expansion used when
 * a group is opened.
 *
 * A work run's key is [TranscriptRow.key] (`work-<first entry id>`), which
 * [TranscriptGrouping.group] keeps stable while the run streams, so an open group stays open as its
 * tools arrive. Expanding a single tool row is a different, independent level: [ChatEntry.Tool.expanded]
 * travels with the entry and is preserved across history rebuilds by [DurableTranscriptReducer].
 *
 * Pure and free of Android dependencies, so every rule here is unit-tested off-device.
 */
data class TranscriptFoldState(
  val expandedWorkKeys: Set<String> = emptySet(),
) {
  fun isWorkExpanded(key: String): Boolean = key in expandedWorkKeys
}

object TranscriptFoldReducer {
  fun toggleWork(state: TranscriptFoldState, key: String): TranscriptFoldState =
    if (key in state.expandedWorkKeys) {
      state.copy(expandedWorkKeys = state.expandedWorkKeys - key)
    } else {
      state.copy(expandedWorkKeys = state.expandedWorkKeys + key)
    }

  /**
   * Drop keys for runs that are no longer in the transcript (the conversation switched, or the
   * run's first entry was replaced). Keeping the open set bounded to what is on screen.
   */
  fun reconcile(state: TranscriptFoldState, rows: List<TranscriptRow>): TranscriptFoldState {
    val live = rows.filterIsInstance<TranscriptRow.Work>().map { it.key }.toSet()
    val kept = state.expandedWorkKeys intersect live
    return if (kept.size == state.expandedWorkKeys.size) state else state.copy(expandedWorkKeys = kept)
  }

  /**
   * Flip one tool row's expanded flag, leaving every other row untouched. Returns a new list; the
   * caller's list is never mutated. An unknown [toolCallId] is a no-op.
   */
  fun toggleTool(entries: List<ChatEntry>, toolCallId: String): List<ChatEntry> {
    var changed = false
    val next = entries.map { entry ->
      if (entry is ChatEntry.Tool && entry.toolCallId == toolCallId) {
        changed = true
        entry.copy(expanded = !entry.expanded)
      } else {
        entry
      }
    }
    return if (changed) next else entries
  }

  /** The tool rows currently open, by call id — what reconciliation must preserve. */
  fun expandedToolIds(entries: List<ChatEntry>): Set<String> =
    entries.filterIsInstance<ChatEntry.Tool>().filter { it.expanded }.map { it.toolCallId }.toSet()
}
