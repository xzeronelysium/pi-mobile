package dev.pi.mobile.chat

/** How the composer should dispatch the current draft. */
enum class ChatSendMode { Send, Steer, FollowUp }

/**
 * Pure routing for busy-run composer actions (steer / follow-up / compact).
 * Keep side effects in the host/UI; this only decides mode and formats notices.
 */
object ChatRunReducer {
  /**
   * Idle → normal send. Busy + follow-up affordance → queue follow-up.
   * Busy otherwise → steer mid-run.
   */
  fun mode(busy: Boolean, queueFollowUp: Boolean): ChatSendMode = when {
    !busy -> ChatSendMode.Send
    queueFollowUp -> ChatSendMode.FollowUp
    else -> ChatSendMode.Steer
  }

  fun canCompact(busy: Boolean, runtimeReady: Boolean): Boolean = runtimeReady && !busy
}
