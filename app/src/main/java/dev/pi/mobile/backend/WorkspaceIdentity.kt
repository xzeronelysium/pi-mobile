package dev.pi.mobile.backend

import java.io.File
import java.security.MessageDigest

/**
 * The identity of one bound workspace, and the app-private store that belongs to it.
 *
 * A workspace is a directory, so its identity is the canonical path: the same folder bound twice —
 * as app-private, app-external, a real shared path or a SAF mirror — resolves to the same key, and
 * two different folders can never share one. The durable conversation database is named from that
 * key, which is what makes every workspace own its sessions instead of reading one app-wide list.
 *
 * Paths are canonicalized before hashing so a symlink, `.`/`..` segment or trailing slash cannot
 * make the same workspace look like two. Hashing keeps the file name bounded and legal whatever the
 * user's folder is called; nothing about the path is stored in the name.
 */
object WorkspaceIdentity {
  /** The pre-workspace database: every workspace shared this one file. */
  const val LEGACY_DATABASE = "pi-native.sqlite"

  private const val DATABASE_PREFIX = "pi-native-"
  private const val DATABASE_SUFFIX = ".sqlite"
  private const val DIGEST_BYTES = 16
  private const val DEFAULT_KEY = "default"

  /** A fresh open of the database: the store and its write-ahead log siblings. */
  private val SIDECARS = listOf("-wal", "-shm", "-journal")
  private val HEX = "0123456789abcdef".toCharArray()

  /** A short, stable key for the workspace at [path]; blank means the app-private default. */
  fun key(path: String?): String {
    if (path.isNullOrBlank()) return DEFAULT_KEY
    val canonical = runCatching { File(path).canonicalPath }.getOrElse { path }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return buildString(DIGEST_BYTES * 2) {
      for (byte in digest.take(DIGEST_BYTES)) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
      }
    }
  }

  /**
   * The database file this workspace owns. A null path keeps the legacy name: only the app's real
   * workspace binding asks for a per-workspace store, and tests/tools that open the host without one
   * keep the same file they always used.
   */
  fun databaseName(path: String?): String =
    if (path.isNullOrBlank()) LEGACY_DATABASE
    else DATABASE_PREFIX + key(path) + DATABASE_SUFFIX

  /**
   * Move a pre-workspace database — and the SQLite sidecars that carry its committed WAL pages — to
   * the first workspace this build opens. The old file had no workspace column, so it cannot be
   * split after the fact; assigning it once to the workspace that is active at upgrade preserves the
   * user's conversations there, and every other workspace starts empty.
   *
   * Returns true only when a legacy database was moved. An existing target is never overwritten: a
   * workspace that already has its own store keeps it.
   */
  fun migrateLegacyDatabase(directory: File, targetName: String): Boolean {
    val legacy = File(directory, LEGACY_DATABASE)
    val target = File(directory, targetName)
    if (targetName == LEGACY_DATABASE) return false
    if (!legacy.exists() || target.exists()) return false

    // Move the database first: until its pages are beside it, no SQLite reader opens the target.
    if (!legacy.renameTo(target) && !copyThenDelete(legacy, target)) return false
    for (suffix in SIDECARS) {
      val source = File(directory, LEGACY_DATABASE + suffix)
      if (!source.exists()) continue
      val destination = File(directory, targetName + suffix)
      // A sidecar carries committed pages, so it is moved even when a rename is refused. If neither
      // move works the database itself is still in place; SQLite simply sees no sidecar beside it.
      source.renameTo(destination) || copyThenDelete(source, destination)
    }
    return true
  }

  /** Rename across a filesystem that refuses it: copy beside the target, then drop the source. */
  private fun copyThenDelete(source: File, target: File): Boolean = runCatching {
    source.copyTo(target, overwrite = false)
    source.delete()
    true
  }.getOrDefault(false)
}
