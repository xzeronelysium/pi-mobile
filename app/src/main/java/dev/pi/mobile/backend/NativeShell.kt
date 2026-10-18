package dev.pi.mobile.backend

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream

/**
 * Blocking adapter: invoke on a background thread, never the Android main thread.
 * [root] is a working directory, NOT a security sandbox; shell commands retain app privileges.
 * The environment is replaced, not inherited. Supply PATH/HOME/TMPDIR explicitly if needed.
 *
 * Output is a UTF-8 prefix bounded per stream (a cut code point may decode as a replacement); the
 * per-stream byte cap sets `stdoutTruncated`/`stderrTruncated`. Live delivery through `onChunk` is
 * bounded separately by `maxStreamBytes` (a shared budget across both streams), which sets
 * `streamTruncated`; the capture cap and the stream budget are independent so a chatty command
 * cannot flood the caller just because the shell is reading it. Completion follows the child's own
 * exit; if a descendant still holds a pipe write end after the child is gone the call returns after
 * a short bounded flush grace with `drainIncomplete` set, rather than waiting for EOF or reporting
 * a timeout. Launch failures throw IOException.
 * The deadline includes output draining after launch; launch and best-effort cleanup are not
 * hard real-time bounded. Detached/reparented descendants may escape cleanup, particularly
 * where Android restricts PID reflection or /proc. Blocked daemon drainers may outlive a call.
 */
