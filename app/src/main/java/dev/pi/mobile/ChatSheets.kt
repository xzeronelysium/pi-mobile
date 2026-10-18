package dev.pi.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.pi.mobile.chat.CommandDiscoveryReducer
import dev.pi.mobile.chat.ExtensionsSheet
import dev.pi.mobile.chat.McpSheet
import dev.pi.mobile.chat.ModelCatalogReducer
import dev.pi.mobile.chat.PickerSheet
import dev.pi.mobile.chat.SettingsUiReducer
import dev.pi.mobile.chat.TemplatesSheet

// ---------------------------------------------------------------------------
// The settings sheets behind the composer's chevron
// ---------------------------------------------------------------------------

/**
 * Render whichever settings sheet is open. This is the one place the sheet catalogue is dispatched;
 * the state transitions stay in [SettingsUiReducer], and every runtime call goes through the
 * Activity's own helpers.
 */
@Composable
internal fun MainActivity.SettingsSheetHost() {
  settings.sheet?.let { sheet ->
    val backToIndex: (() -> Unit)? =
      if (sheet == PickerSheet.Index) null
      else ({
        dismissSheet()
        settings = SettingsUiReducer.openSheet(settings, PickerSheet.Index)
      })
    // A new key per sheet gives the modal a fresh sheet state. Without it, returning from a detail
    // to the index reuses the hidden state the back gesture left behind and the index never shows.
    key(sheet) {
      FlatSheet(
        // Back closes exactly one layer: a detail returns to the index, the index closes.
        onDismissRequest = backToIndex ?: ::dismissSheet,
        onBack = backToIndex,
        // The index is a short orientation page; a detail gets the extra room its list needs.
        heightFraction = if (sheet == PickerSheet.Index) 0.56f else 0.66f,
      ) {
        when (sheet) {
        PickerSheet.Index -> SettingsIndexSheet(
          workspaceLabel = workspace?.label ?: "app private",
          modelName = selectedModel()?.name ?: settings.selectedModel,
          effort = settings.effort,
          runtimeReady = ready,
          onModel = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Model) },
          onEffort = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Effort) },
          onWorkspace = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Workspace) },
          onResources = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Workspace) },
          onDismiss = ::dismissSheet,
        )
        PickerSheet.Workspace -> WorkspaceSheet(
          workspace = workspace,
          allFilesAccess = allFilesAccess,
          onWorkspaceDefault = {
            binding.useDefault()
            startRuntime()
          },
          onWorkspaceExternal = {
            binding.useExternal()
            startRuntime()
          },
          onWorkspacePickFolder = { openWorkspaceTree.launch(null) },
          onRequestAllFilesAccess = ::openAllFilesAccessSettings,
          onOpenOperations = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Operations) },
          onOpenTemplates = { commandQuery = ""; settings = SettingsUiReducer.openSheet(settings, PickerSheet.Templates) },
          onOpenExtensions = { commandQuery = ""; settings = SettingsUiReducer.openSheet(settings, PickerSheet.Extensions) },
          onOpenMcp = { commandQuery = ""; runtime?.mcpState(); settings = SettingsUiReducer.openSheet(settings, PickerSheet.Mcp) },
          onRefresh = ::refreshModels,
          onDismiss = ::dismissSheet,
        )
        PickerSheet.Model -> ModelSheet(
          query = settings.modelQuery,
          onQuery = { settings = SettingsUiReducer.setModelQuery(settings, it) },
          editingProvider = settings.editingProvider,
          groups = modelGroups(),
          selected = selectedModel(),
          onEdit = { settings = SettingsUiReducer.setEditingProvider(settings, it) },
          onToggleHidden = { option, hidden -> settings = SettingsUiReducer.toggleHidden(settings, option, hidden) },
          onHideAll = { provider -> settings = SettingsUiReducer.hideAll(settings, provider, chatCatalog()) },
          onShowAll = { provider -> settings = SettingsUiReducer.showAll(settings, provider) },
          onSelect = { option -> settings = SettingsUiReducer.selectModel(settings, option) },
          onConnect = { settings = SettingsUiReducer.openSheet(settings, PickerSheet.Connect) },
        )
        PickerSheet.Effort -> EffortSheet(
          selected = selectedModel(),
          effort = settings.effort,
          onEffort = { level ->
            settings = SettingsUiReducer.selectEffort(settings, level, ModelCatalogReducer.effortsFor(selectedModel()))
          },
        )
        PickerSheet.Connect -> ConnectSheet(
          state = settings,
          authRows = authRows,
          providerChoices = providers,
          onQuery = { settings = SettingsUiReducer.setConnectQuery(settings, it) },
          onSignIn = ::startSignIn,
          onAnswerPrompt = { value ->
            settings.signIn?.prompt?.let { prompt ->
              settings = SettingsUiReducer.promptAnswered(settings, prompt.requestId)
              runtime?.answerPrompt(prompt.requestId, value)
            }
          },
          onDismissPrompt = { requestId ->
            settings = SettingsUiReducer.promptAnswered(settings, requestId)
            runtime?.dismissPrompt(requestId)
          },
          onCancelSignIn = {
            runtime?.cancelLogin()
            settings = SettingsUiReducer.cancelSignIn(settings)
          },
          onLogout = { provider ->
            runtime?.logout(provider)
            requestCatalogs()
          },
          onOpenUrl = ::openInBrowser,
          onRefresh = ::refreshModels,
        )
        PickerSheet.Operations -> OperationsSheet(
          kind = operationKind,
          modelId = operationModel(operationKind) ?: "",
          models = (available[operationKind].orEmpty() + catalog[operationKind].orEmpty())
            .distinctBy { it.provider to it.modelId },
          query = settings.operationQuery,
          imagePrompt = imagePrompt,
          classifyState = classifyState,
          classifyQuestion = classifyQuestion,
          result = settings.operationResult,
          providerNote = capabilityNote(providers.firstOrNull { it.id == settings.selectedProvider }),
          onKind = { kind ->
            operationKind = kind
            settings = SettingsUiReducer.setOperationQuery(settings, "")
          },
          onQuery = { settings = SettingsUiReducer.setOperationQuery(settings, it) },
          onModel = { option -> operationModels = operationModels + (operationKind to option.modelId) },
          onImagePrompt = { imagePrompt = it },
          onClassifyState = { classifyState = it },
          onClassifyQuestion = { classifyQuestion = it },
          onRunImages = ::runImages,
          onRunClassify = ::runClassify,
        )
        // The app-owned commands. Templates insert their prompt and Extensions run here, both
        // under the one query field the sheet itself owns.
        PickerSheet.Templates -> TemplatesSheet(
          rows = dev.pi.mobile.chat.ResourceCatalogUi.templateRows(
            CommandDiscoveryReducer.catalog(commands),
            commandQuery,
          ),
          query = commandQuery,
          onQuery = { commandQuery = it },
          onInsert = { row ->
            draft = dev.pi.mobile.chat.ResourceCatalogUi.templateInsert(row)
            dismissSheet()
          },
        )
        PickerSheet.Extensions -> ExtensionsSheet(
          rows = dev.pi.mobile.chat.ResourceCatalogUi.extensionRows(
            CommandDiscoveryReducer.catalog(commands),
            commandQuery,
          ),
          query = commandQuery,
          busy = busy,
          notice = noticeDialog,
          onQuery = { commandQuery = it },
          onRun = { row, arguments ->
            runLocalCommand(row, arguments)
            dismissSheet()
          },
        )
        PickerSheet.Mcp -> McpSheet(
          state = mcp,
          busy = busy,
          query = commandQuery,
          onQuery = { commandQuery = it },
          onDraftName = { mcp = dev.pi.mobile.chat.McpUiReducer.setDraftName(mcp, it) },
          onDraftUrl = { mcp = dev.pi.mobile.chat.McpUiReducer.setDraftUrl(mcp, it) },
          onAdd = {
            val error = dev.pi.mobile.chat.McpUiReducer.addError(mcp)
            if (error != null) noticeDialog = error
            else {
              runtime?.mcpAdd(mcp.draftName.trim(), mcp.draftUrl.trim())
              mcp = mcp.copy(draftName = "", draftUrl = "")
            }
          },
          onRemove = { id -> runtime?.mcpRemove(id) },
          onConnect = { id -> runtime?.mcpConnect(id) },
          onDisconnect = { id -> runtime?.mcpDisconnect(id) },
          onRefresh = { runtime?.mcpState() },
        )
        }
      }
    }
  }
}
