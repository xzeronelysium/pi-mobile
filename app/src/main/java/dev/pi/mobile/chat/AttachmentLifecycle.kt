package dev.pi.mobile.chat

import dev.pi.mobile.backend.StoragePrune
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Staged attachment lifetime.
 *
 * Accepted paths stay on disk for agent tools and for histories that may still
 * reference them. Do not delete immediately when chips clear or when a send
 * fails after markAccepted — call [unmarkAccepted] on failed-send rollback.
 *
 * Retained staging: files needed by accepted/in-flight sends or by explicit
 * durable refs are kept; idle chips may be deleted; age-bound prune only
 * removes unowned staging children.
 */
class AttachmentLifecycle {
  private val acceptedIds = ConcurrentHashMap.newKeySet<String>()
  private val consumedIds = ConcurrentHashMap.newKeySet<String>()
  /** Paths still needed after chip clear (persisted history / tools). */
  private val durablePaths = ConcurrentHashMap.newKeySet<String>()
  private val acceptedAtMs = ConcurrentHashMap<String, Long>()
  private val pathById = ConcurrentHashMap<String, String>()

  fun markAccepted(attachments: List<PendingAttachment>, nowMs: Long = System.currentTimeMillis()) {
    for (a in attachments) {
      acceptedIds.add(a.id)
      acceptedAtMs[a.id] = nowMs
      pathById[a.id] = a.absolutePath
      // Durable until explicitly released — histories/tools may reopen paths.
      durablePaths.add(a.absolutePath)
    }
  }

  /**
   * Failed send / rejected steer: undo acceptance so chips can delete again and
   * the file is not treated as history-owned.
   */
  fun unmarkAccepted(attachments: List<PendingAttachment>) {
    for (a in attachments) {
      acceptedIds.remove(a.id)
      consumedIds.remove(a.id)
      acceptedAtMs.remove(a.id)
      pathById.remove(a.id)
      // Only drop durable if no other accepted id still points at the same path.
      val stillOwned = pathById.values.any { it == a.absolutePath }
      if (!stillOwned) durablePaths.remove(a.absolutePath)
    }
  }

  fun consumeAccepted(ids: Collection<String>) {
    for (id in ids) {
      acceptedIds.remove(id)
      consumedIds.add(id)
      // Keep durable path for retained history/tool access until prune/release.
    }
  }

  fun tryDeleteIfIdle(attachment: PendingAttachment, stagingRoot: File? = null): Boolean {
    if (acceptedIds.contains(attachment.id)) return false
    if (durablePaths.contains(attachment.absolutePath)) return false
    deleteUnderStaging(attachment, stagingRoot)
    consumedIds.remove(attachment.id)
    pathById.remove(attachment.id)
    acceptedAtMs.remove(attachment.id)
    return true
  }

  /**
   * Age/size prune of staging children that are neither in use nor durable.
   *
   * The staging root lives inside the workspace the agent can read, so it is the directory that grows
   * without bound in the place the agent sees; [StoragePrune] is the policy, shared with the
   * app-private temp root so both roots answer "what may be deleted" the same way. A child holding a
   * file that is accepted, durable, or named by the caller as [inUse] (a chip still on screen) is
   * never pruned; an unowned child older than [maxAgeMs] is, and while the survivors exceed
   * [maxTotalBytes] the oldest unowned children go too. A missing or empty root removes nothing.
   */
  fun pruneStaging(
    stagingRoot: File,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    inUse: Set<String> = emptySet(),
  ): Int {
    val root = runCatching { stagingRoot.canonicalFile }.getOrNull() ?: return 0
    if (!root.isDirectory || root.name != AttachmentStaging.STAGING_DIR_NAME) return 0
    val owned = HashSet<String>(durablePaths)
    for ((id, path) in pathById) if (acceptedIds.contains(id)) owned += path
    owned += inUse
    return StoragePrune.sweep(
      root,
      inUse = owned,
      nowMs = nowMs,
      policy = StoragePrune.Policy(maxAgeMs = maxAgeMs, maxTotalBytes = maxTotalBytes),
    )
  }

  companion object {
    /** Default retention for unowned staging debris (24h). */
    const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * Ceiling on what unowned staging debris may occupy after a prune (64 MB).
     *
     * Staged files are copies of the user's own picks, capped per file and in total by
     * [AttachmentLimits], so the bound only ever bites on debris the age rule has not reached yet
     * (an app killed mid-compose leaves one directory per abandoned pick).
     */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 64L * 1024L * 1024L

    fun cleanupObsolete(
      obsolete: List<PendingAttachment>,
      stillNeeded: List<PendingAttachment>,
      stagingRoot: File,
      durablePaths: Set<String> = emptySet(),
    ) {
      val keep = stillNeeded.map { it.absolutePath }.toSet() + durablePaths
      for (a in obsolete) {
        if (a.absolutePath in keep) continue
        deleteUnderStaging(a, stagingRoot)
      }
    }

    internal fun deleteUnderStaging(attachment: PendingAttachment, stagingRoot: File?) {
      runCatching {
        val file = File(attachment.absolutePath).canonicalFile
        if (stagingRoot != null) {
          val root = stagingRoot.canonicalFile
          val prefix = root.path + File.separator
          if (!file.path.startsWith(prefix) && file.path != root.path) return
        }
        val parent = file.parentFile
        file.delete()
        if (parent != null && parent.name != AttachmentStaging.STAGING_DIR_NAME) {
          parent.deleteRecursively()
        }
      }
    }
  }

  private fun deleteUnderStaging(attachment: PendingAttachment, stagingRoot: File?) {
    Companion.deleteUnderStaging(attachment, stagingRoot)
  }
}
