package dev.pi.mobile.backend

import java.io.File

/**
 * Resolves the agent workspace to a real filesystem directory.
 *
 * Modes:
 * - APP_PRIVATE: `filesDir/workspace` (default, no permission)
 * - APP_EXTERNAL: `externalFilesDir/workspace` (no permission; visible in Files)
 * - CUSTOM: user-chosen absolute path under app dirs, or under shared storage when
 *   All files access (MANAGE_EXTERNAL_STORAGE) is granted
 * - SAF_MIRROR: SAF tree could not be turned into a stable real path; cwd is an
 *   app-private mirror folder and [Resolved.safUri] holds the persisted tree URI
 *
 * Node/Pi require a real path for cwd. SAF content URIs alone are not a cwd.
 */
class WorkspaceBinding(
  private val filesDir: File,
  private val externalFilesDir: File?,
  private val sharedStorageRoot: File?,
  private val isAllFilesAccess: () -> Boolean,
  private val load: () -> Stored,
  private val save: (Stored) -> Unit
) {
  enum class Kind { APP_PRIVATE, APP_EXTERNAL, CUSTOM, SAF_MIRROR }

  data class Resolved(
    val root: File,
    val kind: Kind,
    val label: String,
    val safUri: String? = null
  )

  sealed class Stored {
    data object Default : Stored()
    data object External : Stored()
    data class Custom(val path: String, val safUri: String?, val label: String) : Stored()
  }

  fun resolve(): Resolved {
    when (val stored = load()) {
      is Stored.Default -> return privateRoot().let {
        Resolved(it, Kind.APP_PRIVATE, "App private", null)
      }
      is Stored.External -> {
        val external = externalRoot()
        return if (external != null) Resolved(external, Kind.APP_EXTERNAL, "App external", null)
        else privateRoot().let { Resolved(it, Kind.APP_PRIVATE, "App private", null) }
      }
      is Stored.Custom -> {
        val root = File(stored.path).canonicalFile
        if (!root.isDirectory) {
          // Stale binding: fall back without wiping prefs so UI can prompt.
          return privateRoot().let {
            Resolved(it, Kind.APP_PRIVATE, "App private (bound folder missing)", stored.safUri)
          }
        }
        // Re-validate grants each resolve so revoked All-files access cannot keep
        // a previously allowed shared path.
        val allowed = validateAllowed(root)
        if (allowed.isFailure) {
          return privateRoot().let {
            Resolved(
              it,
              Kind.APP_PRIVATE,
              "App private (access revoked for bound folder)",
              stored.safUri,
            )
          }
        }
        val kind = if (stored.safUri != null && isUnder(root, File(filesDir, "workspace-mirrors"))) {
          Kind.SAF_MIRROR
        } else {
          Kind.CUSTOM
        }
        return Resolved(root, kind, stored.label.ifBlank { root.name }, stored.safUri)
      }
    }
  }

  fun useDefault() {
    save(Stored.Default)
    privateRoot()
  }

  fun useExternal(): Result<Resolved> {
    val external = externalRoot()
      ?: return Result.failure(IllegalStateException("App external storage is unavailable"))
    save(Stored.External)
    return Result.success(Resolved(external, Kind.APP_EXTERNAL, "App external", null))
  }

  fun bindPath(path: String, label: String? = null, safUri: String? = null): Result<Resolved> {
    val target = runCatching { File(path).canonicalFile }.getOrElse {
      return Result.failure(IllegalArgumentException("Invalid path"))
    }
    if (target.exists() && !target.isDirectory) {
      return Result.failure(IllegalArgumentException("Workspace must be a directory"))
    }
    // Validate before mkdir so a denied path cannot create folders outside roots.
    // Non-existent targets still have a usable canonical path for prefix checks.
    validateAllowed(target).onFailure { return Result.failure(it) }
    if (!target.exists()) {
      if (!target.mkdirs() && !target.isDirectory) {
        return Result.failure(IllegalArgumentException("Could not create workspace directory"))
      }
    }
    if (!target.isDirectory) {
      return Result.failure(IllegalArgumentException("Workspace must be a directory"))
    }
    validateAllowed(target).onFailure { return Result.failure(it) }
    val name = label?.ifBlank { null } ?: target.name.ifBlank { target.path }
    save(Stored.Custom(target.path, safUri, name))
    return Result.success(Resolved(target, Kind.CUSTOM, name, safUri))
  }

  /**
   * Bind a SAF document tree. When the tree is on primary shared storage and the
   * resulting path is allowed, cwd becomes that real path. Otherwise creates an
   * app-private mirror directory and stores the URI for later DocumentFile work.
   */
  fun bindSafTree(uri: String, treeDocumentId: String, displayName: String): Result<Resolved> {
    val resolvedPath = primaryTreePath(treeDocumentId)
    if (resolvedPath != null) {
      val attempt = bindPath(resolvedPath, label = displayName.ifBlank { File(resolvedPath).name }, safUri = uri)
      if (attempt.isSuccess) return attempt
      // If path resolved but not allowed (no All files), fall through to mirror
      // unless the failure is "not a directory".
    }
    val safeName = displayName.ifBlank { "bound" }
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .take(64)
      .ifBlank { "bound" }
    val mirror = File(File(filesDir, "workspace-mirrors"), safeName).canonicalFile
    mirror.mkdirs()
    if (!mirror.isDirectory) {
      return Result.failure(IllegalStateException("Cannot create workspace mirror"))
    }
    val label = buildString {
      append(displayName.ifBlank { safeName })
      append(" (local mirror — not the picked folder contents)")
    }
    save(Stored.Custom(mirror.path, uri, label))
    return Result.success(Resolved(mirror, Kind.SAF_MIRROR, label, uri))
  }

  fun validateAllowed(target: File): Result<File> {
    val canonical = target.canonicalFile
    if (isUnder(canonical, filesDir.canonicalFile)) return Result.success(canonical)
    externalFilesDir?.canonicalFile?.let { if (isUnder(canonical, it)) return Result.success(canonical) }
    val shared = sharedStorageRoot?.canonicalFile
    if (shared != null && isUnder(canonical, shared)) {
      return if (isAllFilesAccess()) Result.success(canonical)
      else Result.failure(
        IllegalStateException(
          "All files access is required to use folders outside app storage. " +
            "Enable it in system settings, or pick App private / App external."
        )
      )
    }
    return Result.failure(
      IllegalArgumentException("Path is outside allowed storage roots")
    )
  }

  /** primary:Code/app → sharedStorageRoot/Code/app ; primary: → shared root */
  fun primaryTreePath(treeDocumentId: String): String? {
    val shared = sharedStorageRoot ?: return null
    val parts = treeDocumentId.split(":", limit = 2)
    if (parts.isEmpty() || !parts[0].equals("primary", ignoreCase = true)) return null
    val relative = parts.getOrNull(1)?.trim('/').orEmpty()
    return if (relative.isEmpty()) shared.canonicalFile.path
    else File(shared, relative).canonicalFile.path
  }

  private fun privateRoot(): File = File(filesDir, "workspace").apply { mkdirs() }.canonicalFile

  private fun externalRoot(): File? =
    externalFilesDir?.let { File(it, "workspace").apply { mkdirs() }.canonicalFile }

  private fun isUnder(child: File, root: File): Boolean {
    val c = child.canonicalFile
    val r = root.canonicalFile
    return c == r || c.path.startsWith(r.path + File.separator)
  }

  companion object {
    const val PREFS = "workspace-binding"
    const val KEY_MODE = "mode"
    const val KEY_PATH = "path"
    const val KEY_SAF = "safUri"
    const val KEY_LABEL = "label"
    const val MODE_DEFAULT = "default"
    const val MODE_EXTERNAL = "external"
    const val MODE_CUSTOM = "custom"

    fun storedFromPrefs(
      mode: String?,
      path: String?,
      safUri: String?,
      label: String?
    ): Stored = when (mode) {
      MODE_EXTERNAL -> Stored.External
      MODE_CUSTOM -> if (!path.isNullOrBlank()) {
        Stored.Custom(path, safUri, label ?: File(path).name)
      } else Stored.Default
      else -> Stored.Default
    }

    fun writePrefs(stored: Stored, edit: (mode: String, path: String?, saf: String?, label: String?) -> Unit) {
      when (stored) {
        is Stored.Default -> edit(MODE_DEFAULT, null, null, null)
        is Stored.External -> edit(MODE_EXTERNAL, null, null, null)
        is Stored.Custom -> edit(MODE_CUSTOM, stored.path, stored.safUri, stored.label)
      }
    }
  }
}
