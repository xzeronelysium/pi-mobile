package dev.pi.mobile.chat

/**
 * Small GFM-ish markdown AST for assistant answers. No HTML execution, no image fetch.
 * User messages stay plain text; only assistant rows use this renderer.
 *
 * The parser is deliberately pragmatic rather than a full CommonMark implementation: it covers the
 * constructs coding agents actually emit (nested lists, task lists, tables, quotes, fences, links,
 * bare URLs, hard breaks) and degrades unmatched delimiters to literal text while streaming.
 */
enum class MdAlign { START, CENTER, END }

enum class MdTask { TODO, DONE }

sealed class MdBlock {
  data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
  data class Paragraph(val inlines: List<MdInline>) : MdBlock()
  data class ListBlock(
    val ordered: Boolean,
    val start: Int,
    val items: List<MdListItem>,
  ) : MdBlock()
  data class CodeFence(val language: String, val code: String) : MdBlock()
  data class Table(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdAlign> = emptyList(),
  ) : MdBlock()
  data class Quote(val blocks: List<MdBlock>) : MdBlock()
  data class ThematicBreak(val unused: Unit = Unit) : MdBlock()
}

/** One list item: an optional task checkbox plus its block content (so nested lists work). */
data class MdListItem(val task: MdTask?, val blocks: List<MdBlock>)

sealed class MdInline {
  data class Text(val value: String) : MdInline()
  data class Strong(val children: List<MdInline>) : MdInline()
  data class Emphasis(val children: List<MdInline>) : MdInline()
  data class Strikethrough(val children: List<MdInline>) : MdInline()
  data class Code(val value: String) : MdInline()
  data class Link(val label: List<MdInline>, val url: String) : MdInline()
  /** Image refs are shown as plain alt text only — never fetched or rendered. */
  data class ImageRef(val alt: String, val url: String) : MdInline()
  /** A hard line break (two trailing spaces or a trailing backslash). */
  data class Break(val unused: Unit = Unit) : MdInline()
}

object MarkdownParser {
  /** Sentinel for hard breaks inside a paragraph; source text never contains NUL. */
  private const val HARD_BREAK = '\u0000'

  fun parse(source: String): List<MdBlock> {
    val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    return parseBlocks(normalized.split('\n'))
  }

