package dev.pi.mobile.chat

import android.util.Base64
import java.io.File

/**
 * Writing an exported transcript to a real file the user can take off the phone.
 *
 * The host either hands back encoded bytes or the document itself; either way the Activity shows the
 * path it actually wrote rather than assuming one. Filename safety is [SessionExportUi]'s rule.
 */
internal object ExportFiles {
  /** The app's export folder, preferring external files when the device exposes it. */
  fun directory(externalFiles: File?, filesDir: File): File =
    externalFiles ?: File(filesDir, "exports")

  /** Write the export into [directory]; the result carries a user-readable failure message. */
  fun write(result: DurableEvent.ExportResult, directory: File): Result<File> {
    val file = File(directory, SessionExportUi.safeFileName(result.filename, result.format))
    return runCatching {
      directory.mkdirs()
      if (result.base64.isNotBlank()) {
        file.writeBytes(Base64.decode(result.base64, Base64.DEFAULT))
      } else {
        file.writeText(result.text)
      }
      file
    }.recoverCatching { error ->
      throw java.io.IOException("Could not write ${file.name}: ${error.javaClass.simpleName}")
    }
  }

  /** What the user reads after a successful write: name, size, truncation and the real path. */
  fun notice(file: File, result: DurableEvent.ExportResult): String = buildString {
    append("Exported ${file.name} · ${file.length()} bytes")
    if (result.truncated) append(" · truncated")
    append("\n${file.path}")
  }
}
