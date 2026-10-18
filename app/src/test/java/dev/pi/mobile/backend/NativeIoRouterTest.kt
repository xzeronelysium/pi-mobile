package dev.pi.mobile.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The per-call working directory the durable environment sends must actually select the adapter
 * root: `shell.exec` runs in it, and absolute file paths are interpreted against it.
 *
 * Everything outside the allowed roots fails with a coded `permission_denied` instead of quietly
 * running somewhere else. When the user has granted All-files access the allowed roots must cover
 * the shared storage that grant actually permits — so an ordinary user folder outside the bound
 * workspace is reachable — while the workspace stays the cwd and the app-private fallback stays the
 * floor.
 */
class NativeIoRouterTest {
  @get:Rule val tmp = TemporaryFolder()

  private lateinit var filesDir: File
  private lateinit var cacheDir: File
  private lateinit var fallback: File
  private lateinit var custom: File
  private lateinit var tempRoot: File
  /** Stands in for `/storage/emulated/0`: the shared storage a granted All-files permission covers. */
  private lateinit var shared: File

  @Before fun setUp() {
    filesDir = tmp.newFolder("files")
    cacheDir = tmp.newFolder("cache")
    fallback = File(filesDir, "workspace").apply { mkdirs() }
    custom = tmp.newFolder("chosen-project")
    tempRoot = File(cacheDir, "native-tmp").apply { mkdirs() }
    shared = tmp.newFolder("shared-storage")
  }

  private fun router(
    workspacePath: String? = null,
    allFiles: Boolean = false,
    broadRoots: List<File> = emptyList(),
    log: (String) -> Unit = {},
  ): NativeIoRouter = NativeIoRouter(
    fallbackRoot = fallback,
    workspacePath = workspacePath,
    tempRoot = tempRoot,
    shellExecutable = "/bin/sh",
    isAllFilesAccess = { allFiles },
    broadRoots = broadRoots,
    log = log,
  )

  private fun permissionDenied(block: () -> Unit): String = try {
    block()
    throw AssertionError("expected a NativeFailure")
  } catch (error: NativeFailure) {
    error.code
  }

  @Test fun blankCwdFallsBackToTheConfiguredWorkspace() {
    val withWorkspace = router(custom.path)
    assertEquals(custom.canonicalFile, withWorkspace.defaultRoot)
    assertEquals(custom.canonicalFile, withWorkspace.resolveCwd(null))
    assertEquals(custom.canonicalFile, withWorkspace.resolveCwd("  "))

    val withoutWorkspace = router(null)
    assertEquals(fallback.canonicalFile, withoutWorkspace.defaultRoot)
    assertEquals(fallback.canonicalFile, withoutWorkspace.resolveCwd(null))
  }

  @Test fun perCallCwdInsideAnAllowedRootIsHonoured() {
    val io = router(custom.path)
    val project = File(custom, "pkg/deep").apply { mkdirs() }
    assertEquals(project.canonicalFile, io.resolveCwd(project.path))
    // The app-private fallback stays reachable even while a custom workspace is bound.
    assertEquals(fallback.canonicalFile, io.resolveCwd(fallback.path))
  }

  @Test fun cwdOutsideEveryAllowedRootFailsCoded() {
    val io = router(custom.path)
    val outside = tmp.newFolder("elsewhere")
    assertEquals("permission_denied", permissionDenied { io.resolveCwd(outside.path) })
    assertEquals("permission_denied", permissionDenied { io.resolveCwd("/") })
  }

  @Test fun aSymlinkedCwdThatEscapesEveryRootIsRejected() {
    val io = router(custom.path)
    val outside = tmp.newFolder("hidden")
    val link = File(custom, "escape")
    Files.createSymbolicLink(link.toPath(), outside.toPath())
    assertEquals("permission_denied", permissionDenied { io.resolveCwd(link.path) })
  }

  @Test fun shellRunsInTheRequestedDirectoryAndDefaultsToTheWorkspace() {
    val io = router(custom.path)
    val project = File(custom, "pkg").apply { mkdirs() }
    val inProject = io.shell(project.path).execute("pwd", environment = mapOf("PATH" to "/bin"))
    assertEquals(project.canonicalPath, inProject.stdout.trim())
    assertEquals(0, inProject.exitCode)

    val byDefault = io.shell(null).execute("pwd", environment = mapOf("PATH" to "/bin"))
    assertEquals(custom.canonicalPath, byDefault.stdout.trim())
  }

