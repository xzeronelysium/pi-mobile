package dev.pi.mobile.chat

/**
 * The Resources sheet's vocabulary: the template picker (insert-only) and the extension-command
 * list (searchable, with argument input), both reached from the Workspace sheet.
 *
 * The pre-cut app had the same three surfaces; packages are gone with the Node engine, so the
 * extension list is the app's own commands. Neither list is truncated: [SHEET_VISIBLE_ROWS] is how
 * many rows fit before the sheet scrolls.
 */
object ResourceCatalogUi {
  const val SHEET_VISIBLE_ROWS = 8

  /** Templates matching [query], name-prefix or description. */
  fun templateRows(catalog: List<CommandRow>, query: String): List<CommandRow> =
    CommandDiscoveryReducer.filter(catalog.filter { it.kind == CommandKind.Template }, query)

  /** App commands matching [query], name-prefix or description. */
  fun extensionRows(catalog: List<CommandRow>, query: String): List<CommandRow> =
    CommandDiscoveryReducer.filter(catalog.filter { it.kind == CommandKind.Extension }, query)

  /** The composer text a template contributes: its prompt, ready to send or edit. */
  fun templateInsert(row: CommandRow): String = when (row.kind) {
    // A template's own prompt is the text; a skill's insertion is its invocation.
    CommandKind.Template -> row.insert.ifBlank { row.description }
    else -> ComposerSkillUiPolicy.insertFor(row)
  }

  /** The draft an extension row would run with, shown before it is run. */
  fun extensionDraft(row: CommandRow, arguments: String): String {
    val trimmed = arguments.trim()
    return if (trimmed.isEmpty()) row.invocation else "${row.invocation} $trimmed"
  }
}
