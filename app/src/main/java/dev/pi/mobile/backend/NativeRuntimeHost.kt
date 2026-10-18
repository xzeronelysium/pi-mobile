package dev.pi.mobile.backend

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.pi.mobile.chat.DurableRuntimeScripts
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** App-private store of the configured MCP servers (`mcp.servers` / `mcp.save`). */
private const val MCP_STORE_NAME = "mcp-servers.json"

/** Packaged JS only. This WebView is a runtime, not the Compose UI. Own it on the main thread. */
@SuppressLint("SetJavaScriptEnabled")
class NativeRuntimeHost(private val context: Context, private val workspacePath: String? = null, private val resumeConversation: Long? = null, private val onEvent: (JSONObject) -> Unit) : Closeable {
  /**
   * The largest bridge message a provider request body may occupy, base64 and quoting included.
   * Derived from the transport's own request limit, so the refusal is a clean error reply rather
   * than a dropped message that would leave the JS call pending forever.
   */
  private val maxRequestBase64 = HttpTransport.MAX_REQUEST_BYTES / 3 * 4 + 8
  /** Hard ceiling on one bridge message; above [maxRequestBase64] with room for JSON escaping. */
  private val maxBridgeMessage = 16_000_000
  private val main = Handler(Looper.getMainLooper())
  private val sqlWorker = Executors.newSingleThreadExecutor()
  private val credentialWorker = Executors.newSingleThreadExecutor()
  private val ioWorker = Executors.newCachedThreadPool()
  private var storage: NativeStorage? = null
  /** The app-private workspace: the fallback root, for an app that has bound no project folder. */
  private val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
  /**
   * The one app-private temp dir: the router's file root for it, the `TMPDIR` every shell call is
   * given, and the directory [NativeFileSystem]'s `file.tempDir`/`file.tempFile` mint into.
   *
   * One value, so the directory the agent is told to write temp files into is the directory the file
   * ops are allowed to read back — the two used to be `cacheDir` and `cacheDir/native-tmp`, which
   * made a read-back of a path the app itself handed over a coded `permission_denied`. It is also the
   * root [pruneTempRoot] bounds.
   */
  private val tempRoot = File(context.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }
  /**
   * Routes each call onto the cwd it asks for. The configured workspace and the app-private
   * fallback are the allowed roots; while All-files access is granted the shared storage that grant
   * actually covers (plus the app's own dirs) joins them, so the permission is used rather than
   * left as a grant the agent cannot reach — read live, so the grant takes effect on the next call
   * rather than at the next restart. A cwd or path outside them is a coded `permission_denied`
   * rather than a silent run in the wrong directory. One adapter pair is cached per root, and the
   * effective roots go to logcat as `io.roots` (once at construction, and again whenever the grant
   * changes the reach).
   */
  private val io = NativeIoRouter(
    fallbackRoot = workspace,
    workspacePath = workspacePath,
    tempRoot = tempRoot,
    isAllFilesAccess = { hasAllFilesAccess() },
    // What a granted All-files permission actually covers: the shared storage root (Download,
    // Documents, DCIM, the user's own project folders) plus the app's own external and private
    // dirs. Each is skipped by the router unless it is a readable directory.
    broadRoots = listOfNotNull(
      sharedStorageRootOrNull(),
      context.getExternalFilesDir(null),
      context.filesDir,
      context.cacheDir,
      context.noBackupFilesDir,
    ),
    // One line on the shared console tag; grep with `logcat -s PiRuntimeConsole -F 'io.roots'`.
    log = { line -> Log.println(Log.INFO, ConsoleRedaction.TAG, line) },
  )
  private val commands = ConcurrentHashMap<String, NativeShell.Cancellation>()
  /** The isolated JS sandbox: no app bridge, only the narrow input/output/report interface. */
  private val sandbox = JsSandboxHost(context)
  /**
   * pi-ai credentials, sealed with a non-exportable Android Keystore key. The durable conversation
   * database never holds a secret; only this vault does.
   */
  private val credentials = CredentialVault(File(context.filesDir, "native-credentials.json"), KeystoreCredentialKeyProvider())
  /** The TCP listener behind pi-ai's loopback OAuth callback servers. */
  private val loopback = LoopbackListener { serverId, request -> deliverOAuthRequest(serverId, request) }
  /**
   * Provider HTTP, owned here rather than by the WebView: pi-ai's provider calls cannot leave the
   * `https://pi.local` origin through the browser's own fetch, because no provider grants that
   * origin via CORS. `runtime/src/fetch.mjs` forwards them to this transport instead.
   */
  private val http = HttpTransport.network()
  /** Sign-in prompt id -> the bridge reply id the flow is waiting on. */
  private val pendingPrompts = ConcurrentHashMap<String, String>()
  private val webView = WebView(context)
  /** True once the runtime has been released: no evaluation, no reply, no new call reaches the WebView. */
  @Volatile private var released = false
  /** No new event is emitted and no new work is accepted from the moment the teardown begins. */
  private val closing: Boolean get() = teardown.closing
  /**
   * The teardown: ask the runtime to close itself (`window.PiNative.close()`, which is where
   * `runtime/src/host.mjs` releases the MCP connections, the event stream and the harness), give it
   * a bounded drain window — its close is asynchronous and may still be calling back through the
   * bridge while it runs — then release the native side and destroy the WebView. Never evaluated
   * before, so the runtime's own teardown never ran and in-flight bridge calls were abandoned.
   */
  private val teardown = RuntimeTeardown(
    requestRuntimeClose = { evaluate(DurableRuntimeScripts.close()) },
    scheduleRelease = { delayMillis, release -> main.postDelayed(release, delayMillis) },
    release = ::releaseWebView,
  )
  /**
   * The bounded temp-root sweep: after the interval, for as long as this runtime lives.
   *
   * Declared before [init] because the init block arms it; the sweep itself runs off the main thread.
   */
  private val pruneTick = object : Runnable {
    override fun run() {
      if (released) return
      ioWorker.execute { pruneTempRoot("interval") }
      main.postDelayed(this, TEMP_PRUNE_INTERVAL_MILLIS)
    }
  }

