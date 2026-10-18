package dev.pi.mobile.chat

/**
 * Attachment data layer: hard limits plus name sanitization and content classification.
 *
 * NOTE (attachment slice): the shared attachment value shapes ([AttachmentKind],
 * [PendingAttachment], [ComposerDraft]) live in the composer-draft slice
 * (`ComposerModels.kt`), which the pre-cut `AttachmentModels.kt` also carried. This file owns
 * the rest of that file's content: the limits and the name/type classification that keep
 * attachments honest general files (never an image-only picker, never a fake PDF/Office
 * text extractor).
 */

/** Hard limits for staged composer attachments. */
object AttachmentLimits {
  const val MAX_COUNT = 10
  const val MAX_BYTES_PER_FILE = 8L * 1024L * 1024L // 8 MiB
  const val MAX_TOTAL_BYTES = 24L * 1024L * 1024L // 24 MiB across the draft
}

object AttachmentNameSanitizer {
  const val MAX_NAME_LENGTH = 120

  fun sanitize(raw: String?): String {
    var name = (raw ?: "").trim()
    // Drop path segments (Unix + Windows).
    name = name.substringAfterLast('/').substringAfterLast('\\')
    // Drop controls; replace remaining path separators.
    name = buildString(name.length) {
      for (ch in name) {
        when {
          ch.code < 0x20 || ch.code == 0x7f -> Unit // strip
          ch == '/' || ch == '\\' || ch == ':' -> append('_')
          else -> append(ch)
        }
      }
    }
    // Collapse ".." segments left as a name.
    while (name.contains("..")) name = name.replace("..", ".")
    name = name.trim().trimStart('.')
    if (name.isBlank() || name == "." || name == "..") name = "attachment"
    if (name.length <= MAX_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && name.length - dot <= 12) {
      val ext = name.substring(dot)
      name.substring(0, MAX_NAME_LENGTH - ext.length).trimEnd('.') + ext
    } else {
      name.take(MAX_NAME_LENGTH)
    }
  }
}

object AttachmentClassifier {
  private val IMAGE_MIMES = setOf(
    "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/bmp",
  )
  private val TEXT_MIMES = setOf(
    "text/plain", "text/markdown", "text/csv", "text/html", "text/css", "text/xml",
    "text/x-python", "text/javascript", "application/json", "application/xml",
    "application/javascript", "application/typescript", "application/x-yaml",
    "application/yaml", "application/sql",
  )
  private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "html", "htm",
    "css", "js", "jsx", "ts", "tsx", "mjs", "cjs", "kt", "kts", "java", "py", "rb",
    "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "m", "mm", "sh", "bash",
    "zsh", "fish", "ps1", "bat", "cmd", "yml", "yaml", "toml", "ini", "cfg", "conf",
    "properties", "gradle", "sql", "r", "lua", "pl", "pm", "php", "dart", "scala",
    "clj", "edn", "elm", "ex", "exs", "hs", "erl", "vue", "svelte", "astro", "tex",
    "rst", "adoc", "org", "log", "diff", "patch", "gitignore", "dockerignore",
    "editorconfig", "env", "svg",
  )
  // Never auto-inline or claim extraction for these (honest path-only / binary).
  private val BINARY_MIMES = setOf(
    "application/pdf",
    "application/zip",
    "application/x-zip-compressed",
    "application/gzip",
    "application/x-gzip",
    "application/x-tar",
    "application/x-7z-compressed",
    "application/x-rar-compressed",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/wasm",
    "application/java-archive",
    "application/vnd.android.package-archive",
  )
  private val BINARY_EXTS = setOf(
    "pdf", "zip", "gz", "tgz", "7z", "rar", "xz", "bz2",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "wasm", "so", "dylib", "exe", "dll", "class", "jar", "apk", "aab",
    "dmg", "iso", "bin", "dat", "mp3", "mp4", "mov", "avi", "mkv", "webm",
    "woff", "woff2", "ttf", "otf", "ico", "heic", "heif",
  )

  fun classify(mimeType: String?, displayName: String?, bytes: ByteArray): AttachmentKind {
    val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
    val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    if (mime in IMAGE_MIMES) return AttachmentKind.IMAGE
    if (mime.startsWith("image/") && sniffImageMime(bytes) != null) return AttachmentKind.IMAGE
    if (sniffImageMime(bytes) != null && (mime.isEmpty() || mime == "application/octet-stream")) {
      return AttachmentKind.IMAGE
    }
    // Null bytes → binary even when claimed text.
    if (bytes.any { it == 0.toByte() }) return AttachmentKind.BINARY
    // Known binary containers / office / media — never pretend as extracted text.
    if (mime in BINARY_MIMES || ext in BINARY_EXTS) return AttachmentKind.BINARY
    if (mime in TEXT_MIMES || mime.startsWith("text/")) return AttachmentKind.TEXT
    if (ext in TEXT_EXTS) return AttachmentKind.TEXT
    // UTF-8-ish plain body upgrade for unknown mime (no PDF/doc extraction).
    if (looksLikeUtf8Text(bytes)) return AttachmentKind.TEXT
    return AttachmentKind.BINARY
  }

  fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) {
      return if (bytes.size > 3 && bytes[3] == 0xf7.toByte()) null else "image/jpeg"
    }
    if (bytes.size >= 8 &&
      bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() &&
      bytes[3] == 0x47.toByte() && bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
      bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()
    ) {
      return "image/png"
    }
    if (bytes.size >= 6) {
      val h6 = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
      if (h6 == "GIF87a" || h6 == "GIF89a") return "image/gif"
    }
    if (bytes.size >= 12) {
      val riff = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
      val webp = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
      if (riff == "RIFF" && webp == "WEBP") return "image/webp"
    }
    if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
      return "image/bmp"
    }
    return null
  }

  private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    if (bytes.size > 512 * 1024) return false // large unknown → don't auto-upgrade
    // Reject if too many non-text control chars.
    var i = 0
    while (i < bytes.size) {
      val b = bytes[i].toInt() and 0xff
      when {
        b == 0 -> return false
        b < 0x09 -> return false
        b == 0x0b || b == 0x0c -> return false
        b in 0x0e..0x1f -> return false
        b < 0x80 -> i++
        b shr 5 == 0b110 -> {
          if (i + 1 >= bytes.size || (bytes[i + 1].toInt() and 0xc0) != 0x80) return false
          i += 2
        }
        b shr 4 == 0b1110 -> {
          if (i + 2 >= bytes.size ||
            (bytes[i + 1].toInt() and 0xc0) != 0x80 ||
            (bytes[i + 2].toInt() and 0xc0) != 0x80
          ) return false
          i += 3
        }
        b shr 3 == 0b11110 -> {
          if (i + 3 >= bytes.size ||
            (bytes[i + 1].toInt() and 0xc0) != 0x80 ||
            (bytes[i + 2].toInt() and 0xc0) != 0x80 ||
            (bytes[i + 3].toInt() and 0xc0) != 0x80
          ) return false
          i += 4
        }
        else -> return false
      }
    }
    return true
  }
}