  @Test fun adapterInstancesAreCachedPerCanonicalRoot() {
    val io = router(custom.path)
    val project = File(custom, "pkg").apply { mkdirs() }
    val other = File(custom, "other").apply { mkdirs() }

    assertSame(io.shell(project.path), io.shell(project.path))
    assertNotSame(io.shell(project.path), io.shell(other.path))

    val rooted = io.confine(project.path).root
    assertSame(io.files(rooted), io.files(rooted))
    assertSame(io.files(io.confine(project.path).root), io.files(io.confine(project.path).root))
  }

  @Test fun anAbsoluteFilePathInsideTheWorkspaceIsRebasedOntoItsRoot() {
    val io = router(custom.path)
    val routed = io.confine(File(custom, "pkg/notes.txt").path)
    assertEquals(custom.canonicalFile, routed.root)
    assertEquals("pkg/notes.txt", routed.path)

    val storage = io.files(routed.root)
    storage.dispatch("file.write", JSONObject().put("path", routed.path).put("text", "hello"))
    val read = storage.dispatch("file.readRange", JSONObject().put("path", routed.path).put("offset", 0).put("length", 64)) as JSONObject
    assertEquals("hello", String(BridgeBase64.decode(read.getString("base64")), Charsets.UTF_8))
    assertEquals("hello", File(custom, "pkg/notes.txt").readText())
  }

  @Test fun absoluteFilePathsOutsideEveryAllowedRootFailCoded() {
    val io = router(custom.path)
    val outside = File(tmp.newFolder("outside"), "secret.txt").apply { writeText("no") }
    assertEquals("permission_denied", permissionDenied { io.confine(outside.path) })
    assertEquals("permission_denied", permissionDenied { io.confine("/") })
  }

  @Test fun relativePathsKeepTheDefaultRootAndTheirRelativeForm() {
    val io = router(custom.path)
    val routed = io.confine("pkg/notes.txt")
    assertEquals(custom.canonicalFile, routed.root)
    assertEquals("pkg/notes.txt", routed.path)
    assertEquals("", io.confine("").path)
  }

  @Test fun theWorkspaceRootItselfRoutesToAnEmptyRelativePath() {
    val io = router(custom.path)
    val routed = io.confine(custom.canonicalPath)
    assertEquals(custom.canonicalFile, routed.root)
    assertEquals("", routed.path)
  }

  @Test fun confinementStillRejectsASymlinkThatEscapesTheRoutedRoot() {
    val io = router(custom.path)
    val outside = File(tmp.newFolder("target"), "real.txt").apply { writeText("secret") }
    Files.createSymbolicLink(File(custom, "link.txt").toPath(), outside.toPath())

    val routed = io.confine(File(custom, "link.txt").path)
    assertEquals(custom.canonicalFile, routed.root)
    val storage = io.files(routed.root)
    assertEquals("permission_denied", permissionDenied {
      storage.dispatch("file.readRange", JSONObject().put("path", routed.path).put("offset", 0).put("length", 64))
    })
  }

  @Test fun theSpillDirectoryTheShellReportsStaysReadableAndWritable() {
    val io = router(custom.path)
    val spill = File(tempRoot, "pi-spill-1.txt").apply { writeText("full output") }
    val routed = io.confine(spill.path)
    assertEquals(tempRoot.canonicalFile, routed.root)
    assertEquals("pi-spill-1.txt", routed.path)

    val storage = io.files(routed.root)
    storage.dispatch("file.write", JSONObject().put("path", routed.path).put("text", "more"))
    assertEquals("more", spill.readText())
    val read = storage.dispatch("file.readRange", JSONObject().put("path", routed.path).put("offset", 0).put("length", 64)) as JSONObject
    assertEquals("more", String(BridgeBase64.decode(read.getString("base64")), Charsets.UTF_8))
  }

  @Test fun theSpillDirectoryDoesNotOpenUpTheRestOfTheCache() {
    val io = router(custom.path)
    val elsewhere = File(cacheDir, "elsewhere").apply { mkdirs() }
    assertEquals("permission_denied", permissionDenied { io.confine(File(elsewhere, "x.txt").path) })
    assertEquals("permission_denied", permissionDenied { io.confine(cacheDir.path) })
  }

