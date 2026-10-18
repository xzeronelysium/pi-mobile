package dev.pi.mobile

import android.util.Log
import dev.pi.mobile.backend.ConsoleRedaction
import dev.pi.mobile.chat.ChatEntry
import dev.pi.mobile.chat.ComposerDraft
import dev.pi.mobile.chat.ComposerSnapshotReconciler
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.DurableTranscriptReducer
import dev.pi.mobile.chat.ModelKind
import dev.pi.mobile.chat.SessionList
import dev.pi.mobile.chat.SessionListReducer
import dev.pi.mobile.chat.SettingsUiReducer
import dev.pi.mobile.chat.TranscriptFoldReducer
import dev.pi.mobile.chat.TranscriptGrouping

// ---------------------------------------------------------------------------
// Durable event routing and transcript reconciliation
// ---------------------------------------------------------------------------

/**
 * Apply one host frame. Every branch is a state transition on this Activity; the domain decisions
 * themselves live in the pure reducers under `chat/`, so this file only maps events onto them.
 */
internal fun MainActivity.handle(event: DurableEvent) {
  when (event) {
  is DurableEvent.Loaded -> Unit
  is DurableEvent.Ready -> {
    ready = true
    providers = event.providers
    // Whatever the host actually opened is the conversation this app comes back to next launch:
    // the id it resumed when there was one, and the honest fallback when the recorded one was gone.
    event.conversationId?.let { recordActiveConversation(it) }
    runtime?.history()
    requestCatalogs()
    requestSessionSurface()
    // The app-owned commands and the MCP server list, so both surfaces have rows as soon as the
    // runtime is up rather than on first open.
    runtime?.commands()
  }
  // A whole-history read rebuilds the transcript; a page of a longer conversation is merged, so the
  // read can never delete the rows it does not carry (the settle/load/switch reconcile).
  is DurableEvent.History -> applyTranscript(reconcileOptimistic(DurableTranscriptReducer.applyHistory(transcript, event.rows, event.complete)))
  is DurableEvent.Snapshot -> applyTranscript(reconcileOptimistic(DurableTranscriptReducer.mergeEntries(transcript, event.rows)))
  // The frame carries the rows the UI renders, so it merges them by id instead of re-requesting
  // the whole transcript. `history()` stays the reconciliation read on settle/switch/error.
  is DurableEvent.Message -> mergeMessage(event)
  is DurableEvent.Submitted -> Unit
  is DurableEvent.Settled -> {
    busy = false
    // The run consumed the sent chips; text is cleared only if the field still matches what went
    // out, and a chip the user added after sending is kept.
    settlePendingSend(failed = false)
    runtime?.history()
    requestSessionSurface()
  }
  is DurableEvent.Failure -> {
    // A turn that failed mid-stream can leave an optimistic bubble or a half-rendered row behind;
    // the whole-history read is the reconciliation path, so the transcript never drifts for good.
    val inFlight = busy
    busy = false
    // A failed send puts the exact text and chips back, but newer input wins.
    settlePendingSend(failed = true)
    noticeDialog = event.message.ifBlank { "Backend error" }
    if (inFlight && ready) runtime?.history()
  }
  // The history drawer: the list the host keeps, the active conversation marked current.
  is DurableEvent.Conversations -> sessions = withCurrent(event.list)
  is DurableEvent.Tree -> tree = event.tree
  // The app-owned commands: the composer's skills-only autocomplete and the Resources sheet read
  // these rows. Nothing here expands a skill — the host selects the section on the invoking turn.
  is DurableEvent.Commands -> commands = event.catalog.copy(loading = false)
  is DurableEvent.Mcp -> mcp = event.state
  is DurableEvent.Usage -> usage = event.usage
  is DurableEvent.Inbox -> inbox = event.inbox
  is DurableEvent.Export -> writeExport(event.export)
  // Every switch — open, new, fork — is reported here, and the newest choice is what a later launch
  // resumes. Reset keeps the same conversation, so nothing changes for it.
  is DurableEvent.Switched -> {
    event.id.toLongOrNull()?.let { recordActiveConversation(it) }
    runtime?.sessions()
  }
  // The admission, then the summarization's own outcome: the second frame is what the user reads.
  is DurableEvent.Compacted -> {
    noticeDialog = when {
      event.status == "admitted" -> null
      event.error != null -> "Could not compact: ${event.error}"
      event.entryId != null -> "Compacted · summary placed as entry ${event.entryId}"
      else -> "Compaction finished (${event.status})."
    }
    runtime?.sessions()
    runtime?.usage()
    runtime?.history()
  }
  // `queued`, `withdrawn`, `session.reset`: the queue and the list are stale, nothing else is.
  is DurableEvent.SessionChanged -> {
    runtime?.sessions()
    requestInbox()
  }
  // Every model of every provider is kept, so a hidden row can always be shown again and a
  // provider that is not signed in still reports how many models it has.
  is DurableEvent.Models -> keep(event.type) { catalog = catalog + (it to event.rows) }
  is DurableEvent.Available -> keep(event.type) { available = available + (it to event.rows) }
  is DurableEvent.Refresh -> {
    settings = SettingsUiReducer.setOperationResult(
      settings,
      if (event.errors.isEmpty()) null else "refresh errors: ${event.errors.joinToString("; ")}",
    )
  }
  is DurableEvent.AuthState -> authRows = event.rows
  is DurableEvent.AuthStarted -> {
    if (settings.signIn == null) {
      // A flow started by the host without a matching UI action still gets a live session.
      settings = SettingsUiReducer.startSignIn(settings, event.provider, event.name, event.authType, event.subscription)
      authGeneration = settings.loginGeneration
    }
  }
  is DurableEvent.AuthNotice -> {
    // Decide on the pre-update state (its `openedUrl` is what makes the launch single-shot), then
    // record the notice so the sheet shows the URL and the manual reopen button.
    authHandoff.onNotice(settings, authGeneration, event)
    settings = SettingsUiReducer.applyNotice(settings, authGeneration, event)
  }
  is DurableEvent.AuthPrompt -> settings = SettingsUiReducer.applyPrompt(settings, authGeneration, event)
  is DurableEvent.AuthPromptDone -> settings = SettingsUiReducer.promptAnswered(settings, event.requestId)
  is DurableEvent.AuthSettled -> {
    settings = SettingsUiReducer.authSettled(settings, authGeneration, event)
    requestCatalogs()
  }
  is DurableEvent.AuthFailed -> settings = SettingsUiReducer.authFailed(settings, authGeneration, event)
  is DurableEvent.AuthFinished -> {
    settings = SettingsUiReducer.authFinished(settings, authGeneration, event)
    runtime?.authState()
  }
  is DurableEvent.AuthResolved -> Unit
  is DurableEvent.ImagesResult -> settings = SettingsUiReducer.setOperationResult(
    settings,
    when {
      event.error != null -> "images: ${event.error}"
      event.known.isNotEmpty() -> "images: known ids ${event.known.joinToString(", ")}"
      else -> "images: ${event.imageCount} image(s), ${event.imageBytes} bytes" +
        (event.text.takeIf { it.isNotBlank() }?.let { " · ${it.take(120)}" } ?: "")
    },
  )
  is DurableEvent.ClassifyResult -> settings = SettingsUiReducer.setOperationResult(
    settings,
    when {
      event.error != null -> "classify: ${event.error}"
      event.known.isNotEmpty() -> "classify: known ids ${event.known.joinToString(", ")}"
      else -> "classify: ${event.answers}"
    },
  )
  // A frame this build has no arm for is surfaced, never silently dropped: the flow frames (and any
  // future host event) reach the console with their type and raw payload, so nothing the runtime
  // emits can go invisible. It carries no product meaning here — the flow layer is typed elsewhere.
  is DurableEvent.Unknown -> Log.println(
    Log.INFO,
    ConsoleRedaction.TAG,
    ConsoleRedaction.redact("unknown.event type=${event.type} ${event.payload}"),
  )
  }
}

