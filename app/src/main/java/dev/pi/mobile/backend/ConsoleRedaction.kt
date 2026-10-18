package dev.pi.mobile.backend

/**
 * WebView console output, made safe to forward to logcat.
 *
 * The host forwards `WebChromeClient.onConsoleMessage`, so a JS-level failure inside the runtime —
 * an uncaught TypeError, a refused provider request, a rejected bridge call — becomes visible in
 * `adb logcat -s PiRuntimeConsole` instead of failing invisibly on the device.
 *
 * That makes the tag a diagnostic channel, and a diagnostic channel must not become a credential
 * sink. Two things keep it clean: the runtime logs messages and never header values, and this
 * redactor masks anything credential-shaped that reaches it anyway (a provider SDK, or a future
 * log line). Ordinary diagnostics are left exactly as they were.
 */
object ConsoleRedaction {
    /** `adb logcat -s PiRuntimeConsole` is the whole discovery story. */
    const val TAG = "PiRuntimeConsole"

    /** One logcat line is capped, so a runaway console storm cannot flood the buffer. */
    const val MAX_LENGTH = 2_000

    private const val TRUNCATED = "…[truncated]"

    /** `Authorization: Bearer …`, `Basic …`, and the bare token after a scheme word. */
    private val SCHEMED = Regex("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}")

    /** `apiKey: "…"`, `x-api-key=…`, `refresh_token: …` and friends. */
    private val KEYED = Regex(
        "(?i)\\b(authorization|x-api-key|api[-_]?key|apikey|access[-_]?token|refresh[-_]?token|client[-_]?secret|password|passwd)\"?'?\\s*[:=]\\s*\"?'?([A-Za-z0-9._~+/=-]{8,})",
    )

    /** Mask credentials, then bound the line. Never throws, whatever it is handed. */
    fun redact(message: String): String {
        val masked = KEYED.replace(SCHEMED.replace(message) { match -> "${match.groupValues[1]} [redacted]" }) { match ->
            "${match.groupValues[1]}: [redacted]"
        }
        return if (masked.length <= MAX_LENGTH) masked else masked.take(MAX_LENGTH) + TRUNCATED
    }

    /** The single logcat line for one console message: level, source position, redacted text. */
    fun format(level: String, source: String, line: Int, message: String): String {
        val where = if (source.isNullOrBlank()) "pi.local" else source
        return "$level $where:$line ${redact(message)}"
    }
}
