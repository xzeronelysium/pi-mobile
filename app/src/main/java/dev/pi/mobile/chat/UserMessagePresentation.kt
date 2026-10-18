package dev.pi.mobile.chat

/**
 * Presentation-layer projection of a raw user prompt.
 *
 * The stored user message (Pi SDK session history) is machine-oriented. It carries blocks the
 * model must receive verbatim, but that must not be dumped into the transcript as raw text:
 *
 *  - `<skill name="…" location="…">…</skill>` — Pi SDK skill-command expansion
 *    (`AgentSession._expandSkillCommand`), which replaces `/skill:<name> <args>` with the full
 *    SKILL.md instructions and appends the user's own arguments after the closing tag.
 *  - `<file name="…" path="…"[ kind="…"][ mime="…"]>…</file>` — the mobile attachment pipeline
 *    ([PromptAttachments], the durable replacement for `runtime/node-src/attachments.mjs`). Text
 *    files are inlined; images/binary/large files carry a path-only note instead of their content.
 *
 * [UserMessagePresenter.present] splits those blocks out so the transcript can render a compact
 * skill badge and real attachment cards while the human-authored part stays as [body].
 *
 * This is display-only: the raw message text is never rewritten, truncated or removed, so the
 * model keeps receiving exactly what it received before and stored history needs no migration.
 * Pure and side-effect free; safe to unit test off-device.
 */
data class SkillActivation(val name: String, val location: String? = null)

data class AttachedFileRef(
  val name: String,
  val path: String?,
  val kind: String,
  val mimeType: String? = null,
  /** False when the block carried a path-only note instead of the file's content. */
  val inlined: Boolean = false,
)

data class UserPresentation(
  val body: String = "",
  val skills: List<SkillActivation> = emptyList(),
  val files: List<AttachedFileRef> = emptyList(),
) {
  val structured: Boolean get() = skills.isNotEmpty() || files.isNotEmpty()
}

object UserMessagePresenter {
  private const val SKILL_MARKER = "<skill "
  private const val FILE_MARKER = "<file "

  /** Pi SDK skill expansion: exactly one block, normally at the start of the prompt. */
  private val SKILL_BLOCK =
    Regex("""<skill\s+name="([^"]*)"\s+location="([^"]*)"\s*>[\r\n]*([\s\S]*?)[\r\n]*</skill>""")

  /** Attachment tag. Attribute values are XML-escaped by the builder, so `>` never appears raw. */
  private val FILE_BLOCK = Regex("""<file\s+([^>]*)>([\s\S]*?)</file>""")

  private val ATTR = Regex("""([A-Za-z_][\w-]*)\s*=\s*"([^"]*)"""")

  /** Bodies the builder writes for path-only references — never a file's real content. */
  private val PATH_ONLY_PREFIXES = listOf(
    "Image too large to inline",
    "Image attached at tool-accessible path",
    "Binary attachment at tool-accessible path",
    "Binary/non-text file at tool-accessible path",
    "Text file too large to inline",
    "Inline text budget exhausted",
  )

  fun present(raw: String): UserPresentation {
    if (raw.isEmpty() || (!raw.contains(SKILL_MARKER) && !raw.contains(FILE_MARKER))) {
      // Plain prompt: returned untouched, byte for byte (no trimming, no normalization).
      return UserPresentation(body = raw)
    }

    val skills = mutableListOf<SkillActivation>()
    var working = SKILL_BLOCK.replace(raw) { match ->
      skills += SkillActivation(
        name = decodeAttr(match.groupValues[1]),
        location = decodeAttr(match.groupValues[2]).takeIf { it.isNotBlank() },
      )
      "\n"
    }

    val files = mutableListOf<AttachedFileRef>()
    working = FILE_BLOCK.replace(working) { match ->
      files += fileRef(match.groupValues[1], match.groupValues[2])
      "\n"
    }

    // No recognized block survived: keep the original text exactly as authored.
    if (skills.isEmpty() && files.isEmpty()) return UserPresentation(body = raw)

    return UserPresentation(body = normalizeBody(working), skills = skills, files = files)
  }

  private fun fileRef(attrs: String, rawBody: String): AttachedFileRef {
    val map = ATTR.findAll(attrs)
      .associate { it.groupValues[1].lowercase() to decodeAttr(it.groupValues[2]) }
    val body = rawBody.trim('\n', '\r')
    val pathOnly = body.isEmpty() || PATH_ONLY_PREFIXES.any { body.startsWith(it) }
    return AttachedFileRef(
      name = map["name"]?.takeIf { it.isNotBlank() } ?: "attachment",
      path = map["path"]?.takeIf { it.isNotBlank() },
      kind = map["kind"]?.takeIf { it.isNotBlank() } ?: "file",
      mimeType = map["mime"]?.takeIf { it.isNotBlank() },
      inlined = !pathOnly,
    )
  }

  /** Attribute values are XML-escaped when written; decode them so the card shows the real name. */
  private fun decodeAttr(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

  private fun normalizeBody(text: String): String =
    text.replace(Regex("""\n{3,}"""), "\n\n").trim()
}
