package dev.pi.mobile.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.PiBackground
import dev.pi.mobile.PiIcons
import dev.pi.mobile.PiLogo
import dev.pi.mobile.PiSecondary
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiMuted
import dev.pi.mobile.PiAccent
import dev.pi.mobile.PiHairline
import dev.pi.mobile.theme.PiLabel
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType
import dev.pi.mobile.SettingsChevron
import dev.pi.mobile.backend.WorkspaceBinding

/**
 * Everything the session surface asks the Activity to do, in one stable object: the drawer's own
 * actions, the conversation switches and forks, and the queue/context/rename calls.
 */
internal class SessionActions(
  val openHistory: (Boolean) -> Unit,
  val closeHistory: () -> Unit,
  val setQuery: (String) -> Unit,
  val setOverflow: (Boolean) -> Unit,
  val toggleTree: () -> Unit,
  val newSession: () -> Unit,
  val switchSession: (SessionRow) -> Unit,
  val startRename: (SessionRow) -> Unit,
  val setRenaming: (String) -> Unit,
  val submitRename: () -> Unit,
  val cancelRename: () -> Unit,
  val fork: (TreeNodeRow) -> Unit,
  val export: (String) -> Unit,
  val copyLast: () -> Unit,
  val withdraw: (String) -> Unit,
  val refreshQueue: () -> Unit,
  val setCompactInstructions: (String) -> Unit,
  val compact: () -> Unit,
  val resetContext: () -> Unit,
)

