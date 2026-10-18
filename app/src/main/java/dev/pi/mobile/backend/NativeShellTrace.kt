package dev.pi.mobile.backend

/**
 * The shell invocation trace the on-device completion audit asked for, made cheap.
 *
 * The audit saw external commands print output and still time out, and could not tell which phase
 * of the call was missing — launch, child exit, the last chunk of a stream, EOF on a stream, or the
 * reply itself. This records exactly those phases, monotonic and relative to shell start, on one
 * grepable channel: `adb logcat -s PiRuntimeConsole | grep -F 'shell.trace'`.
 *
 * An invocation writes at most three short lines — `phase=start`, `phase=exit`, `phase=reply` (plus
 * `reason=precancelled` for a call refused before launch). The reply line carries every phase
 * timestamp, so a reproduction answers "which phase never happened" at a glance instead of needing
 * a second run. Nothing secret or large is recorded: a correlation id, phase names, monotonic
 * millisecond deltas, an exit code and boolean outcome flags — never command text, environment
 * values or output bytes.
 *
 * [sink] is installed by the app to forward to logcat ([NativeRuntimeHost]); it stays null on the
 * plain JVM, so unit tests neither log nor depend on Android's `Log`, and emission is a no-op.
 */
object NativeShellTrace {
    /** The app's existing single console tag, so discovery is still one `logcat -s`. */
    const val TAG = ConsoleRedaction.TAG

    /** Every line begins with this, making `grep -F shell.trace` the whole filter. */
    const val PREFIX = "shell.trace"

    /** Installed once by the app; null means "discard", which is the unit-test default. */
    @Volatile var sink: ((String) -> Unit)? = null

    fun emit(line: String) { sink?.invoke("$PREFIX $line") }
}
