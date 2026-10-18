package dev.pi.mobile.backend

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Routes the durable environment's per-call working directory onto a real adapter root.
 *
 * Pi Durable sends the cwd it wants on every `shell.exec` (`runtime/src/env.mjs` resolves it
 * against the conversation's agent cwd), and every file path it sends is already absolute.
 * The bridge originally ignored both and ran everything under the hardcoded
 * `filesDir/workspace`, so changing the workspace in Settings never changed where tools ran.
 *
 * The allowed roots are the configured workspace ([workspacePath] when non-blank) plus the
 * app-private fallback. While the user has not granted All-files access that pair is the whole
 * reach. Once it is granted, [broadRoots] — the shared storage the grant actually covers, plus the
 * app's own external/files/cache/no_backup dirs — joins them, so a granted permission is used
 * instead of leaving the agent jailed to one folder. The grant is read when it is used, so the
 * reach follows the permission without the runtime being rebuilt. The workspace stays first and is
 * the cwd/HOME; the app-private fallback is always present so the list can never be empty; a
 * candidate that is missing, not a directory, or unreadable is dropped so one unusable root cannot
 * poison the list.
 *
 * A cwd or path that resolves outside every allowed root fails with a coded `permission_denied`
 * instead of quietly running somewhere else. The app-private fallback stays allowed so an agent
 * can still reach its own HOME/storage while a project folder is bound. Widening the root list must
 * never loosen this: the resolver is unchanged, only the roots it checks are.
 *
 * One [NativeShell]/[NativeFileSystem] pair is cached per canonical root; adapters are built
 * lazily on the caller's thread (the host's io worker). [NativeShell] and [NativeFileSystem]
 * keep their own contracts untouched — the router only selects and feeds their root.
 */