  @Test fun closingReleasesEveryCachedFileAdapter() {
    val io = router(custom.path)
    io.files(io.confine(File(custom, "a.txt").path).root)
    io.close()
    assertTrue(true)
  }

  // ------------------------------------------------------------------ All-files reach

  /**
   * The whole point of the grant: an ordinary user folder that is outside the bound workspace — the
   * shared storage the permission covers — is reachable, for a shell cwd and for a file path.
   */
  @Test fun allFilesAccessWidensTheReachToSharedStorageOutsideTheWorkspace() {
    val download = File(shared, "Download").apply { mkdirs() }
    val io = router(workspacePath = custom.path, allFiles = true, broadRoots = listOf(shared))

    assertTrue(io.allowedRoots.contains(shared.canonicalFile))
    assertEquals(download.canonicalFile, io.resolveCwd(download.path))

    val ran = io.shell(download.path).execute("pwd", environment = mapOf("PATH" to "/bin"))
    assertEquals(download.canonicalPath, ran.stdout.trim())
    assertEquals(0, ran.exitCode)

    val routed = io.confine(File(download, "notes.txt").path)
    assertEquals(shared.canonicalFile, routed.root)
    assertEquals("Download/notes.txt", routed.path)

    // A granted permission that the OS would honour must survive a real read/write through the root.
    val storage = io.files(routed.root)
    storage.dispatch("file.write", JSONObject().put("path", routed.path).put("text", "reached"))
    assertEquals("reached", File(download, "notes.txt").readText())
  }

  /** The mirror of the test above: no grant, no illusion — the same path stays a coded refusal. */
  @Test fun withoutAllFilesAccessTheSameSharedPathIsStillRefused() {
    val download = File(shared, "Download").apply { mkdirs() }
    val io = router(workspacePath = custom.path, allFiles = false, broadRoots = listOf(shared))

    assertFalse(io.allowedRoots.contains(shared.canonicalFile))
    assertEquals("permission_denied", permissionDenied { io.resolveCwd(download.path) })
    assertEquals("permission_denied", permissionDenied { io.confine(File(download, "notes.txt").path) })
  }

  /** A path outside every root — grant or not — is refused, never silently rebased. */
  @Test fun pathsOutsideEveryRootStayRefusedInBothStates() {
    val download = File(shared, "Download").apply { mkdirs() }
    for (allFiles in listOf(false, true)) {
      val io = router(workspacePath = custom.path, allFiles = allFiles, broadRoots = listOf(shared))
      assertEquals("permission_denied", permissionDenied { io.resolveCwd("/system/bin") })
      assertEquals("permission_denied", permissionDenied { io.resolveCwd("/data/data/other.pkg") })
      assertEquals("permission_denied", permissionDenied { io.confine("/system/bin/sh") })
      val other = tmp.newFolder("other-volume-$allFiles")
      assertEquals("permission_denied", permissionDenied { io.resolveCwd(other.path) })
      assertEquals("permission_denied", permissionDenied { io.confine(File(other, "x").path) })
      // The widened root is reachable only when granted, so the refusals above are not a fluke.
      if (allFiles) assertEquals(download.canonicalFile, io.resolveCwd(download.path))
      else assertEquals("permission_denied", permissionDenied { io.resolveCwd(download.path) })
    }
  }

  /** Widening the reach must not move the cwd/HOME: the workspace stays first and resolves the cwd. */
  @Test fun theWorkspaceStaysTheCwdAndTheFirstRootWhenTheReachWidens() {
    val io = router(workspacePath = custom.path, allFiles = true, broadRoots = listOf(shared))
    assertEquals(custom.canonicalFile, io.defaultRoot)
    assertEquals(custom.canonicalFile, io.allowedRoots.first())
    assertEquals(custom.canonicalFile, io.resolveCwd(null))
    assertEquals(custom.canonicalFile, io.resolveCwd("  "))

    val ran = io.shell(null).execute("pwd", environment = mapOf("PATH" to "/bin"))
    assertEquals(custom.canonicalPath, ran.stdout.trim())
  }

