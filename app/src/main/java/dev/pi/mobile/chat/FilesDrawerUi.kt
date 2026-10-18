package dev.pi.mobile.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.PiAccent
import dev.pi.mobile.PiBackground
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiHairline
import dev.pi.mobile.PiIcons
import dev.pi.mobile.PiMuted
import dev.pi.mobile.PiSecondary
import dev.pi.mobile.theme.PiIconButton
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The left-edge files drawer: breadcrumb, filter, directory rows, then a footer that says how many
 * entries were listed. Every rule it draws comes from [WorkspaceFilesUi]/[WorkspaceFilesReducer];
 * this file owns only the surface.
 */
@Composable
internal fun FilesDrawerPanel(
  workspaceLabel: String,
  rootLabel: String,
  state: WorkspaceFilesState,
  onQuery: (String) -> Unit,
  onNavigate: (String) -> Unit,
  onRefresh: () -> Unit,
  onOpenFile: (WorkspaceEntry) -> Unit,
  onAttach: (WorkspaceEntry) -> Unit,
  onClose: () -> Unit,
) {
  val rows = WorkspaceFilesUi.visibleEntries(state)
  val crumbs = WorkspaceFilesUi.crumbs(state.path, rootLabel)
  val parent = WorkspaceFilesUi.parent(state.path)
  // System back closes the drawer, matching the close glyph and the scrim.
  BackHandler(enabled = state.open, onBack = onClose)
  Column(
    Modifier
      .fillMaxHeight()
      .width(SessionDrawerLayout.panelWidthDp(LocalConfiguration.current.screenWidthDp).dp)
      .statusBarsPadding()
      .navigationBarsPadding()
      .background(PiBackground)
      .border(1.dp, PiHairline),
  ) {
    DrawerTitle(
      title = "Files",
      subtitle = workspaceLabel.ifBlank { "app private" },
      trailing = {
        DrawerGlyph(
          glyph = if (state.loading) "…" else "↻",
          label = "Refresh files",
          enabled = !state.loading,
          onClick = onRefresh,
        )
        DrawerGlyph(
          glyph = "↑",
          label = "Up one folder",
          enabled = parent != null,
          onClick = { parent?.let(onNavigate) },
        )
        PiIconButton(
          onClick = onClose,
          contentDescription = "Close files",
          glyph = painterResource(PiIcons.close),
          tint = PiSecondary,
          target = PiSpace.TouchMin,
          glyphSize = 18.dp,
        )
      },
    )
    DrawerField(state.query, onQuery, "Filter this folder")
    Crumbs(crumbs, activePath = state.path, onNavigate = onNavigate)
    PiRule()
    when {
      state.loading && state.entries.isEmpty() -> DrawerNote("Reading this folder…")
      state.error != null -> DrawerNote(state.error)
      rows.isEmpty() && state.query.isNotBlank() -> DrawerNote("No file in this folder matches “${state.query.trim()}”.")
      rows.isEmpty() -> DrawerNote("This folder is empty.")
      else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(rows, key = { it.path.ifBlank { it.name } }) { entry ->
          FileRow(
            entry = entry,
            onOpen = {
              if (entry.directory) onNavigate(entry.path) else onOpenFile(entry)
            },
            onAttach = { onAttach(entry) },
          )
        }
      }
    }
    if (rows.isEmpty() || state.loading) Spacer(Modifier.weight(1f))
    PiRule()
    Row(
      Modifier.fillMaxWidth().padding(horizontal = PiSpace.Screen, vertical = PiSpace.x2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        when {
          state.loading -> "reading"
          rows.size == state.entries.size -> "${rows.size} ${if (rows.size == 1) "entry" else "entries"}"
          else -> "${rows.size} of ${state.entries.size}"
        },
        style = PiType.Meta,
        color = PiSecondary,
      )
      if (state.truncated) {
        Text(
          " · first ${WorkspaceFilesUi.MAX_ENTRIES} only",
          style = PiType.Meta,
          color = PiMuted,
        )
      }
      Spacer(Modifier.weight(1f))
      Text("tap to preview · + to attach", style = PiType.Micro, color = PiMuted, maxLines = 1)
    }
  }
}

/** A small text glyph with a 44 dp target; the drawer's quiet secondary controls. */
@Composable
private fun DrawerGlyph(
  glyph: String,
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Box(
    Modifier
      .size(PiSpace.TouchMin)
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      glyph,
      color = if (enabled) PiSecondary else PiMuted,
      fontSize = 16.sp,
    )
  }
}

