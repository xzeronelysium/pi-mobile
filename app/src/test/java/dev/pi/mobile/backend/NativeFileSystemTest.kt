package dev.pi.mobile.backend

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeFileSystemTest {
    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var storage: NativeFileSystem

    @Before fun setUp() {
        val tmp = System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")
        root = Files.createTempDirectory(File(tmp).toPath(), "native-fs-").toFile()
        workspace = File(root, "workspace").apply { mkdirs() }
        storage = NativeFileSystem(workspace, root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun codeOf(block: () -> Unit): String = try {
        block()
        fail("expected a NativeFailure")
        "none"
    } catch (error: NativeFailure) {
        error.code
    }

    private fun write(path: String, text: String) =
        storage.dispatch("file.write", JSONObject().put("path", path).put("text", text))

    private fun text(path: String): String {
        val value = storage.dispatch("file.readRange", JSONObject().put("path", path).put("offset", 0).put("length", 1 shl 20)) as JSONObject
        return String(BridgeBase64.decode(value.getString("base64")), Charsets.UTF_8)
    }

    @Test fun writeCreatesMissingParentsAndSurvivesAppendAndTruncate() {
        write("notes/deep/todo.md", "buy milk")
        assertEquals("buy milk", text("notes/deep/todo.md"))

        storage.dispatch("file.write", JSONObject().put("path", "notes/deep/todo.md").put("text", " and eggs").put("append", true))
        assertEquals("buy milk and eggs", text("notes/deep/todo.md"))

        storage.dispatch("file.write", JSONObject().put("path", "notes/deep/todo.md").put("truncateTo", 8))
        assertEquals("buy milk", text("notes/deep/todo.md"))
    }

    @Test fun binaryRoundTripsThroughBase64() {
        val bytes = ByteArray(256) { it.toByte() }
        storage.dispatch("file.write", JSONObject().put("path", "blob.bin").put("base64", BridgeBase64.encode(bytes)))
        val value = storage.dispatch("file.readRange", JSONObject().put("path", "blob.bin").put("offset", 0).put("length", 1024)) as JSONObject
        assertArrayEquals(bytes, BridgeBase64.decode(value.getString("base64")))
    }

    @Test fun binaryReaderKeepsReadingTheFileItOpenedAfterARename() {
        write("a.txt", "one")
        val handle = storage.dispatch("file.open", JSONObject().put("path", "a.txt")) as JSONObject
        val id = handle.getLong("id")
        assertEquals(3L, handle.getLong("size"))

        storage.dispatch("file.rename", JSONObject().put("source", "a.txt").put("destination", "b.txt"))
        write("a.txt", "two")

        val read = storage.dispatch("file.readAt", JSONObject().put("id", id).put("offset", 0).put("length", 10)) as JSONObject
        assertEquals("one", String(BridgeBase64.decode(read.getString("base64")), Charsets.UTF_8))

        storage.dispatch("file.close", JSONObject().put("id", id))
        storage.dispatch("file.close", JSONObject().put("id", id))
        assertEquals("invalid", codeOf {
            storage.dispatch("file.readAt", JSONObject().put("id", id).put("offset", 0).put("length", 1))
        })
    }

    @Test fun openRejectsDirectoriesMissingFilesAndInvalidRanges() {
        storage.dispatch("file.mkdir", JSONObject().put("path", "dir"))
        write("file.txt", "x")

        assertEquals("is_directory", codeOf { storage.dispatch("file.open", JSONObject().put("path", "dir")) })
        assertEquals("not_found", codeOf { storage.dispatch("file.open", JSONObject().put("path", "missing.txt")) })

        val handle = storage.dispatch("file.open", JSONObject().put("path", "file.txt")) as JSONObject
        val id = handle.getLong("id")
        assertEquals("invalid", codeOf {
            storage.dispatch("file.readAt", JSONObject().put("id", id).put("offset", -1).put("length", 1))
        })
        assertEquals("invalid", codeOf {
            storage.dispatch("file.readRange", JSONObject().put("path", "file.txt").put("offset", 0).put("length", -1))
        })
        // A read past end of file is empty, not an error.
        val past = storage.dispatch("file.readAt", JSONObject().put("id", id).put("offset", 50).put("length", 4)) as JSONObject
        assertEquals("", past.getString("base64"))
    }

    @Test fun noFollowRefusesOnlyTheFinalSymlink() {
        write("target.txt", "target")
        write("sub/inner.txt", "inner")
        Files.createSymbolicLink(File(workspace, "link.txt").toPath(), File(workspace, "target.txt").toPath())
        Files.createSymbolicLink(File(workspace, "dirlink").toPath(), File(workspace, "sub").toPath())

        assertEquals("invalid", codeOf {
            storage.dispatch("file.open", JSONObject().put("path", "link.txt").put("noFollow", true))
        })
        // Following is the default, and only the final component is refused.
        val followed = storage.dispatch("file.open", JSONObject().put("path", "link.txt")) as JSONObject
        assertEquals("target", String(BridgeBase64.decode(
            (storage.dispatch("file.readAt", JSONObject().put("id", followed.getLong("id")).put("offset", 0).put("length", 10)) as JSONObject).getString("base64"),
        ), Charsets.UTF_8))

        val inner = storage.dispatch("file.open", JSONObject().put("path", "dirlink/inner.txt").put("noFollow", true)) as JSONObject
        assertEquals("inner", String(BridgeBase64.decode(
            (storage.dispatch("file.readAt", JSONObject().put("id", inner.getLong("id")).put("offset", 0).put("length", 10)) as JSONObject).getString("base64"),
        ), Charsets.UTF_8))
    }

    @Test fun listAndStatReportKindsAndMissingPaths() {
        write("a.txt", "hello")
        storage.dispatch("file.mkdir", JSONObject().put("path", "sub"))
        Files.createSymbolicLink(File(workspace, "link").toPath(), File(workspace, "a.txt").toPath())

        val entries = (storage.dispatch("file.list", JSONObject().put("path", ".")) as JSONObject).getJSONArray("entries")
        val byName = (0 until entries.length()).associate { entries.getJSONObject(it).getString("name") to entries.getJSONObject(it) }
        assertEquals("file", byName.getValue("a.txt").getString("kind"))
        assertEquals(5L, byName.getValue("a.txt").getLong("size"))
        assertEquals("directory", byName.getValue("sub").getString("kind"))
        assertEquals("symlink", byName.getValue("link").getString("kind"))

        val missing = storage.dispatch("file.stat", JSONObject().put("path", "nope")) as JSONObject
        assertFalse(missing.getBoolean("exists"))
        // Following a symlink reports the target's kind.
        assertEquals("file", (storage.dispatch("file.stat", JSONObject().put("path", "link")) as JSONObject).getString("kind"))
        assertEquals("symlink", (storage.dispatch("file.stat", JSONObject().put("path", "link").put("follow", false)) as JSONObject).getString("kind"))

        assertEquals("not_found", codeOf { storage.dispatch("file.list", JSONObject().put("path", "nope")) })
        assertEquals("not_directory", codeOf { storage.dispatch("file.list", JSONObject().put("path", "a.txt")) })
    }

    @Test fun mkdirRemoveRenameCanonicalAndTempsBehave() {
        storage.dispatch("file.mkdir", JSONObject().put("path", "a/b/c").put("recursive", true))
        assertTrue(File(workspace, "a/b/c").isDirectory)

        write("a/b/c/x.md", "x")
        storage.dispatch("file.rename", JSONObject().put("source", "a/b/c/x.md").put("destination", "a/b/y.md"))
        assertFalse(File(workspace, "a/b/c/x.md").exists())
        assertTrue(File(workspace, "a/b/y.md").isFile)

        val canonical = storage.dispatch("file.canonical", JSONObject().put("path", "a/b/../b/y.md")) as JSONObject
        assertEquals(File(workspace, "a/b/y.md").canonicalPath, canonical.getString("path"))

        storage.dispatch("file.remove", JSONObject().put("path", "a").put("recursive", true))
        assertFalse(File(workspace, "a").exists())

        assertEquals("not_found", codeOf { storage.dispatch("file.remove", JSONObject().put("path", "a")) })
        storage.dispatch("file.remove", JSONObject().put("path", "a").put("force", true))

        val tempDir = storage.dispatch("file.tempDir", JSONObject().put("prefix", "pi-")) as JSONObject
        assertTrue(File(tempDir.getString("path")).isDirectory)
        val tempFile = storage.dispatch("file.tempFile", JSONObject().put("prefix", "pi-").put("suffix", ".txt")) as JSONObject
        assertTrue(File(tempFile.getString("path")).isFile)
    }

    @Test fun pathsCannotEscapeTheWorkspace() {
        write("inside.txt", "ok")
        assertEquals("permission_denied", codeOf { storage.dispatch("file.stat", JSONObject().put("path", "../outside.txt")) })
        assertEquals("permission_denied", codeOf { storage.dispatch("file.open", JSONObject().put("path", "../outside.txt")) })
        assertEquals("permission_denied", codeOf { write("../outside.txt", "no") })
        assertEquals("permission_denied", codeOf {
            storage.dispatch("file.list", JSONObject().put("path", ".."))
        })
    }

    @Test fun cleanupClosesEveryHandle() {
        write("x.txt", "x")
        val handle = storage.dispatch("file.open", JSONObject().put("path", "x.txt")) as JSONObject
        storage.dispatch("file.cleanup", JSONObject())
        assertEquals("invalid", codeOf {
            storage.dispatch("file.readAt", JSONObject().put("id", handle.getLong("id")).put("offset", 0).put("length", 1))
        })
    }
}
