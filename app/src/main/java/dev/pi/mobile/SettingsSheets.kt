package dev.pi.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.backend.WorkspaceBinding
import dev.pi.mobile.chat.ChatModelOption
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.ModelCatalogReducer
import dev.pi.mobile.chat.ModelKind
import dev.pi.mobile.chat.PickerProviderGroup
import dev.pi.mobile.chat.SettingsUiReducer
import dev.pi.mobile.chat.SettingsUiState
import dev.pi.mobile.chat.SignInSessionState
import dev.pi.mobile.theme.PiIconButton
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType

// ---------------------------------------------------------------------------
// The settings sheets themselves
// ---------------------------------------------------------------------------

/**
 * The one settings index. Every row carries its current value; every detail has a named back.
 * Model and Effort are also reachable directly from the composer, so the index is orientation,
 * not a maze.
 */
@Composable
internal fun SettingsIndexSheet(
  workspaceLabel: String,
  modelName: String,
  effort: String,
  runtimeReady: Boolean,
  onModel: () -> Unit,
  onEffort: () -> Unit,
  onWorkspace: () -> Unit,
  onResources: () -> Unit,
  onDismiss: () -> Unit,
) {
  SheetBody {
    SheetTitle(
      "Settings",
      trailing = {
        PiIconButton(
          onClick = onDismiss,
          contentDescription = "Close settings",
          glyph = painterResource(PiIcons.close),
          tint = PiSecondary,
          target = 44.dp,
          glyphSize = 18.dp,
        )
      },
    )
    SettingsIndexRow("Model", modelName.ifBlank { "No model" }, onModel)
    SettingsIndexRow("Thinking effort", effort, onEffort)
    SettingsIndexRow("Workspace", workspaceLabel.ifBlank { "app private" }, onWorkspace)
    SettingsIndexRow("Integrations", "Templates · Extensions · MCP · Operations", onResources)
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(5.dp).background(if (runtimeReady) PiAccent else PiSecondary))
      Spacer(Modifier.width(PiSpace.x2))
      Text(
        if (runtimeReady) "Runtime ready · this session" else "Starting runtime…",
        style = PiType.Meta,
        color = PiSecondary,
      )
    }
  }
}

@Composable
private fun SettingsIndexRow(title: String, value: String, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 58.dp)
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = PiType.Ui, color = PiForeground)
      Text(
        value,
        style = PiType.Meta,
        color = PiSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    Icon(
      painter = painterResource(PiIcons.chevron),
      contentDescription = null,
      tint = PiMuted,
      // The base glyph points up; +90° opens to the right, into the detail.
      modifier = Modifier.size(16.dp).rotate(90f),
    )
  }
  PiRule()
}

