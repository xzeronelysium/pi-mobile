package dev.pi.mobile.backend

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The identity a workspace is stored under.
 *
 * The same canonical directory must always produce the same key (so returning to a folder returns
 * to its sessions), two different directories must never share one (so a session cannot follow the
 * reader between workspaces), and the pre-workspace database must be adopted exactly once.
 */
class WorkspaceIdentityTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun theSameDirectoryHasTheSameKeyWhateverFormItsPathTakes() {
    val directory = tmp.newFolder("repo")
    val key = WorkspaceIdentity.key(directory.path)

    assertEquals(key, WorkspaceIdentity.key(directory.path + "/"))
    assertEquals(key, WorkspaceIdentity.key(File(directory, ".").path))
    assertEquals(key, WorkspaceIdentity.key(File(File(directory, ".."), directory.name).path))
    assertEquals(key, WorkspaceIdentity.key(directory.canonicalPath))
  }

  @Test fun differentDirectoriesHaveDifferentKeys() {
    val first = tmp.newFolder("one")
    val second = tmp.newFolder("two")

    assertNotEquals(WorkspaceIdentity.key(first.path), WorkspaceIdentity.key(second.path))
  }

  @Test fun aBlankPathIsTheAppPrivateDefaultKey() {
    assertEquals(WorkspaceIdentity.key(null), WorkspaceIdentity.key(""))
    assertEquals(WorkspaceIdentity.key("   "), WorkspaceIdentity.key(""))
  }

  @Test fun eachWorkspaceOwnsADifferentDatabaseFile() {
    val first = tmp.newFolder("one")
    val second = tmp.newFolder("two")
    val firstName = WorkspaceIdentity.databaseName(first.path)
    val secondName = WorkspaceIdentity.databaseName(second.path)

    assertNotEquals(firstName, secondName)
    assertTrue(firstName.endsWith(".sqlite"))
    assertTrue(firstName.startsWith("pi-native-"))
    assertEquals(firstName, WorkspaceIdentity.databaseName(first.canonicalPath))
  }

  @Test fun anUnboundHostKeepsTheLegacyDatabaseName() {
    assertEquals(WorkspaceIdentity.LEGACY_DATABASE, WorkspaceIdentity.databaseName(null))
    assertEquals(WorkspaceIdentity.LEGACY_DATABASE, WorkspaceIdentity.databaseName(""))
  }

  @Test fun theLegacyDatabaseAndItsSidecarsMoveToTheFirstWorkspaceOpened() {
    val databases = tmp.newFolder("databases")
    val legacy = File(databases, WorkspaceIdentity.LEGACY_DATABASE).apply { writeText("sessions") }
    val wal = File(databases, WorkspaceIdentity.LEGACY_DATABASE + "-wal").apply { writeText("pages") }
    val shm = File(databases, WorkspaceIdentity.LEGACY_DATABASE + "-shm").apply { writeText("index") }
    val target = WorkspaceIdentity.databaseName(tmp.newFolder("workspace").path)

    assertTrue(WorkspaceIdentity.migrateLegacyDatabase(databases, target))

    assertFalse(legacy.exists())
    assertFalse(wal.exists())
    assertFalse(shm.exists())
    assertEquals("sessions", File(databases, target).readText())
    assertEquals("pages", File(databases, target + "-wal").readText())
    assertEquals("index", File(databases, target + "-shm").readText())
  }

  @Test fun anExistingWorkspaceDatabaseIsNeverOverwrittenByTheLegacyStore() {
    val databases = tmp.newFolder("databases")
    val target = WorkspaceIdentity.databaseName(tmp.newFolder("workspace").path)
    File(databases, WorkspaceIdentity.LEGACY_DATABASE).writeText("old shared store")
    File(databases, target).writeText("this workspace's own store")

    assertFalse(WorkspaceIdentity.migrateLegacyDatabase(databases, target))

    assertEquals("old shared store", File(databases, WorkspaceIdentity.LEGACY_DATABASE).readText())
    assertEquals("this workspace's own store", File(databases, target).readText())
  }

  @Test fun migratingTheLegacyNameItselfIsANoOp() {
    val databases = tmp.newFolder("databases")
    File(databases, WorkspaceIdentity.LEGACY_DATABASE).writeText("sessions")

    assertFalse(WorkspaceIdentity.migrateLegacyDatabase(databases, WorkspaceIdentity.LEGACY_DATABASE))
    assertEquals("sessions", File(databases, WorkspaceIdentity.LEGACY_DATABASE).readText())
  }

  @Test fun migrationWithoutALegacyDatabaseDoesNothing() {
    val databases = tmp.newFolder("databases")
    val target = WorkspaceIdentity.databaseName(tmp.newFolder("workspace").path)

    assertFalse(WorkspaceIdentity.migrateLegacyDatabase(databases, target))
    assertFalse(File(databases, target).exists())
  }
}
