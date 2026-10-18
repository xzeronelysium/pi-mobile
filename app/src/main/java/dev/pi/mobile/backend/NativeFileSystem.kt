package dev.pi.mobile.backend

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Workspace-confined filesystem primitives for Pi Durable's portable `ExecutionEnv`.
 *
 * This is the Kotlin half of the contract specified by `runtime/src/env.mjs` and exercised
 * by Pi Durable's own `createEnvConformance` suite. Semantics that matter:
 *
 * - [root] is a working directory, NOT a security sandbox beyond path confinement: the
 *   process runs with app privileges either way.
 * - `file.write` creates missing parent directories.
 * - Binary reads go through real open handles, so a reader keeps seeing the file it opened
 *   even after that path is renamed or replaced.
 * - `noFollow` refuses a symbolic link only in the final path component.
 * - Invalid arguments are contract, not accidents: negative offsets, non-integer lengths,
 *   and use after close are `invalid`.
 *
 * minSdk is 24, so this avoids `java.nio.file` and `java.util.Base64` (both API 26+).
 */
class NativeFileSystem(private val root: File, private val tempRoot: File = root) {
    private class Handle(val file: RandomAccessFile, val info: JSONObject)

    private val handles = ConcurrentHashMap<Long, Handle>()
    private val nextHandle = AtomicLong(1)

    init {
        root.mkdirs()
        tempRoot.mkdirs()
    }

    fun dispatch(operation: String, args: JSONObject): Any? = when (operation) {
        "file.stat" -> stat(args.getString("path"), args.optBoolean("follow", true))
        "file.open" -> open(args.getString("path"), args.optBoolean("noFollow", false))
        "file.readAt" -> readAt(args.getLong("id"), args.getLong("offset"), args.getLong("length"))
        "file.close" -> { closeHandle(args.getLong("id")); null }
        "file.readRange" -> readRange(args.getString("path"), args.getLong("offset"), args.getLong("length"))
        "file.write" -> { write(args); null }
        "file.list" -> list(args.getString("path"))
        "file.mkdir" -> { mkdir(args.getString("path"), args.optBoolean("recursive", false)); null }
        "file.remove" -> { remove(args.getString("path"), args.optBoolean("recursive", false), args.optBoolean("force", false)); null }
        "file.rename" -> { rename(args.getString("source"), args.getString("destination")); null }
        "file.canonical" -> JSONObject().put("path", confined(args.getString("path")).path)
        "file.tempDir" -> tempDir(args.optString("prefix", "pi-"))
        "file.tempFile" -> tempFile(args.optString("prefix", "pi-"), args.optString("suffix", ""))
        "file.flush" -> { flush(args.getString("path")); null }
        "file.cleanup" -> { closeAll(); null }
        else -> throw NativeFailure("not_supported", "Unsupported file operation: $operation")
    }

    fun closeAll() {
        for (id in handles.keys.toList()) closeHandle(id)
    }

    // ----- paths -----------------------------------------------------------

    /** The lexical (unresolved) target, used before following symlinks. */
    private fun lexical(path: String): File = File(root, path.removePrefix("/"))

    /**
     * Resolve [path] and require it to stay inside [root].
     *
     * `canonicalFile` resolves symlinks, so a link pointing outside the workspace is
     * rejected here rather than silently read or written.
     */
    private fun confined(path: String): File {
        val rootCanonical = root.canonicalFile
        val target = lexical(path).canonicalFile
        if (target.path != rootCanonical.path && !target.path.startsWith(rootCanonical.path + File.separator)) {
            throw NativeFailure("permission_denied", "Path escapes workspace: $path")
        }
        return target
    }

    /** Whether the final path component is a symbolic link, regardless of the root's own path. */
    private fun isSymlink(file: File): Boolean {
        val parent = file.parentFile ?: return false
        val resolvedParent = parent.canonicalFile
        val resolvedChild = file.canonicalFile
        return resolvedChild.path != File(resolvedParent, file.name).path
    }

    /**
     * The final component without following it, confined by its parent.
     *
     * Used where a symbolic link must be observed as itself (`follow: false`, `noFollow`),
     * which [confined] would erase by resolving it.
     */
    private fun constrainedLexical(path: String): File {
        val file = lexical(path)
        val rootCanonical = root.canonicalFile
        val parent = file.parentFile?.canonicalFile ?: rootCanonical
        if (parent.path != rootCanonical.path && !parent.path.startsWith(rootCanonical.path + File.separator)) {
            throw NativeFailure("permission_denied", "Path escapes workspace: $path")
        }
        return File(parent, file.name)
    }

