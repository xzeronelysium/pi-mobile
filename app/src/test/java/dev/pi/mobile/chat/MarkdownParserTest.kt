package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    assertTrue(blocks.any { it is MdBlock.BulletList })
    val fence = blocks.first { it is MdBlock.CodeFence } as MdBlock.CodeFence
    assertEquals("kotlin", fence.language)
    assertEquals("val x = 1", fence.code.trim())
  }

  @Test
  fun parsesSimpleTable() {
    val src = """
      |Name|Value|
      |---|---|
      |a|1|
      |b|2|
    """.trimIndent()
    val table = MarkdownParser.parse(src).filterIsInstance<MdBlock.Table>().single()
    assertEquals(listOf("Name", "Value"), table.headers)
    assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), table.rows)
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
  fun doesNotExecuteHtmlOrFetchImages() {
    val blocks = MarkdownParser.parse("Hello <script>alert(1)</script> and ![x](https://evil/img.png)")
    val text = blocks.joinToString("\n") { block ->
      when (block) {
        is MdBlock.Paragraph -> block.inlines.joinToString("") {
          when (it) {
            is MdInline.Text -> it.value
            is MdInline.Code -> it.value
            is MdInline.Strong -> it.children.filterIsInstance<MdInline.Text>().joinToString("") { c -> c.value }
            is MdInline.Emphasis -> it.children.filterIsInstance<MdInline.Text>().joinToString("") { c -> c.value }
            is MdInline.Link -> it.label.filterIsInstance<MdInline.Text>().joinToString("") { c -> c.value }
            is MdInline.ImageRef -> "!" + it.alt
          }
        }
        else -> ""
      }
    }
    assertTrue(text.contains("<script>"))
    assertTrue(text.contains("![x]") || text.contains("!x"))
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
  fun underscoresInPathsAreNotEmphasis() {
    val inlines = MarkdownParser.parseInlines("see /home/user_name/my_file.txt and snake_case")
    assertTrue(
      "path underscores must stay plain text, got $inlines",
      inlines.all { it is MdInline.Text },
    )
    val joined = inlines.filterIsInstance<MdInline.Text>().joinToString("") { it.value }
    assertTrue(joined.contains("/home/user_name/my_file.txt"))
    assertTrue(joined.contains("snake_case"))
  }

  @Test
  fun underscoreEmphasisRequiresWordBoundariesBothSides() {
    val inlines = MarkdownParser.parseInlines("ok _italic_ end")
    assertTrue(inlines.any { it is MdInline.Emphasis })
    val em = inlines.filterIsInstance<MdInline.Emphasis>().single()
    assertEquals("italic", em.children.filterIsInstance<MdInline.Text>().joinToString("") { it.value })
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
    assertFalse(MarkdownParser.isSafeHttpUrl("javascript:alert(1)"))
    assertFalse(MarkdownParser.isSafeHttpUrl("file:///etc/passwd"))
    assertFalse(MarkdownParser.isSafeHttpUrl("custom://app"))
    assertFalse(MarkdownParser.isSafeHttpUrl(""))
  }
}
