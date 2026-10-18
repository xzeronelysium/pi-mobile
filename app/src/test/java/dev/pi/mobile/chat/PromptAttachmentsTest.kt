package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment prompt pipeline: staged files become `<file …>…</file>` blocks appended to the
 * user's text. Text is inlined; image/binary stay real tool-accessible paths. What the model
 * receives and what the transcript renders come from this one string.
 */
class PromptAttachmentsTest {
  private fun staged(
    id: String,
    name: String,
    path: String,
    kind: AttachmentKind,
    mime: String,
    size: Long,
    text: String? = null,
  ) = PromptAttachments.Staged(
    attachment = PendingAttachment(id = id, displayName = name, mimeType = mime, kind = kind, absolutePath = path, sizeBytes = size),
    textContent = text,
  )

  @Test
  fun attributeValuesAreEscaped() {
    assertEquals("a&amp;b&quot;c&lt;d&gt;e&apos;f", PromptAttachments.escapeAttr("a&b\"c<d>e'f"))
  }

  @Test
  fun theUsersOwnTextComesFirstThenOneBlockPerFile() {
    val prompt = PromptAttachments.build(
      "summarize these",
      listOf(
        staged("a1", "notes.txt", "/workspace/.pi-attachments/notes.txt", AttachmentKind.TEXT, "text/plain", 11, "hello body"),
        staged("a2", "blob.bin", "/workspace/.pi-attachments/blob.bin", AttachmentKind.BINARY, "application/octet-stream", 40),
      ),
    )
    val lines = prompt.split("\n\n")
    assertEquals("summarize these", lines[0])
    assertEquals("<file name=\"notes.txt\" path=\"/workspace/.pi-attachments/notes.txt\" kind=\"text\" mime=\"text/plain\">\nhello body\n</file>", lines[1])
    assertEquals(
      "<file name=\"blob.bin\" path=\"/workspace/.pi-attachments/blob.bin\" kind=\"binary\" mime=\"application/octet-stream\">\n" +
        "Binary attachment at tool-accessible path (no automatic extraction).\n</file>",
      lines[2],
    )
  }

  @Test
  fun anAttachmentOnlySendIsJustTheBlocksWithNoLeadingBlank() {
    val prompt = PromptAttachments.build(
      "",
      listOf(staged("a1", "shot.png", "/workspace/.pi-attachments/shot.png", AttachmentKind.IMAGE, "image/png", 900)),
    )
    assertTrue(prompt.startsWith("<file name=\"shot.png\""))
    assertTrue(prompt.contains("Image attached at tool-accessible path"))
    assertFalse(prompt.startsWith("\n"))
  }

  @Test
  fun nothingToSendBuildsAnEmptyPrompt() {
    assertEquals("", PromptAttachments.build("", emptyList()))
  }

  @Test
  fun aTextFileOverTheInlineCapKeepsItsPathAndSaysWhy() {
    val prompt = PromptAttachments.build(
      "see this",
      listOf(
        staged(
          "a1", "big.log", "/workspace/.pi-attachments/big.log", AttachmentKind.TEXT, "text/plain",
          PromptAttachments.MAX_TEXT_INLINE + 1L, "ignored",
        ),
      ),
    )
    assertTrue(prompt.contains("Text file too large to inline"))
    assertFalse(prompt.contains("ignored"))
  }

  @Test
  fun aTextFileThatCouldNotBeReadIsAPathReferenceNotFabricatedContent() {
    val prompt = PromptAttachments.build(
      "see this",
      listOf(staged("a1", "notes.txt", "/workspace/.pi-attachments/notes.txt", AttachmentKind.TEXT, "text/plain", 11, text = null)),
    )
    assertTrue(prompt.contains("Text file could not be read"))
  }

  @Test
  fun theAggregateInlineBudgetCapsTheSecondHugeTextFile() {
    val big = "x".repeat(PromptAttachments.MAX_TEXT_INLINE)
    val prompt = PromptAttachments.build(
      "two files",
      listOf(
        staged("a1", "one.txt", "/workspace/.pi-attachments/one.txt", AttachmentKind.TEXT, "text/plain", big.length.toLong(), big),
        staged("a2", "two.txt", "/workspace/.pi-attachments/two.txt", AttachmentKind.TEXT, "text/plain", big.length.toLong(), big),
      ),
    )
    assertTrue(prompt.contains("Inline text budget exhausted"))
    assertEquals(1, Regex("Inline text budget exhausted").findAll(prompt).count())
  }

  @Test
  fun aBomIsStrippedFromInlinedText() {
    val prompt = PromptAttachments.build(
      "",
      listOf(staged("a1", "b.txt", "/workspace/.pi-attachments/b.txt", AttachmentKind.TEXT, "text/plain", 5, "\uFEFFbody")),
    )
    assertTrue(prompt.contains(">\nbody\n</file>"))
    assertFalse(prompt.contains("\uFEFF"))
  }

  @Test
  fun theBuiltPromptRoundTripsThroughTheTranscriptPresenter() {
    val prompt = PromptAttachments.build(
      "look at this",
      listOf(
        staged("a1", "notes.txt", "/workspace/.pi-attachments/notes.txt", AttachmentKind.TEXT, "text/plain", 11, "hello body"),
        staged("a2", "photo.jpg", "/workspace/.pi-attachments/photo.jpg", AttachmentKind.IMAGE, "image/jpeg", 2048),
      ),
    )
    val presentation = UserMessagePresenter.present(prompt)
    assertEquals("look at this", presentation.body)
    assertEquals(listOf("notes.txt", "photo.jpg"), presentation.files.map { it.name })
    val notes = presentation.files.first()
    assertEquals("text", notes.kind)
    assertEquals("text/plain", notes.mimeType)
    assertTrue(notes.inlined)
    val photo = presentation.files.last()
    assertEquals("image", photo.kind)
    assertFalse(photo.inlined)
  }

  /**
   * Why a failed send must restore the user's own draft and never the wrapped prompt: re-wrapping
   * an already-wrapped prompt appends a second block per file, so the model would receive each
   * attachment twice. The snapshot the composer keeps is the draft, and this is the guard against
   * changing that.
   */
  @Test
  fun wrappingAnAlreadyWrappedPromptDuplicatesEveryFileBlock() {
    val staged = listOf(
      staged("a1", "notes.txt", "/workspace/.pi-attachments/notes.txt", AttachmentKind.TEXT, "text/plain", 11, "hello body"),
    )
    val once = PromptAttachments.build("look", staged)
    assertEquals(1, Regex("<file ").findAll(once).count())
    val twice = PromptAttachments.build(once, staged)
    assertEquals(2, Regex("<file ").findAll(twice).count())
  }
}
