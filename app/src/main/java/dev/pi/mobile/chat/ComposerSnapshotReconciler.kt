package dev.pi.mobile.chat

/**
 * Reconcile composer text/chips after send settle, error, or steer/follow-up accept.
 * Never wipe text or chips the user added after the snapshot that left the composer.
 *
 * The draft/chip shapes ([ComposerDraft], [PendingAttachment]) are defined locally in
 * ComposerModels.kt — the durable cut removed the CLI attachment subsystem they used to
 * live in; only the fields this reconciler reads are carried here.
 */
object ComposerSnapshotReconciler {
  data class Result(
    val text: String,
    val attachments: List<PendingAttachment>,
    /** Attachment ids from [sent] that the agent turn consumed. */
    val consumedAttachmentIds: List<String> = emptyList(),
  )

  /**
   * After a successful send settle (or steer/follow-up accept):
   * - clear text only if it still matches what was sent
   * - drop chips that were in the sent set; keep any chips added afterward
   */
  fun afterSettle(sent: ComposerDraft?, current: ComposerDraft): Result {
    if (sent == null) {
      return Result(text = current.text, attachments = current.attachments)
    }
    val sentIds = sent.attachments.map { it.id }.toSet()
    val textUnchanged = current.text == sent.text
    return Result(
      text = if (textUnchanged) "" else current.text,
      attachments = current.attachments.filter { it.id !in sentIds },
      consumedAttachmentIds = sent.attachments.map { it.id },
    )
  }

  /**
   * After send error: restore sent draft when the composer still matches it or
   * was already cleared by the optimistic send reset (nothing typed since);
   * if the user already typed newer text or changed chips, keep the newer state.
   */
  fun afterError(sent: ComposerDraft?, current: ComposerDraft): Result {
    if (sent == null) {
      return Result(text = current.text, attachments = current.attachments)
    }
    val sentIds = sent.attachments.map { it.id }.toSet()
    val currentIds = current.attachments.map { it.id }.toSet()
    val textMatches = current.text == sent.text
    val textCleared = current.text.isBlank()
    val chipsMatch = currentIds == sentIds
    if ((textMatches || textCleared) && chipsMatch) {
      return Result(text = sent.text, attachments = sent.attachments)
    }
    // Newer composer wins entirely.
    return Result(text = current.text, attachments = current.attachments)
  }
}
