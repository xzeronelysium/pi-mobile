package dev.pi.mobile.chat

/**
 * The settings chrome, as a pure state machine.
 *
 * Two rules drive everything here:
 *
 * 1. **One surface at a time.** The composer chevron opens a compact menu whose entries are only
 *    Workspace, Model and Effort; an entry closes the menu and opens exactly one swipe-up sheet.
 *    The Model sheet's plus opens Connect — it does not stack a second sheet on top.
 * 2. **A sign-in is owned by the generation that started it.** pi-ai's flows are upstream code that
 *    keeps running after the sheet closes, so every callback carries the generation it belongs to
 *    and a callback from an abandoned flow is dropped instead of mutating a later session.
 */
enum class PickerSheet {
  /** The one index; details below it are one level deep. */
  Index,
  Workspace,
  Model,
  Effort,
  Connect,

  /** Images and classifier testing: reachable from the Workspace sheet, never a menu entry. */
  Operations,

  /**
   * The resource and server surfaces: the app-owned commands and the MCP server list. They are one
   * tap behind the Workspace sheet for the same reason [Operations] is — the chevron menu is exactly
   * Workspace, Model and Effort, and none of these is one of them.
   */
  Templates,
  Extensions,
  Mcp,
  ;

  companion object {
    /** The chevron menu is exactly these three, in this order. */
    val menuEntries: List<PickerSheet> = listOf(Workspace, Model, Effort)
  }
}

/** Everything the Connect sheet renders for one in-flight sign-in. */
data class SignInSessionState(
  val provider: String,
  val name: String,
  val authType: String,
  val subscription: Boolean,
  /** Lines from `interaction.notify`, in arrival order. */
  val notices: List<String> = emptyList(),
  val openedUrl: String? = null,
  val prompt: DurableEvent.AuthPrompt? = null,
  val busy: Boolean = true,
  val error: String? = null,
  val settled: String? = null,
  /** `userCode to verificationUri` when the flow is a device-code sign-in, else null. */
  val deviceCode: Pair<String, String>? = null,
  /** Minutes until that code expires, when the provider publishes it. */
  val deviceCodeMinutes: Int? = null,
  /** The folded notice line the two numbered steps already say, so it is not printed twice. */
  val deviceCodeNotice: String? = null,
)

/** One provider the Connect sheet offers, per sign-in method it actually supports. */
data class ConnectChoice(
  val provider: String,
  val label: String,
  val authType: String,
  val configured: Boolean,
)

/**
 * The single side effect an `auth.notice` owns: hand its URL to the OS browser, once.
 *
 * The decision is pure and lives in [SettingsUiReducer.authNoticeEffect]; the Activity consumes it
 * and is the only place that actually starts the browser intent.
 */
sealed interface AuthEffect {
  object None : AuthEffect
  data class OpenBrowser(val url: String) : AuthEffect
}

/**
 * The Activity's browser hand-off: the pure [SettingsUiReducer.authNoticeEffect] decision plus the
 * single-shot memory that stops a replayed notice or a recomposition from launching the browser
 * twice for the same sign-in. One launch per (login generation, URL); a fresh login may reuse a URL.
 */
class AuthBrowserHandoff(private val launcher: (String) -> Unit) {
  private var dispatchedGeneration = Long.MIN_VALUE
  private var dispatchedUrl: String? = null

  /** Apply one notice; @return true when it caused a browser launch. */
  fun onNotice(state: SettingsUiState, generation: Long, notice: DurableEvent.AuthNotice): Boolean {
    val effect = SettingsUiReducer.authNoticeEffect(state, generation, notice)
    if (effect !is AuthEffect.OpenBrowser) return false
    if (generation == dispatchedGeneration && effect.url == dispatchedUrl) return false
    dispatchedGeneration = generation
    dispatchedUrl = effect.url
    launcher(effect.url)
    return true
  }
}

/** Connect's two typed sections. */
data class ConnectGroup(val title: String, val rows: List<ConnectChoice>)

/**
 * All mutable settings chrome. Held by the Activity and replaced wholesale by [SettingsUiReducer]
 * so every transition is unit-testable without a device.
 */
