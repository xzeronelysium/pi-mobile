package dev.pi.mobile.backend

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A persistent, isolated WebView that hosts one JavaScript Worker per run.
 *
 * This is deliberately NOT the Pi runtime WebView:
 *  - no `AndroidNative` bridge is installed here;
 *  - only the narrow [SandboxBridge] below is exposed to the trusted sandbox page;
 *  - third-party package code runs in a Worker, which cannot see that bridge; the page mediates
 *    declared input reads and declared output writes;
 *  - a new Worker per run means a timeout/cancel can call `worker.terminate()` without destroying
 *    the WebView, and a timed-out busy loop cannot poison the next run;
 *  - the WebView itself is destroyed only when the runtime closes.
 *
 * The Worker gets `piImports` (resolved package modules), `piInputs` (declared files), `piWrite`
 * (declared outputs), and `console`. It cannot reach credentials, shell, files outside the declared
 * paths, or the Pi bridge.
 */
internal class JsSandboxHost(private val context: Context) : Closeable {
  private val main = Handler(Looper.getMainLooper())
  private val runLock = Any()
  private val active = AtomicReference<Run?>()
  private val pageReady = CountDownLatch(1)
  @Volatile private var webView: WebView? = null
  @Volatile private var closed = false

  private class Run(
    val config: JSONObject,
    val inputFiles: Map<String, File>,
    val outputFiles: Map<String, File>,
    val latch: CountDownLatch,
    val result: AtomicReference<JSONObject>,
  ) {
    val finished = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
  }