class NativeShell(
    private val root: File,
    private val shellExecutable: String = "/system/bin/sh",
    /**
     * How a command's child process is started. The default is the real thing; the seam exists so a
     * test can hand back a `Process` whose child has exited while a stream deliberately never
     * reaches EOF — the descendant-holds-the-write-end case, reproduced deterministically and
     * without a real lingering process.
     */
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
) {
    /**
     * How to start a command.
     *
     * [Script] runs through the shell as one string; [Argv] starts `argv[0]` directly with the
     * rest as its arguments, so they reach the program unparsed. Pi Durable's `Shell.exec`
     * accepts both forms and the conformance suite distinguishes them.
     */
    sealed class Command {
        data class Script(val script: String) : Command()
        data class Argv(val argv: List<String>) : Command()
    }

    class Cancellation {
        @Volatile var isCancelled: Boolean = false
            private set
        fun cancel() { isCancelled = true }
    }

    /**
     * [drainIncomplete] is its own signal, separate from [timedOut] and [stdoutTruncated]: the child
     * exited on its own, but at least one drain had not reached EOF within the flush grace, so more
     * output may still be in flight from a process that inherited the pipe. Output already captured
     * is never discarded.
     */
    data class Result(val stdout: String, val stderr: String, val exitCode: Int?,
        val stdoutTruncated: Boolean = false, val stderrTruncated: Boolean = false,
        val timedOut: Boolean = false, val cancelled: Boolean = false,
        val drainIncomplete: Boolean = false, val streamTruncated: Boolean = false)

    /** Convenience for the common single-script case. */
    fun execute(command: String, environment: Map<String, String> = emptyMap(),
        maxOutputBytes: Int = 1024 * 1024, timeoutMillis: Long = 30_000,
        cancellation: Cancellation = Cancellation(), traceId: String? = null): Result =
        execute(Command.Script(command), environment, maxOutputBytes, timeoutMillis, cancellation, null, traceId)

    /**
     * @param onChunk receives every decoded chunk as it arrives, per stream, before any
     *   truncation — the caller owns [String] and byte-array lifetime. Called from the two
     *   drain threads, so it must tolerate concurrent invocation across streams.
     * @param traceId correlates this call's [NativeShellTrace] lines with the caller's own request
     *   id (the durable bridge passes its `requestId`); blank means one is generated.
     */
    fun execute(command: Command, environment: Map<String, String> = emptyMap(),
        maxOutputBytes: Int = 1024 * 1024, timeoutMillis: Long = 30_000,
        cancellation: Cancellation = Cancellation(),
        onChunk: ((String, ByteArray) -> Unit)? = null,
        traceId: String? = null,
        maxStreamBytes: Long = ShellLimits.MAX_STREAM_BYTES): Result {
        require(maxOutputBytes >= 0) { "maxOutputBytes must be non-negative" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxStreamBytes > 0) { "maxStreamBytes must be positive" }
        val id = traceId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString().substring(0, 8)
        if (cancellation.isCancelled || Thread.currentThread().isInterrupted) {
            NativeShellTrace.emit("id=$id phase=reply exit=- timedOut=false cancelled=true drainIncomplete=false reason=precancelled")
            return Result("", "", null, cancelled = true)
        }
        val builder = when (command) {
            is Command.Script -> ProcessBuilder(shellExecutable, "-c", command.script)
            is Command.Argv -> {
                require(command.argv.isNotEmpty()) { "argv must not be empty" }
                ProcessBuilder(command.argv)
            }
        }
        builder.directory(root)
        builder.environment().clear()
        builder.environment().putAll(environment)
        // The trace's zero point, and where the child is launched. The deadline itself starts after
        // launch (below), exactly as it did before, so this does not shorten a command's budget.
        val origin = System.nanoTime()
        NativeShellTrace.emit("id=$id phase=start deadlineMs=$timeoutMillis")
        val process = startProcess(builder)
        process.outputStream.close()
        // The per-stream capture cap bounds the reply. The stream budget bounds what is forwarded
        // live to the caller (and therefore the WebView and the spill file); it is separate because
        // a chatty command must not be able to flood the main thread just by being read.
        val budget = if (onChunk == null) null else StreamBudget(maxStreamBytes)
        val out = Capture(process.inputStream, maxOutputBytes, "stdout", onChunk, budget)
        val err = Capture(process.errorStream, maxOutputBytes, "stderr", onChunk, budget)
        val readers = listOf(out, err).map { capture ->
            Thread({ capture.drain() }, "native-shell-drain").apply { isDaemon = true; start() }
        }
        var interrupted = false
        try {
            val started = System.nanoTime()
            var timedOut = false
            var cancelled = false
            var exit: Int? = null
            var exitNanos = 0L
            val pid = pidOf(process)
            // Wait for the child itself. The child's exit is the completion event; a drain thread's
            // death is not, because a drain only ends at pipe EOF, and any descendant that inherited
            // the write end — a backgrounded child, a slow-to-reap process — can hold it open
            // indefinitely. Gating completion on the drains made a command that had already finished
            // and delivered all its output burn the whole deadline and report `timedOut`.
            while (true) {
                exit = exitCode(process)
                if (exit != null) {
                    exitNanos = System.nanoTime()
                    break
                }
                if (Thread.interrupted()) interrupted = true
                if (cancellation.isCancelled || interrupted) {
                    cancelled = true
                    terminate(process)
                    break
                }
                if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                    timedOut = true
                    terminate(process)
                    break
                }
                try { Thread.sleep(POLL_MILLIS) } catch (_: InterruptedException) { interrupted = true }
            }
            if (exit != null) NativeShellTrace.emit("id=$id phase=exit code=$exit pid=${pid ?: "-"} at=${millis(exitNanos, origin)}")
            // The child is gone, so this is never a timeout. Give the drains a short, bounded grace
            // to reach EOF and hand over their last chunks, then return and report honestly rather
            // than spinning on a write end someone else still holds. The grace is a ceiling, not a
            // fixed delay: a normal command's pipes close at exit, its drains finish in about a poll
            // interval, and the loop leaves as soon as both are complete.
            if (exit != null) {
                val graceDeadline = System.nanoTime() + FLUSH_GRACE_MILLIS * 1_000_000
                while (!(out.isComplete() && err.isComplete()) && System.nanoTime() < graceDeadline) {
                    if (Thread.interrupted()) interrupted = true
                    if (cancellation.isCancelled || interrupted) { cancelled = true; break }
                    try { Thread.sleep(POLL_MILLIS) } catch (_: InterruptedException) { interrupted = true }
                }
            }
            val stdout = out.snapshot()
            val stderr = err.snapshot()
            val drainIncomplete = !(out.isComplete() && err.isComplete())
            val streamTruncated = out.streamTruncated || err.streamTruncated
            val reason = if (cancelled) "cancelled" else if (timedOut) "timeout" else "-"
            NativeShellTrace.emit(
                "id=$id phase=reply exit=${exit ?: "-"} pid=${pid ?: "-"} timedOut=$timedOut cancelled=$cancelled " +
                    "drainIncomplete=$drainIncomplete streamTruncated=$streamTruncated exitAt=${millis(exitNanos, origin)} " +
                    "stdoutLast=${millis(out.lastChunkNanos, origin)} stderrLast=${millis(err.lastChunkNanos, origin)} " +
                    "stdoutEof=${millis(out.eofNanos, origin)} stderrEof=${millis(err.eofNanos, origin)} " +
                    "replyAt=${millis(System.nanoTime(), origin)} deadlineMs=$timeoutMillis reason=$reason",
            )
            return Result(stdout.text, stderr.text, exit, stdout.truncated, stderr.truncated, timedOut, cancelled, drainIncomplete, streamTruncated)
        } finally {
            if (exitCode(process) == null) terminate(process)
            runCatching { process.outputStream.close() }
            // Closing a stream blocked in read can itself block on some JVMs, so the closes run on
            // their own daemon thread. This is also what releases an abandoned drain when the child
            // exited but a descendant still holds the write end: the drain's read sees EOF or errors
            // out as soon as the last holder is gone. A call may therefore leave up to two daemon
            // drain threads alive until then — one per stream, holding a pipe fd each and nothing
            // else — but they always exit at the real EOF and cannot outlive the app process.
            Thread({
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }, "native-shell-close").apply { isDaemon = true; start() }
            readers.forEach { it.interrupt() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun exitCode(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) { null }

    /** A monotonic millisecond offset from [origin] for the trace, or `-` if the phase never happened. */
    private fun millis(nanos: Long, origin: Long): String =
        if (nanos == 0L) "-" else ((nanos - origin) / 1_000_000).toString()

    /**
     * The child's native PID when reflection can reach it — `Process.pid()` from API 26, else the
     * `pid` field — for the trace and for the kill. Best effort and never throws; null where Android
     * restricts PID reflection.
     */
    private fun pidOf(process: Process): Int? = runCatching {
        (Process::class.java.getMethod("pid").invoke(process) as Number).toInt()
    }.getOrNull() ?: runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        (field.get(process) as Number).toInt()
    }.getOrNull()

    // Android API 24 has no ProcessHandle. /proc access and PID discovery are best effort.
    private fun terminate(process: Process) {
        val pid = pidOf(process)
        if (pid != null) {
            val parents = mutableMapOf<Int, Int>()
            File("/proc").listFiles()?.forEach { entry ->
                val child = entry.name.toIntOrNull() ?: return@forEach
                runCatching {
                    val stat = File(entry, "stat").readText()
                    val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                    parents[child] = fields[1].toInt()
                }
            }
            val descendants = linkedSetOf(pid)
            do {
                val previous = descendants.size
                parents.forEach { (child, parent) -> if (parent in descendants) descendants.add(child) }
            } while (descendants.size > previous)
            runCatching {
                // Prefer the `kill` binary next to the shell: spawning `sh -c` under load can
                // outlast the wait below, in which case the tree was never signalled at all.
                val direct = File(File(shellExecutable).parentFile, "kill")
                val targets = descendants.toList().asReversed().map { it.toString() }
                val builder = if (direct.canExecute()) {
                    ProcessBuilder(listOf(direct.path, "-9") + targets)
                } else {
                    ProcessBuilder(shellExecutable, "-c", "kill -9 " + targets.joinToString(" "))
                }
                val killer = builder.start()
                killer.outputStream.close()
                val deadline = System.nanoTime() + 1_000_000_000
                while (exitCode(killer) == null && System.nanoTime() < deadline) Thread.sleep(5)
                killer.destroy()
                killer.inputStream.close()
                killer.errorStream.close()
            }
        }
        runCatching { process.destroy() }
        runCatching { Process::class.java.getMethod("destroyForcibly").invoke(process) }
    }

    private data class Captured(val text: String, val truncated: Boolean)
    private class Capture(
        private val input: InputStream,
        private val limit: Int,
        private val stream: String,
        private val onChunk: ((String, ByteArray) -> Unit)?,
        private val budget: StreamBudget?,
    ) {
        private val bytes = ByteArrayOutputStream()
        private var truncated = false
        private var complete = false

        /** Monotonic time of the last chunk and of EOF; read only by the invocation trace. */
        @Volatile var lastChunkNanos: Long = 0L
        @Volatile var eofNanos: Long = 0L

        /** True when the shared stream budget cut at least one forwarded chunk. */
        @Volatile var streamTruncated: Boolean = false

        fun drain() {
            val buffer = ByteArray(8192)
            try {
                input.use {
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) {
                            eofNanos = System.nanoTime()
                            synchronized(this) { complete = true }
                            break
                        }
                        if (count > 0) {
                            lastChunkNanos = System.nanoTime()
                            if (onChunk != null) {
                                // Forward only what the shared budget still allows. The capture below
                                // is bounded separately, so a caller that reads the reply still gets
                                // its per-stream prefix even when live delivery is exhausted.
                                val allowed = budget?.take(count) ?: count
                                if (allowed < count) streamTruncated = true
                                if (allowed > 0) onChunk.invoke(stream, buffer.copyOf(allowed))
                            }
                        }
                        synchronized(this) {
                            val keep = minOf(count, limit - bytes.size())
                            bytes.write(buffer, 0, keep)
                            if (keep < count) truncated = true
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // A failed read means the drain never saw EOF; `drainIncomplete` reports that. It is
                // not the byte cap, so it does not set `truncated`.
            }
        }

        /** True once the stream reached EOF. A false value after the child exited is an incomplete drain. */
        @Synchronized fun isComplete(): Boolean = complete

        @Synchronized fun snapshot() = Captured(bytes.toByteArray().toString(Charsets.UTF_8), truncated)
    }

    /**
     * The shared live-delivery budget for one invocation.
     *
     * Both drains draw from it, so a command that floods one stream cannot starve the other of the
     * whole budget. The capture cap is separate and per stream; this only bounds what `onChunk`
     * forwards, which is the path that reaches the WebView and the spill file.
     */
    private class StreamBudget(private val limit: Long) {
        private var remaining = limit

        @Synchronized fun take(count: Int): Int {
            if (remaining <= 0) return 0
            val keep = minOf(count.toLong(), remaining).toInt()
            remaining -= keep
            return keep
        }
    }

    companion object {
        /**
         * How long after the child exits the drains may keep waiting for EOF and their last chunks
         * before the call reports. A ceiling, not a fixed delay: a normal command's pipes close when
         * it exits, so its drains finish within about a poll interval and the grace costs nothing;
         * it is only paid when some other process still holds a write end. 250 ms is many times the
         * sub-millisecond pipe-close propagation on Linux and the 10 ms poll, yet small enough that
         * a lingering descendant delays the reply by a quarter second instead of the whole deadline.
         */
        const val FLUSH_GRACE_MILLIS = 250L

        /** Poll interval for the child wait and the drain grace. */
        private const val POLL_MILLIS = 10L
    }
}
