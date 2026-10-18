package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flattened conversation tree the fork/clone view renders: ordered, depth-annotated rows. */
class ConversationTreeReducerTest {
  private fun value(vararg nodes: JSONObject): JSONObject {
    val array = JSONArray()
    nodes.forEach { array.put(it) }
    return JSONObject().put("nodes", array)
  }

  private fun node(
    id: String,
    parentId: String? = null,
    entryId: String? = null,
    depth: Int? = null,
    title: String? = null,
    current: Boolean = false,
  ): JSONObject = JSONObject().apply {
    put("id", id)
    if (parentId != null) put("parentId", parentId)
    if (entryId != null) put("entryId", entryId)
    if (depth != null) put("depth", depth)
    if (title != null) put("title", title)
    put("current", current)
  }

  @Test fun parsesEveryFieldOfANode() {
    val tree = ConversationTreeReducer.fromValue(value(node("n1", "root", "e7", depth = 1, title = "Add tests", current = true)))
    val only = tree.rows.single()
    assertEquals("n1", only.id)
    assertEquals("root", only.parentId)
    assertEquals("e7", only.entryId)
    assertEquals(1, only.depth)
    assertEquals("Add tests", only.title)
    assertTrue(only.current)
    assertEquals("n1", tree.currentId)
  }

  @Test fun anAbsentPayloadIsAnEmptyTreeRatherThanACrash() {
    assertTrue(ConversationTreeReducer.fromValue(null).rows.isEmpty())
    assertTrue(ConversationTreeReducer.fromValue(JSONObject()).rows.isEmpty())
    assertNull(ConversationTreeReducer.fromValue(JSONObject()).currentId)
  }

  @Test fun nodesWithoutAnIdAreDroppedAndABlankTitleFallsBackToTheId() {
    val tree = ConversationTreeReducer.fromValue(value(node("", "a"), node("b")))
    assertEquals(listOf("b"), tree.rows.map { it.id })
    assertEquals("b", tree.rows.single().title)
  }

  @Test fun childrenFollowTheirParentAndSiblingsKeepThePayloadOrder() {
    val tree = ConversationTreeReducer.fromValue(
      value(
        node("a1", "a"),
        node("a", null),
        node("b1", "b"),
        node("b", null),
        node("a2", "a"),
      ),
    )
    assertEquals(listOf("a", "a1", "a2", "b", "b1"), tree.rows.map { it.id })
  }

  @Test fun depthIsComputedFromTheTreeWhenThePayloadOmitsIt() {
    val tree = ConversationTreeReducer.fromValue(
      value(node("root"), node("child", "root"), node("grandchild", "child")),
    )
    assertEquals(listOf(0, 1, 2), tree.rows.map { it.depth })
  }

  @Test fun aPayloadDepthIsKeptWhenTheHostAnnotatesTheRowItself() {
    val tree = ConversationTreeReducer.fromValue(value(node("root"), node("child", "root", depth = 5)))
    assertEquals(listOf(0, 5), tree.rows.map { it.depth })
  }

  @Test fun anOrphanWhoseParentIsMissingBecomesARootRatherThanVanishing() {
    val tree = ConversationTreeReducer.fromValue(value(node("orphan", "gone"), node("root")))
    assertEquals(listOf("root", "orphan"), tree.rows.map { it.id })
    assertEquals(0, tree.rows[1].depth)
    // The dangling parent id is kept so the UI can say the parent is gone.
    assertEquals("gone", tree.rows[1].parentId)
  }

  @Test fun aCycleCannotFlattenForeverAndStillYieldsEveryNode() {
    val tree = ConversationTreeReducer.fromValue(value(node("x", "y"), node("y", "x")))
    assertEquals(listOf("x", "y"), tree.rows.map { it.id })
  }

  @Test fun theRowFlaggedCurrentIsTheSelectedNode() {
    val tree = ConversationTreeReducer.fromValue(value(node("a"), node("b", "a", current = true)))
    assertEquals("b", tree.currentId)
    assertNull(ConversationTreeReducer.fromValue(value(node("a"))).currentId)
  }

  @Test fun blankOrNullParentAndEntryIdsAreAbsentRatherThanTheTextNull() {
    val node = JSONObject()
      .put("id", "a").put("parentId", "null").put("entryId", "null").put("depth", 0).put("title", "A")
    val tree = ConversationTreeReducer.fromValue(value(node))
    assertNull(tree.rows.single().parentId)
    assertNull(tree.rows.single().entryId)
  }
}
