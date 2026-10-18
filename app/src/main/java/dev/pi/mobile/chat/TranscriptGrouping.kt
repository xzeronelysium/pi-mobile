package dev.pi.mobile.chat

/**
 * One row in the transcript canvas after collapsing consecutive agent work.
 *
 * Agent work is any run of [ChatEntry.Thinking] / [ChatEntry.Tool] rows. Collapsing them into a
 * single [Work] row keeps the conversation readable: the prose stays open, the machinery folds.
 */
sealed interface TranscriptRow {
  /** Stable identity for LazyColumn keys; survives regrouping while streaming. */
  val key: String

  /** A message row that always renders open (user turn, assistant prose, system notice). */
  data class Message(val entry: ChatEntry) : TranscriptRow {
    override val key: String get() = entry.id
  }

  /** A folded run of thinking/tool rows. [id] is the first entry's id, stable per run. */
  data class Work(val id: String, val entries: List<ChatEntry>) : TranscriptRow {
    override val key: String get() = "work-$id"
  }
}

/** Pure transcript → display-row folding. No Android dependencies so it stays unit-testable. */
object TranscriptGrouping {
  /** Rows that count as agent machinery and fold into a [TranscriptRow.Work]. */
  private fun isWork(entry: ChatEntry): Boolean =
    entry is ChatEntry.Thinking || entry is ChatEntry.Tool

  fun group(entries: List<ChatEntry>): List<TranscriptRow> {
    val rows = ArrayList<TranscriptRow>(entries.size)
    var run = ArrayList<ChatEntry>()
    // A work run only folds when it holds more than one row; a lone tool reads better inline.
    fun flush() {
      if (run.isEmpty()) return
      if (run.size == 1) rows.add(TranscriptRow.Message(run.first()))
      else rows.add(TranscriptRow.Work(id = run.first().id, entries = run))
      run = ArrayList()
    }
    for (entry in entries) {
      if (isWork(entry)) {
        run.add(entry)
      } else {
        flush()
        rows.add(TranscriptRow.Message(entry))
      }
    }
    flush()
    return rows
  }

  /** Counts for the collapsed header, e.g. `3 tools · 2 thinking`. */
  fun label(entries: List<ChatEntry>): String {
    if (entries.isEmpty()) return "work"
    val tools = entries.count { it is ChatEntry.Tool }
    val thinking = entries.count { it is ChatEntry.Thinking }
    val parts = ArrayList<String>(2)
    if (tools > 0) parts.add(if (tools == 1) "1 tool" else "$tools tools")
    if (thinking > 0) parts.add(if (thinking == 1) "1 thinking" else "$thinking thinking")
    return parts.joinToString(" · ").ifEmpty { "work" }
  }

  /** True when any folded tool failed, so the collapsed header can flag it without expanding. */
  fun hasError(entries: List<ChatEntry>): Boolean =
    entries.any { it is ChatEntry.Tool && it.status == ChatEntry.ToolStatus.Error }

  /**
   * True while the folded run still has work in flight.
   *
   * Adapted to the durable model: [ChatEntry.Thinking] carries no streaming flag today, so
   * in-flight work is detected from tool status (Pending/Running) only.
   */
  fun isRunning(entries: List<ChatEntry>): Boolean =
    entries.any {
      it is ChatEntry.Tool &&
        (it.status == ChatEntry.ToolStatus.Pending || it.status == ChatEntry.ToolStatus.Running)
    }

  /** Tool names only, in order, for the collapsed preview line. */
  fun toolPreview(entries: List<ChatEntry>, max: Int = 4): String {
    val names = entries.filterIsInstance<ChatEntry.Tool>().map { it.name }.filter { it.isNotBlank() }
    if (names.isEmpty()) return ""
    val head = names.take(max).joinToString(", ")
    return if (names.size > max) "$head +${names.size - max}" else head
  }
}
