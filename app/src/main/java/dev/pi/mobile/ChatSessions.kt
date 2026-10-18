package dev.pi.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.pi.mobile.chat.ChatEntry
import dev.pi.mobile.chat.ConversationTree
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.ExportFiles
import dev.pi.mobile.chat.InboxState
import dev.pi.mobile.chat.SessionDialogsReducer
import dev.pi.mobile.chat.SessionDrawerReducer
import dev.pi.mobile.chat.SessionRow
import dev.pi.mobile.chat.TreeNodeRow
import dev.pi.mobile.chat.UsageState
import dev.pi.mobile.chat.WorkspaceFilesReducer

// ---------------------------------------------------------------------------
// Session surface: the drawer, conversation switching, export and device intents
// ---------------------------------------------------------------------------

/** The list, the usage and the queue are one request each; the drawer shows all three. */
internal fun MainActivity.requestSessionSurface() {
  runtime?.sessions()
  runtime?.usage()
  runtime?.queue()
}

internal fun MainActivity.requestInbox() {
  runtime?.queue()
}

/**
 * Opening the drawer asks the host for the list, usage and queue. [fromTreeAction] is true only on
 * a tree/fork call path, where the tree section opens with it.
 */
internal fun MainActivity.openHistory(fromTreeAction: Boolean) {
  files = WorkspaceFilesReducer.close(files)
  drawer = SessionDrawerReducer.open(drawer, fromTreeAction)
  requestSessionSurface()
}

internal fun MainActivity.closeHistory() {
  drawer = SessionDrawerReducer.close(drawer)
}

/**
 * Nothing about the run in flight or its queue belongs to another conversation, so switching
 * clears both along with the transcript. History is not re-requested here: the host pushes it for
 * the conversation it actually switched to, and asking earlier would render the old rows.
 */
internal fun MainActivity.resetForSessionChange() {
  applyTranscript(emptyList())
  optimisticRowIds = emptySet()
  busy = false
  usage = UsageState()
  inbox = InboxState()
  tree = ConversationTree()
  activeConversationId = null
  dropStagedForReset()
}

internal fun MainActivity.switchSession(row: SessionRow) {
  if (!SessionDrawerReducer.canSwitch(busy, row)) return
  resetForSessionChange()
  runtime?.openSession(row.id)
  drawer = SessionDrawerReducer.close(drawer)
  requestSessionSurface()
}

internal fun MainActivity.newSession() {
  if (busy) return
  resetForSessionChange()
  runtime?.newSession()
  drawer = SessionDrawerReducer.close(drawer)
  requestSessionSurface()
}

/** A fork keeps the drawer open and shows the tree it forked from. */
internal fun MainActivity.forkAt(node: TreeNodeRow) {
  val entryId = node.entryId ?: return
  if (!SessionDrawerReducer.canFork(busy, node)) return
  resetForSessionChange()
  runtime?.forkSession(entryId)
  drawer = SessionDrawerReducer.open(drawer, fromTreeAction = true)
  requestSessionSurface()
  runtime?.tree()
}

internal fun MainActivity.submitRename() {
  val id = drawer.renamingId ?: return
  val original = sessions.rows.firstOrNull { it.id == id }?.name.orEmpty()
  val name = SessionDialogsReducer.renameArg(drawer.renaming)
  if (SessionDialogsReducer.canRename(original, name)) runtime?.renameSession(id, name)
  drawer = SessionDrawerReducer.cancelRename(drawer)
}

internal fun MainActivity.export(format: String) {
  if (!SessionDrawerReducer.canExport(busy, sessions)) {
    noticeDialog = "Stop the current reply before exporting."
    return
  }
  drawer = SessionDrawerReducer.setOverflow(drawer, false)
  runtime?.exportSession(format)
}

/**
 * Write the exported document beside the app's other files, so the user has a real file to take
 * off the phone. [ExportFiles] owns the write and the report; this only surfaces the notice.
 */
internal fun MainActivity.writeExport(result: DurableEvent.ExportResult) {
  if (!result.hasContent) {
    noticeDialog = "The runtime returned no export content."
    return
  }
  val directory = ExportFiles.directory(getExternalFilesDir("exports"), filesDir)
  ExportFiles.write(result, directory).fold(
    onSuccess = { file -> noticeDialog = ExportFiles.notice(file, result) },
    onFailure = { error -> noticeDialog = error.message ?: "Could not write the export." },
  )
}

internal fun MainActivity.copyLastAnswer() {
  val answer = transcript.filterIsInstance<ChatEntry.Assistant>().lastOrNull()?.markdown
  if (answer.isNullOrBlank()) {
    noticeDialog = "There is no answer to copy yet."
    return
  }
  val clipboard = getSystemService(ClipboardManager::class.java)
  if (clipboard == null) {
    noticeDialog = "This device has no clipboard service."
    return
  }
  clipboard.setPrimaryClip(ClipData.newPlainText("pi answer", answer))
  noticeDialog = "Copied the last answer (${answer.length} characters)."
}

/**
 * Hand an upstream sign-in URL to the device browser; the loopback callback comes back to us.
 * A failure is surfaced to the user rather than swallowed — the sheet keeps its manual button.
 */
internal fun MainActivity.openInBrowser(url: String) {
  try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  } catch (error: ActivityNotFoundException) {
    noticeDialog = "No browser is installed for $url; open it on another device and paste the code here."
  } catch (error: Exception) {
    noticeDialog = "Could not open the browser (${error.javaClass.simpleName}); open $url on another device and paste the code here."
  }
}

/**
 * The system's special "All files access" page for this app. Granting it is what lets a bound
 * folder on shared storage be used as the real cwd instead of an app-private mirror.
 */
internal fun MainActivity.openAllFilesAccessSettings() {
  val packageUri = Uri.fromParts("package", packageName, null)
  try {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
    } else {
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
    startActivity(intent)
  } catch (error: ActivityNotFoundException) {
    noticeDialog = "This device has no All files access settings page."
  } catch (error: Exception) {
    noticeDialog = "Could not open All files access settings (${error.javaClass.simpleName})."
  }
}
