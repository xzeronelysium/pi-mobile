package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure list ops for composer attachments.
 */
class ChatAttachmentReducerTest {
  private fun att(id: String, name: String = "f.txt", bytes: Long = 10) = PendingAttachment(
    id = id,
    displayName = name,
    mimeType = "text/plain",
    kind = AttachmentKind.TEXT,
    absolutePath = "/tmp/$id-$name",
    sizeBytes = bytes,
  )

  @Test
  fun addAppendsUntilLimit() {
    var list = emptyList<PendingAttachment>()
    list = ChatAttachmentReducer.add(list, att("1")).getOrThrow()
    list = ChatAttachmentReducer.add(list, att("2")).getOrThrow()
    assertEquals(2, list.size)
    assertTrue(ChatAttachmentReducer.add(list, att("1")).isFailure) // duplicate id
  }

  @Test
  fun addRejectsOverCountLimit() {
    var list = emptyList<PendingAttachment>()
    for (i in 0 until AttachmentLimits.MAX_COUNT) {
      list = ChatAttachmentReducer.add(list, att("id-$i")).getOrThrow()
    }
    assertEquals(AttachmentLimits.MAX_COUNT, list.size)
    assertTrue(ChatAttachmentReducer.add(list, att("one-more")).isFailure)
  }

  @Test
  fun addRejectsOverTotalByteBudget() {
    val nearCap = AttachmentLimits.MAX_TOTAL_BYTES - 5
    val list = listOf(att("a", bytes = nearCap))
    val result = ChatAttachmentReducer.add(list, att("b", bytes = 10))
    assertTrue(result.isFailure)
  }

  @Test
  fun removeById() {
    val list = listOf(att("a"), att("b"), att("c"))
    assertEquals(listOf("a", "c"), ChatAttachmentReducer.remove(list, "b").map { it.id })
  }

  @Test
  fun canSendWithTextOrAttachmentsOnly() {
    assertFalse(ChatAttachmentReducer.canSend("   ", emptyList()))
    assertTrue(ChatAttachmentReducer.canSend("hi", emptyList()))
    assertTrue(ChatAttachmentReducer.canSend("  ", listOf(att("1"))))
    assertTrue(ChatAttachmentReducer.canSend("", listOf(att("1"))))
  }
}
