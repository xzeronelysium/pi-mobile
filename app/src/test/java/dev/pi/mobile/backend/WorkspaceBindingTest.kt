package dev.pi.mobile.backend

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceBindingTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun dirs(): Triple<File, File, File> {
    val files = tmp.newFolder("files")
    val external = tmp.newFolder("external")
    val shared = tmp.newFolder("shared")
    return Triple(files, external, shared)
  }

  @Test fun defaultModeUsesAppPrivateWorkspace() {
    val (files, external, shared) = dirs()
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { WorkspaceBinding.Stored.Default },
      save = {})
    val resolved = binding.resolve()
    assertEquals(File(files, "workspace").canonicalFile, resolved.root)
    assertEquals(WorkspaceBinding.Kind.APP_PRIVATE, resolved.kind)
    assertTrue(resolved.root.isDirectory)
  }

  @Test fun externalModeUsesAppExternalWorkspaceWithoutPermission() {
    val (files, external, shared) = dirs()
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { WorkspaceBinding.Stored.External },
      save = {})
    val resolved = binding.resolve()
    assertEquals(File(external, "workspace").canonicalFile, resolved.root)
    assertEquals(WorkspaceBinding.Kind.APP_EXTERNAL, resolved.kind)
  }

  @Test fun externalModeFallsBackWhenExternalMissing() {
    val (files, _, shared) = dirs()
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = null, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { WorkspaceBinding.Stored.External },
      save = {})
    assertEquals(WorkspaceBinding.Kind.APP_PRIVATE, binding.resolve().kind)
  }

  @Test fun customPathInsideAppExternalIsAllowedWithoutAllFiles() {
    val (files, external, shared) = dirs()
    val project = File(external, "MyProject").apply { mkdirs() }
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    val result = binding.bindPath(project.path)
    assertTrue(result.isSuccess)
    assertEquals(project.canonicalFile, binding.resolve().root)
    assertEquals(WorkspaceBinding.Kind.CUSTOM, binding.resolve().kind)
  }

  @Test fun customPathOutsideAppDirsRequiresAllFilesAccess() {
    val (files, external, shared) = dirs()
    val project = File(shared, "Projects/demo").apply { mkdirs() }
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { WorkspaceBinding.Stored.Default },
      save = {})
    val denied = binding.bindPath(project.path)
    assertTrue(denied.isFailure)
    assertTrue(denied.exceptionOrNull()!!.message!!.contains("All files access"))
  }

  @Test fun customPathOutsideAppDirsAllowedWithAllFilesAccess() {
    val (files, external, shared) = dirs()
    val project = File(shared, "Projects/demo").apply { mkdirs() }
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { true },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    assertTrue(binding.bindPath(project.path).isSuccess)
    assertEquals(project.canonicalFile, binding.resolve().root)
  }

  @Test fun pathMustStayUnderSharedRootEvenWithAllFilesAccess() {
    val (files, external, shared) = dirs()
    val outside = tmp.newFolder("other-volume")
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { true },
      load = { WorkspaceBinding.Stored.Default },
      save = {})
    assertTrue(binding.bindPath(outside.path).isFailure)
  }

  @Test fun safPrimaryTreeResolvesToSharedPathWhenAllFilesGranted() {
    val (files, external, shared) = dirs()
    File(shared, "Code/app").mkdirs()
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { true },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    val result = binding.bindSafTree(
      uri = "content://com.android.externalstorage.documents/tree/primary%3ACode%2Fapp",
      treeDocumentId = "primary:Code/app",
      displayName = "app")
    assertTrue(result.isSuccess)
    assertEquals(File(shared, "Code/app").canonicalFile, binding.resolve().root)
    assertEquals("content://com.android.externalstorage.documents/tree/primary%3ACode%2Fapp",
      (saved as WorkspaceBinding.Stored.Custom).safUri)
  }

  @Test fun safPrimaryTreeUnderAppExternalNeedsNoAllFiles() {
    val (files, external, shared) = dirs()
    // Simulate external files dir living under shared storage like a real device.
    val extUnderShared = File(shared, "Android/data/dev.pi.mobile/files").apply { mkdirs() }
    val project = File(extUnderShared, "repo").apply { mkdirs() }
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = extUnderShared, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    val result = binding.bindSafTree(
      uri = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fdev.pi.mobile%2Ffiles%2Frepo",
      treeDocumentId = "primary:Android/data/dev.pi.mobile/files/repo",
      displayName = "repo")
    assertTrue(result.isSuccess)
    assertEquals(project.canonicalFile, binding.resolve().root)
  }

  @Test fun safNonPrimaryOrUnresolvableFallsBackToMirrorUnderAppPrivate() {
    val (files, external, shared) = dirs()
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    val result = binding.bindSafTree(
      uri = "content://com.android.externalstorage.documents/tree/deadbeef%3Afoo",
      treeDocumentId = "deadbeef:foo",
      displayName = "foo")
    assertTrue(result.isSuccess)
    val resolved = binding.resolve()
    assertEquals(WorkspaceBinding.Kind.SAF_MIRROR, resolved.kind)
    assertTrue(resolved.root.path.startsWith(File(files, "workspace-mirrors").canonicalPath))
    assertTrue(resolved.root.isDirectory)
    assertNotNull(resolved.safUri)
  }

  @Test fun rejectsPathEscapeAndNonDirectory() {
    val (files, external, shared) = dirs()
    val file = tmp.newFile("not-a-dir.txt")
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { true },
      load = { WorkspaceBinding.Stored.Default },
      save = {})
    assertTrue(binding.bindPath(file.path).isFailure)
  }

  @Test fun resetToDefaultClearsCustomBinding() {
    val (files, external, shared) = dirs()
    var saved: WorkspaceBinding.Stored = WorkspaceBinding.Stored.Custom("/x", null, "x")
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { true },
      load = { saved },
      save = { saved = it })
    binding.useDefault()
    assertEquals(WorkspaceBinding.Stored.Default, saved)
    assertEquals(WorkspaceBinding.Kind.APP_PRIVATE, binding.resolve().kind)
  }

  @Test fun deniedPathDoesNotMkdirOutsideRoots() {
    val (files, external, shared) = dirs()
    val outside = File(tmp.root, "other-volume/sneaky").apply {
      // parent exists but target does not
      parentFile!!.mkdirs()
    }
    assertFalse(outside.exists())
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { WorkspaceBinding.Stored.Default },
      save = {})
    assertTrue(binding.bindPath(outside.path).isFailure)
    assertFalse("denied bind must not create the target", outside.exists())
  }

  @Test fun resolveRevalidatesSharedCustomWhenAllFilesRevoked() {
    val (files, external, shared) = dirs()
    val project = File(shared, "Projects/demo").apply { mkdirs() }
    var allFiles = true
    var saved: WorkspaceBinding.Stored = WorkspaceBinding.Stored.Custom(project.path, null, "demo")
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { allFiles },
      load = { saved },
      save = { saved = it })
    assertEquals(WorkspaceBinding.Kind.CUSTOM, binding.resolve().kind)
    allFiles = false
    val resolved = binding.resolve()
    assertEquals(WorkspaceBinding.Kind.APP_PRIVATE, resolved.kind)
    assertTrue(resolved.label.contains("revoked"))
  }

  @Test fun safMirrorLabelDisclosesLocalMirror() {
    val (files, external, shared) = dirs()
    var saved: WorkspaceBinding.Stored? = null
    val binding = WorkspaceBinding(
      filesDir = files, externalFilesDir = external, sharedStorageRoot = shared,
      isAllFilesAccess = { false },
      load = { saved ?: WorkspaceBinding.Stored.Default },
      save = { saved = it })
    val resolved = binding.bindSafTree(
      uri = "content://com.android.externalstorage.documents/tree/deadbeef%3Afoo",
      treeDocumentId = "deadbeef:foo",
      displayName = "Photos").getOrThrow()
    assertEquals(WorkspaceBinding.Kind.SAF_MIRROR, resolved.kind)
    assertTrue(resolved.label.contains("mirror"))
  }

}