  init {
    // The shell invocation trace (start / child exit / reply, with every phase timestamp) goes to
    // the same single console tag the JS console already uses, so one
    // `adb logcat -s PiRuntimeConsole | grep -F 'shell.trace'` answers "which phase is missing".
    NativeShellTrace.sink = { line -> Log.println(Log.INFO, NativeShellTrace.TAG, line) }
    // The temp/spill root is written by every command that outgrows the capture cap and never read
    // again after its turn, so it is swept at startup — before any call can be in flight — and then
    // on a bounded interval, so a long-lived runtime cannot accumulate spilled output without
    // limit. Both passes use the same age/size policy as the attachment staging root.
    ioWorker.execute { pruneTempRoot("startup") }
    main.postDelayed(pruneTick, TEMP_PRUNE_INTERVAL_MILLIS)
    webView.settings.javaScriptEnabled = true
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.domStorageEnabled = false
    webView.addJavascriptInterface(Bridge(), "AndroidNative")
    // Console output is the only window into the runtime's JS: without this, a failure inside the
    // WebView (a refused provider request, an uncaught TypeError) is invisible to logcat and the app
    // just reports a dead turn. One grepable line, redacted, never a request header.
    webView.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val level = when (message.messageLevel()) {
          ConsoleMessage.MessageLevel.ERROR -> "error"
          ConsoleMessage.MessageLevel.WARNING -> "warn"
          ConsoleMessage.MessageLevel.DEBUG -> "debug"
          else -> "log"
        }
        Log.println(
          if (level == "error") Log.ERROR else Log.INFO,
          ConsoleRedaction.TAG,
          ConsoleRedaction.format(level, message.sourceId() ?: "", message.lineNumber(), message.message() ?: ""),
        )
        return true
      }
    }
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != "pi.local") return null
        if (uri.path == "/index.html") {
          val html = "<!doctype html><meta charset=utf-8><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src https:; frame-src 'none'; object-src 'none'\"><script src='/runtime.js'></script>"
          return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
        }
        if (uri.path == "/runtime.js") return WebResourceResponse("application/javascript", "UTF-8", context.assets.open("native-runtime/runtime.js"))
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), "Not found".byteInputStream())
      }
      override fun onPageFinished(view: WebView, url: String) {
        // The environment's cwd is always a real, allowed root: file paths it builds are then
        // absolute inside that root, which is what the per-call routing expects.
        if (url == "https://pi.local/index.html") evaluate(DurableRuntimeScripts.init(io.defaultRoot.path, conversationId = resumeConversation))
      }
    }
  }

  fun start() {
    check(!closing) { "The runtime host is closing" }
    webView.loadUrl("https://pi.local/index.html")
  }

  /** Evaluate JS on the WebView's thread. Still allowed during the drain window: that is how the runtime's own close reports back through the bridge. */
  fun evaluate(script: String) { main.post { if (!released) webView.evaluateJavascript(script, null) } }

  /**
   * The reach this host's own router grants the agent, in the capability report's shape.
   *
   * Read from the very [NativeIoRouter] every call here is routed through, so the report states the
   * roots that are really in use — the shared storage a granted All-files access adds once the
   * permission is held, and only the workspace pair while it is not — instead of a second derivation
   * that would drift the moment this host's inputs change. The same value renders the `io.roots`
   * line, so the log and the report cannot disagree.
   */
  fun reach(): CapabilityReport.Reach = io.reach()

  /** Values come back as `history`/`snapshot` events, so no reply correlation is needed. */
  fun requestHistory() { evaluate(DurableRuntimeScripts.history()) }
  fun requestSnapshot() { evaluate(DurableRuntimeScripts.snapshot()) }
  fun send(config: JSONObject) { evaluate(DurableRuntimeScripts.send(config)) }
  fun abort() { evaluate(DurableRuntimeScripts.abort()) }
  fun models(provider: String?, type: String) { evaluate(DurableRuntimeScripts.models(provider, type)) }
  fun available(provider: String?, type: String) { evaluate(DurableRuntimeScripts.available(provider, type)) }
  fun refresh(provider: String?, allowNetwork: Boolean) { evaluate(DurableRuntimeScripts.refresh(provider, allowNetwork)) }
  fun login(config: JSONObject) { evaluate(DurableRuntimeScripts.login(config)) }
  fun logout(provider: String) { evaluate(DurableRuntimeScripts.logout(provider)) }
  fun cancelLogin() { evaluate(DurableRuntimeScripts.cancelLogin()) }
  fun authState() { evaluate(DurableRuntimeScripts.authState()) }
  fun getAuth(provider: String) { evaluate(DurableRuntimeScripts.getAuth(provider)) }
  fun images(config: JSONObject) { evaluate(DurableRuntimeScripts.images(config)) }
  fun classify(config: JSONObject) { evaluate(DurableRuntimeScripts.classify(config)) }

  /**
   * Answer the sign-in prompt the flow is blocked on. [requestId] comes from the `auth.prompt`
   * event; the value is the entered text or the selected option id.
   */
  fun answerPrompt(requestId: String, value: String) {
    val replyId = pendingPrompts.remove(requestId) ?: return
    evaluate("window.nativeReply(${JSONObject().put("id", replyId).put("ok", true).put("value", value)})")
  }

  /** Dismiss a prompt (the sign-in sheet closed or the flow raced past it). */
  fun cancelPrompt(requestId: String) {
    val replyId = pendingPrompts.remove(requestId) ?: return
    evaluate("window.nativeReply(${JSONObject().put("id", replyId).put("ok", false).put("error", JSONObject().put("code", "aborted").put("message", "Sign-in step cancelled"))})")
  }

  /**
   * Deliver one streamed shell chunk to the JS environment.
   *
   * Called from a drain thread; [evaluate] hops to the main thread. This is one JS evaluation
   * per chunk, which is deliberately simple — a later revision can batch chunks if a chatty
   * command makes it worth it.
   */
  private fun emitShellOutput(requestId: String, stream: String, bytes: ByteArray) {
    if (closing) return
    val event = JSONObject().put("event", "shell.output").put("requestId", requestId)
      .put("stream", stream).put("base64", BridgeBase64.encode(bytes))
    evaluate("window.nativeEvent?.($event)")
  }

  /**
   * Deliver one provider-HTTP event to the JS transport (`runtime/src/fetch.mjs`).
   *
   * Same channel and shape as [emitShellOutput]: a head event before the body, ordered base64 chunk
   * events, then one terminal event. Only the response is described here — the request's headers
   * never travel back, so a credential cannot leak through this path.
   */
  private fun emitHttpEvent(requestId: String, event: HttpTransport.Event) {
    if (closing) return
    val payload = JSONObject().put("requestId", requestId)
    when (event) {
      is HttpTransport.Event.Head -> payload.put("event", "http.head").put("status", event.status)
        .put("statusText", event.statusText)
        .put("headers", JSONObject().apply { event.headers.forEach { (name, value) -> put(name, value) } })
      is HttpTransport.Event.Chunk -> payload.put("event", "http.chunk").put("seq", event.seq)
        .put("base64", BridgeBase64.encode(event.bytes))
      is HttpTransport.Event.End -> payload.put("event", "http.end").put("seq", event.seq)
        .put("truncated", event.truncated).put("cancelled", event.cancelled)
      is HttpTransport.Event.Failure -> payload.put("event", "http.error").put("code", event.code)
        .put("message", event.message)
    }
    evaluate("window.nativeEvent?.($payload)")
  }

  /** Hand one browser callback from the loopback socket to the JS shim's http handler. */
  private fun deliverOAuthRequest(serverId: String, request: LoopbackListener.Request) {
    if (closing) return
    val headers = JSONObject()
    request.headers.forEach { (name, value) -> headers.put(name, value) }
    val event = JSONObject().put("event", "oauth.request").put("serverId", serverId)
      .put("requestId", request.id).put("method", request.method).put("target", request.target)
      .put("headers", headers)
    evaluate("window.nativeEvent?.($event)")
  }

  private inner class Bridge {
    @JavascriptInterface fun postMessage(message: String) {
      // A request body carries whole transcripts, attachments included, as base64 — an image in the
      // payload can be megabytes. The cap only exists to stop a runaway message, and must stay above
      // `maxRequestBase64` so a legitimate send is never dropped (a dropped message leaves the JS
      // call pending forever, since no reply is ever evaluated).
      //
      // Calls still arrive during the drain window — that is how the runtime's own close flushes
      // durable state — so the gate is the release, not the start of the teardown.
      if (released || message.length > maxBridgeMessage) return
      try {
        val request = JSONObject(message)
        if (request.has("event")) { main.post { if (!closing) onEvent(request) }; return }
        val id = request.getString("id")
        val operation = request.getString("op")
        val args = request.optJSONObject("args") ?: JSONObject()
        val worker = when {
          operation.startsWith("sql.") -> sqlWorker
          operation.startsWith("credential.") -> credentialWorker
          else -> ioWorker
        }
        val executeRequest = {
        worker.execute {
          val reply = JSONObject().put("id", id)
          try {
            val result = when {
              operation.startsWith("sql.") -> {
                if (storage == null) storage = NativeStorage(context)
                storage!!.dispatch(operation, args)
              }
              operation.startsWith("credential.") -> dispatchCredential(operation, args)
              else -> dispatchIo(operation, args)
            }
            reply.put("ok", true).put("value", result ?: JSONObject.NULL)
          } catch (error: Exception) {
            // Carry the contract code through to JS (FileError/ExecutionError mapping).
            reply.put("ok", false).put("error", if (error is NativeFailure) {
              JSONObject().put("code", error.code).put("message", error.message ?: error.code)
            } else {
              error.message ?: error.javaClass.simpleName
            })
          }
          evaluate("window.nativeReply($reply)")
        }
        }
        if (operation == "auth.prompt") {
          // The flow is blocked until the sign-in sheet answers; the prompt itself was already
          // announced by the `auth.prompt` event the interaction emitted before this request.
          val requestId = args.optString("requestId")
          if (requestId.isBlank()) {
            evaluate("window.nativeReply(${JSONObject().put("id", id).put("ok", false).put("error", "Sign-in prompt id required")})")
          } else {
            pendingPrompts[requestId] = id
          }
        } else executeRequest()
      } catch (error: Exception) { main.post { if (!closing) onEvent(JSONObject().put("event", "error").put("message", error.message ?: "Invalid native request")) } }
    }
  }

  private fun file(path: String): File {
    val routed = io.confine(path)
    return File(routed.root, routed.path)
  }

  /** Credential vault operations, serialized on their own thread. */
  private fun dispatchCredential(operation: String, args: JSONObject): Any? = when (operation) {
    "credential.list" -> {
      val rows = JSONArray()
      for ((provider, payload) in credentials.list()) rows.put(JSONObject().put("provider", provider).put("payload", payload))
      rows
    }
    "credential.put" -> { credentials.put(args.getString("provider"), args.getString("payload")); null }
    "credential.delete" -> { credentials.delete(args.getString("provider")); null }
    else -> error("Unsupported credential operation: $operation")
  }

  private fun dispatchIo(operation: String, args: JSONObject): Any? = when (operation) {
    "shell.exec" -> {
      // The cwd and environment are resolved before the command is registered, so a denial or a
      // malformed command cannot leak an entry into `commands`.
      val shell = io.shell(args.optString("cwd"))
      val token = NativeShell.Cancellation()
      val commandId = args.optString("requestId").ifBlank { java.util.UUID.randomUUID().toString() }
      val command = when (val raw = args.opt("command")) {
        is JSONArray -> NativeShell.Command.Argv((0 until raw.length()).map { raw.getString(it) })
        is String -> NativeShell.Command.Script(raw)
        else -> throw NativeFailure("invalid", "Command required")
      }
      // The environment is replaced, never inherited: an app process environment would leak the
      // WebView's own variables. It is derived from the router's own roots, so HOME is the directory
      // the command really runs in and TMPDIR is the one directory a temp file can be written to and
      // read back through the file ops. Callers may add explicit pairs.
      val environment = io.shellEnvironment(args.optString("cwd"), args.optJSONObject("env"))
      val timeoutMillis = ShellLimits.timeoutMillis(args.optLong("timeoutMs", ShellLimits.DEFAULT_TIMEOUT_MILLIS))
      val stream = args.optBoolean("stream", false)
      var registered = false
      try {
        require(commands.putIfAbsent(commandId, token) == null) { "Duplicate shell command ID" }
        registered = true
        val result = shell.execute(
          command, environment,
          maxOutputBytes = ShellLimits.MAX_OUTPUT_BYTES,
          timeoutMillis = timeoutMillis,
          cancellation = token,
          // Only a caller that consumes `shell.output` pays for the per-chunk JS evaluation. The
          // script tool and flow steps await the reply, so they do not stream.
          onChunk = if (stream) {
            { streamName, bytes -> emitShellOutput(commandId, streamName, bytes) }
          } else {
            null
          },
          traceId = commandId,
          maxStreamBytes = ShellLimits.MAX_STREAM_BYTES,
        )
        shellExecReply(result, timeoutMillis)
      } finally { if (registered) commands.remove(commandId, token) }
    }
    "shell.cancel" -> { commands[args.getString("requestId")]?.cancel(); null }
    // The shell's limits, for the runtime: `run_script` reads the caps it runs under from here rather
    // than keeping its own copy of the numbers, so what it tells the model and what this host
    // enforces cannot drift. The host is the owner because the host is what enforces them.
    "shell.capabilities" -> ShellLimits.advertise()
    // One isolated JS run. The sandbox WebView has no Pi bridge; only declared inputs/outputs cross
    // this boundary, and the result is the sandbox's own report.
    "js.run" -> {
      val requestId = args.optString("requestId").ifBlank { java.util.UUID.randomUUID().toString() }
      val code = args.optString("code")
      if (code.isBlank()) throw NativeFailure("invalid", "JS code required")
      val imports = args.optJSONObject("imports") ?: JSONObject()
      fun files(key: String): List<File> {
        val array = args.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { index ->
          val path = array.getString(index)
          val routed = io.confine(path)
          File(routed.root, routed.path).absoluteFile
        }
      }
      val timeoutMs = args.optLong("timeoutMs", 60_000L).coerceIn(1L, 180_000L)
      sandbox.run(requestId, code, imports, files("inputs"), files("outputs"), timeoutMs)
    }
    "js.cancel" -> { sandbox.cancel(args.getString("requestId")); null }
    // Provider HTTP. Capabilities is how the JS transport decides it can route here at all; a build
    // without it falls back to the ambient fetch rather than failing every request.
    "http.capabilities" -> JSONObject().put("transport", true)
      .put("maxBytes", HttpTransport.DEFAULT_MAX_BYTES)
      .put("maxRequestBytes", HttpTransport.MAX_REQUEST_BYTES)
    "http.request" -> {
      val requestId = args.getString("requestId")
      val body = args.optString("body", "")
      require(body.length <= maxRequestBase64) { "Request body exceeds the transport limit" }
      val headers = mutableListOf<Pair<String, String>>()
      args.optJSONObject("headers")?.let { values ->
        values.keys().forEach { name -> headers += name to values.getString(name) }
      }
      val request = HttpTransport.Request(
        method = args.optString("method", "GET"),
        url = args.getString("url"),
        headers = headers,
        body = body.takeIf { it.isNotEmpty() }?.let(BridgeBase64::decode),
        connectTimeoutMillis = args.optInt("connectTimeoutMs", HttpTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS)
          .coerceIn(1_000, HttpTransport.MAX_TIMEOUT_MILLIS),
        readTimeoutMillis = args.optInt("readTimeoutMs", HttpTransport.DEFAULT_READ_TIMEOUT_MILLIS)
          .coerceIn(1_000, HttpTransport.MAX_TIMEOUT_MILLIS),
        maxBytes = args.optLong("maxBytes", HttpTransport.DEFAULT_MAX_BYTES)
          .coerceIn(1_024L, HttpTransport.MAX_MAX_BYTES),
      )
      // The exchange runs on the transport's own threads: this call only registers it, so the reply
      // and the streamed body events can interleave in either order without losing a chunk.
      JSONObject().put("accepted", http.start(requestId, request) { event -> emitHttpEvent(requestId, event) })
    }
    "http.cancel" -> { http.cancel(args.getString("requestId")); null }
    // The loopback listener pi-ai's browser sign-in flows expect from node:http.
    "oauth.listen" -> try {
      JSONObject().put("port", loopback.open(args.getString("serverId"), args.optString("host", "127.0.0.1"), args.optInt("port", 0)))
    } catch (error: BindException) {
      throw NativeFailure("EADDRINUSE", error.message ?: "Address already in use")
    }
    "oauth.respond" -> {
      loopback.respond(args.getLong("requestId"), args.optInt("status", 200), args.optString("contentType", "text/html; charset=utf-8"), args.optString("body", ""))
      null
    }
    "oauth.close" -> { loopback.close(args.getString("serverId")); null }
    "oauth.closeAll" -> { loopback.dropConnections(args.getString("serverId")); null }
    // The interaction aborts a raced prompt (a manual-code prompt losing to the browser callback);
    // the flow is no longer listening, so the pending reply is released instead of left hanging.
    "auth.dismissPrompt" -> { cancelPrompt(args.getString("requestId")); null }
    // Legacy single-shot read kept for the hand-rolled durable tools; superseded by
    // file.readRange/openBinaryReader in the ExecutionEnv contract.
    "file.read" -> file(args.getString("path")).let { require(it.length() <= 1_000_000) { "File exceeds read limit" }; it.readText() }
    "file.info" -> file(args.getString("path")).let { JSONObject().put("exists", it.exists()).put("size", it.length()).put("kind", if (it.isDirectory) "directory" else "file") }
    // The MCP server list, app-side: a JSON file beside the other app-private state, never a
    // workspace file the agent would see and never a table in the conversation database.
    "mcp.servers" -> JSONObject().put("servers", JSONArray(readMcpServers().toString()))
    "mcp.save" -> { writeMcpServers(args.optJSONArray("servers")); null }
    else -> if (operation.startsWith("file.")) dispatchFile(operation, args)
    else error("Unsupported native operation: $operation")
  }

  /** The persisted server list: `{id, name, url}` records only, exactly what the runtime sent. */
  private fun readMcpServers(): JSONArray {
    val stored = File(context.filesDir, MCP_STORE_NAME)
    if (!stored.isFile) return JSONArray()
    return try {
      JSONObject(stored.readText()).optJSONArray("servers") ?: JSONArray()
    } catch (error: Exception) {
      // A store that cannot be parsed is reported, not treated as "no servers configured": silently
      // discarding it would look like the servers were removed.
      throw NativeFailure("invalid", "Unreadable MCP server store: ${error.javaClass.simpleName}")
    }
  }

  private fun writeMcpServers(servers: JSONArray?) {
    val target = File(context.filesDir, MCP_STORE_NAME)
    target.writeText(JSONObject().put("servers", servers ?: JSONArray()).toString())
  }

  /**
   * Route a file call onto the allowed root that owns its path, then dispatch there.
   *
   * The durable environment sends absolute paths (it resolves them against its own cwd), so the
   * path is rebased onto that root and [NativeFileSystem]'s own confinement and symlink resolution
   * decide whether the final target is legal. A path outside every allowed root is denied.
   */
  private fun dispatchFile(operation: String, args: JSONObject): Any? {
    val key = when {
      args.has("source") -> "source"
      args.has("path") -> "path"
      else -> return io.files(io.defaultRoot).dispatch(operation, args)
    }
    val routed = io.confine(args.optString(key, ""))
    val rewritten = JSONObject(args.toString()).put(key, routed.path)
    if (args.has("source") && args.has("destination")) {
      val destination = io.confine(args.optString("destination", ""))
      if (destination.root.path != routed.root.path) {
        throw NativeFailure("permission_denied", "Rename must stay inside one workspace root")
      }
      rewritten.put("destination", destination.path)
    }
    return io.files(routed.root).dispatch(operation, rewritten)
  }

  /**
   * Start the teardown, in the order [RuntimeTeardown] fixes: the runtime is asked to close itself
   * (`window.PiNative.close()`), gets a bounded drain window to finish, and only then are the native
   * side and the WebView released.
   *
   * Cancelling the running commands and releasing the sign-in prompts comes first and is immediate —
   * they are this host's own state — so no command output or prompt reply can arrive during the
   * window. The native resources go last on purpose: the runtime's own close flushes durable state
   * through the bridge, so the WebView, the SQL worker and the HTTP transport have to outlive it.
   *
   * A second `close()` is a no-op. A process that dies inside the drain window never runs the JS
   * teardown at all; the app-level consequence of that is the same as before this change (nothing is
   * released on the JS side), and the app is already gone by then.
   */
  override fun close() {
    commands.values.forEach { it.cancel() }
    pendingPrompts.keys.forEach(::cancelPrompt)
    teardown.close()
  }

  /**
   * Release everything the WebView was using and destroy it.
   *
   * Runs once, from the teardown's drain window. [released] gates the WebView from here on, so a late
   * evaluation or an in-flight bridge call cannot touch a destroyed view.
   */
  private fun releaseWebView() {
    released = true
    main.removeCallbacks(pruneTick)
    // In-flight provider requests own sockets and a worker pool; both have to go with the WebView.
    http.close()
    loopback.close()
    credentials.close()
    sandbox.close()
    webView.removeJavascriptInterface("AndroidNative")
    webView.destroy()
    credentialWorker.shutdownNow()
    io.close()
    ioWorker.shutdownNow()
    sqlWorker.execute { storage?.close(); storage = null }
    sqlWorker.shutdown()
  }

  /**
   * Bound the app-private temp/spill root.
   *
   * The durable shell spills output that outgrew the capture cap into this directory and reports the
   * absolute path as the full-output location (`runtime/src/env.mjs`), and `file.tempDir` /
   * `file.tempFile` mint into it per call — nothing ever removed any of it. The policy is the same
   * one the attachment staging root uses ([StoragePrune]): an entry younger than [TEMP_MAX_AGE_MS] is
   * kept, because a spill the current turn reported may still be read, and the directory is held
   * under [TEMP_MAX_TOTAL_BYTES] beyond that. There is no in-use set to pass: at startup nothing can
   * be in flight, and the interval sweep only ever reaches files older than the age floor.
   *
   * Never throws: a prune at a lifecycle point must not be able to fail a startup.
   */
  private fun pruneTempRoot(trigger: String) {
    val removed = runCatching {
      StoragePrune.sweep(
        tempRoot,
        nowMs = System.currentTimeMillis(),
        policy = StoragePrune.Policy(maxAgeMs = TEMP_MAX_AGE_MS, maxTotalBytes = TEMP_MAX_TOTAL_BYTES),
      )
    }.getOrElse { error ->
      Log.println(Log.WARN, ConsoleRedaction.TAG, "$TEMP_PRUNE_PREFIX failed trigger=$trigger ${error.javaClass.simpleName}")
      return
    }
    // One grepable line, on the shared console tag: `adb logcat -s PiRuntimeConsole | grep -F 'temp.prune'`.
    Log.println(Log.INFO, ConsoleRedaction.TAG, "$TEMP_PRUNE_PREFIX removed=$removed trigger=$trigger root=${tempRoot.path}")
  }

  private companion object {
    /** The app-private temp dir's name under `cacheDir`; the file root, and `TMPDIR`. */
    const val TEMP_DIR_NAME = "native-tmp"

    /** The log prefix one inspection greps for. */
    const val TEMP_PRUNE_PREFIX = "temp.prune"

    /**
     * Retention for the temp/spill root (12 h).
     *
     * Longer than the sweep interval, so a spill a live session reported is never removed underneath
     * that session, and short enough that a conversation's spilled output is not kept for days.
     */
    const val TEMP_MAX_AGE_MS: Long = 12L * 60L * 60L * 1000L

    /** Ceiling on the temp/spill root (32 MB), so a chatty command cannot fill the cache dir. */
    const val TEMP_MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L

    /** How often a long-lived runtime re-applies the policy (6 h). */
    const val TEMP_PRUNE_INTERVAL_MILLIS: Long = 6L * 60L * 60L * 1000L
  }
}