    private fun kindOf(file: File): String = when {
        isSymlink(file) -> "symlink"
        file.isDirectory -> "directory"
        file.isFile -> "file"
        else -> "other"
    }

    private fun infoOf(file: File, follow: Boolean): JSONObject {
        if (follow) {
            val target = runCatching { file.canonicalFile }.getOrDefault(file)
            if (!target.exists()) return JSONObject().put("exists", false)
            val directory = target.isDirectory
            return JSONObject()
                .put("exists", true)
                .put("kind", if (directory) "directory" else "file")
                .put("size", if (directory) 0L else target.length())
                .put("mtimeMs", target.lastModified())
        }
        if (isSymlink(file)) {
            return JSONObject().put("exists", true).put("kind", "symlink")
                .put("size", file.length()).put("mtimeMs", file.lastModified())
        }
        if (!file.exists()) return JSONObject().put("exists", false)
        val directory = file.isDirectory
        return JSONObject()
            .put("exists", true)
            .put("kind", if (directory) "directory" else "file")
            .put("size", if (directory) 0L else file.length())
            .put("mtimeMs", file.lastModified())
    }

    // ----- operations ------------------------------------------------------

    private fun stat(path: String, follow: Boolean): JSONObject =
        if (follow) infoOf(confined(path), true) else infoOf(constrainedLexical(path), false)

    private fun open(path: String, noFollow: Boolean): JSONObject {
        val file = lexical(path)
        if (noFollow && isSymlink(file)) throw NativeFailure("invalid", "Symbolic link: $path")
        val target = confined(path)
        if (!target.exists()) throw NativeFailure("not_found", "No such file: $path")
        if (target.isDirectory) throw NativeFailure("is_directory", "Is a directory: $path")
        if (!target.isFile) throw NativeFailure("invalid", "Not a regular file: $path")
        val handle = try {
            RandomAccessFile(target, "r")
        } catch (error: IOException) {
            throw NativeFailure("permission_denied", error.message ?: "Cannot open $path")
        }
        val id = nextHandle.getAndIncrement()
        val info = JSONObject()
            .put("id", id)
            .put("kind", "file")
            .put("size", target.length())
            .put("mtimeMs", target.lastModified())
        handles[id] = Handle(handle, info)
        return info
    }

    private fun closeHandle(id: Long) {
        handles.remove(id)?.let { runCatching { it.file.close() } }
    }

    private fun readAt(id: Long, offset: Long, length: Long): JSONObject {
        if (offset < 0 || length < 0) throw NativeFailure("invalid", "Invalid read range")
        val handle = handles[id] ?: throw NativeFailure("invalid", "Reader closed")
        if (length == 0L) return JSONObject().put("base64", "")
        return JSONObject().put("base64", BridgeBase64.encode(readFully(handle.file, offset, length)))
    }

    private fun readRange(path: String, offset: Long, length: Long): JSONObject {
        if (offset < 0 || length < 0) throw NativeFailure("invalid", "Invalid read range")
        val target = confined(path)
        if (!target.exists()) throw NativeFailure("not_found", "No such file: $path")
        if (target.isDirectory) throw NativeFailure("is_directory", "Is a directory: $path")
        RandomAccessFile(target, "r").use { file ->
            if (length == 0L) return JSONObject().put("base64", "")
            return JSONObject().put("base64", BridgeBase64.encode(readFully(file, offset, length)))
        }
    }

