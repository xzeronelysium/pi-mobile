package dev.pi.mobile.backend

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class NativeShellTest {
    private lateinit var root: File
    private lateinit var shell: NativeShell

    @Before fun setUp() {
        val tmp = System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")
        root = Files.createTempDirectory(File(tmp).toPath(), "native-shell-").toFile()
        shell = NativeShell(root, shellExecutable = "/bin/sh")
    }

    @After fun tearDown() { NativeShellTrace.sink = null; root.deleteRecursively() }

    /** A shell whose child is [process]; the process builder is never actually started. */
    private fun shellWith(process: Process): NativeShell =
        NativeShell(root, shellExecutable = "/bin/sh", startProcess = { process })

    /** A child that has already exited, reporting [exit]. */
    private class FakeProcess(
        private val stdout: InputStream,
        private val stderr: InputStream,
        private val exit: () -> Int,
    ) : Process() {
        private val stdin = java.io.ByteArrayOutputStream()
        @Volatile var destroyed = false
        override fun getOutputStream(): java.io.OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = exit.invoke()
        override fun exitValue(): Int = exit.invoke()
        override fun destroy() { destroyed = true }
    }

    /**
     * Delivers [prefix], then blocks until closed: it never reaches EOF on its own, standing in for
     * a pipe whose write end a descendant still holds after the child itself exited.
     */
    private class HeldPipe(private val prefix: ByteArray) : InputStream() {
        private val lock = Object()
        private var position = 0
        private var closed = false
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) {
                if (position < prefix.size) {
                    val count = minOf(length, prefix.size - position)
                    System.arraycopy(prefix, position, buffer, offset, count)
                    position += count
                    return count
                }
                try { while (!closed) lock.wait() } catch (_: InterruptedException) { /* fall through to EOF */ }
                return -1
            }
        }
        override fun close() { synchronized(lock) { closed = true; lock.notifyAll() } }
    }

    /** Delivers [prefix], waits [delayMillis], then reaches EOF: output still in flight at child exit. */
    private class DelayedEof(private val prefix: ByteArray, private val delayMillis: Long) : InputStream() {
        private var position = 0
        private var eof = false
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < prefix.size) {
                val count = minOf(length, prefix.size - position)
                System.arraycopy(prefix, position, buffer, offset, count)
                position += count
                return count
            }
            if (eof) return -1
            Thread.sleep(delayMillis)
            eof = true
            return -1
        }
    }

    @Test fun executesShellInExplicitWorkingDirectory() {
        val result = shell.execute("pwd; printf hello; printf problem >&2; exit 7")
        assertEquals(root.canonicalPath + "\nhello", result.stdout)
        assertEquals("problem", result.stderr)
        assertEquals(7, result.exitCode)
    }

    @Test fun environmentIsExplicitNotInherited() {
        val result = shell.execute("printf '%s|%s' \"$" + "TOKEN\" \"$" + "HOME\"",
            environment = mapOf("TOKEN" to "controlled"))
        assertEquals("controlled|", result.stdout)
    }

    @Test fun boundsEachOutputStreamWhileDraining() {
        val result = shell.execute("i=0; while [ $" + "i -lt 3000 ]; do printf x; printf y >&2; i=$((i+1)); done",
            maxOutputBytes = 128)
        assertEquals("x".repeat(128), result.stdout)
        assertEquals("y".repeat(128), result.stderr)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertEquals(0, result.exitCode)
    }

    @Test(timeout = 4000) fun timeoutReturnsPartialOutputPromptly() {
        val start = System.nanoTime()
        val result = shell.execute("printf ready; exec /bin/sleep 2", timeoutMillis = 100)
        assertTrue(result.timedOut)
        assertFalse(result.cancelled)
        assertEquals("ready", result.stdout)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test(timeout = 4000) fun tokenCancelsRunningCommand() {
        val cancellation = NativeShell.Cancellation()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<NativeShell.Result> {
                shell.execute("printf ready > started; exec /bin/sleep 2", cancellation = cancellation)
            }
            val deadline = System.nanoTime() + 1_000_000_000
            while (!File(root, "started").exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(File(root, "started").exists())
            cancellation.cancel()
            assertTrue(result.get(1, java.util.concurrent.TimeUnit.SECONDS).cancelled)
        } finally { executor.shutdownNow() }
    }

    @Test(timeout = 10_000) fun timeoutKillsDescendantThatIgnoresTerm() {
        // The descendant ignores TERM, so only the `-9` tree kill can stop it, and it keeps a 3 s
        // sleep before writing its marker so a broken kill always shows up.
        //
        // The marker has to mean "the descendant outlived its own sleep", not merely "the script ran
        // another statement": the tree kill signals one pid at a time, so when it reaches the sleep
        // (the deepest process) before the descendant shell, that shell reaps the killed sleep and
        // runs the next statement before its own signal lands. Measured under load: `stderr=[Killed]`
        // and a marker written ~300 ms into a call whose kill had already fired, with the shell itself
        // gone by the time the call returned (and never reaching its own completion). `|| exit 0` keeps
        // that window from reading as survival. No timing budget is relaxed: the 100 ms deadline, the
        // 3 s sleep and the 3.5 s watch are unchanged; the harness bound above is 10 s because the
        // measured worst case is ~5.7 s (the kill path waits up to 1 s for its killer, plus the watch).
        val result = shell.execute(
            "/bin/sh -c 'trap \"\" TERM; echo $$ > child.pid; /bin/sleep 3 || exit 0; echo leaked > leaked' & wait",
            timeoutMillis = 100,
        )
        assertTrue(result.timedOut)

        // A pass is only meaningful if the descendant really ran and really slept: prove it wrote its
        // pid first, so "nothing was killed" cannot pass for "the kill worked".
        val started = System.nanoTime() + 1_000_000_000
        while (!File(root, "child.pid").exists() && System.nanoTime() < started) Thread.sleep(20)
        assertTrue("Descendant never started, so nothing was killed", File(root, "child.pid").exists())

        val deadline = System.nanoTime() + 3_500_000_000
        while (System.nanoTime() < deadline && !File(root, "leaked").exists()) Thread.sleep(50)
        assertFalse("Descendant survived timeout", File(root, "leaked").exists())
    }

    @Test(timeout = 4000) fun interruptionCancelsAndPreservesInterruptFlag() {
        var result: NativeShell.Result? = null
        var interrupted = false
        val worker = Thread {
            result = shell.execute("printf ready > started; exec /bin/sleep 2")
            interrupted = Thread.currentThread().isInterrupted
        }
        worker.start()
        val deadline = System.nanoTime() + 1_000_000_000
        while (!File(root, "started").exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(File(root, "started").exists())
        worker.interrupt()
        worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue(result?.cancelled == true)
        assertTrue(interrupted)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonPositiveTimeout() {
        shell.execute("true", timeoutMillis = 0)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeOutputLimitBeforeStarting() {
        shell.execute("printf bad > side-effect", maxOutputBytes = -1)
    }

    @Test fun preCancelledCommandNeverStarts() {
        val cancellation = NativeShell.Cancellation().apply { cancel() }
        val result = shell.execute("printf bad > side-effect", cancellation = cancellation)
        assertTrue(result.cancelled)
        assertNull(result.exitCode)
        assertFalse(File(root, "side-effect").exists())
    }

    @Test(timeout = 4000) fun inheritedPipesCannotExtendDeadline() {
        val start = System.nanoTime()
        // Keep the parent alive briefly so drainers enter read before its pipes are inherited.
        val result = shell.execute("printf ready; /bin/sleep 2 & /bin/sleep 0.05; exit 0", timeoutMillis = 100)
        // The shell exited on its own, so this is not a timeout: the leftover `/bin/sleep 2` holds
        // the pipe's write end past the deadline, which the flush grace reports as an incomplete
        // drain over the output already delivered.
        assertFalse(result.timedOut)
        assertFalse(result.cancelled)
        assertTrue(result.drainIncomplete)
        assertEquals(0, result.exitCode)
        assertEquals("ready", result.stdout)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test(timeout = 5000) fun exitedChildWithAHeldPipeReturnsPromptlyInsteadOfTimingOut() {
        // The regression the on-device audit found: the child exited and its output was delivered,
        // but a pipe write end stays open, so drain-thread death never comes. Gating completion on
        // the drains made this report timedOut after the whole deadline.
        val start = System.nanoTime()
        val stdout = HeldPipe("PROBE_OUT".toByteArray())
        val result = shellWith(FakeProcess(stdout, ByteArrayInputStream(ByteArray(0)), exit = { 0 }))
            .execute("ignored by the fake", timeoutMillis = 2_000)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertFalse("reported timedOut for a process that exited", result.timedOut)
        assertFalse(result.cancelled)
        assertEquals(0, result.exitCode)
        assertEquals("PROBE_OUT", result.stdout)
        assertTrue("incomplete drain was not signalled", result.drainIncomplete)
        assertFalse(result.stdoutTruncated)
        assertTrue("elapsed=${elapsed}ms against a 2000ms deadline", elapsed < 1_000)
    }

    @Test(timeout = 4000) fun exitedChildDrainsTrailingOutputBeforeReportingComplete() {
        // Mirror case: output still in flight when the child exits is still captured, and a drain
        // that reaches EOF inside the grace is complete — not truncation, not an incomplete drain.
        val result = shellWith(FakeProcess(
            DelayedEof("TRAILING".toByteArray(), delayMillis = 100),
            ByteArrayInputStream("WARNED".toByteArray()),
            exit = { 0 },
        )).execute("ignored by the fake", timeoutMillis = 5_000)
        assertEquals(0, result.exitCode)
        assertEquals("TRAILING", result.stdout)
        assertEquals("WARNED", result.stderr)
        assertFalse(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
        assertFalse("drain finished inside the grace", result.drainIncomplete)
        assertFalse(result.timedOut)
    }

    @Test(timeout = 4000) fun liveChildStillTimesOutAndIsTerminated() {
        // Mirror case: a child that is genuinely still running at the deadline still times out and
        // is still terminated — the exit-as-completion change must not weaken that path.
        val process = FakeProcess(
            HeldPipe("READY".toByteArray()),
            ByteArrayInputStream(ByteArray(0)),
            exit = { throw IllegalThreadStateException("still running") },
        )
        val start = System.nanoTime()
        val result = shellWith(process).execute("ignored by the fake", timeoutMillis = 150)
        assertTrue(result.timedOut)
        assertFalse(result.cancelled)
        assertNull(result.exitCode)
        assertEquals("READY", result.stdout)
        assertTrue("process tree was not signalled", process.destroyed)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
    }

    @Test(timeout = 4000) fun traceRecordsEveryPhaseWithoutLeakingTheCommand() {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        NativeShellTrace.sink = { lines.add(it) }
        val secret = "printf TOPSECRET_PAYLOAD"
        val result = shellWith(FakeProcess(
            DelayedEof("OUT".toByteArray(), delayMillis = 0),
            ByteArrayInputStream(ByteArray(0)),
            exit = { 3 },
        )).execute(secret, timeoutMillis = 1_000, traceId = "trace-1")
        assertEquals(3, result.exitCode)

        // Exactly three short lines per invocation: start, child exit, reply — never a flood, and
        // the reply carries the phase timestamps the audit needs to spot a missing phase.
        assertEquals(lines.joinToString("\n"), 3, lines.size)
        assertTrue(lines.joinToString("\n"), lines.any { it == "shell.trace id=trace-1 phase=start deadlineMs=1000" })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("phase=exit code=3") })
        val reply = lines.single { it.contains("phase=reply") }
        for (expected in listOf("exit=3", "pid=", "timedOut=false", "cancelled=false", "drainIncomplete=false",
            "exitAt=", "stdoutLast=", "stderrLast=", "stdoutEof=", "stderrEof=", "replyAt=", "reason=-")) {
            assertTrue("reply line missing $expected: $reply", reply.contains(expected))
        }
        assertFalse("trace leaked the command text", lines.joinToString("\n").contains("TOPSECRET_PAYLOAD"))
    }

    @Test(timeout = 4000) fun argvRunsWithoutShellParsing() {
        val hostile = "it's \$(touch pwned) `touch pwned` *"
        val result = shell.execute(NativeShell.Command.Argv(listOf("/bin/echo", hostile)))
        assertEquals(0, result.exitCode)
        assertEquals("$hostile\n", result.stdout)
        assertFalse(File(root, "pwned").exists())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyArgv() {
        shell.execute(NativeShell.Command.Argv(emptyList()))
    }

    @Test(timeout = 4000) fun streamingDeliversEveryChunkPerStream() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val result = shell.execute(
            NativeShell.Command.Script("printf out-one; printf err-one >&2; printf out-two"),
            environment = mapOf("PATH" to "/bin"),
            onChunk = { stream, bytes ->
                val text = String(bytes, Charsets.UTF_8)
                synchronized(this) {
                    if (stream == "stdout") stdout.append(text) else stderr.append(text)
                }
            },
        )
        assertEquals(0, result.exitCode)
        assertEquals("out-oneout-two", stdout.toString())
        assertEquals("err-one", stderr.toString())
    }

    @Test(timeout = 4000) fun streamBudgetBoundsLiveDeliveryWithoutTruncatingTheReply() {
        val delivered = StringBuilder()
        val result = shell.execute(
            NativeShell.Command.Script("printf 0123456789abcdefghij"),
            environment = mapOf("PATH" to "/bin"),
            maxOutputBytes = 100,
            maxStreamBytes = 8,
            onChunk = { _, bytes -> synchronized(this) { delivered.append(String(bytes, Charsets.UTF_8)) } },
        )
        assertEquals(0, result.exitCode)
        assertEquals("01234567", delivered.toString())
        assertTrue(result.streamTruncated)
        assertFalse(result.stdoutTruncated)
        assertEquals("0123456789abcdefghij", result.stdout)
    }
}