/**
 * The `shell.exec` reply the durable environment and the script tool read.
 *
 * The shell itself is the only thing that knows whether its per-stream byte cap actually cut a
 * stream — [NativeShell] sets `stdoutTruncated`/`stderrTruncated` only when it did — so the reply
 * carries both flags rather than leaving the consumer to infer truncation from the cap's documented
 * size. `streamTruncated` is separate again: the shared live-delivery budget was exhausted, even
 * though the per-stream reply may still hold more (up to its own cap). Everything the consumer
 * already reads keeps its name and meaning: this is additive, and the flags describe the streams the
 * reply is carrying, never the process outcome ([NativeShell.Result] keeps
 * `timedOut`/`cancelled`/`drainIncomplete` separate for that). `timeoutMs`, when present, is the
 * deadline the host actually applied, not the one the caller asked for.
 */
internal fun shellExecReply(result: NativeShell.Result, timeoutMillis: Long? = null): JSONObject = JSONObject()
  .put("stdout", result.stdout).put("stderr", result.stderr)
  .put("exitCode", result.exitCode ?: JSONObject.NULL).put("timedOut", result.timedOut)
  .put("cancelled", result.cancelled).put("drainIncomplete", result.drainIncomplete)
  .put("stdoutTruncated", result.stdoutTruncated).put("stderrTruncated", result.stderrTruncated)
  .put("streamTruncated", result.streamTruncated)
  .apply { if (timeoutMillis != null) put("timeoutMs", timeoutMillis) }
