package dev.pi.mobile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.pi.mobile.backend.CapabilityProbe
import dev.pi.mobile.backend.DurableRuntime
import dev.pi.mobile.backend.WorkspaceBinding
import dev.pi.mobile.backend.hasAllFilesAccess
import dev.pi.mobile.backend.workspaceBinding
import dev.pi.mobile.chat.AttachmentController
import dev.pi.mobile.chat.AttachmentLifecycle
import dev.pi.mobile.chat.AuthBrowserHandoff
import dev.pi.mobile.chat.ChatEntry
import dev.pi.mobile.chat.ChatModelOption
import dev.pi.mobile.chat.ChatScreen
import dev.pi.mobile.chat.CommandCatalog
import dev.pi.mobile.chat.ComposerDockState
import dev.pi.mobile.chat.ComposerDraft
import dev.pi.mobile.chat.ComposerSkillUiPolicy
import dev.pi.mobile.chat.ConversationTree
import dev.pi.mobile.chat.DurableEvent
import dev.pi.mobile.chat.InboxState
import dev.pi.mobile.chat.McpState
import dev.pi.mobile.chat.ModelKind
import dev.pi.mobile.chat.PendingAttachment
import dev.pi.mobile.chat.PickerSheet
import dev.pi.mobile.chat.SessionActions
import dev.pi.mobile.chat.SessionDialogsReducer
import dev.pi.mobile.chat.SessionDrawerReducer
import dev.pi.mobile.chat.SessionDrawerState
import dev.pi.mobile.chat.SessionList
import dev.pi.mobile.chat.SettingsUiReducer
import dev.pi.mobile.chat.SettingsUiState
import dev.pi.mobile.chat.TranscriptFoldReducer
import dev.pi.mobile.chat.TranscriptFoldState
import dev.pi.mobile.chat.UsageState
import dev.pi.mobile.chat.activeSessionStore
import dev.pi.mobile.theme.PiAITheme

/**
 * The durable-native chat screen.
 *
 * The Activity owns the runtime, the workspace and the session's platform services. Its state is
 * shared by the concern-specific files beside it: durable event routing (`ChatEvents.kt`), the
 * session surface (`ChatSessions.kt`), the composer and attachments (`ChatComposer.kt`), the model
 * catalog and sign-in (`ChatModels.kt`), the settings sheets (`ChatSheets.kt`), and the screen
 * itself (`chat/ChatScreen.kt`). [DurableTranscriptReducer] is the reducer behind the transcript.
 *
 * The Activity drives Pi Durable through [DurableRuntime] and renders the transcript rows the host
 * projects (`runtime/src/ui.mjs` → `DurableTranscriptReducer`). All settings chrome — the composer
 * chevron, its Workspace/Model/Effort menu, the sheets behind it and the live sign-in session — is
 * state in [SettingsUiState] with its transitions in [SettingsUiReducer], so every rule the UI
 * depends on is testable off-device.
 *
 * The session surface is the same shape: the right-edge history drawer, the composer dock and the
 * queue/context dialogs read [SessionList], [ConversationTree], [UsageState] and [InboxState] from
 * the host's events, and every decision they make (when the drawer may open, which section is
 * expanded, what is sendable, where an export lands) lives in a pure reducer under `chat/`.
 *
 * Provider sign-in is real: the `auth.*` events come from pi-ai's own flows running inside the
 * hidden WebView, and the Connect sheet only renders them and answers their prompts.
 */
class MainActivity : ComponentActivity() {
  internal lateinit var binding: WorkspaceBinding
  internal var workspace by mutableStateOf<WorkspaceBinding.Resolved?>(null)
  internal var allFilesAccess by mutableStateOf(false)

  internal var runtime: DurableRuntime? = null
  /** Bumped on every (re)start so events from a closed runtime cannot touch the UI. */
  internal var runtimeEpoch = 0L

  internal var ready by mutableStateOf(false)
  internal var busy by mutableStateOf(false)
  internal var transcript by mutableStateOf<List<ChatEntry>>(emptyList())
  /**
   * Ids of the rows [dispatchSend] staged optimistically (the user's own bubble, shown before the
   * host confirms it). A durable row for the turn replaces them, exactly as a whole-history rebuild
   * already drops them, so an incremental frame never leaves a second copy on screen.
   */
  internal var optimisticRowIds = emptySet<String>()
  internal var draft by mutableStateOf("")
  /** A notice the user must read: an export's path, why nothing was exported, or a failure. */
  internal var noticeDialog by mutableStateOf<String?>(null)

