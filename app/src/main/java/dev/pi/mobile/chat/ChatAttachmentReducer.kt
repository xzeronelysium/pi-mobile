package dev.pi.mobile.chat

/** Pure list ops for composer attachments. */
object ChatAttachmentReducer {
  fun add(current: List<PendingAttachment>, item: PendingAttachment): Result<List<PendingAttachment>> {
    if (current.any { it.id == item.id }) {
      return Result.failure(IllegalArgumentException("Duplicate attachment id"))
    }
    if (current.size >= AttachmentLimits.MAX_COUNT) {
      return Result.failure(IllegalArgumentException("Too many attachments (max ${AttachmentLimits.MAX_COUNT})"))
    }
    val total = current.sumOf { it.sizeBytes } + item.sizeBytes
    if (total > AttachmentLimits.MAX_TOTAL_BYTES) {
      return Result.failure(IllegalArgumentException("Total attachment size exceeds limit"))
    }
    return Result.success(current + item)
  }

  fun remove(current: List<PendingAttachment>, id: String): List<PendingAttachment> =
    current.filterNot { it.id == id }

  fun canSend(text: String, attachments: List<PendingAttachment>): Boolean =
    text.trim().isNotEmpty() || attachments.isNotEmpty()
}
