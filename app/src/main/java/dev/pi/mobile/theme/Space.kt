package dev.pi.mobile.theme

import androidx.compose.ui.unit.dp

/**
 * The 4 dp baseline, in one place.
 *
 * Screens use [Screen] as their only horizontal margin, [Touch] as the default target and [Row] for
 * a list row. A value outside this scale is a deliberate exception, not a local preference.
 */
object PiSpace {
  val Hairline = 1.dp
  val x1 = 4.dp
  val x2 = 8.dp
  val x3 = 12.dp
  val x4 = 16.dp
  val x6 = 24.dp
  val x8 = 32.dp
  val x12 = 48.dp
  val x16 = 64.dp

  val Screen = 20.dp
  val Touch = 48.dp
  val TouchMin = 44.dp
  val Row = 54.dp
  val Header = 58.dp
  val Rail = 2.dp
}
