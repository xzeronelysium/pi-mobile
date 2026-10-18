package dev.pi.mobile.backend

import java.io.File

/**
 * The one age/size policy behind the two directories the agent's work accumulates in: the attachment
 * staging root inside the workspace (`chat/AttachmentStaging`, so the agent can read a staged file by
 * absolute path) and the app-private temp/spill root (`NativeFileSystem.tempDir`/`tempFile`, where
 * the durable shell spills output that outgrew its capture cap). Both are written, handed to the
 * agent by absolute path and — until this policy had a caller — never pruned.
 *
 * The rules are deliberately the same for both, so there is one answer to "what may be deleted":
 *
 * - **An entry in use is never pruned.** For staging, "in use" is every path a pending send, an
 *   accepted attachment or a chip on screen still references; for the temp root, a caller that knows
 *   of a live spill passes it in. This is a hard rule, not a heuristic: the agent may still read the
 *   path it was given, and the user may still send the chip they can see.
 * - **Age is the normal trigger.** An unowned entry older than [Policy.maxAgeMs] is pruned.
 * - **The total is bounded.** While what would remain exceeds [Policy.maxTotalBytes], the oldest
 *   unowned entries go too, so a directory that is only ever appended to still has a ceiling.
 *
 * [sweep] tolerates a root that is missing, empty or not a directory: a prune at a lifecycle point
 * must never be the thing that crashes a startup.
 */
internal object StoragePrune {
  /**
   * One candidate for deletion, as the policy sees it.
   *
   * [target] is what gets removed when the entry is pruned — a staging child directory (one per
   * picked file) or a single temp file. [files] are the file paths the entry owns, which is what the
   * in-use check is made against: a staging child is in use when any file inside it is.
   */
  data class Entry(
    val target: String,
    val files: List<String>,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
  )

  /**
   * What to keep and what to remove.
   *
   * @param maxAgeMs an unowned entry at least this old is pruned.
   * @param maxTotalBytes the ceiling kept entries are held to (oldest unowned entries go first).
   */
  data class Policy(val maxAgeMs: Long, val maxTotalBytes: Long)

  /** The decision, computed as a pure function of the entries, the in-use set and the clock. */
  data class Plan(val targets: List<String>, val prunedBytes: Long, val keptBytes: Long)

  /**
   * Which entries to remove.
   *
   * Unowned entries are considered oldest-first so the policy is deterministic and a size bound
   * takes the least valuable first. An in-use entry is excluded before anything else is decided, so
   * neither the age rule nor the size bound can reach it.
   */
  fun plan(entries: List<Entry>, inUse: Set<String>, nowMs: Long, policy: Policy): Plan {
    val kept = entries.filter { entry -> entry.files.any { it in inUse } }
    val candidates = entries.filterNot { entry -> entry.files.any { it in inUse } }.sortedBy { it.lastModifiedMs }

    var keptBytes = kept.sumOf { it.sizeBytes }
    val targets = ArrayList<String>(candidates.size)
    var prunedBytes = 0L
    // The bytes still being decided at each step, so "would this leave more than the bound" is
    // answered for the candidate being looked at, not for the whole list.
    val remaining = LongArray(candidates.size + 1)
    for (index in candidates.indices.reversed()) remaining[index] = remaining[index + 1] + candidates[index].sizeBytes

    candidates.forEachIndexed { index, entry ->
      val aged = nowMs - entry.lastModifiedMs >= policy.maxAgeMs
      val overBudget = keptBytes + remaining[index] > policy.maxTotalBytes
      if (aged || overBudget) {
        targets += entry.target
        prunedBytes += entry.sizeBytes
      } else {
        keptBytes += entry.sizeBytes
      }
    }
    return Plan(targets = targets, prunedBytes = prunedBytes, keptBytes = keptBytes)
  }

  /**
   * What one root holds, in the policy's shape: each child, with the files it owns and the age it is
   * judged by (the child's own timestamp, so one file's mtime cannot keep a whole staging directory
   * alive). A root that is missing or is not a directory has no entries.
   */
  fun entries(root: File): List<Entry> {
    if (!root.isDirectory) return emptyList()
    val children = root.listFiles() ?: return emptyList()
    return children.mapNotNull { child ->
      runCatching {
        if (child.isDirectory) {
          val files = child.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList()
          Entry(target = child.canonicalPath, files = files, lastModifiedMs = child.lastModified(), sizeBytes = files.sumOf { File(it).length() })
        } else {
          val path = child.canonicalPath
          Entry(target = path, files = listOf(path), lastModifiedMs = child.lastModified(), sizeBytes = child.length())
        }
      }.getOrNull()
    }
  }

  /**
   * Apply the policy to [root] and delete what it plans, returning how many entries went.
   *
   * [inUse] is the caller's own knowledge — the paths a pending send or a live command still needs.
   * A missing, empty or non-directory root is a no-op rather than a failure.
   */
  fun sweep(root: File, inUse: Set<String> = emptySet(), nowMs: Long = System.currentTimeMillis(), policy: Policy): Int {
    val plan = plan(entries(root), inUse = inUse, nowMs = nowMs, policy = policy)
    var removed = 0
    for (target in plan.targets) {
      val file = File(target)
      val gone = if (file.isDirectory) file.deleteRecursively() else file.delete()
      if (gone) removed += 1
    }
    return removed
  }
}
