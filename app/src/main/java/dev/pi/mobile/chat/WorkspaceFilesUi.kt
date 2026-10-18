package dev.pi.mobile.chat

/**
 * The workspace file drawer: the pure vocabulary behind browsing the directory the agent actually
 * runs in, previewing a file, and staging one into the composer.
 *
 * The drawer deliberately mirrors the history drawer's shape: a host-owned [WorkspaceFilesState],
 * a reducer for every transition, and no filesystem calls in the composables. The Kotlin file I/O
 * lives in [WorkspaceFileBrowser]; this file decides what a row is, how rows sort, what a path
 * means, and which preview a name earns.
 */
data class WorkspaceEntry(
  /** The final path segment, as shown in a row. */
  val name: String,
  /** Path from the workspace root, `/`-separated and never leading with one. `""` is the root. */
  val path: String,
  val directory: Boolean,
  val sizeBytes: Long = 0L,
  val modifiedMs: Long = 0L,
)

/** How a file is shown once tapped. Binary files still open — as honest metadata, not fake text. */
enum class FilePreviewKind(val label: String) {
  MARKDOWN("Markdown"),
  IMAGE("Image"),
  CODE("Code"),
  TEXT("Text"),
  BINARY("Binary"),
}

/** One open preview. [absolutePath] is inside the bound workspace, resolved by the browser. */
data class FilePreviewState(
  val entry: WorkspaceEntry,
  val absolutePath: String,
  val loading: Boolean = false,
  val error: String? = null,
  val text: String? = null,
  /** The text preview hit [WorkspaceFilesUi.MAX_TEXT_BYTES] and was cut. */
  val truncated: Boolean = false,
)

/** The drawer's own state: where it is, what it listed, what is filtered, and what is previewing. */
data class WorkspaceFilesState(
  val open: Boolean = false,
  /** The directory being shown, relative to the workspace root. `""` is the root. */
  val path: String = "",
  val entries: List<WorkspaceEntry> = emptyList(),
  val query: String = "",
  val loading: Boolean = false,
  val error: String? = null,
  /** The directory held more entries than the browser will list at once. */
  val truncated: Boolean = false,
  val preview: FilePreviewState? = null,
)

/** One breadcrumb segment; [path] is where tapping it navigates. */
data class WorkspaceCrumb(val label: String, val path: String)

object WorkspaceFilesUi {
  /** Text previews stop here; a bigger file says it was cut rather than freezing the phone. */
  const val MAX_TEXT_BYTES = 256 * 1024

  /** One directory listing stops here. The panel labels the truncation instead of lying. */
  const val MAX_ENTRIES = 400

  /** Images above this are metadata only; decoding them would spend the heap for no readable gain. */
  const val MAX_IMAGE_BYTES = 24L * 1024L * 1024L

  private val MARKDOWN_EXTS = setOf("md", "markdown", "mdown", "mkd")
  private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
  private val CODE_EXTS = setOf(
    "kt", "kts", "java", "js", "jsx", "mjs", "cjs", "ts", "tsx", "py", "rb", "go", "rs",
    "c", "h", "cpp", "hpp", "cc", "cxx", "cs", "swift", "m", "mm", "sh", "bash", "zsh",
    "fish", "ps1", "bat", "cmd", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
    "gradle", "sql", "json", "jsonl", "jsonc", "xml", "html", "htm", "css", "scss", "less",
    "vue", "svelte", "dart", "scala", "clj", "cljs", "edn", "ex", "exs", "hs", "lua", "pl",
    "php", "r", "tex", "diff", "patch", "gitignore", "dockerignore", "editorconfig", "env",
    "svg", "proto", "graphql", "tf", "hcl", "nix", "zig", "v", "asm",
  )
  private val TEXT_EXTS = setOf(
    "txt", "text", "log", "csv", "tsv", "rst", "adoc", "org", "srt", "vtt", "pem", "crt",
    "license", "readme", "makefile", "dockerfile", "gemfile", "podfile", "procfile",
  )
  private val KNOWN_NAMES = setOf(
    "dockerfile", "makefile", "license", "readme", "gemfile", "podfile", "procfile",
    "rakefile", "justfile", "brewfile", "codeowners",
  )

