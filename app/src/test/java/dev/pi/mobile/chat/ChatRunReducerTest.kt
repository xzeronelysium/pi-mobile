package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunReducerTest {
  @Test
  fun idleComposerSendsNormally() {
    assertEquals(ChatSendMode.Send, ChatRunReducer.mode(busy = false, queueFollowUp = false))
    assertEquals(ChatSendMode.Send, ChatRunReducer.mode(busy = false, queueFollowUp = true))
  }

  @Test
  fun busyComposerDefaultsToSteer() {
    assertEquals(ChatSendMode.Steer, ChatRunReducer.mode(busy = true, queueFollowUp = false))
  }

  @Test
  fun busyWithFollowUpAffordanceQueuesFollowUp() {
    assertEquals(ChatSendMode.FollowUp, ChatRunReducer.mode(busy = true, queueFollowUp = true))
  }

  @Test
  fun compactOnlyWhenReadyAndIdle() {
    assertTrue(ChatRunReducer.canCompact(busy = false, runtimeReady = true))
    assertFalse(ChatRunReducer.canCompact(busy = true, runtimeReady = true))
    assertFalse(ChatRunReducer.canCompact(busy = false, runtimeReady = false))
  }
}
