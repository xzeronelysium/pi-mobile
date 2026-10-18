package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's dispatch decision.
 *
 * The rule under test: a slash invocation the catalog knows runs by the path its kind owns, and one
 * the catalog does not know is refused here rather than sent to the model. Prose that opens with a
 * slash path is still a message.
 */
class ComposerCommandDispatcherTest {
  private val catalog = CommandDiscoveryReducer.catalog(
    CommandCatalog(
      listOf(
        CommandRow(CommandKind.Skill, "commit", "/commit", "Write a commit message"),
        CommandRow(CommandKind.Template, "handoff", "/handoff", "Summarize the work so far"),
      ),
    ),
  )

  @Test
  fun anInvocationIsSplitIntoItsNameAndItsArguments() {
    val match = ComposerCommandDispatcher.matchInvocation("/commit fix the parser")
    assertEquals("commit", match?.name)
    assertEquals("fix the parser", match?.arguments)
    assertEquals("commit", ComposerCommandDispatcher.matchInvocation("/skill:commit x")?.name)
    assertEquals(true, ComposerCommandDispatcher.matchInvocation("/skill:commit x")?.explicitSkill)
    assertEquals("", ComposerCommandDispatcher.matchInvocation("/commit")?.arguments)
    // Not an invocation at all.
    assertEquals(null, ComposerCommandDispatcher.matchInvocation("hello"))
    assertEquals(null, ComposerCommandDispatcher.matchInvocation("/ leading space"))
    assertEquals(null, ComposerCommandDispatcher.matchInvocation("/"))
  }

  @Test
  fun aKnownSkillIsSentRawSoTheHostCanSelectItsSection() {
    val dispatch = ComposerCommandDispatcher.classify("/commit fix the parser", catalog)
    assertTrue(dispatch is ComposerDispatch.Skill)
    val skill = dispatch as ComposerDispatch.Skill
    assertEquals("commit", skill.row.name)
    assertEquals("fix the parser", skill.arguments)
  }

  @Test
  fun aTemplateIsItsOwnDispatchSoTheComposerInsertsRatherThanSends() {
    val dispatch = ComposerCommandDispatcher.classify("/handoff", catalog)
    assertTrue(dispatch is ComposerDispatch.Template)
    assertEquals("handoff", (dispatch as ComposerDispatch.Template).row.name)
  }

  @Test
  fun anAppCommandRunsLocallyWithItsArguments() {
    val dispatch = ComposerCommandDispatcher.classify("/compact keep the decisions", catalog)
    assertTrue(dispatch is ComposerDispatch.Local)
    val local = dispatch as ComposerDispatch.Local
    assertEquals("compact", local.row.name)
    assertEquals("keep the decisions", local.arguments)
    assertEquals(CommandKind.Extension, local.row.kind)
    // The app's commands are reachable without any document on disk.
    for (name in listOf("new", "compact", "reload", "export", "usage")) {
      assertTrue("$name must classify locally", ComposerCommandDispatcher.classify("/$name", catalog) is ComposerDispatch.Local)
    }
  }

  @Test
  fun anUnknownSlashCommandIsRefusedLocally() {
    val bare = ComposerCommandDispatcher.classify("/nope", catalog)
    assertTrue(bare is ComposerDispatch.Unknown)
    assertEquals("nope", (bare as ComposerDispatch.Unknown).token)
    assertEquals("/nope is not a known command", ComposerCommandDispatcher.rejectionNotice(bare.token))

    // An explicit `/skill:` form that names nothing is an invocation in any case.
    assertTrue(ComposerCommandDispatcher.classify("/skill:nope do it", catalog) is ComposerDispatch.Unknown)
    assertTrue(ComposerCommandDispatcher.classify("/skill:nope", catalog) is ComposerDispatch.Unknown)

    // A bare token that also carries arguments is prose, not a command: it is never intercepted.
    assertTrue(ComposerCommandDispatcher.classify("/nope and then some", catalog) is ComposerDispatch.Plain)
  }

  @Test
  fun anOrdinaryMessageIsLeftAlone() {
    assertTrue(ComposerCommandDispatcher.classify("hello there", catalog) is ComposerDispatch.Plain)
    assertTrue(ComposerCommandDispatcher.classify("", catalog) is ComposerDispatch.Plain)
    // A path is not a command.
    assertTrue(ComposerCommandDispatcher.classify("/etc/hosts is stale", catalog) is ComposerDispatch.Plain)
    assertTrue(ComposerCommandDispatcher.classify("/tmp full, please help", catalog) is ComposerDispatch.Plain)
  }

  @Test
  fun aSkillWinsOverAnAppCommandOfTheSameName() {
    val clashing = listOf(
      CommandRow(CommandKind.Extension, "export", "/export", "app export"),
      CommandRow(CommandKind.Skill, "export", "/export", "skill export"),
    )
    val dispatch = ComposerCommandDispatcher.classify("/export", clashing)
    assertTrue(dispatch is ComposerDispatch.Skill)
    assertEquals(CommandKind.Skill, (dispatch as ComposerDispatch.Skill).row.kind)
  }
}