  /** When true, a send while a reply streams queues a follow-up instead of steering it. */
  internal var queueFollowUp by mutableStateOf(false)

  /** Bumped on every content change so the transcript's sticky auto-follow re-triggers. */
  internal var transcriptFollowEpoch by mutableStateOf(0L)

  /** The composer's staged general files, in chip order. */
  internal var attachments by mutableStateOf<List<PendingAttachment>>(emptyList())

  /** Which folded agent-work runs are open; pruned to the runs actually on screen. */
  internal var transcriptFold by mutableStateOf(TranscriptFoldState())

  /**
   * The exact text and chips that left the composer on the last send, held until the run settles.
   * A failed send restores them only while the composer is still blank and the chips still match;
   * newer input always wins.
   */
  internal var pendingSend by mutableStateOf<ComposerDraft?>(null)

  /** Staged files stay on disk for tools and history; this owns when an idle chip may be deleted. */
  internal val attachmentLifecycle = AttachmentLifecycle()

  /** Picks, staging and the startup sweep; the chips and notice stay Activity state. */
  internal val attachmentController by lazy {
    AttachmentController(
      contentResolver = contentResolver,
      scope = lifecycleScope,
      stagingRoot = ::stagingRoot,
      attachments = { attachments },
      update = { attachments = it },
      notice = { noticeDialog = it },
    )
  }

