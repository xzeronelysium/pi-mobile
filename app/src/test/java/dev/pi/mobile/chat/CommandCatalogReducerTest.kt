package dev.pi.mobile.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command catalog the pickers and the dispatcher share: what the durable host read off disk,
 * plus the commands this app runs itself. Nothing is truncated and nothing is invented.
 */
class CommandCatalogReducerTest {
  private fun frame(rows: String) = JSONObject("""{"rows":[$rows]}""")

  private val demo =
    """{"kind":"skill","name":"demo","invocation":"/demo","description":"  Demo\n skill  ","argumentHint":"<what>","insert":"/demo "}"""

  @Test
  fun aHostRowCarriesTheInvocationTheDescriptionAndWhatInsertingItWrites() {
    val catalog = CommandCatalogReducer.fromValue(frame(demo))
    val row = catalog.rows.single()
    assertEquals(CommandKind.Skill, row.kind)
    assertEquals("demo", row.name)
    assertEquals("/demo", row.invocation)
    // A multi-line description collapses to one line before a sheet shows it.
    assertEquals("Demo skill", row.description)
    assertEquals("<what>", row.argumentHint)
    assertEquals("/demo ", row.insert)
    assertEquals(listOf("demo"), catalog.skills.map { it.name })
  }

  @Test
  fun aRowWithNoUsableNameIsDroppedRatherThanNamed() {
    val catalog = CommandCatalogReducer.fromValue(
      frame("""{"kind":"skill","description":"no name"},""" + """{"kind":"template","name":"handoff","description":"Hand off"}"""),
    )
    // The unusable row is gone; the usable one keeps both its name and its default invocation.
    assertEquals(listOf("handoff"), catalog.rows.map { it.name })
    assertEquals("/handoff", catalog.rows.single().invocation)
    assertEquals(CommandKind.Template, catalog.rows.single().kind)
  }

  @Test
  fun aMissingOrUnknownKindDoesNotHideTheCommand() {
    val catalog = CommandCatalogReducer.fromValue(frame("""{"name":"x"}"""))
    assertEquals(CommandKind.Skill, catalog.rows.single().kind)
    assertTrue(CommandCatalogReducer.fromValue(JSONObject()).rows.isEmpty())
    assertTrue(CommandCatalogReducer.fromValue(null).rows.isEmpty())
  }

  @Test
  fun theAppOwnCommandsArePartOfTheCatalogAndHostRowsWinTheirNames() {
    val rows = CommandDiscoveryReducer.catalog(CommandCatalog())
    assertEquals(CommandDiscoveryReducer.LOCAL_COMMANDS.size, rows.size)
    assertTrue(rows.all { it.kind == CommandKind.Extension })
    assertTrue(rows.any { it.invocation == "/compact" && it.argumentHint == "[instructions]" })

    // A host row with an app command's name wins: the host is the side that would run it.
    val shadowed = CommandDiscoveryReducer.catalog(
      CommandCatalog(listOf(CommandRow(CommandKind.Extension, "reload", "/reload", "host reload"))),
    )
    assertEquals(1, shadowed.count { it.name == "reload" })
    assertEquals("host reload", shadowed.first { it.name == "reload" }.description)

    // The local commands are only added where the resources sheet asks for them.
    val host = CommandCatalog(listOf(CommandRow(CommandKind.Skill, "demo", "/demo")))
    assertEquals(listOf("demo"), CommandDiscoveryReducer.catalog(host, includeLocal = false).map { it.name })
  }

  @Test
  fun searchIsByNamePrefixByInvocationAndByDescription() {
    val rows = listOf(
      CommandRow(CommandKind.Skill, "commit", "/commit", "Write a commit message"),
      CommandRow(CommandKind.Skill, "review", "/review", "Report defects in the workspace"),
      CommandRow(CommandKind.Template, "handoff", "/handoff", "Summarize the work so far"),
    )
    assertEquals(listOf("commit"), CommandDiscoveryReducer.filter(rows, "com").map { it.name })
    assertEquals(listOf("commit"), CommandDiscoveryReducer.filter(rows, "/com").map { it.name })
    assertEquals(listOf("review"), CommandDiscoveryReducer.filter(rows, "defects").map { it.name })
    assertEquals(rows, CommandDiscoveryReducer.filter(rows, "  "))
    assertTrue(CommandDiscoveryReducer.filter(rows, "nothing-matches").isEmpty())
  }

  @Test
  fun groupsAreOneSectionPerKindAndEmptySectionsAreNotShown() {
    val catalog = CommandDiscoveryReducer.catalog(
      CommandCatalog(
        listOf(
          CommandRow(CommandKind.Template, "handoff", "/handoff"),
          CommandRow(CommandKind.Skill, "demo", "/demo"),
        ),
      ),
    )
    val groups = CommandDiscoveryReducer.groups(catalog)
    assertEquals(listOf("Skills", "Templates", "Extensions"), groups.map { it.title })
    assertEquals(listOf("demo"), groups.first { it.title == "Skills" }.rows.map { it.name })
    // Every app command is reachable from the Extensions section, with no cap.
    assertEquals(CommandDiscoveryReducer.LOCAL_COMMANDS.size, groups.first { it.title == "Extensions" }.rows.size)

    assertEquals(listOf("Skills"), CommandDiscoveryReducer.groups(listOf(CommandRow(CommandKind.Skill, "demo", "/demo"))).map { it.title })
    assertTrue(CommandDiscoveryReducer.groups(emptyList()).isEmpty())
  }
}
