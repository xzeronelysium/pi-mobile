package dev.pi.mobile.backend

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `shell.exec` reply the durable environment and the script tool read.
 *
 * The shell itself knows whether its per-stream byte cap actually cut a stream — it sets
 * [NativeShell.Result.stdoutTruncated]/[NativeShell.Result.stderrTruncated] only when it did. The
 * reply must carry that on the wire rather than leaving the consumer to infer truncation from the
 * cap's documented size. These pin the two flags onto the reply: present, false on a normal short
 * command, true exactly when the cap cut that stream — with every key the consumer already reads
 * left as it was (the change is additive).
 */
class NativeShellReplyTest {

  private fun shell(): Pair<NativeShell, File> {
    val tmp = System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")
    val root = Files.createTempDirectory(File(tmp).toPath(), "native-shell-reply-").toFile()
    return NativeShell(root, shellExecutable = "/bin/sh") to root
  }

  @Test fun aNormalShortCommandCarriesBothFlagsAndTheyAreFalse() {
    val (shell, root) = shell()
    try {
      val result = shell.execute("printf hello", environment = mapOf("PATH" to "/bin"))
      assertEquals(0, result.exitCode)

      val reply = shellExecReply(result)
      assertTrue("stdoutTruncated must be present", reply.has("stdoutTruncated"))
      assertTrue("stderrTruncated must be present", reply.has("stderrTruncated"))
      assertTrue("streamTruncated must be present", reply.has("streamTruncated"))
      assertFalse("a short command is not truncated", reply.getBoolean("stdoutTruncated"))
      assertFalse("and not on stderr either", reply.getBoolean("stderrTruncated"))
      assertFalse("and no live delivery was cut", reply.getBoolean("streamTruncated"))

      // Additive: everything the consumer already reads is still there, unchanged.
      assertEquals("hello", reply.getString("stdout"))
      assertEquals("", reply.getString("stderr"))
      assertEquals(0, reply.getInt("exitCode"))
      assertFalse(reply.getBoolean("timedOut"))
      assertFalse(reply.getBoolean("cancelled"))
      assertFalse(reply.getBoolean("drainIncomplete"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun aStreamTheByteCapCutIsReportedAsTruncatedRatherThanInferred() {
    val (shell, root) = shell()
    try {
      val result = shell.execute(
        "i=0; while [ \$i -lt 300 ]; do printf x; printf y >&2; i=\$((i+1)); done",
        environment = mapOf("PATH" to "/bin"),
        maxOutputBytes = 16,
      )
      assertEquals("x".repeat(16), result.stdout)
      assertEquals("y".repeat(16), result.stderr)

      val reply = shellExecReply(result)
      assertTrue("the cap cut stdout", reply.getBoolean("stdoutTruncated"))
      assertTrue("the cap cut stderr", reply.getBoolean("stderrTruncated"))
      assertEquals("the captured text is unchanged", "x".repeat(16), reply.getString("stdout"))
    } finally {
      root.deleteRecursively()
    }
  }
}