class NativeIoRouter(
  fallbackRoot: File,
  workspacePath: String?,
  private val tempRoot: File,
  private val shellExecutable: String = "/system/bin/sh",
  isAllFilesAccess: () -> Boolean = { false },
  broadRoots: List<File> = emptyList(),
  private val log: (String) -> Unit = {},
) {
  /** The All-files grant, read live (see [widenedRoots]): it must be a property, not a construction-time value. */
  private val isAllFilesAccess: () -> Boolean = isAllFilesAccess
  /** The candidates a granted All-files permission unlocks, filtered each time the grant is read. */
  private val broadRoots: List<File> = broadRoots
  /** A path routed onto [root]; [path] is that root's own relative form, not an absolute path. */
  data class Routed(val root: File, val path: String)

  val fallback: File = canonicalOrSelf(fallbackRoot)

  private val workspace: File? =
    workspacePath?.trim()?.takeIf { it.isNotEmpty() }?.let { canonicalOrSelf(File(it)) }

  /**
   * The roots a granted All-files permission unlocks, read live.
   *
   * Only readable directories survive: a missing, non-directory, or unreadable candidate is skipped
   * rather than poisoning the list. Never holds the workspace or the fallback — those are structural
   * and always kept.
   *
   * The grant is *read when it is used*, not snapshotted when the runtime was built. It used to be a
   * `val` taken at construction, which meant the user could grant All-files access, see the app
   * report the grant on resume, and still have every tool call refused until something unrelated
   * rebuilt the runtime — the reach now follows the permission on the next call, and back again when
   * it is revoked. One `io.roots` line is emitted per change (never per read), so the flip is visible
   * in logcat.
   */
  @Volatile private var widenedFor: Boolean? = null
  @Volatile private var widenedSnapshot: List<File> = emptyList()

  private val widenedRoots: List<File>
    get() {
      val granted = isAllFilesAccess()
      if (widenedFor == granted) return widenedSnapshot
      val computed = if (granted) broadRoots.mapNotNull { usableRoot(it) }.distinctBy { it.path } else emptyList()
      val previous = widenedFor
      // Publish before logging: [rootsLine] reads this very value, and the line must state the new
      // reach rather than re-enter this computation.
      widenedFor = granted
      widenedSnapshot = computed
      if (previous != null) log(rootsLine())
      return computed
    }

  /**
   * Every directory an agent call may run in. The workspace (when it is a usable directory) stays
   * first, so widening the reach can never move the cwd/HOME onto shared storage; the app-private
   * fallback is always present, so the list is never empty.
   */
  val allowedRoots: List<File>
    get() = (listOfNotNull(workspace?.let { usableRoot(it) }, fallback) + widenedRoots).distinctBy { it.path }

  /** Where a call with no cwd goes: the configured workspace, else the app-private fallback. */
  val defaultRoot: File get() = allowedRoots.first()

  /** The app-private temp dir, canonicalised once. A file root, never a cwd root. */
  private val temp: File = canonicalOrSelf(tempRoot)

  /**
   * Directories a file path may live in: the roots above plus the app-private temp dir.
   *
   * The temp dir is a file root because the `shell` tool spills output over its size limit
   * into a temp file and reports that absolute path as the full-output location; a call that
   * writes or re-reads it must reach the real file. It is deliberately NOT a cwd root.
   */
  private val fileRoots: List<File> get() = (allowedRoots + temp).distinctBy { it.path }

  private val shells = ConcurrentHashMap<String, NativeShell>()
  private val fileSystems = ConcurrentHashMap<String, NativeFileSystem>()

  init {
    // One grepable line, on the shared console tag ([NativeRuntimeHost] installs the logcat sink):
    // `adb logcat -s PiRuntimeConsole | grep -F 'io.roots'` answers "what can the agent reach".
    log(rootsLine())
  }

  /**
   * The canonical directory for [requested]. Blank means "whatever the workspace is"; a directory
   * that escapes every allowed root is refused rather than silently redirected.
   */
  fun resolveCwd(requested: String?): File {
    val raw = requested?.trim().orEmpty()
    if (raw.isEmpty()) return defaultRoot
    val canonical = runCatching { File(raw).canonicalFile }.getOrElse {
      throw NativeFailure("permission_denied", "Working directory is not a usable path: $raw")
    }
    if (allowedRoots.none { isUnder(canonical, it) }) {
      throw NativeFailure("permission_denied", "Working directory is outside the allowed workspace: $raw")
    }
    return canonical
  }

  /**
   * The environment one `shell.exec` runs with.
   *
   * Kept here, beside the roots a call is routed through, so there is exactly one answer to "where
   * does this command run, and which directory may it write a temp file into and read it back from"
   * instead of a copy on the host that has to be kept in step with the roots by hand.
   *
   * `HOME` is the directory the command really runs in — the same cwd [resolveCwd] hands the shell —
   * so a shell that expands `~` cannot land outside the allowed roots, and the two can never
   * disagree. `TMPDIR` is the router's own temp root, the one directory the file ops allow as a file
   * root: a path the shell was told to write into is therefore readable back through `file.read` /
   * `file.stat` rather than refused as `permission_denied`. `PATH` is the directory the interpreter
   * lives in. [extra] pairs are applied last, so a caller asking for a specific variable wins — that
   * is the caller's own choice of directory, checked by the file ops like any other path.
   */
  fun shellEnvironment(requestedCwd: String?, extra: JSONObject? = null): Map<String, String> {
    val cwd = resolveCwd(requestedCwd)
    val environment = linkedMapOf(
      "PATH" to (File(shellExecutable).parent ?: "/system/bin"),
      "HOME" to cwd.path,
      "TMPDIR" to temp.path,
    )
    extra?.keys()?.forEach { environment[it] = extra.getString(it) }
    return environment
  }

  /** The shell for [requestedCwd], built once per canonical directory. */
  fun shell(requestedCwd: String?): NativeShell {
    val root = resolveCwd(requestedCwd)
    return shells.computeIfAbsent(root.path) { NativeShell(File(it), shellExecutable) }
  }

  /** The filesystem confined to [root], built once per canonical directory. */
  fun files(root: File): NativeFileSystem {
    val canonical = root.canonicalFile
    return fileSystems.computeIfAbsent(canonical.path) { NativeFileSystem(File(it), temp) }
  }

  /**
   * The reach this router actually grants agent calls, in the shape the capability report states it.
   *
   * This is the router's own answer — [allowedRoots], the file-only temp root and the default cwd —
   * never a second derivation of the same paths: [rootsLine] renders this very value, so the
   * `io.roots` line an inspection greps and the report an agent reads cannot drift apart. Handed to
   * the capability probe as its `reachSource`, the report then states the router that is really in
   * use, All-files widening included and the workspace pair while the grant is absent.
   */
  fun reach(): CapabilityReport.Reach = CapabilityReport.Reach(
    observed = true,
    shellRoots = allowedRoots.map { it.path },
    fileRoots = fileRoots.map { it.path },
    defaultRoot = defaultRoot.path,
    fallbackRoot = fallback.path,
    tempRoot = temp.path,
    source = REACH_SOURCE,
    failure = null,
    // The router confines file paths and the shell's starting cwd. The shell itself runs as the
    // app's UID and is not confined to these roots; the report says so explicitly.
    shellConfined = false,
  )

  /**
   * Route a file path onto its allowed root.
   *
   * A relative path keeps the historical "relative to the workspace" reading. An absolute path
   * is resolved for real (like the Node bridge's `confined`) and rebased onto the allowed root
   * that contains it, so [NativeFileSystem]'s own confinement and symlink resolution still
   * decide whether the final target is legal.
   */
  fun confine(path: String): Routed {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("/")) return Routed(defaultRoot, path)
    val lexical = File(trimmed)
    val canonical = runCatching { lexical.canonicalFile }.getOrElse {
      throw NativeFailure("permission_denied", "Path is not usable: $path")
    }
    // Prefer the parent so a final symlink is still seen as itself (`noFollow`, `follow: false`).
    val parent = lexical.parentFile?.canonicalFile
    val root = (parent?.let(::rootContaining)) ?: rootContaining(canonical)
      ?: throw NativeFailure("permission_denied", "Path is outside the allowed workspace: $path")
    val relative = when {
      canonical.path == root.path -> ""
      parent != null && isUnder(parent, root) -> File(parent.path.removePrefix(root.path), lexical.name).path
      else -> canonical.path.removePrefix(root.path)
    }
    // `File("", name)` resolves to "/name" on a POSIX JVM; the contract is the root's own
    // relative form, which never starts with a separator.
    return Routed(root, relative.trimStart('/'))
  }

  /** Release every cached reader. Safe to call repeatedly. */
  fun close() {
    fileSystems.values.forEach { runCatching { it.closeAll() } }
    fileSystems.clear()
    shells.clear()
  }

  /** The innermost file root containing [directory], so a nested root is not shadowed. */
  private fun rootContaining(directory: File): File? =
    fileRoots.filter { isUnder(directory, it) }.maxByOrNull { it.path.length }

  /** The one line the console tag carries: the effective roots and whether the grant widened them. */
  private fun rootsLine(): String {
    // Rendered from the same [reach] the report states, so the grep line and the agent's report
    // cannot disagree about what the agent can touch.
    val reach = reach()
    return "$ROOTS_PREFIX allFiles=${isAllFilesAccess()} widened=${widenedRoots.isNotEmpty()} " +
      "cwd=${reach.defaultRoot} cwdRoots=[${reach.shellRoots.joinToString(",")}] " +
      "fileRoots=[${reach.fileRoots.joinToString(",")}]"
  }

  /** A canonical directory that exists and is readable, else null: an unusable root is skipped. */
  private fun usableRoot(candidate: File): File? = runCatching {
    canonicalOrSelf(candidate).takeIf { it.isDirectory && it.canRead() }
  }.getOrNull()

  /** [File.canonicalFile], falling back to the absolute path so an odd path cannot break the list. */
  private fun canonicalOrSelf(file: File): File =
    runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

  private fun isUnder(child: File, root: File): Boolean {
    val resolved = child.canonicalFile
    val base = root.canonicalFile
    return resolved.path == base.path || resolved.path.startsWith(base.path + File.separator)
  }

  companion object {
    /** `adb logcat -s PiRuntimeConsole | grep -F 'io.roots'` is the whole root-discovery filter. */
    const val ROOTS_PREFIX = "io.roots"

    /** Stated in the capability report so a reader knows the roots are the live router's own. */
    const val REACH_SOURCE = "NativeIoRouter.allowedRoots, read from the live router this app built " +
      "for the session (bound workspace + app-private fallback, plus the shared storage the All-files " +
      "grant covers only while it is held) — the same roots the `io.roots` line states. These are the " +
      "file tools' roots and the shell's starting-cwd roots; the shell itself is app-privileged and " +
      "is not confined to them."
  }
}
