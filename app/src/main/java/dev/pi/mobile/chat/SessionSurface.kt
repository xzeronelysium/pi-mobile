package dev.pi.mobile.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import dev.pi.mobile.ComposerAction
import dev.pi.mobile.FoldChevron
import dev.pi.mobile.PiAccent
import dev.pi.mobile.PiBackground
import dev.pi.mobile.PiSurface
import dev.pi.mobile.PiError
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiHairline
import dev.pi.mobile.PiMuted
import dev.pi.mobile.PiSecondary
import dev.pi.mobile.theme.PiIconButton
import dev.pi.mobile.theme.PiLabel
import dev.pi.mobile.theme.PiSelectedRail
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType

/**
 * The session surface: the right-edge history drawer's panel, the composer dock, and the dialogs the
 * two of them reach.
 *
 * The panel is inset so it never draws under the system bars, and it is painted in the transcript's
 * own colour with a 1 dp hairline border — there is no raised panel token. Everything here reads
 * the sibling reducers — [SessionListReducer] for the list,
 * [ConversationTreeReducer] for the tree, [InboxReducer] for the queue — and the decisions
 * themselves live in [SessionDrawerReducer], [ComposerDockReducer] and [SessionDialogsReducer],
 * which are unit-tested without a device.
 */

/** The drawer's panel: title, workspace, current session, search, the list, then the tree section. */
@Composable
internal fun HistoryDrawerPanel(
  workspaceLine: String,
  state: SessionDrawerState,
  list: SessionList,
  tree: ConversationTree,
  now: Long,
  busy: Boolean,
  onQuery: (String) -> Unit,
  onNew: () -> Unit,
  onSwitch: (SessionRow) -> Unit,
  onRename: (SessionRow) -> Unit,
  onFork: (TreeNodeRow) -> Unit,
  onToggleTree: () -> Unit,
  onOverflow: (Boolean) -> Unit,
  onExport: (String) -> Unit,
  onCopyLast: () -> Unit,
  onClose: () -> Unit,
) {
  val rows = SessionDrawerReducer.rows(list, state.query)
  val treeRows = SessionDrawerReducer.treeRows(state.treeExpanded, tree)
  Column(
    Modifier
      .fillMaxHeight()
      // 84 % of the screen, from the drawer's own contract: the panel scales with the device.
      .width(SessionDrawerLayout.panelWidthDp(LocalConfiguration.current.screenWidthDp).dp)
      // The bars come off the panel's own rectangle *before* it is painted (the contract's
      // PANEL_LAYER_ORDER), so the background and the hairline never reach the window's top or
      // bottom edge — and the panel's title row clears the status bar the app's header sits under.
      .statusBarsPadding()
      .navigationBarsPadding()
      .background(PiBackground)
      // Background with a 1 dp hairline, square corners: a menu surface, not a raised sheet.
      .border(1.dp, PiHairline),
  ) {
    DrawerTitle(
      title = "Sessions",
      subtitle = workspaceLine,
      trailing = {
        Box {
          Box(
            Modifier
              .size(PiSpace.TouchMin)
              .clickable { onOverflow(true) }
              .semantics { contentDescription = "Session exports" },
            contentAlignment = Alignment.Center,
          ) {
            Text("···", color = PiSecondary, fontSize = 16.sp)
          }
          DropdownMenu(
            expanded = state.overflowOpen,
            onDismissRequest = { onOverflow(false) },
            shape = RectangleShape,
            containerColor = PiBackground,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.border(1.dp, PiHairline),
          ) {
            DropdownMenuItem(
              text = { Text("Export Markdown", style = PiType.Ui, color = PiForeground) },
              enabled = SessionDrawerReducer.canExport(busy, list),
              onClick = { onOverflow(false); onExport(EXPORT_MARKDOWN) },
            )
            DropdownMenuItem(
              text = { Text("Export HTML", style = PiType.Ui, color = PiForeground) },
              enabled = SessionDrawerReducer.canExport(busy, list),
              onClick = { onOverflow(false); onExport(EXPORT_HTML) },
            )
            DropdownMenuItem(
              text = { Text("Export JSONL", style = PiType.Ui, color = PiForeground) },
              enabled = SessionDrawerReducer.canExport(busy, list),
              onClick = { onOverflow(false); onExport(EXPORT_JSONL) },
            )
            DropdownMenuItem(
              text = { Text("Copy last answer", style = PiType.Ui, color = PiForeground) },
              enabled = !busy,
              onClick = { onOverflow(false); onCopyLast() },
            )
          }
        }
        PiIconButton(
          onClick = onClose,
          contentDescription = "Close sessions",
          glyph = painterResource(dev.pi.mobile.PiIcons.close),
          tint = PiSecondary,
          target = PiSpace.TouchMin,
          glyphSize = 18.dp,
        )
      },
    )
    DrawerField(state.query, onQuery, "Search sessions")
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .clickable(enabled = !busy) { onNew() }
        .padding(horizontal = PiSpace.Screen),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        painter = painterResource(dev.pi.mobile.PiIcons.plus),
        contentDescription = null,
        tint = PiAccent,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(PiSpace.x3))
      Text("New session", style = PiType.Ui, color = if (busy) PiSecondary else PiForeground)
    }
    PiRule()
    when {
      rows.isEmpty() -> DrawerNote("No saved sessions in this workspace yet.")
      else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        for (group in SessionDrawerReducer.groups(list, state.query, now)) {
          item(key = "header-${group.header}") {
            PiLabel(
              group.header,
              color = PiSecondary,
              modifier = Modifier.padding(start = PiSpace.Screen, top = PiSpace.x3, bottom = PiSpace.x1),
            )
          }
          items(group.rows, key = { "${group.header}-${it.id}" }) { row ->
            SessionRowItem(
              row = row,
              now = now,
              busy = busy,
              onOpen = { onSwitch(row) },
              onRename = { onRename(row) },
            )
          }
        }
        item(key = "unsaved") {
          if (rows.isNotEmpty() && rows.none { it.current }) {
            Text(
              "This chat is not saved yet.",
              style = PiType.Meta,
              color = PiSecondary,
              modifier = Modifier.padding(horizontal = PiSpace.Screen, vertical = PiSpace.x2),
            )
          }
        }
      }
    }
    if (rows.isEmpty()) Spacer(Modifier.weight(1f))
    PiRule()
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = PiSpace.Touch)
        .clickable(enabled = !busy) { onToggleTree() }
        .padding(horizontal = PiSpace.Screen),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FoldChevron(state.treeExpanded, PiSecondary)
      Text("Session tree", style = PiType.Ui, color = PiForeground)
      Text(
        " ${tree.rows.size}",
        style = PiType.Meta,
        color = PiSecondary,
        modifier = Modifier.weight(1f),
      )
    }
    if (state.treeExpanded) {
      PiRule()
      Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(bottom = PiSpace.x2)) {
        if (treeRows.isEmpty()) {
          DrawerNote("No tree entries yet.")
        }
        for (node in treeRows) {
          Row(
            Modifier
              .fillMaxWidth()
              .heightIn(min = 44.dp)
              .padding(start = PiSpace.Screen + (node.depth * 12).dp, end = PiSpace.x2),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (node.current) {
              PiSelectedRail(height = 24.dp)
              Spacer(Modifier.width(PiSpace.x2))
            }
            Text(
              node.title,
              style = PiType.BodySmall,
              color = when {
                node.current -> PiForeground
                node.onPath -> TREE_ON_PATH
                else -> PiSecondary
              },
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            if (SessionDrawerReducer.canFork(busy, node)) {
              Text(
                "fork",
                style = PiType.UiSmall,
                color = if (busy) PiSecondary else PiForeground,
                modifier = Modifier
                  .clickable(enabled = !busy) { onFork(node) }
                  .padding(horizontal = PiSpace.x2, vertical = PiSpace.x2),
              )
            }
          }
        }
      }
    }
  }
}

