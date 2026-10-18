package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSnapshotReconcilerTest {
  private fun att(id: String, name: String = "$id.txt") = PendingAttachment(
    id = id,
    displayName = name,
    mimeType = "text/plain",
    kind = AttachmentKind.TEXT,
    absolutePath = "/ws/.pi-attachments/$id/$name",
    sizeBytes = 10,
  )

  @Test
  fun settleClearsOnlyWhenComposerUnchangedFromSent() {
    val sent = ComposerDraft("hello", listOf(att("a")))
    val current = ComposerDraft("hello", listOf(att("a")))
    val next = ComposerSnapshotReconciler.afterSettle(sent = sent, current = current)
    assertEquals("", next.text)
    assertTrue(next.attachments.isEmpty())
    assertEquals(listOf("a"), next.consumedAttachmentIds)
  }

  @Test
  fun settlePreservesNewerTypedText() {
    val sent = ComposerDraft("hello", emptyList())
    val current = ComposerDraft("hello and more", emptyList())
    val next = ComposerSnapshotReconciler.afterSettle(sent = sent, current = current)
    assertEquals("hello and more", next.text)
    assertTrue(next.attachments.isEmpty())
  }

  @Test
  fun settlePreservesChipsAddedAfterSend() {
    val sent = ComposerDraft("hi", listOf(att("a")))
    val current = ComposerDraft("hi", listOf(att("a"), att("b")))
    val next = ComposerSnapshotReconciler.afterSettle(sent = sent, current = current)
    assertEquals("", next.text) // text unchanged from sent → clear
    assertEquals(listOf("b"), next.attachments.map { it.id })
    assertEquals(listOf("a"), next.consumedAttachmentIds)
  }

  @Test
  fun settlePreservesBothNewerTextAndNewChips() {
    val sent = ComposerDraft("hi", listOf(att("a")))
    val current = ComposerDraft("next question", listOf(att("a"), att("b")))
    val next = ComposerSnapshotReconciler.afterSettle(sent = sent, current = current)
    assertEquals("next question", next.text)
    assertEquals(listOf("b"), next.attachments.map { it.id })
  }

  @Test
  fun errorRestoresSentWhenComposerStillMatchesSent() {
    val sent = ComposerDraft("keep", listOf(att("x")))
    val current = ComposerDraft("keep", listOf(att("x")))
    val next = ComposerSnapshotReconciler.afterError(sent = sent, current = current)
    assertEquals("keep", next.text)
    assertEquals(listOf("x"), next.attachments.map { it.id })
  }

  @Test
  fun errorKeepsNewerComposerOverStaleSent() {
    val sent = ComposerDraft("old", listOf(att("a")))
    val current = ComposerDraft("newer edit", listOf(att("b")))
    val next = ComposerSnapshotReconciler.afterError(sent = sent, current = current)
    assertEquals("newer edit", next.text)
    assertEquals(listOf("b"), next.attachments.map { it.id })
  }

  @Test
  fun errorRestoresSentAfterOptimisticResetLeftComposerBlank() {
    // Send path clears the composer immediately; on error nothing new was typed
    // (chips still match the sent set), so the sent text must come back.
    val sent = ComposerDraft("restore me", listOf(att("a")))
    val next = ComposerSnapshotReconciler.afterError(sent = sent, current = ComposerDraft("", listOf(att("a"))))
    assertEquals("restore me", next.text)
    assertEquals(listOf("a"), next.attachments.map { it.id })
  }

  @Test
  fun errorWithBlankComposerButNewChipsKeepsNewerState() {
    // Chips typed/added after the send mean the user acted: do not resurrect the sent text.
    val sent = ComposerDraft("old", listOf(att("a")))
    val current = ComposerDraft("", listOf(att("b")))
    val next = ComposerSnapshotReconciler.afterError(sent = sent, current = current)
    assertEquals("", next.text)
    assertEquals(listOf("b"), next.attachments.map { it.id })
  }
}
