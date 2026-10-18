package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class AttachmentStagingTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun stagingRoot(): File = tmp.newFolder("workspace").also {
    File(it, AttachmentStaging.STAGING_DIR_NAME).mkdirs()
  }.let { File(it, AttachmentStaging.STAGING_DIR_NAME) }

  @Test
  fun stagesUniqueSanitizedFileUnderRoot() {
    val root = stagingRoot()
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "../evil/report.txt",
      mimeType = "text/plain",
      sizeHint = 5L,
      openStream = { ByteArrayInputStream("hello".toByteArray()) },
      existingCount = 0,
      existingTotalBytes = 0L,
    )
    assertTrue(result.isSuccess)
    val staged = result.getOrThrow()
    assertEquals(AttachmentKind.TEXT, staged.kind)
    assertEquals("report.txt", staged.displayName)
    assertTrue(staged.absolutePath.startsWith(root.canonicalPath))
    assertFalse(staged.absolutePath.contains(".."))
    assertEquals("hello", File(staged.absolutePath).readText())
    assertTrue(File(staged.absolutePath).parentFile!!.name.length >= 8) // unique dir
  }

  @Test
  fun rejectsOverCountLimit() {
    val root = stagingRoot()
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "a.txt",
      mimeType = "text/plain",
      sizeHint = 1L,
      openStream = { ByteArrayInputStream("x".toByteArray()) },
      existingCount = AttachmentLimits.MAX_COUNT,
      existingTotalBytes = 0L,
    )
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("Too many"))
  }

  @Test
  fun rejectsOverPerFileByteLimit() {
    val root = stagingRoot()
    val over = AttachmentLimits.MAX_BYTES_PER_FILE + 1
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "big.bin",
      mimeType = "application/octet-stream",
      sizeHint = over,
      openStream = { ByteArrayInputStream(ByteArray(0)) },
      existingCount = 0,
      existingTotalBytes = 0L,
    )
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("too large") ||
      result.exceptionOrNull()!!.message!!.contains("limit"))
  }

  @Test
  fun rejectsWhenStreamExceedsLimitEvenIfHintWasSmall() {
    val root = stagingRoot()
    val chunk = ByteArray(1024) { 1 }
    var remaining = (AttachmentLimits.MAX_BYTES_PER_FILE + 2048).toInt()
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "stream.bin",
      mimeType = "application/octet-stream",
      sizeHint = 100L,
      openStream = {
        object : java.io.InputStream() {
          override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 1
          }
          override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len, remaining, chunk.size)
            System.arraycopy(chunk, 0, b, off, n)
            remaining -= n
            return n
          }
        }
      },
      existingCount = 0,
      existingTotalBytes = 0L,
    )
    assertTrue(result.isFailure)
    assertEquals(0, root.listFiles()?.sumOf { it.walkTopDown().filter { f -> f.isFile }.count() } ?: 0)
  }

  @Test
  fun rejectsTotalBytesBudget() {
    val root = stagingRoot()
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "a.txt",
      mimeType = "text/plain",
      sizeHint = 10L,
      openStream = { ByteArrayInputStream("abcdefghij".toByteArray()) },
      existingCount = 1,
      existingTotalBytes = AttachmentLimits.MAX_TOTAL_BYTES - 5,
    )
    assertTrue(result.isFailure)
  }

  @Test
  fun rejectsWhenStreamTotalExceedsEvenIfPerFileAndHintOk() {
    // existingActual total near 24MiB; stream writes enough to cross with real bytes.
    val root = stagingRoot()
    val existing = AttachmentLimits.MAX_TOTAL_BYTES - 100
    val payload = ByteArray(200) { 2 }
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "more.bin",
      mimeType = "application/octet-stream",
      sizeHint = 50L, // forged small hint
      openStream = { ByteArrayInputStream(payload) },
      existingCount = 3,
      existingTotalBytes = existing,
    )
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()!!.message!!.contains("Total") ||
        result.exceptionOrNull()!!.message!!.contains("limit"),
    )
  }

  @Test
  fun imagesGetImageKind() {
    val root = stagingRoot()
    val png = byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
      0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1,
      8, 2, 0, 0, 0
    )
    val result = AttachmentStaging.stage(
      stagingRoot = root,
      displayName = "pic.png",
      mimeType = "image/png",
      sizeHint = png.size.toLong(),
      openStream = { ByteArrayInputStream(png) },
      existingCount = 0,
      existingTotalBytes = 0L,
    )
    assertEquals(AttachmentKind.IMAGE, result.getOrThrow().kind)
    assertEquals("image/png", result.getOrThrow().mimeType)
  }
}
