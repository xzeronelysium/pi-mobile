package dev.pi.mobile.backend

import android.content.Context
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.DurableRuntimeScripts
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * The app-facing client for the durable native runtime.
 *
 * Wraps [NativeRuntimeHost] and narrows its surface to the durable API the UI needs, exposing
 * parsed [DurableEvent]s instead of raw JSON. Values that a call produces (history, snapshot)
 * arrive as events rather than return values, so there is no reply correlation here.
 */
class DurableRuntime(
  context: Context,
  private val workspace: File?,
  /** The conversation the user was last reading, to resume when it still exists; null starts fresh. */
  private val resumeConversation: Long? = null,
  onEvent: (DurableEvent) -> Unit,
) : Closeable {
  private val context = context.applicationContext
  private val host = NativeRuntimeHost(context, workspace?.path, resumeConversation) { json ->
    DurableEvent.parse(json)?.let(onEvent)
  }

  /** Loads the packaged runtime; `ready` arrives as an event. */
  fun start() = host.start()

  /**
   * The reach this runtime's own router grants the agent, in the capability report's shape.
   *
   * The activity hands this to the capability probe as its `reachSource`, so the report it drops in
   * the workspace states the router that is really in use rather than a re-derivation of the same
   * paths. Read live, so a granted All-files access is reflected and its absence is not.
   */
  fun reach(): CapabilityReport.Reach = host.reach()

  fun history() = host.requestHistory()

  fun snapshot() = host.requestSnapshot()

  fun send(config: JSONObject) = host.send(config)

  /** Chat models by default; `type` selects image or classifier models. */
  fun models(provider: String?, type: String = "chat") = host.models(provider, type)

  /** Models whose provider is configured right now. */
  fun available(provider: String?, type: String = "chat") = host.available(provider, type)

  fun refresh(provider: String? = null, allowNetwork: Boolean = true) = host.refresh(provider, allowNetwork)

  /** Store an API key through the same path the runtime uses (Keystore-sealed). */
  fun setKey(provider: String, key: String) =
    host.evaluate(DurableRuntimeScripts.setKey(JSONObject().put("provider", provider).put("apiKey", key)))

  /**
   * Run the provider's own sign-in flow. The stable installation id travels with the request
   * because OpenAI's ChatGPT sign-in requires it and must see the same value every time.
   */
  fun login(provider: String, type: String) = host.login(
    JSONObject().put("provider", provider).put("type", type).put("deviceId", DeviceIdentity.of(context)),
  )

  fun logout(provider: String) = host.logout(provider)

  fun cancelLogin() = host.cancelLogin()

  fun authState() = host.authState()

  fun getAuth(provider: String) = host.getAuth(provider)

  fun images(config: JSONObject) = host.images(config)

  fun classify(config: JSONObject) = host.classify(config)

  // --------------------------------------------------------- sessions, usage, queue
  //
  // These are the session surfaces the UI drives: the history drawer, the dock's usage line and
  // the queue dialog. Results arrive as events (`DurableEvent.Conversations`, `Tree`, `Usage`,
  // `Inbox`, `Export`, `Switched`, `Compacted`), so nothing is returned here. The script builders
  // are what the bridge contract is checked against; see [DurableRuntimeScripts].

  /** Every conversation in storage: the `conversations` frame. */
  fun sessions() = host.evaluate(DurableRuntimeScripts.sessions())

  /** The active conversation's entry tree: the `tree` frame. */
  fun tree() = host.evaluate(DurableRuntimeScripts.tree())

  /** Switch to another conversation; its history and event stream follow. Resets run/queue state. */
  fun openSession(id: String) = host.evaluate(DurableRuntimeScripts.openSession(id))

  /** Create a fresh conversation and switch to it. */
  fun newSession() = host.evaluate(DurableRuntimeScripts.newSession())

  /** Fork the active conversation at one visible entry and switch to the fork. */
  fun forkSession(entryId: String) = host.evaluate(DurableRuntimeScripts.forkSession(entryId))

  /** Start a new context, optionally with a handoff note. */
  fun resetSession(note: String? = null) = host.evaluate(DurableRuntimeScripts.resetSession(note))

  /** Rename a conversation: the name the list and the tree already report. */
  fun renameSession(id: String, name: String) = host.evaluate(DurableRuntimeScripts.renameSession(id, name))

  /** Compact older messages into a summary; the durable outcome arrives as `session.compacted`. */
  fun compactSession(instructions: String? = null) =
    host.evaluate(DurableRuntimeScripts.compactSession(instructions))

  /** Session spend plus the active conversation's own usage. */
  fun usage() = host.evaluate(DurableRuntimeScripts.usage())

  /** Input waiting for a boundary in the active conversation: the `inbox` frame. */
  fun queue() = host.evaluate(DurableRuntimeScripts.queue())

  /** Admit input that joins the running work at the next tool boundary. */
  fun steer(text: String) = host.evaluate(DurableRuntimeScripts.steer(text))

  /** Admit input that starts the next run once the current answer lands. */
  fun followUp(text: String) = host.evaluate(DurableRuntimeScripts.followUp(text))

  /** Withdraw a queued submission. */
  fun withdraw(submissionId: String) = host.evaluate(DurableRuntimeScripts.withdraw(submissionId))

  /** Export the active transcript as `markdown` or `jsonl`; the file arrives as an `export` event. */
  fun exportSession(format: String) = host.evaluate(DurableRuntimeScripts.exportSession(format))

  /** Answer the sign-in prompt the flow is waiting on (text, secret, select or pasted code). */
  fun answerPrompt(requestId: String, value: String) = host.answerPrompt(requestId, value)

  fun dismissPrompt(requestId: String) = host.cancelPrompt(requestId)

  // ------------------------------------------------- app-owned commands and MCP
  //
  // The rows and the server state arrive as events (`DurableEvent.Commands`, `DurableEvent.Mcp`), so
  // nothing is returned here. The host reads the command documents and owns the server store; the
  // script builders are what the bridge contract is checked against ([DurableRuntimeScripts]).

  /** Re-read the app-owned command documents; the rows arrive as a `commands` event. */
  fun commands() = host.evaluate(DurableRuntimeScripts.commands())

  /** The configured MCP servers and what each is doing; arrives as an `mcp` event. */
  fun mcpState() = host.evaluate(DurableRuntimeScripts.mcpState())

  /** Configure an MCP server. It is stored, not connected. */
  fun mcpAdd(name: String, url: String) = host.evaluate(DurableRuntimeScripts.mcpAdd(name, url))

  fun mcpRemove(id: String) = host.evaluate(DurableRuntimeScripts.mcpRemove(id))

  /** Connect a server and offer its tools to the agent. */
  fun mcpConnect(id: String) = host.evaluate(DurableRuntimeScripts.mcpConnect(id))

  /** Drop a connection and its tools; the configuration stays. */
  fun mcpDisconnect(id: String) = host.evaluate(DurableRuntimeScripts.mcpDisconnect(id))

  fun abort() = host.abort()

  override fun close() = host.close()
}