  /**
   * The general-file picker: any document type, picked through the system UI and staged into the
   * workspace. Registered before `onCreate` returns, as the activity-result API requires.
   */
  internal val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    attachmentController.stage(uris)
  }

  /**
   * The workspace folder picker: a SAF document tree, whose grant is persisted before it is bound.
   *
   * Binding the tree is what makes the picked directory the real `cwd` the agent's tools run in.
   * Primary shared storage only resolves to a real path when All files access is granted; otherwise
   * [WorkspaceBinding.bindSafTree] falls back to an app-private mirror and the notice says exactly
   * that — no promise of a path Android will not hand over.
   */
  internal val openWorkspaceTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri == null) return@registerForActivityResult
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (docId == null) {
      noticeDialog = "Could not read the picked folder."
      return@registerForActivityResult
    }
    val label = docId.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" }
    binding.bindSafTree(uri.toString(), docId, label).fold(
      onSuccess = { resolved ->
        workspace = resolved
        noticeDialog = when (resolved.kind) {
          WorkspaceBinding.Kind.SAF_MIRROR ->
            "Folder bound as an app-private mirror (no stable path). Enable all files access to use primary storage paths directly."
          else -> "Workspace: ${resolved.root.path}"
        }
        startRuntime()
      },
      onFailure = { error -> noticeDialog = error.message ?: "Could not bind folder" },
    )
  }

  private val preferences by lazy { dev.pi.mobile.chat.settingsStore(this) }
  private var settingsState by mutableStateOf(SettingsUiState())
  internal var settings: SettingsUiState
    get() = settingsState
    set(value) {
      preferences.record(value)
      settingsState = value
    }
  internal var providers by mutableStateOf<List<DurableEvent.CatalogProvider>>(emptyList())
  internal var authRows by mutableStateOf<List<DurableEvent.AuthRow>>(emptyList())
  /** Every model the providers list, by kind: the picker's reachable catalog. */
  internal var catalog by mutableStateOf<Map<ModelKind, List<ChatModelOption>>>(emptyMap())
  /** The authenticated subset, by kind: what the picker offers for selection. */
  internal var available by mutableStateOf<Map<ModelKind, List<ChatModelOption>>>(emptyMap())
  internal var operationKind by mutableStateOf(ModelKind.Image)
  internal var operationModels by mutableStateOf<Map<ModelKind, String>>(emptyMap())
  internal var imagePrompt by mutableStateOf("")
  internal var classifyState by mutableStateOf("""{"text":"hello"}""")
  internal var classifyQuestion by mutableStateOf("Is this a greeting?")

  /** The conversation the host is reading, as it reports it; what the drawer marks as current. */
  internal var activeConversationId: Long? = null

  /**
   * Where the app was when it was last open. The host resumes it at launch while it still exists, and
   * every `ready`/`session` frame re-records whatever the host actually opened — so a switch is what a
   * later launch comes back to, and a stale record replaces itself with the truth.
   */
  internal val activeSession by lazy { activeSessionStore(this) }
  internal var sessions by mutableStateOf(SessionList())
  internal var tree by mutableStateOf(ConversationTree())
  internal var usage by mutableStateOf(UsageState())
  internal var inbox by mutableStateOf(InboxState())
  internal var drawer by mutableStateOf(SessionDrawerState())
  internal var compactInstructions by mutableStateOf("")

  /** The app-owned commands the host discovered: skills (durable extensions) and templates. */
  internal var commands by mutableStateOf(CommandCatalog(loading = true))

  /** The MCP servers the host has configured, and what each is doing right now. */
  internal var mcp by mutableStateOf(McpState())

  /**
   * What the composer's command surfaces are filtering on: the slash token, the Resources sheet's
   * query and the MCP sheet's tool query. One place, because each sheet is a single field.
   */
  internal var commandQuery by mutableStateOf("")

  /**
   * The generation of the login whose callbacks the UI is still waiting on. Captured from the
   * state `startSignIn` returns; a callback carrying anything else belongs to an abandoned flow.
   */
  internal var authGeneration by mutableStateOf(0L)

  /**
   * The OAuth browser hand-off. A notice carrying a sign-in URL (or a device verification page)
   * opens the OS browser itself — the user is not expected to tap "open in browser". The hand-off
   * dispatches once per login and URL, so a replayed event or a recomposition cannot launch twice.
   */
  internal val authHandoff = AuthBrowserHandoff { url -> openInBrowser(url) }

  /** The session surface's callbacks, grouped so the screen's signature stays readable. */
  internal val sessionActions = SessionActions(
    openHistory = { fromTreeAction -> openHistory(fromTreeAction) },
    closeHistory = { closeHistory() },
    setQuery = { drawer = SessionDrawerReducer.setQuery(drawer, it) },
    setOverflow = { drawer = SessionDrawerReducer.setOverflow(drawer, it) },
    // Expanding the tree is what loads it: the drawer never pays for a tree it does not show.
    toggleTree = {
      drawer = SessionDrawerReducer.toggleTree(drawer)
      if (drawer.treeExpanded) runtime?.tree()
    },
    newSession = { newSession() },
    switchSession = { switchSession(it) },
    startRename = { drawer = SessionDrawerReducer.startRename(drawer, it) },
    setRenaming = { drawer = SessionDrawerReducer.setRenaming(drawer, it) },
    submitRename = { submitRename() },
    cancelRename = { drawer = SessionDrawerReducer.cancelRename(drawer) },
    fork = { forkAt(it) },
    export = { export(it) },
    copyLast = { copyLastAnswer() },
    withdraw = { id ->
      // The queue is re-read from the host either way; dropping the row now keeps the dialog honest
      // while the withdrawal round-trips.
      inbox = SessionDialogsReducer.withdraw(inbox, id)
      runtime?.withdraw(id)
    },
    refreshQueue = { requestInbox() },
    setCompactInstructions = { compactInstructions = it },
    compact = {
      runtime?.compactSession(SessionDialogsReducer.compactNote(compactInstructions))
      compactInstructions = ""
    },
    resetContext = { runtime?.resetSession(null) },
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settingsState = preferences.restore()
    // Edge to edge, with both bars transparent and contrast enforcement off, so the Compose window
    // insets are the real ones the drawer, the header and the composer all inset against.
    enableEdgeToEdge()
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
      window.isStatusBarContrastEnforced = false
    }
    binding = workspaceBinding(this)
    workspace = binding.resolve()
    setContent {
      PiAITheme {
        ChatScreen(
          workspace = workspace,
          allFilesAccess = allFilesAccess,
          ready = ready,
          busy = busy,
          transcript = transcript,
          draft = draft,
          noticeDialog = noticeDialog,
          settings = settings,
          sessions = sessions,
          tree = tree,
          usage = usage,
          inbox = inbox,
          drawer = drawer,
          compactInstructions = compactInstructions,
          commands = commands,
          mcp = mcp,
          commandQuery = commandQuery,
          dock = ComposerDockState(
            model = selectedModel()?.name ?: settings.selectedModel,
            effort = settings.effort,
            usage = usage,
            inbox = inbox,
          ),
          actions = sessionActions,
          attachments = attachments,
          fold = transcriptFold,
          followUp = queueFollowUp,
          followEpoch = transcriptFollowEpoch,
          onDraft = { draft = it },
          onSend = ::send,
          onFollowUpSend = ::sendFollowUp,
          onToggleFollowUp = { queueFollowUp = !queueFollowUp },
          onStop = { runtime?.abort() },
          onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
          onRemoveAttachment = ::removeAttachment,
          onToggleWork = { key -> transcriptFold = TranscriptFoldReducer.toggleWork(transcriptFold, key) },
          onToggleTool = { id -> transcript = TranscriptFoldReducer.toggleTool(transcript, id) },
          onOpenAttachment = ::openAttachment,
          onChevron = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Index) },
          onMenuEntry = { sheet -> settings = SettingsUiReducer.openSheet(settings, sheet) },
          onDismissNoticeDialog = { noticeDialog = null },
          onWorkspacePickFolder = { openWorkspaceTree.launch(null) },
          onRequestAllFilesAccess = ::openAllFilesAccessSettings,
          // The composer's slash surface: the autocomplete inserts a skill's invocation into the
          // draft and runs nothing; selecting a row from the Resources sheets does the same.
          slashRows = ComposerSkillUiPolicy.suggestions(commands.skills, draft),
          onInsertCommand = { row -> draft = ComposerSkillUiPolicy.applyInsertion(draft, row) },
          onCommandQuery = { commandQuery = it },
          onRunLocal = { row, arguments -> runLocalCommand(row, arguments) },
          onMcpAdd = {
            val error = dev.pi.mobile.chat.McpUiReducer.addError(mcp)
            if (error != null) noticeDialog = error
            else {
              runtime?.mcpAdd(mcp.draftName.trim(), mcp.draftUrl.trim())
              mcp = mcp.copy(draftName = "", draftUrl = "")
            }
          },
          onMcpRemove = { id -> runtime?.mcpRemove(id) },
          onMcpConnect = { id -> runtime?.mcpConnect(id) },
          onMcpDisconnect = { id -> runtime?.mcpDisconnect(id) },
          onMcpRefresh = { runtime?.mcpState(); runtime?.commands() },
          onMcpDraftName = { mcp = dev.pi.mobile.chat.McpUiReducer.setDraftName(mcp, it) },
          onMcpDraftUrl = { mcp = dev.pi.mobile.chat.McpUiReducer.setDraftUrl(mcp, it) },
        )
        SettingsSheetHost()
      }
    }
    startRuntime()
  }

  override fun onResume() {
    super.onResume()
    allFilesAccess = hasAllFilesAccess()
    workspace = binding.resolve()
  }

  override fun onDestroy() {
    runtimeEpoch++
    runtime?.close()
    runtime = null
    super.onDestroy()
  }

  /** One runtime per workspace; a restart bumps [runtimeEpoch] so stale events are ignored. */
  internal fun startRuntime() {
    // A new workspace cannot read the old one's staged files, so the chips go with it.
    dropStagedForReset()
    val resolved = binding.resolve()
    workspace = resolved
    allFilesAccess = hasAllFilesAccess()
    // The workspace the agent is about to see is the one worth sweeping: staged copies of the user's
    // own files live inside it, so nothing else bounds them.
    pruneStagedAttachments()
    runtimeEpoch++
    val epoch = runtimeEpoch
    runtime?.close()
    ready = false
    busy = false
    val next = DurableRuntime(this, resolved.root, resumeConversation = activeSession.recorded()) { event ->
      runOnUiThread { if (epoch == runtimeEpoch) handle(event) }
    }
    runtime = next
    next.start()
    // Prove what this app's own process can do — which exec routes work for a file it wrote, and which
    // PATH binaries its uid may actually run — from inside its own SELinux domain, then drop the
    // report in the bound workspace for the agent to read. Off the main thread, fire-and-forget; one
    // summary line goes to logcat on the existing console tag. The reach comes from the router this
    // runtime is really using ([DurableRuntime.reach]), so the report cannot state a root set the app
    // is not routing through.
    CapabilityProbe.forApp(this, resolved.root, reachSource = next::reach).runDetached()
  }
}
