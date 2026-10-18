package dev.pi.mobile

import dev.pi.mobile.chat.ChatModelOption
import dev.pi.mobile.chat.ModelCatalogReducer
import dev.pi.mobile.chat.ModelKind
import dev.pi.mobile.chat.SettingsUiReducer
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Model catalog, sign-in and the one-shot operations
// ---------------------------------------------------------------------------

internal fun MainActivity.chatCatalog(): List<ChatModelOption> = catalog[ModelKind.Chat].orEmpty()

internal fun MainActivity.selectedModel(): ChatModelOption? = chatCatalog()
  .firstOrNull { it.provider == settings.selectedProvider && it.modelId == settings.selectedModel }

/** The model an operation runs on: the one picked in the Operations sheet, else the selected
 * provider's first model of that kind. */
internal fun MainActivity.operationModel(kind: ModelKind): String? = operationModels[kind]
  ?: (available[kind].orEmpty() + catalog[kind].orEmpty()).firstOrNull { it.provider == settings.selectedProvider }?.modelId

/** The picker's sections: the reachable catalog grouped per provider, names from the catalog. */
internal fun MainActivity.modelGroups() = ModelCatalogReducer.groups(
  catalog = chatCatalog(),
  available = available[ModelKind.Chat].orEmpty(),
  hiddenByProvider = settings.hiddenByProvider,
  providerNames = providers.associate { it.id to it.name },
)

/** Everything the Model sheet needs, re-requested after any change to credentials. */
internal fun MainActivity.requestCatalogs() {
  for (kind in ModelKind.entries) {
    runtime?.models(null, kind.wire)
    runtime?.available(null, kind.wire)
  }
  runtime?.authState()
}

/**
 * The user-asked-for reload: re-list from the providers with the network allowed. This is never
 * run on startup, because it would hit all 42 providers before the first message.
 */
internal fun MainActivity.refreshModels() {
  settings = SettingsUiReducer.setOperationResult(settings, null)
  requestCatalogs()
  runtime?.refresh(null)
}

internal fun MainActivity.startSignIn(provider: String, authType: String) {
  val row = providers.firstOrNull { it.id == provider }
  val before = settings
  settings = SettingsUiReducer.startSignIn(
    state = before,
    provider = provider,
    name = row?.name ?: provider,
    authType = authType,
    subscription = row?.oauthSubscription == true,
  )
  // A refused start (one login already running) leaves the generation alone and must not call
  // the runtime a second time.
  if (settings.loginGeneration == before.loginGeneration) return
  authGeneration = settings.loginGeneration
  runtime?.login(provider, authType)
}

/** Closing a sheet abandons a sign-in that is still running, so the flow is aborted too. */
internal fun MainActivity.dismissSheet() {
  val before = settings
  settings = SettingsUiReducer.dismissSheet(before)
  if (SettingsUiReducer.shouldCancelLogin(before, settings)) runtime?.cancelLogin()
}

internal fun MainActivity.runImages() {
  val model = operationModel(ModelKind.Image) ?: return
  settings = SettingsUiReducer.setOperationResult(settings, "generating…")
  runtime?.images(JSONObject().put("provider", settings.selectedProvider).put("modelId", model).put("prompt", imagePrompt))
}

internal fun MainActivity.runClassify() {
  val model = operationModel(ModelKind.Classifier) ?: return
  settings = SettingsUiReducer.setOperationResult(settings, "classifying…")
  runtime?.classify(
    JSONObject().put("provider", settings.selectedProvider).put("modelId", model)
      .put("state", runCatching { JSONObject(classifyState) }.getOrDefault(JSONObject()))
      .put(
        "questions",
        JSONObject().put(
          "answer",
          JSONObject().put("type", "bool").put("instructions", classifyQuestion)
            .put("criteria", JSONObject().put("true", "yes").put("false", "no")),
        ),
      ),
  )
}
