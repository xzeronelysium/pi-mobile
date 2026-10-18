package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentClassifierTest {
  @Test
  fun classifiesSupportedImagesByMime() {
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/png", "x.png", byteArrayOf()))
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/jpeg", "x.jpg", byteArrayOf()))
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/webp", "x.webp", byteArrayOf()))
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/gif", "x.gif", byteArrayOf()))
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/bmp", "x.bmp", byteArrayOf()))
  }

  @Test
  fun sniffsPngMagicWhenMimeMissing() {
    val png = byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
      0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52
    )
    assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify(null, "shot", png))
  }

  @Test
  fun classifiesTextMimeAndSourceExtensions() {
    assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("text/plain", "a.txt", "hello".toByteArray()))
    assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("application/json", "a.json", "{}".toByteArray()))
    assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify(null, "Main.kt", "fun x(){}".toByteArray()))
    assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify(null, "readme.md", "# hi".toByteArray()))
  }

  @Test
  fun binaryIsNotPretendedAsText() {
    // PDF magic — must stay binary (no fake PDF extraction)
    val pdf = "%PDF-1.4".toByteArray()
    assertEquals(AttachmentKind.BINARY, AttachmentClassifier.classify("application/pdf", "doc.pdf", pdf))
    assertEquals(AttachmentKind.BINARY, AttachmentClassifier.classify("application/octet-stream", "blob.bin", byteArrayOf(0, 1, 2, 3, 0)))
  }

  @Test
  fun utf8TextBodyCanUpgradeUnknownMime() {
    assertEquals(
      AttachmentKind.TEXT,
      AttachmentClassifier.classify("application/octet-stream", "notes", "plain utf-8 notes\n".toByteArray())
    )
  }

  @Test
  fun nullBytesForceBinaryEvenWithTextMime() {
    assertEquals(
      AttachmentKind.BINARY,
      AttachmentClassifier.classify("text/plain", "weird.txt", byteArrayOf(0x41, 0x00, 0x42))
    )
  }
}