/** Root → segment breadcrumbs, horizontally scrollable, the current folder in full ink. */
@Composable
private fun Crumbs(
  crumbs: List<WorkspaceCrumb>,
  activePath: String,
  onNavigate: (String) -> Unit,
) {
  val scroll = rememberScrollState()
  LaunchedEffect(activePath) {
    // The current folder is the right-hand crumb; wait for layout, then reveal it.
    snapshotFlow { scroll.maxValue }.first { it > 0 }
    scroll.animateScrollTo(scroll.maxValue)
  }
  Row(
    Modifier
      .fillMaxWidth()
      .horizontalScroll(scroll)
      .padding(horizontal = PiSpace.Screen, vertical = PiSpace.x2),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    crumbs.forEachIndexed { index, crumb ->
      if (index > 0) {
        Text("/", style = PiType.Meta, color = PiMuted, modifier = Modifier.padding(horizontal = PiSpace.x1))
      }
      val current = crumb.path == activePath
      Text(
        crumb.label,
        style = PiType.Meta,
        color = if (current) PiForeground else PiSecondary,
        maxLines = 1,
        modifier = Modifier
          .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
          .clickable { onNavigate(crumb.path) }
          .padding(horizontal = PiSpace.x1, vertical = 2.dp),
      )
    }
  }
}

/** One row: folder or file, its size/kind, and the attach control files earn. */
@Composable
private fun FileRow(
  entry: WorkspaceEntry,
  onOpen: () -> Unit,
  onAttach: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = PiSpace.Row)
      .clickable(onClick = onOpen)
      .padding(start = PiSpace.Screen),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(if (entry.directory) PiIcons.folder else PiIcons.file),
      contentDescription = null,
      tint = if (entry.directory) PiAccent else PiSecondary,
      modifier = Modifier.size(16.dp),
    )
    Spacer(Modifier.width(PiSpace.x3))
    Column(Modifier.weight(1f).padding(end = PiSpace.x2)) {
      Text(
        entry.name,
        style = PiType.Ui,
        color = PiForeground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        WorkspaceFilesUi.entryDetail(entry),
        style = PiType.Micro,
        color = PiSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (entry.directory) {
      Text(
        "›",
        style = PiType.Ui,
        color = PiMuted,
        modifier = Modifier.padding(end = PiSpace.Screen),
      )
    } else {
      val canAttach = WorkspaceFilesUi.canAttach(entry)
      Box(
        Modifier
          .size(PiSpace.Touch)
          .clickable(enabled = canAttach, onClick = onAttach),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          painter = painterResource(PiIcons.plus),
          contentDescription = "Attach ${entry.name}",
          tint = if (canAttach) PiSecondary else PiMuted,
          modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}

/**
 * The full-screen preview. It sits above both drawers, so closing it returns the user to the file
 * list exactly where they left it. Text, Markdown, code and images render inline; everything else
 * is honest metadata with the same attach/copy/share hand-offs.
 */
@Composable
internal fun FilePreviewOverlay(
  preview: FilePreviewState,
  onClose: () -> Unit,
  onAttach: () -> Unit,
  onCopyPath: () -> Unit,
  onShare: () -> Unit,
  onOpenWith: () -> Unit,
) {
  BackHandler(onBack = onClose)
  val entry = preview.entry
  val kind = WorkspaceFilesUi.previewKind(entry.name)
  Column(
    Modifier
      .fillMaxSize()
      .background(PiBackground)
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(start = PiSpace.x1, end = PiSpace.x1, top = PiSpace.x2, bottom = PiSpace.x2),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PiIconButton(
        onClick = onClose,
        contentDescription = "Back to files",
        glyph = painterResource(PiIcons.back),
        tint = PiSecondary,
        target = PiSpace.TouchMin,
        glyphSize = 18.dp,
      )
      Column(Modifier.weight(1f).padding(horizontal = PiSpace.x2)) {
        Text(
          entry.name,
          style = PiType.Heading,
          color = PiForeground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          "${WorkspaceFilesUi.humanSize(entry.sizeBytes)} · ${kind.label} · ${entry.path}",
          style = PiType.Micro,
          color = PiSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
      PiIconButton(
        onClick = onClose,
        contentDescription = "Close preview",
        glyph = painterResource(PiIcons.close),
        tint = PiSecondary,
        target = PiSpace.TouchMin,
        glyphSize = 18.dp,
      )
    }
    PiRule()
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = PiSpace.Screen, vertical = PiSpace.x1),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(PiSpace.x4),
    ) {
      PreviewAction(
        label = "ATTACH",
        enabled = WorkspaceFilesUi.canAttach(entry),
        onClick = onAttach,
      )
      PreviewAction(label = "COPY PATH", onClick = onCopyPath)
      PreviewAction(label = "SHARE", onClick = onShare)
      PreviewAction(label = "OPEN WITH", onClick = onOpenWith)
    }
    PiRule()
    Box(Modifier.fillMaxSize()) {
      when {
        preview.loading -> CenteredNote("Reading…")
        preview.error != null -> CenteredNote(preview.error)
        kind == FilePreviewKind.MARKDOWN -> MarkdownPreview(preview)
        kind == FilePreviewKind.CODE -> CodePreview(
          text = preview.text.orEmpty(),
          language = WorkspaceFilesUi.languageFor(entry.name),
          truncated = preview.truncated,
        )
        kind == FilePreviewKind.TEXT -> TextPreview(preview.text.orEmpty(), preview.truncated)
        kind == FilePreviewKind.IMAGE -> ImagePreview(preview.absolutePath, entry)
        else -> CenteredNote(
          "No inline preview for ${entry.name.substringAfterLast('.', "this format")}.\n" +
            "Attach it to the prompt, share it, or open it in another app.",
        )
      }
    }
  }
}

@Composable
private fun CenteredNote(text: String) {
  Box(Modifier.fillMaxSize().padding(PiSpace.Screen), contentAlignment = Alignment.Center) {
    Text(text, style = PiType.BodySmall, color = PiSecondary)
  }
}

@Composable
private fun PreviewAction(
  label: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Text(
    label,
    style = PiType.Label,
    color = if (enabled) PiAccent else PiMuted,
    modifier = Modifier
      .heightIn(min = PiSpace.TouchMin)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(vertical = PiSpace.x3),
  )
}

@Composable
private fun MarkdownPreview(preview: FilePreviewState) {
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = PiSpace.Screen, vertical = PiSpace.x3),
  ) {
    MarkdownContent(preview.text.orEmpty())
    TruncationNote(preview.truncated)
  }
}

