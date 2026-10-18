package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file drawer's decisions: path grammar, row order, preview classification, attach honesty and
 * the state transitions the Activity calls. Filesystem reads are [WorkspaceFileBrowser]'s tests.
 */
class WorkspaceFilesUiTest {
  private fun file(name: String, size: Long = 10L) = WorkspaceEntry(
    name = name,
    path = WorkspaceFilesUi.child("src", name),
    directory = false,
    sizeBytes = size,
  )

  private fun folder(name: String) = WorkspaceEntry(
    name = name,
    path = WorkspaceFilesUi.child("src", name),
    directory = true,
  )

  @Test fun pathsAreNormalizedAndCannotClimbAboveTheRoot() {
    assertEquals("", WorkspaceFilesUi.normalizePath("/"))
    assertEquals("", WorkspaceFilesUi.normalizePath("../../.."))
    assertEquals("src/main", WorkspaceFilesUi.normalizePath("/src//./main/"))
    assertEquals("src/main", WorkspaceFilesUi.normalizePath("src/other/../main"))
    assertEquals("src/main.kt", WorkspaceFilesUi.child("src", "main.kt"))
    assertEquals("main.kt", WorkspaceFilesUi.child("", "/main.kt"))
  }

  @Test fun parentsStopAtTheRoot() {
    assertNull(WorkspaceFilesUi.parent(""))
    assertEquals("", WorkspaceFilesUi.parent("src"))
    assertEquals("src", WorkspaceFilesUi.parent("src/main"))
  }

  @Test fun crumbsStartAtTheWorkspaceAndEndAtTheCurrentFolder() {
    val crumbs = WorkspaceFilesUi.crumbs("src/main/kotlin", "app private")
    assertEquals(listOf("app private", "src", "main", "kotlin"), crumbs.map { it.label })
    assertEquals(listOf("", "src", "src/main", "src/main/kotlin"), crumbs.map { it.path })
    assertEquals(listOf("Workspace"), WorkspaceFilesUi.crumbs("", "").map { it.label })
  }

  @Test fun previewKindIsDecidedByNameNotByOptimism() {
    assertEquals(FilePreviewKind.MARKDOWN, WorkspaceFilesUi.previewKind("README.md"))
    assertEquals(FilePreviewKind.CODE, WorkspaceFilesUi.previewKind("MainActivity.kt"))
    assertEquals(FilePreviewKind.TEXT, WorkspaceFilesUi.previewKind("notes.txt"))
    assertEquals(FilePreviewKind.TEXT, WorkspaceFilesUi.previewKind("Dockerfile"))
    assertEquals(FilePreviewKind.IMAGE, WorkspaceFilesUi.previewKind("photo.JPG"))
    assertEquals(FilePreviewKind.BINARY, WorkspaceFilesUi.previewKind("archive.zip"))
    assertEquals(FilePreviewKind.BINARY, WorkspaceFilesUi.previewKind("report.pdf"))
  }

  @Test fun theHighlighterGetsTheLanguageItKnows() {
    assertEquals("kotlin", WorkspaceFilesUi.languageFor("Main.kt"))
    assertEquals("typescript", WorkspaceFilesUi.languageFor("app.tsx"))
    assertEquals("shell", WorkspaceFilesUi.languageFor("run.sh"))
    assertEquals("diff", WorkspaceFilesUi.languageFor("fix.patch"))
    assertEquals("", WorkspaceFilesUi.languageFor("notes.txt"))
  }

  @Test fun foldersSortFirstThenNamesCaseInsensitively() {
    val entries = listOf(file("zeta.kt"), folder("src"), file("Alpha.kt"), folder("assets"))
    assertEquals(
      listOf("assets", "src", "Alpha.kt", "zeta.kt"),
      WorkspaceFilesUi.sorted(entries).map { it.name },
    )
  }

  @Test fun theFilterAppliesToTheCurrentFoldersRows() {
    val state = WorkspaceFilesState(entries = listOf(file("Main.kt"), file("notes.md"), folder("main-assets")))
    assertEquals(
      listOf("main-assets", "Main.kt"),
      WorkspaceFilesUi.visibleEntries(state.copy(query = "MAIN")).map { it.name },
    )
    assertEquals(emptyList<String>(), WorkspaceFilesUi.visibleEntries(state.copy(query = "nothing")).map { it.name })
  }

