package dev.pi.mobile.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the host tears down in when the app drops a runtime.
 *
 * The durable runtime owns resources only it can release — MCP connections, the event stream, the
 * agent harness — and it releases them from `window.PiNative.close()`
 * (`runtime/src/host.mjs`). So the host asks JS to close itself, gives it a bounded drain window,
 * and releases the native side (WebView included) when the window expires. The JS teardown may still
 * be calling back through the bridge while it runs, which is why the release is deferred at all, and
 * why it has to happen exactly once: a second `destroy()` on the same WebView is a crash.
 */
class RuntimeTeardownTest {
  private val calls = mutableListOf<String>()
  private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
  private var releases = 0

  private fun teardown() = RuntimeTeardown(
    requestRuntimeClose = { calls += "js-close" },
    scheduleRelease = { delay, action -> calls += "schedule"; scheduled += delay to action },
    release = { calls += "release"; releases += 1 },
  )

  @Test fun theRuntimeIsAskedToCloseFirstAndTheNativeReleaseWaitsForTheWindow() {
    val teardown = teardown()
    assertFalse("nothing has claimed the teardown yet", teardown.closing)

    assertTrue(teardown.close())
    assertEquals(listOf("js-close", "schedule"), calls)
    assertEquals("the release must not race the JS teardown", 0, releases)
    assertEquals(1, scheduled.size)

    val (delay, action) = scheduled.single()
    assertEquals(RuntimeTeardown.DRAIN_MILLIS, delay)
    assertTrue("the drain window must be bounded", delay in 1..2_000L)

    action()
    assertEquals(1, releases)
    assertEquals(listOf("js-close", "schedule", "release"), calls)
  }

  @Test fun theTeardownIsOneShot() {
    val teardown = teardown()
    assertTrue(teardown.close())
    assertTrue(teardown.closing)
    assertFalse("a second close is refused, not repeated", teardown.close())
    assertEquals(listOf("js-close", "schedule"), calls)

    scheduled.single().second()
    assertEquals(1, releases)
    // A window that fires twice (or after a second close) still releases exactly once.
    scheduled.single().second()
    assertEquals(1, releases)
  }

  @Test fun theWindowIsArmedEvenWhenTheRuntimeNeverAnswers() {
    val teardown = teardown()
    teardown.close()
    // Nothing else happens — no JS callback exists to wait for — so the window alone must release.
    scheduled.single().second()
    assertEquals(1, releases)
  }
}
