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
  private const val MAX_NESTING = 64

  fun parse(source: String): List<MdBlock> {
    val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', '\uFFFD')
    return parseBlocks(normalized.split('\n'))
  }

  private fun parseBlocks(lines: List<String>, depth: Int = 0): List<MdBlock> {
    if (depth >= MAX_NESTING) {
      return listOf(MdBlock.Paragraph(listOf(MdInline.Text(lines.joinToString("\n")))))
    }
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
        val text = heading.groupValues[2].replace(Regex("""(?:^|[ \t]+)#+[ \t]*$"""), "").trim()
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
        blocks.add(MdBlock.Quote(parseBlocks(inner, depth + 1)))
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
          rows.add(splitRow(lines[i]).take(headers.size).let { it + List(headers.size - it.size) { "" } })
          i++
        }
        blocks.add(MdBlock.Table(headers, rows, alignments))
        continue
      }

      val marker = listMarker(line)
      if (marker != null) {
        val list = parseList(lines, i, marker, depth)
        blocks.add(list.first)
        i = list.second
        continue
      }

      val text = StringBuilder()
      var setextLevel = 0
      var firstLine = true
      while (i < lines.size) {
        val raw = lines[i]
        if (raw.isBlank()) break
        if (!firstLine && SETEXT_UNDERLINE.matches(raw)) {
          setextLevel = if (raw.trim().startsWith("=")) 1 else 2
          i++
          break
        }
        if (!firstLine && (interruptsParagraph(raw) || isTableHeaderAt(lines, i))) break

        // Preserve line endings and trailing spaces until inline parsing: code spans take
        // precedence over hard breaks, and escaped backslashes must remain distinguishable.
        if (!firstLine) text.append('\n')
        text.append(raw.trimStart())
        firstLine = false
        i++
      }
      if (setextLevel > 0) {
        blocks.add(MdBlock.Heading(setextLevel, parseInlines(text.toString().trimEnd())))
      } else if (text.isNotEmpty()) {
        blocks.add(MdBlock.Paragraph(parseInlines(text.toString().trimEnd())))
      }
    }
    return blocks
  }

  private fun parseList(
    lines: List<String>,
    startIndex: Int,
    first: ListMarker,
    depth: Int,
  ): Pair<MdBlock.ListBlock, Int> {
    val items = ArrayList<MdListItem>()
    var i = startIndex
    while (i < lines.size) {
      val marker = listMarker(lines[i]) ?: break
      if (marker.indent != first.indent || marker.ordered != first.ordered || marker.delimiter != first.delimiter) break

      val body = ArrayList<String>()
      body.add(marker.text)
      i++
      var afterBlank = false
      var lazyParagraph: Boolean? = null
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
          lazyParagraph = null
          afterBlank = false
          i++
          continue
        }
        if (afterBlank || interruptsParagraph(current)) break
        if (lazyParagraph == null) lazyParagraph = parseBlocks(body, depth + 1).lastOrNull() is MdBlock.Paragraph
        if (!lazyParagraph) break
        // Lazy continuation is allowed only while the item ends in a paragraph.
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
      items.add(MdListItem(task, parseBlocks(body, depth + 1)))
    }
    return MdBlock.ListBlock(first.ordered, first.start, items) to i
  }

  private data class ListMarker(
    val ordered: Boolean,
    val start: Int,
    val indent: Int,
    val contentIndent: Int,
    val text: String,
    val delimiter: Char,
  )

  private fun listMarker(line: String): ListMarker? {
    val indent = indentOf(line)
    if (indent > 3) return null
    val rest = line.drop(indent)
    val match = BULLET_MARKER.matchEntire(rest) ?: ORDERED_MARKER.matchEntire(rest) ?: return null
    val ordered = match.groupValues[1].first().isDigit()
    val markerWidth = if (ordered) match.groupValues[1].length + 1 else 1
    val delimiter = rest[markerWidth - 1]
    val tail = rest.drop(markerWidth)
    var column = indent + markerWidth
    var chars = 0
    while (chars < tail.length && (tail[chars] == ' ' || tail[chars] == '\t')) {
      column += if (tail[chars] == '\t') 4 - column % 4 else 1
      chars++
    }
    val spaces = column - indent - markerWidth
    val empty = tail.drop(chars).isEmpty()
    val pad = if (empty || spaces > 4) 1 else spaces
    val content = if (empty) "" else " ".repeat(spaces - pad) + tail.drop(chars)
    return ListMarker(
      ordered, if (ordered) match.groupValues[1].toInt() else 1,
      indent, indent + markerWidth + pad, content, delimiter,
    )
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

  private fun hasUnescapedPipe(line: String): Boolean = tablePipes(line).isNotEmpty()

  /** Only matched code spans shield pipes; an unfinished backtick remains literal. */
  private fun tablePipes(line: String): List<Int> {
    val pipes = ArrayList<Int>()
    var i = 0
    while (i < line.length) {
      if (line[i] == '\\' && i + 1 < line.length && isEscapable(line[i + 1])) {
        i += 2
        continue
      }
      if (line[i] == '`') {
        val span = readCodeSpan(line, i)
        if (span != null) {
          i = span.end
          continue
        }
        i += countRun(line, i, '`')
        continue
      }
      if (line[i] == '|') pipes.add(i)
      i++
    }
    return pipes
  }

  /** Keep inline source for the renderer, unescaping table-specific escaped pipes once. */
  private fun splitRow(line: String): List<String> {
    val pipes = tablePipes(line)
    val cells = ArrayList<String>()
    var start = 0
    for (end in pipes + line.length) {
      val raw = line.substring(start, end).trim()
      val cell = StringBuilder()
      var i = 0
      while (i < raw.length) {
        if (raw[i] == '\\' && i + 1 < raw.length && isEscapable(raw[i + 1])) {
          if (raw[i + 1] != '|') cell.append('\\')
          cell.append(raw[i + 1])
          i += 2
        } else {
          cell.append(raw[i++])
        }
      }
      cells.add(cell.toString())
      start = end + 1
    }
    if (pipes.firstOrNull() == line.indexOfFirst { !it.isWhitespace() }) cells.removeAt(0)
    if (pipes.lastOrNull() == line.indexOfLast { !it.isWhitespace() } && cells.isNotEmpty()) {
      cells.removeAt(cells.lastIndex)
    }
    return cells
  }

  private fun interruptsParagraph(line: String): Boolean {
    val marker = listMarker(line)
    if (marker != null) return marker.text.isNotBlank() && (!marker.ordered || marker.start == 1)
    return isBlockStartLine(line)
  }

  private fun isBlockStartLine(line: String): Boolean {
    if (indentOf(line) > 3) return false
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
        '\t' -> count += 4 - count % 4
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
        '\t' -> remaining -= 4 - (count - remaining) % 4
        else -> return line.substring(i)
      }
      i++
    }
    return if (remaining < 0) " ".repeat(-remaining) + line.substring(i) else line.substring(i)
  }

  fun parseInlines(text: String): List<MdInline> = parseInlines(text, 0)

  private fun parseInlines(text: String, depth: Int): List<MdInline> {
    if (depth >= MAX_NESTING) return listOf(MdInline.Text(text))
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
        c == '\n' -> {
          if (buffer.endsWith("  ")) {
            while (buffer.isNotEmpty() && buffer.last() == ' ') buffer.setLength(buffer.length - 1)
            flush()
            out.add(MdInline.Break())
          } else {
            while (buffer.isNotEmpty() && buffer.last() == ' ') buffer.setLength(buffer.length - 1)
            buffer.append(' ')
          }
          i++
        }
        c == '\\' && text.getOrNull(i + 1) == '\n' -> {
          flush()
          out.add(MdInline.Break())
          i += 2
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
            out.add(MdInline.Link(parseInlines(link.label, depth + 1), link.url))
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
        c == '~' && text.getOrNull(i + 1) == '~' &&
          text.getOrNull(i + 2)?.let { !it.isWhitespace() && it != '~' } == true -> {
          val close = findStrikethroughClose(text, i + 2)
          if (close >= 0) {
            flush()
            out.add(MdInline.Strikethrough(parseInlines(text.substring(i + 2, close), depth + 1)))
            i = close + 2
          } else {
            buffer.append(c)
            i++
          }
        }
        c == '*' || c == '_' -> {
          val emphasis = readEmphasis(text, i, depth)
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
        if (text[i] == '\\' && i + 1 < text.length && isEscapable(text[i + 1])) {
          destination.append(text[i]).append(text[i + 1])
          i += 2
        } else {
          if (text[i] == '<' || text[i].isISOControl()) return null
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
        if (c == '\\' && i + 1 < text.length && isEscapable(text[i + 1])) {
          destination.append(c).append(text[i + 1])
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
      if (depth != 0) return null
    }
    val destinationEnd = i
    i = skipSpaces(text, i)

    // Optional link title: "…", '…' or (…). It never belongs to the URL.
    if (i < text.length && (text[i] == '"' || text[i] == '\'' || text[i] == '(')) {
      if (i == destinationEnd) return null
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
    return RawLink(text.substring(labelStart, labelEnd), decodeLinkDestination(destination.toString()), i + 1)
  }

  /** Link destinations decode escapes/entities without interpreting inline markup or HTML. */
  private fun decodeLinkDestination(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < raw.length) {
      if (raw[i] == '\\' && i + 1 < raw.length && isEscapable(raw[i + 1])) {
        out.append(raw[i + 1])
        i += 2
      } else {
        val entity = if (raw[i] == '&') readEntity(raw, i) else null
        if (entity != null) {
          out.append(entity.text)
          i = entity.end
        } else {
          out.append(raw[i++])
        }
      }
    }
    return out.toString()
  }

  private fun findClosingBracket(text: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
      if (text[i] == '`') {
        val span = readCodeSpan(text, i)
        if (span != null) {
          i = span.end
          continue
        }
      }
      when (text[i]) {
        '\\' -> if (text.getOrNull(i + 1)?.let(::isEscapable) == true) i++
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
    while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n')) i++
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

  private fun readEmphasis(text: String, start: Int, depth: Int): EmphasisResult? {
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
              val children = parseInlines(base + text.substring(i, i + extra), depth + 1)
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
      if (text[i] == '`') {
        val span = readCodeSpan(text, i)
        if (span != null) {
          i = span.end
          continue
        }
      }
      if (text[i] == '[' || (text[i] == '!' && text.getOrNull(i + 1) == '[')) {
        val link = readLink(text, i, text[i] == '!')
        if (link != null) {
          i = link.end
          continue
        }
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
      else -> NAMED_ENTITIES[body]
    } ?: return null
    return Entity(decoded, match.range.last + 1)
  }

  private fun codePoint(value: Int?): String? {
    if (value == null || value <= 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) return "\uFFFD"
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
  private val BULLET_MARKER = Regex("""^([-*+])(?:[ \t]+.*)?$""")
  private val ORDERED_MARKER = Regex("""^(\d{1,9})([.)])(?:[ \t]+.*)?$""")
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
    "AMP" to "&",
    "LT" to "<",
    "GT" to ">",
    "QUOT" to "\"",
    "nbsp" to "\u00A0",
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