  private val MIMES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "avif" to "image/avif",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "json" to "application/json",
    "jsonl" to "application/json",
    "html" to "text/html",
    "htm" to "text/html",
    "xml" to "application/xml",
    "svg" to "image/svg+xml",
    "css" to "text/css",
    "js" to "text/javascript",
    "ts" to "application/typescript",
    "pdf" to "application/pdf",
    "csv" to "text/csv",
    "txt" to "text/plain",
    "log" to "text/plain",
  )

  /** The path shown in the drawer, normalized lexically and never above the root. */
  fun normalizePath(raw: String?): String {
    val parts = ArrayDeque<String>()
    for (part in (raw ?: "").replace('\\', '/').split('/')) {
      when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeLast()
        else -> parts.addLast(part)
      }
    }
    return parts.joinToString("/")
  }

  /** A child path, or this directory for a blank name. */
  fun child(parent: String, name: String): String {
    val base = normalizePath(parent)
    val leaf = name.trim().replace('\\', '/').trim('/')
    return if (base.isEmpty()) normalizePath(leaf) else normalizePath("$base/$leaf")
  }

  /** The parent directory, or `null` when the current path is already the root. */
  fun parent(path: String): String? {
    val normalized = normalizePath(path)
    return if (normalized.isEmpty()) null else normalized.substringBeforeLast('/', "")
  }

  /** Root first, then one crumb per path segment. */
  fun crumbs(path: String, rootLabel: String): List<WorkspaceCrumb> {
    val normalized = normalizePath(path)
    val result = ArrayList<WorkspaceCrumb>()
    result += WorkspaceCrumb(rootLabel.ifBlank { "Workspace" }, "")
    if (normalized.isEmpty()) return result
    var walking = ""
    for (segment in normalized.split('/')) {
      walking = if (walking.isEmpty()) segment else "$walking/$segment"
      result += WorkspaceCrumb(segment, walking)
    }
    return result
  }

  fun previewKind(name: String): FilePreviewKind {
    val ext = extensionOf(name)
    return when {
      ext in MARKDOWN_EXTS -> FilePreviewKind.MARKDOWN
      ext in IMAGE_EXTS -> FilePreviewKind.IMAGE
      ext in CODE_EXTS -> FilePreviewKind.CODE
      ext in TEXT_EXTS -> FilePreviewKind.TEXT
      normalizeName(name) in KNOWN_NAMES -> FilePreviewKind.TEXT
      else -> FilePreviewKind.BINARY
    }
  }

  /** The language name [CodeHighlight] understands for this file, or blank for plain code. */
  fun languageFor(name: String): String = when (extensionOf(name)) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "jsx", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "hpp", "cc", "cxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "sh", "bash", "zsh", "fish" -> "shell"
    "sql" -> "sql"
    "json", "jsonl", "jsonc" -> "json"
    "yml", "yaml" -> "yaml"
    "html", "htm", "xml", "svg" -> "html"
    "css", "scss", "less" -> "css"
    "php" -> "php"
    "diff", "patch" -> "diff"
    else -> ""
  }

  /** A best-effort MIME for staging and for the system hand-off. Unknown files stay octet-stream. */
  fun mimeFor(name: String): String = MIMES[extensionOf(name)] ?: "application/octet-stream"

  /** Directories first, then case-insensitive by name. Dotfiles sort with their neighbours. */
  fun sorted(entries: List<WorkspaceEntry>): List<WorkspaceEntry> =
    entries.sortedWith(
      compareByDescending<WorkspaceEntry> { it.directory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

  /** The rows the panel draws: the listing sorted, then the drawer's own name filter applied. */
  fun visibleEntries(state: WorkspaceFilesState): List<WorkspaceEntry> {
    val query = state.query.trim()
    val sorted = sorted(state.entries)
    if (query.isEmpty()) return sorted
    return sorted.filter { it.name.contains(query, ignoreCase = true) }
  }

  /** A file may be staged only when it fits the composer's per-file limit. */
  fun canAttach(entry: WorkspaceEntry): Boolean =
    !entry.directory && entry.sizeBytes <= AttachmentLimits.MAX_BYTES_PER_FILE

  fun attachRejection(entry: WorkspaceEntry): String? = when {
    entry.directory -> "Folders cannot be attached."
    entry.sizeBytes > AttachmentLimits.MAX_BYTES_PER_FILE ->
      "${entry.name} is larger than the ${humanSize(AttachmentLimits.MAX_BYTES_PER_FILE)} attachment limit."
    else -> null
  }

  /** A binary file previews as metadata; an image previews as a bitmap; everything else as text. */
  fun previewsAsText(kind: FilePreviewKind): Boolean =
    kind == FilePreviewKind.MARKDOWN || kind == FilePreviewKind.CODE || kind == FilePreviewKind.TEXT

  fun entryDetail(entry: WorkspaceEntry): String =
    if (entry.directory) "folder" else "${humanSize(entry.sizeBytes)} · ${previewKind(entry.name).label}"

  fun humanSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
      value /= 1024.0
      unit++
    }
    val whole = value >= 100.0 || value % 1.0 == 0.0
    val rendered = if (whole) value.toInt().toString() else ((value * 10).toInt() / 10.0).toString()
    return "$rendered ${units[unit]}"
  }

  private fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase()

  private fun normalizeName(name: String): String = name.lowercase().trim()
}

