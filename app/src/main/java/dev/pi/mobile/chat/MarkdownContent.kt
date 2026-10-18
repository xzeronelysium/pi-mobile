package dev.pi.mobile.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.theme.Accent
import dev.pi.mobile.theme.CodeSurface
import dev.pi.mobile.theme.Error
import dev.pi.mobile.theme.Foreground
import dev.pi.mobile.theme.Hairline
import dev.pi.mobile.theme.Mono
import dev.pi.mobile.theme.Muted
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType
import dev.pi.mobile.theme.Sans
import dev.pi.mobile.theme.Secondary
import kotlinx.coroutines.delay

// The transcript's ink is the same ink as the chrome. No local greys: #737373 failed AA and made
// metadata the loudest thing on the page.
private val MdForeground = Foreground
private val MdSecondary = Secondary
private val MdMuted = Muted
private val MdDivider = Hairline
private val MdLink = Accent
private val MdCode = CodeSurface

private const val COLLAPSED_CODE_LINES = 24
private val TableColumnGap = 12.dp

/** Renders assistant markdown: headings, emphasis, links, nested lists, quotes, tables and code. */
@Composable
fun MarkdownContent(source: String, modifier: Modifier = Modifier) {
  val blocks = remember(source) { MarkdownParser.parse(source) }
  SelectionContainer {
    Column(modifier.fillMaxWidth()) {
      MarkdownBlocks(blocks)
    }
  }
}

@Composable
private fun MarkdownBlocks(
  blocks: List<MdBlock>,
  muted: Boolean = false,
  compact: Boolean = false,
  depth: Int = 0,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
  ) {
    blocks.forEach { block -> MarkdownBlock(block, muted, compact, depth) }
  }
}

