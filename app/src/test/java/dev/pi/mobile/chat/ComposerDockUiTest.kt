package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer dock's layout contract: one right-edge axis shared by the history glyph and the
 * picker control, content-sized labels, and the session usage line taken verbatim from the sibling
 * [UsageReducer] instead of reworded here.
 */
class ComposerDockUiTest {
  @Test fun theHistoryGlyphAndThePickerControlShareOneRightEdgeAxis() {
    val rowEndPadding = 6
    val iconPadding = ComposerDockReducer.iconPaddingDp(rowEndPadding)
    assertEquals(10, iconPadding)
    val centre = rowEndPadding + iconPadding + ComposerDockReducer.ICON_SIZE_DP / 2
    assertEquals(ComposerDockReducer.ICON_AXIS_DP, centre)
  }

  @Test fun theDockRunsModelEffortUsageThenThePicker() {
    assertEquals(
      listOf(
        ComposerDockSlot.Model,
        ComposerDockSlot.Effort,
        ComposerDockSlot.Usage,
        ComposerDockSlot.Queue,
        ComposerDockSlot.Picker,
      ),
      ComposerDockReducer.dockSlots(),
    )
  }

  @Test fun theModelLabelIsCappedButNeverDropped() {
    assertEquals(140, ComposerDockReducer.MODEL_MAX_WIDTH_DP)
    assertEquals("No model", ComposerDockReducer.modelLabel(""))
    assertEquals("No model", ComposerDockReducer.modelLabel("   "))
    assertEquals("claude-sonnet-4", ComposerDockReducer.modelLabel("claude-sonnet-4"))
  }

  @Test fun theUsageLineIsTheUsageReducersFooter() {
    val value = JSONObject()
      .put(
        "models",
        JSONArray().put(
          JSONObject().put("provider", "anthropic").put("modelId", "m").put("input", 1200).put("output", 340).put("cost", 0.02),
        ),
      )
      .put("context", JSONObject().put("used", 25_000).put("window", 200_000))
    val usage = UsageReducer.fromValue(value)
    assertEquals(UsageReducer.footer(usage), ComposerDockReducer.usageText(usage))
    assertEquals("↑1.2k ↓340 $0.020 12.5%/200k", ComposerDockReducer.usageText(usage))
  }

  @Test fun anUnusedSessionShowsNoUsageTextAtAll() {
    assertEquals("", ComposerDockReducer.usageText(UsageState()))
    assertEquals("", ComposerDockReducer.compactUsage(UsageState()))
  }

  @Test fun theCompactUsageLineShowsTokensAndContextShare() {
    val value = JSONObject()
      .put(
        "models",
        JSONArray().put(
          JSONObject().put("provider", "anthropic").put("modelId", "m").put("input", 1200).put("output", 340).put("cost", 0.02),
        ),
      )
      .put("context", JSONObject().put("used", 25_000).put("window", 200_000))
    val usage = UsageReducer.fromValue(value)
    assertEquals("1.5k · 13%", ComposerDockReducer.compactUsage(usage))
  }

  @Test fun theCompactUsageLineFallsBackToCostWhenTheWindowIsUnknown() {
    val value = JSONObject()
      .put(
        "models",
        JSONArray().put(
          JSONObject().put("provider", "anthropic").put("modelId", "m").put("input", 1200).put("output", 340).put("cost", 0.02),
        ),
      )
    val usage = UsageReducer.fromValue(value)
    assertEquals("1.5k · $0.02", ComposerDockReducer.compactUsage(usage))
  }

  @Test fun theQueueBadgeOnlyAppearsWhenSomethingIsQueued() {
    assertNull(ComposerDockReducer.queueLabel(InboxState()))
    val one = InboxReducer.fromValue(
      JSONObject().put(
        "queued",
        JSONArray().put(JSONObject().put("id", "1").put("kind", "steer").put("text", "hold on").put("status", "queued")),
      ),
    )
    assertEquals("1", ComposerDockReducer.queueLabel(one))
  }

  @Test fun theSendControlIsTheStopGlyphOnlyWhileAReplyStreams() {
    assertTrue(ComposerDockReducer.sendIsStop(busy = true))
    assertFalse(ComposerDockReducer.sendIsStop(busy = false))
  }
}
