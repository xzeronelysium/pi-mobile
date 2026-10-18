package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSummaryTest {
  @Test
  fun statusLabelsAreTheDurableToolStatuses() {
    assertEquals("pending", ToolSummary.statusLabel(ChatEntry.ToolStatus.Pending))
    assertEquals("running", ToolSummary.statusLabel(ChatEntry.ToolStatus.Running))
    assertEquals("ok", ToolSummary.statusLabel(ChatEntry.ToolStatus.Ok))
    assertEquals("error", ToolSummary.statusLabel(ChatEntry.ToolStatus.Error))
  }

  @Test
  fun theCollapsedLineIsNameStatusAndADetail() {
    assertEquals("bash · ok · ls -la", ToolSummary.summary("bash", "{\"command\":\"ls -la\"}", "ok"))
    assertEquals("read · ok · /tmp/a.txt", ToolSummary.summary("read", "{\"path\":\"/tmp/a.txt\"}", "ok"))
  }

  @Test
  fun aBlankDetailIsNotRenderedAsAStraySeparator() {
    assertEquals("bash · ok", ToolSummary.summary("bash", "", "ok"))
    assertEquals("bash · ok", ToolSummary.summary("bash", "{}", "ok"))
    assertEquals("bash", ToolSummary.summary("bash", ""))
  }

  @Test
  fun prettyPrintedArgumentsYieldAValueLineNotABrace() {
    val pretty = "{\n  \"command\": \"echo hi\",\n  \"timeout\": 1000\n}"
    assertEquals("echo hi", ToolSummary.detail(pretty))
    val bare = "{\n  \"path\": \"/etc/hosts\",\n}"
    assertEquals("/etc/hosts", ToolSummary.detail(bare))
  }

  @Test
  fun aVeryLongCommandIsCappedInTheCollapsedLine() {
    val long = "x".repeat(200)
    val line = ToolSummary.summary("bash", "{\"command\":\"$long\"}", "ok")
    assertEquals("bash · ok · " + "x".repeat(72) + "…", line)
  }
}