@Composable
private fun TextPreview(text: String, truncated: Boolean) {
  val scroll = rememberScrollState()
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = PiSpace.Screen, vertical = PiSpace.x3),
  ) {
    SelectionContainer {
      Box(Modifier.horizontalScroll(rememberScrollState())) {
        Text(
          text,
          style = PiType.Code.copy(color = PiForeground),
          softWrap = false,
        )
      }
    }
    TruncationNote(truncated)
  }
}

@Composable
private fun CodePreview(text: String, language: String, truncated: Boolean) {
  val annotated = remember(text, language) {
    val tokens = CodeHighlight.tokenize(text, language)
    buildAnnotatedString {
      append(text)
      tokens.forEach { token -> addStyle(codeTokenStyle(token.kind), token.start, token.end) }
    }
  }
  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = PiSpace.Screen, vertical = PiSpace.x3),
  ) {
    SelectionContainer {
      Box(Modifier.horizontalScroll(rememberScrollState())) {
        Text(
          annotated,
          style = PiType.Code.copy(color = PiForeground),
          softWrap = false,
        )
      }
    }
    TruncationNote(truncated)
  }
}

@Composable
private fun TruncationNote(truncated: Boolean) {
  if (!truncated) return
  Text(
    "Showing the first ${WorkspaceFilesUi.humanSize(WorkspaceFilesUi.MAX_TEXT_BYTES.toLong())}. " +
      "Copy the path from the drawer to read the whole file elsewhere.",
    style = PiType.Meta,
    color = PiSecondary,
    modifier = Modifier.padding(top = PiSpace.x4),
  )
}

private fun codeTokenStyle(kind: CodeTokenKind): SpanStyle = when (kind) {
  CodeTokenKind.KEYWORD -> SpanStyle(color = PiForeground, fontWeight = FontWeight.SemiBold)
  CodeTokenKind.TYPE -> SpanStyle(color = PiSecondary)
  CodeTokenKind.STRING -> SpanStyle(color = PiAccent)
  CodeTokenKind.COMMENT -> SpanStyle(color = PiMuted, fontStyle = FontStyle.Italic)
  CodeTokenKind.NUMBER -> SpanStyle(color = PiSecondary)
  CodeTokenKind.META -> SpanStyle(color = PiMuted)
  CodeTokenKind.ADDED -> SpanStyle(color = PiAccent)
  CodeTokenKind.REMOVED -> SpanStyle(color = dev.pi.mobile.theme.Error)
}

@Composable
private fun ImagePreview(path: String, entry: WorkspaceEntry) {
  var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
  var failed by remember(path) { mutableStateOf(false) }
  LaunchedEffect(path) {
    val loaded = withContext(Dispatchers.IO) { decodeSampled(path) }
    bitmap = loaded
    failed = loaded == null
  }
  val image = bitmap
  when {
    failed -> CenteredNote("This image could not be decoded on this device.")
    image == null -> CenteredNote("Reading image…")
    else -> Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(PiSpace.Screen),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      androidx.compose.foundation.Image(
        bitmap = image.asImageBitmap(),
        contentDescription = entry.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "${image.width} × ${image.height} · ${WorkspaceFilesUi.humanSize(entry.sizeBytes)}",
        style = PiType.Micro,
        color = PiSecondary,
        modifier = Modifier.padding(top = PiSpace.x2),
      )
    }
  }
}

/**
 * Decode a preview-sized bitmap. `inJustDecodeBounds` reads the header only, then the sample size
 * halves until the longest edge is at most [maxDimension]; the full-resolution file is never
 * allocated just to fit a phone screen.
 */
private fun decodeSampled(path: String, maxDimension: Int = 2048): Bitmap? {
  val file = File(path)
  if (file.length() <= 0L || file.length() > WorkspaceFilesUi.MAX_IMAGE_BYTES) return null
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(path, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
    sample *= 2
  }
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}
