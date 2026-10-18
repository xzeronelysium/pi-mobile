package dev.pi.mobile.chat

/**
 * Minimal composer draft / attachment model.
 *
 * NOTE: the durable cut deleted the CLI attachment subsystem. The pure composer-draft
 * reconciliation that survives ([ComposerSnapshotReconciler]) still operates on the draft/chip
 * shapes below, so they live here in their minimal form — only the fields that reducer reads. The
 * rest of the attachment subsystem (limits, classifier, sanitiser, protocol encoding) is
 * intentionally NOT resurrected; a later wave owns the real staging/transport pipeline.
 */

/** Staged attachment class. Binary files are tool-accessible via path, never pretended as
 * model-native vision or as extracted document text. */
enum class AttachmentKind { IMAGE, TEXT, BINARY }

/** One staged attachment owned by the app (real path under workspace staging). */
data class PendingAttachment(
  val id: String,
  val displayName: String,
  val mimeType: String,
  val kind: AttachmentKind,
  val absolutePath: String,
  val sizeBytes: Long,
)

/** The composer's current text plus its staged attachment chips. */
data class ComposerDraft(
  val text: String,
  val attachments: List<PendingAttachment> = emptyList(),
)
