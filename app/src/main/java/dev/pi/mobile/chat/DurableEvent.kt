package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Events the durable native host emits to the app (`runtime/src/host.mjs` → `bridge.emit`).
 *
 * This is the durable replacement for the CLI backend's event surface. The host streams one
 * uniform sequence, so the UI has a single typed entry point instead of re-parsing
 * `event.optString("event")` chains in the Activity.
 *
 * Transcript content arrives as projected `rows` (see `runtime/src/ui.mjs`); events that only
 * signal "something changed" leave the rows stale and the caller re-requests history.
 *
 * Sign-in is its own event family (`auth.*`): the upstream provider flow drives the user interface
 * through prompts and device-code/auth-URL notices, and this file only types them.
 */
sealed class DurableEvent {
  /** The bundled JS runtime finished loading. */
  data class Loaded(val runtime: String, val version: String) : DurableEvent()

  /** Harness opened; the conversation exists and history may be requested. */
  data class Ready(val conversationId: Long?, val providers: List<CatalogProvider> = emptyList()) : DurableEvent()

  /** Answer to an explicit `history` request. */
  data class History(val rows: List<ChatEntry>, val complete: Boolean = true) : DurableEvent()

  /** Full conversation view pushed by the host. */
  data class Snapshot(val rows: List<ChatEntry>) : DurableEvent()

  /**
   * The conversation changed (a durable commit landed). [changed] is how many engine events the
   * batch carried and [rows] are the transcript rows that changed in it, already projected by
   * `runtime/src/transcript.mjs` and upserted by id; [removed] names the transient streaming rows
   * the frame retires, and [full] marks a snapshot frame whose [rows] are the whole projection
   * rather than a delta. The caller merges these instead of re-requesting the whole transcript.
   */
  data class Message(
    val rows: List<ChatEntry> = emptyList(),
    val removed: List<String> = emptyList(),
    val full: Boolean = false,
    val snapshot: Boolean = false,
  ) : DurableEvent()

  /** A submitted input was durably admitted. */
  data class Submitted(val requestId: String, val submissionId: Long?) : DurableEvent()

  /** A submitted run settled. [status] is "done" or "unanswered". Rows are stale. */
  data class Settled(val requestId: String, val status: String) : DurableEvent()

  /** A failure. [operation] is set when the host knows which call failed. */
  data class Failure(val message: String, val operation: String?) : DurableEvent()

  /** One built-in provider and everything the UI needs to describe it honestly. */
  data class CatalogProvider(
    val id: String,
    val name: String,
    val apiKey: Boolean,
    val oauth: Boolean,
    val oauthName: String? = null,
    val oauthSubscription: Boolean = false,
    val oauthLoginLabel: String? = null,
    val oauthDeviceCode: Boolean = false,
    val oauthLoopback: Boolean = false,
    val oauthLoopbackRequired: Boolean = false,
    val oauthManualCode: Boolean = false,
    val oauthMechanismKnown: Boolean = false,
    val deferred: Boolean = false,
    val chatCount: Int = 0,
    val imageCount: Int = 0,
    val classifierCount: Int = 0,
  )

  /** Model catalog rows for one provider and model type, with the metadata the picker shows. */
  data class Models(val provider: String?, val type: String, val rows: List<ChatModelOption>) : DurableEvent()

  /** Catalog rows whose provider is configured right now — the authenticated subset. */
  data class Available(val provider: String?, val type: String, val rows: List<ChatModelOption>) : DurableEvent()

  /** Result of re-listing models from the providers. */
  data class Refresh(val aborted: Boolean, val errors: List<String>) : DurableEvent()

  // ------------------------------------------------------------------ sessions, usage, queue

  /**
   * The conversation list the history drawer renders (`{current, rows:[{id, name, created,
   * modified, messageCount, owner, current}]}`), projected by the sibling [SessionListReducer]. A
   * field the payload does not carry stays absent rather than being invented.
   */
  data class Conversations(val list: SessionList) : DurableEvent()

  /** The active conversation's entry tree, flattened parents-first by [ConversationTreeReducer]. */
  data class Tree(val tree: ConversationTree) : DurableEvent()

