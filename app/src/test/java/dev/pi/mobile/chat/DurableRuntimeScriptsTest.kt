package dev.pi.mobile.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DurableRuntimeScriptsTest {
  @Test fun initCarriesTheWorkspaceAndProbeFlag() {
    val script = DurableRuntimeScripts.init("/data/user/0/dev.pi.mobile/files/workspace")
    assertTrue(script.startsWith("window.PiNative.init("))
    assertTrue(script.contains("\"probeShell\":true"))
    assertTrue(script.contains("\"workspace\":\"/data/user/0/dev.pi.mobile/files/workspace\""))
    // A rejected promise must surface as a host error event, never an unhandled rejection.
    assertTrue(script.contains("catch(e=>AndroidNative.postMessage"))
    assertTrue(script.contains("event:'error'"))
  }

  @Test fun initOmitsABlankWorkspaceRatherThanSendingOne() {
    assertFalse(DurableRuntimeScripts.init(null).contains("workspace"))
    assertFalse(DurableRuntimeScripts.init("   ").contains("workspace"))
  }

  @Test fun workspaceIsEscapedSoItCannotBreakOutOfTheCall() {
    val hostile = "/tmp/x\") ; AndroidNative.postMessage('pwned') //"
    val script = DurableRuntimeScripts.init(hostile)
    // The quote is escaped, so the payload stays inside the string literal.
    assertTrue(script.contains("\\\""))
    assertFalse(script.contains("); AndroidNative.postMessage('pwned') //"))
  }

  @Test fun sendEmbedsTheConfigAsAJavaScriptLiteral() {
    val config = JSONObject().put("requestId", "r1").put("prompt", "hi \"there\"")
    val script = DurableRuntimeScripts.send(config)
    assertTrue(script.startsWith("window.PiNative.send({"))
    assertTrue(script.contains("\"requestId\":\"r1\""))
    assertTrue(script.contains("hi \\\"there\\\""))
  }

  @Test fun lifecycleCallsTargetTheRuntime() {
    assertTrue(DurableRuntimeScripts.history().startsWith("window.PiNative.history()"))
    assertTrue(DurableRuntimeScripts.snapshot().startsWith("window.PiNative.snapshot()"))
    assertTrue(DurableRuntimeScripts.abort().startsWith("window.PiNative.abort()"))
    // close is fire-and-forget; it needs no error guard.
    assertEquals("window.PiNative.close()", DurableRuntimeScripts.close())
  }
}
