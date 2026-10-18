package dev.pi.mobile.chat

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history drawer's layout contract, checked without a device: the composition-local direction
 * that anchors the panel to the RIGHT edge (and the Ltr it re-provides inside), the panel geometry,
 * the system bars it is inset by, and the header control's accessible touch target.
 */
class SessionDrawerLayoutTest {
  /**
   * Rtl makes the composition's *leading* edge the right one, which is the edge Material anchors the
   * modal sheet to — the pre-cut's mechanism for a right-to-left slide-in. Without this the panel
   * comes in from the left.
   */
  @Test fun theDrawerContainerIsProvidedRtlSoThePanelAnchorsToTheRightEdge() {
    assertEquals(LayoutDirection.Rtl, SessionDrawerLayout.CONTAINER_DIRECTION)
  }

  @Test fun thePanelAndTheAppContentAreReProvidedLtr() {
    assertEquals(LayoutDirection.Ltr, SessionDrawerLayout.CONTENT_DIRECTION)
  }

  /**
   * The two must differ: the flip is Rtl at the container and Ltr for everything inside it, which is
   * what keeps the transcript, the header and the panel's own text left-to-right.
   */
  @Test fun theFlipDoesNotLeakIntoTheAppContent() {
    assertTrue(SessionDrawerLayout.CONTAINER_DIRECTION != SessionDrawerLayout.CONTENT_DIRECTION)
  }

  @Test fun thePanelIsEightyFourPercentOfTheScreenWidth() {
    assertEquals(345, SessionDrawerLayout.panelWidthDp(411))
    assertEquals(672, SessionDrawerLayout.panelWidthDp(800))
  }

  /** Both system bars come off the panel's own rectangle, so it never draws under either. */
  @Test fun thePanelIsInsetByTheStatusBarAndTheNavigationBar() {
    assertEquals(
      listOf(SessionDrawerLayout.PanelSystemBar.StatusBar, SessionDrawerLayout.PanelSystemBar.NavigationBar),
      SessionDrawerLayout.PANEL_INSET_BARS,
    )
    val bounds = SessionDrawerLayout.panelBoundsPx(windowHeightPx = 2000, statusBarPx = 72, navigationBarPx = 48)
    // Starts below the status bar and stops above the navigation bar — never at the window edges.
    assertEquals(72, bounds.first)
    assertEquals(1951, bounds.last)
  }

  @Test fun theHeaderGlyphHasItsOwnSizeAndAnAccessibleTouchTarget() {
    assertEquals(16, HeaderHistoryGlyph.GLYPH_SIZE_DP)
    assertTrue(HeaderHistoryGlyph.TOUCH_TARGET_DP >= 48)
    // It is the header's own control, not the composer's icon reused as a hit area.
    assertTrue(HeaderHistoryGlyph.TOUCH_TARGET_DP > ComposerDockReducer.ICON_SIZE_DP)
  }

  /** The glyph's centre lands on the header's right-edge axis, symmetric with the logo opposite it. */
  @Test fun theHeaderGlyphCentreSitsOnTheHeadersRightEdgeAxis() {
    val trailing = HeaderHistoryGlyph.trailingPaddingDp()
    assertEquals(0, trailing)
    assertEquals(ComposerDockReducer.ICON_AXIS_DP, HeaderHistoryGlyph.glyphCentreFromRightDp(trailing))
    // The logo is 16 dp drawn 16 dp in from the left, so its centre is 24 dp in — the mirror of this.
    assertEquals(24, HeaderHistoryGlyph.glyphCentreFromRightDp(trailing))
  }

  /**
   * The 48 dp hit area still fits inside the header's right edge: the trailing padding the header
   * derives from it is never negative, and half the target is exactly the axis its centre sits on, so
   * the accessible square cannot overflow the header's own edge.
   */
  @Test fun theHeaderHitAreaFitsInsideTheHeaderWithoutOverflowingItsEdge() {
    val trailing = HeaderHistoryGlyph.trailingPaddingDp()
    assertTrue(trailing >= 0)
    assertTrue(trailing * 2 + HeaderHistoryGlyph.TOUCH_TARGET_DP <= ComposerDockReducer.ICON_AXIS_DP * 2)
  }

  /** The width the panel derives from the screen is the 84 % the drawer has always used. */
  @Test fun thePanelWidthIsThatFractionOfTheGivenScreen() {
    for (screenWidthDp in listOf(320, 360, 411, 480)) {
      assertEquals((screenWidthDp * 0.84f).toInt(), SessionDrawerLayout.panelWidthDp(screenWidthDp))
    }
  }

  /**
   * The point of [SessionDrawerLayout.PANEL_LAYER_ORDER]: the insets are outside layers, so the
   * background and the hairline are painted inside the safe area. The second assertion is the
   * control — the pre-fix order, paint first and insets on the inner content, is rejected, so this
   * cannot pass by accident.
   */
  @Test fun thePanelPaintsInsideItsSystemBarInsetsRatherThanUnderThem() {
    assertTrue(SessionDrawerLayout.insetsPrecedePaint())
    val preFixOrder = listOf(
      SessionDrawerLayout.PanelLayer.Width,
      SessionDrawerLayout.PanelLayer.Background,
      SessionDrawerLayout.PanelLayer.Hairline,
      SessionDrawerLayout.PanelLayer.StatusBarInset,
      SessionDrawerLayout.PanelLayer.NavigationBarInset,
    )
    assertFalse(SessionDrawerLayout.insetsPrecedePaint(preFixOrder))
  }

  /**
   * The panel is its own surface. The drawer container must not paint: material applies a sheet's
   * windowInsets to its inner content, so a container colour would still reach the window's top and
   * bottom edges whatever it was inset by.
   */
  @Test fun thePanelIsItsOwnSurfaceAndTheDrawerContainerPaintsNothing() {
    assertEquals(SessionDrawerLayout.PaintSurface.Panel, SessionDrawerLayout.PAINT_SURFACE)
  }

  /** Both sites re-provide Ltr: the panel, and the app's own header/transcript/dock content. */
  @Test fun bothThePanelAndTheAppContentReProvideLtr() {
    assertEquals(
      listOf(SessionDrawerLayout.ContentSite.Panel, SessionDrawerLayout.ContentSite.AppContent),
      SessionDrawerLayout.CONTENT_DIRECTION_SITES,
    )
  }

  /** The painted rectangle stays strictly inside the window: no part of it is under a system bar. */
  @Test fun thePanelsPaintedRectangleNeverReachesTheWindowEdges() {
    val portrait = SessionDrawerLayout.panelBoundsPx(windowHeightPx = 2000, statusBarPx = 72, navigationBarPx = 48)
    assertTrue(portrait.first > 0 && portrait.last < 1999)
    // A gesture-nav device has a small bottom inset, and still not the window edge.
    val gesture = SessionDrawerLayout.panelBoundsPx(windowHeightPx = 2000, statusBarPx = 72, navigationBarPx = 12)
    assertTrue(gesture.first > 0 && gesture.last < 1999)
    // Only a window with no bars at all lets the rectangle equal the window's own bounds.
    val bare = SessionDrawerLayout.panelBoundsPx(windowHeightPx = 2000, statusBarPx = 0, navigationBarPx = 0)
    assertEquals(0, bare.first)
    assertEquals(1999, bare.last)
  }
}