/** One session: current rail, name, age/message line, and a visible row action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(row: SessionRow, now: Long, busy: Boolean, onOpen: () -> Unit, onRename: () -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.Row)
      .background(if (row.current) PiSurface else PiBackground)
      .combinedClickable(
        enabled = SessionDrawerReducer.canSwitch(busy, row),
        onClick = onOpen,
        onLongClick = onRename,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier
        .width(PiSpace.Rail)
        .height(28.dp)
        .background(if (row.current) PiAccent else Color.Transparent),
    )
    Column(Modifier.weight(1f).padding(start = PiSpace.x3, end = PiSpace.x2)) {
      Text(
        row.name,
        style = PiType.Ui,
        color = if (row.current) PiForeground else PiSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        SessionDrawerReducer.rowDetail(row, now),
        style = PiType.Meta,
        color = PiMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    if (row.current) {
      PiLabel("Now", color = PiAccent, modifier = Modifier.padding(end = PiSpace.x2))
    }
    Box {
      Box(
        Modifier
          .size(PiSpace.TouchMin)
          .combinedClickable(enabled = !busy, onClick = { menuOpen = true }, onLongClick = onRename),
        contentAlignment = Alignment.Center,
      ) {
        Text("···", color = PiSecondary, fontSize = 16.sp)
      }
      DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        shape = RectangleShape,
        containerColor = PiBackground,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.border(1.dp, PiHairline),
      ) {
        DropdownMenuItem(
          text = { Text("Rename", style = PiType.Ui, color = PiForeground) },
          enabled = !busy,
          onClick = { menuOpen = false; onRename() },
        )
      }
    }
  }
}

@Composable
private fun DrawerTitle(title: String, subtitle: String, trailing: @Composable () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = PiSpace.Screen, end = PiSpace.x1, top = PiSpace.x2, bottom = PiSpace.x2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = PiType.Title, color = PiForeground)
      Text(
        subtitle,
        style = PiType.Meta,
        color = PiSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
  }
  PiRule()
}

@Composable
private fun DrawerNote(text: String) {
  Text(
    text,
    style = PiType.Meta,
    color = PiSecondary,
    modifier = Modifier.padding(horizontal = PiSpace.Screen, vertical = PiSpace.x3),
  )
}

/** The drawer's search field: an icon, an underline, no box. */
@Composable
private fun DrawerField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = PiSpace.Screen),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(dev.pi.mobile.PiIcons.search),
      contentDescription = null,
      tint = PiMuted,
      modifier = Modifier.size(14.dp),
    )
    Spacer(Modifier.width(PiSpace.x2))
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      singleLine = true,
      textStyle = PiType.BodySmall.copy(color = PiForeground),
      cursorBrush = SolidColor(PiAccent),
      decorationBox = { inner ->
        Column(Modifier.fillMaxWidth()) {
          Box(Modifier.fillMaxWidth().padding(vertical = PiSpace.x2)) {
            if (value.isEmpty()) {
              Text(placeholder, style = PiType.BodySmall, color = PiSecondary.copy(alpha = 0.7f))
            }
            inner()
          }
          PiRule()
        }
      },
    )
  }
}

