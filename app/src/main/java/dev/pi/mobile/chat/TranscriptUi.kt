package dev.pi.mobile.chat

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.FoldChevron
import dev.pi.mobile.PiIcons
import dev.pi.mobile.PiAccent
import dev.pi.mobile.PiError
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiSecondary
import dev.pi.mobile.PiMuted
import dev.pi.mobile.theme.PiIconButton
import dev.pi.mobile.theme.PiLabel
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The transcript canvas: consecutive agent work folds into one dim line that opens on tap, the
 * prose stays open, and a user turn renders as a prompt line with real file cards instead of the
 * raw machine-facing `<file …>` blocks.
 *
 * Every decision here comes from a pure reducer — [TranscriptGrouping] for the fold, [ToolSummary]
 * for a row's one-line form, [UserMessagePresenter] for the user bubble, [TranscriptFoldReducer] for
 * which runs are open — so the drawing code carries no rules of its own.
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Transcript(
  entries: List<ChatEntry>,
  fold: TranscriptFoldState,
  /** Bumped whenever the host pushes new content, so growth re-triggers the follow. */
  followEpoch: Long,
  onToggleWork: (String) -> Unit,
  onToggleTool: (String) -> Unit,
  onOpenAttachment: (AttachedFileRef) -> Unit,
  /** The discovered skills, so a turn that invoked one shows the badge instead of the raw invocation. */
  skills: List<CommandRow> = emptyList(),
  modifier: Modifier = Modifier,
  /** The measured height of the composer/chrome overlay the list's bottom reserve tracks. */
  bottomOverlayHeightPx: Int = 0,
) {
  val rows = remember(entries) { TranscriptGrouping.group(entries) }
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  // Sticky auto-follow: only while the reader is near the bottom; content growth re-triggers it.
  // Bottom-align tall last rows; ignore a transient not-near reading during auto-scroll / IME churn.
  var stickToBottom by remember { mutableStateOf(true) }
  var autoScrollInFlight by remember { mutableStateOf(false) }
  // Content follow deferred while the IME animates; drained when source == target (no lost follow).
  var pendingContentFollow by remember { mutableStateOf(false) }
  var lastContentFollowEpoch by remember { mutableStateOf(-1L) }
  var lastContentFollowSize by remember { mutableStateOf(-1) }
  val listBottomPadDp = with(density) {
    ComposerSendMotionPolicy.lazyListBottomPaddingPx(bottomOverlayHeightPx).toDp()
  }
  val imeVisible = WindowInsets.isImeVisible
  val imeSourceBottomPx = WindowInsets.imeAnimationSource.getBottom(density)
  val imeTargetBottomPx = WindowInsets.imeAnimationTarget.getBottom(density)
  val imeAnimating = ComposerSendMotionPolicy.isImeAnimating(imeSourceBottomPx, imeTargetBottomPx)
  // Reduced motion: a zero system animation scale means every follow is an instant jump, never a
  // glide. Captured here because the setting cannot change from inside the app on this device.
  val animationsEnabled = systemAnimationsEnabled()
  LaunchedEffect(listState) {
    snapshotFlow {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()
      val near = TranscriptScrollMath.isNearBottom(
        totalItems = info.totalItemsCount,
        lastVisible = last?.let {
          TranscriptScrollMath.VisibleItem(index = it.index, offset = it.offset, size = it.size)
        },
        viewportEndOffset = info.viewportEndOffset,
        thresholdPx = 48,
      )
      Triple(near, autoScrollInFlight, imeAnimating)
    }.distinctUntilChanged().collect { (nearBottom, inFlight, imeMotion) ->
      stickToBottom = TranscriptScrollMath.nextStickToBottom(
        currentlyStuck = stickToBottom,
        nearBottom = nearBottom,
        autoScrollInFlight = inFlight,
        imeAnimating = imeMotion,
      )
    }
  }
  // One programmatic scroll owner: content growth + pending drain + the IME-settle edge.
  var prevImeAnimating by remember { mutableStateOf(false) }
  LaunchedEffect(followEpoch, rows.size, stickToBottom, imeAnimating) {
    val contentSignal =
      followEpoch != lastContentFollowEpoch || rows.size != lastContentFollowSize
    val imeJustSettled = prevImeAnimating && !imeAnimating
    prevImeAnimating = imeAnimating

    // nearBottom is only needed for settle; skip the layout read when content-follow will own it.
    val wantFollowProbe = contentSignal || pendingContentFollow
    val nearBottom = if (!wantFollowProbe && imeJustSettled && stickToBottom && rows.isNotEmpty()) {
      val info = listState.layoutInfo
      val last = info.visibleItemsInfo.lastOrNull()
      TranscriptScrollMath.isNearBottom(
        totalItems = info.totalItemsCount,
        lastVisible = last?.let {
          TranscriptScrollMath.VisibleItem(index = it.index, offset = it.offset, size = it.size)
        },
        viewportEndOffset = info.viewportEndOffset,
        thresholdPx = 48,
      )
    } else {
      true // irrelevant when not settling
    }

    val plan = ComposerSendMotionPolicy.planProgrammaticScroll(
      stickToBottom = stickToBottom,
      itemCount = rows.size,
      imeAnimating = imeAnimating,
      contentSignal = contentSignal,
      alreadyPending = pendingContentFollow,
      nearBottom = nearBottom,
      imeJustSettled = imeJustSettled,
    )
    pendingContentFollow = plan.pendingContentFollow
    if (plan.kind == ComposerSendMotionPolicy.ProgrammaticScrollKind.None) return@LaunchedEffect
    val lastIndex = rows.lastIndex
    if (lastIndex < 0) {
      pendingContentFollow = false
      return@LaunchedEffect
    }
    autoScrollInFlight = true
    try {
      var info = listState.layoutInfo
      var viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
      var laidOut = info.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.size ?: 0
      // Off-screen last row → size 0; a measure-only scroll is needed before the final bottom-align.
      if (TranscriptScrollMath.needsItemMeasurePass(laidOut)) {
        listState.scrollToItem(lastIndex)
        info = listState.layoutInfo
        viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
        laidOut = info.visibleItemsInfo.firstOrNull { it.index == lastIndex }?.size ?: 0
      }
      val offset = TranscriptScrollMath.bottomAlignScrollOffset(itemSize = laidOut, viewportSize = viewport)
      val animate = animationsEnabled &&
        plan.kind == ComposerSendMotionPolicy.ProgrammaticScrollKind.ContentFollow &&
        ComposerSendMotionPolicy.contentFollowShouldAnimate(imeVisible = imeVisible, imeAnimating = imeAnimating)
      if (animate) {
        listState.animateScrollToItem(index = lastIndex, scrollOffset = offset)
      } else {
        listState.scrollToItem(index = lastIndex, scrollOffset = offset)
      }
      if (plan.kind == ComposerSendMotionPolicy.ProgrammaticScrollKind.ContentFollow) {
        lastContentFollowEpoch = followEpoch
        lastContentFollowSize = rows.size
      }
    } finally {
      if (plan.kind == ComposerSendMotionPolicy.ProgrammaticScrollKind.ContentFollow) {
        pendingContentFollow = false
      }
      autoScrollInFlight = false
    }
  }
  LazyColumn(
    state = listState,
    modifier = modifier,
    contentPadding = PaddingValues(
      start = PiSpace.Screen,
      end = PiSpace.Screen,
      top = PiSpace.x2,
      bottom = if (listBottomPadDp > 0.dp) listBottomPadDp else 140.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(PiSpace.x6),
  ) {
    items(rows, key = { it.key }) { row ->
      when (row) {
        is TranscriptRow.Message -> TranscriptEntry(
          entry = row.entry,
          onToggleTool = onToggleTool,
          onOpenAttachment = onOpenAttachment,
          skills = skills,
        )
        is TranscriptRow.Work -> WorkGroup(
          entries = row.entries,
          expanded = fold.isWorkExpanded(row.key),
          onToggle = { onToggleWork(row.key) },
          onToggleTool = onToggleTool,
        )
      }
    }
  }
}

/**
 * One message row. A user turn renders its parsed projection (skill badge, file cards, body) while
 * the raw text stays the source of truth for reconciliation and for the model.
 */
@Composable
internal fun TranscriptEntry(
  entry: ChatEntry,
  onToggleTool: (String) -> Unit,
  onOpenAttachment: (AttachedFileRef) -> Unit,
  skills: List<CommandRow> = emptyList(),
  showRole: Boolean = true,
) = when (entry) {
  is ChatEntry.User -> UserMessageBubble(entry, onOpenAttachment, skills, showRole)
  is ChatEntry.Assistant -> Column(Modifier.fillMaxWidth()) {
    if (entry.markdown.isNotBlank()) {
      if (showRole) {
        PiLabel("Pi", color = PiAccent)
        Spacer(Modifier.height(PiSpace.x1))
      }
      MarkdownContent(entry.markdown)
    }
  }
  is ChatEntry.Thinking -> ThinkingBlock(entry.text)
  is ChatEntry.Tool -> ToolCard(entry, onToggle = { onToggleTool(entry.toolCallId) })
  is ChatEntry.SystemNotice -> Text(
    entry.text,
    style = PiType.Meta,
    color = PiSecondary,
  )
}

/**
 * One folded run of thinking/tool rows. Collapsed, it is a single dim line; expanded, it is the full
 * machinery. This is what keeps the transcript readable on a phone.
 */
@Composable
internal fun WorkGroup(
  entries: List<ChatEntry>,
  expanded: Boolean,
  onToggle: () -> Unit,
  onToggleTool: (String) -> Unit,
) {
  val label = TranscriptGrouping.label(entries)
  val preview = TranscriptGrouping.toolPreview(entries)
  val failed = TranscriptGrouping.hasError(entries)
  val running = TranscriptGrouping.isRunning(entries)
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = PiSpace.TouchMin)
        .clickable(onClick = onToggle)
        .padding(end = PiSpace.x1),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FoldChevron(expanded, PiAccent)
      PiLabel("Activity", color = PiMuted)
      Spacer(Modifier.width(PiSpace.x2))
      Text(
        label,
        style = PiType.Meta,
        color = when {
          failed -> PiError
          running -> PiAccent
          else -> PiAccent
        },
      )
      if (failed) {
        Text(" · error", style = PiType.Meta, color = PiError)
      } else if (running) {
        Text(" · running", style = PiType.Meta, color = PiAccent)
      }
      if (preview.isNotBlank()) {
        Text(
          " $preview",
          style = PiType.Meta,
          color = PiMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      } else {
        Spacer(Modifier.weight(1f))
      }
    }
    PiRule()
    if (expanded) {
      Column(
        Modifier.fillMaxWidth().padding(start = PiSpace.x4, top = PiSpace.x2, bottom = PiSpace.x1),
        verticalArrangement = Arrangement.spacedBy(PiSpace.x1),
      ) {
        entries.forEach { entry ->
          TranscriptEntry(entry = entry, onToggleTool = onToggleTool, onOpenAttachment = {}, showRole = false)
        }
      }
    }
  }
}

