package dev.pi.mobile.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Flat = RoundedCornerShape(0.dp)

/**
 * A control, not a glyph. The visible mark is [glyphSize]; the target is [PiSpace.Touch] by default.
 * A filled control uses [background] and flips its tint to [Background] so it reads as one object.
 */
@Composable
fun PiIconButton(
  onClick: () -> Unit,
  contentDescription: String,
  glyph: Painter,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = Secondary,
  background: Color? = null,
  target: Dp = PiSpace.Touch,
  glyphSize: Dp = 18.dp,
) {
  Box(
    modifier
      .size(target)
      .then(if (background != null) Modifier.background(background, Flat) else Modifier)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = glyph,
      contentDescription = contentDescription,
      tint = if (enabled) (if (background != null) Background else tint) else Muted,
      modifier = Modifier.size(glyphSize),
    )
  }
}

/** A 1 dp rule; the only boundary a surface draws. */
@Composable
fun PiRule(modifier: Modifier = Modifier, color: Color = Hairline) {
  Box(modifier.fillMaxWidth().height(PiSpace.Hairline).background(color))
}

/** The 2 dp Kin rail that marks current/selected. */
@Composable
fun PiSelectedRail(modifier: Modifier = Modifier, height: Dp = PiSpace.TouchMin) {
  Box(modifier.width(PiSpace.Rail).height(height).background(Kin))
}

/** An uppercase micro-label. The only place 10 sp is allowed. */
@Composable
fun PiLabel(text: String, modifier: Modifier = Modifier, color: Color = Muted) {
  Text(
    text = text.uppercase(),
    style = PiType.Label,
    color = color,
    modifier = modifier,
  )
}
