package dev.pi.mobile.backend

import org.junit.Assert.*
import org.junit.Test

/**
 * Console output from the runtime WebView, made safe to forward to logcat.
 *
 * The host now forwards `onConsoleMessage` so a JS-level provider failure is visible in `adb logcat`
 * instead of failing invisibly. That makes the tag a diagnostic channel, and a diagnostic channel
 * must not become a credential sink: the runtime logs messages, never header values, and this
 * redactor is the second line of defence for anything that reaches it anyway.
 */
class ConsoleRedactionTest {

    @Test fun masksBearerAndKeyedCredentials() {
        assertFalse(ConsoleRedaction.redact("GET https://api.anthropic.com failed with Authorization: Bearer sk-ant-api03-abcdef123456").contains("sk-ant-api03-abcdef123456"))
        assertFalse(ConsoleRedaction.redact("""{"apiKey":"sk-proj-abcdef1234567890"}""").contains("sk-proj-abcdef1234567890"))
        assertFalse(ConsoleRedaction.redact("x-api-key=abcdef1234567890 rejected").contains("abcdef1234567890"))
        assertFalse(ConsoleRedaction.redact("refresh_token: \"rt-abcdef1234567890\"").contains("rt-abcdef1234567890"))
        assertTrue(ConsoleRedaction.redact("Authorization: Bearer sk-ant-api03-abcdef123456").contains("[redacted]"))
    }

    @Test fun leavesOrdinaryDiagnosticsReadable() {
        val message = "Uncaught TypeError: Cannot read properties of undefined (reading 'streamSimple')"
        assertEquals(message, ConsoleRedaction.redact(message))
        assertEquals("tap send: provider request failed (connect)", ConsoleRedaction.redact("tap send: provider request failed (connect)"))
    }

    @Test fun boundsTheForwardedLine() {
        val redacted = ConsoleRedaction.redact("x".repeat(20_000))
        assertTrue(redacted.length <= ConsoleRedaction.MAX_LENGTH + 32)
        assertTrue(redacted.endsWith("[truncated]"))
    }

    @Test fun formatsOneGrepableLinePerMessage() {
        val line = ConsoleRedaction.format("error", "https://pi.local/index.html", 1, "Uncaught TypeError: boom")
        assertEquals("error https://pi.local/index.html:1 Uncaught TypeError: boom", line)
        // An unknown source still produces a line worth grepping.
        assertEquals("log pi.local:0 hello", ConsoleRedaction.format("log", "", 0, "hello"))
        assertEquals(ConsoleRedaction.TAG, "PiRuntimeConsole")
    }
}
