package dev.pi.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.pi.mobile.chat.SessionDrawerReducer
import dev.pi.mobile.chat.WorkspaceEntry
import dev.pi.mobile.chat.WorkspaceFileBrowser
import dev.pi.mobile.chat.WorkspaceFilesReducer
import dev.pi.mobile.chat.WorkspaceFilesUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// The workspace file drawer: listing, preview loading, and the device hand-offs
// ---------------------------------------------------------------------------

/** Open the left-edge drawer at the folder it last showed, and close the history drawer. */
internal fun MainActivity.openFiles() {
  drawer = SessionDrawerReducer.close(drawer)
  files = WorkspaceFilesReducer.open(files)
  loadFiles(files.path)
}

/** Close the drawer and any preview together; there is nothing left to return to. */
internal fun MainActivity.closeFiles() {
  files = WorkspaceFilesReducer.close(files)
  filesLoadEpoch++
  previewLoadEpoch++
}

/** Navigate to a folder. The reducer clears the old rows so none linger under the new crumbs. */
internal fun MainActivity.navigateFiles(path: String) {
  files = WorkspaceFilesReducer.navigate(files, path)
  loadFiles(files.path)
}

/**
 * List one folder off the main thread. The epoch is checked on return so a slow listing for a
 * folder the user already left cannot replace the rows they are looking at.
 */
internal fun MainActivity.loadFiles(path: String) {
  val root = workspace?.root
  if (root == null) {
    files = WorkspaceFilesReducer.failed(files, path, "Choose a workspace first.")
    return
  }
  files = WorkspaceFilesReducer.refreshing(files)
  filesLoadEpoch++
  val epoch = filesLoadEpoch
  lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) { WorkspaceFileBrowser.list(root, path) }
    if (epoch != filesLoadEpoch) return@launch
    result.fold(
      onSuccess = { listing ->
        files = WorkspaceFilesReducer.listed(files, path, listing.entries, listing.truncated)
      },
      onFailure = { error ->
        files = WorkspaceFilesReducer.failed(files, path, error.message ?: "This folder cannot be read.")
      },
    )
  }
}

/**
 * Open a file's preview. Text is read now; an image is decoded by the preview itself; binary files
 * show metadata without pretending to be text. A folder is never previewed.
 */
internal fun MainActivity.openFilePreview(entry: WorkspaceEntry) {
  if (entry.directory) return
  val root = workspace?.root ?: return
  val file = WorkspaceFileBrowser.resolve(root, entry.path) ?: run {
    noticeDialog = "That file is outside the workspace."
    return
  }
  val kind = WorkspaceFilesUi.previewKind(entry.name)
  files = WorkspaceFilesReducer.previewOpened(
    state = files,
    entry = entry,
    absolutePath = file.absolutePath,
    loading = WorkspaceFilesUi.previewsAsText(kind),
  )
  if (kind == dev.pi.mobile.chat.FilePreviewKind.IMAGE && entry.sizeBytes > WorkspaceFilesUi.MAX_IMAGE_BYTES) {
    files = WorkspaceFilesReducer.previewFailed(
      files,
      "This image is ${WorkspaceFilesUi.humanSize(entry.sizeBytes)}; inline previews stop at " +
        "${WorkspaceFilesUi.humanSize(WorkspaceFilesUi.MAX_IMAGE_BYTES)}.",
    )
    return
  }
  if (!WorkspaceFilesUi.previewsAsText(kind)) return
  previewLoadEpoch++
  val epoch = previewLoadEpoch
  lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) { WorkspaceFileBrowser.readText(root, entry.path) }
    if (epoch != previewLoadEpoch) return@launch
    result.fold(
      onSuccess = { preview ->
        files = WorkspaceFilesReducer.previewLoaded(files, preview.text, preview.truncated)
      },
      onFailure = { error ->
        files = WorkspaceFilesReducer.previewFailed(files, error.message ?: "This file cannot be read.")
      },
    )
  }
}

/** Close the preview but leave the file list open underneath it. */
internal fun MainActivity.closeFilePreview() {
  previewLoadEpoch++
  files = WorkspaceFilesReducer.previewClosed(files)
}

/**
 * Stage a workspace file into the composer. The file is copied, not referenced, so the chip
 * survives the file being moved and the agent still reads it through the usual staging path.
 */
internal fun MainActivity.attachWorkspaceFile(entry: WorkspaceEntry) {
  val rejection = WorkspaceFilesUi.attachRejection(entry)
  if (rejection != null) {
    noticeDialog = rejection
    return
  }
  val root = workspace?.root ?: return
  val file = WorkspaceFileBrowser.resolve(root, entry.path) ?: run {
    noticeDialog = "That file is outside the workspace."
    return
  }
  attachmentController.stagePath(file, WorkspaceFilesUi.mimeFor(entry.name))
  // Reveal the composer: the chip lands there, and the drawer is no longer the task.
  files = WorkspaceFilesReducer.close(files)
}

/** Copy the absolute path so it can be pasted into a shell or another app. */
internal fun MainActivity.copyWorkspacePath(entry: WorkspaceEntry) {
  val file = resolveWorkspaceEntry(entry) ?: return
  val clipboard = getSystemService(ClipboardManager::class.java)
  if (clipboard == null) {
    noticeDialog = "Workspace path\n${file.absolutePath}"
    return
  }
  clipboard.setPrimaryClip(ClipData.newPlainText("workspace path", file.absolutePath))
  noticeDialog = "Workspace path copied\n${file.absolutePath}"
}

/** Hand the file to the system share sheet. A FileProvider URI is the only safe hand-off on N+. */
internal fun MainActivity.shareWorkspaceFile(entry: WorkspaceEntry) {
  val file = resolveWorkspaceEntry(entry) ?: return
  val uri = shareableUri(file) ?: return
  val send = Intent(Intent.ACTION_SEND).apply {
    type = WorkspaceFilesUi.mimeFor(entry.name)
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newRawUri(entry.name, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  runCatching { startActivity(Intent.createChooser(send, "Share ${entry.name}")) }
    .onFailure { noticeDialog = "No app can share ${entry.name}." }
}

/** Open the file in whichever app claims its MIME; the in-app preview stays the fallback. */
internal fun MainActivity.openWorkspaceFileWith(entry: WorkspaceEntry) {
  val file = resolveWorkspaceEntry(entry) ?: return
  val uri = shareableUri(file) ?: return
  val view = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, WorkspaceFilesUi.mimeFor(entry.name))
    clipData = ClipData.newRawUri(entry.name, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  runCatching { startActivity(view) }
    .onFailure { noticeDialog = "No app on this device can open ${entry.name}." }
}

private fun MainActivity.resolveWorkspaceEntry(entry: WorkspaceEntry): File? {
  val root = workspace?.root ?: run {
    noticeDialog = "Choose a workspace first."
    return null
  }
  return WorkspaceFileBrowser.resolve(root, entry.path) ?: run {
    noticeDialog = "That file is outside the workspace."
    return null
  }
}

private fun MainActivity.shareableUri(file: File) = runCatching {
  FileProvider.getUriForFile(this, "$packageName.files", file)
}.getOrElse {
  noticeDialog = "This file cannot be handed to another app from the workspace."
  null
}
