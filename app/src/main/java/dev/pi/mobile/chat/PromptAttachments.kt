package dev.pi.mobile.chat

/**
 * Builds the machine-facing prompt for one composer send that carries staged attachments.
 *
 * This is the durable replacement for the pre-cut `runtime/node-src/attachments.mjs`
 * (`preparePromptAttachments`). The durable host submits a plain prompt string, so attachments
 * travel as `<file …>…</file>` blocks appended to the user's own text — exactly the shape
 * [UserMessagePresenter] parses back into transcript file cards. What the model receives and what
 * the user sees therefore come from one string, and no message is rewritten afterwards.
 *
 * Honesty rules (never present a file as something it is not):
 *  - a text file is inlined only after it was actually read, under the per-file and aggregate caps;
 *  - an image or binary file is a path-only reference; the app never claims PDF/Office extraction;
 *  - a file that could not be read or does not fit keeps its tag with a note saying why.
 *
 * Pure: the caller supplies [Staged.textContent] (the bytes already read off the UI thread) and the
 * file's real [PendingAttachment.sizeBytes]. No I/O happens here.
 */
object PromptAttachments {
  /** Per-file inline text into the prompt (bytes). Mirrors the pre-cut backend cap. */
  const val MAX_TEXT_INLINE = 512 * 1024

  /** Aggregate inline text across all attachments in one prompt. */
  const val MAX_TEXT_INLINE_AGGREGATE = 768 * 1024

  /** A staged attachment plus the text the caller read for it (null when it was not read). */
  data class Staged(val attachment: PendingAttachment, val textContent: String? = null)

  /** The `kind="…"` attribute value for a staged attachment. */
  fun kindLabel(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGE -> "image"
    AttachmentKind.TEXT -> "text"
    AttachmentKind.BINARY -> "binary"
  }

  /** Escape a value for the prompt's `key="value"` attribute context. */
  fun escapeAttr(value: String?): String = (value ?: "")
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "&apos;")

  /** One file block. A null/blank body is written self-closed. */
  fun fileTag(name: String, path: String, kind: String, mime: String?, body: String?): String {
    val attrs = ArrayList<String>(4)
    attrs.add("name=\"${escapeAttr(name)}\"")
    attrs.add("path=\"${escapeAttr(path)}\"")
    if (kind.isNotBlank()) attrs.add("kind=\"${escapeAttr(kind)}\"")
    if (!mime.isNullOrBlank()) attrs.add("mime=\"${escapeAttr(mime)}\"")
    val head = "<file ${attrs.joinToString(" ")}>"
    return if (body.isNullOrEmpty()) "$head</file>" else "$head\n$body\n</file>"
  }

  /**
   * The prompt the model receives: the user's own text first, then one block per staged file,
   * blank-line separated — the same shape the pre-cut backend produced.
   */
  fun build(prompt: String, staged: List<Staged>): String {
    val parts = ArrayList<String>(staged.size + 1)
    if (prompt.isNotBlank()) parts.add(prompt)
    var inlineBudget = MAX_TEXT_INLINE_AGGREGATE
    for (item in staged) {
      val file = item.attachment
      val name = file.displayName.ifBlank { "attachment" }
      val path = file.absolutePath
      val kind = kindLabel(file.kind)
      when (file.kind) {
        AttachmentKind.IMAGE -> parts.add(
          fileTag(
            name = name,
            path = path,
            kind = kind,
            mime = file.mimeType,
            // No vision payload is built here: the durable submit carries a prompt string only, so
            // the image stays a real tool-accessible path rather than a claimed inline.
            body = "Image attached at tool-accessible path (model has no vision input or image could not be inlined).",
          ),
        )

        AttachmentKind.TEXT -> {
          val content = item.textContent
          val note = when {
            file.sizeBytes > MAX_TEXT_INLINE ->
              "Text file too large to inline (${file.sizeBytes} bytes); use tools to read the path."
            content == null ->
              "Text file could not be read; use tools to read the path."
            content.length > inlineBudget ->
              "Inline text budget exhausted ($MAX_TEXT_INLINE_AGGREGATE bytes); use tools to read the path."
            else -> null
          }
          if (note != null) {
            parts.add(fileTag(name, path, kind, if (file.mimeType.isBlank()) null else file.mimeType, note))
          } else {
            inlineBudget -= content!!.length
            parts.add(fileTag(name, path, kind, if (file.mimeType.isBlank()) null else file.mimeType, content.replaceFirst("\uFEFF", "")))
          }
        }

        AttachmentKind.BINARY -> parts.add(
          fileTag(
            name = name,
            path = path,
            kind = kind,
            mime = file.mimeType.ifBlank { "application/octet-stream" },
            body = "Binary attachment at tool-accessible path (no automatic extraction).",
          ),
        )
      }
    }
    return parts.joinToString("\n\n")
  }
}
