package dev.pi.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.backend.WorkspaceBinding
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.PickerSheet
import dev.pi.mobile.chat.SettingsUiReducer
import dev.pi.mobile.chat.SettingsUiState
import dev.pi.mobile.theme.Accent as TokenAccent
import dev.pi.mobile.theme.Background as TokenBackground
import dev.pi.mobile.theme.Error as TokenError
import dev.pi.mobile.theme.Foreground as TokenForeground
import dev.pi.mobile.theme.Hairline as TokenHairline
import dev.pi.mobile.theme.Muted as TokenMuted
import dev.pi.mobile.theme.Secondary as TokenSecondary
import dev.pi.mobile.theme.Surface as TokenSurface
import dev.pi.mobile.theme.PiIconButton
import dev.pi.mobile.theme.PiLabel
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType

// The chrome's colours are the semantic tokens in `theme/`; the transcript chrome is fixed dark and
// there is no light scheme. These aliases exist so one token change reaches every surface at once
// instead of leaving ad-hoc hexes behind on the sheets, the drawer and the dock.
//
// There is no raised panel token: the drawer, the menus and the dialogs are all [PiBackground] with
// a 1 dp [PiHairline] border, square corners and no elevation.
internal val PiBackground = TokenBackground
internal val PiSurface = TokenSurface
internal val PiHairline = TokenHairline
internal val PiForeground = TokenForeground
internal val PiSecondary = TokenSecondary
internal val PiMuted = TokenMuted
internal val PiAccent = TokenAccent
internal val PiError = TokenError

// ---------------------------------------------------------------------------
// Settings chrome: chevron, menu, sheets
// ---------------------------------------------------------------------------

/**
 * The composer chevron.
 *
 * It points up while nothing is open and down while the menu or a sheet is open, and tapping it
 * closes whatever is open. [SettingsUiReducer.toggleChevron] decides from the pre-tap state, so a
 * sheet's own dismiss handler cannot flip it back open.
 */
@Composable
internal fun SettingsChevron(open: Boolean, onToggle: () -> Unit) {
  PiIconButton(
    onClick = onToggle,
    contentDescription = "Settings",
    glyph = painterResource(PiIcons.settings),
    tint = if (open) PiForeground else PiSecondary,
    target = PiSpace.TouchMin,
    glyphSize = 18.dp,
  )
}

/**
 * The one swipe-up sheet container.
 *
 * The height is bounded first and only the inner column scrolls: `ModalBottomSheet` measures its
 * content unbounded, so chaining `verticalScroll` onto the sheet content itself crashes on open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlatSheet(
  onDismissRequest: () -> Unit,
  onBack: (() -> Unit)? = null,
  heightFraction: Float = 0.55f,
  content: @Composable () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = PiBackground,
    contentColor = PiForeground,
    shape = RectangleShape,
    tonalElevation = 0.dp,
    scrimColor = Color(0x99000000),
    dragHandle = { PiRule() },
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .height(LocalConfiguration.current.screenHeightDp.dp * heightFraction),
    ) {
      if (onBack != null) SheetBackRow(onBack)
      Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
  }
}

/** The named back a detail sheet owes the index. */
@Composable
internal fun SheetBackRow(onBack: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.TouchMin)
      .clickable(onClick = onBack)
      .padding(horizontal = PiSpace.Screen),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(PiIcons.back),
      contentDescription = null,
      tint = PiSecondary,
      modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.width(PiSpace.x2))
    Text("Settings", style = PiType.Ui, color = PiSecondary)
  }
  PiRule()
}

/** A sheet body: the bounded outer box is already applied; this scrolls inside it. */
@Composable
internal fun SheetBody(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Column(
    modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
  ) {
    content()
    Spacer(Modifier.height(20.dp))
  }
}

/** Centered monospace sheet title with a hairline rule. Tight and flat. */
@Composable
internal fun SheetTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
  Row(
    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text, style = PiType.Title, color = PiForeground, modifier = Modifier.weight(1f))
    trailing?.invoke()
  }
  PiRule()
}

/** A section heading inside a sheet. */
@Composable
internal fun SectionLabel(text: String, top: Int = 10) {
  PiLabel(
    text = text,
    color = PiSecondary,
    modifier = Modifier.padding(top = top.dp, bottom = PiSpace.x1),
  )
}