data class SettingsUiState(
  val menuOpen: Boolean = false,
  val sheet: PickerSheet? = null,
  val selectedProvider: String = "",
  val selectedModel: String = "",
  val effort: String = "Medium",
  val modelQuery: String = "",
  val connectQuery: String = "",
  val operationQuery: String = "",
  /** Provider whose visibility checklist is open in the Model sheet. */
  val editingProvider: String? = null,
  val hiddenByProvider: Map<String, Set<String>> = emptyMap(),
  /** Typed into the Connect sheet; cleared whenever that sheet closes. */
  val apiKeyDraft: String = "",
  val signIn: SignInSessionState? = null,
  /** Bumped by every start and every abandonment; late callbacks carry the old value. */
  val loginGeneration: Long = 0,
  val operationResult: String? = null,
)

/** The transition functions behind the chevron, the sheets and the sign-in session. */
object SettingsUiReducer {
  const val OAUTH_SECTION = "OAUTH SIGN-IN"
  const val API_KEY_SECTION = "API KEY"
  const val AUTH_TYPE_OAUTH = "oauth"
  const val AUTH_TYPE_API_KEY = "api_key"

  // ------------------------------------------------------------------ menu and sheets

  /**
   * The composer chevron. It points down while the menu or a sheet is open and closes whatever is
   * open. The decision comes from the pre-tap state, so a sheet's own dismiss handler running on
   * the same tap cannot re-open the menu.
   */
  fun toggleChevron(state: SettingsUiState): SettingsUiState =
    if (state.menuOpen || state.sheet != null) dismissSheet(state)
    else state.copy(menuOpen = true)

  /** Open exactly one sheet: the menu closes and any other sheet is replaced, never stacked. */
  fun openSheet(state: SettingsUiState, sheet: PickerSheet): SettingsUiState {
    val base = if (state.sheet != sheet) dismissSheet(state) else state
    return base.copy(menuOpen = false, sheet = sheet)
  }

  /**
   * Close the open sheet. A sign-in that is still running is abandoned: its generation is bumped
   * so every callback it has left to emit is ignored, and [shouldCancelLogin] tells the caller to
   * abort the upstream flow too.
   */
  fun dismissSheet(state: SettingsUiState): SettingsUiState = state.copy(
    menuOpen = false,
    sheet = null,
    editingProvider = null,
    connectQuery = "",
    apiKeyDraft = "",
    signIn = null,
    loginGeneration = if (state.signIn != null) state.loginGeneration + 1 else state.loginGeneration,
  )

  /** True when moving from [previous] to [next] abandoned a live login the runtime must abort. */
  fun shouldCancelLogin(previous: SettingsUiState, next: SettingsUiState): Boolean =
    previous.signIn != null && next.signIn == null

  fun setModelQuery(state: SettingsUiState, query: String): SettingsUiState = state.copy(modelQuery = query)

  fun setOperationQuery(state: SettingsUiState, query: String): SettingsUiState = state.copy(operationQuery = query)

  fun setConnectQuery(state: SettingsUiState, query: String): SettingsUiState = state.copy(connectQuery = query)

  fun setEditingProvider(state: SettingsUiState, provider: String?): SettingsUiState = state.copy(editingProvider = provider)

  fun setApiKeyDraft(state: SettingsUiState, draft: String): SettingsUiState = state.copy(apiKeyDraft = draft)

  // ------------------------------------------------------------------ model + effort

  /** Selecting a model closes the sheet and clamps the effort to a level the model can run. */
  fun selectModel(state: SettingsUiState, option: ChatModelOption): SettingsUiState = state.copy(
    selectedProvider = option.provider,
    selectedModel = option.modelId,
    effort = ModelCatalogReducer.clampEffort(option, state.effort),
    sheet = null,
    editingProvider = null,
  )

  /** An effort the model cannot run is refused rather than stored and silently ignored upstream. */
  fun selectEffort(state: SettingsUiState, effort: String, allowed: List<String>): SettingsUiState =
    if (allowed.contains(effort)) state.copy(effort = effort, sheet = null) else state

  fun toggleHidden(state: SettingsUiState, option: ChatModelOption, hidden: Boolean): SettingsUiState =
    state.copy(hiddenByProvider = ModelCatalogReducer.hideModel(state.hiddenByProvider, option, hidden))

  fun hideAll(state: SettingsUiState, provider: String, catalog: List<ChatModelOption>): SettingsUiState =
    state.copy(hiddenByProvider = ModelCatalogReducer.hideProvider(state.hiddenByProvider, catalog, provider))

  fun showAll(state: SettingsUiState, provider: String): SettingsUiState =
    state.copy(hiddenByProvider = ModelCatalogReducer.showProvider(state.hiddenByProvider, provider))

