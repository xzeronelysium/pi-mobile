package dev.pi.mobile.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeShellDeviceTest {
  @Test fun realAndroidShellHasSeparateStreamsAndAppIdentity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "shell-device-test").apply { mkdirs() }
    val result = NativeShell(root).execute("printf android-shell-ok; printf error-ok >&2; id; exit 7", mapOf("PATH" to "/system/bin", "HOME" to root.path), timeoutMillis = 3000)
    assertEquals(7, result.exitCode)
    assertTrue(result.stdout, result.stdout.startsWith("android-shell-ok"))
    assertTrue(result.stdout, result.stdout.contains("uid="))
    assertEquals("error-ok", result.stderr)
    assertFalse(result.timedOut)
  }
}
