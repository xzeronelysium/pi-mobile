package dev.pi.mobile.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in and one-shot-operation event family, as the host emits it. */
class DurableAuthEventTest {
  @Test fun catalogRowsCarryTheSignInTransportAndOperationCounts() {
    val ready = DurableEvent.parse(
      JSONObject().put("event", "ready").put("conversationId", 1)
        .put(
          "providers",
          JSONArray().put(
            JSONObject().put("id", "openrouter").put("name", "OpenRouter")
              .put("apiKey", true).put("oauth", true).put("oauthName", "OpenRouter OAuth")
              .put("oauthSubscription", false).put("oauthLoginLabel", "Sign in with OpenRouter")
              .put("oauthLoopback", true).put("oauthLoopbackRequired", true)
              .put("oauthDeviceCode", false).put("oauthManualCode", true).put("oauthMechanismKnown", true)
              .put("deferred", false)
              .put("counts", JSONObject().put("chat", 401).put("image", 60).put("classifier", 15).put("total", 476)),
          ),
        ),
    ) as DurableEvent.Ready
    val provider = ready.providers.single()
    assertEquals("openrouter", provider.id)
    assertTrue(provider.oauth)
    assertTrue(provider.oauthLoopbackRequired)
    assertEquals("Sign in with OpenRouter", provider.oauthLoginLabel)
    assertEquals(60, provider.imageCount)
    assertTrue(provider.apiKey)
    assertEquals("OpenRouter OAuth", provider.oauthName)
    // A provider without OAuth metadata keeps the defaults instead of a bogus transport claim.
    val plain = DurableEvent.parse(
      JSONObject().put("event", "ready").put("providers", JSONArray().put(JSONObject().put("id", "deepseek").put("name", "DeepSeek").put("apiKey", true).put("oauth", false))),
    ) as DurableEvent.Ready
    assertTrue(!plain.providers.single().oauth)
    assertTrue(!plain.providers.single().oauthMechanismKnown)
    assertNull(plain.providers.single().oauthName)
  }

  @Test fun aPromptCarriesEverythingTheSheetNeedsToRenderIt() {
    val prompt = DurableEvent.parse(
      JSONObject().put("event", "auth.prompt").put("requestId", "auth-prompt-1")
        .put(
          "prompt",
          JSONObject().put("type", "select").put("message", "Select Anthropic login method:")
            .put("placeholder", JSONObject.NULL)
            .put(
              "options",
              JSONArray()
                .put(JSONObject().put("id", "browser").put("label", "Browser login (default)"))
                .put(JSONObject().put("id", "copy_code").put("label", "Copy code login (headless)")),
            ),
        ),
    ) as DurableEvent.AuthPrompt
    assertEquals("auth-prompt-1", prompt.requestId)
    assertEquals("select", prompt.type)
    assertEquals(2, prompt.options.size)
    assertEquals("copy_code", prompt.options[1].id)
    assertNull(prompt.placeholder)
    // A prompt without a request id cannot be answered, so it is dropped rather than shown.
    assertNull(DurableEvent.parse(JSONObject().put("event", "auth.prompt").put("prompt", JSONObject().put("type", "text"))))
  }

  @Test fun deviceCodeAndAuthUrlNoticesAreTyped() {
    val device = DurableEvent.parse(
      JSONObject().put("event", "auth.notice")
        .put(
          "notice",
          JSONObject().put("type", "device_code").put("userCode", "ABCD-EFGH")
            .put("verificationUri", "https://github.com/login/device")
            .put("intervalSeconds", 5).put("expiresInSeconds", 900),
        ),
    ) as DurableEvent.AuthNotice
    val notice = device as DurableEvent.AuthNotice
    assertEquals("device_code", notice.kind)
    assertEquals("ABCD-EFGH", notice.userCode)
    assertEquals(900, notice.expiresInSeconds)

    val url = DurableEvent.parse(
      JSONObject().put("event", "auth.notice")
        .put("notice", JSONObject().put("type", "auth_url").put("url", "https://claude.ai/oauth/authorize?x=1").put("instructions", "Complete login in your browser.")),
    ) as DurableEvent.AuthNotice
    assertEquals("auth_url", url.kind)
    assertTrue(url.url!!.startsWith("https://claude.ai/"))
  }