/**
 * A user turn as a prompt line inside a translucent greyscale band, not a themed bubble. Skills and
 * files appear as a compact badge and real file cards. The raw prompt stays in [ChatEntry.User.text]
 * and is what the model receives.
 */
@Composable
internal fun UserMessageBubble(
  entry: ChatEntry.User,
  onOpenAttachment: (AttachedFileRef) -> Unit,
  skills: List<CommandRow> = emptyList(),
  showRole: Boolean = true,
) {
  val presentation = remember(entry.text) { UserMessagePresenter.present(entry.text) }
  // A turn that invoked a skill stored the raw invocation, and the instructions travelled in that
  // turn's prompt section. The badge is the projection: the invocation and the user's own words,
  // never the expansion and never the raw slash command printed twice.
  val badge = remember(entry.text, skills) {
    if (presentation.skills.isEmpty()) ComposerSkillUiPolicy.skillBadge(entry.text, skills) else null
  }
  Column(Modifier.fillMaxWidth()) {
    if (showRole) PiLabel("You", color = PiSecondary)
    Spacer(Modifier.height(if (showRole) PiSpace.x1 else 0.dp))
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Box(Modifier.width(PiSpace.Rail).fillMaxHeight().background(PiAccent))
      Column(
        Modifier.weight(1f).padding(start = PiSpace.x3),
        verticalArrangement = Arrangement.spacedBy(PiSpace.x1),
      ) {
        badge?.let { SkillBadgeLine(it.invocation) }
        presentation.skills.forEach { skill ->
          Text(
            "[skill] ${skill.name}",
            style = PiType.Meta,
            color = PiSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        presentation.files.forEach { file ->
          TranscriptFileCard(file, onOpen = { onOpenAttachment(file) })
        }
        val body = if (badge != null) badge.arguments else presentation.body
        if (body.isNotBlank()) {
          Text(
            body,
            style = PiType.Body,
            color = PiForeground,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

/**
 * A real card for a file the turn carried: its type, mime and (when the staged file is still on
 * disk) its size. A path-only reference says so rather than pretending the content was inlined.
 */
@Composable
internal fun TranscriptFileCard(file: AttachedFileRef, onOpen: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.TouchMin)
      .then(if (file.path != null) Modifier.clickable(onClick = onOpen) else Modifier)
      .padding(vertical = PiSpace.x1),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "[file] ${file.name}",
      style = PiType.BodySmall,
      color = PiForeground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      fileMetaLine(file),
      style = PiType.Meta,
      color = PiSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = PiSpace.x2),
    )
  }
}

internal fun fileMetaLine(file: AttachedFileRef): String = buildString {
  append(
    when (file.kind) {
      "image" -> "image"
      "text" -> "text"
      "binary" -> "file"
      else -> file.kind
    },
  )
  file.mimeType?.let { append(" · ").append(it) }
  if (!file.inlined) append(" · path only")
}

@Composable
internal fun ToolCard(tool: ChatEntry.Tool, onToggle: () -> Unit) {
  val summary = ToolSummary.summary(tool.name, tool.arguments, ToolSummary.statusLabel(tool.status))
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier
        .fillMaxWidth()
        .heightIn(min = PiSpace.TouchMin)
        .clickable(onClick = onToggle)
        .padding(end = PiSpace.x1),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FoldChevron(tool.expanded, PiAccent)
      Text(
        summary,
        style = PiType.Meta,
        color = PiAccent,
        maxLines = if (tool.expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    if (tool.expanded) {
      SelectionContainer {
        Column(
          Modifier.fillMaxWidth().padding(start = PiSpace.x6, bottom = PiSpace.x2),
          verticalArrangement = Arrangement.spacedBy(PiSpace.x1),
        ) {
          PiLabel("Arguments", color = PiSecondary)
          Text(
            tool.arguments.ifBlank { "(none)" },
            style = PiType.BodySmall,
            color = PiForeground,
          )
          if (tool.output.isNotEmpty() || tool.status == ChatEntry.ToolStatus.Ok || tool.status == ChatEntry.ToolStatus.Error) {
            PiLabel("Output", color = PiSecondary, modifier = Modifier.padding(top = PiSpace.x2))
            Text(
              tool.output.ifBlank { "(empty)" },
              style = PiType.BodySmall,
              color = if (tool.status == ChatEntry.ToolStatus.Error) PiError else PiForeground,
            )
          }
        }
      }
    }
  }
}

/** A staged attachment above the composer: its type, its name, and a tap to drop it. */
@Composable
internal fun AttachmentChip(att: PendingAttachment, onRemove: () -> Unit) {
  val kindLabel = when (att.kind) {
    AttachmentKind.IMAGE -> "img"
    AttachmentKind.TEXT -> "txt"
    AttachmentKind.BINARY -> "bin"
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "[$kindLabel] ${att.displayName}",
      style = PiType.Meta,
      color = PiForeground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.widthIn(max = 180.dp),
    )
    PiIconButton(
      onClick = onRemove,
      contentDescription = "Remove ${att.displayName}",
      glyph = painterResource(PiIcons.close),
      tint = PiSecondary,
      target = PiSpace.TouchMin,
      glyphSize = 14.dp,
    )
  }
}

/** The composer's attach control: a plain `+` that opens the system file picker. */
@Composable
internal fun AttachControl(enabled: Boolean, onAttach: () -> Unit) {
  PiIconButton(
    onClick = onAttach,
    contentDescription = "Attach a file",
    glyph = painterResource(PiIcons.attach),
    enabled = enabled,
    tint = PiSecondary,
    target = PiSpace.TouchMin,
    glyphSize = 18.dp,
  )
}

/** The system animation scale; zero means the user asked for reduced motion. */
@Composable
private fun systemAnimationsEnabled(): Boolean {
  val context = LocalContext.current
  return remember(context) {
    Settings.Global.getFloat(
      context.contentResolver,
      Settings.Global.ANIMATOR_DURATION_SCALE,
      1f,
    ) > 0f
  }
}