@Composable
internal fun WorkspaceSheet(
  workspace: WorkspaceBinding.Resolved?,
  allFilesAccess: Boolean,
  onWorkspaceDefault: () -> Unit,
  onWorkspaceExternal: () -> Unit,
  onWorkspacePickFolder: () -> Unit = {},
  onRequestAllFilesAccess: () -> Unit = {},
  onOpenOperations: () -> Unit,
  onOpenTemplates: () -> Unit = {},
  onOpenExtensions: () -> Unit = {},
  onOpenMcp: () -> Unit = {},
  onRefresh: () -> Unit,
  onDismiss: () -> Unit,
) {
  SheetBody {
    SheetTitle("Workspace & resources")
    Text(
      workspace?.label ?: "app private",
      style = PiType.Ui,
      color = PiForeground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      workspace?.root?.path ?: "—",
      style = PiType.Meta,
      color = PiSecondary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 2.dp, bottom = PiSpace.x2),
    )
    PopupChoice("App private", workspace?.kind == WorkspaceBinding.Kind.APP_PRIVATE) { onWorkspaceDefault(); onDismiss() }
    PopupChoice("App external", workspace?.kind == WorkspaceBinding.Kind.APP_EXTERNAL) { onWorkspaceExternal(); onDismiss() }
    // The folder picker is a real SAF tree: binding it is what makes the picked directory the cwd
    // the agent's tools actually run in. Primary storage only resolves to a real path once All
    // files access is granted; otherwise the binding is an app-private mirror and says so.
    PopupChoice(
      when (workspace?.kind) {
        WorkspaceBinding.Kind.CUSTOM -> "Folder · ${workspace.label}"
        WorkspaceBinding.Kind.SAF_MIRROR -> "Folder mirror · ${workspace.label}"
        else -> "Choose folder…"
      },
      workspace?.kind == WorkspaceBinding.Kind.CUSTOM || workspace?.kind == WorkspaceBinding.Kind.SAF_MIRROR,
    ) { onWorkspacePickFolder(); onDismiss() }
    if (!allFilesAccess) {
      PopupChoice("Enable all files access…", false) { onRequestAllFilesAccess(); onDismiss() }
    } else {
      Text("All files access on", style = PiType.Meta, color = PiSecondary, modifier = Modifier.padding(top = PiSpace.x1))
    }
    SectionLabel("Resources", top = 14)
    // The command and server surfaces the composer's chevron menu deliberately does not hold. Each
    // is its own sheet rather than a list inside this one, and each is one tap from here.
    PopupChoice("Reload models", false) { onRefresh(); onDismiss() }
    PopupChoice("Templates…", false) { onOpenTemplates() }
    PopupChoice("Extensions…", false) { onOpenExtensions() }
    PopupChoice("MCP servers…", false) { onOpenMcp() }
    SectionLabel("Testing", top = 14)
    // Images and classification are one-shot operations rather than chat: they live one tap
    // behind the Workspace sheet instead of crowding the Model and Connect sheets, and they never
    // become a fourth entry in the chevron menu.
    PopupChoice("Images & classifier…", false) { onOpenOperations() }
  }
}

// ---------------------------------------------------------------------------
// Model sheet
// ---------------------------------------------------------------------------

@Composable
internal fun ModelSheet(
  query: String,
  onQuery: (String) -> Unit,
  editingProvider: String?,
  groups: List<PickerProviderGroup>,
  selected: ChatModelOption?,
  onEdit: (String?) -> Unit,
  onToggleHidden: (ChatModelOption, Boolean) -> Unit,
  onHideAll: (String) -> Unit,
  onShowAll: (String) -> Unit,
  onSelect: (ChatModelOption) -> Unit,
  onConnect: () -> Unit,
) {
  val listState = rememberLazyListState()
  val matching = remember(groups, query, editingProvider) {
    SettingsUiReducer.modelGroups(groups, query, editingProvider)
  }
  // Reset for a user filter change, not for catalog refreshes or visibility toggles.
  LaunchedEffect(query, editingProvider) { listState.scrollToItem(0) }
  Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
    SheetTitle(
      "Model",
      trailing = {
        PiIconButton(
          onClick = onConnect,
          contentDescription = "Connect a provider",
          glyph = painterResource(PiIcons.plus),
          tint = PiForeground,
          target = PiSpace.TouchMin,
          glyphSize = 16.dp,
        )
      },
    )
    FlatField(query, onQuery, "Search models")
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
      if (groups.isEmpty()) {
        item(key = "empty") {
          Text(
            "Models appear automatically once a provider is signed in or a key is added.",
            style = PiType.Meta,
            color = PiSecondary,
            modifier = Modifier.padding(vertical = PiSpace.x2),
          )
          ActionRow("Connect a provider", onClick = onConnect)
        }
      } else if (matching.isEmpty()) {
        item(key = "no-results") {
          Text("No model matches that search.", style = PiType.Meta, color = PiSecondary)
        }
      }
      for (group in matching) {
        val editing = editingProvider == group.provider
        item(key = "provider:${group.provider}") {
          Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.providerName, style = PiType.Ui, color = PiSecondary, modifier = Modifier.weight(1f))
            Box(
              Modifier.heightIn(min = PiSpace.TouchMin)
                .semantics { contentDescription = "Choose which ${group.providerName} models stay in this list" }
                .clickable { onEdit(if (editing) null else group.provider) }
                .padding(horizontal = PiSpace.x2),
              contentAlignment = Alignment.Center,
            ) {
              Text(if (editing) "Done" else "Edit", color = PiSecondary, style = PiType.UiSmall)
            }
          }
          PiRule()
          if (editing) {
            Text("Checked models stay in this list", style = PiType.Meta, color = PiSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              ActionRow("hide all", modifier = Modifier.weight(1f)) { onHideAll(group.provider) }
              ActionRow("show all", modifier = Modifier.weight(1f)) { onShowAll(group.provider) }
            }
          } else if (group.visible.isEmpty()) {
            Text("All models hidden", style = PiType.Meta, color = PiSecondary)
          }
        }
        val rows = if (editing) group.models else group.visible
        items(rows.size, key = { index -> "model:${group.provider}:${rows[index].modelId}" }) { index ->
          val option = rows[index]
          if (editing) {
            val shown = option.modelId !in group.hiddenIds
            ModelVisibilityRow(option, shown) { onToggleHidden(option, shown) }
          } else {
            ModelChoice(option, selected?.provider == option.provider && selected.modelId == option.modelId) { onSelect(option) }
          }
        }
      }
      item(key = "bottom-space") { Spacer(Modifier.height(20.dp)) }
    }
  }
}

