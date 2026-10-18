package dev.pi.mobile.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownContentTest {
  @Test fun linksUseNativeAnnotationsOnlyOverTheirLabels() {
    val opened = mutableListOf<String>()
    val text = inlineAnnotated(
      listOf(
        MdInline.Text("Before "),
        MdInline.Link(listOf(MdInline.Strong(listOf(MdInline.Text("site")))), "https://example.com"),
        MdInline.Text(" after"),
      ),
      opened::add,
    )

    assertEquals("Before site after", text.text)
    val link = text.getLinkAnnotations(0, text.length).single()
    assertEquals(7, link.start)
    assertEquals(11, link.end)
    val annotation = link.item as LinkAnnotation.Url
    assertEquals("https://example.com", annotation.url)
    assertEquals(TextDecoration.Underline, annotation.styles?.style?.textDecoration)
    assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 7 && it.end == 11 })
    assertTrue(text.getLinkAnnotations(0, 7).isEmpty())
    assertTrue(text.getLinkAnnotations(11, text.length).isEmpty())
    annotation.linkInteractionListener!!.onClick(annotation)
    assertEquals(listOf("https://example.com"), opened)
  }

  @Test fun unsafeDestinationsRemainReadableWithoutLinkActions() {
    listOf("javascript:alert(1)", "file:///etc/passwd", "intent://example", "data:text/html,hello").forEach { url ->
      val text = inlineAnnotated(
        listOf(MdInline.Link(listOf(MdInline.Text("label")), url)),
      ) { error("Unsafe URL opened: $it") }
      assertEquals("label", text.text)
      assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
  }

  @Test fun emptyLabelsFallBackToTheDestination() {
    val url = "https://example.com"
    val safe = inlineAnnotated(listOf(MdInline.Link(emptyList(), url))) {}
    assertEquals(url, safe.text)
    assertEquals(url.length, safe.getLinkAnnotations(0, safe.length).single().end)
    val unsafe = inlineAnnotated(listOf(MdInline.Link(emptyList(), "file:///tmp/example"))) {}
    assertEquals("file:///tmp/example", unsafe.text)
    assertTrue(unsafe.getLinkAnnotations(0, unsafe.length).isEmpty())
  }

  @Test fun multipleLinksKeepSeparateDestinationsAndHardBreaks() {
    val opened = mutableListOf<String>()
    val text = inlineAnnotated(
      listOf(
        MdInline.Link(listOf(MdInline.Text("one")), "https://one.example"),
        MdInline.Break(),
        MdInline.Link(listOf(MdInline.Text("two")), "mailto:two@example.com"),
      ),
      opened::add,
    )
    assertEquals("one\ntwo", text.text)
    val links = text.getLinkAnnotations(0, text.length)
    assertEquals(listOf(0, 4), links.map { it.start })
    links.forEach { it.item.linkInteractionListener!!.onClick(it.item) }
    assertEquals(listOf("https://one.example", "mailto:two@example.com"), opened)
  }

  @Test fun expandedCodeStaysExpandedAcrossStreamingChunksAndFenceClosure() {
    val expanded = MarkdownCodeUiState("first\nsecond", "kotlin", expanded = true)
      .updated("first\nsecond\nthi", "kotlin")
      .updated("first\nsecond\nthird", "kotlin")
    assertTrue(expanded.expanded)
    assertTrue(expanded.updated(expanded.code, expanded.language).expanded)

    // Use actual parser output: completing a fence consumes the EOF empty source line.
    val source = "```kotlin\nfirst\nsecond\n"
    val open = MarkdownParser.parse(source).single() as MdBlock.CodeFence
    val closed = MarkdownParser.parse(source + "```").single() as MdBlock.CodeFence
    assertTrue(
      MarkdownCodeUiState(open.code, open.language, expanded = true)
        .updated(closed.code, closed.language).expanded,
    )
  }

  @Test fun collapsedCodeDoesNotExpandWhenTheStreamGrows() {
    assertFalse(MarkdownCodeUiState("first", "").updated("first\nsecond", "").expanded)
  }

  @Test fun replacementOrTruncationResetsExpansionWithoutResurrectingIt() {
    val state = MarkdownCodeUiState("first\nsecond", "kotlin", expanded = true)
    assertFalse(state.updated("replacement", "kotlin").expanded)
    assertFalse(state.updated(state.code, "python").expanded)
    val truncated = state.updated("first", "kotlin")
    assertFalse(truncated.expanded)
    assertFalse(truncated.updated("first\nsecond", "kotlin").expanded)
  }

  @Test fun codeBlocksHaveIndependentExpansionState() {
    val first = MarkdownCodeUiState("same", "", expanded = true)
    val second = MarkdownCodeUiState("same", "")
    assertTrue(first.updated("same\nmore", "").expanded)
    assertFalse(second.updated("same\nmore", "").expanded)
    assertFalse(first.copy(expanded = false).updated("same\nmore", "").expanded)
  }
}