object WorkspaceFilesReducer {
  /** Opening resets the filter and any open preview but keeps the folder the user was last in. */
  fun open(state: WorkspaceFilesState): WorkspaceFilesState =
    state.copy(open = true, query = "", error = null, preview = null)

  fun close(state: WorkspaceFilesState): WorkspaceFilesState =
    state.copy(open = false, query = "", preview = null, error = null)

  /** Navigating starts a fresh listing; the old rows do not linger under the new breadcrumb. */
  fun navigate(state: WorkspaceFilesState, path: String): WorkspaceFilesState =
    state.copy(
      path = WorkspaceFilesUi.normalizePath(path),
      entries = emptyList(),
      query = "",
      loading = true,
      error = null,
      truncated = false,
      preview = null,
    )

  fun refreshing(state: WorkspaceFilesState): WorkspaceFilesState =
    state.copy(loading = true, error = null)

  fun listed(
    state: WorkspaceFilesState,
    path: String,
    entries: List<WorkspaceEntry>,
    truncated: Boolean,
  ): WorkspaceFilesState {
    if (state.path != WorkspaceFilesUi.normalizePath(path)) return state
    return state.copy(entries = entries, loading = false, error = null, truncated = truncated)
  }

  fun failed(state: WorkspaceFilesState, path: String, message: String): WorkspaceFilesState {
    if (state.path != WorkspaceFilesUi.normalizePath(path)) return state
    return state.copy(entries = emptyList(), loading = false, error = message, truncated = false)
  }

  fun setQuery(state: WorkspaceFilesState, query: String): WorkspaceFilesState = state.copy(query = query)

  fun previewOpened(
    state: WorkspaceFilesState,
    entry: WorkspaceEntry,
    absolutePath: String,
    loading: Boolean,
  ): WorkspaceFilesState = state.copy(
    preview = FilePreviewState(entry = entry, absolutePath = absolutePath, loading = loading),
  )

  fun previewLoaded(state: WorkspaceFilesState, text: String, truncated: Boolean): WorkspaceFilesState =
    state.copy(
      preview = state.preview?.copy(loading = false, error = null, text = text, truncated = truncated),
    )

  fun previewFailed(state: WorkspaceFilesState, message: String): WorkspaceFilesState =
    state.copy(preview = state.preview?.copy(loading = false, error = message))

  fun previewClosed(state: WorkspaceFilesState): WorkspaceFilesState = state.copy(preview = null)
}
