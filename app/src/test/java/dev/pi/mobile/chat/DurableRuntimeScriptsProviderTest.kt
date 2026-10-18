package dev.pi.mobile.chat

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider/sign-in/operation script builders the host evaluates in the WebView. */
class DurableRuntimeScriptsProviderTest {
  @Test fun modelsAndAvailableCarryTheTypeAndStayGuarded() {
    val chat = DurableRuntimeScripts.models("openai", "chat")
    // org.json does not preserve key order, so assert on the parts, not on one serialization.
    assertTrue(chat.startsWith("window.PiNative.models({"))
    assertTrue(chat.contains("\"type\":\"chat\""))
    assertTrue(chat.contains("\"provider\":\"openai\""))
    assertTrue(chat.contains("catch(e=>AndroidNative.postMessage"))
    val image = DurableRuntimeScripts.models(null, "image")
    assertTrue(image.startsWith("window.PiNative.models({"))
    assertTrue(image.contains("\"type\":\"image\""))
    assertFalse(image.contains("provider"))
    assertTrue(DurableRuntimeScripts.available("openrouter", "image").contains("\"type\":\"image\""))
    assertTrue(DurableRuntimeScripts.available(null, "classifier").contains("\"type\":\"classifier\""))
  }

  @Test fun refreshCarriesTheNetworkFlagSoACacheOnlyPassIsPossible() {
    val cacheOnly = DurableRuntimeScripts.refresh(null, false)
    assertTrue(cacheOnly.startsWith("window.PiNative.refresh({"))
    assertTrue(cacheOnly.contains("\"allowNetwork\":false"))
    assertFalse(cacheOnly.contains("provider"))
    val provider = DurableRuntimeScripts.refresh("xai")
    assertTrue(provider.contains("\"allowNetwork\":true"))
    assertTrue(provider.contains("\"provider\":\"xai\""))
  }

  @Test fun signInCallsTargetTheRuntimeAndEscapeTheirArguments() {
    val login = DurableRuntimeScripts.login(JSONObject().put("provider", "anthropic").put("type", "oauth").put("deviceId", "11111111-2222-3333-4444-555555555555"))
    assertTrue(login.startsWith("window.PiNative.login({" ))
    assertTrue(login.contains("\"type\":\"oauth\""))
    assertTrue(DurableRuntimeScripts.logout("anthropic").startsWith("window.PiNative.logout({\"provider\":\"anthropic\"})"))
    assertTrue(DurableRuntimeScripts.cancelLogin().startsWith("window.PiNative.cancelLogin()"))
    assertTrue(DurableRuntimeScripts.authState().startsWith("window.PiNative.authState()"))
    assertTrue(DurableRuntimeScripts.getAuth("xai").contains("\"provider\":\"xai\""))
    assertTrue(DurableRuntimeScripts.setKey(JSONObject().put("provider", "openai").put("apiKey", "sk-1")).contains("\"apiKey\":\"sk-1\""))
  }

  @Test fun operationCallsEmbedTheirConfigAsAJavaScriptLiteral() {
    val images = DurableRuntimeScripts.images(JSONObject().put("provider", "openrouter").put("modelId", "black-forest-labs/flux-3-image").put("prompt", "a \"pi\" mark"))
    assertTrue(images.startsWith("window.PiNative.generateImages({"))
    assertTrue(images.contains("a \\\"pi\\\" mark"))
    val classify = DurableRuntimeScripts.classify(JSONObject().put("provider", "openai").put("modelId", "gpt-6-luna").put("questions", JSONObject().put("answer", JSONObject().put("type", "bool"))))
    assertTrue(classify.startsWith("window.PiNative.classify({"))
    assertTrue(classify.contains("\"type\":\"bool\""))
    // Every provider-facing call is guarded, so a rejection is a host error rather than an unhandled one.
    for (script in listOf(images, classify, login("anthropic", "oauth"))) {
      assertTrue(script.contains("catch(e=>"));
    }
    assertFalse(DurableRuntimeScripts.close().contains("catch"))
  }

  private fun login(provider: String, type: String) =
    DurableRuntimeScripts.login(JSONObject().put("provider", provider).put("type", type))
}
