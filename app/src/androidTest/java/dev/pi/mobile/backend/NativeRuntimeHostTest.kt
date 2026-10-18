package dev.pi.mobile.backend

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeRuntimeHostTest {
  @Test fun bundledPiImportsAndCommitsOnDevice() {
    val ready = CountDownLatch(1)
    var result: JSONObject? = null
    lateinit var host: NativeRuntimeHost
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      host = NativeRuntimeHost(ApplicationProvider.getApplicationContext<Context>()) {
        if (it.optString("event") == "ready" || it.optString("event") == "error") { result = it; ready.countDown() }
      }
      host.start()
    }
    try {
      assertTrue("Runtime did not initialize", ready.await(45, TimeUnit.SECONDS))
      assertEquals(result.toString(), "ready", result?.optString("event"))
      assertEquals("native-shell-ok", result?.getJSONObject("shell")?.getString("stdout"))
      assertEquals("app.native.probe", result?.getJSONObject("probe")?.getString("kind"))
    } finally { instrumentation.runOnMainSync { host.close() } }
  }
}
