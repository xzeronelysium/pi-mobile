package dev.pi.mobile.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pi.mobile.PiBackground
import dev.pi.mobile.PiError
import dev.pi.mobile.PiForeground
import dev.pi.mobile.PiHairline
import dev.pi.mobile.PiSecondary

/**
 * The command surfaces: the composer's skills-only autocomplete, and the sheets behind the
 * Workspace sheet — Templates (insert-only), Extensions (run locally, with arguments) and MCP
 * servers.
 *
 * Every list bounds its own height and scrolls; none of them truncates the catalog. Each row shows
 * its invocation and one line of description, and selecting a row writes a draft — nothing here
 * runs a command or sends anything.
 */

/** The composer's slash autocomplete: skills only, compact, height-bounded, insert-only. */
@Composable
internal fun SlashAutocomplete(rows: List<CommandRow>, onInsert: (CommandRow) -> Unit) {
  if (rows.isEmpty()) return
  Column(
    Modifier
      .fillMaxWidth()
      .padding(start = 12.dp, end = 12.dp, bottom = 2.dp)
      .heightIn(max = (ComposerSkillUiPolicy.VISIBLE_ROWS * 44).dp)
      .background(PiBackground),
  ) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
      for (row in rows) {
        Row(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { onInsert(row) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              ComposerSkillUiPolicy.displayTitle(row),
              color = PiForeground,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val description = ComposerSkillUiPolicy.oneLineDescription(row)
            if (description.isNotBlank()) {
              Text(
                description,
                color = PiSecondary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          Text("insert", color = PiSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
      }
    }
  }
}

/** Prompt templates: insert into the composer only. Send runs it as an ordinary model turn. */
@Composable
internal fun TemplatesSheet(
  rows: List<CommandRow>,
  query: String,
  onQuery: (String) -> Unit,
  onInsert: (CommandRow) -> Unit,
) {
  SheetSection(
    title = "Templates",
    note = "Insert a prompt template into the composer. Send runs it as a model turn.",
    query = query,
    onQuery = onQuery,
    placeholder = "Filter templates",
    empty = "No templates discovered yet. Templates are markdown files under .pi-templates/ in the workspace.",
    rows = rows,
    rowDetail = { ComposerSkillUiPolicy.oneLineDescription(it) },
    trailing = { "insert" },
    onRow = onInsert,
  )
}

/** App commands: searchable, with the arguments field the row's own hint describes. */
@Composable
internal fun ExtensionsSheet(
  rows: List<CommandRow>,
  query: String,
  busy: Boolean,
  notice: String?,
  onQuery: (String) -> Unit,
  onRun: (CommandRow, String) -> Unit,
) {
  var selected by remember { mutableStateOf<CommandRow?>(null) }
  var arguments by remember { mutableStateOf("") }
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
    SectionTitle("Extensions")
    SectionNote("App commands. They run locally, without a model call and without credentials.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        "${rows.size} command${if (rows.size == 1) "" else "s"} · searchable by name or description",
        color = PiSecondary,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
    UnderlineField(query, onQuery, "Search extensions")
    notice?.let { SectionNote(it) }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
      if (rows.isEmpty()) SectionNote("No app commands discovered yet.")
      for (row in rows) {
        val isSelected = selected?.name == row.name
        Column(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { selected = row; arguments = "" }
            .padding(vertical = 6.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              ComposerSkillUiPolicy.displayTitle(row),
              color = PiForeground,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f),
            )
            Text(
              if (isSelected) ")" else " ",
              color = PiSecondary,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
            )
          }
          val detail = ComposerSkillUiPolicy.oneLineDescription(row)
          if (detail.isNotBlank()) {
            Text(detail, color = PiSecondary, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
      Spacer(Modifier.height(8.dp))
    }
    selected?.let { row ->
      UnderlineField(arguments, { arguments = it }, row.argumentHint.ifBlank { "Arguments (optional)" })
      Text(
        ResourceCatalogUi.extensionDraft(row, arguments),
        color = PiSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        if (busy) "busy" else "run",
        color = if (busy) PiSecondary else PiForeground,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .clickable(enabled = !busy) { onRun(row, arguments) }
          .padding(vertical = 12.dp),
      )
    } ?: Spacer(Modifier.height(12.dp))
  }
}

/**
 * MCP servers over HTTP. A server is connected only when it really connected: a failure keeps its
 * own reason, offers no tools, and says so here instead of showing a configured server as working.
 */
@Composable
internal fun McpSheet(
  state: McpState,
  busy: Boolean,
  query: String,
  onQuery: (String) -> Unit,
  onDraftName: (String) -> Unit,
  onDraftUrl: (String) -> Unit,
  onAdd: () -> Unit,
  onRemove: (String) -> Unit,
  onConnect: (String) -> Unit,
  onDisconnect: (String) -> Unit,
  onRefresh: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
    SectionTitle("MCP servers")
    Text(
      McpUiReducer.summary(state),
      color = PiSecondary,
      fontSize = 10.sp,
      lineHeight = 14.sp,
      fontFamily = FontFamily.Monospace,
    )
    SectionNote("Only the network transport is available on this build. A server is connected when you connect it, not when it is saved.")
    for (error in state.errors) SectionNote(error, error = true)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
      for (server in state.servers) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(server.name, color = PiForeground, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(McpUiReducer.statusLine(server), color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
            Text(server.url, color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.plaintext) SectionNote(McpUiReducer.PLAINTEXT_NOTE)
          }
          Column {
            if (McpUiReducer.canConnect(state, server, busy)) {
              Text("connect", color = PiForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onConnect(server.id) }.padding(horizontal = 6.dp, vertical = 6.dp))
            }
            if (McpUiReducer.canDisconnect(busy, server)) {
              Text("disconnect", color = PiForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onDisconnect(server.id) }.padding(horizontal = 6.dp, vertical = 6.dp))
            }
            if (!busy) {
              Text("remove", color = PiError, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable { onRemove(server.id) }.padding(horizontal = 6.dp, vertical = 6.dp))
            }
          }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
      }
      if (state.servers.isEmpty()) SectionNote("Nothing configured yet.")
      Spacer(Modifier.height(12.dp))
    }
    UnderlineField(state.draftName, onDraftName, "Server name (e.g. Files)")
    UnderlineField(state.draftUrl, onDraftUrl, "https://host/path/mcp")
    McpUiReducer.addError(state)?.let { SectionNote(it, error = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Text(
        "save server",
        color = if (McpUiReducer.addError(state) == null && !busy) PiForeground else PiSecondary,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .clickable(enabled = McpUiReducer.addError(state) == null && !busy) { onAdd() }
          .padding(vertical = 10.dp),
      )
      Text("refresh", color = PiSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.clickable(enabled = !busy) { onRefresh() }.padding(vertical = 10.dp))
    }
    SectionTitle("Tools offered to the agent")
    UnderlineField(query, onQuery, "Search tools")
    val rows = McpUiReducer.toolRows(state, query)
    if (rows.isEmpty()) {
      SectionNote(if (state.servers.any { it.state == McpConnectionState.Connected }) "No tool matches that search." else "Connect a server to see the tools it publishes.")
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
      for ((server, tool) in rows) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          Text(tool.name, color = PiForeground, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("${server.name} · ${tool.tool}", color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
          if (tool.description.isNotBlank()) {
            Text(tool.description, color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
    Spacer(Modifier.height(12.dp))
  }
}

// --------------------------------------------------------------------------- local chrome

/** A shared sheet body: title, note, search field, a bounded scrolling list, then the trailing label. */
@Composable
private fun SheetSection(
  title: String,
  note: String,
  query: String,
  onQuery: (String) -> Unit,
  placeholder: String,
  empty: String,
  rows: List<CommandRow>,
  rowDetail: (CommandRow) -> String,
  trailing: (CommandRow) -> String,
  onRow: (CommandRow) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
    SectionTitle(title)
    SectionNote(note)
    UnderlineField(query, onQuery, placeholder)
    Text(
      "${rows.size} row${if (rows.size == 1) "" else "s"}",
      color = PiSecondary,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
    )
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
      if (rows.isEmpty()) SectionNote(empty)
      for (row in rows) {
        Row(
          Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { onRow(row) }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              ComposerSkillUiPolicy.displayTitle(row),
              color = PiForeground,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val detail = rowDetail(row)
            if (detail.isNotBlank()) {
              Text(detail, color = PiSecondary, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (row.argumentHint.isNotBlank()) {
              Text(row.argumentHint, color = PiSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          Text(trailing(row), color = PiSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
    Text(text, color = PiForeground, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
}

@Composable
private fun SectionNote(text: String, error: Boolean = false) {
  Text(
    text,
    color = if (error) PiError else PiSecondary,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    fontFamily = FontFamily.Monospace,
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
  )
}

/** A local underline field: the shared sheet's `FlatField` is private to `Settings.kt`. */
@Composable
private fun UnderlineField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    textStyle = TextStyle(color = PiForeground, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
    cursorBrush = SolidColor(PiForeground),
    decorationBox = { inner ->
      Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
          if (value.isEmpty()) {
            Text(placeholder, color = PiSecondary.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
          }
          inner()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PiHairline))
      }
    },
  )
}

/** The transcript's skill badge line: the invocation, never the expansion. */
@Composable
internal fun SkillBadgeLine(invocation: String) {
  Text(
    "[skill] $invocation",
    color = PiSecondary,
    fontSize = 12.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth(),
  )
}
