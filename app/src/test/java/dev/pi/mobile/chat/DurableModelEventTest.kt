package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model events feed the Model sheet directly, so the parsed row must carry the metadata the
 * sheet renders and must never lose a model the provider reported.
 */
class DurableModelEventTest {
  private fun model(id: String, name: String, extra: JSONObject.() -> Unit = {}) = JSONObject()
    .put("provider", "openrouter").put("id", id).put("name", name)
    .put("reasoning", true).put("type", "chat").put("api", "openai-completions")
    .put("contextWindow", 200_000).put("maxTokens", 8_192)
    .put("input", JSONArray().put("text").put("image"))
    .put("cost", JSONObject().put("input", 3.0).put("output", 15.0))
    .put("thinkingLevels", JSONArray().put("low").put("medium").put("high"))
    .apply(extra)

  @Test fun modelRowsCarryTheMetadataThePickerShows() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "available")
        .put("value", JSONObject().put("provider", JSONObject.NULL).put("type", "chat").put("models", JSONArray().put(model("a/one", "One")))),
    ) as DurableEvent.Available
    val rows = event.rows
    assertEquals(1, rows.size)
    val row = rows.single()
    assertEquals("openrouter", row.provider)
    assertEquals("a/one", row.modelId)
    assertEquals("One", row.name)
    assertEquals(ModelKind.Chat, row.kind)
    assertEquals(200_000L, row.contextWindow)
    assertEquals(8_192L, row.maxTokens)
    assertTrue(row.reasoning)
    assertEquals(listOf("text", "image"), row.input)
    assertEquals(3.0, row.costIn!!, 0.0)
    assertEquals(15.0, row.costOut!!, 0.0)
    assertEquals(listOf("low", "medium", "high"), row.thinkingLevels)
  }

  @Test fun everyReportedModelSurvivesParsingEvenWithoutMetadata() {
    val payload = JSONArray()
    payload.put(model("a/one", "One"))
    payload.put(JSONObject().put("provider", "openai").put("id", "b/two"))
    payload.put(JSONObject().put("provider", "openai").put("id", "c/three").put("name", "Three").put("type", "image"))
    val event = DurableEvent.parse(
      JSONObject().put("event", "models").put("value", JSONObject().put("type", "chat").put("models", payload)),
    ) as DurableEvent.Models
    assertEquals(listOf("a/one", "b/two", "c/three"), event.rows.map { it.modelId })
    // A row the provider named only by id keeps that id as its label rather than disappearing.
    assertEquals("b/two", event.rows[1].name)
    // Absent metadata stays absent; it does not become a zero the sheet would print.
    assertNull(event.rows[1].contextWindow)
    assertNull(event.rows[1].costIn)
    assertTrue(event.rows[1].thinkingLevels.isEmpty())
    // The model's own type wins over the query type, so a mixed list cannot mislabel a row.
    assertEquals(ModelKind.Image, event.rows[2].kind)
  }

  @Test fun rowsWithNoIdentityAreDroppedRatherThanRenderedBlank() {
    val payload = JSONArray()
      .put(JSONObject().put("provider", "openai").put("id", ""))
      .put(JSONObject().put("id", "no-provider"))
      .put(model("a/one", "One"))
    val event = DurableEvent.parse(
      JSONObject().put("event", "models").put("value", JSONObject().put("type", "chat").put("models", payload)),
    ) as DurableEvent.Models
    assertEquals(listOf("a/one"), event.rows.map { it.modelId })
  }

  @Test fun aMissingModelArrayYieldsNoRowsInsteadOfACrash() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "available").put("value", JSONObject().put("type", "chat")),
    ) as DurableEvent.Available
    assertTrue(event.rows.isEmpty())
    assertEquals("chat", event.type)
  }
}
