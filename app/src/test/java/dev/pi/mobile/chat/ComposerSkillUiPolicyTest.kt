package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's slash autocomplete and the transcript's skill badge.
 *
 * The autocomplete is skills-only and height-bounded, and it never runs anything: selecting a row
 * writes the invocation into the draft. Invoking on send leaves the stored entry raw — the
 * instructions travel in the turn's prompt section — so the transcript shows a badge, never the
 * expansion.
 */
class ComposerSkillUiPolicyTest {
  private val commit = CommandRow(CommandKind.Skill, "commit", "/commit", "Write a commit message", "<scope>")
  private val checklist = CommandRow(CommandKind.Skill, "checklist", "/checklist", "Turn notes into a checklist")
  private val handoff = CommandRow(CommandKind.Template, "handoff", "/handoff", "Summarize the work so far")
  private val skills = listOf(commit, checklist)

  @Test
  fun theQueryIsTheTokenAfterTheSlashAndOnlyWhileItIsBeingTyped() {
    assertEquals("", ComposerSkillUiPolicy.slashQuery("/"))
    assertEquals("co", ComposerSkillUiPolicy.slashQuery("/co"))
    assertEquals("co", ComposerSkillUiPolicy.slashQuery("  /co"))
    // A space after the token means the arguments have begun: the list closes.
    assertNull(ComposerSkillUiPolicy.slashQuery("/commit -m"))
    assertNull(ComposerSkillUiPolicy.slashQuery("/ commit"))
    // No leading slash, no completion.
    assertNull(ComposerSkillUiPolicy.slashQuery("hello /commit"))
    assertNull(ComposerSkillUiPolicy.slashQuery(""))
  }

  @Test
  fun theAutocompleteListsSkillsOnlyAndNeverATemplateOrAnAppCommand() {
    val query = "/"
    val offered = ComposerSkillUiPolicy.suggestions(skills, query)
    assertEquals(listOf("commit", "checklist"), offered.map { it.name })
    assertTrue(offered.all { it.kind == CommandKind.Skill })
    // A template and an app command in the same catalog are not offered here; they have their own
    // entry points behind the Workspace sheet.
    val mixed = skills + handoff + CommandDiscoveryReducer.LOCAL_COMMANDS
    assertEquals(listOf("commit", "checklist"), ComposerSkillUiPolicy.suggestions(mixed, "/").map { it.name })
  }

  @Test
  fun suggestionsMatchOnNamePrefixAndOnDescriptionAndAreNotTruncated() {
    assertEquals(listOf("commit"), ComposerSkillUiPolicy.suggestions(skills, "/com").map { it.name })
    assertEquals(listOf("checklist"), ComposerSkillUiPolicy.suggestions(skills, "/notes").map { it.name })
    assertTrue(ComposerSkillUiPolicy.suggestions(skills, "/zzz").isEmpty())
    // Every match is returned: the caller bounds the viewport and scrolls, so no skill is hidden.
    val many = (1..40).map { CommandRow(CommandKind.Skill, "skill$it", "/skill$it", "Skill $it") }
    assertEquals(40, ComposerSkillUiPolicy.suggestions(many, "/").size)
    assertTrue(ComposerSkillUiPolicy.VISIBLE_ROWS < 40)
  }

  @Test
  fun aRowShowsItsInvocationAndOneLineOfDescription() {
    val row = CommandRow(CommandKind.Skill, "commit", "/commit", "Write a  commit\n message")
    assertEquals("/commit", ComposerSkillUiPolicy.displayTitle(row))
    assertEquals("Write a commit message", ComposerSkillUiPolicy.oneLineDescription(row))
    // A row that somehow carries no invocation still shows a usable title.
    assertEquals("/commit", ComposerSkillUiPolicy.displayTitle(CommandRow(CommandKind.Skill, "commit", "")))
  }

  @Test
  fun selectingASkillPutsItsInvocationInTheDraftAndRunsNothing() {
    assertEquals("/commit ", ComposerSkillUiPolicy.applyInsertion("/comm", commit))
    assertEquals("/commit ", ComposerSkillUiPolicy.applyInsertion("/", commit))
    // With no invocation under the caret the insertion still lands in front of what was typed.
    assertEquals("/commit hello", ComposerSkillUiPolicy.applyInsertion("hello", commit))
    // And the draft is then an invocation with no arguments: inserting is not sending.
  }

  @Test
  fun aStoredEntryBadgesTheInvocationAndKeepsTheUsersOwnWords() {
    val badge = ComposerSkillUiPolicy.skillBadge("/commit fix the parser", skills)
    assertEquals("commit", badge?.name)
    assertEquals("/commit", badge?.invocation)
    assertEquals("fix the parser", badge?.arguments)
    // The CLI's `/skill:` form badges exactly the same way.
    assertEquals("commit", ComposerSkillUiPolicy.skillBadge("/skill:commit", skills)?.name)
    assertEquals("", ComposerSkillUiPolicy.skillBadge("/commit", skills)?.arguments)
  }

  @Test
  fun aPromptThatInvokesNothingKnownHasNoBadge() {
    assertNull(ComposerSkillUiPolicy.skillBadge("/nope", skills))
    assertNull(ComposerSkillUiPolicy.skillBadge("plain text", skills))
    // Prose that merely opens with a slash path is not an invocation.
    assertNull(ComposerSkillUiPolicy.skillBadge("/etc/hosts is stale", skills))
    assertNull(ComposerSkillUiPolicy.skillBadge("/handoff", skills))
    assertFalse(ComposerSkillUiPolicy.skillBadge("/commit", emptyList()) != null)
  }
}
