package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentNameSanitizerTest {
  @Test
  fun stripsPathSegmentsAndTraversal() {
    assertEquals("secret.txt", AttachmentNameSanitizer.sanitize("../../etc/secret.txt"))
    assertEquals("notes.md", AttachmentNameSanitizer.sanitize("foo/bar/notes.md"))
    assertEquals("notes.md", AttachmentNameSanitizer.sanitize("foo\\bar\\notes.md"))
  }

  @Test
  fun rejectsBlankAndControlCharacters() {
    assertEquals("attachment", AttachmentNameSanitizer.sanitize("   "))
    assertEquals("file.txt", AttachmentNameSanitizer.sanitize("file\u0000.txt"))
    assertEquals("hi.txt", AttachmentNameSanitizer.sanitize("hi\n.txt"))
  }

  @Test
  fun keepsSafeUnicodeAndExtensions() {
    val name = AttachmentNameSanitizer.sanitize("résumé (1).PDF")
    assertTrue(name.endsWith(".PDF") || name.endsWith(".pdf") || name.contains("sum"))
    assertFalse(name.contains("/"))
    assertFalse(name.contains("\\"))
  }

  @Test
  fun capsExcessiveLength() {
    val long = "a".repeat(300) + ".txt"
    val out = AttachmentNameSanitizer.sanitize(long)
    assertTrue(out.length <= AttachmentNameSanitizer.MAX_NAME_LENGTH)
    assertTrue(out.endsWith(".txt"))
  }
}
