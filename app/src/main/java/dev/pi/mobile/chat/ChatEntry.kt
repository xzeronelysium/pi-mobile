package dev.pi.mobile.chat

import java.util.UUID

/**
 * One rendered transcript row.
 *
 * These are the durable-native rows the host projects (`runtime/src/ui.mjs`) and
 * [DurableTranscriptReducer] builds. Nothing here is CLI-shaped: a row is text, thinking,
 * a tool call with its result folded in, or a notice.
 */
sealed class ChatEntry {
  abstract val id: String

  data class User(override val id: String = newId(), val text: String) : ChatEntry()

  data class Assistant(override val id: String = newId(), val markdown: String) : ChatEntry()

  data class Thinking(override val id: String = newId(), val text: String) : ChatEntry()

  data class Tool(
    override val id: String = newId(),
    val toolCallId: String,
    val name: String,
    val arguments: String = "",
    val status: ToolStatus = ToolStatus.Pending,
    val output: String = "",
    /** Expanded state is UI-local; [DurableTranscriptReducer.mergeEntries] preserves it. */
    val expanded: Boolean = false,
  ) : ChatEntry()

  data class SystemNotice(override val id: String = newId(), val text: String) : ChatEntry()

  enum class ToolStatus { Pending, Running, Ok, Error }
}

internal fun newId(): String = UUID.randomUUID().toString()
