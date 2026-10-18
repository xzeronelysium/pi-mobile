package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Token and cost totals for one model, from the `usage` bridge payload. */
data class UsageModelRow(
  val provider: String,
  val modelId: String,
  val input: Long = 0L,
  val output: Long = 0L,
  val cacheRead: Long = 0L,
  val cacheWrite: Long = 0L,
  val cost: Double = 0.0,
  val requests: Int = 0,
)

/** Tool call count for one tool, from the `usage` bridge payload. */
data class UsageToolRow(val name: String, val requests: Int = 0)

/** How full the model's context window is. A window of 0 means "not reported". */
data class UsageContext(val used: Long = 0L, val window: Long = 0L)

/** The session's usage as the footer renders it. */
data class UsageState(
  val models: List<UsageModelRow> = emptyList(),
  val tools: List<UsageToolRow> = emptyList(),
  val context: UsageContext = UsageContext(),
)

/**
 * Pure projection of the `usage` bridge payload into compact display strings.
 *
 * Tokens, cost and context pressure are formatted here rather than in the composable so the exact
 * text is unit-tested, and every formatter tolerates missing or zero fields instead of crashing.
 */
object UsageReducer {
  fun fromValue(value: JSONObject?): UsageState {
    if (value == null) return UsageState()
    return UsageState(
      models = modelsOf(value.optJSONArray("models")),
      tools = toolsOf(value.optJSONArray("tools")),
      context = value.optJSONObject("context")?.let { context ->
        UsageContext(
          used = context.optLong("used").coerceAtLeast(0L),
          window = context.optLong("window").coerceAtLeast(0L),
        )
      } ?: UsageContext(),
    )
  }

  /** Compact token count: `0`, `340`, `1.2k`, `25k`, `123k`, `1.2M`. */
  fun tokenLabel(tokens: Long): String {
    val value = tokens.coerceAtLeast(0L)
    return when {
      value == 0L -> "0"
      value < 1_000L -> value.toString()
      value < 10_000L -> "%.1fk".format(Locale.US, value / 1_000.0)
      value < 1_000_000L -> "${value / 1_000L}k"
      else -> "%.1fM".format(Locale.US, value / 1_000_000.0)
    }
  }

  /** Compact cost: `free`, `<$0.01` (a real but sub-cent charge), `$0.02`, `$123`. */
  fun costLabel(cost: Double): String = when {
    cost <= 0.0 -> "free"
    cost < 0.005 -> "<$0.01"
    cost < 100.0 -> "$%.2f".format(Locale.US, cost)
    else -> "$${cost.toLong()}"
  }

  /** Share of the context window in use, or null when the window is unknown. */
  fun contextPercent(context: UsageContext): Int? {
    if (context.window <= 0L) return null
    val percent = Math.round(context.used * 100.0 / context.window).toInt()
    return percent.coerceIn(0, 100)
  }

  /** `25% of 200k`, or empty when the window is unknown. */
  fun contextLabel(context: UsageContext): String =
    contextPercent(context)?.let { "$it% of ${tokenLabel(context.window)}" }.orEmpty()

  /** One model's line; cache and cost appear only when the payload reports them. */
  fun modelLabel(row: UsageModelRow): String = buildString {
    append(if (row.provider.isBlank()) row.modelId else "${row.provider}/${row.modelId}")
    append(" · ${tokenLabel(row.input)} in · ${tokenLabel(row.output)} out")
    if (row.cacheRead > 0L) append(" · ${tokenLabel(row.cacheRead)} cache")
    if (row.cost > 0.0) append(" · ${costLabel(row.cost)}")
  }

  /** The footer's summary: model totals plus context pressure. Empty when the session is unused. */
  fun summary(state: UsageState): String {
    val parts = mutableListOf<String>()
    if (state.models.isNotEmpty()) {
      parts.add("${tokenLabel(state.models.sumOf { it.input })} in · ${tokenLabel(state.models.sumOf { it.output })} out")
      val cost = state.models.sumOf { it.cost }
      if (cost > 0.0) parts.add(costLabel(cost))
    }
    val context = contextLabel(state.context)
    if (context.isNotEmpty()) parts.add(context)
    return parts.joinToString(" · ")
  }

  /**
   * The composer dock's footer line in the approved vocabulary:
   * `↑12k ↓3k R500 W60 $0.123 45.2%/200k`.
   *
   * Arrows carry the token direction, `R`/`W` the cache read and write, cost is always three
   * decimals, and context pressure is a one-decimal share of the window. A segment whose value is
   * zero is omitted rather than printed as a zero, so an unused session shows nothing at all; a
   * window that is known while the used count is not reads `?/200k` instead of inventing a share.
   */
  fun footer(state: UsageState): String {
    val parts = mutableListOf<String>()
    val input = state.models.sumOf { it.input }
    val output = state.models.sumOf { it.output }
    val cacheRead = state.models.sumOf { it.cacheRead }
    val cacheWrite = state.models.sumOf { it.cacheWrite }
    val cost = state.models.sumOf { it.cost }
    if (input > 0L) parts.add("↑${tokenLabel(input)}")
    if (output > 0L) parts.add("↓${tokenLabel(output)}")
    if (cacheRead > 0L) parts.add("R${tokenLabel(cacheRead)}")
    if (cacheWrite > 0L) parts.add("W${tokenLabel(cacheWrite)}")
    if (cost > 0.0) parts.add("$%.3f".format(Locale.US, cost))
    val window = state.context.window
    when {
      window > 0L && state.context.used > 0L ->
        parts.add("%.1f%%/%s".format(Locale.US, state.context.used * 100.0 / window, tokenLabel(window)))
      window > 0L -> parts.add("?/${tokenLabel(window)}")
      state.context.used > 0L -> parts.add(tokenLabel(state.context.used))
    }
    return parts.joinToString(" ")
  }

  private fun modelsOf(array: JSONArray?): List<UsageModelRow> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val provider = row.optString("provider")
        val modelId = row.optString("modelId")
        if (provider.isBlank() || modelId.isBlank()) continue
        add(
          UsageModelRow(
            provider = provider,
            modelId = modelId,
            input = row.optLong("input").coerceAtLeast(0L),
            output = row.optLong("output").coerceAtLeast(0L),
            cacheRead = row.optLong("cacheRead").coerceAtLeast(0L),
            cacheWrite = row.optLong("cacheWrite").coerceAtLeast(0L),
            cost = row.optDouble("cost").takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0,
            requests = row.optInt("requests").coerceAtLeast(0),
          ),
        )
      }
    }
  }

  private fun toolsOf(array: JSONArray?): List<UsageToolRow> {
    if (array == null) return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val name = row.optString("name")
        if (name.isBlank()) continue
        add(UsageToolRow(name, row.optInt("requests").coerceAtLeast(0)))
      }
    }
  }
}
