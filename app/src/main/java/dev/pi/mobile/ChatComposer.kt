package dev.pi.mobile

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.lifecycleScope
import dev.pi.mobile.chat.AttachmentKind
import dev.pi.mobile.chat.AttachmentStaging
import dev.pi.mobile.chat.AttachedFileRef
import dev.pi.mobile.chat.ChatAttachmentReducer
import dev.pi.mobile.chat.ChatEntry
import dev.pi.mobile.chat.CommandDiscoveryReducer
import dev.pi.mobile.chat.CommandRow
import dev.pi.mobile.chat.ComposerCommandDispatcher
import dev.pi.mobile.chat.ComposerDispatch
import dev.pi.mobile.chat.ComposerDraft
import dev.pi.mobile.chat.EXPORT_MARKDOWN
import dev.pi.mobile.chat.PendingAttachment
import dev.pi.mobile.chat.PickerSheet
import dev.pi.mobile.chat.PromptAttachments
import dev.pi.mobile.chat.SettingsUiReducer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Composer: sending, local commands and attachment staging
// ---------------------------------------------------------------------------

/**
 * Send the draft, plus any staged files, as one prompt. Mid-run the same control steers into the
 * running turn, or queues a follow-up when the follow-up toggle is on; only a blank draft makes
 * the control the stop glyph, so nothing is dispatched here without text. The composer is cleared
 * only once the prompt is actually dispatched; a refusal (not ready, no model) leaves the text
 * and the chips exactly as they were.
 */
internal fun MainActivity.send() {
  val text = draft.trim()
  if (!ready) return
  if (busy) {
    if (text.isEmpty()) return
    if (queueFollowUp) runtime?.followUp(text) else runtime?.steer(text)
    draft = ""
    requestInbox()
    return
  }
  if (text.isEmpty() && attachments.isEmpty()) return
  // A slash command is resolved locally before anything is dispatched. A skill runs as a model
  // turn with the host selecting its section; an app command runs here, with no model call and no
  // credentials; one that names nothing is refused rather than sent to the model.
  when (val dispatch = ComposerCommandDispatcher.classify(text, CommandDiscoveryReducer.catalog(commands))) {
    is ComposerDispatch.Unknown -> {
      noticeDialog = ComposerCommandDispatcher.rejectionNotice(dispatch.token)
      return
    }
    is ComposerDispatch.Local -> {
      runLocalCommand(dispatch.row, dispatch.arguments)
      return
    }
    else -> Unit
  }
  if (settings.selectedProvider.isBlank() || settings.selectedModel.isBlank()) {
    noticeDialog = "Choose a model first."
    settings = SettingsUiReducer.openSheet(settings, PickerSheet.Model)
    return
  }
  val staged = attachments
  lifecycleScope.launch {
    // Text is read off the UI thread; a file that cannot be read stays a path reference rather
    // than being silently dropped. [PromptAttachments] enforces the inline caps.
    val prompt = if (staged.isEmpty()) text else withContext(Dispatchers.IO) { buildPrompt(text, staged) }
    dispatchSend(prompt = prompt, draftText = text, staged = staged)
  }
}

/**
 * The long press on the send control: a follow-up, never a steer. It falls back to a plain send
 * while idle, so the gesture is never a dead end. Mid-run only the text can be queued, so any
 * staged chips stay in the composer rather than being silently dropped.
 */
internal fun MainActivity.sendFollowUp() {
  if (!ready) return
  if (!busy) {
    send()
    return
  }
  val text = draft.trim()
  if (text.isEmpty()) return
  runtime?.followUp(text)
  draft = ""
  requestInbox()
}

/**
 * Run one of the app's own commands. None of them calls the model or reads a credential, so they
 * all work before any provider is signed in — which is the point of keeping them reachable.
 */
internal fun MainActivity.runLocalCommand(row: CommandRow, arguments: String) {
  val handled = when (row.name) {
    "new" -> { newSession(); true }
    "compact" -> { runtime?.compactSession(arguments.takeIf { it.isNotBlank() }); true }
    "reload" -> { refreshModels(); true }
    "export" -> { export(EXPORT_MARKDOWN); true }
    "usage" -> { requestSessionSurface(); true }
    else -> false
  }
  draft = ""
  // A local command is not a send: the chips stay in the composer and nothing was submitted.
  noticeDialog = if (handled) "ran /${row.name}" else "/${row.name} has no handler on this build"
}

/** The prompt the model receives: the user's own text, then one `<file …>` block per staged file. */
internal fun MainActivity.buildPrompt(text: String, staged: List<PendingAttachment>): String =
  PromptAttachments.build(
    prompt = text,
    staged = staged.map { attachment ->
      val content = if (attachment.kind == AttachmentKind.TEXT && attachment.sizeBytes <= PromptAttachments.MAX_TEXT_INLINE) {
        runCatching { File(attachment.absolutePath).readText() }.getOrNull()
      } else {
        null
      }
      PromptAttachments.Staged(attachment, content)
    },
  )

/**
 * Dispatch one prompt. The pending snapshot holds the user's own text and chips — never the
 * tag-wrapped prompt — so a failed send restores a clean draft the next send can wrap again
 * instead of double-wrapping the file blocks.
 */
internal fun MainActivity.dispatchSend(prompt: String, draftText: String, staged: List<PendingAttachment>) {
  pendingSend = ComposerDraft(text = draftText, attachments = staged)
  draft = ""
  // The bubble shows the sender's own words plus real cards for the files they picked. It is
  // optimistic: the durable user row that follows supersedes it.
  val optimistic = ChatEntry.User(text = prompt)
  optimisticRowIds = optimisticRowIds + optimistic.id
  transcript = transcript + optimistic
  busy = true
  // The files must survive the run and any history that still references them.
  attachmentLifecycle.markAccepted(staged)
  runtime?.send(
    JSONObject()
      .put("provider", settings.selectedProvider)
      .put("prompt", prompt)
      .put("requestId", UUID.randomUUID().toString())
      .put("modelId", settings.selectedModel)
      .put("thinkingLevel", settings.effort),
  )
}

/** Drop one chip; its staged file is deleted only when nothing else owns it. */
internal fun MainActivity.removeAttachment(id: String) {
  val target = attachments.firstOrNull { it.id == id } ?: return
  attachments = ChatAttachmentReducer.remove(attachments, id)
  attachmentLifecycle.tryDeleteIfIdle(target, stagingRoot())
}

/** Show a card's real path and copy it, so the user can reach the staged file on the device. */
internal fun MainActivity.openAttachment(file: AttachedFileRef) {
  val path = file.path ?: return
  val clipboard = getSystemService(ClipboardManager::class.java)
  if (clipboard == null) {
    noticeDialog = "Attachment path\n$path"
    return
  }
  clipboard.setPrimaryClip(ClipData.newPlainText("attachment path", path))
  noticeDialog = "Attachment path copied\n$path"
}

/** The workspace staging root: files the agent's own tools can read by absolute path. */
internal fun MainActivity.stagingRoot(): File? = workspace?.root?.let { File(it, AttachmentStaging.STAGING_DIR_NAME) }

/**
 * Sweep the staging root the agent can see, at every runtime start. The policy lives in
 * [dev.pi.mobile.chat.AttachmentLifecycle] and the Android half in [dev.pi.mobile.chat.AttachmentController].
 */
internal fun MainActivity.pruneStagedAttachments() {
  attachmentController.prune(attachmentLifecycle)
}
