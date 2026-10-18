package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings chrome is a pure state machine: the chevron menu, the one-at-a-time sheets, and the
 * sign-in session that must survive provider notices while ignoring callbacks from a login the
 * user already abandoned.
 */
class SettingsUiReducerTest {
  private fun provider(id: String, name: String, apiKey: Boolean, oauth: Boolean, oauthSubscription: Boolean = false, loginLabel: String? = null) =
    DurableEvent.CatalogProvider(
      id = id, name = name, apiKey = apiKey, oauth = oauth,
      oauthSubscription = oauthSubscription, oauthLoginLabel = loginLabel,
    )

  private fun authRow(id: String, configured: Boolean, source: String? = null) =
    DurableEvent.AuthRow(provider = id, name = id, configured = configured, source = source, type = null, detail = null)

  private val providers = listOf(
    provider("openai", "OpenAI", apiKey = true, oauth = true, oauthSubscription = true, loginLabel = "Sign in with ChatGPT"),
    provider("anthropic", "Anthropic", apiKey = false, oauth = true, oauthSubscription = true),
    provider("deepseek", "DeepSeek", apiKey = true, oauth = false),
    provider("xai", "xAI", apiKey = false, oauth = true, oauthSubscription = false),
  )

  // ------------------------------------------------------------------ chevron + sheets

  @Test fun theChevronOpensTheMenuAndClosesWhateverIsOpen() {
    val base = SettingsUiState()
    val opened = SettingsUiReducer.toggleChevron(base)
    assertTrue(opened.menuOpen)
    // Tapping again closes it — decided from the pre-tap state, so a dismiss handler cannot
    // re-open it.
    assertFalse(SettingsUiReducer.toggleChevron(opened).menuOpen)
    // While a sheet is open the same control closes it instead of opening the menu.
    val sheet = SettingsUiReducer.openSheet(SettingsUiReducer.toggleChevron(base), PickerSheet.Model)
    assertFalse(sheet.menuOpen)
    assertEquals(PickerSheet.Model, sheet.sheet)
    val closed = SettingsUiReducer.toggleChevron(sheet)
    assertNull(closed.sheet)
    assertFalse(closed.menuOpen)
    // And from a closed menu a sheet entry opens exactly one sheet.
    val reopened = SettingsUiReducer.toggleChevron(closed)
    assertTrue(reopened.menuOpen)
  }

  @Test fun openingASheetClosesTheMenuAndNeverStacksSheets() {
    var state = SettingsUiReducer.openSheet(SettingsUiReducer.toggleChevron(SettingsUiState()), PickerSheet.Model)
    assertFalse(state.menuOpen)
    assertEquals(PickerSheet.Model, state.sheet)
    // The Model sheet's plus opens Connect; the Model sheet goes away.
    state = SettingsUiReducer.openSheet(state, PickerSheet.Connect)
    assertEquals(PickerSheet.Connect, state.sheet)
    // Opening Connect clears the model sheet's checklist editor.
    state = SettingsUiReducer.setEditingProvider(SettingsUiReducer.openSheet(state, PickerSheet.Model), "openai")
    assertEquals("openai", state.editingProvider)
    assertEquals(PickerSheet.Connect, SettingsUiReducer.openSheet(state, PickerSheet.Connect).sheet)
    assertNull(SettingsUiReducer.openSheet(state, PickerSheet.Connect).editingProvider)
  }

  @Test fun dismissingASheetClearsTheTransientSheetState() {
    var state = SettingsUiReducer.openSheet(SettingsUiState(), PickerSheet.Connect)
    state = SettingsUiReducer.setConnectQuery(state, "open")
    state = SettingsUiReducer.setApiKeyDraft(state, "sk-secret")
    val dismissed = SettingsUiReducer.dismissSheet(state)
    assertNull(dismissed.sheet)
    assertEquals("", dismissed.connectQuery)
    // A typed key never survives the sheet it was typed into.
    assertEquals("", dismissed.apiKeyDraft)
  }

  // ------------------------------------------------------------------ model + effort selection

  @Test fun pickingAModelCarriesItsProviderAndClampsTheEffort() {
    val model = ChatModelOption(provider = "openai", modelId = "gpt-5", name = "GPT-5", thinkingLevels = listOf("medium", "high"))
    val picked = SettingsUiReducer.selectModel(SettingsUiState(effort = "Max"), model)
    assertEquals("openai", picked.selectedProvider)
    assertEquals("gpt-5", picked.selectedModel)
    assertEquals("High", picked.effort)
    assertNull(picked.sheet)
  }

