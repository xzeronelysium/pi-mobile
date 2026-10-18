package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendMotionPolicyTest {
  @Test
  fun contentFollowAnimatesRatherThanPreSnapping() {
    // Regression: unconditional scrollToItem + animateScrollToItem stacked with IME hide jumps.
    // Measure-only scroll when size is unknown is separate (TranscriptScrollMath.needsItemMeasurePass).
    assertTrue(ComposerSendMotionPolicy.contentFollowUsesAnimation())
  }

  @Test
  fun contentFollowSnapsWhileImeVisibleAnimatesWhenClosedAndSettled() {
    assertFalse(ComposerSendMotionPolicy.contentFollowShouldAnimate(imeVisible = true, imeAnimating = false))
    assertFalse(ComposerSendMotionPolicy.contentFollowShouldAnimate(imeVisible = false, imeAnimating = true))
    assertFalse(ComposerSendMotionPolicy.contentFollowShouldAnimate(imeVisible = true, imeAnimating = true))
    assertTrue(ComposerSendMotionPolicy.contentFollowShouldAnimate(imeVisible = false, imeAnimating = false))
  }

  @Test
  fun contentFollowPendingWhileImeAnimatingThenRunsWhenSettled() {
    // Early exit without pending loses follow when effect is not re-keyed on imeAnimating.
    val during = ComposerSendMotionPolicy.planContentFollow(
      stickToBottom = true,
      itemCount = 4,
      imeAnimating = true,
      contentSignal = true,
      alreadyPending = false,
    )
    assertFalse(during.runNow)
    assertTrue(during.pending)

    val after = ComposerSendMotionPolicy.planContentFollow(
      stickToBottom = true,
      itemCount = 4,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = true,
    )
    assertTrue(after.runNow)
    assertFalse(after.pending)

    // Reader scrolled away while pending — drop follow.
    val left = ComposerSendMotionPolicy.planContentFollow(
      stickToBottom = false,
      itemCount = 4,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = true,
    )
    assertFalse(left.runNow)
    assertFalse(left.pending)
  }

  @Test
  fun contentFollowPlanIgnoresEmptyOrNoSignal() {
    val empty = ComposerSendMotionPolicy.planContentFollow(
      stickToBottom = true,
      itemCount = 0,
      imeAnimating = false,
      contentSignal = true,
      alreadyPending = false,
    )
    assertFalse(empty.runNow)
    assertFalse(empty.pending)

    val idle = ComposerSendMotionPolicy.planContentFollow(
      stickToBottom = true,
      itemCount = 3,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = false,
    )
    assertFalse(idle.runNow)
    assertFalse(idle.pending)
  }

  @Test
  fun imeAnimatingWhenSourceAndTargetBottomsDiffer() {
    // WindowInsets.imeAnimationSource/Target — no arbitrary delay timers.
    assertFalse(ComposerSendMotionPolicy.isImeAnimating(sourceBottomPx = 0, targetBottomPx = 0))
    assertFalse(ComposerSendMotionPolicy.isImeAnimating(sourceBottomPx = 800, targetBottomPx = 800))
    assertTrue(ComposerSendMotionPolicy.isImeAnimating(sourceBottomPx = 800, targetBottomPx = 0))
    assertTrue(ComposerSendMotionPolicy.isImeAnimating(sourceBottomPx = 0, targetBottomPx = 400))
    assertTrue(ComposerSendMotionPolicy.isImeAnimating(sourceBottomPx = 200, targetBottomPx = 600))
  }

  @Test
  fun settleRepinOnlyWhenStuckObscuredAndImeSettled() {
    // After keyboard finishes, one pin if still stuck and latest message not near bottom.
    assertTrue(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = 2,
        imeAnimating = false,
        nearBottom = false,
      ),
    )
    assertFalse(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = 2,
        imeAnimating = false,
        nearBottom = true,
      ),
    )
    assertFalse(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = 2,
        imeAnimating = true,
        nearBottom = false,
      ),
    )
    assertFalse(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = false,
        itemCount = 2,
        imeAnimating = false,
        nearBottom = false,
      ),
    )
    assertFalse(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = 0,
        imeAnimating = false,
        nearBottom = false,
      ),
    )
    // Pending content-follow owns the scroll — settle must not double-fire.
    assertFalse(
      ComposerSendMotionPolicy.shouldSettleRepinAfterIme(
        stickToBottom = true,
        itemCount = 2,
        imeAnimating = false,
        nearBottom = false,
        pendingContentFollow = true,
      ),
    )
  }

  @Test
  fun singleScrollOwnerPendingContentFollowBeatsImeSettleEvenWhenNotNear() {
    // Race: content-follow clears pending before animateScroll suspends; settle keyed on
    // size/pending sees pending=false + nearBottom=false and snaps mid-animation.
    // Unified owner: pending/content growth always wins; pending stays owned until drain.
    val drained = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 4,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = true,
      nearBottom = false,
      imeJustSettled = true,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.ContentFollow, drained.kind)
    assertTrue(drained.pendingContentFollow)

    val growth = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 5,
      imeAnimating = false,
      contentSignal = true,
      alreadyPending = false,
      nearBottom = false,
      imeJustSettled = false,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.ContentFollow, growth.kind)
    assertTrue(growth.pendingContentFollow)

    // Size/stick re-entry while already settled must not settle (no second scroll owner).
    val idleRekey = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 4,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = false,
      nearBottom = false,
      imeJustSettled = false,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.None, idleRekey.kind)
    assertFalse(idleRekey.pendingContentFollow)
  }

  @Test
  fun singleScrollOwnerSettleOnlyOnGenuineImeEdgeWithoutContentFollow() {
    val settle = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 3,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = false,
      nearBottom = false,
      imeJustSettled = true,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.SettleRepin, settle.kind)
    assertFalse(settle.pendingContentFollow)

    val near = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 3,
      imeAnimating = false,
      contentSignal = false,
      alreadyPending = false,
      nearBottom = true,
      imeJustSettled = true,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.None, near.kind)

    val duringIme = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 3,
      imeAnimating = true,
      contentSignal = true,
      alreadyPending = false,
      nearBottom = false,
      imeJustSettled = false,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.None, duringIme.kind)
    assertTrue(duringIme.pendingContentFollow)
  }

  @Test
  fun bottomPaddingTracksLiveOverlayHeight() {
    assertEquals(0, ComposerSendMotionPolicy.lazyListBottomPaddingPx(0))
    assertEquals(120, ComposerSendMotionPolicy.lazyListBottomPaddingPx(120))
    assertEquals(128, ComposerSendMotionPolicy.lazyListBottomPaddingPx(120, extraPx = 8))
    assertEquals(8, ComposerSendMotionPolicy.lazyListBottomPaddingPx(-4, extraPx = 8))
  }

  /**
   * Equivalence capture for the guard that used to be inlined as `!stickToBottom || itemCount <= 0`
   * and now calls [TranscriptScrollMath.shouldAutoFollow]: the same boundary inputs still plan no
   * scroll at all.
   */
  @Test
  fun planProgrammaticScrollRefusesADetachedOrEmptyList() {
    val detached = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = false,
      itemCount = 4,
      imeAnimating = false,
      contentSignal = true,
      alreadyPending = true,
      nearBottom = false,
      imeJustSettled = true,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.None, detached.kind)
    assertFalse(detached.pendingContentFollow)

    val empty = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = true,
      itemCount = 0,
      imeAnimating = false,
      contentSignal = true,
      alreadyPending = true,
      nearBottom = false,
      imeJustSettled = true,
    )
    assertEquals(ComposerSendMotionPolicy.ProgrammaticScrollKind.None, empty.kind)
    assertFalse(empty.pendingContentFollow)
  }
}