@Composable
private fun MarkdownBlock(block: MdBlock, muted: Boolean, compact: Boolean, depth: Int) {
  when (block) {
    is MdBlock.Heading -> {
      val size = when (block.level) {
        1 -> 20.sp
        2 -> 17.sp
        3 -> 15.sp
        else -> 13.sp
      }
      val line = when (block.level) {
        1 -> 27.sp
        2 -> 24.sp
        3 -> 21.sp
        else -> 19.sp
      }
      MarkdownInlineText(
        block.inlines,
        color = if (muted) MdSecondary else MdForeground,
        fontSize = size,
        lineHeight = line,
        fontWeight = FontWeight.SemiBold,
        fontFamily = Sans,
        modifier = Modifier.padding(top = if (compact) 2.dp else if (block.level <= 2) 6.dp else 2.dp),
      )
    }
    is MdBlock.Paragraph -> MarkdownInlineText(
      block.inlines,
      color = if (muted) MdSecondary else MdForeground,
      fontSize = if (muted) PiType.BodySmall.fontSize else PiType.Body.fontSize,
      lineHeight = if (muted) PiType.BodySmall.lineHeight else PiType.Body.lineHeight,
      fontStyle = if (muted) FontStyle.Italic else null,
    )
    is MdBlock.Quote -> Box(
      Modifier
        .fillMaxWidth()
        .drawBehind {
          drawRect(color = MdDivider, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
        },
    ) {
      Box(Modifier.padding(start = 12.dp)) {
        MarkdownBlocks(block.blocks, muted = true, compact = true, depth = depth)
      }
    }
    is MdBlock.ListBlock -> MarkdownList(block, muted, depth)
    is MdBlock.CodeFence -> MarkdownCodeBlock(block)
    is MdBlock.Table -> MarkdownTable(block, muted)
    is MdBlock.ThematicBreak -> HorizontalDivider(
      color = MdDivider,
      modifier = Modifier.padding(vertical = 4.dp),
    )
  }
}

@Composable
private fun MarkdownList(block: MdBlock.ListBlock, muted: Boolean, depth: Int) {
  val lastNumber = block.start + (block.items.size - 1).coerceAtLeast(0)
  val digits = lastNumber.toString().length
  val gutter = if (block.ordered) {
    val digitWidth = with(LocalDensity.current) { (PiType.Body.fontSize * 0.62f).toDp() }
    digitWidth * digits + 8.dp
  } else {
    16.dp
  }
  Column(
    Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(if (muted) 2.dp else 4.dp),
  ) {
    block.items.forEachIndexed { index, item ->
      Row(Modifier.fillMaxWidth()) {
        Box(
          Modifier
            .width(gutter)
            .padding(top = if (item.task != null) 5.dp else 2.dp),
          contentAlignment = if (block.ordered) Alignment.TopEnd else Alignment.TopStart,
        ) {
          when {
            item.task != null -> TaskCheckbox(done = item.task == MdTask.DONE)
            block.ordered -> Text(
              "${block.start + index}.",
              color = MdSecondary,
              fontSize = PiType.Body.fontSize,
              lineHeight = PiType.Body.lineHeight,
              fontFamily = Mono,
              textAlign = TextAlign.End,
              modifier = Modifier.fillMaxWidth(),
            )
            else -> Text(
              bulletFor(depth),
              color = MdSecondary,
              fontSize = PiType.Body.fontSize,
              lineHeight = PiType.Body.lineHeight,
              fontFamily = Mono,
            )
          }
        }
        Spacer(Modifier.width(6.dp))
        MarkdownBlocks(
          item.blocks,
          muted = muted,
          compact = true,
          depth = depth + 1,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

private fun bulletFor(depth: Int): String = when (depth % 3) {
  0 -> "•"
  1 -> "◦"
  else -> "▪"
}

@Composable
private fun TaskCheckbox(done: Boolean) {
  Box(
    Modifier
      .size(13.dp)
      .border(1.dp, if (done) Accent else MdSecondary, RoundedCornerShape(2.dp)),
    contentAlignment = Alignment.Center,
  ) {
    if (done) {
      Text(
        "✓",
        color = Accent,
        fontSize = 9.sp,
        lineHeight = 9.sp,
        fontFamily = Mono,
      )
    }
  }
}

@Composable
private fun MarkdownCodeBlock(block: MdBlock.CodeFence) {
  val clipboard = LocalClipboardManager.current
  var copied by remember(block.code) { mutableStateOf(false) }
  var expanded by remember(block.code) { mutableStateOf(false) }
  val lines = remember(block.code) { block.code.split('\n') }
  val collapsed = !expanded && lines.size > COLLAPSED_CODE_LINES
  val visible = remember(block.code, collapsed) {
    if (collapsed) lines.take(COLLAPSED_CODE_LINES).joinToString("\n") else block.code
  }
  val annotated = remember(visible, block.language) {
    val tokens = CodeHighlight.tokenize(visible, block.language)
    buildAnnotatedString {
      append(visible)
      tokens.forEach { token -> addStyle(codeTokenStyle(token.kind), token.start, token.end) }
    }
  }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(MdCode)
      .border(1.dp, MdDivider, RoundedCornerShape(6.dp)),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(start = 10.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        block.language.ifBlank { "Code" },
        style = PiType.Meta,
        color = MdSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Box(
        Modifier
          .heightIn(min = PiSpace.TouchMin)
          .clip(RoundedCornerShape(4.dp))
          .clickable {
            clipboard.setText(AnnotatedString(block.code))
            copied = true
          }
          .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          if (copied) "Copied" else "Copy",
          style = PiType.Meta,
          color = if (copied) Accent else MdSecondary,
        )
      }
    }
    Box(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
    ) {
      Text(
        annotated,
        style = PiType.Code.copy(color = MdForeground),
        softWrap = false,
      )
    }
    if (lines.size > COLLAPSED_CODE_LINES) {
      HorizontalDivider(color = MdDivider)
      Box(
        Modifier
          .fillMaxWidth()
          .heightIn(min = PiSpace.TouchMin)
          .clickable { expanded = !expanded }
          .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          if (collapsed) "Show all ${lines.size} lines" else "Collapse",
          style = PiType.Meta,
          color = Accent,
        )
      }
    }
  }
}

private fun codeTokenStyle(kind: CodeTokenKind): SpanStyle = when (kind) {
  CodeTokenKind.KEYWORD -> SpanStyle(color = MdForeground, fontWeight = FontWeight.SemiBold)
  CodeTokenKind.TYPE -> SpanStyle(color = MdSecondary)
  CodeTokenKind.STRING -> SpanStyle(color = Accent)
  CodeTokenKind.COMMENT -> SpanStyle(color = MdMuted, fontStyle = FontStyle.Italic)
  CodeTokenKind.NUMBER -> SpanStyle(color = MdSecondary)
  CodeTokenKind.META -> SpanStyle(color = MdMuted)
  CodeTokenKind.ADDED -> SpanStyle(color = Accent)
  CodeTokenKind.REMOVED -> SpanStyle(color = Error)
}

@Composable
private fun MarkdownTable(block: MdBlock.Table, muted: Boolean) {
  val columnCount = maxOf(
    block.headers.size,
    block.rows.maxOfOrNull { it.size } ?: 0,
  ).coerceAtLeast(1)
  val widths = remember(block) { columnWidths(block, columnCount) }
  val totalWidth = widths.fold(0.dp) { total, width -> total + width } +
    TableColumnGap * (columnCount - 1)
  val scroll = rememberScrollState()

  Column(
    Modifier
      .fillMaxWidth()
      .horizontalScroll(scroll),
  ) {
    Column(Modifier.width(totalWidth).padding(vertical = 2.dp)) {
      MarkdownTableRow(block.headers, widths, block.alignments, header = true, muted = muted)
      HorizontalDivider(color = MdDivider, modifier = Modifier.padding(vertical = 6.dp))
      block.rows.forEachIndexed { index, row ->
        MarkdownTableRow(row, widths, block.alignments, header = false, muted = muted)
        if (index < block.rows.lastIndex) {
          HorizontalDivider(
            color = MdDivider.copy(alpha = 0.5f),
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun MarkdownTableRow(
  cells: List<String>,
  widths: List<Dp>,
  alignments: List<MdAlign>,
  header: Boolean,
  muted: Boolean,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(TableColumnGap)) {
    widths.forEachIndexed { column, width ->
      val cell = cells.getOrElse(column) { "" }
      val inlines = remember(cell) { MarkdownParser.parseInlines(cell) }
      Box(Modifier.width(width).padding(vertical = if (header) 0.dp else 3.dp)) {
        MarkdownInlineText(
          inlines,
          color = if (muted) MdSecondary else MdForeground,
          fontSize = 12.sp,
          lineHeight = 17.sp,
          fontWeight = if (header) FontWeight.SemiBold else null,
          textAlign = when (alignments.getOrElse(column) { MdAlign.START }) {
            MdAlign.START -> TextAlign.Start
            MdAlign.CENTER -> TextAlign.Center
            MdAlign.END -> TextAlign.End
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

private fun columnWidths(block: MdBlock.Table, columns: Int): List<Dp> =
  (0 until columns).map { column ->
    val widest = maxOf(
      block.headers.getOrNull(column)?.let(::displayWidth) ?: 0,
      block.rows.maxOfOrNull { displayWidth(it.getOrNull(column).orEmpty()) } ?: 0,
    )
    (widest.coerceIn(3, 28) * 7 + 14).dp
  }

/** Approximate visual width of a cell: strip inline markers, count wide glyphs twice. */
private fun displayWidth(raw: String): Int {
  val plain = raw
    .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "\$1")
    .replace(Regex("""[`*_~]"""), "")
  var width = 0
  for (c in plain) {
    width += if (c.code >= 0x1100 && (
      c.code <= 0x115F ||
        c.code in 0x2E80..0xA4CF ||
        c.code in 0xAC00..0xD7A3 ||
        c.code in 0xF900..0xFAFF ||
        c.code in 0xFE30..0xFE6F ||
        c.code in 0xFF00..0xFF60 ||
        c.code in 0xFFE0..0xFFE6
      )
    ) 2 else 1
  }
  return width
}

@Composable
private fun MarkdownInlineText(
  inlines: List<MdInline>,
  color: Color,
  fontSize: TextUnit,
  lineHeight: TextUnit = fontSize * 1.4f,
  fontWeight: FontWeight? = null,
  fontStyle: FontStyle? = null,
  fontFamily: FontFamily = Mono,
  textAlign: TextAlign? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val annotated = remember(inlines) { inlineAnnotated(inlines) }
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  Text(
    text = annotated,
    modifier = modifier.pointerInput(annotated) {
      awaitEachGesture {
        // Observe rather than consume: text selection and scrolling keep working. If selection
        // takes over the gesture, waitForUpOrCancellation returns null and no link is opened.
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        val layoutResult = layout ?: return@awaitEachGesture
        val offset = layoutResult.getOffsetForPosition(up.position)
        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
          if (MarkdownParser.isSafeLinkUrl(annotation.item)) {
            runCatching {
              context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              )
            }
          }
        }
      }
    },
    color = color,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    fontStyle = fontStyle,
    fontFamily = fontFamily,
    textAlign = textAlign,
    onTextLayout = { layout = it },
  )
}

private fun inlineAnnotated(inlines: List<MdInline>): AnnotatedString = buildAnnotatedString {
  fun emit(nodes: List<MdInline>) {
    nodes.forEach { node ->
      when (node) {
        is MdInline.Text -> append(node.value)
        is MdInline.Code -> withStyle(
          SpanStyle(fontFamily = Mono, color = MdForeground, background = MdCode),
        ) { append(node.value) }
        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { emit(node.children) }
        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(node.children) }
        is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
          emit(node.children)
        }
        is MdInline.Break -> append("\n")
        is MdInline.Link -> {
          if (MarkdownParser.isSafeLinkUrl(node.url)) {
            pushStringAnnotation("URL", node.url)
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = MdLink)) {
              if (node.label.isEmpty()) append(node.url) else emit(node.label)
            }
            pop()
          } else if (node.label.isEmpty()) {
            append(node.url)
          } else {
            emit(node.label)
          }
        }
        is MdInline.ImageRef -> withStyle(SpanStyle(color = MdSecondary)) {
          append(if (node.alt.isNotBlank()) "[image: ${node.alt}]" else "[image]")
        }
      }
    }
  }
  emit(inlines)
}