  /** One unusable candidate (missing, a file, unreadable) is dropped, not allowed to poison the list. */
  @Test fun anUnusableExtraRootIsSkippedWithoutBreakingTheList() {
    val download = File(shared, "Download").apply { mkdirs() }
    val missing = File(tmp.root, "does-not-exist")
    val notADir = tmp.newFile("plain.txt")
    val locked = tmp.newFolder("locked")
    locked.setReadable(false, false)
    val unreadable = !locked.canRead()
    try {
      val io = router(
        workspacePath = custom.path,
        allFiles = true,
        broadRoots = listOf(missing, notADir, locked, shared),
      )
      assertFalse("a missing root must be skipped", io.allowedRoots.contains(missing.canonicalFile))
      assertFalse("a non-directory root must be skipped", io.allowedRoots.contains(notADir.canonicalFile))
      if (unreadable) assertFalse("an unreadable root must be skipped", io.allowedRoots.contains(locked.canonicalFile))
      // The readable candidate survives, and the workspace is still first.
      assertTrue(io.allowedRoots.contains(shared.canonicalFile))
      assertEquals(custom.canonicalFile, io.allowedRoots.first())
      assertEquals(shared.canonicalFile, io.confine(File(download, "a.txt").path).root)
    } finally {
      locked.setReadable(true, false)
    }
  }

  /** The app-private fallback is the floor: it is always present, and it is the cwd when none is bound. */
  @Test fun theAppPrivateFallbackSurvivesAloneWhenTheWorkspaceIsUnset() {
    val io = router(workspacePath = null, allFiles = true, broadRoots = emptyList())
    assertEquals(listOf(fallback.canonicalFile), io.allowedRoots)
    assertEquals(fallback.canonicalFile, io.defaultRoot)
    assertEquals(fallback.canonicalFile, io.resolveCwd(null))
  }

  /** A workspace root that is not there cannot anchor anything; the fallback still does. */
  @Test fun aMissingWorkspaceIsSkippedSoTheFallbackStillAnchorsTheList() {
    val gone = File(tmp.root, "gone-project")
    val io = router(workspacePath = gone.path, allFiles = false)
    assertFalse(io.allowedRoots.contains(gone.canonicalFile))
    assertTrue(io.allowedRoots.contains(fallback.canonicalFile))
    assertTrue(io.allowedRoots.isNotEmpty())
    assertEquals(fallback.canonicalFile, io.defaultRoot)
  }

  /** The one line a device inspection greps to learn what the agent can reach. */
  @Test fun theRootsLineNamesTheEffectiveRootsAndWhetherAllFilesWidenedThem() {
    val widened = mutableListOf<String>()
    router(
      workspacePath = custom.path,
      allFiles = true,
      broadRoots = listOf(shared),
      log = { widened += it },
    )
    assertEquals(1, widened.size)
    val line = widened.single()
    assertTrue(line.startsWith(NativeIoRouter.ROOTS_PREFIX))
    assertTrue(line.contains("allFiles=true"))
    assertTrue(line.contains("widened=true"))
    assertTrue(line.contains(custom.canonicalPath))
    assertTrue(line.contains(shared.canonicalPath))

    val narrow = mutableListOf<String>()
    router(
      workspacePath = custom.path,
      allFiles = false,
      broadRoots = listOf(shared),
      log = { narrow += it },
    )
    assertEquals(1, narrow.size)
    assertTrue(narrow.single().contains("allFiles=false"))
    assertTrue(narrow.single().contains("widened=false"))
    assertFalse(narrow.single().contains(shared.canonicalPath))
  }

  // ------------------------------------------------------------------ the reach the report states

  /**
   * The capability report's reach must be the router's own answer, not a second guess at the same
   * paths: what `allowedRoots` accepts a cwd against is exactly what `reach()` states, and the
   * file-only temp root joins the file roots without ever becoming a cwd root.
   */
  @Test fun theReachIsTheLiveRoutersOwnRootsIncludingTheFileOnlyTempRoot() {
    val io = router(workspacePath = custom.path)
    val reach = io.reach()

    assertTrue("a built router is an observed reach", reach.observed)
    assertEquals(io.allowedRoots.map { it.path }, reach.shellRoots)
    assertEquals(
      (io.allowedRoots + tempRoot.canonicalFile).map { it.path }.distinct(),
      reach.fileRoots,
    )
    assertEquals(custom.canonicalPath, reach.defaultRoot)
    assertEquals(fallback.canonicalPath, reach.fallbackRoot)
    assertEquals(tempRoot.canonicalPath, reach.tempRoot)
    assertTrue("the report states where the roots came from", reach.source!!.contains("NativeIoRouter"))
    assertNull(reach.failure)
    assertFalse("the temp dir is not a cwd root", reach.shellRoots.contains(tempRoot.canonicalPath))
  }