  private fun parseBlocks(lines: List<String>): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank()) {
        i++
        continue
      }

      // Indented code comes first: four spaces beat a heading/fence marker by definition.
      if (indentOf(line) >= 4) {
        val body = ArrayList<String>()
        while (i < lines.size) {
          val current = lines[i]
          if (current.isBlank()) {
            body.add("")
            i++
          } else if (indentOf(current) >= 4) {
            body.add(dropIndent(current, 4))
            i++
          } else {
            break
          }
        }
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
        blocks.add(MdBlock.CodeFence("", body.joinToString("\n")))
        continue
      }

      val fence = openingFence(line)
      if (fence != null) {
        val body = ArrayList<String>()
        i++
        while (i < lines.size && !isClosingFence(lines[i], fence)) {
          body.add(dropIndent(lines[i], fence.indent))
          i++
        }
        if (i < lines.size) i++ // unclosed fences run to EOF, which is valid while streaming
        blocks.add(MdBlock.CodeFence(languageOf(fence.info), body.joinToString("\n")))
        continue
      }

      val heading = ATX_HEADING.matchEntire(line.trimEnd())
      if (heading != null) {
        val text = heading.groupValues[2].replace(Regex("""[ \t]+#+[ \t]*$"""), "").trim()
        blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInlines(text)))
        i++
        continue
      }

      if (THEMATIC_BREAK.matches(line.trim())) {
        blocks.add(MdBlock.ThematicBreak())
        i++
        continue
      }

      if (quoteLine(line) != null) {
        val inner = ArrayList<String>()
        while (i < lines.size) {
          val quoted = quoteLine(lines[i]) ?: break
          inner.add(quoted)
          i++
        }
        blocks.add(MdBlock.Quote(parseBlocks(inner)))
        continue
      }

      if (i + 1 < lines.size && isTableStart(lines[i], lines[i + 1])) {
        val headers = splitRow(line)
        val alignments = splitRow(lines[i + 1]).map(::alignmentOf)
        i += 2
        val rows = ArrayList<List<String>>()
        while (
          i < lines.size &&
          lines[i].isNotBlank() &&
          hasUnescapedPipe(lines[i]) &&
          !isBlockStartLine(lines[i])
        ) {
          rows.add(splitRow(lines[i]))
          i++
        }
        blocks.add(MdBlock.Table(headers, rows, alignments))
        continue
      }

      val marker = listMarker(line)
      if (marker != null) {
        val list = parseList(lines, i, marker)
        blocks.add(list.first)
        i = list.second
        continue
      }

      // Setext heading: a paragraph line underlined with = or -.
      if (i + 1 < lines.size && SETEXT_UNDERLINE.matches(lines[i + 1].trim())) {
        val level = if (lines[i + 1].trim().startsWith("=")) 1 else 2
        blocks.add(MdBlock.Heading(level, parseInlines(line.trim())))
        i += 2
        continue
      }

      val text = StringBuilder()
      var previousHardBreak = false
      var endedWithBackslash = false
      var setextLevel = 0
      var firstLine = true
      while (i < lines.size) {
        val raw = lines[i]
        if (raw.isBlank()) break
        if (!firstLine && SETEXT_UNDERLINE.matches(raw.trim())) {
          setextLevel = if (raw.trim().startsWith("=")) 1 else 2
          i++
          break
        }
        if (!firstLine && (isBlockStartLine(raw) || isTableHeaderAt(lines, i))) break

        val hardBreak = raw.endsWith("  ") || raw.trimEnd().endsWith("\\")
        val trailingBackslash = hardBreak && raw.trimEnd().endsWith("\\")
        var piece = raw.trim()
        if (trailingBackslash) piece = piece.dropLast(1)
        if (!firstLine) text.append(if (previousHardBreak) HARD_BREAK else ' ')
        text.append(piece)
        previousHardBreak = hardBreak
        endedWithBackslash = trailingBackslash
        firstLine = false
        i++
      }
      // A trailing backslash only means "hard break" when a next line was consumed.
      if (endedWithBackslash && text.isNotEmpty()) text.append('\\')
      if (setextLevel > 0) {
        blocks.add(MdBlock.Heading(setextLevel, parseInlines(text.toString())))
      } else if (text.isNotEmpty()) {
        blocks.add(MdBlock.Paragraph(parseInlines(text.toString())))
      }
    }
    return blocks
  }

  private fun parseList(
    lines: List<String>,
    startIndex: Int,
    first: ListMarker,
  ): Pair<MdBlock.ListBlock, Int> {
    val items = ArrayList<MdListItem>()
    var i = startIndex
    while (i < lines.size) {
      val marker = listMarker(lines[i]) ?: break
      if (marker.indent != first.indent || marker.ordered != first.ordered) break

      val body = ArrayList<String>()
      body.add(marker.text)
      i++
      var afterBlank = false
      while (i < lines.size) {
        val current = lines[i]
        if (current.isBlank()) {
          body.add("")
          afterBlank = true
          i++
          continue
        }
        val indent = indentOf(current)
        val sibling = listMarker(current)
        if (indent <= first.indent && sibling != null) break
        if (indent >= marker.contentIndent) {
          body.add(dropIndent(current, marker.contentIndent))
          afterBlank = false
          i++
          continue
        }
        if (afterBlank || indent <= first.indent) break
        // Lazy continuation of the item's paragraph.
        body.add(current.trim())
        afterBlank = false
        i++
      }
      while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)

      var task: MdTask? = null
      val firstBodyLine = body.firstOrNull().orEmpty()
      val taskMatch = TASK_PREFIX.find(firstBodyLine)
      if (taskMatch != null) {
        task = if (taskMatch.groupValues[1] == " ") MdTask.TODO else MdTask.DONE
        body[0] = firstBodyLine.substring(taskMatch.range.last + 1).trimStart()
      }
      items.add(MdListItem(task, parseBlocks(body)))
    }
    return MdBlock.ListBlock(first.ordered, first.start, items) to i
  }

  private data class ListMarker(
    val ordered: Boolean,
    val start: Int,
    val indent: Int,
    val contentIndent: Int,
    val text: String,
  )

  private fun listMarker(line: String): ListMarker? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val rest = line.drop(indent)
    val bullet = BULLET_MARKER.matchEntire(rest)
    if (bullet != null) {
      val spaces = bullet.groupValues[2].length
      val pad = if (spaces > 4) 1 else spaces
      return ListMarker(false, 1, indent, indent + 1 + pad, bullet.groupValues[3])
    }
    val ordered = ORDERED_MARKER.matchEntire(rest)
    if (ordered != null) {
      val digits = ordered.groupValues[1]
      val spaces = ordered.groupValues[3].length
      val pad = if (spaces > 4) 1 else spaces
      return ListMarker(
        ordered = true,
        start = digits.toIntOrNull() ?: 1,
        indent = indent,
        contentIndent = indent + digits.length + 1 + pad,
        text = ordered.groupValues[4],
      )
    }
    return null
  }

  private data class Fence(val indent: Int, val marker: Char, val length: Int, val info: String)

  private fun openingFence(line: String): Fence? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val rest = line.drop(indent)
    val marker = rest.firstOrNull() ?: return null
    if (marker != '`' && marker != '~') return null
    val length = rest.takeWhile { it == marker }.length
    if (length < 3) return null
    val info = rest.drop(length).trim()
    if (marker == '`' && info.contains('`')) return null
    return Fence(indent, marker, length, info)
  }

  private fun isClosingFence(line: String, fence: Fence): Boolean {
    val indent = indentOf(line)
    if (indent > 3) return false
    val rest = line.drop(indent)
    val run = rest.takeWhile { it == fence.marker }.length
    return run >= fence.length && rest.drop(run).isBlank()
  }

  private fun languageOf(info: String): String {
    var language = info.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
    if (language.startsWith("{.") && language.endsWith("}")) language = language.substring(2, language.length - 1)
    return language.trim().trimEnd(',')
  }

  private fun quoteLine(line: String): String? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val rest = line.drop(indent)
    if (!rest.startsWith(">")) return null
    var inner = rest.drop(1)
    if (inner.startsWith(" ")) inner = inner.drop(1)
    return inner
  }

  private fun isTableHeaderAt(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size && isTableStart(lines[index], lines[index + 1])

  private fun isTableStart(header: String, separator: String): Boolean {
    if (!hasUnescapedPipe(header)) return false
    val headerCells = splitRow(header)
    val separatorCells = splitRow(separator)
    if (headerCells.isEmpty() || headerCells.size != separatorCells.size) return false
    if (separatorCells.none { it.contains('-') }) return false
    return separatorCells.all { TABLE_SEPARATOR_CELL.matches(it) }
  }

  private fun alignmentOf(cell: String): MdAlign {
    val trimmed = cell.trim()
    val left = trimmed.startsWith(":")
    val right = trimmed.endsWith(":")
    return when {
      left && right -> MdAlign.CENTER
      right -> MdAlign.END
      else -> MdAlign.START
    }
  }

  private fun hasUnescapedPipe(line: String): Boolean {
    var i = 0
    var ticks = 0
    while (i < line.length) {
      val c = line[i]
      if (c == '\\' && i + 1 < line.length) {
        i += 2
        continue
      }
      if (c == '`') {
        val run = countRun(line, i, '`')
        ticks = if (ticks == 0) run else if (ticks == run) 0 else ticks
        i += run
        continue
      }
      if (c == '|' && ticks == 0) return true
      i++
    }
    return false
  }

  /** Split a table row on unescaped pipes, keeping pipes inside code spans intact. */
  private fun splitRow(line: String): List<String> {
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var i = 0
    var ticks = 0
    while (i < line.length) {
      val c = line[i]
      if (c == '\\' && i + 1 < line.length && line[i + 1] == '|') {
        cell.append('|')
        i += 2
        continue
      }
      if (c == '`') {
        val run = countRun(line, i, '`')
        ticks = if (ticks == 0) run else if (ticks == run) 0 else ticks
        cell.append(line, i, i + run)
        i += run
        continue
      }
      if (c == '|' && ticks == 0) {
        cells.add(cell.toString().trim())
        cell.clear()
        i++
        continue
      }
      cell.append(c)
      i++
    }
    cells.add(cell.toString().trim())
    if (cells.isNotEmpty() && cells.first().isEmpty() && line.trimStart().startsWith("|")) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isEmpty() && line.trimEnd().endsWith("|")) cells.removeAt(cells.lastIndex)
    return cells
  }

  private fun isBlockStartLine(line: String): Boolean {
    val trimmed = line.trim()
    return openingFence(line) != null ||
      ATX_HEADING.matches(line.trimEnd()) ||
      THEMATIC_BREAK.matches(trimmed) ||
      quoteLine(line) != null ||
      listMarker(line) != null
  }

  private fun indentOf(line: String): Int {
    var count = 0
    var i = 0
    while (i < line.length) {
      when (line[i]) {
        ' ' -> count++
        '\t' -> count += 4
        else -> return count
      }
      i++
    }
    return count
  }

  private fun dropIndent(line: String, count: Int): String {
    var i = 0
    var remaining = count
    while (i < line.length && remaining > 0) {
      when (line[i]) {
        ' ' -> remaining--
        '\t' -> remaining -= 4
        else -> return line.substring(i)
      }
      i++
    }
    return if (remaining < 0) " ".repeat(-remaining) + line.substring(i) else line.substring(i)
  }

  fun parseInlines(text: String): List<MdInline> {
    val out = ArrayList<MdInline>()
    val buffer = StringBuilder()
    fun flush() {
      if (buffer.isNotEmpty()) {
        out.add(MdInline.Text(buffer.toString()))
        buffer.clear()
      }
    }
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == HARD_BREAK -> {
          flush()
          out.add(MdInline.Break())
          i++
        }
        c == '\\' && i + 1 < text.length && isEscapable(text[i + 1]) -> {
          buffer.append(text[i + 1])
          i += 2
        }
        c == '`' -> {
          val span = readCodeSpan(text, i)
          if (span != null) {
            flush()
            out.add(MdInline.Code(span.content))
            i = span.end
          } else {
            val run = countRun(text, i, '`')
            buffer.append(text, i, i + run)
            i += run
          }
        }
        c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
          val link = readLink(text, i, image = true)
          if (link != null) {
            flush()
            out.add(MdInline.ImageRef(link.label, link.url))
            i = link.end
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '[' -> {
          val link = readLink(text, i, image = false)
          if (link != null) {
            flush()
            out.add(MdInline.Link(parseInlines(link.label), link.url))
            i = link.end
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '<' -> {
          val htmlBreak = HTML_BREAK.matchAt(text, i)
          if (htmlBreak != null) {
            flush()
            out.add(MdInline.Break())
            i = htmlBreak.range.last + 1
            continue
          }
          val auto = readAutolink(text, i)
          if (auto != null) {
            flush()
            out.add(MdInline.Link(listOf(MdInline.Text(auto.label)), auto.url))
            i = auto.end
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '~' && text.getOrNull(i + 1) == '~' -> {
          val close = findStrikethroughClose(text, i + 2)
          if (close >= 0) {
            flush()
            out.add(MdInline.Strikethrough(parseInlines(text.substring(i + 2, close))))
            i = close + 2
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '*' || c == '_' -> {
          val emphasis = readEmphasis(text, i)
          if (emphasis != null) {
            flush()
            out.add(emphasis.node)
            i = emphasis.end
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '&' -> {
          val entity = readEntity(text, i)
          if (entity != null) {
            buffer.append(entity.text)
            i = entity.end
          } else {
            buffer.append(c)
            i++
          }
        }
        (c == 'h' || c == 'H') && isBareUrlStart(text, i) -> {
          val end = bareUrlEnd(text, i)
          val url = text.substring(i, end)
          flush()
          out.add(MdInline.Link(listOf(MdInline.Text(url)), url))
          i = end
        }
        else -> {
          buffer.append(c)
          i++
        }
      }
    }
    flush()
    return out
  }

  private data class CodeSpan(val content: String, val end: Int)

  private fun readCodeSpan(text: String, start: Int): CodeSpan? {
    val run = countRun(text, start, '`')
    var i = start + run
    while (i < text.length) {
      if (text[i] == '`') {
        val closeRun = countRun(text, i, '`')
        if (closeRun == run) {
          var content = text.substring(start + run, i).replace('\n', ' ')
          if (
            content.length >= 2 &&
            content.startsWith(" ") &&
            content.endsWith(" ") &&
            content.isNotBlank()
          ) {
            content = content.substring(1, content.length - 1)
          }
          return CodeSpan(content, i + closeRun)
        }
        i += closeRun
      } else {
        i++
      }
    }
    return null
  }

  private data class RawLink(val label: String, val url: String, val end: Int)

  private fun readLink(text: String, start: Int, image: Boolean): RawLink? {
    val labelStart = start + if (image) 2 else 1
    val labelEnd = findClosingBracket(text, labelStart)
    if (labelEnd < 0) return null
    var i = labelEnd + 1
    if (text.getOrNull(i) != '(') return null
    i++
    i = skipSpaces(text, i)

    val destination = StringBuilder()
    if (text.getOrNull(i) == '<') {
      i++
      while (i < text.length && text[i] != '>' && text[i] != '\n') {
        if (text[i] == '\\' && i + 1 < text.length) {
          destination.append(text[i + 1])
          i += 2
        } else {
          destination.append(text[i])
          i++
        }
      }
      if (text.getOrNull(i) != '>') return null
      i++
    } else {
      var depth = 0
      while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
          destination.append(text[i + 1])
          i += 2
          continue
        }
        if (c.isWhitespace()) break
        if (c == '(') {
          depth++
          destination.append(c)
          i++
          continue
        }
        if (c == ')') {
          if (depth == 0) break
          depth--
          destination.append(c)
          i++
          continue
        }
        if (c == '<' || c == '>') return null
        destination.append(c)
        i++
      }
    }
    i = skipSpaces(text, i)

    // Optional link title: "…", '…' or (…). It never belongs to the URL.
    if (i < text.length && (text[i] == '"' || text[i] == '\'' || text[i] == '(')) {
      val closer = if (text[i] == '(') ')' else text[i]
      var j = i + 1
      while (j < text.length && text[j] != closer) {
        if (text[j] == '\\') j++
        j++
      }
      if (j >= text.length) return null
      i = skipSpaces(text, j + 1)
    }
    if (text.getOrNull(i) != ')') return null
    return RawLink(text.substring(labelStart, labelEnd), destination.toString().trim(), i + 1)
  }

  private fun findClosingBracket(text: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      when (text[i]) {
        '\\' -> i++
        '[' -> depth++
        ']' -> {
          if (depth == 0) return i
          depth--
        }
      }
      i++
    }
    return -1
  }

  private fun skipSpaces(text: String, from: Int): Int {
    var i = from
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return i
  }

  private data class AutoLink(val label: String, val url: String, val end: Int)

  private fun readAutolink(text: String, start: Int): AutoLink? {
    val end = text.indexOf('>', start + 1)
    if (end < 0) return null
    val inner = text.substring(start + 1, end)
    if (inner.isEmpty() || inner.any { it.isWhitespace() } || inner.contains('<')) return null
    return when {
      inner.startsWith("http://", ignoreCase = true) || inner.startsWith("https://", ignoreCase = true) ->
        AutoLink(inner, inner, end + 1)
      inner.startsWith("mailto:", ignoreCase = true) -> {
        val address = inner.substringAfter(':')
        if (address.isBlank()) null else AutoLink(address, inner, end + 1)
      }
      EMAIL.matches(inner) -> AutoLink(inner, "mailto:$inner", end + 1)
      else -> null
    }
  }

  private fun isBareUrlStart(text: String, index: Int): Boolean {
    val previous = text.getOrNull(index - 1)
    if (previous != null && (previous.isLetterOrDigit() || previous in "_/-.@")) return false
    return text.startsWith("http://", index, ignoreCase = true) ||
      text.startsWith("https://", index, ignoreCase = true)
  }

  private fun bareUrlEnd(text: String, start: Int): Int {
    var i = start
    var depth = 0
    while (i < text.length) {
      val c = text[i]
      if (c.isWhitespace() || c in "<>\"`") break
      if (c == '(') depth++
      if (c == ')') {
        if (depth == 0) break
        depth--
      }
      i++
    }
    // Sentence punctuation belongs to the sentence, not the URL.
    while (i > start && (text[i - 1] in ".,;:!?*_~'")) i--
    return i
  }

  private data class EmphasisResult(val node: MdInline, val end: Int)

  private fun readEmphasis(text: String, start: Int): EmphasisResult? {
    val marker = text[start]
    val run = countRun(text, start, marker)
    if (!canOpenDelimiter(text, start, run, marker)) return null

    var i = start + run
    while (i < text.length) {
      if (text[i] == '\\') {
        i += 2
        continue
      }
      if (text[i] == '`') {
        val span = readCodeSpan(text, i)
        if (span != null) {
          i = span.end
          continue
        }
      }
      if ((text[i] == '[' || (text[i] == '!' && text.getOrNull(i + 1) == '['))) {
        val link = readLink(text, i, image = text[i] == '!')
        if (link != null) {
          i = link.end
          continue
        }
      }
      if (text[i] == marker) {
        val closeRun = countRun(text, i, marker)
        if (canCloseDelimiter(text, i, closeRun, marker)) {
          val width = when {
            run >= 3 && closeRun >= 3 -> 3
            run >= 2 && closeRun >= 2 -> 2
            run == 1 && closeRun == 1 -> 1
            else -> 0
          }
          if (width > 0) {
            val base = text.substring(start + width, i)
            if (base.isNotEmpty()) {
              // A longer closing run can close both the inner and outer emphasis (`**a *b***`).
              val extra = if (run >= 2 && closeRun > width && hasUnmatchedMarker(base, marker)) {
                closeRun - width
              } else {
                0
              }
              val children = parseInlines(base + text.substring(i, i + extra))
              val node = when (width) {
                3 -> MdInline.Emphasis(listOf(MdInline.Strong(children)))
                2 -> MdInline.Strong(children)
                else -> MdInline.Emphasis(children)
              }
              return EmphasisResult(node, i + width + extra)
            }
          }
        }
        i += closeRun
        continue
      }
      i++
    }
    return null
  }

  /** True when [text] still has an opener with no closer, e.g. the `*it` in `**bold *it***`. */
  private fun hasUnmatchedMarker(text: String, marker: Char): Boolean {
    var i = 0
    var open = 0
    while (i < text.length) {
      if (text[i] == '\\') {
        i += 2
        continue
      }
      if (text[i] == '`') {
        val span = readCodeSpan(text, i)
        if (span != null) {
          i = span.end
          continue
        }
      }
      if (text[i] == marker) {
        val run = countRun(text, i, marker)
        open = if (open == 0) run else (open - run).coerceAtLeast(0)
        i += run
        continue
      }
      i++
    }
    return open > 0
  }

  private fun canOpenDelimiter(text: String, start: Int, run: Int, marker: Char): Boolean {
    val before = if (start == 0) null else text[start - 1]
    val after = text.getOrNull(start + run)
    val left = isLeftFlanking(before, after)
    if (marker == '*') return left
    return left && (!isRightFlanking(before, after) || isPunctuation(before))
  }

  private fun canCloseDelimiter(text: String, start: Int, run: Int, marker: Char): Boolean {
    val before = if (start == 0) null else text[start - 1]
    val after = text.getOrNull(start + run)
    val right = isRightFlanking(before, after)
    if (marker == '*') return right
    return right && (!isLeftFlanking(before, after) || isPunctuation(after))
  }

  private fun isLeftFlanking(before: Char?, after: Char?): Boolean {
    if (isWhitespace(after)) return false
    if (isPunctuation(after) && !isWhitespace(before) && !isPunctuation(before)) return false
    return true
  }

  private fun isRightFlanking(before: Char?, after: Char?): Boolean {
    if (isWhitespace(before)) return false
    if (isPunctuation(before) && !isWhitespace(after) && !isPunctuation(after)) return false
    return true
  }

  private fun isWhitespace(c: Char?): Boolean = c == null || c.isWhitespace()

  private fun isPunctuation(c: Char?): Boolean = c != null && !c.isLetterOrDigit() && !c.isWhitespace()

  private fun findStrikethroughClose(text: String, from: Int): Int {
    var i = from
    while (i + 1 < text.length) {
      if (text[i] == '\\') {
        i += 2
        continue
      }
      if (text[i] == '~' && text[i + 1] == '~' && i > from && !text[i - 1].isWhitespace()) return i
      i++
    }
    return -1
  }

  private fun countRun(text: String, start: Int, marker: Char): Int {
    var i = start
    while (i < text.length && text[i] == marker) i++
    return i - start
  }

  private fun isEscapable(c: Char): Boolean = c in "\\`*_{}[]()#+-.!|~<>\"'&$%@^=:;,?/"

  private data class Entity(val text: String, val end: Int)

  private fun readEntity(text: String, start: Int): Entity? {
    val match = ENTITY.matchAt(text, start) ?: return null
    val body = match.groupValues[1]
    val decoded = when {
      body.startsWith("#x", ignoreCase = true) -> codePoint(body.drop(2).toIntOrNull(16))
      body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull())
      else -> NAMED_ENTITIES[body.lowercase()]
    } ?: return null
    return Entity(decoded, match.range.last + 1)
  }

  private fun codePoint(value: Int?): String? {
    if (value == null || value <= 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) return null
    return String(Character.toChars(value))
  }

  fun isSafeHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return false
    if (!(trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true))) {
      return false
    }
    val authority = trimmed.substringAfter("://").substringBefore('/')
    val hostPort = authority.substringAfterLast('@')
    val host = when {
      hostPort.isEmpty() -> return false
      hostPort.startsWith("[") -> hostPort.substringBefore(']') + "]"
      else -> hostPort.substringBefore(':')
    }
    val local = host.equals("localhost", ignoreCase = true) || host == "[::1]"
    return host.isNotBlank() && (host.contains('.') || local) && !host.startsWith(".")
  }

  /** Links the UI may open: web URLs and plain mailto addresses, nothing else. */
  fun isSafeLinkUrl(url: String): Boolean {
    if (isSafeHttpUrl(url)) return true
    val trimmed = url.trim()
    if (!trimmed.startsWith("mailto:", ignoreCase = true)) return false
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return false
    return trimmed.substringAfter(':').isNotBlank()
  }

  private val ATX_HEADING = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*))?$""")
  private val SETEXT_UNDERLINE = Regex("""^ {0,3}(?:=+|-+)[ \t]*$""")
  private val THEMATIC_BREAK = Regex("""^(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
  private val BULLET_MARKER = Regex("""^([-*+])([ \t]+)(.*)$""")
  private val ORDERED_MARKER = Regex("""^(\d{1,9})([.)])([ \t]+)(.*)$""")
  private val TASK_PREFIX = Regex("""^\[([ xX])\](?:[ \t]+|$)""")
  private val TABLE_SEPARATOR_CELL = Regex("""^:?-+:?$""")
  private val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
  private val EMAIL = Regex("""^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$""")
  private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});""")
  private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "times" to "×",
    "divide" to "÷",
    "rarr" to "→",
    "larr" to "←",
    "uarr" to "↑",
    "darr" to "↓",
    "harr" to "↔",
  )
}
