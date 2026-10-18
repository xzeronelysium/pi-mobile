package dev.pi.mobile.chat

import org.json.JSONObject

/**
 * One app-owned command, as the durable host projects it (`runtime/src/commands.mjs`).
 *
 * A command is a markdown document the app owns, not an installed package: the CLI's command
 * discovery loaded packages through Node, and that loader is gone. `insert` is what selecting the
 * row puts in the composer — a skill inserts its invocation, a template inserts its prompt text.
 */
enum class CommandKind(val wire: String) {
  Skill("skill"),
  Template("template"),

  /** A command the app runs itself: it never reaches the model. */
  Extension("extension"),
  ;

  companion object {
    fun ofWire(value: String?): CommandKind? = entries.firstOrNull { it.wire == value }
  }
}

/**
 * One command row. [invocation] is the `/name` form the composer inserts and the transcript badges;
 * [name] is the skill's own name, which is what the host's extension is keyed by.
 */
data class CommandRow(
  val kind: CommandKind,
  val name: String,
  val invocation: String,
  val description: String = "",
  val argumentHint: String = "",
  val insert: String = "",
)

/** The whole discovered catalog, whichever way it was reached. */
data class CommandCatalog(
  val rows: List<CommandRow> = emptyList(),
  val loading: Boolean = false,
  val notice: String? = null,
) {
  val skills: List<CommandRow> get() = rows.filter { it.kind == CommandKind.Skill }
  val templates: List<CommandRow> get() = rows.filter { it.kind == CommandKind.Template }
  val extensions: List<CommandRow> get() = rows.filter { it.kind == CommandKind.Extension }
}

/**
 * Parse the `commands` frame the host emits. A row missing a name or an invocation is dropped: an
 * unusable row cannot be inserted or matched, and inventing a name would create a command the
 * host's registry does not hold.
 */
object CommandCatalogReducer {
  fun fromValue(value: JSONObject?): CommandCatalog {
    val rows = value?.optJSONArray("rows") ?: return CommandCatalog()
    return CommandCatalog(rows = buildList {
      for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val name = row.optString("name")
        if (name.isBlank()) continue
        val invocation = row.optString("invocation").takeIf { it.isNotBlank() } ?: "/$name"
        add(
          CommandRow(
            kind = CommandKind.ofWire(row.optString("kind")) ?: CommandKind.Skill,
            name = name,
            invocation = invocation,
            description = oneLine(row.optString("description")),
            argumentHint = oneLine(row.optString("argumentHint")),
            insert = row.optString("insert").takeIf { it.isNotBlank() } ?: "$invocation ",
          ),
        )
      }
    })
  }

  /** Collapse to a single line: a sheet row shows one line of description, not a paragraph. */
  private fun oneLine(value: String): String =
    value.replace(Regex("\\s+"), " ").trim().take(240)
}
