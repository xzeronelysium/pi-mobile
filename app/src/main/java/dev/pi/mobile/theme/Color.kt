package dev.pi.mobile.theme

import androidx.compose.ui.graphics.Color

/**
 * Sumi Grid: one fixed dark palette, eight ink values, no elevation.
 *
 * Hierarchy comes from value and space, not from boxes: [Foreground] for the thing being read,
 * [Secondary] for supporting text, [Muted] for de-emphasised but readable text, and [Hairline]
 * only for rules. Every token here clears AA at its intended size; the old #737373 secondary did
 * not. There is deliberately no light scheme and no raised-panel token.
 */
val Background = Color(0xFF0A0A0A)

/** A sheet or panel plane. Nearly the base colour: a change of surface, not a raised card. */
val Surface = Color(0xFF0E0E0E)

val Foreground = Color(0xFFEDEDED)

/** Supporting text, metadata and unselected labels. 8.3:1 on [Background]. */
val Secondary = Color(0xFFA8A8A8)

/** De-emphasised but readable text; the absolute floor for a sentence. 5.7:1. */
val Muted = Color(0xFF8A8A8A)

/** Rules and separators; a divider is a hairline, never a slab, and never text. */
val Hairline = Color(0xFF262626)

/** The one low-saturation accent, for agent activity and the current/selected state. */
val Accent = Color(0xFFC8A96A)

/** Destructive and error only. A seal red, used sparingly. */
val Error = Color(0xFFD0614C)

// Stable semantic aliases. New chrome reads these names; older call sites keep the names above.
val Sumi = Background
val SumiSurface = Surface
val Ink = Foreground
val Ink2 = Secondary
val Ink3 = Muted
val Kin = Accent
val Shu = Error
