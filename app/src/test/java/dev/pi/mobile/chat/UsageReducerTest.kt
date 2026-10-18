package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session usage footer: per-model totals, tool counts and context pressure, as display text. */
class UsageReducerTest {
  private fun value(vararg models: JSONObject, tools: List<JSONObject> = emptyList(), used: Long? = null, window: Long? = null): JSONObject {
    val modelArray = JSONArray()
    models.forEach { modelArray.put(it) }
    val toolArray = JSONArray()
    tools.forEach { toolArray.put(it) }
    return JSONObject().apply {
      put("models", modelArray)
      put("tools", toolArray)
      if (used != null && window != null) put("context", JSONObject().put("used", used).put("window", window))
    }
  }

  private fun model(
    provider: String,
    modelId: String,
    input: Long = 0L,
    output: Long = 0L,
    cacheRead: Long = 0L,
    cacheWrite: Long = 0L,
    cost: Double = 0.0,
    requests: Int = 0,
  ): JSONObject = JSONObject().apply {
    put("provider", provider)
    put("modelId", modelId)
    put("input", input)
    put("output", output)
    put("cacheRead", cacheRead)
    put("cacheWrite", cacheWrite)
    put("cost", cost)
    put("requests", requests)
  }

  @Test fun parsesEveryFieldOfThePayload() {
    val state = UsageReducer.fromValue(value(model("openai", "gpt-5", 1200L, 340L, 500L, 60L, 0.02, 3), tools = listOf(JSONObject().put("name", "bash").put("requests", 4)), used = 50_000L, window = 200_000L))
    val only = state.models.single()
    assertEquals("openai", only.provider)
    assertEquals("gpt-5", only.modelId)
    assertEquals(1200L, only.input)
    assertEquals(340L, only.output)
    assertEquals(500L, only.cacheRead)
    assertEquals(60L, only.cacheWrite)
    assertEquals(0.02, only.cost, 1e-9)
    assertEquals(3, only.requests)
    assertEquals("bash", state.tools.single().name)
    assertEquals(4, state.tools.single().requests)
    assertEquals(50_000L, state.context.used)
    assertEquals(200_000L, state.context.window)
  }

  @Test fun anAbsentOrPartialPayloadNeverCrashes() {
    val empty = UsageReducer.fromValue(null)
    assertTrue(empty.models.isEmpty())
    assertTrue(empty.tools.isEmpty())
    assertEquals(0L, empty.context.used)
    assertEquals(0L, empty.context.window)
    assertTrue(UsageReducer.fromValue(JSONObject()).models.isEmpty())
    // Rows missing an identity are dropped rather than shown blank.
    val partial = UsageReducer.fromValue(value(JSONObject().put("input", 5L), model("", "gpt-5"), model("openai", "")))
    assertTrue(partial.models.isEmpty())
  }

  @Test fun zeroAndMissingNumbersReadAsZeroInsteadOfFailing() {
    val state = UsageReducer.fromValue(value(JSONObject().put("provider", "x").put("modelId", "y")))
    val only = state.models.single()
    assertEquals(0L, only.input)
    assertEquals(0L, only.output)
    assertEquals(0.0, only.cost, 1e-9)
    assertEquals("0", UsageReducer.tokenLabel(only.input))
    assertEquals("free", UsageReducer.costLabel(only.cost))
  }

  @Test fun tokenLabelsUseSensibleUnits() {
    assertEquals("0", UsageReducer.tokenLabel(0L))
    assertEquals("340", UsageReducer.tokenLabel(340L))
    assertEquals("1.2k", UsageReducer.tokenLabel(1200L))
    assertEquals("25k", UsageReducer.tokenLabel(25_000L))
    assertEquals("123k", UsageReducer.tokenLabel(123_456L))
    assertEquals("1.2M", UsageReducer.tokenLabel(1_234_567L))
  }

  @Test fun costLabelsUseSensibleUnits() {
    assertEquals("free", UsageReducer.costLabel(0.0))
    assertEquals("<$0.01", UsageReducer.costLabel(0.001))
    assertEquals("$0.02", UsageReducer.costLabel(0.0234))
    assertEquals("$12.50", UsageReducer.costLabel(12.5))
    assertEquals("$123", UsageReducer.costLabel(123.4))
  }

  @Test fun contextReadsAsAPercentageAndIsUnknownWithoutAWindow() {
    assertEquals(25, UsageReducer.contextPercent(UsageContext(50_000L, 200_000L)))
    assertEquals(0, UsageReducer.contextPercent(UsageContext(0L, 200_000L)))
    assertNull(UsageReducer.contextPercent(UsageContext(50L, 0L)))
    assertNull(UsageReducer.contextPercent(UsageContext(0L, 0L)))
    assertEquals("25% of 200k", UsageReducer.contextLabel(UsageContext(50_000L, 200_000L)))
    assertEquals("", UsageReducer.contextLabel(UsageContext(50L, 0L)))
  }

  @Test fun aModelRowReadsAsOneCompactLine() {
    assertEquals(
      "openai/gpt-5 · 1.2k in · 340 out · 500 cache · $0.02",
      UsageReducer.modelLabel(UsageModelRow("openai", "gpt-5", input = 1200L, output = 340L, cacheRead = 500L, cost = 0.0234)),
    )
    // Nothing is invented for a model with no usage yet.
    assertEquals("openai/gpt-5 · 0 in · 0 out", UsageReducer.modelLabel(UsageModelRow("openai", "gpt-5")))
    assertEquals("gpt-5 · 0 in · 0 out", UsageReducer.modelLabel(UsageModelRow("", "gpt-5")))
  }

  @Test fun theSummaryLineTotalsTheModelsAndAddsContextPressure() {
    val state = UsageReducer.fromValue(
      value(
        model("openai", "gpt-5", input = 1200L, output = 340L, cost = 0.02),
        model("openai", "gpt-5-mini", input = 10_000L, output = 200L, cost = 0.01),
        used = 50_000L,
        window = 200_000L,
      ),
    )
    assertEquals("11k in · 540 out · $0.03 · 25% of 200k", UsageReducer.summary(state))
    assertEquals("", UsageReducer.summary(UsageReducer.fromValue(null)))
  }

  @Test fun theFooterLineUsesTheApprovedVocabulary() {
    val state = UsageReducer.fromValue(
      value(
        model("openai", "gpt-5", input = 12_000L, output = 3_000L, cacheRead = 500L, cost = 0.1234),
        used = 90_400L,
        window = 200_000L,
      ),
    )
    assertEquals("↑12k ↓3.0k R500 $0.123 45.2%/200k", UsageReducer.footer(state))
    // Zero segments are omitted, not printed as zeroes; an unused session shows nothing at all.
    assertEquals("", UsageReducer.footer(UsageReducer.fromValue(null)))
    assertEquals("", UsageReducer.footer(UsageReducer.fromValue(value())))
    // A known window with no used count refuses to invent a share.
    assertEquals("↑1.2k ?/200k", UsageReducer.footer(UsageReducer.fromValue(value(model("o", "m", input = 1_200L), used = 0L, window = 200_000L))))
  }
}
