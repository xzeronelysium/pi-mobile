package dev.pi.mobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val TerminalColorScheme = darkColorScheme(
  primary = Foreground,
  onPrimary = Background,
  secondary = Secondary,
  onSecondary = Background,
  tertiary = Secondary,
  background = Background,
  onBackground = Foreground,
  surface = Background,
  onSurface = Foreground,
  surfaceVariant = Background,
  onSurfaceVariant = Secondary,
  outline = Hairline,
  outlineVariant = Hairline,
)

/** Zero radius so sheets, dialogs, and fields stay square. */
private val FlatCorner = RoundedCornerShape(0.dp)
private val FlatShapes = Shapes(
  extraSmall = FlatCorner,
  small = FlatCorner,
  medium = FlatCorner,
  large = FlatCorner,
  extraLarge = FlatCorner,
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun PiAITheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  // Always the terminal scheme. Light/dynamic palettes fight the flat TUI.
  MaterialTheme(
    colorScheme = TerminalColorScheme,
    typography = Typography,
    shapes = FlatShapes,
    content = content,
  )
}