  /**
   * The app-owned commands the host discovered and parsed: skills (durable extensions) and
   * templates. Nothing is expanded here — the rows are what the pickers render.
   */
  data class Commands(val catalog: CommandCatalog) : DurableEvent()

  /** The configured MCP servers and what each one is doing right now. */
  data class Mcp(val state: McpState) : DurableEvent()

  /** Session spend and the conversation's own usage, projected for the composer dock. */
  data class Usage(val usage: UsageState) : DurableEvent()

  /** Input waiting for a boundary: steer and follow-up submissions. */
  data class Inbox(val inbox: InboxState) : DurableEvent()

  /** An exported transcript: bytes when the host encoded them, text when it inlined them. */
  data class Export(val export: ExportResult) : DurableEvent()

  /** The host moved to another conversation; the rows have been re-requested separately. */
  data class Switched(val id: String) : DurableEvent()

  /**
   * A session operation with no payload of its own (`queued`, `withdrawn`, `session.reset`,
   * `written`, `session.renamed`). The list, usage and inbox are stale, so the caller re-requests.
   */
  data class SessionChanged(val name: String) : DurableEvent()

  /**
   * A manual compaction, in two frames: the admission (`status = "admitted"`) and then the
   * summarization task's own terminal outcome, which is never assumed to have succeeded.
   */
  data class Compacted(
    val status: String,
    val taskId: String = "",
    val error: String? = null,
    val entryId: String? = null,
  ) : DurableEvent()

  /** One exported document. A filename the host did not send is derived from real fields. */
  data class ExportResult(
    val format: String,
    val filename: String,
    val mime: String = "",
    val base64: String = "",
    val text: String = "",
    val truncated: Boolean = false,
  ) {
    /** True when there is something to write; a name alone is not an export. */
    val hasContent: Boolean get() = base64.isNotBlank() || text.isNotBlank()
  }

  // ------------------------------------------------------------------ sign-in

  data class AuthStarted(val provider: String, val name: String, val authType: String, val subscription: Boolean) : DurableEvent()

  /** One option of a `select` prompt, verbatim from the provider flow. */
  data class AuthOption(val id: String, val label: String, val description: String?)

  /** A prompt the upstream flow is blocked on; answer it with [DurableRuntime.answerPrompt]. */
  data class AuthPrompt(
    val requestId: String,
    val type: String,
    val message: String,
    val placeholder: String?,
    val options: List<AuthOption> = emptyList(),
  ) : DurableEvent()

  data class AuthPromptDone(val requestId: String) : DurableEvent()

  /** Out-of-band notice from the flow: auth URL, device code, progress or info. */
  data class AuthNotice(
    val kind: String,
    val message: String?,
    val url: String?,
    val instructions: String?,
    val userCode: String?,
    val verificationUri: String?,
    val expiresInSeconds: Int?,
  ) : DurableEvent()

  /** The flow returned a credential and it is sealed in the Keystore vault. */
  data class AuthSettled(val provider: String, val authType: String, val detail: String) : DurableEvent()

  data class AuthFailed(val provider: String, val cancelled: Boolean, val message: String) : DurableEvent()

  data class AuthFinished(val provider: String, val cancelled: Boolean) : DurableEvent()

  /** One provider's credential status. */
  data class AuthRow(
    val provider: String,
    val name: String,
    val configured: Boolean,
    val source: String?,
    val type: String?,
    val detail: String?,
  )

  data class AuthState(val rows: List<AuthRow>) : DurableEvent()

  /** Resolved request auth (never the token itself). */
  data class AuthResolved(val provider: String, val configured: Boolean, val source: String?, val hasApiKey: Boolean) : DurableEvent()

  // ------------------------------------------------------------- operations

  data class ImagesResult(
    val provider: String?,
    val model: String?,
    val error: String?,
    val stopReason: String?,
    val text: String,
    val imageCount: Int,
    val imageBytes: Int,
    val known: List<String> = emptyList(),
  ) : DurableEvent()

  data class ClassifyResult(
    val provider: String?,
    val model: String?,
    val error: String?,
    val stopReason: String?,
    val answers: String,
    val known: List<String> = emptyList(),
  ) : DurableEvent()