// ---------------------------------------------------------------------------
// Presentation
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
  workspace: WorkspaceBinding.Resolved?,
  allFilesAccess: Boolean,
  ready: Boolean,
  busy: Boolean,
  transcript: List<ChatEntry>,
  draft: String,
  noticeDialog: String?,
  settings: SettingsUiState,
  sessions: SessionList,
  tree: ConversationTree,
  usage: UsageState,
  inbox: InboxState,
  drawer: SessionDrawerState,
  compactInstructions: String,
  commands: CommandCatalog,
  mcp: McpState,
  commandQuery: String,
  dock: ComposerDockState,
  actions: SessionActions,
  attachments: List<PendingAttachment>,
  fold: TranscriptFoldState,
  /** True while a send would queue a follow-up instead of steering the run in flight. */
  followUp: Boolean,
  /** Bumped by the host's content pushes so the transcript can follow the newest row. */
  followEpoch: Long,
  onDraft: (String) -> Unit,
  onSend: () -> Unit,
  onFollowUpSend: () -> Unit,
  onToggleFollowUp: () -> Unit,
  onStop: () -> Unit,
  onAttach: () -> Unit,
  onRemoveAttachment: (String) -> Unit,
  onToggleWork: (String) -> Unit,
  onToggleTool: (String) -> Unit,
  onOpenAttachment: (AttachedFileRef) -> Unit,
  onChevron: () -> Unit,
  onMenuEntry: (PickerSheet) -> Unit,
  onDismissNoticeDialog: () -> Unit,
  onWorkspacePickFolder: () -> Unit = {},
  onRequestAllFilesAccess: () -> Unit = {},
  // The command surfaces: the composer's slash autocomplete and the app-owned sheets behind the
  // Workspace sheet. Every one of them writes a draft or runs a local command; none of them
  // expands anything into the transcript.
  slashRows: List<CommandRow> = emptyList(),
  onInsertCommand: (CommandRow) -> Unit = {},
  onCommandQuery: (String) -> Unit = {},
  onRunLocal: (CommandRow, String) -> Unit = { _, _ -> },
  onMcpAdd: () -> Unit = {},
  onMcpRemove: (String) -> Unit = {},
  onMcpConnect: (String) -> Unit = {},
  onMcpDisconnect: (String) -> Unit = {},
  onMcpRefresh: () -> Unit = {},
  onMcpDraftName: (String) -> Unit = {},
  onMcpDraftUrl: (String) -> Unit = {},
) {
  val historyDrawer = rememberDrawerState(DrawerValue.Closed)
  // The drawer opens only from the history glyph, so the pure state drives the animation; a swipe
  // or a tap on the dim reports itself back here.
  LaunchedEffect(drawer.open) {
    if (drawer.open) historyDrawer.open() else historyDrawer.close()
  }
  LaunchedEffect(historyDrawer.isOpen) {
    if (!historyDrawer.isOpen && drawer.open) actions.closeHistory()
  }
  // Relative ages are computed once per list, not per frame.
  val now = remember(sessions) { System.currentTimeMillis() }

  var queueOpen by remember { mutableStateOf(false) }
  var contextOpen by remember { mutableStateOf(false) }
  // The measured height of the dock (composer + chrome + IME padding): the transcript's bottom
  // reserve tracks it instead of guessing a fixed gap.
  var bottomOverlayHeightPx by remember { mutableStateOf(0) }

  // The drawer's *container* takes the contract's Rtl: material anchors the sheet to the
  // composition's leading edge, so Rtl makes the leading edge the right one and the panel slides
  // in from there. The drawer's own edge places the panel — nothing here animates it by hand.
  CompositionLocalProvider(LocalLayoutDirection provides SessionDrawerLayout.CONTAINER_DIRECTION) {
    ModalNavigationDrawer(
      drawerState = historyDrawer,
      // No edge-swipe open; while open, material's own scrim tap and dismiss drag keep working.
      gesturesEnabled = historyDrawer.isOpen,
      scrimColor = Color(0x99000000),
      drawerContent = {
        // The panel and everything in it go back to Ltr, so none of the drawer's own content —
        // its title, workspace line, search field, rows and date bands — is mirrored.
        CompositionLocalProvider(LocalLayoutDirection provides SessionDrawerLayout.CONTENT_DIRECTION) {
          // The container is a square, transparent, zero-inset layout box. Square because material's
          // own rounded elevated sheet would otherwise show at the panel's corners; transparent
          // because material applies the sheet's windowInsets to its *content*, inside the surface's
          // own background — a container colour would still paint the window's full height, under
          // both system bars. The panel paints itself below its own insets (SessionDrawerLayout).
          ModalDrawerSheet(
            drawerShape = RectangleShape,
            drawerContainerColor = Color.Transparent,
            windowInsets = WindowInsets(0, 0, 0, 0),
          ) {
            HistoryDrawerPanel(
              workspaceLine = SessionDrawerReducer.workspaceLine(workspace?.label, allFilesAccess),
              state = drawer,
              list = sessions,
              tree = tree,
              now = now,
              busy = busy,
              onQuery = actions.setQuery,
              onNew = actions.newSession,
              onSwitch = actions.switchSession,
              onRename = actions.startRename,
              onFork = actions.fork,
              onToggleTree = actions.toggleTree,
              onOverflow = actions.setOverflow,
              onExport = actions.export,
              onCopyLast = actions.copyLast,
              onClose = actions.closeHistory,
            )
          }
        }
      },
    ) {
      // The app's own content sits inside the Rtl container above and re-provides Ltr here, so the
      // header, the transcript and the dock keep their left-to-right layout.
      CompositionLocalProvider(LocalLayoutDirection provides SessionDrawerLayout.CONTENT_DIRECTION) {
        // The transcript fills the screen and the dock floats over its foot — exactly the approved
        // stack — so the list's own bottom reserve (the dock's measured height) is what keeps the
        // newest row clear of the composer.
        Box(Modifier.fillMaxSize().background(PiBackground)) {
          Column(Modifier.fillMaxSize()) {
            Header(
              title = SessionDrawerReducer.currentName(sessions) ?: "New session",
              onHistory = { actions.openHistory(false) },
            )
            if (transcript.isEmpty()) {
              EmptySessionState(
                workspaceLabel = workspace?.label ?: "app private",
                onAsk = { onDraft("What can you do in this workspace?") },
                onAttach = onAttach,
                onSessions = { actions.openHistory(false) },
                bottomReservePx = bottomOverlayHeightPx,
                modifier = Modifier.weight(1f).fillMaxWidth(),
              )
            } else {
              Transcript(
                entries = transcript,
                fold = fold,
                followEpoch = followEpoch,
                skills = commands.skills,
                onToggleWork = onToggleWork,
                onToggleTool = onToggleTool,
                onOpenAttachment = onOpenAttachment,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                bottomOverlayHeightPx = bottomOverlayHeightPx,
              )
            }
          }
          // One dock: the metadata row above the input row, the whole thing painted in the transcript's
          // own colour with no contrasting slab. Staged attachment chips ride above the input, and the
          // slash autocomplete above those.
          ComposerDock(
            dock = dock,
            draft = draft,
            busy = busy,
            ready = ready,
            followUp = followUp,
            onDraft = onDraft,
            onSend = onSend,
            onFollowUpSend = onFollowUpSend,
            onToggleFollowUp = onToggleFollowUp,
            onStop = onStop,
            onModel = { onMenuEntry(PickerSheet.Model) },
            onCompact = { contextOpen = true },
            onQueue = { queueOpen = true; actions.refreshQueue() },
            attachments = attachments,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            slash = { SlashAutocomplete(slashRows) { row -> onInsertCommand(row) } },
            picker = {
              SettingsChevron(open = settings.sheet != null, onToggle = onChevron)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            onSizeChanged = { bottomOverlayHeightPx = it },
            workspaceLabel = workspace?.label ?: "app private",
          )
        }
      }
    }
  }

  if (queueOpen) {
    QueueDialog(
      inbox = inbox,
      onWithdraw = actions.withdraw,
      onRefresh = actions.refreshQueue,
      onDismiss = { queueOpen = false },
    )
  }
  if (contextOpen) {
    ContextDialog(
      instructions = compactInstructions,
      onInstructions = actions.setCompactInstructions,
      busy = busy,
      ready = ready,
      onCompact = { actions.compact(); contextOpen = false },
      onReset = { actions.resetContext(); contextOpen = false },
      onDismiss = { contextOpen = false },
    )
  }
  drawer.renamingId?.let { id ->
    RenameSessionDialog(
      original = sessions.rows.firstOrNull { it.id == id }?.name.orEmpty(),
      draft = drawer.renaming,
      onDraft = actions.setRenaming,
      onSubmit = actions.submitRename,
      onDismiss = actions.cancelRename,
    )
  }
  noticeDialog?.let { NoticeDialog("Notice", it, onDismissNoticeDialog) }
}

/**
 * The header: the genuine Pi mark at the left, and opposite it the transparent history glyph that
 * opens the sessions drawer — the drawer's only way in. The glyph is drawn at the logo's own size,
 * with no tile, no button background and no outline, but its hit area is the full accessible touch
 * target from [HeaderHistoryGlyph], so a 16 dp glyph is never a 16 dp target.
 */
@Composable
private fun Header(title: String, onHistory: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.Header)
      .padding(
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp,
        start = PiSpace.Screen,
        end = PiSpace.x1,
        bottom = 4.dp,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PiLogo(Modifier.size(20.dp))
    Spacer(Modifier.width(PiSpace.x3))
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = PiType.Header,
        color = PiForeground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(
      modifier = Modifier
        .widthIn(min = 56.dp)
        .height(PiSpace.Touch)
        .clickable(onClick = onHistory),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
          painter = painterResource(PiIcons.history),
          contentDescription = "Open sessions",
          tint = PiSecondary,
          modifier = Modifier.size(18.dp),
        )
        Text("SESSIONS", style = PiType.Nano, color = PiSecondary, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 1.dp))
      }
    }
  }
}

