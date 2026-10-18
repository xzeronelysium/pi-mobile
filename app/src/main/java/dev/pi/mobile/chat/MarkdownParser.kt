package dev.pi.mobile.chat

/**
 * Small GFM-ish markdown AST for assistant answers. No HTML execution, no image fetch.
 * User messages stay plain text; only assistant rows use this renderer.
 */
sealed class MdBlock {
  data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
  data class Paragraph(val inlines: List<MdInline>) : MdBlock()
  data class BulletList(val items: List<List<MdInline>>) : MdBlock()
  data class OrderedList(val items: List<List<MdInline>>) : MdBlock()
  data class CodeFence(val language: String, val code: String) : MdBlock()
  data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
  data class Quote(val inlines: List<MdInline>) : MdBlock()
  data class ThematicBreak(val unused: Unit = Unit) : MdBlock()
}

sealed class MdInline {
  data class Text(val value: String) : MdInline()
  data class Strong(val children: List<MdInline>) : MdInline()
  data class Emphasis(val children: List<MdInline>) : MdInline()
  data class Code(val value: String) : MdInline()
  data class Link(val label: List<MdInline>, val url: String) : MdInline()
  /** Image refs are shown as plain alt text only — never fetched or rendered. */
  data class ImageRef(val alt: String, val url: String) : MdInline()
}

