package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
  @Test
  fun kotlinKeywordsStringsAndCommentsAreClassified() {
    val code = "// note\nfun main() { val x = \"hi\" }"
    val tokens = CodeHighlight.tokenize(code, "kotlin")

    assertTrue(tokens.any { it.text(code) == "// note" && it.kind == CodeTokenKind.COMMENT })
    assertTrue(tokens.any { it.text(code) == "fun" && it.kind == CodeTokenKind.KEYWORD })
    assertTrue(tokens.any { it.text(code) == "val" && it.kind == CodeTokenKind.KEYWORD })
    assertTrue(tokens.any { it.text(code) == "\"hi\"" && it.kind == CodeTokenKind.STRING })
  }

  @Test
  fun numbersAndAliasesAreRecognized() {
    val code = "const x = 0x2A"
    val tokens = CodeHighlight.tokenize(code, "js")
    assertTrue(tokens.any { it.text(code) == "const" && it.kind == CodeTokenKind.KEYWORD })
    assertTrue(tokens.any { it.text(code) == "0x2A" && it.kind == CodeTokenKind.NUMBER })
  }

  @Test
  fun hashCommentsAreCommentsNotStrings() {
    val code = "x = 1  # note\nprint(x)"
    val tokens = CodeHighlight.tokenize(code, "python")
    assertTrue(tokens.any { it.text(code) == "# note" && it.kind == CodeTokenKind.COMMENT })
    assertTrue(tokens.none { it.kind == CodeTokenKind.STRING })
  }

  @Test
  fun cPreprocessorLinesAreMeta() {
    val code = "#include <stdio.h>\nint main(void) {}"
    val tokens = CodeHighlight.tokenize(code, "c")
    assertTrue(tokens.any { it.text(code) == "#include <stdio.h>" && it.kind == CodeTokenKind.META })
    assertTrue(tokens.any { it.text(code) == "int" && it.kind == CodeTokenKind.KEYWORD })
  }

  @Test
  fun diffLinesReportAddedAndRemoved() {
    val code = "--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new"
    val tokens = CodeHighlight.tokenize(code, "diff")
    assertTrue(tokens.any { it.text(code) == "--- a/file" && it.kind == CodeTokenKind.META })
    assertTrue(tokens.any { it.text(code) == "-old" && it.kind == CodeTokenKind.REMOVED })
    assertTrue(tokens.any { it.text(code) == "+new" && it.kind == CodeTokenKind.ADDED })
  }

  @Test
  fun unknownLanguagesProduceNoTokensAndNeverChangeText() {
    assertTrue(CodeHighlight.tokenize("some prose", "made-up-language").isEmpty())
    assertTrue(CodeHighlight.tokenize("", "kotlin").isEmpty())
  }

  @Test
  fun unterminatedStringsStopAtTheLineEnd() {
    val code = "val x = \"unterminated\nval y = 1"
    val string = CodeHighlight.tokenize(code, "kotlin").single { it.kind == CodeTokenKind.STRING }
    assertEquals("\"unterminated", string.text(code))
  }
}

private fun CodeToken.text(source: String): String = source.substring(start, end)
