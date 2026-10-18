package dev.pi.mobile.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativePersistenceTest {
  @Test fun reopeningRuntimeFindsPreviouslyCommittedProbe() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = ApplicationProvider.getApplicationContext<Context>()
    fun open(): Pair<NativeRuntimeHost, JSONObject> {
      val latch = CountDownLatch(1)
      var ready: JSONObject? = null
      lateinit var host: NativeRuntimeHost
      instrumentation.runOnMainSync {
        host = NativeRuntimeHost(context) { if (it.optString("event") in listOf("ready", "error")) { ready = it; latch.countDown() } }
        host.start()
      }
      assertTrue(latch.await(30, TimeUnit.SECONDS))
      assertEquals(ready.toString(), "ready", ready?.getString("event"))
      return host to ready!!
    }
    val (first, ready) = open()
    instrumentation.runOnMainSync { first.close() }
    val (second, reopened) = open()
    try {
      assertEquals(ready.getJSONObject("probe").getString("id"), reopened.getJSONObject("previousProbe").getString("id"))
    } finally { instrumentation.runOnMainSync { second.close() } }
  }
}
