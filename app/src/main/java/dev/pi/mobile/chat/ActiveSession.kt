package dev.pi.mobile.chat

import android.content.Context

/**
 * The conversation the user was last reading, remembered between launches.
 *
 * Nothing about "what this app had open" belongs in the durable conversation database, so the choice
 * lives in one app-private preference: the id the host last reported as active. The store writes
 * only that id — no transcript text, no credential, nothing the reader typed.
 *
 * Every launch asks the host to resume the recorded conversation. The host honours it only while the
 * conversation still exists and otherwise opens the conversation it would have opened anyway, which
 * is why the app records whatever the host reports rather than trusting the value it restored: a
 * stale id is replaced by the truth on the first `ready`/`session` frame, so a session the reader has
 * moved away from can never put itself back.
 *
 * Pure apart from its two injected effects, so every rule here is unit-tested off the device.
 */
class ActiveSessionStore(
  private val load: () -> String?,
  private val save: (String?) -> Unit,
) {
  /** The conversation to ask the host to resume, or null when nothing usable was recorded. */
  fun recorded(): Long? = parse(load())

  /** Remember the conversation the host says is active; null forgets the record. */
  fun record(id: Long?) {
    save(write(id))
  }

  companion object {
    const val PREFS = "active-session"
    const val KEY_CONVERSATION = "conversationId"

    /** A conversation id is a positive number; anything else was not written by this app. */
    fun parse(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    fun write(id: Long?): String? = id?.takeIf { it > 0 }?.toString()
  }
}

/** The app-private preference file the active conversation is recorded in. */
fun activeSessionStore(context: Context): ActiveSessionStore {
  val prefs = context.applicationContext.getSharedPreferences(ActiveSessionStore.PREFS, Context.MODE_PRIVATE)
  return ActiveSessionStore(
    load = { prefs.getString(ActiveSessionStore.KEY_CONVERSATION, null) },
    save = { value ->
      val edit = prefs.edit()
      if (value == null) edit.remove(ActiveSessionStore.KEY_CONVERSATION) else edit.putString(ActiveSessionStore.KEY_CONVERSATION, value)
      edit.apply()
    },
  )
}
