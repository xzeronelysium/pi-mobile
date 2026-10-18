package dev.pi.mobile.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML export, Kotlin half: the drawer offers the format, the frame the host emits lands as a
 * rendered document, and it is named for the format that actually came back.
 */
class HtmlExportUiTest {
  @Test
  fun theDrawerOffersTheHtmlFormatTheExporterRenders() {
    assertEquals("html", EXPORT_HTML)
    assertEquals("markdown", EXPORT_MARKDOWN)
    assertEquals("jsonl", EXPORT_JSONL)
  }

  @Test
  fun theHtmlExportFrameLandsAsARenderedDocument() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "export").put(
        "value",
        JSONObject()
          .put("format", "html")
          .put("conversationId", "7")
          .put("mime", "text/html")
          .put("content", "<!DOCTYPE html>\n<html></html>")
          .put("bytes", 27)
          .put("truncated", false),
      ),
    ) as DurableEvent.Export
    assertEquals("html", event.export.format)
    assertEquals("text/html", event.export.mime)
    assertEquals("<!DOCTYPE html>\n<html></html>", event.export.text)
    // The host names no file, so the name is derived from real fields and carries the real extension.
    assertEquals("conversation-7.html", event.export.filename)
    assertTrue(event.export.hasContent)
  }

  @Test
  fun anHtmlExportNameFromTheHostCannotEscapeTheExportsDirectory() {
    val event = DurableEvent.parse(
      JSONObject().put("event", "export").put(
        "value",
        JSONObject()
          .put("format", "html")
          .put("filename", "../../etc/passwd")
          .put("content", "<!DOCTYPE html>")
          .put("conversationId", "7"),
      ),
    ) as DurableEvent.Export
    assertEquals("passwd", event.export.filename)
    assertEquals("html", SessionExportUi.extension(event.export.format))
  }
}