  /** Search is a projection: it never changes the selected model or the visibility checklist. */
  fun modelGroups(
    groups: List<PickerProviderGroup>,
    query: String,
    editingProvider: String?,
  ): List<PickerProviderGroup> {
    if (query.isBlank()) return groups
    return groups.mapNotNull { group ->
      val providerMatches = group.providerName.contains(query.trim(), ignoreCase = true)
      val matches = group.models.filter { providerMatches || ModelCatalogReducer.matches(it, query) }
      val rows = if (editingProvider == group.provider) matches else matches.filter { it.modelId !in group.hiddenIds }
      if (rows.isEmpty()) null else group.copy(models = matches)
    }
  }

  // ------------------------------------------------------------------ connect provider list

  /**
   * The Connect list: every sign-in method of every provider, split into subscription OAuth and API
   * key, alphabetical by label, filtered by [query]. A provider that supports both methods appears
   * in both sections so neither capability is hidden.
   */
  fun connectGroups(
    providers: List<DurableEvent.CatalogProvider>,
    authRows: List<DurableEvent.AuthRow>,
    query: String,
  ): List<ConnectGroup> {
    val configured = authRows.filter { it.configured }.map { it.provider }.toSet()
    val choices = buildList {
      for (provider in providers) {
        if (provider.oauth) {
          add(
            ConnectChoice(
              provider = provider.id,
              label = provider.oauthLoginLabel ?: provider.name,
              authType = AUTH_TYPE_OAUTH,
              configured = provider.id in configured,
            ),
          )
        }
        if (provider.apiKey) {
          add(ConnectChoice(provider = provider.id, label = provider.name, authType = AUTH_TYPE_API_KEY, configured = provider.id in configured))
        }
      }
    }
    val needle = query.trim().lowercase(java.util.Locale.US)
    val matching = if (needle.isEmpty()) choices else choices.filter {
      it.label.lowercase(java.util.Locale.US).contains(needle) || it.provider.lowercase(java.util.Locale.US).contains(needle)
    }
    return listOf(
      OAUTH_SECTION to matching.filter { it.authType == AUTH_TYPE_OAUTH },
      API_KEY_SECTION to matching.filter { it.authType == AUTH_TYPE_API_KEY },
    ).filter { (_, rows) -> rows.isNotEmpty() }
      .map { (title, rows) -> ConnectGroup(title, rows.sortedBy { it.label.lowercase(java.util.Locale.US) }) }
  }

  // ------------------------------------------------------------------ sign-in session

  /**
   * Record that the runtime started a login. A second login is refused while one is running —
   * upstream refuses it too, so the UI must not pretend otherwise. The returned state's
   * [SettingsUiState.loginGeneration] is the token every later callback must carry.
   */
  fun startSignIn(
    state: SettingsUiState,
    provider: String,
    name: String,
    authType: String,
    subscription: Boolean,
  ): SettingsUiState {
    if (state.signIn != null) return state
    return state.copy(
      loginGeneration = state.loginGeneration + 1,
      sheet = PickerSheet.Connect,
      menuOpen = false,
      signIn = SignInSessionState(provider = provider, name = name, authType = authType, subscription = subscription),
    )
  }

  /** The live session, or null when the callback belongs to a login the UI has abandoned. */
  private fun live(state: SettingsUiState, generation: Long): SignInSessionState? {
    val session = state.signIn ?: return null
    return if (generation == state.loginGeneration) session else null
  }

  /**
   * Callbacks that name their provider are correlated on it as well as on the generation: the host
   * event surface carries no request id, so when a flow is abandoned and another provider's login
   * starts, the provider is what stops the dead flow's `auth.finished` from idling the live one.
   */
  private fun liveFor(state: SettingsUiState, generation: Long, provider: String): SignInSessionState? =
    live(state, generation)?.takeIf { it.provider == provider }

