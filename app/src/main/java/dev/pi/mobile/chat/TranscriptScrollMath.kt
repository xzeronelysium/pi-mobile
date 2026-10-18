package dev.pi.mobile.chat

/**
 * Pure scroll math for transcript sticky auto-follow.
 * `LazyListState.animateScrollToItem` defaults to aligning the item's **top** to the
 * viewport top — when the last streaming row is taller than the viewport, the growing bottom
 * stays off-screen and the near-bottom check incorrectly drops sticky follow.
 */
object TranscriptScrollMath {
  data class VisibleItem(val index: Int, val offset: Int, val size: Int)

  /**
   * Scroll offset passed to `animateScrollToItem(index, scrollOffset)` so the item's bottom
   * edge lands on the viewport bottom when [itemSize] exceeds [viewportSize]; otherwise 0
   * (top-align is fine for short rows).
   *
   * When the last row is off-screen, LazyList reports size 0 — callers must run a measure pass
   * ([needsItemMeasurePass]) before using this offset as the final bottom-align.
   */
  fun bottomAlignScrollOffset(itemSize: Int, viewportSize: Int): Int {
    if (itemSize <= 0 || viewportSize <= 0) return 0
    return (itemSize - viewportSize).coerceAtLeast(0)
  }

  /**
   * True when the last row has no laid-out size yet (off-screen or not composed). Using
   * [bottomAlignScrollOffset] alone would return 0 and top-align a tall row.
   */
  fun needsItemMeasurePass(laidOutItemSize: Int): Boolean = laidOutItemSize <= 0

  /**
   * Whether the list is considered "at bottom" for sticky follow.
   * Requires the last item to be visible and its trailing edge within [thresholdPx] of the
   * viewport end — works for short rows and bottom-aligned tall rows (negative offset).
   */
  fun isNearBottom(
    totalItems: Int,
    lastVisible: VisibleItem?,
    viewportEndOffset: Int,
    thresholdPx: Int = 48,
  ): Boolean {
    if (totalItems == 0) return true
    if (lastVisible == null) return true
    if (lastVisible.index < totalItems - 1) return false
    val itemBottom = lastVisible.offset + lastVisible.size
    return itemBottom <= viewportEndOffset + thresholdPx
  }

  /**
   * Update sticky flag from a near-bottom observation.
   * While an automatic bottom-align scroll is in flight, or while IME inset animation is
   * resizing the viewport, transient "not near" readings must not unset sticky — only a
   * genuine reader scroll away (settled layout, not near, not in-flight) clears stick.
   */
  fun nextStickToBottom(
    currentlyStuck: Boolean,
    nearBottom: Boolean,
    autoScrollInFlight: Boolean,
    imeAnimating: Boolean = false,
  ): Boolean {
    if (nearBottom) return true
    if (autoScrollInFlight && currentlyStuck) return true
    if (imeAnimating && currentlyStuck) return true
    return false
  }

  fun shouldAutoFollow(stickToBottom: Boolean, itemCount: Int): Boolean =
    stickToBottom && itemCount > 0
}
