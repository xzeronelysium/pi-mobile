package dev.pi.mobile.chat

import androidx.compose.ui.unit.LayoutDirection

/**
 * The history drawer's layout contract: which physical edge it comes in from, how wide its panel is,
 * which system bars the panel stays inside, and how big the header control that opens it is.
 *
 * Material's [androidx.compose.material3.ModalNavigationDrawer] anchors its sheet to the
 * composition's *leading* edge. Providing [CONTAINER_DIRECTION] to the drawer swaps which physical
 * edge is leading, so the panel anchors to the RIGHT edge and slides in right-to-left. The panel and
 * the app's own content re-provide [CONTENT_DIRECTION] so their layout stays left-to-right. This is
 * the pre-cut's mechanism, not a custom animation: the drawer's own edge is what places the panel.
 *
 * The values here are pure so the contract is checkable off-device; only the pixel result of the
 * system-bar insets needs a screen.
 *
 * The flip belongs to the drawer's container alone — the panel and the app's own content both
 * re-provide [CONTENT_DIRECTION] — and that container paints nothing ([PAINT_SURFACE]), because
 * material's sheet would otherwise reach the window's top and bottom edges.
 */
object SessionDrawerLayout {
  /** The direction the drawer container is provided: the leading edge becomes the right edge. */
  val CONTAINER_DIRECTION: LayoutDirection = LayoutDirection.Rtl

  /** The direction re-provided inside the panel and around the app content: both stay LTR. */
  val CONTENT_DIRECTION: LayoutDirection = LayoutDirection.Ltr

  /** The panel is 84 % of the screen width, so it scales with the device instead of a fixed dp. */
  const val WIDTH_FRACTION = 0.84f

  fun panelWidthDp(screenWidthDp: Int): Int = (screenWidthDp * WIDTH_FRACTION).toInt()

  /** A system bar the panel's own rectangle must never draw under. */
  enum class PanelSystemBar { StatusBar, NavigationBar }

  /**
   * The bars the panel is inset by, in the order they are applied: the status bar at the top, the
   * navigation bar at the bottom. The insets go on the panel's container, *before* its background
   * and 1 dp hairline are painted, so the drawer rectangle stays inside the safe area instead of
   * running to the screen edges.
   */
  val PANEL_INSET_BARS: List<PanelSystemBar> =
    listOf(PanelSystemBar.StatusBar, PanelSystemBar.NavigationBar)

  /** The panel's vertical bounds inside a window: below the status bar, above the nav bar. */
  fun panelBoundsPx(windowHeightPx: Int, statusBarPx: Int, navigationBarPx: Int): IntRange =
    statusBarPx until (windowHeightPx - navigationBarPx)

  /** Where the panel's background and its 1 dp hairline are painted. */
  enum class PaintSurface { Container, Panel }

  /**
   * The panel paints itself; the drawer container behind it stays transparent.
   *
   * Not [PaintSurface.Container]: material's `DrawerSheet` applies its `windowInsets` to the sheet's
   * *inner content*, inside the surface's own background, so a container that painted a colour would
   * still reach the window's top and bottom edges however it was inset. The panel paints after this
   * contract's insets, so the rectangle that is painted is the inset one.
   */
  val PAINT_SURFACE: PaintSurface = PaintSurface.Panel

  /** The panel's own modifier layers, outermost first. */
  enum class PanelLayer { Width, StatusBarInset, NavigationBarInset, Background, Hairline }

  /**
   * The panel's layers in the order it applies them: the width, the two system-bar insets, then the
   * background and the 1 dp hairline. Compose applies a modifier list outermost-first, so the insets
   * shrink the panel's rectangle *before* the background and the hairline paint inside it.
   */
  val PANEL_LAYER_ORDER: List<PanelLayer> = listOf(
    PanelLayer.Width,
    PanelLayer.StatusBarInset,
    PanelLayer.NavigationBarInset,
    PanelLayer.Background,
    PanelLayer.Hairline,
  )

  /** True when every paint layer of [order] comes after every system-bar inset. */
  fun insetsPrecedePaint(order: List<PanelLayer> = PANEL_LAYER_ORDER): Boolean {
    val lastInset = order.indexOfLast {
      it == PanelLayer.StatusBarInset || it == PanelLayer.NavigationBarInset
    }
    val firstPaint = order.indexOfFirst {
      it == PanelLayer.Background || it == PanelLayer.Hairline
    }
    return lastInset >= 0 && firstPaint > lastInset
  }

  /** A site [CONTENT_DIRECTION] has to be re-provided at, or it mirrors inside the Rtl container. */
  enum class ContentSite { Panel, AppContent }

  /**
   * Both sites: the drawer's panel and the app's own content — the header, the transcript and the
   * dock. Re-providing at one and forgetting the other mirrors whichever side was missed.
   */
  val CONTENT_DIRECTION_SITES: List<ContentSite> = listOf(ContentSite.Panel, ContentSite.AppContent)
}

/**
 * The header's history glyph — the single control that opens the drawer.
 *
 * It deliberately does not size itself from [ComposerDockReducer.ICON_SIZE_DP]: that is the
 * *composer's* glyph size, and a control whose hit area is the glyph itself is a 16 dp target. The
 * glyph is still drawn at the logo's size, because the two sit opposite each other, but the
 * invisible hit area around it is a full [TOUCH_TARGET_DP]-dp square — easy to hit without looking
 * oversized.
 */
object HeaderHistoryGlyph {
  /** The drawn glyph, the same size as the logo opposite it. */
  const val GLYPH_SIZE_DP = 16

  /** The transparent hit area around it: Material's accessible minimum touch target. */
  const val TOUCH_TARGET_DP = 48

  /**
   * The header row's trailing padding that leaves the hit area's centre on the header's right-edge
   * axis ([ComposerDockReducer.ICON_AXIS_DP]) — the same axis the dock's picker control sits on.
   */
  fun trailingPaddingDp(axisDp: Int = ComposerDockReducer.ICON_AXIS_DP): Int =
    axisDp - TOUCH_TARGET_DP / 2

  /** Where the glyph's centre lands, measured in from the header's right edge. */
  fun glyphCentreFromRightDp(trailingPaddingDp: Int): Int = trailingPaddingDp + TOUCH_TARGET_DP / 2
}
