package dev.pi.mobile.chat

import org.junit.Assert.*
import org.junit.Test

class SettingsStoreTest {
  @Test fun transientChangesDoNotWriteAndShowingModelsClearsSavedHiddenRows() {
    var disk: Map<String, String> = emptyMap()
    var writes = 0
    val store = SettingsStore({ disk }, { disk = it; writes++ })
    val state = SettingsUiState(hiddenByProvider = mapOf("provider" to setOf("a", "b")))
    store.record(state)
    store.record(state.copy(modelQuery = "typing", apiKeyDraft = "secret"))
    assertEquals(1, writes)
    store.record(state.copy(hiddenByProvider = emptyMap()))
    assertTrue(store.restore().hiddenByProvider.isEmpty())
  }

  @Test fun restartRestoresPreferencesWithoutTransientOrSecretState() {
    var disk: Map<String, String> = emptyMap()
    val store = SettingsStore({ disk }, { disk = it })
    val chosen = SettingsUiState(selectedProvider = "openai", selectedModel = "gpt", effort = "High",
      hiddenByProvider = mapOf("openai" to setOf("old")), apiKeyDraft = "secret", modelQuery = "query", menuOpen = true)
    store.record(chosen)
    val restored = SettingsStore({ disk }, { disk = it }).restore()
    assertEquals("openai", restored.selectedProvider)
    assertEquals("gpt", restored.selectedModel)
    assertEquals("High", restored.effort)
    assertEquals(chosen.hiddenByProvider, restored.hiddenByProvider)
    assertEquals("", restored.apiKeyDraft)
    assertEquals("", restored.modelQuery)
    assertFalse(restored.menuOpen)
    assertFalse(disk.values.contains("secret"))
  }
}