object MarkdownParser {
  fun parse(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (line.isBlank()) {
        i++
        continue
      }
      if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
        blocks.add(MdBlock.ThematicBreak()); i++; continue
      }
      val fence = FENCE.matchEntire(line)
      if (fence != null) {
        // info string: first token is language (may include + # .); rest is ignored metadata
        val info = fence.groupValues[1].trim()
        val lang = info.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        val body = StringBuilder()
        i++
        while (i < lines.size && !isClosingFence(lines[i])) {
          if (body.isNotEmpty()) body.append('\n')
          body.append(lines[i])
          i++
        }
        if (i < lines.size) i++ // closing fence when present; unclosed → EOF is valid MD
        blocks.add(MdBlock.CodeFence(lang, body.toString()))
        continue
      }
      val heading = HEADING.matchEntire(line)
      if (heading != null) {
        blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInlines(heading.groupValues[2].trim())))
        i++; continue
      }
      if (line.startsWith(">")) {
        val text = line.removePrefix(">").trimStart()
        blocks.add(MdBlock.Quote(parseInlines(text)))
        i++; continue
      }
      if (isTableHeader(lines, i)) {
        val headers = splitRow(lines[i])
        i += 2 // skip header + separator
        val rows = ArrayList<List<String>>()
        while (i < lines.size && lines[i].contains('|') && !lines[i].isBlank()) {
          rows.add(splitRow(lines[i])); i++
        }
        blocks.add(MdBlock.Table(headers, rows)); continue
      }
      if (BULLET.matchEntire(line.trim()) != null) {
        val items = ArrayList<List<MdInline>>()
        while (i < lines.size) {
          val m = BULLET.matchEntire(lines[i].trim()) ?: break
          items.add(parseInlines(m.groupValues[1])); i++
        }
        blocks.add(MdBlock.BulletList(items)); continue
      }
      if (ORDERED.matchEntire(line.trim()) != null) {
        val items = ArrayList<List<MdInline>>()
        while (i < lines.size) {
          val m = ORDERED.matchEntire(lines[i].trim()) ?: break
          items.add(parseInlines(m.groupValues[1])); i++
        }
        blocks.add(MdBlock.OrderedList(items)); continue
      }
      val para = StringBuilder(line)
      i++
      while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i]) && !isTableHeader(lines, i)) {
        para.append(' ').append(lines[i].trim()); i++
      }
      blocks.add(MdBlock.Paragraph(parseInlines(para.toString())))
    }
    return blocks
  }

  private fun isClosingFence(line: String): Boolean = CLOSING_FENCE.matches(line)

  private fun isBlockStart(line: String): Boolean {
    val t = line.trim()
    return HEADING.matches(t) || FENCE.matches(line) || BULLET.matches(t) || ORDERED.matches(t) ||
      t.startsWith(">") || t == "---" || t == "***" || t == "___"
  }

  private fun isTableHeader(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    if (!lines[index].contains('|')) return false
    val sep = lines[index + 1].trim()
    if (!TABLE_SEP.matches(sep)) return false
    // Separator must look like a real GFM row (dashes), not a bare pipe line.
    return sep.contains('-')
  }

  private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split('|').map { it.trim() }
  }

  fun parseInlines(text: String): List<MdInline> {
    val out = ArrayList<MdInline>()
    var i = 0
    val buf = StringBuilder()
    fun flush() {
      if (buf.isNotEmpty()) {
        out.add(MdInline.Text(buf.toString())); buf.clear()
      }
    }
    while (i < text.length) {
      when {
        text.startsWith("![", i) -> {
          val closeAlt = text.indexOf(']', i + 2)
          val openParen = if (closeAlt >= 0) text.indexOf('(', closeAlt) else -1
          val closeParen = if (openParen >= 0) text.indexOf(')', openParen) else -1
          if (closeAlt > i && openParen == closeAlt + 1 && closeParen > openParen) {
            flush()
            val alt = text.substring(i + 2, closeAlt)
            val url = text.substring(openParen + 1, closeParen)
            out.add(MdInline.ImageRef(alt, url))
            i = closeParen + 1
          } else {
            buf.append(text[i]); i++
          }
        }
        text.startsWith("[", i) -> {
          val closeLabel = text.indexOf(']', i + 1)
          val openParen = if (closeLabel >= 0) text.indexOf('(', closeLabel) else -1
          val closeParen = if (openParen >= 0) text.indexOf(')', openParen) else -1
          if (closeLabel > i && openParen == closeLabel + 1 && closeParen > openParen) {
            flush()
            val label = text.substring(i + 1, closeLabel)
            val url = text.substring(openParen + 1, closeParen)
            out.add(MdInline.Link(parseInlines(label), url))
            i = closeParen + 1
          } else {
            buf.append(text[i]); i++
          }
        }
        text.startsWith("`", i) -> {
          val end = text.indexOf('`', i + 1)
          if (end > i) {
            flush()
            out.add(MdInline.Code(text.substring(i + 1, end)))
            i = end + 1
          } else {
            buf.append(text[i]); i++
          }
        }
        text.startsWith("**", i) -> {
          val end = text.indexOf("**", i + 2)
          if (end > i) {
            flush()
            out.add(MdInline.Strong(parseInlines(text.substring(i + 2, end))))
            i = end + 2
          } else {
            buf.append(text[i]); i++
          }
        }
        text.startsWith("__", i) -> {
          // Require non-word boundaries so __not__ in identifiers is rare; still allow __bold__
          val end = text.indexOf("__", i + 2)
          if (end > i && (end + 2 >= text.length || !text[end + 2].isLetterOrDigit())) {
            flush()
            out.add(MdInline.Strong(parseInlines(text.substring(i + 2, end))))
            i = end + 2
          } else {
            buf.append(text[i]); i++
          }
        }
        text[i] == '*' -> {
          val end = text.indexOf('*', i + 1)
          if (end > i) {
            flush()
            out.add(MdInline.Emphasis(parseInlines(text.substring(i + 1, end))))
            i = end + 1
          } else {
            buf.append(text[i]); i++
          }
        }
        text[i] == '_' -> {
          // Emphasis only when both sides are word boundaries (not inside paths / snake_case).
          val atBoundary = i == 0 || !isWordChar(text[i - 1])
          if (atBoundary) {
            val end = findUnderscoreEmphasisEnd(text, i + 1)
            if (end > i) {
              flush()
              out.add(MdInline.Emphasis(parseInlines(text.substring(i + 1, end))))
              i = end + 1
            } else {
              buf.append(text[i]); i++
            }
          } else {
            buf.append(text[i]); i++
          }
        }
        else -> {
          buf.append(text[i]); i++
        }
      }
    }
    flush()
    return out
  }

  private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

  /**
   * Closing `_` for emphasis: must not be mid-word (next char not word char),
   * and the span must not contain raw path-like segments that would break files.
   */
  private fun findUnderscoreEmphasisEnd(text: String, from: Int): Int {
    var j = from
    while (j < text.length) {
      if (text[j] == '_') {
        val nextOk = j + 1 >= text.length || !isWordChar(text[j + 1])
        val prevOk = j == from || !isWordChar(text[j - 1]) // content before close can be word
        // Standard MD: closing _ is punctuation boundary on the right.
        if (nextOk && j > from) {
          // Reject if the interior looks like a single snake_case token with internal underscores only?
          // Allow normal words; reject if open was after path start — already handled by open boundary.
          return j
        }
      }
      j++
    }
    return -1
  }

  /** Only http/https may be opened by the UI. */
  fun isSafeHttpUrl(url: String): Boolean {
    val t = url.trim()
    if (t.isEmpty()) return false
    val lower = t.lowercase()
    if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false
    // Block embedded credentials tricks / javascript via weird schemes already excluded.
    if (lower.contains("javascript:")) return false
    return true
  }

  // Language/info: optional spaces after ```, then info string (c++, c#, python title="x")
  private val HEADING = Regex("""^(#{1,6})\s+(.+)$""")
  private val FENCE = Regex("""^ {0,3}```\s*([^`]*)$""")
  private val CLOSING_FENCE = Regex("""^ {0,3}```\s*$""")
  private val BULLET = Regex("""^[-*+]\s+(.+)$""")
  private val ORDERED = Regex("""^\d+[.)]\s+(.+)$""")
  private val TABLE_SEP = Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
}