  @Test fun settledReportsTheCredentialShapeAndNeverASecret() {
    val settled = DurableEvent.parse(
      JSONObject().put("event", "auth.settled").put("provider", "xai").put("authType", "oauth")
        .put("credential", JSONObject().put("type", "oauth").put("refreshable", true).put("accountId", "acct-1").put("expires", System.currentTimeMillis() + 600_000)),
    ) as DurableEvent.AuthSettled
    assertEquals("xai", settled.provider)
    assertTrue(settled.detail.contains("subscription token"))
    assertTrue(settled.detail.contains("renewable"))
    assertTrue(settled.detail.contains("acct-1"))
    assertTrue(settled.detail.contains("valid"))
    assertTrue(!settled.detail.contains("access"))
  }

  @Test fun authStateAcceptsBothTheFullSweepAndTheSingleProviderUpdate() {
    val sweep = DurableEvent.parse(
      JSONObject().put("event", "auth.state")
        .put(
          "value",
          JSONObject().put(
            "providers",
            JSONArray()
              .put(JSONObject().put("provider", "openai").put("name", "OpenAI").put("configured", true).put("source", "stored credential").put("type", "api_key").put("stored", JSONObject().put("type", "api_key").put("keyLength", 12)))
              .put(JSONObject().put("provider", "xai").put("name", "xAI").put("configured", false)),
          ),
        ),
    ) as DurableEvent.AuthState
    assertEquals(2, sweep.rows.size)
    assertTrue(sweep.rows[0].configured)
    assertEquals("stored credential", sweep.rows[0].source)
    assertTrue(sweep.rows[0].detail!!.contains("API key"))
    assertNull(sweep.rows[1].detail)

    val single = DurableEvent.parse(
      JSONObject().put("event", "auth.state").put("value", JSONObject().put("provider", "openai").put("type", "api_key").put("configured", true)),
    ) as DurableEvent.AuthState
    assertEquals(1, single.rows.size)
    assertEquals("openai", single.rows[0].provider)
    assertTrue(single.rows[0].configured)
    // An empty shape yields no rows rather than a bogus "everything is unconfigured" row.
    assertTrue((DurableEvent.parse(JSONObject().put("event", "auth.state").put("value", JSONObject())) as DurableEvent.AuthState).rows.isEmpty())
  }

  @Test fun imagesAndClassifyResultsKeepTheErrorPathsStructured() {
    val images = DurableEvent.parse(
      JSONObject().put("event", "images")
        .put(
          "value",
          JSONObject().put("provider", "openrouter").put("model", "black-forest-labs/flux-3-image")
            .put("stopReason", "stop").put("error", JSONObject.NULL)
            .put("output", JSONArray().put(JSONObject().put("type", "text").put("text", "Rendered.")).put(JSONObject().put("type", "image").put("mimeType", "image/png").put("base64", "QUJD"))),
        ),
    ) as DurableEvent.ImagesResult
    assertEquals("Rendered.", images.text)
    assertEquals(1, images.imageCount)
    assertEquals(3, images.imageBytes)
    assertNull(images.error)

    val unknown = DurableEvent.parse(
      JSONObject().put("event", "images")
        .put("value", JSONObject().put("error", "Unknown OpenRouter image model: nope").put("provider", "openrouter").put("modelId", "nope").put("known", JSONArray().put("black-forest-labs/flux-3-image"))),
    ) as DurableEvent.ImagesResult
    assertEquals("nope", unknown.model)
    assertEquals(listOf("black-forest-labs/flux-3-image"), unknown.known)

    val classify = DurableEvent.parse(
      JSONObject().put("event", "classify")
        .put("value", JSONObject().put("provider", "openai").put("model", "gpt-6-luna").put("stopReason", "stop").put("answers", JSONObject().put("answer", JSONObject().put("type", "bool").put("probability", 0.9)))),
    ) as DurableEvent.ClassifyResult
    assertTrue(classify.answers.contains("0.9"))
    assertNull(classify.error)
  }
}
