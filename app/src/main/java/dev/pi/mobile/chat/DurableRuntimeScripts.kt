package dev.pi.mobile.chat

import org.json.JSONObject
import java.util.UUID

/**
 * The JavaScript calls the Kotlin host makes into the durable runtime (`runtime/src/index.mjs`).
 *
 * Kept as pure builders so the exact scripts are unit-tested rather than assembled inline at
 * call sites. Each call is wrapped so a rejected promise surfaces as a host `error` event instead
 * of an unhandled rejection inside the WebView.
 *
 * Values go through `JSONObject`/`JSONObject.quote`, which produce valid JavaScript literals and
 * escape embedded quotes, so a workspace path can never break out of the call.
 */
object DurableRuntimeScripts {
  private fun guard(call: String): String =
    "$call.catch(e=>AndroidNative.postMessage(JSON.stringify({event:'error',message:String(e&&e.message||e)})))"

  /**
   * Start the runtime. [conversationId] asks it to resume the conversation the user was last reading
   * when there is one; the runtime opens it only while it still exists, so a stale id falls back to
   * the conversation it would have opened anyway rather than failing the launch.
   */
  fun init(workspace: String?, probeShell: Boolean = true, conversationId: Long? = null): String {
    val options = JSONObject().put("probeShell", probeShell)
    if (!workspace.isNullOrBlank()) options.put("workspace", workspace)
    if (conversationId != null) options.put("conversationId", conversationId)
    return guard("window.PiNative.init($options)")
  }

  fun history(): String = guard("window.PiNative.history()")

  fun snapshot(): String = guard("window.PiNative.snapshot()")

  fun send(config: JSONObject): String = guard("window.PiNative.send($config)")

  /** Chat, image or classifier models of one provider (all providers when [provider] is blank). */
  fun models(provider: String?, type: String = "chat"): String {
    val query = JSONObject().put("type", type)
    if (!provider.isNullOrBlank()) query.put("provider", provider)
    return guard("window.PiNative.models($query)")
  }

  /** Models whose provider has complete auth configuration right now. */
  fun available(provider: String?, type: String = "chat"): String {
    val query = JSONObject().put("type", type)
    if (!provider.isNullOrBlank()) query.put("provider", provider)
    return guard("window.PiNative.available($query)")
  }

  /** Re-list models from the providers. `allowNetwork=false` is a cache-only pass. */
  fun refresh(provider: String?, allowNetwork: Boolean = true): String {
    val query = JSONObject().put("allowNetwork", allowNetwork)
    if (!provider.isNullOrBlank()) query.put("provider", provider)
    return guard("window.PiNative.refresh($query)")
  }

  fun setKey(config: JSONObject): String = guard("window.PiNative.setKey($config)")

  /** Runs the provider's own sign-in flow; `type` is `api_key` or `oauth`. */
  fun login(config: JSONObject): String = guard("window.PiNative.login($config)")

  fun logout(provider: String): String = guard("window.PiNative.logout(${JSONObject().put("provider", provider)})")

  fun cancelLogin(): String = guard("window.PiNative.cancelLogin()")

  fun authState(): String = guard("window.PiNative.authState()")

  /** Resolve request auth, refreshing an expired OAuth token through the provider's flow. */
  fun getAuth(provider: String): String = guard("window.PiNative.getAuth(${JSONObject().put("provider", provider)})")

  fun images(config: JSONObject): String = guard("window.PiNative.generateImages($config)")

  fun classify(config: JSONObject): String = guard("window.PiNative.classify($config)")

  // ------------------------------------------------------------------ sessions, usage, queue
  //
  // Exactly the operations `runtime/src/host.mjs` exposes today. Its ids are integers — a
  // conversation id, a fork entry id and a submission id are all refused when they are not — so a
  // numeric string travels as a number and anything else travels as text, which the runtime rejects
  // rather than acting on the wrong conversation.

  /** The `conversations` frame the history drawer renders. */
  fun sessions(): String = guard("window.PiNative.sessions()")

  /** The `tree` frame: fork edges, with no depth — `ConversationTreeReducer` computes it. */
  fun tree(): String = guard("window.PiNative.tree()")

  /** Switch the host to another conversation; its history and event stream follow. */
  fun openSession(id: String): String =
    guard("window.PiNative.openSession(${JSONObject().put("conversationId", idArg(id))})")

  /** Create a fresh conversation and switch to it. */
  fun newSession(): String = guard("window.PiNative.newSession()")

  /** Fork the active conversation at one visible entry and switch to the fork. */
  fun forkSession(entryId: String): String =
    guard("window.PiNative.forkSession(${JSONObject().put("entryId", idArg(entryId))})")

  /** Start a new context in the active conversation, optionally with a handoff note. */
  fun resetSession(note: String?): String {
    val text = note?.trim().orEmpty()
    val config = if (text.isEmpty()) "{}" else JSONObject().put("handoff", text).toString()
    return guard("window.PiNative.resetSession($config)")
  }

  /** Rename a conversation. The name written is the one the list and the tree already report. */
  fun renameSession(id: String, name: String): String =
    guard("window.PiNative.renameSession(${JSONObject().put("conversationId", idArg(id)).put("name", name)})")

  /** Compact older messages into a summary, optionally guided by instructions. */
  fun compactSession(instructions: String?): String {
    val text = instructions?.trim().orEmpty()
    val config = if (text.isEmpty()) "{}" else JSONObject().put("instructions", text).toString()
    return guard("window.PiNative.compactSession($config)")
  }

