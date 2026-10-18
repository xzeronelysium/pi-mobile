package dev.pi.mobile.chat

/**
 * Pure motion policy for send/composer-reset + IME coordination.
 * Keeps chat stick-to-bottom without stacking independent animations that fight
 * system IME inset animation (Compose [imePadding] already tracks IME frames).
 *
 * Single scroll owner: one [planProgrammaticScroll] decides ContentFollow vs
 * SettleRepin. Content growth / pending always beats IME settle. Pending stays
 * owned until the content-follow drain finishes (no clear-before-suspend race
 * that lets settle snap mid-animation). No continuous overlay/viewport repin.
 *
 * Agreed behavior while the keyboard closes:
 * - keep the visible transcript position steady (no frame-by-frame scrollToItem)
 * - composer follows native IME downward via imePadding
 * - after the transition settles, pin once only if still stuck-to-bottom and the
 *   latest message is actually obscured
 * - never reposition a reader who scrolled up
 */
object ComposerSendMotionPolicy {
  /**
   * Sticky follow for new transcript content animates rather than pre-snapping (a pre-snap would
   * double the motion). A measure-only scroll is allowed when the last row is off-screen (size
   * unknown) before the final bottom-align — see [TranscriptScrollMath.needsItemMeasurePass].
   */
  fun contentFollowUsesAnimation(): Boolean = true

  /**
   * When IME is open or mid-transition, content follow must not animate against
   * [imePadding] / system keyboard frames. Animate only when IME is fully gone
   * and settled.
   */
  fun contentFollowShouldAnimate(imeVisible: Boolean, imeAnimating: Boolean = false): Boolean =
    contentFollowUsesAnimation() && !imeVisible && !imeAnimating

  /**
   * Content-follow opportunity: either run now, or mark pending until IME settles.
   * [contentSignal] is true when transcript epoch/size (or equivalent) changed.
   */
  data class ContentFollowPlan(
    val runNow: Boolean,
    val pending: Boolean,
  )

  fun planContentFollow(
    stickToBottom: Boolean,
    itemCount: Int,
    imeAnimating: Boolean,
    contentSignal: Boolean,
    alreadyPending: Boolean,
  ): ContentFollowPlan {
    if (!TranscriptScrollMath.shouldAutoFollow(stickToBottom, itemCount)) {
      return ContentFollowPlan(runNow = false, pending = false)
    }
    val wantFollow = contentSignal || alreadyPending
    if (!wantFollow) {
      return ContentFollowPlan(runNow = false, pending = alreadyPending)
    }
    if (imeAnimating) {
      return ContentFollowPlan(runNow = false, pending = true)
    }
    return ContentFollowPlan(runNow = true, pending = false)
  }

  /**
   * True while WindowInsets.imeAnimationSource and imeAnimationTarget bottoms differ.
   * Prefer these APIs over delay timers.
   */
  fun isImeAnimating(sourceBottomPx: Int, targetBottomPx: Int): Boolean =
    sourceBottomPx != targetBottomPx

  /**
   * After IME settles, one bottom pin if the user was stuck and the latest message
   * is not near the bottom. No-op when the reader scrolled away or content is
   * already visible. Skipped when a pending content-follow will own the scroll.
   */
  fun shouldSettleRepinAfterIme(
    stickToBottom: Boolean,
    itemCount: Int,
    imeAnimating: Boolean,
    nearBottom: Boolean,
    pendingContentFollow: Boolean = false,
  ): Boolean =
    !imeAnimating &&
      stickToBottom &&
      itemCount > 0 &&
      !nearBottom &&
      !pendingContentFollow

  /**
   * Sole programmatic scroll decision for the transcript list.
   *
   * [imeJustSettled] is the genuine IME true→false edge only — size/stick re-keys
   * must pass false so settle cannot race content-follow (which used to clear
   * pending before [androidx.compose.foundation.lazy.LazyListState.animateScrollToItem]
   * suspended, letting settle see pending=false + nearBottom=false and snap mid-animation).
   *
   * [pendingContentFollow] remains true for ContentFollow until the caller finishes
   * the scroll and clears ownership — never clear pending before the scroll starts.
   */
  enum class ProgrammaticScrollKind {
    None,
    ContentFollow,
    SettleRepin,
  }

  data class ProgrammaticScrollPlan(
    val kind: ProgrammaticScrollKind,
    val pendingContentFollow: Boolean,
  )

  fun planProgrammaticScroll(
    stickToBottom: Boolean,
    itemCount: Int,
    imeAnimating: Boolean,
    contentSignal: Boolean,
    alreadyPending: Boolean,
    nearBottom: Boolean,
    imeJustSettled: Boolean,
  ): ProgrammaticScrollPlan {
    if (!TranscriptScrollMath.shouldAutoFollow(stickToBottom, itemCount)) {
      return ProgrammaticScrollPlan(ProgrammaticScrollKind.None, pendingContentFollow = false)
    }
    val follow = planContentFollow(
      stickToBottom = true,
      itemCount = itemCount,
      imeAnimating = imeAnimating,
      contentSignal = contentSignal,
      alreadyPending = alreadyPending,
    )
    if (follow.runNow) {
      // Hold pending ownership through the scroll body so a concurrent settle path
      // (or re-plan) cannot treat the follow as drained mid-animation.
      return ProgrammaticScrollPlan(
        kind = ProgrammaticScrollKind.ContentFollow,
        pendingContentFollow = true,
      )
    }
    if (follow.pending) {
      return ProgrammaticScrollPlan(
        kind = ProgrammaticScrollKind.None,
        pendingContentFollow = true,
      )
    }
    if (
      imeJustSettled &&
      shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = itemCount,
        imeAnimating = imeAnimating,
        nearBottom = nearBottom,
        pendingContentFollow = false,
      )
    ) {
      return ProgrammaticScrollPlan(
        kind = ProgrammaticScrollKind.SettleRepin,
        pendingContentFollow = false,
      )
    }
    return ProgrammaticScrollPlan(
      kind = ProgrammaticScrollKind.None,
      pendingContentFollow = false,
    )
  }

  /**
   * LazyColumn bottom content padding should match the live bottom overlay height
   * (composer + chrome + current IME padding) so multiline collapse and keyboard
   * hide do not leave a stale fixed gap (old ~140.dp reserve).
   */
  fun lazyListBottomPaddingPx(overlayHeightPx: Int, extraPx: Int = 0): Int =
    overlayHeightPx.coerceAtLeast(0) + extraPx.coerceAtLeast(0)
}