  @Test fun anEffortTheModelCannotRunIsRefusedNotSilentlyStored() {
    val base = SettingsUiState(effort = "Medium")
    assertEquals("High", SettingsUiReducer.selectEffort(base, "High", listOf("Low", "Medium", "High")).effort)
    assertEquals("Medium", SettingsUiReducer.selectEffort(base, "Max", listOf("Low", "Medium", "High")).effort)
  }

  @Test fun searchSelectionAndVisibilityPersistInTheChromeState() {
    var state = SettingsUiReducer.setModelQuery(SettingsUiState(), "fable")
    assertEquals("fable", state.modelQuery)
    state = SettingsUiReducer.setEditingProvider(state, "openrouter")
    assertEquals("openrouter", state.editingProvider)
    state = SettingsUiReducer.toggleHidden(state, ChatModelOption("openrouter", "a/one", "One"), hidden = true)
    assertEquals(setOf("a/one"), state.hiddenByProvider["openrouter"])
    state = SettingsUiReducer.toggleHidden(state, ChatModelOption("openrouter", "a/one", "One"), hidden = false)
    assertTrue(state.hiddenByProvider.isEmpty())
  }

  // ------------------------------------------------------------------ connect provider list

  @Test fun connectSplitsSubscriptionSignInFromApiKeysAndSearchIsHonest() {
    val rows = listOf(authRow("openai", true, "stored credential"))
    val groups = SettingsUiReducer.connectGroups(providers, rows, "")
    assertEquals(listOf("OAUTH SIGN-IN", "API KEY"), groups.map { it.title })
    val subscription = groups[0]
    val keys = groups[1]
    // A provider that supports both methods offers both, so neither capability is lost.
    assertEquals(
      setOf("openai" to "oauth", "anthropic" to "oauth", "xai" to "oauth"),
      subscription.rows.map { it.provider to it.authType }.toSet(),
    )
    assertEquals(
      setOf("openai" to "api_key", "deepseek" to "api_key"),
      keys.rows.map { it.provider to it.authType }.toSet(),
    )
    // Alphabetical by label inside a section; a provider-supplied login label wins over the name.
    assertEquals(listOf("Anthropic", "Sign in with ChatGPT", "xAI"), subscription.rows.map { it.label })
    assertEquals(listOf("DeepSeek", "OpenAI"), keys.rows.map { it.label })
    assertEquals("Sign in with ChatGPT", subscription.rows.first { it.provider == "openai" }.label)
    // A stored credential is reported on the rows the user would tap.
    assertTrue(keys.rows.first { it.provider == "openai" }.configured)
    assertFalse(subscription.rows.first { it.provider == "xai" }.configured)
    assertTrue(SettingsUiReducer.connectGroups(providers, rows, "deep").single().rows.single().provider == "deepseek")
    assertTrue(SettingsUiReducer.connectGroups(providers, rows, "zzz").isEmpty())
  }

  @Test fun aProviderWithNoSignInMethodIsNotOffered() {
    val bare = listOf(provider("nowhere", "Nowhere", apiKey = false, oauth = false))
    assertTrue(SettingsUiReducer.connectGroups(bare, emptyList(), "").isEmpty())
  }

  // ------------------------------------------------------------------ sign-in session

  private fun started(): SettingsUiState =
    SettingsUiReducer.startSignIn(SettingsUiState(), "openai", "OpenAI", "oauth", subscription = true)

  private fun notice(kind: String, userCode: String? = null, uri: String? = null, url: String? = null, message: String? = null) =
    DurableEvent.AuthNotice(kind = kind, message = message, url = url, instructions = null, userCode = userCode, verificationUri = uri, expiresInSeconds = null)

  @Test fun aLoginOwnsTheSheetUntilItSettlesAndNoticesAccumulate() {
    val state = started()
    val generation = state.loginGeneration
    val session = state.signIn!!
    assertEquals("openai", session.provider)
    assertTrue(session.subscription)
    assertTrue(session.busy)

    var running = SettingsUiReducer.applyNotice(state, generation, notice("device_code", "ABCD-EFGH", "https://example.test/device"))
    running = SettingsUiReducer.applyNotice(running, generation, notice("info", message = "still waiting"))
    assertEquals(2, running.signIn!!.notices.size)
    // The verification page is the browser hand-off even though a device flow has no auth URL.
    assertEquals("https://example.test/device", running.signIn!!.openedUrl)

    val url = SettingsUiReducer.applyNotice(running, generation, notice("auth_url", url = "https://accounts.test/authorize"))
    assertEquals("https://accounts.test/authorize", url.signIn!!.openedUrl)

    val settled = SettingsUiReducer.authSettled(url, generation, DurableEvent.AuthSettled("openai", "oauth", "subscription token, renewable"))
    assertFalse(settled.signIn!!.busy)
    assertEquals("subscription token, renewable", settled.signIn!!.settled)
  }

