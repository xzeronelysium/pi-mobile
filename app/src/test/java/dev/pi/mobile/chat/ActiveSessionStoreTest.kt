package dev.pi.mobile.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * The conversation the app comes back to.
 *
 * The user's complaint is that every launch opens the same session and keeps going back to it, even
 * after they have moved on. Nothing about "what this app had open" belongs in the durable
 * conversation database, so the choice lives in one app-private preference: the id the host last
 * reported as active. The store is pure — a load and a save — so every rule can be tested off the
 * device.
 */
class ActiveSessionStoreTest {
  /** An in-memory stand-in for the app-private preference file. */
  private class FakePrefs {
    var value: String? = null
    val store = ActiveSessionStore(load = { value }, save = { value = it })
    /** A fresh process reading the same preference file. */
    fun reopen() = ActiveSessionStore(load = { value }, save = { value = it })
  }

  @Test fun theRecordedConversationIsTheOneTheNextLaunchRestores() {
    val prefs = FakePrefs()
    prefs.store.record(7L)

    val nextLaunch = ActiveSessionStore(load = { prefs.value }, save = { prefs.value = it })
    assertEquals(7L, nextLaunch.recorded())
  }

  @Test fun switchingUpdatesWhatWouldBeRestored() {
    val prefs = FakePrefs()
    prefs.store.record(7L)
    // The reader opens another session; the newest choice is what comes back.
    prefs.store.record(9L)

    assertEquals(9L, prefs.reopen().recorded())
  }

  @Test fun aNewSessionBecomesTheRecordedOne() {
    val prefs = FakePrefs()
    prefs.store.record(7L)
    prefs.store.record(12L)

    assertEquals(12L, prefs.reopen().recorded())
  }

  @Test fun nothingRecordedRestoresNothing() {
    assertNull(FakePrefs().reopen().recorded())
  }

  @Test fun aStoredValueThatIsNotAConversationIsNotRestored() {
    val prefs = FakePrefs()
    prefs.value = "not-an-id"
    assertNull(prefs.reopen().recorded())

    prefs.value = "-3"
    assertNull("a conversation id is a positive number", prefs.reopen().recorded())

    prefs.value = "   "
    assertNull(prefs.reopen().recorded())
  }

  @Test fun whatTheHostActuallyOpenedIsWhatIsRecordedAfterAFallback() {
    val prefs = FakePrefs()
    // A recorded session that no longer exists: the host opens the root instead and reports it, and
    // that is the id the next launch would restore — the stale one is not kept.
    prefs.store.record(404L)
    prefs.store.record(1L)

    assertEquals(1L, prefs.reopen().recorded())
  }

  @Test fun forgettingRemovesTheRecord() {
    val prefs = FakePrefs()
    prefs.store.record(7L)
    prefs.store.record(null)

    assertNull(prefs.reopen().recorded())
  }

  @Test fun aRecordedIdTravelsAsAJsonNumberForTheRuntime() {
    assertEquals("7", ActiveSessionStore.write(7L))
    assertNull(ActiveSessionStore.write(null))
    assertNull(ActiveSessionStore.write(0L))
    assertEquals(7L, ActiveSessionStore.parse(" 7 "))
  }
}
