package dev.pi.mobile.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiMuted
import dev.pi.mobile.FoldChevron
import dev.pi.mobile.theme.PiLabel
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType

// Thinking is supporting text, not a second transcript: readable at 5.7:1, never a hairline grey.
private val ThinkSecondary = PiMuted
private val ThinkForeground = PiForeground

/**
 * Collapsed thinking line. No panel — a prompt marker and dim text, like a TUI fold.
 */
@Composable
fun ThinkingBlock(text: String, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
  if (text.isBlank()) return
  var expanded by remember(text) { mutableStateOf(initiallyExpanded) }
  Column(modifier.fillMaxWidth()) {
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = PiSpace.TouchMin)
        .clickable { expanded = !expanded },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FoldChevron(expanded, ThinkSecondary)
      PiLabel("Thinking", color = ThinkSecondary)
      if (!expanded) {
        Text(
          text.replace('\n', ' ').trim(),
          style = PiType.Meta,
          color = ThinkSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = PiSpace.x2).weight(1f),
        )
      }
    }
    if (expanded) {
      Text(
        text,
        style = PiType.BodySmall.copy(color = ThinkForeground, fontStyle = FontStyle.Italic),
        modifier = Modifier.padding(start = PiSpace.x6, bottom = PiSpace.x1),
      )
    }
  }
}