    /** Up to [length] bytes at [offset]; fewer only at end of file. */
    private fun readFully(file: RandomAccessFile, offset: Long, length: Long): ByteArray {
        file.seek(offset)
        val available = (file.length() - offset).coerceAtLeast(0L)
        val size = minOf(length, available).toInt()
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = file.read(bytes, read, size - read)
            if (count < 0) break
            read += count
        }
        return if (read == size) bytes else bytes.copyOf(read)
    }

    private fun write(args: JSONObject) {
        val target = confined(args.getString("path"))
        if (args.has("truncateTo")) {
            val size = args.getLong("truncateTo")
            if (size < 0) throw NativeFailure("invalid", "Invalid truncate size")
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { it.setLength(size) }
            return
        }
        val bytes = when {
            args.has("base64") -> BridgeBase64.decode(args.getString("base64"))
            else -> args.optString("text", "").toByteArray(Charsets.UTF_8)
        }
        // The WebView bridge rejects messages over 2 MB before they arrive; keep writes
        // comfortably below that rather than truncating silently.
        if (bytes.size > MAX_WRITE_BYTES) {
            throw NativeFailure("not_supported", "Write exceeds ${MAX_WRITE_BYTES} bytes")
        }
        target.parentFile?.mkdirs()
        if (args.optBoolean("append", false)) target.appendBytes(bytes) else target.writeBytes(bytes)
    }

    private fun list(path: String): JSONObject {
        val lexicalTarget = constrainedLexical(path)
        if (!lexicalTarget.exists() && !isSymlink(lexicalTarget)) throw NativeFailure("not_found", "No such file: $path")
        val confinedTarget = confined(path)
        if (confinedTarget.isFile) throw NativeFailure("not_directory", "Not a directory: $path")
        val entries = JSONArray()
        val children = confinedTarget.listFiles() ?: return JSONObject().put("entries", entries)
        for (child in children) {
            entries.put(
                JSONObject()
                    .put("name", child.name)
                    .put("kind", kindOf(child))
                    .put("size", if (child.isDirectory) 0L else child.length())
                    .put("mtimeMs", child.lastModified()),
            )
        }
        return JSONObject().put("entries", entries)
    }

    private fun mkdir(path: String, recursive: Boolean) {
        val target = confined(path)
        if (target.isDirectory) return
        val created = if (recursive) target.mkdirs() else target.mkdir()
        if (!created && !target.isDirectory) throw NativeFailure("permission_denied", "Cannot create directory: $path")
    }

    private fun remove(path: String, recursive: Boolean, force: Boolean) {
        val target = confined(path)
        if (!target.exists()) {
            if (force) return
            throw NativeFailure("not_found", "No such file: $path")
        }
        if (target.isDirectory && !recursive) {
            throw NativeFailure("is_directory", "Directory without recursive: $path")
        }
        if (!target.deleteRecursively() && target.exists()) {
            throw NativeFailure("permission_denied", "Cannot remove: $path")
        }
    }

    private fun rename(source: String, destination: String) {
        val from = confined(source)
        val to = confined(destination)
        if (!from.exists()) throw NativeFailure("not_found", "No such file: $source")
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw NativeFailure("unknown", "Cannot rename $source to $destination")
    }

    private fun tempDir(prefix: String): JSONObject {
        val dir = File(tempRoot, "$prefix${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw NativeFailure("unknown", "Cannot create temporary directory")
        return JSONObject().put("path", dir.path)
    }

    private fun tempFile(prefix: String, suffix: String): JSONObject {
        val file = File.createTempFile(prefix, suffix, tempRoot)
        return JSONObject().put("path", file.path)
    }

    private fun flush(path: String) {
        val target = confined(path)
        if (!target.exists()) throw NativeFailure("not_found", "No such file: $path")
        RandomAccessFile(target, "rw").use { it.fd.sync() }
    }

    private companion object {
        const val MAX_WRITE_BYTES = 1_000_000
    }
}

/**
 * Minimal base64 for the bridge payloads. `java.util.Base64` needs API 26 but minSdk is 24,
 * and `android.util.Base64` is unavailable to JVM unit tests.
 */
internal object BridgeBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index + 2 < bytes.size) {
            val chunk = (bytes[index].toInt() and 0xFF shl 16) or
                (bytes[index + 1].toInt() and 0xFF shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            builder.append(ALPHABET[chunk ushr 18])
            builder.append(ALPHABET[chunk ushr 12 and 0x3F])
            builder.append(ALPHABET[chunk ushr 6 and 0x3F])
            builder.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        val remaining = bytes.size - index
        if (remaining == 1) {
            val chunk = bytes[index].toInt() and 0xFF shl 16
            builder.append(ALPHABET[chunk ushr 18])
            builder.append(ALPHABET[chunk ushr 12 and 0x3F])
            builder.append("==")
        } else if (remaining == 2) {
            val chunk = (bytes[index].toInt() and 0xFF shl 16) or (bytes[index + 1].toInt() and 0xFF shl 8)
            builder.append(ALPHABET[chunk ushr 18])
            builder.append(ALPHABET[chunk ushr 12 and 0x3F])
            builder.append(ALPHABET[chunk ushr 6 and 0x3F])
            builder.append('=')
        }
        return builder.toString()
    }

    fun decode(text: String): ByteArray {
        val cleaned = text.filter { !it.isWhitespace() && it != '=' }
        if (cleaned.isEmpty()) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        for (character in cleaned) {
            val value = ALPHABET.indexOf(character)
            if (value < 0) throw NativeFailure("invalid", "Invalid base64 payload")
            accumulator = accumulator shl 6 or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write(accumulator ushr bits and 0xFF)
            }
        }
        return output.toByteArray()
    }
}
