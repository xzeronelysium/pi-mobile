package dev.pi.mobile.chat

import java.util.Locale

/**
 * The decisions the queue, compact, notice and rename dialogs make before they call anywhere.
 *
 * Steering and follow-up are the two ways input joins a busy conversation; this file only decides
 * what is sendable and what the call carries. The queue itself is the sibling [InboxReducer]'s.
 */
object SessionDialogsReducer {
  /** Renaming to the name it already has, or to nothing, is not a change. */
  fun canRename(original: String, draft: String): Boolean {
    val trimmed = draft.trim()
    return trimmed.isNotEmpty() && trimmed != original.trim()
  }

  fun renameArg(draft: String): String = draft.trim()

  /** Compaction needs the runtime ready and no run in flight. */
  fun canCompact(busy: Boolean, ready: Boolean): Boolean = ChatRunReducer.canCompact(busy, ready)

  /** Optional instructions: blank means "no instructions", not an empty string sent upstream. */
  fun compactNote(instructions: String): String? = instructions.trim().takeIf { it.isNotEmpty() }

  /** One labelled band of the queue dialog, in the order the dialog renders them. */
  data class QueueSection(val title: String, val rows: List<QueuedInput>)

  /**
   * The queue split into the two ways input joins a running conversation — `Steering` and
   * `Follow-up` — with both bands always present so the dialog names both, even when one is empty.
   * A row whose kind names neither is listed under Steering, the default join.
   */
  fun queueSections(inbox: InboxState): List<QueueSection> {
    val steering = ArrayList<QueuedInput>()
    val followUps = ArrayList<QueuedInput>()
    for (item in inbox.queued) {
      if (item.kind.lowercase(Locale.US).contains("follow")) followUps.add(item) else steering.add(item)
    }
    return listOf(QueueSection("Steering", steering), QueueSection("Follow-up", followUps))
  }

  fun withdraw(inbox: InboxState, id: String): InboxState = InboxReducer.withdraw(inbox, id)
}

/**
 * Where an export lands and what it is called.
 *
 * The host may hand back a ready-made name or only the document itself, so the name is either taken
 * as given (flattened to one path segment, so a name from the runtime can never escape the exports
 * directory) or derived from the conversation id and the format actually returned.
 */
object SessionExportUi {
  fun extension(format: String): String = when (format.lowercase(Locale.US)) {
    "html", "htm" -> "html"
    "jsonl" -> "jsonl"
    "json" -> "json"
    "markdown", "md" -> "md"
    else -> "txt"
  }

  fun safeFileName(name: String?, format: String, conversationId: String = "conversation"): String {
    val cleaned = name.orEmpty().trim()
      .substringAfterLast('/')
      .substringAfterLast('\\')
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .trimStart('.')
    if (cleaned.isNotEmpty() && cleaned != "_") return cleaned
    val id = conversationId.trim().ifEmpty { "conversation" }
    return "$id.${extension(format)}"
  }
}
