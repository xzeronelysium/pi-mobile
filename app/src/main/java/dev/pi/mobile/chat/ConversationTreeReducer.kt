package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * One node of the conversation tree as the flattened view renders it.
 *
 * `{id, parentId, entryId, depth, title, current}` from the `tree` bridge payload, annotated with
 * its depth in the flattened list.
 */
data class TreeNodeRow(
  val id: String,
  val parentId: String? = null,
  val entryId: String? = null,
  val depth: Int = 0,
  val title: String = "",
  val current: Boolean = false,
  /** True when this node is an ancestor of the current conversation: the path the drawer brightens. */
  val onPath: Boolean = false,
)

/** The flattened tree: rows in render order, parents before their children. */
data class ConversationTree(val rows: List<TreeNodeRow> = emptyList()) {
  val currentId: String? get() = rows.firstOrNull { it.current }?.id
}

/**
 * Pure projection of the `tree` bridge payload into ordered, depth-annotated rows.
 *
 * Nodes are flattened depth-first; siblings keep payload order. A node whose parent is not in the
 * payload, or a cycle, still renders — as a root — so nothing silently disappears. A depth the
 * host annotated itself is kept; otherwise it is computed from the nesting.
 */
object ConversationTreeReducer {
  private class Node(val row: TreeNodeRow, val declaredDepth: Int?)

  fun fromValue(value: JSONObject?): ConversationTree {
    val nodes = parse(value?.optJSONArray("nodes"))
    if (nodes.isEmpty()) return ConversationTree()

    val children = nodes.groupBy { it.row.parentId }
    val ordered = ArrayList<TreeNodeRow>(nodes.size)
    val visited = HashSet<String>()

    fun emit(start: Node, startDepth: Int) {
      val pending = ArrayDeque<Pair<Node, Int>>()
      pending.addLast(start to startDepth)
      while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        if (!visited.add(node.row.id)) continue
        ordered.add(node.row.copy(depth = node.declaredDepth ?: depth))
        val kids = children[node.row.id].orEmpty()
        // Push in reverse so siblings pop in payload order.
        for (index in kids.indices.reversed()) pending.addLast(kids[index] to depth + 1)
      }
    }

    nodes.filter { it.row.parentId == null }.forEach { emit(it, 0) }
    // Orphans and cycle members render as roots rather than being dropped.
    nodes.forEach { if (it.row.id !in visited) emit(it, 0) }
    return ConversationTree(ordered)
  }

  private fun parse(array: JSONArray?): List<Node> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val node = array.optJSONObject(index) ?: continue
        val id = node.optString("id")
        if (id.isBlank()) continue
        add(
          Node(
            row = TreeNodeRow(
              id = id,
              parentId = text(node.optString("parentId")),
              entryId = text(node.optString("entryId")),
              title = node.optString("title").ifBlank { id },
              current = node.optBoolean("current"),
              onPath = node.optBoolean("onPath"),
            ),
            declaredDepth = node.optInt("depth").takeIf { node.has("depth") },
          ),
        )
      }
    }
  }

  private fun text(value: String): String? = value.takeIf { it.isNotBlank() && it != "null" }
}