  /** A device-code flow is carried structurally so the sheet can show the two numbered steps. */
  @Test fun aDeviceCodeFlowRecordsTheCodeAndTheVerificationPage() {
    val state = started()
    val generation = state.loginGeneration
    val withCode = SettingsUiReducer.applyNotice(
      state,
      generation,
      DurableEvent.AuthNotice(
        kind = "device_code", message = null, url = null, instructions = null,
        userCode = "ABCD-EFGH", verificationUri = "https://example.test/device", expiresInSeconds = 600,
      ),
    )
    val session = withCode.signIn!!
    assertEquals("ABCD-EFGH" to "https://example.test/device", session.deviceCode)
    assertEquals(10, session.deviceCodeMinutes)
    // The folded notice line is the one the steps already say, so the sheet can skip it.
    assertEquals(session.notices.single(), session.deviceCodeNotice)

    // A later, unrelated notice leaves the code alone.
    val later = SettingsUiReducer.applyNotice(withCode, generation, notice("info", message = "still waiting"))
    assertEquals("ABCD-EFGH" to "https://example.test/device", later.signIn!!.deviceCode)
    assertEquals(session.deviceCodeNotice, later.signIn!!.deviceCodeNotice)
    // A device-code notice with a blank code records nothing rather than an empty step.
    val blank = SettingsUiReducer.applyNotice(state, generation, notice("device_code", "", "https://example.test/device"))
    assertNull(blank.signIn!!.deviceCode)
    assertNull(blank.signIn!!.deviceCodeNotice)
  }

  @Test fun aPromptBlocksTheSheetAndIsReleasedWhenAnsweredOrSkipped() {
    val state = started()
    val generation = state.loginGeneration
    val prompt = DurableEvent.AuthPrompt(
      requestId = "auth-prompt-1", type = "select", message = "Choose a method",
      placeholder = null,
      options = listOf(DurableEvent.AuthOption("browser", "Browser login", null)),
    )
    val asked = SettingsUiReducer.applyPrompt(state, generation, prompt)
    assertEquals("auth-prompt-1", asked.signIn!!.prompt!!.requestId)
    assertNull(SettingsUiReducer.promptAnswered(asked, "auth-prompt-1").signIn!!.prompt)
    // A prompt for a different request id leaves the live prompt alone.
    assertEquals("auth-prompt-1", SettingsUiReducer.promptAnswered(asked, "other").signIn!!.prompt!!.requestId)
  }

  @Test fun aSecondLoginIsRefusedWhileOneIsRunning() {
    val state = started()
    val again = SettingsUiReducer.startSignIn(state, "xai", "xAI", "oauth", subscription = false)
    assertEquals(state.loginGeneration, again.loginGeneration)
    assertEquals("openai", again.signIn!!.provider)
  }

  @Test fun cancellingDropsTheSessionAndMakesLateCallbacksHarmless() {
    val state = started()
    val generation = state.loginGeneration
    val cancelled = SettingsUiReducer.cancelSignIn(state)
    assertNull(cancelled.signIn)
    assertTrue("cancelling must invalidate the in-flight login", cancelled.loginGeneration != generation)
    // Every late event from the abandoned flow is ignored, not applied to a later session.
    assertNull(SettingsUiReducer.applyNotice(cancelled, generation, notice("info", message = "late")).signIn)
    assertNull(SettingsUiReducer.applyPrompt(cancelled, generation, prompt("auth-prompt-9")).signIn)
    assertNull(SettingsUiReducer.authSettled(cancelled, generation, DurableEvent.AuthSettled("openai", "oauth", "late token")).signIn)
    assertNull(SettingsUiReducer.authFailed(cancelled, generation, DurableEvent.AuthFailed("openai", cancelled = true, message = "late failure")).signIn)
    assertNull(SettingsUiReducer.authFinished(cancelled, generation, DurableEvent.AuthFinished("openai", cancelled = true)).signIn)

    // A fresh login after the cancel behaves normally and owns a new generation.
    val restarted = SettingsUiReducer.startSignIn(cancelled, "xai", "xAI", "oauth", subscription = false)
    assertTrue(restarted.loginGeneration != generation)
    assertEquals("xai", SettingsUiReducer.applyNotice(restarted, restarted.loginGeneration, notice("info", message = "now")).signIn!!.provider)
  }

