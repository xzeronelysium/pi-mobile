package dev.pi.mobile.chat

import java.io.File

/**
 * The filesystem half of the file drawer. Every path is resolved against the bound workspace root
 * and confined to it with `canonicalFile`, so `..`, an absolute path, or a symlink pointing out of
 * the workspace is refused before any read.
 *
 * This object is deliberately JVM-only (no Android types) so the path and truncation rules are
 * testable off-device. The panel never calls it on the main thread.
 */
object WorkspaceFileBrowser {
  data class Listing(val entries: List<WorkspaceEntry>, val truncated: Boolean)

  data class TextPreview(val text: String, val truncated: Boolean)

  /**
   * Resolve [relativePath] inside [root], or `null` when it escapes the workspace. A blank path is
   * the root itself. The returned file may not exist; callers check.
   */
  fun resolve(root: File, relativePath: String?): File? {
    val rootCanonical = runCatching { root.canonicalFile }.getOrElse { return null }
    val raw = (relativePath ?: "").replace('\\', '/').trim()
    if (raw == "/") return rootCanonical
    // An absolute path is not a workspace-relative one; refuse it instead of reinterpreting it.
    if (raw.startsWith("/")) return null
    val cleaned = raw
    if (cleaned.isEmpty() || cleaned == ".") return rootCanonical
    val target = runCatching { File(rootCanonical, cleaned).canonicalFile }.getOrElse { return null }
    val inside = target.path == rootCanonical.path ||
      target.path.startsWith(rootCanonical.path + File.separator)
    return if (inside) target else null
  }

  /** List one directory, newest irrelevant: the reducer owns ordering. */
  fun list(
    root: File,
    relativePath: String,
    maxEntries: Int = WorkspaceFilesUi.MAX_ENTRIES,
  ): Result<Listing> = runCatching {
    val directory = resolve(root, relativePath)
      ?: throw IllegalArgumentException("That path is outside the workspace.")
    if (!directory.exists()) throw IllegalArgumentException("That folder no longer exists.")
    if (!directory.isDirectory) throw IllegalArgumentException("That path is not a folder.")
    val children = directory.listFiles()
      ?: throw IllegalStateException("This folder cannot be read.")
    val parent = WorkspaceFilesUi.normalizePath(relativePath)
    val entries = children.mapNotNull { child ->
      runCatching { entryFor(child, parent) }.getOrNull()
    }
    Listing(entries = entries.take(maxEntries), truncated = entries.size > maxEntries)
  }

  /**
   * Read a UTF-8 text preview, bounded by [WorkspaceFilesUi.MAX_TEXT_BYTES]. A NUL byte means the
   * file is binary even when its name claimed text, and is reported rather than rendered as noise.
   */
  fun readText(
    root: File,
    relativePath: String,
    maxBytes: Int = WorkspaceFilesUi.MAX_TEXT_BYTES,
  ): Result<TextPreview> = runCatching {
    val file = resolve(root, relativePath)
      ?: throw IllegalArgumentException("That path is outside the workspace.")
    if (!file.exists()) throw IllegalArgumentException("That file no longer exists.")
    if (file.isDirectory) throw IllegalArgumentException("That path is a folder.")
    val length = file.length()
    val limit = minOf(length, maxBytes.toLong()).toInt()
    val bytes = file.inputStream().use { input -> readAtMost(input, limit) }
    if (bytes.any { it == 0.toByte() }) {
      throw IllegalArgumentException("This file is binary, not text.")
    }
    TextPreview(text = String(bytes, Charsets.UTF_8), truncated = length > maxBytes)
  }

  private fun entryFor(file: File, parent: String): WorkspaceEntry {
    val directory = file.isDirectory
    return WorkspaceEntry(
      name = file.name,
      path = WorkspaceFilesUi.child(parent, file.name),
      directory = directory,
      sizeBytes = if (directory) 0L else file.length(),
      modifiedMs = file.lastModified(),
    )
  }

  private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
      val read = input.read(buffer, offset, limit - offset)
      if (read < 0) break
      offset += read
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
  }
}