  @Test fun attachIsRefusedForFoldersAndFilesOverTheComposerLimit() {
    assertTrue(WorkspaceFilesUi.canAttach(file("small.kt")))
    assertFalse(WorkspaceFilesUi.canAttach(folder("src")))
    assertFalse(WorkspaceFilesUi.canAttach(file("huge.bin", AttachmentLimits.MAX_BYTES_PER_FILE + 1)))
    assertTrue(WorkspaceFilesUi.attachRejection(folder("src")) != null)
    assertTrue(WorkspaceFilesUi.attachRejection(file("huge.bin", AttachmentLimits.MAX_BYTES_PER_FILE + 1)) != null)
    assertNull(WorkspaceFilesUi.attachRejection(file("small.kt")))
  }

  @Test fun sizesReadAsHumanSizesNotByteCounts() {
    assertEquals("0 B", WorkspaceFilesUi.humanSize(0))
    assertEquals("512 B", WorkspaceFilesUi.humanSize(512))
    assertEquals("1 KB", WorkspaceFilesUi.humanSize(1024))
    assertEquals("1.5 KB", WorkspaceFilesUi.humanSize(1536))
    assertEquals("1 MB", WorkspaceFilesUi.humanSize(1024L * 1024L))
    assertEquals("1 GB", WorkspaceFilesUi.humanSize(1024L * 1024L * 1024L))
  }

  @Test fun openingKeepsTheFolderButDropsTheFilterAndPreview() {
    val previewed = WorkspaceFilesReducer.previewOpened(
      WorkspaceFilesState(path = "src", query = "main"),
      file("Main.kt"),
      "/tmp/src/Main.kt",
      loading = true,
    )
    val opened = WorkspaceFilesReducer.open(previewed)
    assertTrue(opened.open)
    assertEquals("src", opened.path)
    assertEquals("", opened.query)
    assertNull(opened.preview)
  }

  @Test fun closingDropsThePreviewAndFilterButKeepsTheFolder() {
    val state = WorkspaceFilesReducer.close(
      WorkspaceFilesState(open = true, path = "src", query = "main", preview = FilePreviewState(file("a"), "/a")),
    )
    assertFalse(state.open)
    assertEquals("src", state.path)
    assertEquals("", state.query)
    assertNull(state.preview)
  }

  @Test fun navigatingClearsStaleRowsUnderTheNewBreadcrumb() {
    val state = WorkspaceFilesReducer.navigate(
      WorkspaceFilesState(open = true, entries = listOf(file("old.kt")), query = "old", truncated = true),
      "src/main/",
    )
    assertEquals("src/main", state.path)
    assertTrue(state.entries.isEmpty())
    assertEquals("", state.query)
    assertTrue(state.loading)
    assertFalse(state.truncated)
  }

  /** A late response for a folder the user already left must not replace the current rows. */
  @Test fun aLateListingForAnotherFolderIsIgnored() {
    val state = WorkspaceFilesState(path = "src", loading = true)
    val stale = WorkspaceFilesReducer.listed(state, "other", listOf(file("wrong.kt")), truncated = false)
    assertEquals(state, stale)
    val live = WorkspaceFilesReducer.listed(state, "src", listOf(file("right.kt")), truncated = false)
    assertEquals(listOf("right.kt"), live.entries.map { it.name })
    assertFalse(live.loading)
  }

  @Test fun previewStatesLoadFailAndCloseWithoutTouchingTheFolder() {
    val base = WorkspaceFilesState(open = true, path = "src")
    val opened = WorkspaceFilesReducer.previewOpened(base, file("Main.kt"), "/tmp/Main.kt", loading = true)
    assertTrue(opened.preview!!.loading)
    val loaded = WorkspaceFilesReducer.previewLoaded(opened, "fun main() {}", truncated = false)
    assertEquals("fun main() {}", loaded.preview!!.text)
    assertFalse(loaded.preview!!.loading)
    val failed = WorkspaceFilesReducer.previewFailed(opened, "gone")
    assertEquals("gone", failed.preview!!.error)
    assertEquals("src", failed.path)
    assertNull(WorkspaceFilesReducer.previewClosed(loaded).preview)
  }
}