/**
 * The one bottom dock. Top to bottom, in the order the approved surface used:
 *
 * 1. the model · effort · usage · queue-label · settings-chevron row,
 * 2. the busy hint row naming the mode, with its follow-up toggle,
 * 3. the slash autocomplete (skills only, inserts a draft),
 * 4. the staged attachment chips,
 * 5. a 1 dp hairline rule,
 * 6. the input row: attach control, prompt marker, the field, the send/stop control.
 *
 * All of it is painted in the transcript's own colour, so no contrasting slab appears, and the
 * insets are consumed here so the composer stays above the navigation bar and the keyboard.
 *
 * The control is the stop glyph only while a reply streams *and* the composer is blank; a non-blank
 * draft steers mid-run or queues a follow-up, exactly as [ChatRunReducer.mode] decides. A tap sends
 * (aborts while it is the stop glyph) and a long press queues a follow-up. [picker] is the settings
 * chevron and its menu; it is the last slot and it owns the right edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerDock(
  dock: ComposerDockState,
  draft: String,
  busy: Boolean,
  ready: Boolean,
  followUp: Boolean,
  onDraft: (String) -> Unit,
  onSend: () -> Unit,
  onFollowUpSend: () -> Unit,
  onToggleFollowUp: () -> Unit,
  onStop: () -> Unit,
  onModel: () -> Unit,
  onCompact: () -> Unit,
  onQueue: () -> Unit,
  attachments: List<PendingAttachment> = emptyList(),
  onAttach: () -> Unit = {},
  onRemoveAttachment: (String) -> Unit = {},
  /** The composer's slash autocomplete: skills only, and it inserts a draft rather than running one. */
  slash: @Composable () -> Unit = {},
  picker: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  /** Reports the dock's measured height so the transcript reserves exactly that much room. */
  onSizeChanged: (Int) -> Unit = {},
  workspaceLabel: String = "app private",
) {
  val mode = ChatRunReducer.mode(busy, followUp)
  val blank = draft.isBlank() && attachments.isEmpty()
  // The stop glyph owns the blank draft while a reply streams; a non-blank draft steers or queues.
  val showStop = ComposerDockReducer.sendIsStop(busy) && blank
  val sendEnabled = showStop || (ready && !blank)
  val sendLabel = when {
    showStop -> "stop"
    mode == ChatSendMode.Steer -> "steer"
    mode == ChatSendMode.FollowUp -> "queue"
    else -> "send"
  }
  val hint = when {
    !busy -> null
    followUp -> "Follow-up mode · send queues until the run finishes"
    else -> "Busy · send steers · long-press queues follow-up"
  }
  Column(
    modifier
      .fillMaxWidth()
      .background(PiBackground)
      .onSizeChanged { onSizeChanged(it.height) }
      .navigationBarsPadding()
      .imePadding()
      .padding(bottom = PiSpace.x2),
  ) {
    // One metadata line: model, effort, usage, queue, settings. The model and settings controls
    // are 44 dp targets; the usage line is the context shortcut.
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = PiSpace.TouchMin)
        .padding(start = PiSpace.Screen, end = PiSpace.x1),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        Modifier
          .heightIn(min = PiSpace.TouchMin)
          .widthIn(max = ComposerDockReducer.MODEL_MAX_WIDTH_DP.dp)
          .clickable(onClick = onModel),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          ComposerDockReducer.modelLabel(dock.model),
          style = PiType.UiSmall,
          color = PiSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Icon(
          painter = painterResource(dev.pi.mobile.PiIcons.chevron),
          contentDescription = null,
          tint = PiMuted,
          modifier = Modifier.padding(start = 4.dp).size(12.dp).rotate(180f),
        )
      }
      Text(
        dock.effort,
        style = PiType.UiSmall,
        color = PiMuted,
        modifier = Modifier.padding(start = PiSpace.x2),
      )
      Text(
        ComposerDockReducer.compactUsage(dock.usage),
        style = PiType.Micro,
        color = PiSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .weight(1f)
          .clickable(onClick = onCompact)
          .padding(horizontal = PiSpace.x2),
      )
      ComposerDockReducer.queueLabel(dock.inbox)?.let { badge ->
        Box(
          Modifier
            .size(PiSpace.x6)
            .background(PiHairline)
            .clickable(onClick = onQueue),
          contentAlignment = Alignment.Center,
        ) {
          Text(badge, style = PiType.Micro, color = PiForeground, maxLines = 1)
        }
      }
      picker()
    }
    if (hint != null) {
      Row(
        Modifier.fillMaxWidth().padding(start = PiSpace.Screen, end = PiSpace.x4, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PiLabel(if (followUp) "Follow-up" else "Busy", color = PiAccent)
        Text(
          if (followUp) "send queues until the run finishes" else "send steers · long-press queues",
          style = PiType.Micro,
          color = PiSecondary,
          modifier = Modifier.padding(start = PiSpace.x2).weight(1f),
        )
        Text(
          if (followUp) "steer instead" else "queue follow-up",
          style = PiType.Micro,
          color = if (followUp) PiForeground else PiSecondary,
          modifier = Modifier
            .clickable(onClick = onToggleFollowUp)
            .padding(horizontal = PiSpace.x2, vertical = PiSpace.x1),
        )
      }
    }
    slash()
    if (attachments.isNotEmpty()) {
      Row(
        Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(start = PiSpace.x4, end = PiSpace.x3, top = PiSpace.x1),
        horizontalArrangement = Arrangement.spacedBy(PiSpace.x2),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        attachments.forEach { attachment ->
          AttachmentChip(attachment, onRemove = { onRemoveAttachment(attachment.id) })
        }
      }
    }
    Column(Modifier.fillMaxWidth()) {
      PiRule()
      Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = PiSpace.x2),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AttachControl(enabled = !busy, onAttach = onAttach)
        Box(Modifier.weight(1f).padding(vertical = PiSpace.x2)) {
          if (draft.isBlank()) {
            Text(
              if (attachments.isEmpty()) "Message Pi…" else "Message or send files",
              style = PiType.BodySmall,
              color = PiSecondary.copy(alpha = 0.7f),
            )
          }
          BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = PiType.BodySmall.copy(color = PiForeground),
            cursorBrush = SolidColor(PiForeground),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        Box {
          dev.pi.mobile.ComposerAction(
            showStop = showStop,
            enabled = sendEnabled,
            label = sendLabel,
            onClick = { if (showStop) onStop() else onSend() },
            onLongClick = {
              if (showStop) onStop() else if (busy) onFollowUpSend() else onSend()
            },
          )
        }
      }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = PiSpace.Screen),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (busy) "STREAMING" else if (ready) "READY" else "STARTING",
          style = PiType.Micro,
          color = PiSecondary,
        )
        Text(
          " · $workspaceLabel",
          style = PiType.Micro,
          color = PiMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text("0.1.0", style = PiType.Micro, color = PiMuted)
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/**
 * The queued inputs, read-only: the two ways input can join the conversation, what is waiting in
 * each, and how to withdraw one. Sending a steer or a follow-up happens in the composer, where the
 * mode is visible while the user types — this dialog only shows the queue.
 */
@Composable
internal fun QueueDialog(
  inbox: InboxState,
  onWithdraw: (String) -> Unit,
  onRefresh: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    shape = RectangleShape,
    containerColor = PiBackground,
    tonalElevation = 0.dp,
    modifier = Modifier.border(1.dp, PiHairline),
    titleContentColor = PiForeground,
    textContentColor = PiSecondary,
    title = { Text("Pending messages") },
    text = {
      Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        val sections = SessionDialogsReducer.queueSections(inbox)
        if (sections.all { it.rows.isEmpty() }) {
          Text("Nothing is queued for this conversation.", color = PiSecondary, fontSize = 12.sp)
        }
        for (section in sections) {
          Text(
            section.title,
            color = PiSecondary,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
          )
          Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
          if (section.rows.isEmpty()) {
            Text("—", color = PiSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
          }
          for (item in section.rows) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(
                  InboxReducer.rowLabel(item),
                  color = PiForeground,
                  fontSize = 13.sp,
                  fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  item.status,
                  color = PiSecondary,
                  fontSize = 10.sp,
                  fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
              }
              TextButton(onClick = { onWithdraw(item.id) }) { Text("withdraw", color = PiError) }
            }
          }
        }
        Spacer(Modifier.height(14.dp))
      }
    },
    confirmButton = { TextButton(onClick = onRefresh) { Text("Refresh", color = PiSecondary) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = PiSecondary) } },
  )
}

