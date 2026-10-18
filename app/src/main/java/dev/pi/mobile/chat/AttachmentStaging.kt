package dev.pi.mobile.chat

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Copies a content-stream into an app-owned unique file under the workspace
 * staging directory. Never trusts the caller path; never writes outside [stagingRoot].
 *
 * Pure and injectable: the caller supplies [stage]'s [openStream] content source, so JVM unit
 * tests need no Android framework, and the picker can feed any SAF `InputStream`.
 */
object AttachmentStaging {
  const val STAGING_DIR_NAME = ".pi-attachments"

  fun stage(
    stagingRoot: File,
    displayName: String?,
    mimeType: String?,
    sizeHint: Long?,
    openStream: () -> InputStream,
    existingCount: Int,
    existingTotalBytes: Long,
  ): Result<PendingAttachment> {
    if (existingCount >= AttachmentLimits.MAX_COUNT) {
      return Result.failure(IllegalArgumentException("Too many attachments (max ${AttachmentLimits.MAX_COUNT})"))
    }
    if (sizeHint != null && sizeHint > AttachmentLimits.MAX_BYTES_PER_FILE) {
      return Result.failure(IllegalArgumentException("File too large (limit ${AttachmentLimits.MAX_BYTES_PER_FILE} bytes)"))
    }
    if (sizeHint != null && existingTotalBytes + sizeHint > AttachmentLimits.MAX_TOTAL_BYTES) {
      return Result.failure(IllegalArgumentException("Total attachment size exceeds limit"))
    }

    val safeName = AttachmentNameSanitizer.sanitize(displayName)
    val root = stagingRoot.canonicalFile
    root.mkdirs()
    val uniqueDir = File(root, UUID.randomUUID().toString().replace("-", "").take(16))
    if (!uniqueDir.mkdirs()) {
      return Result.failure(IllegalStateException("Could not create staging directory"))
    }
    val target = File(uniqueDir, safeName).canonicalFile
    if (!target.path.startsWith(root.path + File.separator) && target.path != root.path) {
      uniqueDir.deleteRecursively()
      return Result.failure(IllegalStateException("Path traversal blocked"))
    }

    return try {
      var written = 0L
      openStream().use { input ->
        target.outputStream().use { output ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            written += n
            if (written > AttachmentLimits.MAX_BYTES_PER_FILE) {
              throw IllegalArgumentException("File too large (limit ${AttachmentLimits.MAX_BYTES_PER_FILE} bytes)")
            }
            if (existingTotalBytes + written > AttachmentLimits.MAX_TOTAL_BYTES) {
              throw IllegalArgumentException("Total attachment size exceeds limit")
            }
            output.write(buf, 0, n)
          }
        }
      }
      // Re-read head for classification (file may be large; only sniff first 4 KiB + full if small).
      val sniff = readPrefix(target, 4100)
      val kindBytes = if (target.length() <= 512 * 1024L) target.readBytes() else sniff
      val kind = AttachmentClassifier.classify(mimeType, safeName, kindBytes)
      val resolvedMime = when {
        !mimeType.isNullOrBlank() && mimeType != "application/octet-stream" -> mimeType.substringBefore(';').trim()
        kind == AttachmentKind.IMAGE -> AttachmentClassifier.sniffImageMime(sniff) ?: "image/png"
        kind == AttachmentKind.TEXT -> "text/plain"
        else -> mimeType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/octet-stream"
      }
      Result.success(
        PendingAttachment(
          id = UUID.randomUUID().toString(),
          displayName = safeName,
          mimeType = resolvedMime,
          kind = kind,
          absolutePath = target.absolutePath,
          sizeBytes = target.length(),
        )
      )
    } catch (e: Exception) {
      uniqueDir.deleteRecursively()
      Result.failure(e)
    }
  }

  private fun readPrefix(file: File, max: Int): ByteArray {
    file.inputStream().use { input ->
      val buf = ByteArray(max)
      var off = 0
      while (off < max) {
        val n = input.read(buf, off, max - off)
        if (n < 0) break
        off += n
      }
      return if (off == max) buf else buf.copyOf(off)
    }
  }
}
