package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptScrollMathTest {
  @Test
  fun bottomAlignOffsetZeroWhenLastRowFitsInViewport() {
    assertEquals(0, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 120, viewportSize = 800))
    assertEquals(0, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 800, viewportSize = 800))
    assertEquals(0, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 0, viewportSize = 800))
    assertEquals(0, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 400, viewportSize = 0))
  }

  @Test
  fun bottomAlignOffsetPinsTallLastRowBottomToViewportBottom() {
    // animateScrollToItem(index, offset) uses offset from item start; itemSize - viewport pins trailing edge.
    assertEquals(1200, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 2000, viewportSize = 800))
    assertEquals(1, TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 801, viewportSize = 800))
  }

  @Test
  fun offscreenLastRowNeedsMeasurePassBeforeBottomAlign() {
    // LazyList reports size 0 when last row is not composed → offset 0 would top-align a tall row.
    assertTrue(TranscriptScrollMath.needsItemMeasurePass(laidOutItemSize = 0))
    assertTrue(TranscriptScrollMath.needsItemMeasurePass(laidOutItemSize = -1))
    assertFalse(TranscriptScrollMath.needsItemMeasurePass(laidOutItemSize = 1))
    assertFalse(TranscriptScrollMath.needsItemMeasurePass(laidOutItemSize = 2000))
    // After measure, tall row gets a real bottom-align offset.
    assertEquals(
      1200,
      TranscriptScrollMath.bottomAlignScrollOffset(itemSize = 2000, viewportSize = 800),
    )
  }

  @Test
  fun nearBottomTrueForShortLastRowFullyInView() {
    val near = TranscriptScrollMath.isNearBottom(
      totalItems = 5,
      lastVisible = TranscriptScrollMath.VisibleItem(index = 4, offset = 600, size = 100),
      viewportEndOffset = 800,
      thresholdPx = 48,
    )
    assertTrue(near)
  }

  @Test
  fun nearBottomTrueWhenTallLastRowBottomAligned() {
    // Bottom-aligned tall row: top is above viewport (negative offset), bottom at viewport end.
    val near = TranscriptScrollMath.isNearBottom(
      totalItems = 3,
      lastVisible = TranscriptScrollMath.VisibleItem(index = 2, offset = -1200, size = 2000),
      viewportEndOffset = 800,
      thresholdPx = 48,
    )
    assertTrue(near)
  }

  @Test
  fun nearBottomFalseWhenTallLastRowOnlyTopVisible() {
    // Default animateScrollToItem top-align leaves trailing content below the fold.
    val near = TranscriptScrollMath.isNearBottom(
      totalItems = 3,
      lastVisible = TranscriptScrollMath.VisibleItem(index = 2, offset = 0, size = 2000),
      viewportEndOffset = 800,
      thresholdPx = 48,
    )
    assertFalse(near)
  }

  @Test
  fun nearBottomFalseWhenUserScrolledAwayFromLastItem() {
    val near = TranscriptScrollMath.isNearBottom(
      totalItems = 10,
      lastVisible = TranscriptScrollMath.VisibleItem(index = 6, offset = 100, size = 200),
      viewportEndOffset = 800,
      thresholdPx = 48,
    )
    assertFalse(near)
  }

  @Test
  fun nearBottomTrueWhenEmptyOrNoVisibleItems() {
    assertTrue(
      TranscriptScrollMath.isNearBottom(
        totalItems = 0,
        lastVisible = null,
        viewportEndOffset = 800,
      ),
    )
    assertTrue(
      TranscriptScrollMath.isNearBottom(
        totalItems = 4,
        lastVisible = null,
        viewportEndOffset = 800,
      ),
    )
  }

  @Test
  fun stickUpdateIgnoresTransientNotNearDuringAutoFollowLayoutEpoch() {
    // Growing streaming row can briefly report not-near-bottom before bottom-align scroll settles.
    assertTrue(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = true,
        nearBottom = false,
        autoScrollInFlight = true,
      ),
    )
    // After auto-scroll settles still not near → genuine leave (should not happen if bottom-aligned).
    assertFalse(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = true,
        nearBottom = false,
        autoScrollInFlight = false,
      ),
    )
    // User scrolls back to bottom.
    assertTrue(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = false,
        nearBottom = true,
        autoScrollInFlight = false,
      ),
    )
    // Auto-scroll in flight must not flip stick on from a false near while user had scrolled away —
    // auto-follow only runs when already stuck, so currentlyStuck stays false.
    assertFalse(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = false,
        nearBottom = false,
        autoScrollInFlight = true,
      ),
    )
  }

  @Test
  fun stickRetainedAcrossImeLayoutChurnUnlessReaderLeft() {
    // IME resize can briefly report not-near; keep stick if user was stuck.
    assertTrue(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = true,
        nearBottom = false,
        autoScrollInFlight = false,
        imeAnimating = true,
      ),
    )
    // Reader had already left before IME motion — do not force stick back on.
    assertFalse(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = false,
        nearBottom = false,
        autoScrollInFlight = false,
        imeAnimating = true,
      ),
    )
    // After IME settles, not-near with no in-flight scroll is a real leave.
    assertFalse(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = true,
        nearBottom = false,
        autoScrollInFlight = false,
        imeAnimating = false,
      ),
    )
    // Near bottom while IME animating still sticks.
    assertTrue(
      TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = false,
        nearBottom = true,
        autoScrollInFlight = false,
        imeAnimating = true,
      ),
    )
  }

  @Test
  fun shouldAutoFollowOnlyWhenStuckAndNonEmpty() {
    assertTrue(TranscriptScrollMath.shouldAutoFollow(stickToBottom = true, itemCount = 3))
    assertFalse(TranscriptScrollMath.shouldAutoFollow(stickToBottom = false, itemCount = 3))
    assertFalse(TranscriptScrollMath.shouldAutoFollow(stickToBottom = true, itemCount = 0))
  }
}
