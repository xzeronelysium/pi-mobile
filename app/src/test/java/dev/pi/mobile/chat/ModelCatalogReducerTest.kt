package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Model sheet's catalog maths: grouping, search, per-provider visibility and effort levels. */
class ModelCatalogReducerTest {
  private fun option(
    provider: String,
    id: String,
    name: String = id,
    contextWindow: Long? = null,
    costIn: Double? = null,
    costOut: Double? = null,
    input: List<String> = listOf("text"),
    reasoning: Boolean = true,
    thinkingLevels: List<String> = emptyList(),
    kind: ModelKind = ModelKind.Chat,
  ) = ChatModelOption(
    provider = provider, modelId = id, name = name, kind = kind,
    contextWindow = contextWindow, costIn = costIn, costOut = costOut,
    input = input, reasoning = reasoning, thinkingLevels = thinkingLevels,
  )

  private val catalog = listOf(
    option("openai", "gpt-5", "GPT-5", contextWindow = 400_000, costIn = 1.25, costOut = 10.0, input = listOf("text", "image")),
    option("openai", "gpt-5-mini", "GPT-5 mini"),
    option("openrouter", "anthropic/claude-fable-5", "Claude Fable 5", reasoning = false),
    option("openrouter", "gpt-5", "GPT-5 (via OpenRouter)"),
  )

  @Test fun groupsKeepEveryProviderEvenWhenAllOfItsModelsAreHidden() {
    val groups = ModelCatalogReducer.groups(
      catalog = catalog, available = catalog,
      hiddenByProvider = mapOf("openai" to setOf("gpt-5", "gpt-5-mini")),
      providerNames = mapOf("openai" to "OpenAI", "openrouter" to "OpenRouter"),
    )
    assertEquals(listOf("openai", "openrouter"), groups.map { it.provider })
    assertEquals("OpenAI", groups[0].providerName)
    // The section survives so the checklist can show its models again.
    assertEquals(2, groups[0].models.size)
    assertTrue(groups[0].visible.isEmpty())
    assertEquals(listOf("anthropic/claude-fable-5", "gpt-5"), groups[1].visible.map { it.modelId })
    // An unknown display name falls back to the provider id rather than an empty header.
    assertEquals("openrouter", ModelCatalogReducer.groups(catalog, catalog, emptyMap(), emptyMap())[1].providerName)
  }

  @Test fun groupsHoldOnlyProvidersWithAnAuthenticatedRow() {
    // The sheet lists the models this device can actually run, so a catalog provider nothing is
    // signed in for keeps no section at all — the empty state speaks for it instead of a dead
    // "not signed in" row. A provider whose only rows are authenticated still gets its section.
    val available = listOf(option("deepseek", "deepseek-chat", "DeepSeek Chat"))
    val groups = ModelCatalogReducer.groups(catalog, available, emptyMap(), emptyMap())
    assertEquals(listOf("deepseek"), groups.map { it.provider })
    assertEquals(listOf("deepseek-chat"), groups.last().models.map { it.modelId })
    assertEquals(listOf("deepseek-chat"), groups.last().visible.map { it.modelId })
  }

  @Test fun nothingSignedInMeansNoSections() {
    // Nothing is signed in: the sheet's empty state, not one dead section per catalog provider.
    assertTrue(ModelCatalogReducer.groups(catalog, emptyList(), emptyMap(), emptyMap()).isEmpty())
  }

  @Test fun aSectionBrowsesOneListTheVisibilityIdsSubset() {
    // Signed in for openai only: its section renders the catalog's rows for that provider — one
    // list, not a second authenticated one — while the provider that is not signed in stays out.
    val groups = ModelCatalogReducer.groups(catalog, listOf(catalog[0]), emptyMap(), emptyMap())
    assertEquals(listOf("openai"), groups.map { it.provider })
    assertEquals(listOf("gpt-5", "gpt-5-mini"), groups[0].visible.map { it.modelId })
    // Hiding one id leaves the other row, and the hidden one is still in the checklist.
    val hidden = ModelCatalogReducer.groups(catalog, listOf(catalog[0]), mapOf("openai" to setOf("gpt-5")), emptyMap())
    assertEquals(listOf("gpt-5-mini"), hidden[0].visible.map { it.modelId })
    assertEquals(listOf("gpt-5", "gpt-5-mini"), hidden[0].models.map { it.modelId })
  }

  @Test fun hidingIsScopedToItsProvider() {
    val hidden = ModelCatalogReducer.hideModel(emptyMap(), catalog[1], hidden = true)
    assertTrue("gpt-5-mini" in ModelCatalogReducer.groups(catalog, catalog, hidden, emptyMap())[0].hiddenIds)
    // The same id on another provider is a different row and stays visible.
    assertTrue(ModelCatalogReducer.groups(catalog, catalog, hidden, emptyMap())[1].visible.map { it.modelId }.contains("gpt-5"))
    val shown = ModelCatalogReducer.hideModel(hidden, catalog[1], hidden = false)
    assertTrue(shown.isEmpty())
  }

  @Test fun hideAllAndShowAllCoverOneProviderAtATime() {
    val hidden = ModelCatalogReducer.hideProvider(emptyMap(), catalog, "openai")
    assertEquals(setOf("gpt-5", "gpt-5-mini"), hidden["openai"])
    assertTrue("openrouter" !in hidden)
    assertTrue(ModelCatalogReducer.showProvider(hidden, "openai").isEmpty())
  }

  @Test fun effortsComeFromTheModelAndFallBackToTheFullLadder() {
    val rich = option("openai", "gpt-5", thinkingLevels = listOf("low", "medium", "high"))
    assertEquals(listOf("Low", "Medium", "High"), ModelCatalogReducer.effortsFor(rich))
    val plain = option("openai", "gpt-5-mini")
    assertEquals(listOf("Off", "Minimal", "Low", "Medium", "High", "Xhigh", "Max"), ModelCatalogReducer.effortsFor(plain))
    assertEquals(ModelCatalogReducer.effortsFor(plain), ModelCatalogReducer.effortsFor(null))
  }

  @Test fun anUnsupportedEffortClampsToTheNearestRealLevel() {
    val rich = option("openai", "gpt-5", thinkingLevels = listOf("medium", "high"))
    assertEquals("High", ModelCatalogReducer.clampEffort(rich, "Max"))
    assertEquals("Medium", ModelCatalogReducer.clampEffort(rich, "off"))
    assertEquals("Medium", ModelCatalogReducer.clampEffort(rich, "Medium"))
    // No declared levels means the provider clamps at request time, so the value is kept as-is.
    assertEquals("Max", ModelCatalogReducer.clampEffort(option("x", "y"), "Max"))
  }

  @Test fun theInfoLineDescribesOnlyWhatTheCatalogKnows() {
    assertEquals(
      "400k context · $1.25 in · $10.00 out per M tok · image input",
      ModelCatalogReducer.infoLine(catalog[0]),
    )
    assertEquals("no reasoning", ModelCatalogReducer.infoLine(catalog[2]))
    assertEquals("", ModelCatalogReducer.infoLine(option("x", "y")))
    assertEquals("1.0M context", ModelCatalogReducer.infoLine(option("x", "y", contextWindow = 1_000_000)))
    assertEquals("free", ModelCatalogReducer.costLabel(0.0))
    assertEquals("", ModelCatalogReducer.costLabel(null))
  }
}
