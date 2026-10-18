package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentLifecycleTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun att(root: File, id: String, name: String = "f.txt"): PendingAttachment {
    val dir = File(root, id).apply { mkdirs() }
    val f = File(dir, name).apply { writeText("body-$id") }
    return PendingAttachment(
      id = id,
      displayName = name,
      mimeType = "text/plain",
      kind = AttachmentKind.TEXT,
      absolutePath = f.absolutePath,
      sizeBytes = f.length(),
    )
  }

  @Test
  fun acceptedPathsStayUntilConsumedNotDeletedOnChipClear() {
    val staging = tmp.newFolder("stage")
    // tryDelete/cleanup use path checks when a stagingRoot is provided.
    val a = att(staging, "a")
    val b = att(staging, "b")
    val life = AttachmentLifecycle()
    life.markAccepted(listOf(a, b))
    // UI cleared chips after accept — files must remain for agent tools.
    assertTrue(File(a.absolutePath).isFile)
    assertTrue(File(b.absolutePath).isFile)
    life.consumeAccepted(listOf(a.id))
    // Consumed + durable stays on disk until the chip is idle or the path is released.
    assertTrue(File(a.absolutePath).isFile)
    assertTrue(File(b.absolutePath).isFile) // b still accepted, not consumed
  }

  @Test
  fun failedSendUnmarkAcceptedAllowsChipDeleteAgain() {
    val staging = tmp.newFolder("stage")
    val a = att(staging, "a")
    val life = AttachmentLifecycle()
    life.markAccepted(listOf(a))
    assertFalse(life.tryDeleteIfIdle(a, stagingRoot = staging))
    assertTrue(File(a.absolutePath).isFile)
    // Send failed — rollback acceptance (and durable ownership).
    life.unmarkAccepted(listOf(a))
    assertTrue(life.tryDeleteIfIdle(a, stagingRoot = staging))
    assertFalse(File(a.absolutePath).isFile)
  }

  @Test
  fun tryDeleteIfIdleRefusesAcceptedThenConsumedDurable() {
    val staging = tmp.newFolder("stage")
    val a = att(staging, "a")
    val life = AttachmentLifecycle()
    life.markAccepted(listOf(a))
    // User remove while accepted in-flight should not wipe agent path.
    val deleted = life.tryDeleteIfIdle(a, stagingRoot = staging)
    assertFalse(deleted)
    assertTrue(File(a.absolutePath).isFile)
    life.consumeAccepted(listOf(a.id))
    // Still durable for history — tryDelete remains blocked.
    assertFalse(life.tryDeleteIfIdle(a, stagingRoot = staging))
    assertTrue(File(a.absolutePath).isFile)
  }

  @Test
  fun cleanupObsoletePreservesDurablePaths() {
    val workspace = tmp.newFolder("ws")
    val staging = File(workspace, AttachmentStaging.STAGING_DIR_NAME).apply { mkdirs() }
    val keep = att(staging, "keep")
    val gone = att(staging, "gone")
    val durable = att(staging, "hist")
    val outside = File(workspace, "important.txt").apply { writeText("do-not-delete") }
    AttachmentLifecycle.cleanupObsolete(
      obsolete = listOf(gone, durable),
      stillNeeded = listOf(keep),
      stagingRoot = staging,
      durablePaths = setOf(durable.absolutePath),
    )
    assertTrue(File(keep.absolutePath).isFile)
    assertFalse(File(gone.absolutePath).isFile)
    assertTrue(File(durable.absolutePath).isFile)
    assertTrue(outside.isFile)
  }

  @Test
  fun pruneStagingRemovesOnlyAgedUnownedChildren() {
    val workspace = tmp.newFolder("ws")
    val staging = File(workspace, AttachmentStaging.STAGING_DIR_NAME).apply { mkdirs() }
    val life = AttachmentLifecycle()
    val owned = att(staging, "owned")
    val debris = att(staging, "debris")
    life.markAccepted(listOf(owned))
    // Make debris look old.
    File(debris.absolutePath).parentFile!!.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000)
    val removed = life.pruneStaging(
      stagingRoot = staging,
      nowMs = System.currentTimeMillis(),
      maxAgeMs = 24L * 60 * 60 * 1000,
    )
    assertTrue(removed >= 1)
    assertTrue(File(owned.absolutePath).isFile)
    assertFalse(File(debris.absolutePath).isFile)
  }

  @Test
  fun pruneStagingNeverTakesAFileTheUserStillHasOnScreen() {
    val workspace = tmp.newFolder("ws")
    val staging = File(workspace, AttachmentStaging.STAGING_DIR_NAME).apply { mkdirs() }
    val life = AttachmentLifecycle()
    val chip = att(staging, "chip")
    val debris = att(staging, "debris")
    val aged = System.currentTimeMillis() - 48L * 60 * 60 * 1000
    File(chip.absolutePath).parentFile!!.setLastModified(aged)
    File(debris.absolutePath).parentFile!!.setLastModified(aged)

    val removed = life.pruneStaging(
      stagingRoot = staging,
      inUse = setOf(chip.absolutePath),
    )
    assertEquals(1, removed)
    assertTrue("a chip the user can still send keeps its file", File(chip.absolutePath).isFile)
    assertFalse(File(debris.absolutePath).isFile)
  }

  @Test
  fun pruneStagingToleratesAMissingOrEmptyStagingRoot() {
    val life = AttachmentLifecycle()
    assertEquals(0, life.pruneStaging(File(tmp.root, "never-created")))
    val empty = File(tmp.newFolder("ws"), AttachmentStaging.STAGING_DIR_NAME).apply { mkdirs() }
    assertEquals(0, life.pruneStaging(empty))
    // A path that is not the staging root at all is never pruned through.
    val other = tmp.newFolder("not-staging")
    att(other, "kept")
    assertEquals(0, life.pruneStaging(other))
    assertTrue(File(other, "kept").isDirectory)
  }

  @Test
  fun pruneStagingBoundsWhatIsLeftBehind() {
    val workspace = tmp.newFolder("ws")
    val staging = File(workspace, AttachmentStaging.STAGING_DIR_NAME).apply { mkdirs() }
    val life = AttachmentLifecycle()
    val children = (1..4).map { att(staging, "e$it") }
    val total = children.sumOf { File(it.absolutePath).length() }

    val removed = life.pruneStaging(stagingRoot = staging, maxTotalBytes = total / 2)
    assertTrue("the bound must bite", removed >= 1)
    val left = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    assertTrue("what survives stays inside the bound: $left", left <= total / 2)
  }
}
