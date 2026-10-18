package dev.pi.mobile.backend

import dev.pi.mobile.chat.DurableRuntimeScripts
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flow-op script builders the host evaluates in the WebView.
 *
 * These five operations (`flows`, `runFlow`, `resumeFlow`, `flowStatus`, `flowThreads`) existed in
 * the runtime but had no builder here, so the whole shipped flow layer was unreachable from the app.
 * The argument shapes asserted below mirror exactly what `runtime/src/host.mjs` reads, field for
 * field, so a builder cannot drift from the op it evaluates.
 */
class DurableRuntimeFlowScriptsTest {
  @Test fun flowsTargetsTheRuntimeAndStaysGuarded() {
    val script = DurableRuntimeScripts.flows()
    assertTrue(script.startsWith("window.PiNative.flows()"))
    // A rejected promise must surface as a host error event, never an unhandled rejection.
    assertTrue(script.contains("catch(e=>AndroidNative.postMessage"))
    assertTrue(script.contains("event:'error'"))
  }

  @Test fun runFlowCarriesTheFlowAndItsOptionalArguments() {
    val bare = DurableRuntimeScripts.runFlow("workspace-script-probe")
    assertTrue(bare.startsWith("window.PiNative.runFlow({"))
    assertTrue(bare.contains("\"flow\":\"workspace-script-probe\""))
    // A blank thread id is omitted rather than sent as empty: the runtime then chooses one.
    assertFalse(bare.contains("threadId"))

    val full = DurableRuntimeScripts.runFlow(
      "note-check",
      threadId = "t-1",
      input = JSONObject().put("url", "https://example.com"),
      limits = JSONObject().put("maxSteps", 5),
    )
    assertTrue(full.contains("\"flow\":\"note-check\""))
    assertTrue(full.contains("\"threadId\":\"t-1\""))
    assertTrue(full.contains("\"url\":\"https://example.com\""))
    assertTrue(full.contains("\"maxSteps\":5"))

    // A whitespace-only thread id is treated exactly like an absent one.
    assertFalse(DurableRuntimeScripts.runFlow("probe", threadId = "   ").contains("threadId"))
  }

  @Test fun resumeFlowCarriesTheAnswerAndOmitsANullOne() {
    val string = DurableRuntimeScripts.resumeFlow("t-1", "yes")
    assertTrue(string.startsWith("window.PiNative.resumeFlow({"))
    assertTrue(string.contains("\"threadId\":\"t-1\""))
    assertTrue(string.contains("\"value\":\"yes\""))
    assertTrue(string.contains("catch(e=>"))

    val payload = DurableRuntimeScripts.resumeFlow("t-2", JSONObject().put("approved", true))
    assertTrue(payload.contains("\"approved\":true"))

    // A boolean false is a real answer and must travel; a null is not, and is omitted.
    assertTrue(DurableRuntimeScripts.resumeFlow("t-3", false).contains("\"value\":false"))
    assertFalse(DurableRuntimeScripts.resumeFlow("t-4", null).contains("\"value\""))
  }

  @Test fun flowStatusAndFlowThreadsCarryOnlyTheirBounds() {
    val status = DurableRuntimeScripts.flowStatus("t-1", limit = 5)
    assertTrue(status.startsWith("window.PiNative.flowStatus({"))
    assertTrue(status.contains("\"threadId\":\"t-1\""))
    assertTrue(status.contains("\"limit\":5"))

    // The runtime's own default applies when no limit is asked for.
    val unbound = DurableRuntimeScripts.flowStatus("t-2")
    assertFalse(unbound.contains("limit"))

    val threads = DurableRuntimeScripts.flowThreads(limit = 10)
    assertTrue(threads.startsWith("window.PiNative.flowThreads({"))
    assertTrue(threads.contains("\"limit\":10"))

    // No limit is an EMPTY query object, which the runtime reads as "no bound".
    val allThreads = DurableRuntimeScripts.flowThreads()
    assertTrue(allThreads.startsWith("window.PiNative.flowThreads({})"))
    assertFalse(allThreads.contains("limit"))
  }
}
