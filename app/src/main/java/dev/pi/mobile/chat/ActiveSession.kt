package dev.pi.mobile.chat

import android.content.Context

/**
 * The conversation the user was last reading in one workspace, remembered between launches.
 *
 * Sessions belong to a workspace: the durable store is partitioned by workspace (see
 * `backend/WorkspaceIdentity`), and so is the "come back here" choice. The key is the workspace's
 * canonical-path digest, so moving from one bound folder to another cannot resume the conversation
 * that belonged to the folder before it — the new workspace has its own record, or none.
 *
 * Nothing about "what this app had open" belongs in the durable conversation database, so the choice
 * lives in one app-private preference. The store writes only an id — no transcript text, no
 * credential, nothing the reader typed.
 *
 * The previous build wrote the id unscoped under [LEGACY_KEY]. That record is read as a fallback
 * exactly until the first scoped record is written (and then removed), so upgrading does not throw
 * the reader back to the root conversation merely because the key moved.
 *
 * Pure apart from its two injected effects, so every rule here is unit-tested off the device.
 */
class ActiveSessionStore(
  private val load: (String) -> String?,
  private val save: (String, String?) -> Unit,
) {
  /** The conversation to ask the host to resume in [workspaceKey], or null when none is usable. */
  fun recorded(workspaceKey: String): Long? =
    parse(load(scopedKey(workspaceKey))) ?: parse(load(LEGACY_KEY))

  /** Remember the conversation the host says is active in [workspaceKey]; null forgets the record. */
  fun record(workspaceKey: String, id: Long?) {
    save(scopedKey(workspaceKey), write(id))
    // A scoped record is now the truth; the pre-workspace fallback must not leak into the next
    // workspace that has never opened a conversation of its own.
    save(LEGACY_KEY, null)
  }

  companion object {
    const val PREFS = "active-session"

    /** The one unscoped key the pre-workspace build wrote. */
    const val LEGACY_KEY = "conversationId"

    private const val SCOPED_PREFIX = "conversationId:"

    /** The preference key for one workspace's remembered conversation. */
    fun scopedKey(workspaceKey: String): String = SCOPED_PREFIX + workspaceKey

    /** A conversation id is a positive number; anything else was not written by this app. */
    fun parse(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    fun write(id: Long?): String? = id?.takeIf { it > 0 }?.toString()
  }
}

/** The app-private preference file the active conversation is recorded in. */
fun activeSessionStore(context: Context): ActiveSessionStore {
  val prefs = context.applicationContext.getSharedPreferences(ActiveSessionStore.PREFS, Context.MODE_PRIVATE)
  return ActiveSessionStore(
    load = { key -> prefs.getString(key, null) },
    save = { key, value ->
      val edit = prefs.edit()
      if (value == null) edit.remove(key) else edit.putString(key, value)
      edit.apply()
    },
  )
}