  /**
   * A frame this build has no arm for, kept rather than dropped.
   *
   * The flow layer already emits frames the UI does not interpret — `flows`, `flow.status`,
   * `flow.threads`, `flow.started`, `flow.resumed`, `flow.interrupt`, `flow.finished`,
   * `flow.checkpoint`, `flow.step.*` and `deferred` — and a future host event would land here too.
   * [type] is the frame's own `event` string and [payload] its raw JSON, so a frame the runtime
   * emits can never go silent: it is surfaced with everything needed to log it. This carries no
   * product meaning — the flow layer is typed separately — it is only the safety net.
   */
  data class Unknown(val type: String, val payload: String) : DurableEvent()

  companion object {
    /** @return the parsed event; null only when [json] carries no `event` field at all. */
    fun parse(json: JSONObject): DurableEvent? = when (val type = json.optString("event")) {
      "loaded" -> Loaded(json.optString("runtime"), json.optString("version"))
      "ready" -> Ready(
        json.optLong("conversationId").takeIf { json.has("conversationId") },
        providersOf(json.optJSONArray("providers")),
      )
      "history" -> json.optJSONObject("value")?.let { value ->
        // A read that carries a continuation (`next`) did not reach the conversation's first entry:
        // it is a page of a longer conversation and must be merged, never used to delete rows it
        // does not carry.
        History(rowsOf(value), complete = !value.has("next"))
      } ?: History(emptyList())
      "snapshot" -> Snapshot(rowsOf(json.optJSONObject("value")))
      "message" -> json.optJSONObject("value")?.let { value ->
        // A frame with no `value` carries nothing to apply, so it is not an event at all: the raw
        // durable events no longer ride along, only the projection the UI merges.
        Message(
          rows = rowsOf(value),
          removed = stringsOf(value.optJSONArray("removed")),
          full = value.optBoolean("full"),
          snapshot = value.optBoolean("snapshot"),
        )
      }
      "submitted" -> Submitted(
        requestId = json.optString("requestId"),
        submissionId = json.optLong("submissionId").takeIf { json.has("submissionId") },
      )
      "settled" -> Settled(
        requestId = json.optString("requestId"),
        status = json.optJSONObject("value")?.optString("status").orEmpty(),
      )
      "error" -> Failure(
        message = json.optString("message"),
        operation = json.optString("operation").takeIf { it.isNotBlank() },
      )
      "models" -> Models(
        provider = json.optJSONObject("value")?.optString("provider")?.takeIf { it.isNotBlank() && it != "null" },
        type = json.optJSONObject("value")?.optString("type")?.takeIf { it.isNotBlank() } ?: "chat",
        rows = modelRowsOf(json.optJSONObject("value")?.optJSONArray("models")),
      )
      "available" -> Available(
        provider = json.optJSONObject("value")?.optString("provider")?.takeIf { it.isNotBlank() && it != "null" },
        type = json.optJSONObject("value")?.optString("type")?.takeIf { it.isNotBlank() } ?: "chat",
        rows = modelRowsOf(json.optJSONObject("value")?.optJSONArray("models")),
      )
      "refresh" -> json.optJSONObject("value")?.let { value ->
        Refresh(
          aborted = value.optBoolean("aborted"),
          errors = buildList {
            val array = value.optJSONArray("errors") ?: return@buildList
            for (index in 0 until array.length()) {
              val row = array.optJSONObject(index) ?: continue
              add("${row.optString("id")}: ${row.optString("message")}")
            }
          },
        )
      }
      // The session surface: exactly the frames `runtime/src/host.mjs` emits, each projected by the
      // sibling reducer that owns its shape.
      "conversations" -> Conversations(SessionListReducer.fromValue(json.optJSONObject("value")))
      "tree" -> Tree(ConversationTreeReducer.fromValue(json.optJSONObject("value")))
      // The app-owned commands and the MCP server surface: one frame each, projected by the reducer
      // that owns its shape.
      "commands" -> Commands(CommandCatalogReducer.fromValue(json.optJSONObject("value")))
      "mcp" -> Mcp(McpUiReducer.fromValue(json.optJSONObject("value")))
      "usage" -> Usage(UsageReducer.fromValue(json.optJSONObject("value")))
      "inbox" -> Inbox(InboxReducer.fromValue(json.optJSONObject("value")))
      "export" -> json.optJSONObject("value")?.let { Export(exportOf(it)) }
      // An activation is announced as `session`, carrying the conversation that is now being read.
      "session" -> json.optString("conversationId").takeIf { it.isNotBlank() }?.let { Switched(it) }
      "session.compacted" -> json.optJSONObject("value")?.let { compactedOf(it) }
      "queued", "withdrawn", "session.reset", "written", "session.renamed" -> SessionChanged(type)
      "auth.started" -> AuthStarted(
        provider = json.optString("provider"),
        name = json.optString("name"),
        authType = json.optString("authType"),
        subscription = json.optBoolean("subscription"),
      )
      "auth.prompt" -> promptOf(json)
      "auth.prompt.done" -> AuthPromptDone(json.optString("requestId"))
      // The envelope key is `event`; the notice payload travels under `notice` so the two cannot collide.
      "auth.notice" -> json.optJSONObject("notice")?.let { event ->
        if (event.optString("type").isBlank()) null else AuthNotice(
          kind = event.optString("type"),
          message = event.optString("message").takeIf { it.isNotBlank() },
          url = event.optString("url").takeIf { it.isNotBlank() },
          instructions = event.optString("instructions").takeIf { it.isNotBlank() },
          userCode = event.optString("userCode").takeIf { it.isNotBlank() },
          verificationUri = event.optString("verificationUri").takeIf { it.isNotBlank() },
          expiresInSeconds = event.optInt("expiresInSeconds").takeIf { event.has("expiresInSeconds") },
        )
      }
      "auth.settled" -> AuthSettled(
        provider = json.optString("provider"),
        authType = json.optString("authType"),
        detail = credentialDetail(json.optJSONObject("credential")),
      )
      "auth.failed" -> AuthFailed(
        provider = json.optString("provider"),
        cancelled = json.optBoolean("cancelled"),
        message = json.optString("message"),
      )
      "auth.finished" -> AuthFinished(json.optString("provider"), json.optBoolean("cancelled"))
      "auth.state" -> AuthState(authRows(json.optJSONObject("value")))
      "auth.resolved" -> AuthResolved(
        provider = json.optJSONObject("value")?.optString("provider").orEmpty(),
        configured = json.optJSONObject("value")?.optBoolean("configured") == true,
        source = json.optJSONObject("value")?.optString("source")?.takeIf { it.isNotBlank() && it != "null" },
        hasApiKey = json.optJSONObject("value")?.optBoolean("hasApiKey") == true,
      )
      "images" -> json.optJSONObject("value")?.let(::imagesOf)
      "classify" -> json.optJSONObject("value")?.let(::classifyOf)
      "" -> null
      // A frame whose type this build does not know is surfaced, never dropped: the flow frames and
      // any future host event reach the caller intact instead of vanishing here. Only the `else`
      // arm ever builds the raw payload, so the streaming path pays nothing for this net.
      else -> Unknown(type, json.toString())
    }

    private fun rowsOf(value: JSONObject?): List<ChatEntry> =
      DurableTranscriptReducer.fromRows(value?.optJSONArray("rows"))

    /**
     * The `session.compacted` frame: the admission first, then the task's own terminal outcome.
     * A failure carries its message; a success carries the summary entry it placed.
     */
    private fun compactedOf(value: JSONObject): Compacted = Compacted(
      status = text(value.optString("status")) ?: "terminal",
      taskId = text(value.optString("taskId")).orEmpty(),
      error = text(value.optString("error")),
      entryId = text(value.optString("entryId")),
    )

    /**
     * One export. The host inlines the rendered document (`content`) and reports the format it
     * actually rendered; a host that encodes the bytes instead is read just the same. Either way
     * the file is named for the format that came back, not for the one that was asked for.
     */
    private fun exportOf(value: JSONObject): ExportResult {
      val format = text(value.optString("format")) ?: "markdown"
      val conversationId = text(value.optString("conversationId")).orEmpty()
      return ExportResult(
        format = format,
        filename = SessionExportUi.safeFileName(text(value.optString("filename")), format, "conversation-$conversationId"),
        mime = text(value.optString("mime")).orEmpty(),
        base64 = text(value.optString("base64")).orEmpty(),
        text = text(value.optString("content")).orEmpty(),
        truncated = value.optBoolean("truncated"),
      )
    }

    /** A JSON `null` (which `optString` may report as the text "null") is an absent value. */
    private fun text(value: String): String? = value.takeIf { it.isNotBlank() && it != "null" }

    private fun providersOf(array: JSONArray?): List<CatalogProvider> {
      if (array == null) return emptyList()
      return buildList {
        for (index in 0 until array.length()) {
          val row = array.optJSONObject(index) ?: continue
          val id = row.optString("id")
          if (id.isBlank()) continue
          val counts = row.optJSONObject("counts")
          add(
            CatalogProvider(
              id = id,
              name = row.optString("name").ifBlank { id },
              apiKey = row.optBoolean("apiKey"),
              oauth = row.optBoolean("oauth"),
              oauthName = row.optString("oauthName").takeIf { it.isNotBlank() && it != "null" },
              oauthSubscription = row.optBoolean("oauthSubscription"),
              oauthLoginLabel = row.optString("oauthLoginLabel").takeIf { it.isNotBlank() && it != "null" },
              oauthDeviceCode = row.optBoolean("oauthDeviceCode"),
              oauthLoopback = row.optBoolean("oauthLoopback"),
              oauthLoopbackRequired = row.optBoolean("oauthLoopbackRequired"),
              oauthManualCode = row.optBoolean("oauthManualCode"),
              oauthMechanismKnown = row.optBoolean("oauthMechanismKnown"),
              deferred = row.optBoolean("deferred"),
              chatCount = counts?.optInt("chat") ?: 0,
              imageCount = counts?.optInt("image") ?: 0,
              classifierCount = counts?.optInt("classifier") ?: 0,
            ),
          )
        }
      }
    }

    /**
     * One catalog row per model the host reported. Rows missing an id or a provider are dropped
     * rather than rendered blank; a row the provider named only by id keeps that id as its label.
     * Metadata the provider does not publish stays absent so the sheet omits it.
     */
    private fun modelRowsOf(array: JSONArray?): List<ChatModelOption> {
      if (array == null) return emptyList()
      return buildList {
        for (index in 0 until array.length()) {
          val row = array.optJSONObject(index) ?: continue
          val id = row.optString("id")
          val provider = row.optString("provider")
          if (id.isBlank() || provider.isBlank()) continue
          val cost = row.optJSONObject("cost")
          add(
            ChatModelOption(
              provider = provider,
              modelId = id,
              name = row.optString("name").ifBlank { id },
              // The row's own type wins: a mixed list must not mislabel an image model as chat.
              kind = ModelKind.ofWire(row.optString("type")) ?: ModelKind.Chat,
              api = row.optString("api").takeIf { it.isNotBlank() && it != "null" },
              contextWindow = row.optLong("contextWindow").takeIf { row.has("contextWindow") },
              maxTokens = row.optLong("maxTokens").takeIf { row.has("maxTokens") },
              reasoning = row.optBoolean("reasoning"),
              input = stringsOf(row.optJSONArray("input")).ifEmpty { listOf("text") },
              output = stringsOf(row.optJSONArray("output")),
              costIn = cost?.takeIf { it.has("input") }?.optDouble("input"),
              costOut = cost?.takeIf { it.has("output") }?.optDouble("output"),
              thinkingLevels = stringsOf(row.optJSONArray("thinkingLevels")),
            ),
          )
        }
      }
    }

    /** A plain array of strings; JSON `null` members are absent entries, not the text "null". */
    private fun stringsOf(array: JSONArray?): List<String> {
      if (array == null) return emptyList()
      return buildList {
        for (index in 0 until array.length()) {
          val value = array.optString(index)
          if (value.isNotBlank() && value != "null") add(value)
        }
      }
    }

    private fun promptOf(json: JSONObject): AuthPrompt? {
      val prompt = json.optJSONObject("prompt") ?: return null
      val requestId = json.optString("requestId")
      if (requestId.isBlank()) return null
      val options = buildList {
        val array = prompt.optJSONArray("options") ?: return@buildList
        for (index in 0 until array.length()) {
          val option = array.optJSONObject(index) ?: continue
          val id = option.optString("id")
          if (id.isBlank()) continue
          add(AuthOption(id, option.optString("label").ifBlank { id }, option.optString("description").takeIf { it.isNotBlank() }))
        }
      }
      return AuthPrompt(
        requestId = requestId,
        type = prompt.optString("type").ifBlank { "text" },
        message = prompt.optString("message"),
        placeholder = prompt.optString("placeholder").takeIf { it.isNotBlank() && it != "null" },
        options = options,
      )
    }

    /** Non-secret credential description, built from the fields the host reports. */
    private fun credentialDetail(credential: JSONObject?): String {
      if (credential == null) return "credential"
      return buildList {
        add(if (credential.optString("type") == "oauth") "subscription token" else "API key")
        credential.optString("accountId").takeIf { it.isNotBlank() && it != "null" }?.let { add("account $it") }
        if (credential.optBoolean("refreshable")) add("renewable")
        if (credential.has("expires")) {
          val remaining = credential.optLong("expires") - System.currentTimeMillis()
          add(if (remaining <= 0) "expired" else "valid ${remaining / 60_000} min")
        }
      }.joinToString(", ")
    }

    private fun authRows(value: JSONObject?): List<AuthRow> {
      if (value == null) return emptyList()
      // `init` reports every provider; `setKey`/`logout` report the single provider they touched.
      val array = value.optJSONArray("providers")
      if (array == null) {
        if (!value.has("provider")) return emptyList()
        return listOf(rowOf(value, value.optString("provider")))
      }
      return buildList {
        for (index in 0 until array.length()) {
          val row = array.optJSONObject(index) ?: continue
          add(rowOf(row, row.optString("provider")))
        }
      }
    }

    private fun rowOf(row: JSONObject, provider: String): AuthRow {
      if (provider.isBlank()) return AuthRow("", "", false, null, null, null)
      val stored = row.optJSONObject("stored")
      return AuthRow(
        provider = provider,
        name = row.optString("name").ifBlank { provider },
        configured = row.optBoolean("configured"),
        source = row.optString("source").takeIf { it.isNotBlank() && it != "null" },
        type = row.optString("type").takeIf { it.isNotBlank() && it != "null" },
        detail = stored?.let(::credentialDetail),
      )
    }

    private fun imagesOf(value: JSONObject): ImagesResult {
      val output = value.optJSONArray("output")
      var text = ""
      var images = 0
      var bytes = 0
      if (output != null) {
        for (index in 0 until output.length()) {
          val part = output.optJSONObject(index) ?: continue
          if (part.optString("type") == "text") text += part.optString("text")
          else { images += 1; bytes += part.optString("base64").length * 3 / 4 }
        }
      }
      return ImagesResult(
        provider = value.optString("provider").takeIf { it.isNotBlank() && it != "null" },
        model = value.optString("model").takeIf { it.isNotBlank() && it != "null" } ?: value.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
        error = value.optString("error").takeIf { it.isNotBlank() && it != "null" },
        stopReason = value.optString("stopReason").takeIf { it.isNotBlank() && it != "null" },
        text = text,
        imageCount = images,
        imageBytes = bytes,
        known = stringsOf(value.optJSONArray("known")),
      )
    }

    private fun classifyOf(value: JSONObject): ClassifyResult = ClassifyResult(
      provider = value.optString("provider").takeIf { it.isNotBlank() && it != "null" },
      model = value.optString("model").takeIf { it.isNotBlank() && it != "null" } ?: value.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
      error = value.optString("error").takeIf { it.isNotBlank() && it != "null" },
      stopReason = value.optString("stopReason").takeIf { it.isNotBlank() && it != "null" },
      answers = value.optJSONObject("answers")?.toString().orEmpty(),
      known = stringsOf(value.optJSONArray("known")),
    )

  }
}
