package dev.pi.mobile.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file drawer's filesystem boundary: confinement, listing, truncation and binary honesty.
 * These run on the JVM because [WorkspaceFileBrowser] deliberately carries no Android types.
 */
class WorkspaceFileBrowserTest {
  @Rule
  @JvmField
  val temp = TemporaryFolder()

  private fun workspace(): File = temp.newFolder("workspace").also {
    File(it, "src").mkdirs()
    File(it, "src/Main.kt").writeText("fun main() {}\n")
    File(it, "README.md").writeText("# Notes\n")
    File(it, "src/nested").mkdirs()
  }

  @Test fun listReportsEntriesWithWorkspaceRelativePaths() {
    val root = workspace()
    val listing = WorkspaceFileBrowser.list(root, "").getOrThrow()
    assertEquals(setOf("README.md", "src"), listing.entries.map { it.name }.toSet())
    assertFalse(listing.truncated)
    assertEquals("src", listing.entries.first { it.directory }.path)

    val nested = WorkspaceFileBrowser.list(root, "src").getOrThrow()
    assertEquals(setOf("Main.kt", "nested"), nested.entries.map { it.name }.toSet())
    assertEquals("src/Main.kt", nested.entries.first { it.name == "Main.kt" }.path)
  }

  @Test fun aListingHonestlySaysWhenItWasCut() {
    val root = workspace()
    repeat(5) { File(root, "file-$it.txt").writeText("x") }
    val listing = WorkspaceFileBrowser.list(root, "", maxEntries = 3).getOrThrow()
    assertEquals(3, listing.entries.size)
    assertTrue(listing.truncated)
  }

  @Test fun traversalAndAbsolutePathsCannotEscapeTheWorkspace() {
    val root = workspace()
    val outside = temp.newFolder("outside").also { File(it, "secret.txt").writeText("no") }

    assertNull(WorkspaceFileBrowser.resolve(root, "../outside/secret.txt"))
    assertNull(WorkspaceFileBrowser.resolve(root, outside.absolutePath))
    assertEquals(root.canonicalFile, WorkspaceFileBrowser.resolve(root, "/"))
    assertTrue(WorkspaceFileBrowser.list(root, "../outside").isFailure)
    assertTrue(WorkspaceFileBrowser.readText(root, "../outside/secret.txt").isFailure)
  }

  @Test fun aSymlinkOutOfTheWorkspaceIsRefused() {
    val root = workspace()
    val outside = temp.newFolder("outside-link-target").also { File(it, "secret.txt").writeText("no") }
    val link = File(root, "escape")
    runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.getOrElse { return }
    assertNull(WorkspaceFileBrowser.resolve(root, "escape"))
    assertTrue(WorkspaceFileBrowser.list(root, "escape").isFailure)
  }

  @Test fun textPreviewsAreBounded() {
    val root = workspace()
    val long = "x".repeat(5000)
    File(root, "long.txt").writeText(long)
    val preview = WorkspaceFileBrowser.readText(root, "long.txt", maxBytes = 100).getOrThrow()
    assertEquals(100, preview.text.length)
    assertTrue(preview.truncated)

    val whole = WorkspaceFileBrowser.readText(root, "long.txt").getOrThrow()
    assertEquals(5000, whole.text.length)
    assertFalse(whole.truncated)
  }

  @Test fun nulBytesAreReportedAsBinaryRatherThanRendered() {
    val root = workspace()
    File(root, "binary.txt").writeBytes(byteArrayOf(0x41, 0x00, 0x42))
    val result = WorkspaceFileBrowser.readText(root, "binary.txt")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("binary", ignoreCase = true))
  }

  @Test fun missingFilesAndFoldersFailWithAReadableMessage() {
    val root = workspace()
    assertTrue(WorkspaceFileBrowser.list(root, "missing").isFailure)
    assertTrue(WorkspaceFileBrowser.readText(root, "missing.txt").isFailure)
    assertTrue(WorkspaceFileBrowser.list(root, "README.md").isFailure)
    assertTrue(WorkspaceFileBrowser.readText(root, "src").isFailure)
  }
}