/** Underline field. No outline box, no radius. */
@Composable
internal fun FlatField(value: String, onValueChange: (String) -> Unit, placeholder: String = "", modifier: Modifier = Modifier) {
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.fillMaxWidth(),
    singleLine = true,
    textStyle = PiType.BodySmall.copy(color = PiForeground),
    cursorBrush = SolidColor(PiAccent),
    decorationBox = { inner ->
      Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
          if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = PiSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
          }
          inner()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
      }
    },
  )
}

/** A full-row choice; the whole band is the touch target. An unselected label is [PiMuted]. */
@Composable
internal fun PopupChoice(label: String, selected: Boolean, color: Color = PiMuted, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.Touch)
      .clickable(onClick = onClick)
      .padding(end = PiSpace.Screen),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .width(PiSpace.Rail)
        .height(24.dp)
        .background(if (selected) PiAccent else Color.Transparent),
    )
    Spacer(Modifier.width(PiSpace.x3))
    Text(
      label,
      style = PiType.Ui,
      color = if (selected) PiForeground else color,
      modifier = Modifier.weight(1f),
    )
  }
}

/** One tappable line of monospace action text. */
@Composable
internal fun ActionRow(label: String, color: Color = PiForeground, onClick: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.TouchMin)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(label, style = PiType.Ui, color = color)
  }
}

// ---------------------------------------------------------------------------
// Workspace sheet
// ---------------------------------------------------------------------------

@Composable
internal fun PiLogo(modifier: Modifier = Modifier) {
  Icon(
    painter = painterResource(PiIcons.logo),
    contentDescription = "pi",
    tint = Color.Unspecified,
    modifier = modifier.size(18.dp),
  )
}

/**
 * The composer action: the send glyph while idle, the stop glyph only while a reply streams on a
 * blank draft. [label] is the mode the tap will run — `send`, `steer`, `queue` or `stop` — so the
 * control announces what it will actually do; a long press always queues a follow-up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerAction(
  showStop: Boolean,
  enabled: Boolean,
  label: String,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val fill = when {
    !enabled -> Color.Transparent
    showStop -> PiError
    else -> PiForeground
  }
  Box(
    Modifier
      .size(PiSpace.TouchMin)
      .background(fill)
      .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(if (showStop) PiIcons.stop else PiIcons.send),
      contentDescription = label,
      tint = if (enabled) PiBackground else PiSecondary,
      modifier = Modifier.size(18.dp),
    )
  }
}

/** A folding chevron; rotates to point up when its section is open. */
@Composable
internal fun FoldChevron(expanded: Boolean, tint: Color) {
  Icon(
    painter = painterResource(PiIcons.chevron),
    contentDescription = null,
    tint = tint,
    modifier = Modifier.padding(end = 6.dp).size(14.dp).rotate(if (expanded) 0f else 180f),
  )
}

/**
 * Plain-language summary of what this build can actually attempt for [provider]: the sign-in
 * transports pi-ai's own flow needs, and why a transport is missing. Shown instead of implying a
 * subscription button works because the provider merely advertises OAuth.
 */
internal fun capabilityNote(provider: DurableEvent.CatalogProvider?): String {
  val target = provider ?: return "provider not loaded yet"
  val signIn = buildList {
    if (target.apiKey) add("API key")
    if (target.oauth) {
      val mechanisms = buildList {
        if (target.oauthDeviceCode) add("device code")
        if (target.oauthLoopback) add("browser loopback" + if (target.oauthLoopbackRequired) " (required)" else "")
        if (target.oauthManualCode) add("pasted code")
      }
      add(
        if (!target.oauthMechanismKnown) "subscription (flow not source-reviewed)"
        else "subscription: ${mechanisms.joinToString(" / ").ifEmpty { "no usable transport" }}",
      )
    }
  }.ifEmpty { listOf("no sign-in method") }
  val operations = buildList {
    if (target.chatCount > 0) add("${target.chatCount} chat")
    if (target.imageCount > 0) add("${target.imageCount} image")
    if (target.classifierCount > 0) add("${target.classifierCount} classifier")
  }
  return buildString {
    append("sign-in: ${signIn.joinToString(", ")}")
    if (operations.isNotEmpty()) append("\nmodels: ${operations.joinToString(", ")}")
    if (target.deferred) append("\ndeferred responses: advertised by this provider")
  }
}
