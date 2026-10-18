package dev.pi.mobile.chat

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Staging for the composer's general files.
 *
 * Every picked document is copied into the workspace staging root ([AttachmentStaging]) so the
 * agent's own tools and the conversation's history can still reach it. This class owns the Android
 * half of that contract — reading the picked stream, resolving its display name and size, and
 * sweeping debris — while the caller keeps the chips and the notice as Compose state.
 *
 * The current chip list is read through [attachments] on every pick: a user can add files while an
 * earlier batch is still copying, and [ChatAttachmentReducer]'s limits apply to the combined set.
 */
internal class AttachmentController(
  private val contentResolver: ContentResolver,
  private val scope: CoroutineScope,
  private val stagingRoot: () -> File?,
  private val attachments: () -> List<PendingAttachment>,
  private val update: (List<PendingAttachment>) -> Unit,
  private val notice: (String) -> Unit,
) {
  /**
   * Sweep the staging root the agent can see, at every runtime start.
   *
   * What accumulates is the debris: a pick the user removed before sending, an app killed
   * mid-compose. The policy ([AttachmentLifecycle.pruneStaging]) takes an unowned entry past its
   * age, or the oldest unowned entries while the root is over its size bound, and never a path a
   * chip on screen, an accepted send or a durable history reference still holds. A workspace that
   * was never written into is a no-op.
   */
  fun prune(lifecycle: AttachmentLifecycle) {
    val root = stagingRoot() ?: return
    lifecycle.pruneStaging(root, inUse = attachments().map { it.absolutePath }.toSet())
  }

  /**
   * Read each picked document's stream into an app-owned file under the workspace staging dir, off
   * the UI thread. Limits, name sanitisation and collision handling are [AttachmentStaging]'s; a
   * rejection is reported rather than silently dropped.
   */
  fun stage(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val root = stagingRoot() ?: run {
      notice("Choose a workspace before attaching files.")
      return
    }
    scope.launch {
      val failures = ArrayList<String>(2)
      var chips = attachments()
      for (uri in uris) {
        val picked = queryPicked(uri)
        val staged = withContext(Dispatchers.IO) {
          AttachmentStaging.stage(
            stagingRoot = root,
            displayName = picked.first,
            mimeType = contentResolver.getType(uri),
            sizeHint = picked.second,
            openStream = { contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open the picked file") },
            existingCount = chips.size,
            existingTotalBytes = chips.sumOf { it.sizeBytes },
          )
        }
        staged.fold(
          onSuccess = { attachment ->
            ChatAttachmentReducer.add(chips, attachment).fold(
              onSuccess = { chips = it },
              onFailure = { failures += (it.message ?: "Attachment rejected") },
            )
          },
          onFailure = { failures += (it.message ?: "Attachment rejected") },
        )
      }
      update(chips)
      if (failures.isNotEmpty()) notice(failures.joinToString("; "))
    }
  }

  /** A picked document's display name and declared size, when the provider publishes them. */
  private fun queryPicked(uri: Uri): Pair<String?, Long?> {
    val cursor = runCatching {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    }.getOrNull() ?: return null to null
    cursor.use {
      if (!it.moveToFirst()) return null to null
      val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
      val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
      val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
      return name to size
    }
  }
}