  /**
   * The grant is part of the answer the report gives: the reach widens to the shared storage the
   * permission covers while it is held, and narrows back to the workspace pair the moment it is not.
   * The report must never state a root the router would actually refuse.
   */
  @Test fun theReachWidensWithAllFilesAccessAndNarrowsBackWithoutIt() {
    val download = File(shared, "Download").apply { mkdirs() }

    val granted = router(workspacePath = custom.path, allFiles = true, broadRoots = listOf(shared)).reach()
    assertTrue("the grant widens the reach", granted.shellRoots.contains(shared.canonicalPath))
    assertTrue(granted.fileRoots.contains(shared.canonicalPath))
    assertTrue(granted.shellRoots.contains(custom.canonicalPath))
    assertEquals("widening never moves the cwd", custom.canonicalPath, granted.defaultRoot)

    val absent = router(workspacePath = custom.path, allFiles = false, broadRoots = listOf(shared)).reach()
    assertFalse("no grant, no shared storage in the reach", absent.shellRoots.contains(shared.canonicalPath))
    assertFalse(absent.fileRoots.contains(shared.canonicalPath))
    assertEquals(
      "the workspace pair is the whole reach",
      listOf(custom.canonicalPath, fallback.canonicalPath),
      absent.shellRoots,
    )
    assertEquals(custom.canonicalPath, absent.defaultRoot)

    // What the grant would have covered is refused exactly when the report says it is absent.
    val refused = router(workspacePath = custom.path, allFiles = false, broadRoots = listOf(shared))
    assertEquals("permission_denied", permissionDenied { refused.resolveCwd(download.path) })
  }

  /** The grep line an inspection reads and the report an agent reads must be the same answer. */
  @Test fun theRootsLineStatesTheSameRootsTheReportDoes() {
    val lines = mutableListOf<String>()
    val io = router(workspacePath = custom.path, allFiles = true, broadRoots = listOf(shared), log = { lines += it })
    val reach = io.reach()
    val line = lines.single()

    (reach.shellRoots + reach.fileRoots).forEach {
      assertTrue("root $it is missing from the io.roots line: $line", line.contains(it))
    }
    assertTrue("and the cwd the report names", line.contains("cwd=${reach.defaultRoot}"))
  }

  // ------------------------------------------------------------------ HOME and TMPDIR

  /**
   * The shell's own environment must be derived from the roots the call is routed through, never
   * hardcoded beside them: a shell that resolves `~` lands in the directory the command actually
   * runs in, which is inside an allowed root by construction.
   */
  @Test fun homeIsTheDirectoryTheShellActuallyRunsIn() {
    val io = router(custom.path)
    val project = File(custom, "pkg/deep").apply { mkdirs() }

    assertEquals(project.canonicalPath, io.shellEnvironment(project.path).getValue("HOME"))
    assertEquals(io.resolveCwd(project.path).path, io.shellEnvironment(project.path).getValue("HOME"))
    // No cwd asked for: the command runs in the default root, so that is HOME.
    assertEquals(io.defaultRoot.path, io.shellEnvironment(null).getValue("HOME"))
    // The app-private fallback stays reachable as an explicit cwd, so HOME follows it there too.
    assertEquals(fallback.canonicalPath, io.shellEnvironment(fallback.path).getValue("HOME"))
  }

  @Test fun aCwdOutsideEveryRootIsRefusedForTheEnvironmentToo() {
    val io = router(custom.path)
    val outside = tmp.newFolder("somewhere-else")
    assertEquals("permission_denied", permissionDenied { io.shellEnvironment(outside.path) })
  }

