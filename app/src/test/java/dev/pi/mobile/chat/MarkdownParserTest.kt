package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
  @Test
  fun parsesHeadingsEmphasisListsLinksAndCode() {
    val src = """
      |# Title
      |
      |A **bold** and *italic* word with `inline` and a [link](https://example.com).
      |
      |- one
      |- two
      |
      |```kotlin
      |val x = 1
      |```
    """.trimMargin()

    val blocks = MarkdownParser.parse(src)
    assertTrue(blocks[0] is MdBlock.Heading && (blocks[0] as MdBlock.Heading).level == 1)
    val para = blocks[1] as MdBlock.Paragraph
    val kinds = para.inlines.map { it::class.simpleName }
    assertTrue(kinds.contains("Strong"))
    assertTrue(kinds.contains("Emphasis"))
    assertTrue(kinds.contains("Code"))
    assertTrue(kinds.contains("Link"))
    val list = blocks.filterIsInstance<MdBlock.ListBlock>().single()
    assertEquals(listOf("one", "two"), list.items.map { it.blocks.joinToString(" ") { block -> block.flatten() } })
    val fence = blocks.first { it is MdBlock.CodeFence } as MdBlock.CodeFence
    assertEquals("kotlin", fence.language)
    assertEquals("val x = 1", fence.code.trim())
  }

  @Test
  fun parsesNestedListsAsNestedBlocks() {
    val src = """
      |- one
      |  - a
      |  - b
      |- two
    """.trimMargin()

    val list = MarkdownParser.parse(src).filterIsInstance<MdBlock.ListBlock>().single()
    assertEquals(2, list.items.size)
    assertEquals("one", list.items[0].blocks.first().flatten())

    val nested = list.items[0].blocks.filterIsInstance<MdBlock.ListBlock>().single()
    assertEquals(listOf("a", "b"), nested.items.map { it.blocks.joinToString(" ") { block -> block.flatten() } })
    assertEquals("two", list.items[1].blocks.joinToString(" ") { block -> block.flatten() })
  }

  @Test
  fun orderedListsKeepTheirStartNumber() {
    val list = MarkdownParser.parse("3. c\n4. d").filterIsInstance<MdBlock.ListBlock>().single()
    assertTrue(list.ordered)
    assertEquals(3, list.start)
    assertEquals(listOf("c", "d"), list.items.map { it.blocks.joinToString(" ") { block -> block.flatten() } })
  }

  @Test
  fun parsesTaskListCheckboxes() {
    val list = MarkdownParser.parse("- [ ] todo\n- [x] done\n- [X] also done")
      .filterIsInstance<MdBlock.ListBlock>().single()
    assertEquals(listOf(MdTask.TODO, MdTask.DONE, MdTask.DONE), list.items.map { it.task })
    assertEquals(listOf("todo", "done", "also done"), list.items.map { it.blocks.joinToString(" ") { block -> block.flatten() } })
  }

  @Test
  fun groupsConsecutiveQuoteLinesIntoOneBlock() {
    val quote = MarkdownParser.parse("> first\n> second\n>\n> - item")
      .filterIsInstance<MdBlock.Quote>().single()
    assertEquals("first second", quote.blocks[0].flatten())
    val list = quote.blocks[1] as MdBlock.ListBlock
    assertEquals("item", list.items.single().blocks.joinToString(" ") { block -> block.flatten() })
  }

  @Test
  fun parsesSimpleTableWithAlignment() {
    val src = """
      |Name|Value|Note|
      |:---|:---:|---:|
      |a|1|left|
      |b|2|right|
    """.trimIndent()
    val table = MarkdownParser.parse(src).filterIsInstance<MdBlock.Table>().single()
    assertEquals(listOf("Name", "Value", "Note"), table.headers)
    assertEquals(
      listOf(MdAlign.START, MdAlign.CENTER, MdAlign.END),
      table.alignments,
    )
    assertEquals(listOf(listOf("a", "1", "left"), listOf("b", "2", "right")), table.rows)
  }

  @Test
  fun tableCellsKeepInlineMarkdownSourceForRenderer() {
    val src = """
      |Col|
      |---|
      |**bold** and `code`|
    """.trimIndent()
    val table = MarkdownParser.parse(src).filterIsInstance<MdBlock.Table>().single()
    assertEquals("**bold** and `code`", table.rows.single().single())
  }

  @Test
  fun tableSplitsOnUnescapedPipesOnly() {
    val src = """
      |A|B|C|
      |---|---|---|
      |a \| b|`x|y`|c|
    """.trimIndent()
    val table = MarkdownParser.parse(src).filterIsInstance<MdBlock.Table>().single()
    assertEquals(listOf("a | b", "`x|y`", "c"), table.rows.single())
  }

  @Test
  fun pipesWithoutSeparatorStayParagraphText() {
    val blocks = MarkdownParser.parse("a | b\nnot a separator")
    assertTrue(blocks.none { it is MdBlock.Table })
    assertEquals("a | b not a separator", blocks.single().flatten())
  }

  @Test
  fun doesNotExecuteHtmlOrFetchImages() {
    val blocks = MarkdownParser.parse("Hello <script>alert(1)</script> and ![x](https://evil/img.png)")
    val text = blocks.single().flatten()
    assertTrue(text.contains("<script>"))
    assertTrue((blocks.single() as MdBlock.Paragraph).inlines.any { it is MdInline.ImageRef })
    assertTrue(blocks.none { it is MdBlock.CodeFence && it.language == "html-exec" })
  }

  @Test
  fun fencesAcceptLanguageWithPlusHashAndInfoStrings() {
    val src = """
      |```c++
      |int main() {}
      |```
      |
      |```c#
      |var x = 1;
      |```
      |
      |```  python title="x.py"
      |print(1)
      |```
    """.trimMargin()
    val fences = MarkdownParser.parse(src).filterIsInstance<MdBlock.CodeFence>()
    assertEquals(3, fences.size)
    assertEquals("c++", fences[0].language)
    assertEquals("int main() {}", fences[0].code.trim())
    assertEquals("c#", fences[1].language)
    assertEquals("python", fences[2].language)
    assertEquals("print(1)", fences[2].code.trim())
  }

  @Test
  fun tildeFencesAndLongerBacktickFencesCanContainFenceLines() {
    val tilde = MarkdownParser.parse("~~~~\n```\ninside\n```\n~~~~")
      .filterIsInstance<MdBlock.CodeFence>().single()
    assertEquals("```\ninside\n```", tilde.code)

    val long = MarkdownParser.parse("````\n```kotlin\nval x = 1\n```\n````")
      .filterIsInstance<MdBlock.CodeFence>().single()
    assertEquals("```kotlin\nval x = 1\n```", long.code)
  }

  @Test
  fun unclosedFenceConsumesRestAsCodeNotForcedProse() {
    val src = "before\n\n```js\nconst x = 1\nstill code"
    val blocks = MarkdownParser.parse(src)
    assertTrue(blocks.any { it is MdBlock.Paragraph })
    val fence = blocks.filterIsInstance<MdBlock.CodeFence>().single()
    assertEquals("js", fence.language)
    assertTrue(fence.code.contains("const x = 1"))
    assertTrue(fence.code.contains("still code"))
  }

  @Test
  fun indentedCodeIsMachineOutput() {
    val fence = MarkdownParser.parse("    val x = 1\n    val y = 2")
      .filterIsInstance<MdBlock.CodeFence>().single()
    assertEquals("", fence.language)
    assertEquals("val x = 1\nval y = 2", fence.code)
  }

  @Test
  fun setextHeadingsAreHeadings() {
    val blocks = MarkdownParser.parse("Title\n=====\n\nSub\n---")
    assertEquals(1, (blocks[0] as MdBlock.Heading).level)
    assertEquals("Title", blocks[0].flatten())
    assertEquals(2, (blocks[1] as MdBlock.Heading).level)
    assertEquals("Sub", blocks[1].flatten())
  }

  @Test
  fun multilineSetextHeadingKeepsTheWholeParagraph() {
    val heading = MarkdownParser.parse("line one\nline two\n===")
      .filterIsInstance<MdBlock.Heading>().single()
    assertEquals(1, heading.level)
    assertEquals("line one line two", heading.flatten())
  }

  @Test
  fun parsesStrikethroughAndNestedEmphasis() {
    val inlines = MarkdownParser.parseInlines("~~gone~~ and ***both***")
    assertTrue(inlines.any { it is MdInline.Strikethrough })
    val both = inlines.filterIsInstance<MdInline.Emphasis>().single()
    assertTrue(both.children.single() is MdInline.Strong)
  }

  @Test
  fun nestedItalicInsideBoldUsesTheSharedClosingRun() {
    val strong = MarkdownParser.parseInlines("**bold *it***")
      .filterIsInstance<MdInline.Strong>().single()
    assertTrue(strong.children.any { it is MdInline.Emphasis })
    assertEquals("bold it", strong.children.flatten())
  }

  @Test
  fun bareAndAngleBracketUrlsBecomeLinks() {
    val inlines = MarkdownParser.parseInlines("see https://example.com/x, and <https://example.org/y>")
    val links = inlines.filterIsInstance<MdInline.Link>()
    assertEquals(listOf("https://example.com/x", "https://example.org/y"), links.map { it.url })
    assertEquals("https://example.com/x", links[0].label.joinToString("") { (it as MdInline.Text).value })
  }

  @Test
  fun emailAutolinksUseMailto() {
    val link = MarkdownParser.parseInlines("<dev@example.com>").filterIsInstance<MdInline.Link>().single()
    assertEquals("mailto:dev@example.com", link.url)
    assertEquals("dev@example.com", link.label.joinToString("") { (it as MdInline.Text).value })
  }

  @Test
  fun linkTitlesAreNotPartOfTheUrlAndParensSurvive() {
    val link = MarkdownParser.parseInlines(
      "[wiki](https://en.wikipedia.org/wiki/A_(b) \"A title\")",
    ).filterIsInstance<MdInline.Link>().single()
    assertEquals("https://en.wikipedia.org/wiki/A_(b)", link.url)
  }

  @Test
  fun multiBacktickCodeSpansKeepInnerBackticks() {
    val code = MarkdownParser.parseInlines("use ``a ` b`` here").filterIsInstance<MdInline.Code>().single()
    assertEquals("a ` b", code.value)
  }

  @Test
  fun htmlBreakBecomesAHardBreakWithoutExecutingHtml() {
    val para = MarkdownParser.parse("a<br>b<br />c")
      .filterIsInstance<MdBlock.Paragraph>().single()
    assertEquals(2, para.inlines.filterIsInstance<MdInline.Break>().size)
    assertEquals("a\nb\nc", para.flatten())
  }

  @Test
  fun trailingBackslashWithoutANextLineStaysLiteral() {
    val para = MarkdownParser.parse("a\\").filterIsInstance<MdBlock.Paragraph>().single()
    assertEquals("a\\", para.flatten())
  }

  @Test
  fun hardBreaksSurviveAsBreakNodes() {
    val para = MarkdownParser.parse("line one  \nline two\\\nline three")
      .filterIsInstance<MdBlock.Paragraph>().single()
    assertEquals(2, para.inlines.filterIsInstance<MdInline.Break>().size)
    assertEquals("line one\nline two\nline three", para.flatten())
  }

  @Test
  fun backslashEscapesKeepMarkdownLiteral() {
    val para = MarkdownParser.parse("\\*not italic\\* and \\`code\\`")
      .filterIsInstance<MdBlock.Paragraph>().single()
    assertTrue(para.inlines.all { it is MdInline.Text })
    assertEquals("*not italic* and `code`", para.flatten())
  }

  @Test
  fun arithmeticAndPathsDoNotBecomeEmphasis() {
    assertTrue(MarkdownParser.parseInlines("2 * 3 * 4").all { it is MdInline.Text })
    assertTrue(MarkdownParser.parseInlines("see /home/user_name/my_file.txt").all { it is MdInline.Text })
    assertTrue(MarkdownParser.parseInlines("snake_case_name").all { it is MdInline.Text })
  }

  @Test
  fun underscoreEmphasisRequiresWordBoundariesBothSides() {
    val inlines = MarkdownParser.parseInlines("ok _italic_ end")
    assertTrue(inlines.any { it is MdInline.Emphasis })
    val em = inlines.filterIsInstance<MdInline.Emphasis>().single()
    assertEquals("italic", em.children.filterIsInstance<MdInline.Text>().joinToString("") { it.value })
  }

  @Test
  fun entitiesAreDecodedOutsideCode() {
    val text = MarkdownParser.parseInlines("a &amp; b &#39;c&#39;")
      .filterIsInstance<MdInline.Text>().joinToString("") { it.value }
    assertEquals("a & b 'c'", text)
  }

  @Test
  fun unmatchedDelimitersStayLiteralWhileStreaming() {
    val para = MarkdownParser.parse("**still typing and `an open fence")
      .filterIsInstance<MdBlock.Paragraph>().single()
    assertTrue(para.inlines.none { it is MdInline.Strong })
    assertTrue(para.flatten().contains("**still typing"))
    assertTrue(para.flatten().contains("`an open fence"))
  }

  @Test
  fun onlySafeHttpHttpsLinksParsedAsLinks() {
    val http = MarkdownParser.parseInlines("[ok](https://example.com/a)")
    assertTrue(http.any { it is MdInline.Link && (it as MdInline.Link).url.startsWith("https://") })
    // Parser still produces Link nodes; UI must refuse unsafe schemes. Parsing keeps the url string.
    val js = MarkdownParser.parseInlines("[x](javascript:alert(1))")
    val link = js.filterIsInstance<MdInline.Link>().singleOrNull()
    assertTrue(link == null || link.url.startsWith("javascript:"))
  }

  @Test
  fun safeHttpUrlHelperAcceptsOnlyHttpHttps() {
    assertTrue(MarkdownParser.isSafeHttpUrl("https://example.com/x"))
    assertTrue(MarkdownParser.isSafeHttpUrl("http://example.com"))
    assertTrue(MarkdownParser.isSafeHttpUrl("http://localhost:3000/x"))
    assertTrue(MarkdownParser.isSafeHttpUrl("http://192.168.1.5:8080"))
    assertFalse(MarkdownParser.isSafeHttpUrl("javascript:alert(1)"))
    assertFalse(MarkdownParser.isSafeHttpUrl("file:///etc/passwd"))
    assertFalse(MarkdownParser.isSafeHttpUrl("custom://app"))
    assertFalse(MarkdownParser.isSafeHttpUrl("https:///no-host"))
    assertFalse(MarkdownParser.isSafeHttpUrl("https://exa mple.com"))
    assertFalse(MarkdownParser.isSafeHttpUrl(""))
  }

  @Test
  fun safeLinkUrlAlsoAllowsPlainMailto() {
    assertTrue(MarkdownParser.isSafeLinkUrl("https://example.com"))
    assertTrue(MarkdownParser.isSafeLinkUrl("mailto:dev@example.com"))
    assertFalse(MarkdownParser.isSafeLinkUrl("javascript:alert(1)"))
    assertFalse(MarkdownParser.isSafeLinkUrl("mailto:"))
    assertFalse(MarkdownParser.isSafeLinkUrl("mailto:dev@example.com\nBcc:x@y.z"))
  }

  @Test
  fun imageRefsKeepAltTextWithoutFetching() {
    val image = MarkdownParser.parseInlines("![alt text](https://example.com/a.png)")
      .filterIsInstance<MdInline.ImageRef>().single()
    assertEquals("alt text", image.alt)
    assertEquals("https://example.com/a.png", image.url)
  }

  @Test
  fun realisticAgentAnswerKeepsItsStructure() {
    val src = """
      |## Summary
      |
      |I updated **three** files:
      |
      |1. `MarkdownParser.kt`
      |2. `MarkdownContent.kt`
      |3. `MarkdownParserTest.kt`
      |
      |### Notes
      |
      |- [x] Parser
      |- [ ] Renderer
      |  - nested detail
      |- [ ] Docs
      |
      || File | Change | Status |
      ||:-----|:------:|-------:|
      || `a.kt` | **bold** | done |
      || `b.kt` | `x|y` | wip |
      |
      |> Quoted line
      |> continues here.
      |
      |```kotlin
      |fun main() { println("hi") }
      |```
      |
      |See https://example.com/docs and [the guide](https://example.com/guide).
    """.trimMargin()

    val blocks = MarkdownParser.parse(src)
    assertEquals(2, blocks.filterIsInstance<MdBlock.Heading>().size)
    assertTrue(blocks.filterIsInstance<MdBlock.ListBlock>().any { it.ordered })
    val tasks = blocks.filterIsInstance<MdBlock.ListBlock>().first { it.items.any { item -> item.task != null } }
    assertEquals(3, tasks.items.size)
    assertTrue(tasks.items[1].blocks.any { it is MdBlock.ListBlock })
    assertEquals(2, blocks.filterIsInstance<MdBlock.Table>().single().rows.size)
    assertEquals("Quoted line continues here.", blocks.filterIsInstance<MdBlock.Quote>().single().flatten())
    val links = blocks.filterIsInstance<MdBlock.Paragraph>().last()
      .inlines.filterIsInstance<MdInline.Link>()
    assertEquals(2, links.size)
  }

  @Test
  fun escapedBackslashesDoNotCreateHardBreaks() {
    val paragraph = MarkdownParser.parse("one\\\\\ntwo").single() as MdBlock.Paragraph
    assertEquals("one\\ two", paragraph.flatten())
    assertTrue(paragraph.inlines.none { it is MdInline.Break })
    assertEquals("one\\ two", MarkdownParser.parse("one\\ \ntwo").single().flatten())
    assertEquals("one\\\ntwo", MarkdownParser.parse("one\\\\  \ntwo").single().flatten())
  }

  @Test
  fun codeSpansPreserveTrailingSpacesAndNormalizeNewlines() {
    val paragraph = MarkdownParser.parse("`one  \ntwo`").single() as MdBlock.Paragraph
    assertEquals(listOf(MdInline.Code("one   two")), paragraph.inlines)
    assertEquals("a\uFFFDb", MarkdownParser.parse("a\u0000b").single().flatten())
  }

  @Test
  fun fourSpaceIndentedUnderlineDoesNotCreateAHeading() {
    val blocks = MarkdownParser.parse("paragraph\n    ---")
    assertEquals("paragraph ---", blocks.single().flatten())
    assertTrue(blocks.single() is MdBlock.Paragraph)
    assertEquals("one two", MarkdownParser.parse("one\ntwo\n  ===").single().flatten())
  }

  @Test
  fun orderedListsOnlyInterruptParagraphsWhenStartingAtOne() {
    assertEquals("version 2. still prose", MarkdownParser.parse("version\n2. still prose").single().flatten())
    assertEquals(2, MarkdownParser.parse("paragraph\n1. item").size)
    assertEquals("paragraph +", MarkdownParser.parse("paragraph\n+").single().flatten())
  }

  @Test
  fun emptyListItemsAndMarkerChangesArePreserved() {
    val empty = MarkdownParser.parse("-\n- item").single() as MdBlock.ListBlock
    assertEquals(2, empty.items.size)
    assertTrue(empty.items.first().blocks.isEmpty())
    val lists = MarkdownParser.parse("- one\n+ two\n* three\n1. four\n2) five")
    assertEquals(5, lists.size)
    assertTrue(lists.all { it is MdBlock.ListBlock })
  }

  @Test
  fun listPaddingAndTabStopsPreserveCodeIndentation() {
    val spaces = MarkdownParser.parse("-     code\n      next").single() as MdBlock.ListBlock
    assertEquals(MdBlock.CodeFence("", "code\nnext"), spaces.items.single().blocks.single())
    val tabs = MarkdownParser.parse("-\titem\n\tcontinued").single() as MdBlock.ListBlock
    assertEquals("item continued", tabs.items.single().blocks.single().flatten())
    assertEquals(MdBlock.CodeFence("", "code"), MarkdownParser.parse(" \tcode").single())
  }

  @Test
  fun listLazyContinuationRequiresAnOpenParagraph() {
    val blocks = MarkdownParser.parse("- first\ncontinued\n\noutside")
    val list = blocks.first() as MdBlock.ListBlock
    assertEquals("first continued", list.items.single().blocks.single().flatten())
    assertEquals("outside", blocks.last().flatten())
    val heading = MarkdownParser.parse("- # heading\noutside")
    assertEquals(2, heading.size)
    assertEquals("outside", heading.last().flatten())
    assertEquals(2, MarkdownParser.parse("- item\n# outside").size)
  }

  @Test
  fun tableRowsNormalizeMissingAndExcessCells() {
    val table = MarkdownParser.parse("a|b\n---|---\n|one|\n|two|three|extra|").single() as MdBlock.Table
    assertEquals(listOf(listOf("one", ""), listOf("two", "three")), table.rows)
  }

  @Test
  fun tablePipesRespectEscapeParityAndUnmatchedBackticks() {
    val table = MarkdownParser.parse("a|b\n---|---\n`open|two\nslash\\\\|last").single() as MdBlock.Table
    assertEquals(listOf(listOf("`open", "two"), listOf("slash\\\\", "last")), table.rows)
    val escaped = MarkdownParser.parse("a|b\n---|---\n`a\\|b`|last").single() as MdBlock.Table
    assertEquals(listOf("`a|b`", "last"), escaped.rows.single())
  }

  @Test
  fun linkDestinationsDecodeEntitiesAndOnlyPunctuationEscapes() {
    val link = MarkdownParser.parseInlines("[x](https://example.com/a\\q?a=1&amp;b=2)")
      .filterIsInstance<MdInline.Link>().single()
    assertEquals("https://example.com/a\\q?a=1&b=2", link.url)
    val escaped = MarkdownParser.parseInlines("[x](https://example.com/a\\(b\\))")
      .filterIsInstance<MdInline.Link>().single()
    assertEquals("https://example.com/a(b)", escaped.url)
    assertEquals("a&#35;", (MarkdownParser.parseInlines("[x](a&amp;#35;)").single() as MdInline.Link).url)
  }

  @Test
  fun linkLabelsCanContainCodeWithClosingBrackets() {
    val link = MarkdownParser.parseInlines("[use `]` here](https://example.com)").single() as MdInline.Link
    assertEquals("use ] here", link.label.flatten())
    assertTrue(link.label.any { it is MdInline.Code })
  }

  @Test
  fun malformedLinkTitlesAndUnbalancedDestinationsStayLiteral() {
    for (source in listOf("[x](<dest>\"title\")", "[x](a(b title)", "[x](<a<b>)")) {
      assertTrue(source, MarkdownParser.parseInlines(source).none { it is MdInline.Link })
    }
  }

  @Test
  fun strikethroughDoesNotCloseInsideCodeOrLinkDestinations() {
    val strike = MarkdownParser.parseInlines("~~use `~~` here~~").single() as MdInline.Strikethrough
    assertEquals("use ~~ here", strike.children.flatten())
    val linked = MarkdownParser.parseInlines("~~[link](https://example.com/~~) end~~").single() as MdInline.Strikethrough
    assertEquals("link end", linked.children.flatten())
    assertTrue(MarkdownParser.parseInlines("~~ spaced~~").all { it is MdInline.Text })
  }

  @Test
  fun excessiveNestingDegradesToLiteralTextWithoutStackOverflow() {
    val quotes = MarkdownParser.parse("> ".repeat(1500) + "deep")
    assertTrue(quotes.isNotEmpty())
    assertTrue(quotes.single().flatten().endsWith("deep"))
    val links = "[".repeat(200) + "deep" + "](relative)".repeat(200)
    assertTrue(MarkdownParser.parseInlines(links).flatten().contains("deep"))
  }

  @Test
  fun codeSpansKeepBackslashesAtLineEnd() {
    val paragraph = MarkdownParser.parse("`one\\\ntwo`").single() as MdBlock.Paragraph
    assertEquals(listOf(MdInline.Code("one\\ two")), paragraph.inlines)
  }

  @Test
  fun closingHashesCanMakeAnEmptyAtxHeading() {
    val heading = MarkdownParser.parse("## ###").single() as MdBlock.Heading
    assertEquals(2, heading.level)
    assertTrue(heading.inlines.isEmpty())
    assertEquals("literal#", MarkdownParser.parse("## literal#").single().flatten())
  }

  @Test
  fun entitiesPreserveNonBreakingSpacesAndCaseAndReplaceInvalidScalars() {
    assertEquals("a\u00A0b & &Amp;", MarkdownParser.parseInlines("a&nbsp;b &AMP; &Amp;").flatten())
    assertEquals("\uFFFD \uFFFD \uFFFD 😀", MarkdownParser.parseInlines("&#0; &#xD800; &#x110000; &#x1F600;").flatten())
    assertEquals(listOf(MdInline.Code("&nbsp;")), MarkdownParser.parseInlines("`&nbsp;`"))
  }

  @Test
  fun linksCanWrapAcrossParagraphLines() {
    val paragraph = MarkdownParser.parse("[a\nlabel](\nhttps://example.com\n\"title\")").single() as MdBlock.Paragraph
    val link = paragraph.inlines.single() as MdInline.Link
    assertEquals("a label", link.label.flatten())
    assertEquals("https://example.com", link.url)
  }

  @Test
  fun blankAndBomInputsAreSafe() {
    assertTrue(MarkdownParser.parse("").isEmpty())
    assertTrue(MarkdownParser.parse("\uFEFF").isEmpty())
    assertNull(MarkdownParser.parseInlines("plain").singleOrNull() as? MdInline.Code)
  }
}

/** Flattened text of a block, including hard breaks as newlines. */
private fun MdBlock.flatten(): String = when (this) {
  is MdBlock.Paragraph -> inlines.flatten()
  is MdBlock.Heading -> inlines.flatten()
  is MdBlock.ListBlock -> items.joinToString(" ") { it.blocks.joinToString(" ") { block -> block.flatten() } }
  is MdBlock.Quote -> blocks.joinToString(" ") { it.flatten() }
  else -> ""
}

private fun List<MdInline>.flatten(): String = joinToString("") { node ->
  when (node) {
    is MdInline.Text -> node.value
    is MdInline.Code -> node.value
    is MdInline.Strong -> node.children.flatten()
    is MdInline.Emphasis -> node.children.flatten()
    is MdInline.Strikethrough -> node.children.flatten()
    is MdInline.Link -> node.label.flatten()
    is MdInline.ImageRef -> "![${node.alt}](${node.url})"
    is MdInline.Break -> "\n"
  }
}