@Composable
private fun ModelChoice(option: ChatModelOption, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.Touch)
      .semantics { this.selected = selected }
      .clickable(role = Role.RadioButton, onClick = onClick)
      .padding(end = PiSpace.x1),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .width(PiSpace.Rail)
        .height(26.dp)
        .background(if (selected) PiAccent else androidx.compose.ui.graphics.Color.Transparent),
    )
    Spacer(Modifier.width(PiSpace.x3))
    Column(Modifier.weight(1f)) {
      Text(option.name, style = PiType.Ui, color = if (selected) PiForeground else PiSecondary, maxLines = 2)
      val line = ModelCatalogReducer.infoLine(option)
      if (line.isNotBlank()) Text(line, style = PiType.Meta, color = PiMuted)
    }
    if (selected) {
      Icon(
        painter = painterResource(PiIcons.check),
        contentDescription = null,
        tint = PiAccent,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

/** A checklist row: a filled box means "keep in this list", never the selection checkmark. */
@Composable
private fun ModelVisibilityRow(option: ChatModelOption, shown: Boolean, onToggle: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 40.dp)
      .clickable(role = Role.Checkbox, onClickLabel = if (shown) "Hide from picker" else "Show in picker", onClick = onToggle)
      .semantics { stateDescription = if (shown) "Shown in picker" else "Hidden from picker" }
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(14.dp).border(1.dp, if (shown) PiAccent else PiMuted),
      contentAlignment = Alignment.Center,
    ) {
      if (shown) {
        Icon(
          painter = painterResource(PiIcons.check),
          contentDescription = null,
          tint = PiAccent,
          modifier = Modifier.size(12.dp),
        )
      }
    }
    Spacer(Modifier.width(PiSpace.x2))
    Column(Modifier.weight(1f)) {
      Text(option.name, style = PiType.Ui, color = if (shown) PiForeground else PiSecondary, maxLines = 2)
      val line = ModelCatalogReducer.infoLine(option)
      if (line.isNotBlank()) Text(line, style = PiType.Meta, color = PiMuted)
    }
  }
}

// ---------------------------------------------------------------------------
// Effort sheet
// ---------------------------------------------------------------------------

