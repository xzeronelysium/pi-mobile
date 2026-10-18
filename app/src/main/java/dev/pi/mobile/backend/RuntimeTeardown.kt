package dev.pi.mobile.backend

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The order the runtime host tears down in.
 *
 * The durable runtime owns resources only it can release — MCP connections, the event stream, the
 * agent harness — and `runtime/src/host.mjs`'s `close()` is where it releases them. Nothing used to
 * evaluate that call: the host destroyed the WebView and every promise in flight inside it was
 * abandoned, with the runtime's own teardown never running at all.
 *
 * So the host asks the runtime to close itself first, and releases the native side (the WebView
 * included) when a bounded drain window expires. The window exists because the JS teardown is
 * asynchronous and may still be calling back through the bridge while it runs — a step that flushes
 * durable state needs the bridge to answer — while an unbounded wait is not an option for a running
 * app: a wedged runtime must not hold the Activity open.
 *
 * The teardown is one-shot by construction, from one [RuntimeTeardown] per host: a second close is
 * refused, and the single release is claimed by whichever comes first, so a WebView can never be
 * destroyed twice.
 */
internal class RuntimeTeardown(
  /** Ask JS to close itself. Dispatched to whatever thread the WebView lives on. */
  private val requestRuntimeClose: () -> Unit,
  /** Arm the drain window: run [release] no earlier than [delayMillis]. */
  private val scheduleRelease: (delayMillis: Long, release: () -> Unit) -> Unit,
  /** Release the native side and destroy the WebView. Called exactly once. */
  private val release: () -> Unit,
) {
  private val claimed = AtomicBoolean(false)
  private val released = AtomicBoolean(false)

  /** True once the teardown has begun: no new work is accepted and no event is emitted from here on. */
  val closing: Boolean get() = claimed.get()

  /**
   * Begin the teardown. Returns false when one is already under way.
   *
   * Order is the point: the runtime is asked to close before the WebView is destroyed under it.
   */
  fun close(): Boolean {
    if (!claimed.compareAndSet(false, true)) return false
    requestRuntimeClose()
    scheduleRelease(DRAIN_MILLIS) {
      if (released.compareAndSet(false, true)) release()
    }
    return true
  }

  companion object {
    /**
     * How long the JS runtime gets to finish `PiNative.close()` before the native side goes.
     *
     * Sized for the teardown the runtime actually performs (closing MCP connections, stopping the
     * event stream, flushing the conversation through the bridge): comfortably longer than that,
     * while short enough that a wedged runtime delays nothing the user can perceive. The app has
     * already stopped using the runtime at this point, and a process that dies inside the window
     * simply never runs the JS teardown — see the host's `close()`.
     */
    const val DRAIN_MILLIS: Long = 500L
  }
}