  /** The `usage` frame: model and tool totals plus the active conversation's context pressure. */
  fun usage(): String = guard("window.PiNative.usage()")

  /** The `inbox` frame: input waiting for a boundary in the active conversation. */
  fun queue(): String = guard("window.PiNative.queue()")

  /** Admit input that joins the running work at the next tool boundary. */
  fun steer(text: String): String = guard("window.PiNative.steer(${submit(text)})")

  /** Admit input that starts the next run once the current answer lands. */
  fun followUp(text: String): String = guard("window.PiNative.followUp(${submit(text)})")

  /** Withdraw a queued submission; the durable outcome is reported, never assumed. */
  fun withdraw(submissionId: String): String =
    guard("window.PiNative.withdraw(${JSONObject().put("submissionId", idArg(submissionId))})")

  /**
   * Export the active transcript. The exporter renders `markdown`, `json` or `jsonl` and rejects
   * anything else, so the caller asks for one of those and the file is named for what came back.
   */
  fun exportSession(format: String): String =
    guard("window.PiNative.exportSession(${JSONObject().put("format", format)})")

  /** A queued submission carries its text and the request id its outcome is reported under. */
  private fun submit(text: String): String = JSONObject()
    .put("text", text)
    .put("requestId", UUID.randomUUID().toString())
    .toString()

  /**
   * Re-read the app-owned command documents. The host reads them through the bridge itself, parses
   * them and returns the rows the pickers render — no path travels from here.
   */
  fun commands(): String = guard("window.PiNative.commands()")

  /** The persisted MCP server list and the live state of each one. */
  fun mcpState(): String = guard("window.PiNative.mcpState()")

  /** Configure a server. It is stored, not connected; connecting is a separate call. */
  fun mcpAdd(name: String, url: String): String =
    guard("window.PiNative.mcpAdd(${JSONObject().put("name", name).put("url", url)})")

  fun mcpRemove(id: String): String =
    guard("window.PiNative.mcpRemove(${JSONObject().put("id", id)})")

  /** Connect a server and install the tools it publishes; a failure is reported, never assumed away. */
  fun mcpConnect(id: String): String =
    guard("window.PiNative.mcpConnect(${JSONObject().put("id", id)})")

  fun mcpDisconnect(id: String): String =
    guard("window.PiNative.mcpDisconnect(${JSONObject().put("id", id)})")

  // ------------------------------------------------------------------ flows
  //
  // The flow layer the runtime already owns: what this build can run, running one, continuing a
  // paused one, reading a thread back, and listing the threads. A flow is data — an agent-authored
  // JSON spec under `<workspace>/.pi-flows` compiles into the SAME registry as the shipped flows —
  // and every operation answers with the frames `runtime/src/host.mjs` emits unchanged, so the
  // Kotlin consumer reads exactly the payloads the runtime's own tests pin.
  //
  // These five operations existed in the runtime but had no builder here, which made the whole
  // shipped flow layer unreachable from the app: nothing could evaluate `window.PiNative.flows()`.
  // The argument shapes below mirror what `host.mjs`'s flows block reads, field for field.

  /** The `flows` frame: every flow this build can run, with its entry, steps and state schema. */
  fun flows(): String = guard("window.PiNative.flows()")

  /**
   * Run a flow by the name it declares. [threadId] names the run so it can be read back or resumed —
   * the runtime chooses one when it is blank, and the chosen id comes back on the result. [input]
   * seeds the flow's declared state fields; [limits] overrides the flow's own step budget.
   */
  fun runFlow(flow: String, threadId: String? = null, input: JSONObject? = null, limits: JSONObject? = null): String {
    val config = JSONObject().put("flow", flow)
    if (!threadId.isNullOrBlank()) config.put("threadId", threadId.trim())
    if (input != null) config.put("input", input)
    if (limits != null) config.put("limits", limits)
    return guard("window.PiNative.runFlow($config)")
  }

  /**
   * Continue a paused flow: the steps it awaited re-run with [value] as their resume result. A `null`
   * [value] is omitted, which the runtime reads as "no value" rather than a literal JSON null.
   */
  fun resumeFlow(threadId: String, value: Any?, limits: JSONObject? = null): String {
    val config = JSONObject().put("threadId", threadId.trim())
    if (value != null) config.put("value", value)
    if (limits != null) config.put("limits", limits)
    return guard("window.PiNative.resumeFlow($config)")
  }

  /** One flow thread: its newest state, what it awaits, whether it failed, and its boundary history. */
  fun flowStatus(threadId: String, limit: Int? = null): String {
    val query = JSONObject().put("threadId", threadId.trim())
    if (limit != null) query.put("limit", limit)
    return guard("window.PiNative.flowStatus($query)")
  }

  /** Every known flow thread, newest boundary first; [limit] bounds how many are rendered. */
  fun flowThreads(limit: Int? = null): String {
    val query = JSONObject()
    if (limit != null) query.put("limit", limit)
    return guard("window.PiNative.flowThreads($query)")
  }

  /** A JSON number for a numeric id, text otherwise. */
  private fun idArg(id: String): Any = id.trim().toLongOrNull() ?: id.trim()

  fun abort(): String = guard("window.PiNative.abort()")

  fun close(): String = "window.PiNative.close()"
}
