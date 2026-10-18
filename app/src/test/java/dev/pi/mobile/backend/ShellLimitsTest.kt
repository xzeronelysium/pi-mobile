package dev.pi.mobile.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's limits have exactly one owner: this host.
 *
 * The values are advertised to the runtime through the `shell.capabilities` op (`runtime`'s
 * `run_script` tool reads them instead of hard-coding a second copy) and applied by the host itself,
 * so what the runtime is told is what the host enforces. These tests pin both halves: the advertised
 * numbers, and that the host's own clamp is the advertised ceiling.
 */
class ShellLimitsTest {
  @Test fun theAdvertisedLimitsAreTheOnesTheHostEnforces() {
    val advertised = ShellLimits.advertise()
    assertEquals(ShellLimits.MAX_OUTPUT_BYTES, advertised.getInt("maxOutputBytes"))
    assertEquals(ShellLimits.MAX_STREAM_BYTES, advertised.getLong("maxStreamBytes"))
    assertEquals(ShellLimits.DEFAULT_TIMEOUT_MILLIS, advertised.getLong("defaultTimeoutMs"))
    assertEquals(ShellLimits.MAX_TIMEOUT_MILLIS, advertised.getLong("maxTimeoutMs"))
  }

  @Test fun theAdvertisedNumbersAreTheHostsActualContract() {
    // The runtime's own wording and its per-stream truncation report are derived from these, so a
    // change here is a contract change and must be deliberate.
    assertEquals(256 * 1024, ShellLimits.MAX_OUTPUT_BYTES)
    assertEquals(8L * 1024 * 1024, ShellLimits.MAX_STREAM_BYTES)
    assertEquals(30_000L, ShellLimits.DEFAULT_TIMEOUT_MILLIS)
    assertEquals(120_000L, ShellLimits.MAX_TIMEOUT_MILLIS)
  }

  @Test fun aRequestedTimeoutIsClampedToTheAdvertisedCeilingAndFloor() {
    assertEquals(ShellLimits.DEFAULT_TIMEOUT_MILLIS, ShellLimits.timeoutMillis(null))
    assertEquals(ShellLimits.DEFAULT_TIMEOUT_MILLIS, ShellLimits.timeoutMillis(30_000L))
    assertEquals(1_500L, ShellLimits.timeoutMillis(1_500L))
    assertEquals(ShellLimits.MAX_TIMEOUT_MILLIS, ShellLimits.timeoutMillis(300_000L))
    assertEquals(1L, ShellLimits.timeoutMillis(0L))
    assertEquals(1L, ShellLimits.timeoutMillis(-5L))
  }

  @Test fun theLimitsTravelAsPlainNumbersTheRuntimeCanRead() {
    val advertised: JSONObject = ShellLimits.advertise()
    for (key in listOf("maxOutputBytes", "maxStreamBytes", "defaultTimeoutMs", "maxTimeoutMs")) {
      assertTrue("$key must be a number", advertised.get(key) is Number)
    }
  }
}
