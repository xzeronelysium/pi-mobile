package dev.pi.mobile.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one age/size policy behind both directories that are written and never read again: the
 * attachment staging root inside the workspace, and the app-private temp/spill root.
 *
 * The rules under test are the whole contract: an entry in use is never pruned (a file a pending
 * send or an in-flight command still references), a fresh entry survives, an aged unowned entry
 * goes, and the total is bounded — with a missing or empty root tolerated rather than throwing.
 */
class StoragePruneTest {
  @get:Rule val tmp = TemporaryFolder()

  private val now = 1_700_000_000_000L
  private val hour = 60L * 60L * 1000L
  private val policy = StoragePrune.Policy(maxAgeMs = 24 * hour, maxTotalBytes = 1_000_000L)

  private fun entry(path: String, ageMs: Long, bytes: Long = 10L, files: List<String> = listOf(path)) =
    StoragePrune.Entry(target = path, files = files, lastModifiedMs = now - ageMs, sizeBytes = bytes)

  // ------------------------------------------------------------------ the pure policy

  @Test fun aFreshUnownedEntryIsKeptAndAnAgedOneIsPruned() {
    val plan = StoragePrune.plan(
      entries = listOf(entry("/stage/fresh", ageMs = hour), entry("/stage/stale", ageMs = 48 * hour)),
      inUse = emptySet(),
      nowMs = now,
      policy = policy,
    )
    assertEquals(listOf("/stage/stale"), plan.targets)
    assertEquals(10L, plan.prunedBytes)
    assertEquals(10L, plan.keptBytes)
  }

  @Test fun anEntryStillInUseIsNeverPrunedHoweverOldItIs() {
    val plan = StoragePrune.plan(
      entries = listOf(
        entry("/stage/pending", ageMs = 100 * hour, files = listOf("/stage/pending/file.txt")),
        entry("/stage/debris", ageMs = 100 * hour),
      ),
      inUse = setOf("/stage/pending/file.txt"),
      nowMs = now,
      policy = policy,
    )
    assertEquals(listOf("/stage/debris"), plan.targets)
  }

  @Test fun anEntryIsInUseWhenAnyFileInsideItIs() {
    val plan = StoragePrune.plan(
      entries = listOf(entry("/stage/one", ageMs = 100 * hour, files = listOf("/stage/one/a.txt", "/stage/one/b.txt"))),
      inUse = setOf("/stage/one/b.txt"),
      nowMs = now,
      policy = policy,
    )
    assertTrue(plan.targets.isEmpty())
  }

  @Test fun theTotalIsBoundedByPruningTheOldestUnownedEntriesFirst() {
    val bounded = StoragePrune.Policy(maxAgeMs = 24 * hour, maxTotalBytes = 80L)
    val plan = StoragePrune.plan(
      entries = listOf(
        entry("/stage/newest", ageMs = 1 * hour, bytes = 40L),
        entry("/stage/oldest", ageMs = 3 * hour, bytes = 40L),
        entry("/stage/middle", ageMs = 2 * hour, bytes = 40L),
      ),
      inUse = emptySet(),
      nowMs = now,
      policy = bounded,
    )
    // 120 bytes against an 80 byte bound: the oldest goes, the newest two survive.
    assertEquals(listOf("/stage/oldest"), plan.targets)
    assertEquals(80L, plan.keptBytes)
  }

  @Test fun theBoundNeverTakesSomethingInUse() {
    val bounded = StoragePrune.Policy(maxAgeMs = 24 * hour, maxTotalBytes = 1L)
    val plan = StoragePrune.plan(
      entries = listOf(
        entry("/stage/pending", ageMs = 1 * hour, bytes = 40L, files = listOf("/stage/pending/f.txt")),
        entry("/stage/other", ageMs = 2 * hour, bytes = 40L),
      ),
      inUse = setOf("/stage/pending/f.txt"),
      nowMs = now,
      policy = bounded,
    )
    assertEquals(listOf("/stage/other"), plan.targets)
    assertFalse(plan.targets.contains("/stage/pending"))
  }

  @Test fun nothingToPrunePlansNothing() {
    val plan = StoragePrune.plan(emptyList(), inUse = emptySet(), nowMs = now, policy = policy)
    assertTrue(plan.targets.isEmpty())
    assertEquals(0L, plan.keptBytes)
  }

  // ------------------------------------------------------------------ reading and sweeping a root

  @Test fun aMissingOrEmptyRootIsToleratedRatherThanThrowing() {
    val missing = File(tmp.root, "no-such-directory")
    assertTrue(StoragePrune.entries(missing).isEmpty())
    assertEquals(0, StoragePrune.sweep(missing, nowMs = now, policy = policy))

    val empty = tmp.newFolder("empty")
    assertTrue(StoragePrune.entries(empty).isEmpty())
    assertEquals(0, StoragePrune.sweep(empty, nowMs = now, policy = policy))

    val aFile = tmp.newFile("not-a-directory.txt")
    assertTrue(StoragePrune.entries(aFile).isEmpty())
    assertEquals(0, StoragePrune.sweep(aFile, nowMs = now, policy = policy))
  }

  @Test fun theEntriesOfARootAreWhatThePolicySees() {
    val root = tmp.newFolder("root")
    val childDir = File(root, "abc").apply { mkdirs() }
    val inside = File(childDir, "file.txt").apply { writeText("staged body") }
    val loose = File(root, "loose.txt").apply { writeText("spill") }

    val entries = StoragePrune.entries(root).associateBy { it.target }
    val staged = entries.getValue(childDir.canonicalPath)
    assertEquals(listOf(inside.canonicalPath), staged.files)
    assertEquals(inside.length(), staged.sizeBytes)
    val spill = entries.getValue(loose.canonicalPath)
    assertEquals(listOf(loose.canonicalPath), spill.files)
    assertEquals(loose.length(), spill.sizeBytes)
  }

  @Test fun aSweepReallyRemovesThePrunedEntriesAndKeepsTheRest() {
    val root = tmp.newFolder("root")
    val staleDir = File(root, "stale").apply { mkdirs() }
    val stale = File(staleDir, "f.txt").apply { writeText("old") }
    File(staleDir.path).setLastModified(now - 48 * hour)
    val fresh = File(root, "fresh.txt").apply { writeText("new") }

    val removed = StoragePrune.sweep(root, nowMs = now, policy = policy)
    assertEquals(1, removed)
    assertFalse(stale.exists())
    assertFalse(staleDir.exists())
    assertTrue(fresh.isFile)
  }

  @Test fun aSweepNeverRemovesAnEntryTheCallerSaysIsInUse() {
    val root = tmp.newFolder("root")
    val dir = File(root, "held").apply { mkdirs() }
    val file = File(dir, "f.txt").apply { writeText("pending send") }
    dir.setLastModified(now - 48 * hour)

    val removed = StoragePrune.sweep(root, inUse = setOf(file.canonicalPath), nowMs = now, policy = policy)
    assertEquals(0, removed)
    assertTrue(file.isFile)
  }
}
