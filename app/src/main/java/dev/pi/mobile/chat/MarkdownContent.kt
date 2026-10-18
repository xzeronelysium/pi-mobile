package dev.pi.mobile.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.theme.Foreground
import dev.pi.mobile.theme.Hairline
import dev.pi.mobile.theme.Mono
import dev.pi.mobile.theme.PiRule
import dev.pi.mobile.theme.PiSpace
import dev.pi.mobile.theme.PiType
import dev.pi.mobile.theme.Sans
import dev.pi.mobile.theme.Secondary

// The transcript's ink is the same ink as the chrome. No local greys: #737373 failed AA and made
// metadata the loudest thing on the page.
private val MdForeground = Foreground
private val MdSecondary = Secondary
private val MdDivider = Hairline
private val MdLink = Secondary

/** Renders assistant markdown (bold/italic/code/headings/lists/tables/quotes). */
@Composable
fun MarkdownContent(source: String, modifier: Modifier = Modifier) {
  val blocks = remember(source) { MarkdownParser.parse(source) }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    blocks.forEach { block ->
      when (block) {
        is MdBlock.Heading -> {
          // Headings are chrome, not machine output: the sans family marks the change of voice.
          val style = if (block.level == 1) PiType.Title else PiType.Heading
          MarkdownInlineText(
            block.inlines,
            color = MdForeground,
            fontSize = style.fontSize,
            lineHeight = style.lineHeight,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Sans,
          )
        }
        is MdBlock.Paragraph -> MarkdownInlineText(
          block.inlines,
          color = MdForeground,
          fontSize = PiType.Body.fontSize,
          lineHeight = PiType.Body.lineHeight,
        )
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
          Text("│ ", color = MdSecondary, fontSize = PiType.BodySmall.fontSize, fontFamily = Mono)
          MarkdownInlineText(
            block.inlines,
            color = MdSecondary,
            fontSize = PiType.BodySmall.fontSize,
            lineHeight = PiType.BodySmall.lineHeight,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.weight(1f),
          )
        }
        is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          block.items.forEach { item ->
            Row {
              Text("•  ", color = MdSecondary, fontSize = PiType.Body.fontSize)
              MarkdownInlineText(
                item,
                color = MdForeground,
                fontSize = PiType.Body.fontSize,
                lineHeight = PiType.Body.lineHeight,
                modifier = Modifier.weight(1f, fill = false),
              )
            }
          }
        }
        is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          block.items.forEachIndexed { index, item ->
            Row {
              Text("${index + 1}.  ", color = MdSecondary, fontSize = PiType.Body.fontSize)
              MarkdownInlineText(
                item,
                color = MdForeground,
                fontSize = PiType.Body.fontSize,
                lineHeight = PiType.Body.lineHeight,
                modifier = Modifier.weight(1f, fill = false),
              )
            }
          }
        }
        is MdBlock.CodeFence -> SelectionContainer {
          Column(Modifier.fillMaxWidth().padding(vertical = PiSpace.x1)) {
            PiRule()
            if (block.language.isNotBlank()) {
              Text(block.language, style = PiType.Meta, color = MdSecondary, modifier = Modifier.padding(vertical = PiSpace.x1))
            }
            Text(block.code, style = PiType.Code.copy(color = MdForeground), modifier = Modifier.padding(vertical = PiSpace.x1))
            PiRule()
          }
        }
        is MdBlock.Table -> MarkdownTable(block)
        is MdBlock.ThematicBreak -> HorizontalDivider(color = MdDivider, modifier = Modifier.padding(vertical = 4.dp))
      }
    }
  }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table) {
  val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
  val scroll = rememberScrollState()
  SelectionContainer {
    Column(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(scroll)
        .padding(vertical = 4.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in 0 until colCount) {
          val cell = block.headers.getOrElse(c) { "" }
          Box(Modifier.widthIn(min = 72.dp, max = 160.dp)) {
            MarkdownInlineText(
              MarkdownParser.parseInlines(cell),
              color = MdForeground,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              lineHeight = 16.sp,
            )
          }
        }
      }
      HorizontalDivider(color = MdDivider, modifier = Modifier.padding(vertical = 4.dp).widthIn(min = (colCount * 84).dp))
      block.rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 2.dp)) {
          for (c in 0 until colCount) {
            val cell = row.getOrElse(c) { "" }
            Box(Modifier.widthIn(min = 72.dp, max = 160.dp)) {
              MarkdownInlineText(
                MarkdownParser.parseInlines(cell),
                color = MdForeground,
                fontSize = 12.sp,
                lineHeight = 16.sp,
              )
            }
          }
        }
      }
    }
  }
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
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val annotated = remember(inlines) { inlineAnnotated(inlines) }
  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
  Text(
    text = annotated,
    modifier = modifier.pointerInput(annotated) {
      detectTapGestures { pos ->
        val layoutResult = layout ?: return@detectTapGestures
        val offset = layoutResult.getOffsetForPosition(pos)
        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { ann ->
          if (MarkdownParser.isSafeHttpUrl(ann.item)) {
            runCatching {
              context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
    onTextLayout = { layout = it },
  )
}

private fun inlineAnnotated(inlines: List<MdInline>): AnnotatedString = buildAnnotatedString {
  fun emit(nodes: List<MdInline>) {
    nodes.forEach { node ->
      when (node) {
        is MdInline.Text -> append(node.value)
        is MdInline.Code -> withStyle(SpanStyle(fontFamily = Mono, color = MdForeground)) { append("`${node.value}`") }
        is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { emit(node.children) }
        is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(node.children) }
        is MdInline.Link -> {
          val safe = MarkdownParser.isSafeHttpUrl(node.url)
          if (safe) {
            pushStringAnnotation("URL", node.url)
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = MdLink)) {
              if (node.label.isEmpty()) append(node.url) else emit(node.label)
            }
            pop()
          } else {
            withStyle(SpanStyle(color = MdLink)) {
              if (node.label.isEmpty()) append(node.url) else emit(node.label)
            }
          }
        }
        is MdInline.ImageRef -> append(if (node.alt.isNotBlank()) "[image: ${node.alt}]" else "[image]")
      }
    }
  }
  emit(inlines)
}
