package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display projection of a raw user prompt. The stored message is machine-shaped — the model
 * receives it verbatim — and only the presentation layer splits the skill/file blocks out, so the
 * transcript can show a compact badge and real file cards without rewriting history.
 */
class UserMessagePresentationTest {
  private val skillBlock =
    "<skill name=\"pdf-tools\" location=\"/home/pi/skills/pdf-tools/SKILL.md\">\n" +
      "References are relative to /home/pi/skills/pdf-tools.\n\n" +
      "# PDF tools\n" +
      "Use pdftotext to extract text from a PDF.\n" +
      "</skill>"

  private val notesFile =
    "<file name=\"notes.txt\" path=\"/workspace/.pi-attachments/notes.txt\">\n" +
      "hello body\n" +
      "</file>"

  private val binaryFile =
    "<file name=\"blob.bin\" path=\"/workspace/.pi-attachments/blob.bin\" kind=\"binary\" " +
      "mime=\"application/octet-stream\">\n" +
      "Binary attachment at tool-accessible path (no automatic extraction).\n" +
      "</file>"

  private val imageFile =
    "<file name=\"shot.png\" path=\"/workspace/.pi-attachments/shot.png\" kind=\"image\"></file>"

  @Test
  fun plainPromptIsReturnedUntouched() {
    val raw = "  keep my   spacing\n\nand blank lines  "
    val p = UserMessagePresenter.present(raw)
    assertEquals(raw, p.body)
    assertTrue(p.skills.isEmpty())
    assertTrue(p.files.isEmpty())
    assertFalse(p.structured)
  }

  @Test
  fun slashSkillDraftWithoutExpansionStaysPlain() {
    val p = UserMessagePresenter.present("/skill:pdf-tools extract page 3")
    assertEquals("/skill:pdf-tools extract page 3", p.body)
    assertFalse(p.structured)
  }

  @Test
  fun expandedSkillBecomesBadgeAndKeepsUserArguments() {
    val p = UserMessagePresenter.present("$skillBlock\n\nextract page 3")
    assertEquals(listOf(SkillActivation("pdf-tools", "/home/pi/skills/pdf-tools/SKILL.md")), p.skills)
    assertEquals("extract page 3", p.body)
    assertTrue(p.files.isEmpty())
    // The internal instructions must not leak into the visible body.
    assertFalse(p.body.contains("pdftotext"))
  }

  @Test
  fun expandedSkillWithoutArgumentsHasEmptyBody() {
    val p = UserMessagePresenter.present(skillBlock)
    assertEquals("pdf-tools", p.skills.single().name)
    assertEquals("", p.body)
    assertTrue(p.structured)
  }

  @Test
  fun multipleSkillsInOneMessageAreAllCaptured() {
    val second = skillBlock.replace("pdf-tools", "git-tools")
    val p = UserMessagePresenter.present("$skillBlock\n\n$second\n\ndo both")
    assertEquals(listOf("pdf-tools", "git-tools"), p.skills.map { it.name })
    assertEquals("do both", p.body)
  }

  @Test
  fun inlinedTextAttachmentBecomesFileCardWithPromptBody() {
    val p = UserMessagePresenter.present("look at this\n\n$notesFile")
    assertEquals("look at this", p.body)
    val file = p.files.single()
    assertEquals("notes.txt", file.name)
    assertEquals("/workspace/.pi-attachments/notes.txt", file.path)
    assertEquals("file", file.kind)
    assertTrue(file.inlined)
    // File contents belong to the model, not to the transcript body.
    assertFalse(p.body.contains("hello body"))
  }

  @Test
  fun attachmentOnlyMessageHasEmptyBodyAndKeepsCards() {
    val p = UserMessagePresenter.present(notesFile)
    assertEquals("", p.body)
    assertEquals("notes.txt", p.files.single().name)
    assertTrue(p.structured)
  }

  @Test
  fun binaryAndImageAttachmentsKeepMetadataWithoutInlinedContent() {
    val p = UserMessagePresenter.present("$binaryFile\n\n$imageFile")
    val binary = p.files.first { it.name == "blob.bin" }
    assertEquals("binary", binary.kind)
    assertEquals("application/octet-stream", binary.mimeType)
    assertFalse(binary.inlined)
    assertFalse(p.body.contains("tool-accessible path"))

    val image = p.files.first { it.name == "shot.png" }
    assertEquals("image", image.kind)
    assertFalse(image.inlined)
  }

  @Test
  fun skillAndAttachmentsTogetherProduceBothBadgeAndCards() {
    val p = UserMessagePresenter.present("$skillBlock\n\nextract page 3\n\n$notesFile")
    assertEquals("pdf-tools", p.skills.single().name)
    assertEquals("extract page 3", p.body)
    assertEquals("notes.txt", p.files.single().name)
  }

  @Test
  fun malformedMarkersWithoutAClosingTagAreNotStructured() {
    val raw = "I typed <file name=\"x\" path=\"/tmp/x\"> by hand, and <skill stuff"
    val p = UserMessagePresenter.present(raw)
    assertEquals(raw, p.body)
    assertTrue(p.skills.isEmpty())
    assertTrue(p.files.isEmpty())
  }

  @Test
  fun anEscapedAttributeRoundTripsToTheRealName() {
    val tag = PromptAttachments.fileTag(
      name = "a\"b&c<d>.txt",
      path = "/workspace/.pi-attachments/x.txt",
      kind = "text",
      mime = null,
      body = "content",
    )
    val p = UserMessagePresenter.present(tag)
    val file = p.files.single()
    assertEquals("a\"b&c<d>.txt", file.name)
    assertTrue(file.inlined)
  }

  @Test
  fun aStoredUserEntryProjectsToACardWithoutChangingItsRawText() {
    val entry = ChatEntry.User(text = "look at this\n\n$notesFile")
    assertEquals("look at this\n\n$notesFile", entry.text)
    val presentation = UserMessagePresenter.present(entry.text)
    assertEquals("look at this", presentation.body)
    assertEquals("notes.txt", presentation.files.single().name)
  }

  @Test
  fun aPlainStoredUserEntryHasNoStructuredPresentation() {
    val entry = ChatEntry.User(text = "hello")
    val presentation = UserMessagePresenter.present(entry.text)
    assertNull(presentation.files.firstOrNull())
    assertEquals("hello", presentation.body)
  }
}
