package dev.pi.mobile.chat

/** The dock's slots, in the order they are laid out left to right. */
enum class ComposerDockSlot { Model, Effort, Usage, Queue, Picker }

/**
 * What the composer dock shows: the current model and effort, the session's usage, and the queue.
 *
 * The dock is a layout contract as much as a data holder. Both the history glyph in the header and
 * the picker control at the end of the dock hang off *one* right-edge axis ([ICON_AXIS_DP]), so the
 * two glyphs sit on the same vertical line; the model label is capped with `widthIn` rather than
 * `weight(fill = false)` so the remaining width goes to usage and the picker still reaches the
 * edge. Usage text and the queue badge are the sibling reducers' own strings — nothing about cost,
 * tokens or queue counts is re-worded here.
 */
data class ComposerDockState(
  val model: String = "",
  val effort: String = "",
  val usage: UsageState = UsageState(),
  val inbox: InboxState = InboxState(),
)

object ComposerDockReducer {
  /** Inset of any dock/header icon's *centre* from the screen's right edge. */
  const val ICON_AXIS_DP = 24

  /** The history glyph and the picker control are drawn at the same size as the logo. */
  const val ICON_SIZE_DP = 16

  /** The model label is capped, never given a weight that would squeeze the usage line out. */
  const val MODEL_MAX_WIDTH_DP = 140

  /** Horizontal padding an icon needs inside [rowEndPaddingDp] so its centre lands on the axis. */
  fun iconPaddingDp(rowEndPaddingDp: Int): Int = ICON_AXIS_DP - rowEndPaddingDp - ICON_SIZE_DP / 2

  fun dockSlots(): List<ComposerDockSlot> = listOf(
    ComposerDockSlot.Model,
    ComposerDockSlot.Effort,
    ComposerDockSlot.Usage,
    ComposerDockSlot.Queue,
    ComposerDockSlot.Picker,
  )

  fun modelLabel(model: String): String = model.trim().ifEmpty { "No model" }

  /** Tokens, cost and context pressure in the approved footer vocabulary. */
  fun usageText(usage: UsageState): String = UsageReducer.footer(usage)

  /**
   * The one-line dock projection: total tokens and context share, or cost when the window is
   * unknown. The full footer stays available to tests and to the context surface.
   */
  fun compactUsage(usage: UsageState): String {
    val input = usage.models.sumOf { it.input }
    val output = usage.models.sumOf { it.output }
    val total = input + output
    val percent = UsageReducer.contextPercent(usage.context)
    val cost = usage.models.sumOf { it.cost }
    return when {
      total > 0L && percent != null -> "${UsageReducer.tokenLabel(total)} · $percent%"
      total > 0L && cost > 0.0 -> "${UsageReducer.tokenLabel(total)} · ${UsageReducer.costLabel(cost)}"
      percent != null -> "$percent% of ${UsageReducer.tokenLabel(usage.context.window)}"
      cost > 0.0 -> UsageReducer.costLabel(cost)
      else -> ""
    }
  }

  /** The queue badge, or null when nothing is waiting. */
  fun queueLabel(inbox: InboxState): String? = InboxReducer.badge(inbox).takeIf { it.isNotEmpty() }

  /** Send while idle, the stop glyph while a reply streams. */
  fun sendIsStop(busy: Boolean): Boolean = busy
}
