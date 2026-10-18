package dev.pi.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth browser hand-off must be automatic and single-shot.
 *
 * pi-ai's flows emit an `auth.notice` carrying either a sign-in URL (browser callback) or a device
 * verification page. The user must not have to tap "open in browser": the Activity hands the URL
 * to the OS browser itself, exactly once per active sign-in — never for a stale generation, a
 * plain progress line, or a value that is not an http(s) URL. These tests assert real dispatch (a
 * launch count against an injected launcher), not merely that some state field was assigned.
 */
class AuthBrowserHandoffTest {
  private fun started() =
    SettingsUiReducer.startSignIn(SettingsUiState(), "anthropic", "Anthropic", "oauth", subscription = true)

  private fun notice(
    kind: String,
    url: String? = null,
    uri: String? = null,
    userCode: String? = null,
    message: String? = null,
  ) = DurableEvent.AuthNotice(
    kind = kind, message = message, url = url, instructions = null,
    userCode = userCode, verificationUri = uri, expiresInSeconds = null,
  )

  /** Records every dispatch so a test can count browser launches. */
  private class RecordingLauncher {
    val urls = mutableListOf<String>()
    fun open(url: String) { urls.add(url) }
  }

  // ------------------------------------------------------------------ pure decision

  @Test fun anAuthUrlNoticeOnALiveLoginAsksForTheBrowser() {
    val state = started()
    val effect = SettingsUiReducer.authNoticeEffect(
      state, state.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize?x=1"),
    )
    assertEquals(AuthEffect.OpenBrowser("https://claude.ai/oauth/authorize?x=1"), effect)
  }

  @Test fun aDeviceCodeNoticeHandsOffItsVerificationPage() {
    val state = started()
    val effect = SettingsUiReducer.authNoticeEffect(
      state, state.loginGeneration, notice("device_code", uri = "https://github.com/login/device", userCode = "ABCD-EFGH"),
    )
    assertEquals(AuthEffect.OpenBrowser("https://github.com/login/device"), effect)
  }

  @Test fun progressAndInfoNoticesDoNotOpenABrowser() {
    val state = started()
    assertEquals(AuthEffect.None, SettingsUiReducer.authNoticeEffect(state, state.loginGeneration, notice("progress", message = "polling")))
    assertEquals(AuthEffect.None, SettingsUiReducer.authNoticeEffect(state, state.loginGeneration, notice("info", message = "waiting")))
  }

  @Test fun aStaleGenerationNeverOpensABrowser() {
    val state = started()
    assertEquals(
      AuthEffect.None,
      SettingsUiReducer.authNoticeEffect(state, state.loginGeneration - 1, notice("auth_url", url = "https://claude.ai/oauth/authorize")),
    )
    // After cancel there is no live session at all.
    val cancelled = SettingsUiReducer.cancelSignIn(state)
    assertEquals(
      AuthEffect.None,
      SettingsUiReducer.authNoticeEffect(cancelled, cancelled.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize")),
    )
  }

  @Test fun onlyHttpUrlsAreHandedToTheBrowser() {
    val state = started()
    assertEquals(AuthEffect.None, SettingsUiReducer.authNoticeEffect(state, state.loginGeneration, notice("auth_url", url = "javascript:alert(1)")))
    assertEquals(AuthEffect.None, SettingsUiReducer.authNoticeEffect(state, state.loginGeneration, notice("auth_url", url = "file:///etc/passwd")))
    assertEquals(AuthEffect.None, SettingsUiReducer.authNoticeEffect(state, state.loginGeneration, notice("auth_url", url = "  ")))
  }

  @Test fun aUrlAlreadyOpenedForThisSessionIsNotOpenedAgain() {
    val state = started()
    val applied = SettingsUiReducer.applyNotice(
      state, state.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize"),
    )
    assertEquals(
      AuthEffect.None,
      SettingsUiReducer.authNoticeEffect(applied, applied.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize")),
    )
  }

  // ------------------------------------------------------------------ dispatch (the Activity's consumer)

  @Test fun theConsumerOpensTheBrowserOnceForAnAuthUrl() {
    val launcher = RecordingLauncher()
    val handoff = AuthBrowserHandoff(launcher::open)
    val state = started()
    assertTrue(handoff.onNotice(state, state.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize")))
    assertEquals(listOf("https://claude.ai/oauth/authorize"), launcher.urls)
  }

  @Test fun aReplayedNoticeOrProgressLineDoesNotLaunchASecondBrowser() {
    val launcher = RecordingLauncher()
    val handoff = AuthBrowserHandoff(launcher::open)
    val state = started()
    val url = notice("auth_url", url = "https://claude.ai/oauth/authorize")
    handoff.onNotice(state, state.loginGeneration, url)
    // A replay of the same event (recomposition) and a progress line must not relaunch.
    handoff.onNotice(state, state.loginGeneration, url)
    handoff.onNotice(state, state.loginGeneration, notice("progress", message = "polling"))
    assertEquals(1, launcher.urls.size)
  }

  @Test fun aDeviceFlowOpensItsVerificationPageAndKeepsTheCodeVisible() {
    val launcher = RecordingLauncher()
    val handoff = AuthBrowserHandoff(launcher::open)
    var state = started()
    val code = notice("device_code", uri = "https://github.com/login/device", userCode = "ABCD-EFGH")
    handoff.onNotice(state, state.loginGeneration, code)
    assertEquals(listOf("https://github.com/login/device"), launcher.urls)
    // The browser launch does not consume the user code; the sheet still shows it.
    state = SettingsUiReducer.applyNotice(state, state.loginGeneration, code)
    assertTrue(state.signIn!!.notices.single().contains("ABCD-EFGH"))
  }

  @Test fun aStaleOrNonHttpNoticeNeverLaunches() {
    val launcher = RecordingLauncher()
    val handoff = AuthBrowserHandoff(launcher::open)
    val state = started()
    handoff.onNotice(state, state.loginGeneration - 1, notice("auth_url", url = "https://claude.ai/oauth/authorize"))
    handoff.onNotice(state, state.loginGeneration, notice("auth_url", url = "javascript:alert(1)"))
    assertEquals(emptyList<String>(), launcher.urls)
    // A genuine https URL on the live generation does launch.
    assertTrue(handoff.onNotice(state, state.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize")))
    assertEquals(listOf("https://claude.ai/oauth/authorize"), launcher.urls)
  }

  @Test fun aNewLoginWithTheSameUrlLaunchesAgain() {
    val launcher = RecordingLauncher()
    val handoff = AuthBrowserHandoff(launcher::open)
    val first = started()
    handoff.onNotice(first, first.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize"))
    val second = SettingsUiReducer.startSignIn(
      SettingsUiReducer.cancelSignIn(first), "anthropic", "Anthropic", "oauth", subscription = true,
    )
    handoff.onNotice(second, second.loginGeneration, notice("auth_url", url = "https://claude.ai/oauth/authorize"))
    assertEquals(2, launcher.urls.size)
  }
}
