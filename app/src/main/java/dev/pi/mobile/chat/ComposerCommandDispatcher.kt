package dev.pi.mobile.chat

/**
 * What the composer does with one draft, decided locally and purely.
 *
 * The one rule that matters here: a slash command the catalog does not know is **rejected here**
 * rather than sent to the model. Prose that merely opens with a slash path is left alone, so
 * "/etc/hosts is stale" is still a message; only a whole line that is a single slash command, or an
 * explicit `/skill:` form, counts as an invocation that could not be resolved.
 */
sealed interface ComposerDispatch {
  /** A discovered skill: the raw invocation is sent and the host selects the skill's section. */
  data class Skill(val row: CommandRow, val arguments: String) : ComposerDispatch

  /** A template: its prompt text goes into the composer and runs as an ordinary model turn. */
  data class Template(val row: CommandRow) : ComposerDispatch

  /** An app command: run locally, with no model call and no credentials. */
  data class Local(val row: CommandRow, val arguments: String) : ComposerDispatch

  /** A slash command nothing is named by: refused locally. */
  data class Unknown(val token: String) : ComposerDispatch

  /** No slash command at the head of the draft: an ordinary message. */
  object Plain : ComposerDispatch
}

object ComposerCommandDispatcher {
  /** The invocation form the CLI and the pre-cut app used, still accepted as typed input. */
  const val SKILL_PREFIX = "skill:"

  /** A slash command at the head of a draft, in whichever form. */
  data class Invocation(val name: String, val arguments: String, val token: String) {
    /** `/skill:name` names a skill explicitly, so it is never mistaken for prose. */
    val explicitSkill: Boolean get() = token.startsWith(SKILL_PREFIX)
  }

  /**
   * Split a leading `/token` off a draft. `/name` and `/skill:name` both resolve; arguments are
   * whatever follows the token.
   *
   * @return null when the draft does not open with a slash command.
   */
  fun matchInvocation(text: String): Invocation? {
    val line = text.trimStart()
    if (!line.startsWith("/")) return null
    val afterSlash = line.substring(1)
    if (afterSlash.isEmpty()) return null
    val token = afterSlash.takeWhile { !it.isWhitespace() }
    if (token.isEmpty()) return null
    return Invocation(
      name = token.removePrefix(SKILL_PREFIX),
      arguments = afterSlash.substring(token.length).trim(),
      token = token,
    )
  }

  /**
   * Resolve one draft against the catalog. A skill wins over a same-named app command, because the
   * host is the side that would actually run it.
   */
  fun classify(text: String, catalog: List<CommandRow>): ComposerDispatch {
    val invocation = matchInvocation(text) ?: return ComposerDispatch.Plain
    val matches = catalog.filter { it.name == invocation.name }
    val row = matches.firstOrNull { it.kind == CommandKind.Skill }
      ?: matches.firstOrNull { it.kind == CommandKind.Template }
      ?: matches.firstOrNull { it.kind == CommandKind.Extension }
    if (row != null) {
      return when (row.kind) {
        CommandKind.Skill -> ComposerDispatch.Skill(row, invocation.arguments)
        CommandKind.Template -> ComposerDispatch.Template(row)
        CommandKind.Extension -> ComposerDispatch.Local(row, invocation.arguments)
      }
    }
    // Unknown. An explicit `/skill:` form is always an invocation; a bare token is one only when it
    // is the whole line. Anything else is prose that happens to start with a slash path.
    val bare = text.trim()
    if (invocation.explicitSkill || bare == "/${invocation.token}") return ComposerDispatch.Unknown(invocation.token)
    return ComposerDispatch.Plain
  }

  /** What the user is told when a slash command names nothing. */
  fun rejectionNotice(token: String): String = "/$token is not a known command"
}