  /**
   * Run [code] with [imports] resolved to URLs. Serialized: one sandbox run at a time, so a second
   * call waits rather than creating a second WebView. Blocks the caller (the host's io worker) until
   * the worker reports, times out, or is cancelled. Never invoke on the main thread.
   */
  fun run(
    requestId: String,
    code: String,
    imports: JSONObject,
    inputs: List<File>,
    outputs: List<File>,
    timeoutMs: Long,
  ): JSONObject = synchronized(runLock) {
    if (closed) return@synchronized JSONObject().put("ok", false).put("error", "The JS sandbox is closed")
    val inputFiles = inputs.associateBy { it.absolutePath }
    val outputFiles = outputs.associateBy { it.absolutePath }
    val config = JSONObject()
      .put("requestId", requestId)
      .put("code", code)
      .put("imports", imports)
      .put("inputs", JSONArray(inputFiles.keys.toList()))
      .put("outputs", JSONArray(outputFiles.keys.toList()))
    val run = Run(
      config = config,
      inputFiles = inputFiles,
      outputFiles = outputFiles,
      latch = CountDownLatch(1),
      result = AtomicReference(),
    )

    if (!ensurePage()) return@synchronized JSONObject().put("ok", false).put("error", "The JS sandbox page did not load")
    active.set(run)
    main.post {
      runCatching { webView?.evaluateJavascript("window.piSandboxStart ? window.piSandboxStart() : undefined", null) }
    }

    // The deadline is the script's budget; LOAD_GRACE covers a cold CDN module or a slow Worker.
    val completed = try {
      run.latch.await(timeoutMs + LOAD_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }
    active.compareAndSet(run, null)
    abortWorker()

    if (run.cancelled.get()) return@synchronized JSONObject().put("ok", false).put("cancelled", true)
    if (!completed) {
      return@synchronized JSONObject().put("ok", false).put("timedOut", true).put("timeoutMs", timeoutMs)
    }
    run.result.get() ?: JSONObject().put("ok", false).put("error", "The JS sandbox produced no result")
  }

  /** Stop one run by id. Safe from any thread; only the Worker is terminated. */
  fun cancel(requestId: String) {
    val run = active.get() ?: return
    if (run.config.optString("requestId") != requestId) return
    run.cancelled.set(true)
    run.finished.set(true)
    run.latch.countDown()
    abortWorker()
  }

  override fun close() {
    closed = true
    active.get()?.let {
      it.cancelled.set(true)
      it.finished.set(true)
      it.latch.countDown()
    }
    active.set(null)
    main.post {
      val view = webView ?: return@post
      webView = null
      runCatching { view.removeJavascriptInterface("AndroidSandbox") }
      runCatching { view.destroy() }
    }
  }

  private fun ensurePage(): Boolean {
    if (webView != null) return pageReady.count == 0L || pageReady.await(LOAD_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    main.post {
      if (webView != null || closed) return@post
      val view = WebView(context)
      webView = view
      configure(view)
      view.loadUrl(SANDBOX_URL)
    }
    return pageReady.await(LOAD_GRACE_MILLIS, TimeUnit.MILLISECONDS)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun configure(webView: WebView) {
    webView.settings.javaScriptEnabled = true
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.domStorageEnabled = true
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.settings.setSupportMultipleWindows(false)
    webView.alpha = 0f
    webView.isClickable = false
    webView.isFocusable = false
    webView.addJavascriptInterface(SandboxBridge(), "AndroidSandbox")
    webView.webViewClient = SandboxPageClient()
    webView.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        Log.println(Log.INFO, SANDBOX_TAG, "${message.messageLevel()} ${message.message()}")
        return true
      }
    }
  }

  private fun abortWorker() {
    if (webView == null) return
    main.post {
      runCatching { webView?.evaluateJavascript("window.piSandboxAbort ? window.piSandboxAbort() : undefined", null) }
    }
  }

  private fun finish(run: Run, value: JSONObject?) {
    if (!run.finished.compareAndSet(false, true)) return
    if (value != null) run.result.set(value)
    run.latch.countDown()
  }

  private fun fail(run: Run, message: String) {
    finish(run, JSONObject().put("ok", false).put("error", message))
  }

  private inner class SandboxPageClient : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
      val uri = request.url
      if (uri.host != "pi.local") return null
      return when (uri.path) {
        "/sandbox.html" -> asset("native-runtime/sandbox.html", "text/html")
        "/sandbox.js" -> asset("native-runtime/sandbox.js", "application/javascript")
        "/sandbox-worker.js" -> asset("native-runtime/sandbox-worker.js", "application/javascript")
        else -> WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), "Not found".byteInputStream())
      }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
      Log.println(Log.WARN, SANDBOX_TAG, "error ${error.errorCode} ${error.description} ${request.url}")
      if (request.url.toString() == SANDBOX_URL) {
        active.get()?.let { fail(it, "The sandbox page failed to load: ${error.description}") }
        pageReady.countDown()
      }
    }

    override fun onPageFinished(view: WebView, url: String) {
      Log.println(Log.INFO, SANDBOX_TAG, "page finished $url")
      if (url == SANDBOX_URL) pageReady.countDown()
    }
  }

  private fun asset(path: String, mime: String): WebResourceResponse =
    WebResourceResponse(mime, "UTF-8", context.assets.open(path))

  private inner class SandboxBridge {
    @JavascriptInterface fun getConfig(): String? = active.get()?.config?.toString()

    @JavascriptInterface fun readInput(path: String): String? {
      val run = active.get() ?: return null
      val file = run.inputFiles[path] ?: return null
      return runCatching {
        if (!file.isFile || file.length() > MAX_INPUT_BYTES) return null
        BridgeBase64.encode(file.readBytes())
      }.getOrNull()
    }

    @JavascriptInterface fun writeOutput(path: String, base64: String): Boolean {
      val run = active.get() ?: return false
      val file = run.outputFiles[path] ?: return false
      return runCatching {
        val bytes = BridgeBase64.decode(base64)
        if (bytes.size > MAX_OUTPUT_BYTES) return false
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
      }.getOrDefault(false)
    }

    @JavascriptInterface fun report(json: String) {
      val run = active.get() ?: return
      if (run.finished.get()) return
      if (json.length > MAX_REPORT_CHARS) {
        fail(run, "The sandbox result exceeded the host limit")
        return
      }
      val value = runCatching { JSONObject(json) }.getOrElse {
        fail(run, "The sandbox reported invalid JSON")
        return
      }
      finish(run, value)
    }
  }

  private companion object {
    const val SANDBOX_URL = "https://pi.local/sandbox.html"
    const val SANDBOX_TAG = "PiJsSandbox"
    const val LOAD_GRACE_MILLIS = 20_000L
    const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 32L * 1024 * 1024
    const val MAX_REPORT_CHARS = 1_000_000
  }
}
