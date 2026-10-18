package dev.pi.mobile.chat

import java.util.Locale

/**
 * Command discovery and its projections.
 *
 * The catalog is the union of two sources: the app-owned documents the durable host reads
 * (`runtime/src/commands.mjs` — skills and templates) and the commands this app runs itself. There
 * is no package loader any more, so an "extension command" is an app action, not a file: the
 * pre-cut surface is preserved as a real, searchable list rather than being dropped with the Node
 * engine.
 *
 * Nothing here truncates a catalog. A sheet bounds its own viewport and scrolls; `take(n)` would
 * hide a command that no longer has any other way to be reached.
 */
object CommandDiscoveryReducer {
  /** The app's own commands. Each is handled by the Activity; none of them calls the model. */
  val LOCAL_COMMANDS: List<CommandRow> = listOf(
    CommandRow(
      kind = CommandKind.Extension,
      name = "new",
      invocation = "/new",
      description = "Start a new session in this workspace",
      insert = "/new",
    ),
    CommandRow(
      kind = CommandKind.Extension,
      name = "compact",
      invocation = "/compact",
      description = "Compact older messages into a summary",
      argumentHint = "[instructions]",
      insert = "/compact ",
    ),
    CommandRow(
      kind = CommandKind.Extension,
      name = "reload",
      invocation = "/reload",
      description = "Re-list models from the providers (no model call)",
      insert = "/reload",
    ),
    CommandRow(
      kind = CommandKind.Extension,
      name = "export",
      invocation = "/export",
      description = "Write this transcript to a file as markdown",
      insert = "/export",
    ),
    CommandRow(
      kind = CommandKind.Extension,
      name = "usage",
      invocation = "/usage",
      description = "Refresh the session usage and context figures",
      insert = "/usage",
    ),
  )

  /**
   * The catalog the dispatcher and the sheets share: the host's rows first, then the app's own
   * commands. A host row with an extension command's name wins, because the host is the one that
   * would actually run it.
   */
  fun catalog(host: CommandCatalog, includeLocal: Boolean = true): List<CommandRow> {
    if (!includeLocal) return host.rows
    val taken = host.rows.map { it.name }.toSet()
    return host.rows + LOCAL_COMMANDS.filter { it.name !in taken }
  }

  /**
   * Search by name prefix and by description, which is what the sheet fields are for. A query that
   * begins with `/` searches the invocation, so the same field serves both ways of asking.
   */
  fun filter(rows: List<CommandRow>, query: String): List<CommandRow> {
    val needle = query.trim().lowercase(Locale.US).removePrefix("/")
    if (needle.isEmpty()) return rows
    return rows.filter { row ->
      row.name.lowercase(Locale.US).startsWith(needle) ||
        row.invocation.lowercase(Locale.US).removePrefix("/").startsWith(needle) ||
        row.description.lowercase(Locale.US).contains(needle)
    }
  }

  /** One section per kind, in the order the sheets show them, with the titles the pre-cut UI used. */
  fun groups(rows: List<CommandRow>): List<CommandGroup> = listOf(
    CommandGroup("Skills", rows.filter { it.kind == CommandKind.Skill }),
    CommandGroup("Templates", rows.filter { it.kind == CommandKind.Template }),
    CommandGroup("Extensions", rows.filter { it.kind == CommandKind.Extension }),
  ).filter { it.rows.isNotEmpty() }
}

/** A titled block of rows. */
data class CommandGroup(val title: String, val rows: List<CommandRow>)
