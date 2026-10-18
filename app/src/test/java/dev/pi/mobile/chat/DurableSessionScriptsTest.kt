package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session surface's bridge calls, against the operations `runtime/src/host.mjs` actually
 * exposes: `sessions`, `tree`, `openSession`, `newSession`, `forkSession`, `resetSession`,
 * `renameSession`, `compactSession`, `usage`, `queue`, `steer`, `followUp`, `withdraw`,
 * `exportSession`.
 *
 * Its ids are integers — a conversation id, a fork entry id and a submission id are all refused
 * when they are not — so a numeric id must travel as a JSON number.
 */
class DurableSessionScriptsTest {
  @Test fun everySessionOpNamesTheRealRuntimeMethod() {
    assertTrue(DurableRuntimeScripts.sessions().startsWith("window.PiNative.sessions()"))
    assertTrue(DurableRuntimeScripts.tree().startsWith("window.PiNative.tree()"))
    assertTrue(DurableRuntimeScripts.newSession().startsWith("window.PiNative.newSession()"))
    assertTrue(DurableRuntimeScripts.usage().startsWith("window.PiNative.usage()"))
    assertTrue(DurableRuntimeScripts.queue().startsWith("window.PiNative.queue()"))
    assertTrue(DurableRuntimeScripts.openSession("7").contains("window.PiNative.openSession("))
    assertTrue(DurableRuntimeScripts.forkSession("7").contains("window.PiNative.forkSession("))
    assertTrue(DurableRuntimeScripts.resetSession(null).contains("window.PiNative.resetSession("))
    assertTrue(DurableRuntimeScripts.renameSession("7", "New").contains("window.PiNative.renameSession("))
    assertTrue(DurableRuntimeScripts.compactSession(null).contains("window.PiNative.compactSession("))
    assertTrue(DurableRuntimeScripts.steer("hi").contains("window.PiNative.steer("))
    assertTrue(DurableRuntimeScripts.followUp("hi").contains("window.PiNative.followUp("))
    assertTrue(DurableRuntimeScripts.withdraw("3").contains("window.PiNative.withdraw("))
    assertTrue(DurableRuntimeScripts.exportSession("jsonl").contains("window.PiNative.exportSession("))
  }

  @Test fun everySessionOpIsGuarded() {
    val scripts = listOf(
      DurableRuntimeScripts.sessions(),
      DurableRuntimeScripts.tree(),
      DurableRuntimeScripts.openSession("1"),
      DurableRuntimeScripts.newSession(),
      DurableRuntimeScripts.forkSession("2"),
      DurableRuntimeScripts.resetSession(null),
      DurableRuntimeScripts.resetSession("hand off"),
      DurableRuntimeScripts.renameSession("1", "n"),
      DurableRuntimeScripts.compactSession(null),
      DurableRuntimeScripts.compactSession("summarize"),
      DurableRuntimeScripts.usage(),
      DurableRuntimeScripts.queue(),
      DurableRuntimeScripts.steer("hello"),
      DurableRuntimeScripts.followUp("hello"),
      DurableRuntimeScripts.withdraw("3"),
      DurableRuntimeScripts.exportSession("markdown"),
      DurableRuntimeScripts.exportSession("jsonl"),
    )
    scripts.forEach { assertTrue(it, it.contains("catch(e=>AndroidNative.postMessage")) }
    scripts.forEach { assertTrue(it, it.contains("event:'error'")) }
  }

  /** The runtime refuses a non-integer id, so a numeric string has to arrive as a number. */
  @Test fun numericIdsTravelAsJsonNumbersAndOthersAsText() {
    assertTrue(DurableRuntimeScripts.openSession("7").contains("\"conversationId\":7"))
    assertTrue(DurableRuntimeScripts.openSession("root").contains("\"conversationId\":\"root\""))
    assertTrue(DurableRuntimeScripts.forkSession("42").contains("\"entryId\":42"))
    assertTrue(DurableRuntimeScripts.forkSession("entry-a1").contains("\"entryId\":\"entry-a1\""))
    assertTrue(DurableRuntimeScripts.withdraw("3").contains("\"submissionId\":3"))
  }

  @Test fun aQueuedSubmissionCarriesItsTextAndItsRequestId() {
    val steer = DurableRuntimeScripts.steer("hold on")
    assertTrue(steer.contains("\"text\":\"hold on\""))
    assertTrue(steer.contains("\"requestId\":\""))
    // The mode belongs to the operation, not to the payload: `steer` and `followUp` are separate.
    assertFalse(steer.contains("\"mode\""))

    val followUp = DurableRuntimeScripts.followUp("and then")
    assertTrue(followUp.contains("\"text\":\"and then\""))
    assertFalse(followUp.contains("\"mode\""))
  }

  @Test fun optionalTextIsOmittedRatherThanSentBlank() {
    assertEquals("window.PiNative.resetSession({})", DurableRuntimeScripts.resetSession(null).substringBefore(".catch"))
    assertFalse(DurableRuntimeScripts.resetSession("   ").contains("\"handoff\""))
    assertTrue(DurableRuntimeScripts.resetSession("hand off").contains("\"handoff\":\"hand off\""))
    assertFalse(DurableRuntimeScripts.compactSession("   ").contains("\"instructions\""))
    assertTrue(DurableRuntimeScripts.compactSession(" keep the api ").contains("\"instructions\":\"keep the api\""))
  }

  /** The exporter renders markdown/json/jsonl and rejects anything else; nothing is invented. */
  @Test fun theExportAsksForAFormatTheExporterActuallyRenders() {
    assertTrue(DurableRuntimeScripts.exportSession("markdown").contains("\"format\":\"markdown\""))
    assertTrue(DurableRuntimeScripts.exportSession("jsonl").contains("\"format\":\"jsonl\""))
  }

  @Test fun aHostileNameOrIdCannotBreakOutOfTheCall() {
    val hostile = "x\") ; AndroidNative.postMessage('pwned') //"
    val script = DurableRuntimeScripts.renameSession("7", hostile)
    assertTrue(script.contains("\\\""))
    assertFalse(script.contains("\"); AndroidNative.postMessage('pwned') //"))
    assertEquals(1, script.split("catch(e=>AndroidNative.postMessage").size - 1)
  }
}