  /** One `notify` from the flow: an auth URL, a device code, a progress line or plain info. */
  fun applyNotice(state: SettingsUiState, generation: Long, notice: DurableEvent.AuthNotice): SettingsUiState {
    val session = live(state, generation) ?: return state
    val line = when (notice.kind) {
      "device_code" -> "code ${notice.userCode} → ${notice.verificationUri}" +
        (notice.expiresInSeconds?.let { " (expires in ${it / 60} min)" } ?: "")
      "auth_url" -> notice.instructions ?: "open the sign-in URL"
      else -> notice.message ?: notice.kind
    }
    // A device-code flow has no auth URL, but its verification page is what the user must open,
    // so it gets the same browser hand-off. The code is carried structurally as well as in the
    // notice line, because the sheet renders it as the two numbered steps the sign-in screen used.
    val opened = when (notice.kind) {
      "auth_url" -> notice.url ?: session.openedUrl
      "device_code" -> notice.verificationUri ?: session.openedUrl
      else -> session.openedUrl
    }
    val code = notice.userCode.orEmpty().trim()
    val uri = notice.verificationUri.orEmpty().trim()
    val isDeviceCode = notice.kind == "device_code" && code.isNotEmpty() && uri.isNotEmpty()
    return state.copy(
      signIn = session.copy(
        notices = session.notices + line,
        openedUrl = opened,
        deviceCode = if (isDeviceCode) code to uri else session.deviceCode,
        deviceCodeMinutes = if (isDeviceCode) notice.expiresInSeconds?.let { it / 60 } else session.deviceCodeMinutes,
        deviceCodeNotice = if (isDeviceCode) line else session.deviceCodeNotice,
      ),
    )
  }

  /**
   * The one side effect an auth notice owns: launch the OS browser at its sign-in URL.
   *
   * Returns [AuthEffect.None] for a callback from an abandoned generation, for a progress/info
   * line that carries no URL, for a value that is not an http(s) URL, and for a URL the live
   * session has already handed to the browser — so a replayed event or a recomposition cannot
   * launch a second browser. A device-code flow has no auth URL, so it hands off its verification
   * page instead, leaving the user code visible in the sheet. This is the pure decision the
   * Activity consumes; [AuthBrowserHandoff] adds the single-shot memory on top.
   */
  fun authNoticeEffect(state: SettingsUiState, generation: Long, notice: DurableEvent.AuthNotice): AuthEffect {
    val session = live(state, generation) ?: return AuthEffect.None
    val target = when (notice.kind) {
      "auth_url" -> notice.url
      "device_code" -> notice.verificationUri
      else -> null
    } ?: return AuthEffect.None
    if (!isBrowserUrl(target)) return AuthEffect.None
    // The live session already opened this URL; do not relaunch it.
    if (target == session.openedUrl) return AuthEffect.None
    return AuthEffect.OpenBrowser(target)
  }

  /** Only http/https is something the system browser should be handed. */
  fun isBrowserUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
  }

  /** A step the flow is blocked on; answering it resolves exactly that upstream call. */
  fun applyPrompt(state: SettingsUiState, generation: Long, prompt: DurableEvent.AuthPrompt): SettingsUiState {
    val session = live(state, generation) ?: return state
    return state.copy(signIn = session.copy(prompt = prompt, busy = false))
  }

  fun promptAnswered(state: SettingsUiState, requestId: String): SettingsUiState {
    val session = state.signIn ?: return state
    if (session.prompt?.requestId != requestId) return state
    return state.copy(signIn = session.copy(prompt = null, busy = true))
  }

  fun authSettled(state: SettingsUiState, generation: Long, event: DurableEvent.AuthSettled): SettingsUiState {
    val session = liveFor(state, generation, event.provider) ?: return state
    return state.copy(signIn = session.copy(settled = event.detail, prompt = null, busy = false))
  }

  fun authFailed(state: SettingsUiState, generation: Long, event: DurableEvent.AuthFailed): SettingsUiState {
    val session = liveFor(state, generation, event.provider) ?: return state
    val text = if (event.cancelled && event.message.isBlank()) "Sign-in cancelled" else event.message
    return state.copy(signIn = session.copy(error = text, prompt = null, busy = false))
  }

  fun authFinished(state: SettingsUiState, generation: Long, event: DurableEvent.AuthFinished): SettingsUiState {
    val session = liveFor(state, generation, event.provider) ?: return state
    return state.copy(signIn = session.copy(busy = false, prompt = null))
  }

  /** Cancel the in-flight sign-in: the session leaves the sheet and its callbacks are invalidated. */
  fun cancelSignIn(state: SettingsUiState): SettingsUiState {
    if (state.signIn == null) return state
    return state.copy(signIn = null, loginGeneration = state.loginGeneration + 1)
  }

  // ------------------------------------------------------------------ operations sheet

  fun setOperationResult(state: SettingsUiState, result: String?): SettingsUiState = state.copy(operationResult = result)
}