  /**
   * The temp directory the shell is told to write into must be the very root the file ops allow —
   * otherwise the agent writes a file it was told to write and cannot read it back, which is a coded
   * `permission_denied` for a path the app itself handed over.
   */
  @Test fun theTempDirTheShellIsGivenIsTheOneTheFileOpsCanReadBack() {
    val io = router(custom.path)
    val tmpdir = io.shellEnvironment(null).getValue("TMPDIR")
    assertEquals("the advertised temp root is the router's own", io.reach().tempRoot, tmpdir)
    assertTrue("and it is one of the file roots", io.reach().fileRoots.contains(tmpdir))

    val written = File(tmpdir, "shell-wrote.txt").apply { writeText("spilled output") }
    val routed = io.confine(written.path)
    assertEquals(tempRoot.canonicalFile, routed.root)

    val storage = io.files(routed.root)
    val stat = storage.dispatch("file.stat", JSONObject().put("path", routed.path)) as JSONObject
    assertTrue("a file the shell was told to write must exist as far as the agent is concerned", stat.getBoolean("exists"))
    val read = storage.dispatch("file.readRange", JSONObject().put("path", routed.path).put("offset", 0).put("length", 64)) as JSONObject
    assertEquals("spilled output", String(BridgeBase64.decode(read.getString("base64")), Charsets.UTF_8))
  }

  @Test fun callerSuppliedVariablesAreAppliedLast() {
    val io = router(custom.path)
    val extra = JSONObject().put("LC_ALL", "C").put("TMPDIR", "/data/local/tmp")
    val environment = io.shellEnvironment(null, extra)
    assertEquals("C", environment.getValue("LC_ALL"))
    assertEquals("an explicit caller pair wins", "/data/local/tmp", environment.getValue("TMPDIR"))
    assertEquals(io.defaultRoot.path, environment.getValue("HOME"))
  }

  // ------------------------------------------------------------------ the All-files grant, live

  /**
   * The grant is read when it is used, not snapshotted when the runtime was built: the user returns
   * from the OS screen and the very next call must already reach shared storage, without waiting for
   * an unrelated restart. Revoking it must narrow the reach back just as promptly.
   */
  @Test fun theAllFilesGrantWidensAndNarrowsTheLiveRouterWithoutARebuild() {
    val download = File(shared, "Download").apply { mkdirs() }
    var granted = false
    val io = NativeIoRouter(
      fallbackRoot = fallback,
      workspacePath = custom.path,
      tempRoot = tempRoot,
      shellExecutable = "/bin/sh",
      isAllFilesAccess = { granted },
      broadRoots = listOf(shared),
    )

    assertEquals("no grant yet", "permission_denied", permissionDenied { io.resolveCwd(download.path) })
    assertFalse(io.reach().shellRoots.contains(shared.canonicalPath))

    granted = true
    assertEquals("granted ⇒ the shared storage is reachable", download.canonicalFile, io.resolveCwd(download.path))
    assertTrue(io.reach().shellRoots.contains(shared.canonicalPath))
    assertTrue(io.reach().fileRoots.contains(shared.canonicalPath))
    assertEquals(shared.canonicalFile, io.confine(File(download, "notes.txt").path).root)
    assertEquals("widening never moves the cwd", custom.canonicalFile, io.defaultRoot)
    val ran = io.shell(download.path).execute("pwd", environment = io.shellEnvironment(download.path))
    assertEquals(download.canonicalPath, ran.stdout.trim())

    granted = false
    assertEquals("revoked ⇒ narrowed again", "permission_denied", permissionDenied { io.resolveCwd(download.path) })
    assertFalse(io.reach().shellRoots.contains(shared.canonicalPath))
    assertFalse(io.reach().fileRoots.contains(shared.canonicalPath))
    assertEquals(custom.canonicalFile, io.defaultRoot)
  }

  @Test fun aGrantFlipIsLoggedOnceWhenItChangesNotOnEveryRead() {
    val lines = mutableListOf<String>()
    var granted = false
    val io = NativeIoRouter(
      fallbackRoot = fallback,
      workspacePath = custom.path,
      tempRoot = tempRoot,
      shellExecutable = "/bin/sh",
      isAllFilesAccess = { granted },
      broadRoots = listOf(shared),
      log = { lines += it },
    )
    assertEquals("one line at construction", 1, lines.size)

    io.reach(); io.allowedRoots; io.confine("x.txt")
    assertEquals("reads that see no change log nothing", 1, lines.size)

    granted = true
    io.reach()
    assertEquals("the grant flip is reported", 2, lines.size)
    assertTrue(lines.last().contains("allFiles=true"))
    assertTrue(lines.last().contains("widened=true"))

    io.reach()
    assertEquals("and only once", 2, lines.size)

    granted = false
    assertFalse(io.reach().shellRoots.contains(shared.canonicalPath))
    assertEquals(3, lines.size)
    assertTrue(lines.last().contains("allFiles=false"))
    assertTrue(lines.last().contains("widened=false"))
  }
}
