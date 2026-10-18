package dev.pi.mobile.backend

import org.json.JSONObject

/**
 * The shell's limits, owned by the host that enforces them.
 *
 * These numbers used to be declared twice: here (as the values `shell.exec` ran with) and in the
 * runtime's `run_script` tool (`runtime/src/run-script-tool.mjs`), which kept its own copy for its
 * wording and its per-stream truncation report — and described the ceiling as a refusal when the
 * host was in fact silently clamping to it. Kotlin is the side that enforces the limits, so it is
 * the side that owns them; the runtime reads them through the `shell.capabilities` op
 * ([advertise]) and uses the advertised values for everything it reports.
 *
 * [MAX_OUTPUT_BYTES] is the per-stream capture cap `shell.exec` runs with; [DEFAULT_TIMEOUT_MILLIS]
 * is what a command that asks for no timeout gets, and [MAX_TIMEOUT_MILLIS] is the ceiling
 * [timeoutMillis] clamps every request to — the same value the runtime is told, so a request the
 * runtime believes was clamped to the ceiling really was.
 */
internal object ShellLimits {
  /** Per-stream capture cap handed to [NativeShell.execute] for every `shell.exec`. */
  const val MAX_OUTPUT_BYTES: Int = 256 * 1024

  /**
   * The live-delivery ceiling: how much output one invocation may forward through `onChunk` (and
   * therefore into the WebView and the spill file). The capture cap above only bounds the reply; a
   * command that floods stdout must not be able to flood the main thread for the whole deadline.
   */
  const val MAX_STREAM_BYTES: Long = 8L * 1024 * 1024

  /** What `shell.exec` uses when the caller asks for no timeout. */
  const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L

  /** The ceiling [timeoutMillis] clamps to, advertised as such. */
  const val MAX_TIMEOUT_MILLIS: Long = 120_000L

  /** The numbers the runtime reads instead of keeping a second copy of them. */
  fun advertise(): JSONObject = JSONObject()
    .put("maxOutputBytes", MAX_OUTPUT_BYTES)
    .put("maxStreamBytes", MAX_STREAM_BYTES)
    .put("defaultTimeoutMs", DEFAULT_TIMEOUT_MILLIS)
    .put("maxTimeoutMs", MAX_TIMEOUT_MILLIS)

  /** The timeout one command really runs with: the request, defaulted and clamped to the ceiling. */
  fun timeoutMillis(requested: Long?): Long =
    (requested ?: DEFAULT_TIMEOUT_MILLIS).coerceIn(1L, MAX_TIMEOUT_MILLIS)
}