/**
 * The first screen: one quiet statement and three honest routes. It teaches the instrument without
 * filling the room with marketing.
 */
@Composable
private fun EmptySessionState(
  workspaceLabel: String,
  onAsk: () -> Unit,
  onAttach: () -> Unit,
  onSessions: () -> Unit,
  bottomReservePx: Int = 0,
  modifier: Modifier = Modifier,
) {
  // The dock floats over the foot of the column, so the empty state reserves exactly the height it
  // reports; the actions stay clear of the composer instead of sliding underneath it.
  val bottomReserve = with(LocalDensity.current) { bottomReservePx.toDp() }
  Column(
    modifier.padding(
      start = PiSpace.Screen,
      end = PiSpace.Screen,
      top = PiSpace.x6,
      bottom = bottomReserve + PiSpace.x6,
    ),
  ) {
    PiLogo(Modifier.size(34.dp))
    Spacer(Modifier.height(PiSpace.x6))
    Text("Nothing here yet.", style = PiType.Display, color = PiForeground)
    Spacer(Modifier.height(PiSpace.x2))
    Text(
      "Ask about this workspace, attach a file, or open a past session. The runtime is already listening.",
      style = PiType.BodySmall,
      color = PiSecondary,
    )
    Spacer(Modifier.height(PiSpace.x8))
    PiRule()
    EmptyAction("Ask about this workspace", "The agent can read and run inside $workspaceLabel", PiIcons.plus, onAsk)
    EmptyAction("Attach a file", "Stage an image, text, or binary", PiIcons.attach, onAttach)
    EmptyAction("Open a session", "Past conversations in this workspace", PiIcons.history, onSessions)
  }
}

@Composable
private fun EmptyAction(title: String, detail: String, icon: Int, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 52.dp)
      .clickable(onClick = onClick)
      .padding(vertical = PiSpace.x2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(icon),
      contentDescription = null,
      tint = PiAccent,
      modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.width(PiSpace.x3))
    Column(Modifier.weight(1f)) {
      Text(title, style = PiType.Ui, color = PiForeground)
      Text(detail, style = PiType.Micro, color = PiSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Icon(
      painter = painterResource(PiIcons.chevron),
      contentDescription = null,
      tint = PiMuted,
      // The base glyph points up; +90° makes it point into the route, matching the index rows.
      modifier = Modifier.size(14.dp).rotate(90f),
    )
  }
  PiRule()
}
