package dev.pi.mobile.chat

import android.content.Context

/** Only durable preferences cross a restart; sheets, searches and credentials never do. */
class SettingsStore(
  private val load: () -> Map<String, String>,
  private val save: (Map<String, String>) -> Unit,
) {
  fun restore(): SettingsUiState {
    val values = load()
    return SettingsUiState(
      selectedProvider = values["provider"].orEmpty(),
      selectedModel = values["model"].orEmpty(),
      effort = values["effort"] ?: "Medium",
      hiddenByProvider = values.filterKeys { it.startsWith("hidden:") }
        .mapKeys { it.key.removePrefix("hidden:") }
        .mapValues { (_, value) -> value.split('\u0000').filter { it.isNotEmpty() }.toSet() },
    )
  }

  fun record(state: SettingsUiState) {
    val values = buildMap {
      put("provider", state.selectedProvider)
      put("model", state.selectedModel)
      put("effort", state.effort)
      state.hiddenByProvider.forEach { (provider, ids) ->
        if (ids.isNotEmpty()) put("hidden:$provider", ids.sorted().joinToString("\u0000"))
      }
    }
    if (values != load()) save(values)
  }
}

fun settingsStore(context: Context): SettingsStore {
  val prefs = context.applicationContext.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
  return SettingsStore(
    load = { prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap() },
    // Save on each preference change, not onDestroy (which Android need not call).
    save = { values ->
      val edit = prefs.edit().clear()
      values.forEach { (key, value) -> edit.putString(key, value) }
      check(edit.commit()) { "Could not save model settings" }
    },
  )
}