  @Test fun dismissingTheSheetMidLoginAbandonsTheFlowAndAsksTheRuntimeToCancel() {
    val state = SettingsUiReducer.openSheet(started(), PickerSheet.Connect)
    val dismissed = SettingsUiReducer.dismissSheet(state)
    assertNull(dismissed.sheet)
    assertNull(dismissed.signIn)
    assertTrue(dismissed.loginGeneration != state.loginGeneration)
    assertTrue(SettingsUiReducer.shouldCancelLogin(state, dismissed))
    // Dismissing a sheet with no live login must not call cancel on the runtime.
    assertFalse(SettingsUiReducer.shouldCancelLogin(SettingsUiState(), SettingsUiReducer.dismissSheet(SettingsUiState())))
  }

  @Test fun callbacksFromAnAbandonedFlowCannotLandInALaterSession() {
    // openai is cancelled; xai starts while the abandoned openai flow is still unwinding. The
    // host event surface carries no request id, so the provider is the only correlation the
    // reducer has — an event naming the wrong provider must be dropped.
    val first = started()
    val second = SettingsUiReducer.startSignIn(SettingsUiReducer.cancelSignIn(first), "xai", "xAI", "oauth", subscription = false)
    val generation = second.loginGeneration
    val stale = SettingsUiReducer.authFinished(second, generation, DurableEvent.AuthFinished("openai", cancelled = true))
    assertEquals("xai", stale.signIn!!.provider)
    assertTrue("a stale finishing flow must not idle the new session", stale.signIn!!.busy)
    val staleSettled = SettingsUiReducer.authSettled(second, generation, DurableEvent.AuthSettled("openai", "oauth", "subscription token"))
    assertNull(staleSettled.signIn!!.settled)
    val staleFailure = SettingsUiReducer.authFailed(second, generation, DurableEvent.AuthFailed("openai", cancelled = true, message = "boom"))
    assertNull(staleFailure.signIn!!.error)
    // The same callbacks for the live provider are applied normally.
    val own = SettingsUiReducer.authFailed(second, generation, DurableEvent.AuthFailed("xai", cancelled = false, message = "Access denied"))
    assertEquals("Access denied", own.signIn!!.error)
  }

  @Test fun aPromptAnsweredAfterItsSessionEndedDoesNotResurrectTheSheet() {
    val state = started()
    val asked = SettingsUiReducer.applyPrompt(state, state.loginGeneration, prompt("auth-prompt-2"))
    val finished = SettingsUiReducer.authFinished(asked, state.loginGeneration, DurableEvent.AuthFinished("openai", cancelled = false))
    assertNull(finished.signIn!!.prompt)
    assertFalse(finished.signIn!!.busy)
    assertNull(SettingsUiReducer.promptAnswered(finished, "auth-prompt-2").signIn!!.prompt)
  }

  @Test fun aFailedLoginKeepsTheReasonAndStopsSpinning() {
    val state = started()
    val failed = SettingsUiReducer.authFailed(state, state.loginGeneration, DurableEvent.AuthFailed("openai", cancelled = false, message = "Access denied"))
    assertEquals("Access denied", failed.signIn!!.error)
    assertFalse(failed.signIn!!.busy)
    assertNull(failed.signIn!!.prompt)
  }

  private fun prompt(requestId: String) = DurableEvent.AuthPrompt(
    requestId = requestId, type = "text", message = "Paste the code", placeholder = "code", options = emptyList(),
  )

  // ------------------------------------------------------------------ operations sheet

  @Test fun theOperationsSheetIsReachableWithoutTouchingTheThreeMenuEntries() {
    // Workspace → Images & classifier keeps the images/classifier testing surface reachable
    // without adding a fourth entry to the Workspace/Model/Effort menu.
    val state = SettingsUiReducer.openSheet(SettingsUiReducer.toggleChevron(SettingsUiState()), PickerSheet.Workspace)
    assertEquals(PickerSheet.Workspace, state.sheet)
    assertEquals(PickerSheet.Operations, SettingsUiReducer.openSheet(state, PickerSheet.Operations).sheet)
    assertEquals(listOf("Workspace", "Model", "Effort"), PickerSheet.menuEntries.map { it.name })
  }
}
