package dev.pi.mobile.chat

import java.util.Locale

/**
 * The model catalog the picker browses.
 *
 * Every field here comes from the provider's own catalog row (`runtime/src/providers.mjs::modelRows`).
 * A field the provider does not publish stays null or empty so the sheet omits it instead of
 * printing a fabricated zero — a model with unknown pricing must not read as "free".
 */
data class ChatModelOption(
  val provider: String,
  val modelId: String,
  val name: String,
  val kind: ModelKind = ModelKind.Chat,
  val api: String? = null,
  val contextWindow: Long? = null,
  val maxTokens: Long? = null,
  val reasoning: Boolean = false,
  val input: List<String> = listOf("text"),
  val output: List<String> = emptyList(),
  val costIn: Double? = null,
  val costOut: Double? = null,
  /** Thinking levels this model declares; empty means "provider defaults apply". */
  val thinkingLevels: List<String> = emptyList(),
  val providerName: String? = null,
)

/** The model type a picker is showing. */
enum class ModelKind(val wire: String, val label: String) {
  Chat("chat", "chat"),
  Image("image", "images"),
  Classifier("classifier", "classify"),
  ;

  companion object {
    fun ofWire(wire: String): ModelKind? = entries.firstOrNull { it.wire == wire }
  }
}

/**
 * One provider section of the Model sheet.
 *
 * [models] is the single list this section renders: the provider's catalog rows when the catalog
 * lists the provider, else its authenticated rows, so a provider the catalog has not listed yet
 * still browses something instead of vanishing. [hiddenIds] is what the checklist has hidden, so
 * [visible] is that one list minus the hidden ids — a hidden model stays in [models] and is always
 * recoverable, and a section is never dropped for having everything hidden.
 */
data class PickerProviderGroup(
  val provider: String,
  val providerName: String,
  val models: List<ChatModelOption>,
  val hiddenIds: Set<String>,
) {
  val visible: List<ChatModelOption> get() = models.filter { it.modelId !in hiddenIds }
}

/**
 * Pure catalog maths for the Model sheet: grouping, search, per-provider visibility and the
 * thinking-effort ladder a selected model can actually run.
 */
object ModelCatalogReducer {
  /** The provider's full level ladder, lowest first, for models that declare no map of their own. */
  val effortLadder: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

  /**
   * Efforts for a model: the levels its catalog row declares (levels its `thinkingLevelMap` does
   * not mark `null`), else the provider-wide ladder. Upstream clamps a request-time level anyway.
   */
  fun effortsFor(option: ChatModelOption?): List<String> {
    val levels = option?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: effortLadder
    return levels.map { it.label() }
  }

  /** Nearest level the model really supports when the current effort is not one of them. */
  fun clampEffort(option: ChatModelOption?, effort: String): String {
    val supported = option?.thinkingLevels.orEmpty()
    if (supported.isEmpty() || supported.any { it.equals(effort, ignoreCase = true) }) return effort
    val target = effortLadder.indexOfFirst { it.equals(effort, ignoreCase = true) }.let { if (it < 0) 3 else it }
    return supported.minBy { level ->
      val rank = effortLadder.indexOf(level).let { if (it < 0) 99 else it }
      kotlin.math.abs(rank - target) * 16 + rank
    }.label()
  }

  private fun String.label(): String = replaceFirstChar { it.uppercase() }

  /**
   * The picker's sections: one per provider this device can actually reach, in catalog order.
   *
   * A provider with no authenticated row keeps no section, so the sheet never lists a model that
   * cannot be run and never needs a "not signed in" placeholder — with nothing signed in it shows
   * its empty state instead. A provider whose rows the catalog has not listed yet still browses
   * its authenticated rows, and a signed-in provider browses the catalog's rows for it (falling
   * back to the authenticated ones) so no catalog model is quietly unreachable.
   */
  fun groups(
    catalog: List<ChatModelOption>,
    available: List<ChatModelOption>,
    hiddenByProvider: Map<String, Set<String>>,
    providerNames: Map<String, String>,
  ): List<PickerProviderGroup> {
    val providers = (catalog.map { it.provider } + available.map { it.provider }).distinct()
    return providers.mapNotNull { provider ->
      val authed = available.filter { it.provider == provider }
      if (authed.isEmpty()) return@mapNotNull null
      val all = catalog.filter { it.provider == provider }
      PickerProviderGroup(
        provider = provider,
        providerName = providerNames[provider] ?: all.firstNotNullOfOrNull { it.providerName } ?: authed.firstNotNullOfOrNull { it.providerName } ?: provider,
        models = all.ifEmpty { authed },
        hiddenIds = hiddenByProvider[provider].orEmpty(),
      )
    }
  }

  /**
   * Does this row match the picker's search text? Name, id and provider all count.
   */
  fun matches(option: ChatModelOption, query: String): Boolean {
    val needle = query.trim().lowercase(Locale.US)
    if (needle.isEmpty()) return true
    return option.name.lowercase(Locale.US).contains(needle) ||
      option.modelId.lowercase(Locale.US).contains(needle) ||
      option.provider.lowercase(Locale.US).contains(needle)
  }

  /** Hide or show one model. The id is scoped to its provider, so another provider is unaffected. */
  fun hideModel(
    hiddenByProvider: Map<String, Set<String>>,
    option: ChatModelOption,
    hidden: Boolean,
  ): Map<String, Set<String>> {
    val current = hiddenByProvider[option.provider].orEmpty()
    val updated = if (hidden) current + option.modelId else current - option.modelId
    return if (updated.isEmpty()) hiddenByProvider - option.provider
    else hiddenByProvider + (option.provider to updated)
  }

  /** Hide every catalog model of one provider. Other providers are left alone. */
  fun hideProvider(
    hiddenByProvider: Map<String, Set<String>>,
    catalog: List<ChatModelOption>,
    provider: String,
  ): Map<String, Set<String>> {
    val ids = catalog.filter { it.provider == provider }.map { it.modelId }.toSet()
    if (ids.isEmpty()) return hiddenByProvider
    return hiddenByProvider + (provider to (hiddenByProvider[provider].orEmpty() + ids))
  }

  /** Show every model of one provider again, including ones hidden before the last hide-all. */
  fun showProvider(hiddenByProvider: Map<String, Set<String>>, provider: String): Map<String, Set<String>> =
    hiddenByProvider - provider

  // ------------------------------------------------------------------ catalog presentation

  fun tokenLabel(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${"%.1f".format(Locale.US, tokens / 1_000_000.0)}M"
    else -> "${tokens / 1_000}k"
  }

  fun costLabel(cost: Double?): String = when {
    cost == null -> ""
    cost <= 0.0 -> "free"
    cost >= 100 -> "$${cost.toInt()}"
    else -> "$${"%.2f".format(Locale.US, cost)}"
  }

  /** The auto-discovered metadata line under a model row; empty when the catalog knows nothing. */
  fun infoLine(option: ChatModelOption): String {
    val parts = mutableListOf<String>()
    option.contextWindow?.let { parts.add("${tokenLabel(it)} context") }
    val costIn = costLabel(option.costIn)
    val costOut = costLabel(option.costOut)
    if (costIn.isNotEmpty() || costOut.isNotEmpty()) {
      parts.add("${costIn.ifEmpty { "?" }} in · ${costOut.ifEmpty { "?" }} out per M tok")
    }
    if (option.input.contains("image")) parts.add("image input")
    if (!option.reasoning) parts.add("no reasoning")
    return parts.joinToString(" · ")
  }
}