/** Compact the context, or start a fresh one in the same conversation. */
@Composable
internal fun ContextDialog(
  instructions: String,
  onInstructions: (String) -> Unit,
  busy: Boolean,
  ready: Boolean,
  onCompact: () -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
) {
  val canCompact = SessionDialogsReducer.canCompact(busy, ready)
  AlertDialog(
    onDismissRequest = onDismiss,
    shape = RectangleShape,
    containerColor = PiBackground,
    tonalElevation = 0.dp,
    modifier = Modifier.border(1.dp, PiHairline),
    titleContentColor = PiForeground,
    textContentColor = PiSecondary,
    title = { Text("Context") },
    text = {
      Column {
        Text(
          if (canCompact) "Older messages are summarized; recent ones are kept."
          else "Not while a reply is running or before the runtime is ready.",
          color = PiSecondary,
          fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        BasicTextField(
          value = instructions,
          onValueChange = onInstructions,
          textStyle = TextStyle(color = PiForeground, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
          cursorBrush = SolidColor(PiForeground),
          maxLines = 4,
          enabled = canCompact,
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
        Text("Optional instructions", color = PiSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        TextButton(enabled = !busy && ready, onClick = onReset) {
          Text("Start a new context here", color = PiSecondary)
        }
      }
    },
    confirmButton = {
      TextButton(enabled = canCompact, onClick = onCompact) { Text("Compact", color = PiForeground) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PiSecondary) } },
  )
}

/** One name, one line, and the session it renames. */
@Composable
internal fun RenameSessionDialog(
  original: String,
  draft: String,
  onDraft: (String) -> Unit,
  onSubmit: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    shape = RectangleShape,
    containerColor = PiBackground,
    tonalElevation = 0.dp,
    modifier = Modifier.border(1.dp, PiHairline),
    titleContentColor = PiForeground,
    textContentColor = PiSecondary,
    title = { Text("Rename session") },
    text = {
      Column {
        BasicTextField(
          value = draft,
          onValueChange = onDraft,
          singleLine = true,
          textStyle = TextStyle(color = PiForeground, fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
          cursorBrush = SolidColor(PiForeground),
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
      }
    },
    confirmButton = {
      TextButton(enabled = SessionDialogsReducer.canRename(original, draft), onClick = onSubmit) {
        Text("Rename", color = PiForeground)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PiSecondary) } },
  )
}

/** A plain notice: an export's path, or something the user must read before it is dismissed. */
@Composable
internal fun NoticeDialog(title: String, message: String, onClose: () -> Unit) {
  AlertDialog(
    onDismissRequest = onClose,
    shape = RectangleShape,
    containerColor = PiBackground,
    tonalElevation = 0.dp,
    modifier = Modifier.border(1.dp, PiHairline),
    titleContentColor = PiForeground,
    textContentColor = PiSecondary,
    title = { Text(title, fontWeight = FontWeight.Normal) },
    text = { Text(message, color = PiForeground, fontSize = 13.sp) },
    confirmButton = { TextButton(onClick = onClose) { Text("Done", color = PiForeground) } },
  )
}

/** A tree row on the path to the current conversation: brighter than the rest, dimmer than the leaf. */
private val TREE_ON_PATH = Color(0xFFC6C6C6)

internal const val EXPORT_MARKDOWN = "markdown"
internal const val EXPORT_HTML = "html"
internal const val EXPORT_JSONL = "jsonl"
