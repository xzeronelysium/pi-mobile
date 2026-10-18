package dev.pi.mobile.chat

import java.util.Locale

/**
 * The composer's slash surface, as pure decisions.
 *
 * The autocomplete is **skills-only**: a compact, height-bounded vertical list of the skills the
 * app-owned documents define, each showing its invocation and one line of description. Selecting
 * one inserts the invocation into the draft; it never runs anything. Every other kind of command
 * keeps its own entry point (templates and extensions from the Workspace sheet) rather than
 * crowding this list.
 *
 * The list is not truncated here. [suggestions] returns every match and the caller bounds the
 * viewport, because a viewport's height is not a discovery limit.
 */
object ComposerSkillUiPolicy {
  /** Rows the list shows at once before it scrolls. A height bound, not a cap on the catalog. */
  const val VISIBLE_ROWS = 5

  /** A skill's badge in the transcript: the invocation that produced it and the user's own words. */
  data class SkillBadge(val name: String, val invocation: String, val arguments: String)

  /**
   * What the user is completing right now, or null when the composer is not mid-invocation.
   *
   * The draft has to *start* with the slash and the caret is at the end of the draft, so the token
   * under completion is everything after the slash up to the first space. Once a space has been
   * typed the arguments have begun and the list closes.
   */
  fun slashQuery(draft: String): String? {
    val text = draft.trimStart()
    if (!text.startsWith("/")) return null
    val afterSlash = text.substring(1)
    if (afterSlash.isEmpty()) return ""
    if (afterSlash.first().isWhitespace()) return null
    val token = afterSlash.takeWhile { !it.isWhitespace() }
    // A space after the token means the user is typing arguments, not naming a command.
    if (token.length != afterSlash.length) return null
    return token
  }

  /**
   * The skills to offer for [draft]. Skills only, and that is enforced here rather than by the
   * caller: a template is inserted from the Resources sheet and an app command from the Extensions
   * sheet, so neither belongs in this list even when a caller hands over the whole catalog.
   */
  fun suggestions(skills: List<CommandRow>, draft: String): List<CommandRow> {
    val query = slashQuery(draft) ?: return emptyList()
    val offered = skills.filter { it.kind == CommandKind.Skill }
    val needle = query.lowercase(Locale.US)
    if (needle.isEmpty()) return offered
    return offered.filter { it.name.lowercase(Locale.US).startsWith(needle) || it.description.lowercase(Locale.US).contains(needle) }
  }

  /** What inserting a row writes. The host always sends `insert`; a row built locally falls back to its invocation. */
  fun insertFor(row: CommandRow): String = row.insert.ifBlank { "${displayTitle(row)} " }

  /** The row's main line: the invocation, which is exactly what inserting it writes. */
  fun displayTitle(row: CommandRow): String = row.invocation.ifBlank { "/${row.name}" }

  /** The row's second line: one line, collapsed, never a paragraph. */
  fun oneLineDescription(row: CommandRow): String =
    row.description.replace(Regex("\\s+"), " ").trim()

  /** The composer text after inserting [row] at the end of [draft]'s invocation token. */
  fun applyInsertion(draft: String, row: CommandRow): String {
    val text = draft.trimStart()
    val query = slashQuery(text)
    if (query == null) return if (text.isEmpty()) insertFor(row) else "${insertFor(row)}$text"
    return insertFor(row)
  }

  /**
   * The badge a stored user entry projects to.
   *
   * The stored entry is the raw invocation the user authored — the skill's instructions travel in
   * the turn's prompt section, never in the message — so the transcript shows the invocation and
   * the user's own arguments instead of either the expansion or a bare slash command.
   */
  fun skillBadge(text: String, skills: List<CommandRow>): SkillBadge? {
    val match = ComposerCommandDispatcher.matchInvocation(text) ?: return null
    val skill = skills.firstOrNull { it.name == match.name } ?: return null
    return SkillBadge(name = skill.name, invocation = skill.invocation, arguments = match.arguments)
  }
}