/** The host reports the current conversation as an id; until it does, the list decides. */
internal fun MainActivity.withCurrent(list: SessionList): SessionList {
  val id = list.currentId ?: activeConversationId?.toString() ?: return list
  return SessionListReducer.select(list, id)
}

/** Apply a model-list event to the state for its model type. */
internal fun MainActivity.keep(type: String, apply: (ModelKind) -> Unit) {
  ModelKind.ofWire(type)?.let(apply)
}

/** Replace the transcript and keep the open-work set bounded to the runs now on screen. */
internal fun MainActivity.applyTranscript(entries: List<ChatEntry>) {
  transcript = entries
  transcriptFollowEpoch++
  transcriptFold = TranscriptFoldReducer.reconcile(transcriptFold, TranscriptGrouping.group(entries))
}

/**
 * Merge one incremental transcript frame: upsert its rows by id and retire the transient rows it
 * names. A frame that changed nothing is dropped without touching the transcript. The composer's
 * optimistic rows go the moment a durable row for their turn lands.
 */
internal fun MainActivity.mergeMessage(event: DurableEvent.Message) {
  val merged = DurableTranscriptReducer.applyMessage(transcript, event.rows, event.removed, event.full, event.snapshot)
  val superseded = if (event.snapshot || event.rows.any { it is ChatEntry.User }) optimisticRowIds else emptySet()
  val next = if (superseded.isEmpty()) merged else merged.filterNot { it.id in superseded }
  if (next === transcript) return
  if (superseded.isNotEmpty()) optimisticRowIds = emptySet()
  applyTranscript(next)
}

/** The host named the conversation it is reading: keep it as the one the next launch resumes. */
internal fun MainActivity.recordActiveConversation(id: Long) {
  activeConversationId = id
  activeSession.record(id)
}

/** A whole-history rebuild already supersedes the composer's optimistic rows; forget their ids. */
internal fun MainActivity.reconcileOptimistic(entries: List<ChatEntry>): List<ChatEntry> {
  if (optimisticRowIds.isEmpty()) return entries
  val staged = optimisticRowIds
  optimisticRowIds = emptySet()
  return entries.filterNot { it.id in staged }
}

/** Chips and the pending snapshot belong to the conversation and workspace they were staged in. */
internal fun MainActivity.dropStagedForReset() {
  val staged = attachments
  attachments = emptyList()
  pendingSend = null
  for (attachment in staged) attachmentLifecycle.tryDeleteIfIdle(attachment, stagingRoot())
}

/**
 * Reconcile the composer once a send settles.
 *
 * On success the run consumed the sent chips, and the text is cleared only when the field still
 * matches what went out — a chip the user added afterwards is kept. On failure the exact text and
 * chips come back, but only while the field is still blank and the chips still equal the sent set;
 * anything typed since wins.
 */
internal fun MainActivity.settlePendingSend(failed: Boolean) {
  val sent = pendingSend ?: return
  val current = ComposerDraft(text = draft, attachments = attachments)
  val next = if (failed) {
    ComposerSnapshotReconciler.afterError(sent, current)
  } else {
    ComposerSnapshotReconciler.afterSettle(sent, current)
  }
  draft = next.text
  attachments = next.attachments
  if (failed) {
    // The turn never ran, so the files belong to the composer again, not to history.
    attachmentLifecycle.unmarkAccepted(sent.attachments)
  } else {
    attachmentLifecycle.consumeAccepted(next.consumedAttachmentIds)
  }
  pendingSend = null
}
