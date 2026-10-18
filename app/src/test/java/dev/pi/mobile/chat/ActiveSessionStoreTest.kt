package dev.pi.mobile.chat

import org.junit.Assert.*
import org.junit.Test

/**
 * The conversation the app comes back to, per workspace.
 *
 * The user's complaint is that a session followed them into a workspace it did not belong to. The
 * durable store is partitioned by workspace, and so is this choice: the preference key carries the
 * workspace digest, so one workspace can never restore another's conversation. The store is pure —
 * a load and a save — so every rule can be tested off the device.
 */
class ActiveSessionStoreTest {
  /** An in-memory stand-in for the app-private preference file. */
  private class FakePrefs {
    val values = mutableMapOf<String, String?>()
    val store = ActiveSessionStore(
      load = { key -> values[key] },
      save = { key, value -> if (value == null) values.remove(key) else values[key] = value },
    )
    /** A fresh process reading the same preference file. */
    fun reopen() = ActiveSessionStore(
      load = { key -> values[key] },
      save = { key, value -> if (value == null) values.remove(key) else values[key] = value },
    )
  }

  private val alpha = "alpha-workspace"
  private val beta = "beta-workspace"

  @Test fun theRecordedConversationIsTheOneTheNextLaunchRestores() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)

    assertEquals(7L, prefs.reopen().recorded(alpha))
  }

  @Test fun switchingUpdatesWhatWouldBeRestored() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)
    // The reader opens another session in the same workspace; the newest choice is what comes back.
    prefs.store.record(alpha, 9L)

    assertEquals(9L, prefs.reopen().recorded(alpha))
  }

  @Test fun aNewSessionBecomesTheRecordedOne() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)
    prefs.store.record(alpha, 12L)

    assertEquals(12L, prefs.reopen().recorded(alpha))
  }

  @Test fun eachWorkspaceRestoresOnlyItsOwnConversation() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)
    prefs.store.record(beta, 42L)

    assertEquals(7L, prefs.reopen().recorded(alpha))
    assertEquals(42L, prefs.reopen().recorded(beta))
  }

  @Test fun aWorkspaceWithNoRecordRestoresNothingEvenWhenAnotherWorkspaceHasOne() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)

    assertNull(prefs.reopen().recorded(beta))
  }

  @Test fun thePreferenceKeysAreScopedSoOneWorkspaceCannotOverwriteAnother() {
    assertNotEquals(
      ActiveSessionStore.scopedKey(alpha),
      ActiveSessionStore.scopedKey(beta),
    )
    assertNotEquals(ActiveSessionStore.LEGACY_KEY, ActiveSessionStore.scopedKey(alpha))
  }

  @Test fun theLegacyUnscopedRecordIsAdoptedOnceThenRemoved() {
    val prefs = FakePrefs()
    // A pre-workspace build left the id under the old unscoped key.
    prefs.values[ActiveSessionStore.LEGACY_KEY] = "7"

    assertEquals("the upgrade adopts the old choice", 7L, prefs.reopen().recorded(alpha))
    // The host reports what it actually opened; recording it scopes the choice and drops the old key.
    prefs.store.record(alpha, 7L)

    assertFalse(prefs.values.containsKey(ActiveSessionStore.LEGACY_KEY))
    assertEquals(7L, prefs.reopen().recorded(alpha))
    assertNull("the old choice must not leak into a workspace that never had one", prefs.reopen().recorded(beta))
  }

  @Test fun nothingRecordedRestoresNothing() {
    assertNull(FakePrefs().reopen().recorded(alpha))
  }

  @Test fun aStoredValueThatIsNotAConversationIsNotRestored() {
    val prefs = FakePrefs()
    prefs.values[ActiveSessionStore.scopedKey(alpha)] = "not-an-id"
    assertNull(prefs.reopen().recorded(alpha))

    prefs.values[ActiveSessionStore.scopedKey(alpha)] = "-3"
    assertNull("a conversation id is a positive number", prefs.reopen().recorded(alpha))

    prefs.values[ActiveSessionStore.scopedKey(alpha)] = "   "
    assertNull(prefs.reopen().recorded(alpha))
  }

  @Test fun whatTheHostActuallyOpenedIsWhatIsRecordedAfterAFallback() {
    val prefs = FakePrefs()
    // A recorded session that no longer exists: the host opens the root instead and reports it, and
    // that is the id the next launch would restore — the stale one is not kept.
    prefs.store.record(alpha, 404L)
    prefs.store.record(alpha, 1L)

    assertEquals(1L, prefs.reopen().recorded(alpha))
  }

  @Test fun forgettingRemovesOnlyThatWorkspacesRecord() {
    val prefs = FakePrefs()
    prefs.store.record(alpha, 7L)
    prefs.store.record(beta, 8L)
    prefs.store.record(alpha, null)

    assertNull(prefs.reopen().recorded(alpha))
    assertEquals("the other workspace keeps its own choice", 8L, prefs.reopen().recorded(beta))
  }

  @Test fun aRecordedIdTravelsAsAJsonNumberForTheRuntime() {
    assertEquals("7", ActiveSessionStore.write(7L))
    assertNull(ActiveSessionStore.write(null))
    assertNull(ActiveSessionStore.write(0L))
    assertEquals(7L, ActiveSessionStore.parse(" 7 "))
  }
}