@Composable
internal fun EffortSheet(selected: ChatModelOption?, effort: String, onEffort: (String) -> Unit) {
  SheetBody {
    SheetTitle("Thinking effort")
    for (level in ModelCatalogReducer.effortsFor(selected)) {
      PopupChoice(level, level == effort) { onEffort(level) }
    }
    if (selected != null && selected.thinkingLevels.isEmpty()) {
      Text(
        "This model publishes no level map; the provider clamps the request.",
        style = PiType.Meta,
        color = PiSecondary,
        modifier = Modifier.padding(top = PiSpace.x2),
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Connect sheet
// ---------------------------------------------------------------------------

@Composable
internal fun ConnectSheet(
  state: SettingsUiState,
  authRows: List<DurableEvent.AuthRow>,
  providerChoices: List<DurableEvent.CatalogProvider>,
  onQuery: (String) -> Unit,
  onSignIn: (String, String) -> Unit,
  onAnswerPrompt: (String) -> Unit,
  onDismissPrompt: (String) -> Unit,
  onCancelSignIn: () -> Unit,
  onLogout: (String) -> Unit,
  onOpenUrl: (String) -> Unit,
  onRefresh: () -> Unit,
) {
  val groups = remember(providerChoices, authRows, state.connectQuery) {
    SettingsUiReducer.connectGroups(providerChoices, authRows, state.connectQuery)
  }
  // A prompt can arrive after a provider was chosen far down the list. Bring it into view.
  SheetBody(scrollResetKey = state.loginGeneration to state.signIn?.prompt?.requestId) {
    SheetTitle("Connect")
    state.signIn?.let { session ->
      key(state.loginGeneration) {
        SignInSteps(
          session = session,
          onAnswer = onAnswerPrompt,
          onDismissPrompt = onDismissPrompt,
          onCancel = onCancelSignIn,
          onOpenUrl = onOpenUrl,
        )
      }
      HorizontalDivider(color = PiHairline, modifier = Modifier.padding(vertical = 8.dp))
    }
    SectionLabel("Providers", top = 4)
    FlatField(state.connectQuery, onQuery, "Search providers")
    if (providerChoices.isEmpty()) {
      Text("The provider catalog is still loading.", color = PiSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    } else if (groups.isEmpty()) {
      Text("No provider matches that search.", color = PiSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
    for (group in groups) {
      SectionLabel(group.title, top = 12)
      for (row in group.rows) {
        key(row.provider, row.authType) {
          PopupChoice(row.label + if (row.configured) " · connected" else "", row.configured, enabled = state.signIn == null) {
            onSignIn(row.provider, row.authType)
          }
        }
      }
    }
    val stored = authRows.filter { it.configured }
    if (stored.isNotEmpty()) {
      SectionLabel("Stored sign-ins", top = 16)
      for (row in stored) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(row.name.ifBlank { row.provider }, style = PiType.Ui, color = PiForeground)
            Text(
              listOfNotNull(row.source, row.type, row.detail).joinToString(" · ").ifBlank { "configured" },
              style = PiType.Meta,
              color = PiSecondary,
            )
          }
          ActionRow("log out", PiError, modifier = Modifier.width(80.dp)) { if (state.signIn == null) onLogout(row.provider) }
        }
      }
    }
    ActionRow("reload models") { onRefresh() }
  }
}

/**
 * The live part of a sign-in: notices straight from `interaction.notify`, and the prompt the flow
 * is blocked on. Answering resolves exactly that upstream call; the provider's own semantics are
 * not reimplemented here.
 */
@Composable
private fun SignInSteps(
  session: SignInSessionState,
  onAnswer: (String) -> Unit,
  onDismissPrompt: (String) -> Unit,
  onCancel: () -> Unit,
  onOpenUrl: (String) -> Unit,
) {
  var typed by remember(session.prompt?.requestId) { mutableStateOf("") }
  Text(
    when {
      session.authType == SettingsUiReducer.AUTH_TYPE_API_KEY -> "${session.name} · API key sign-in"
      session.subscription -> "${session.name} · subscription sign-in"
      else -> "${session.name} · sign-in"
    },
    color = PiSecondary,
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
  )
  // A device-code sign-in is two numbered steps and a spinner, as the approved sign-in screen had
  // it — the code is what the user types into the provider's page, so it is the largest text here.
  session.deviceCode?.let { (code, uri) ->
    SectionLabel("Device code sign-in", top = 10)
    Text("1. Open $uri in any browser", color = PiForeground, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    Text("2. Enter code:  $code", color = PiForeground, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    session.deviceCodeMinutes?.let {
      Text("expires in $it min", color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
    if (session.busy) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(11.dp), color = PiSecondary, strokeWidth = 1.5.dp)
        Text("  Waiting for approval…", color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
      }
    }
  }
  for (line in session.notices) {
    // The device code is already shown as the two numbered steps above, not twice.
    if (line == session.deviceCodeNotice) continue
    Text(line, color = PiForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
  }
  session.openedUrl?.let { url -> ActionRow("open in browser") { onOpenUrl(url) } }
  val prompt = session.prompt
  if (prompt != null) {
    Text(prompt.message.ifBlank { "Sign-in step" }, color = PiForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
    when (prompt.type) {
      "select" -> {
        for (option in prompt.options) {
          Column(Modifier.padding(top = 4.dp)) {
            PopupChoice(option.label, false) { onAnswer(option.id) }
            option.description?.let { Text(it, color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
          }
        }
      }
      else -> {
        FlatField(typed, { typed = it }, prompt.placeholder ?: if (prompt.type == "secret") "secret" else "value", secret = prompt.type == "secret")
        ActionRow("submit") { onAnswer(typed) }
      }
    }
    ActionRow("skip this step") { onDismissPrompt(prompt.requestId) }
  } else if (session.busy) {
    Text("waiting for the provider…", color = PiSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
  }
  session.error?.let { Text(it, color = PiError, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp)) }
  session.settled?.let { Text("signed in · $it", color = PiAccent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp)) }
  // Cancellable whenever the flow has not finished — including while a prompt is open.
  if (session.busy || session.prompt != null) ActionRow("cancel sign-in", PiError, onClick = onCancel)
  else ActionRow("Done", onClick = onCancel)
}

// ---------------------------------------------------------------------------
// Operations sheet (images + classification)
// ---------------------------------------------------------------------------

@Composable
internal fun OperationsSheet(
  kind: ModelKind,
  modelId: String,
  models: List<ChatModelOption>,
  query: String,
  imagePrompt: String,
  classifyState: String,
  classifyQuestion: String,
  result: String?,
  providerNote: String,
  onKind: (ModelKind) -> Unit,
  onQuery: (String) -> Unit,
  onModel: (ChatModelOption) -> Unit,
  onImagePrompt: (String) -> Unit,
  onClassifyState: (String) -> Unit,
  onClassifyQuestion: (String) -> Unit,
  onRunImages: () -> Unit,
  onRunClassify: () -> Unit,
) {
  SheetBody {
    SheetTitle("Operations")
    Text(
      providerNote.ifBlank { "No provider selected; pick a chat model first." },
      color = PiSecondary,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
    )
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      for (option in listOf(ModelKind.Image, ModelKind.Classifier)) {
        Text(
          if (option == kind) "[${option.label}]" else option.label,
          color = if (option == kind) PiForeground else PiSecondary,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.clickable { onKind(option) }.padding(vertical = 6.dp),
        )
      }
    }
    FlatField(query, onQuery, "Search ${kind.label} models")
    val matches = models.filter { ModelCatalogReducer.matches(it, query) }
    Text("${matches.size} of ${models.size} ${kind.label} models", color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    if (models.isEmpty()) {
      Text(
        "This provider exposes no ${kind.label} models in this build.",
        color = PiSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
    for (option in matches) {
      ModelChoice(option, option.modelId == modelId) { onModel(option) }
    }
    when (kind) {
      ModelKind.Image -> {
        FlatField(imagePrompt, onImagePrompt, "Image prompt")
        ActionRow("run image generation") { onRunImages() }
      }
      else -> {
        FlatField(classifyState, onClassifyState, "state JSON")
        FlatField(classifyQuestion, onClassifyQuestion, "question")
        ActionRow("run classification") { onRunClassify() }
      }
    }
    result?.let { Text(it, color = PiForeground, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp)) }
  }
}

// ---------------------------------------------------------------------------
// Shared chrome
// ---------------------------------------------------------------------------

/** Drawables used by the chrome; kept in one place so a rename cannot half-apply. */
internal object PiIcons {
  val logo = R.drawable.pi_logo
  /** The supplied history artwork: a transparent glyph, used as-is with no tile behind it. */
  val history = R.drawable.history_icon
  val chevron = R.drawable.chevron_up
  val send = R.drawable.send_arrow_circle
  val stop = R.drawable.stop_circle
  val plus = R.drawable.plus_icon
  val settings = R.drawable.settings_icon
  val close = R.drawable.close_icon
  val search = R.drawable.search_icon
  val check = R.drawable.check_icon
  val back = R.drawable.back_icon
  val attach = R.drawable.attach_icon
}

/** The genuine Pi mark with no wordmark beside it. */
