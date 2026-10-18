package dev.pi.mobile.backend

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidAlgorithmParameterException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * The on-device half of the credential vault: the Android Keystore key itself.
 *
 * The JVM tests (`CredentialVaultTest`, `KeystoreContractVaultTest`) cover the envelope and the
 * Keystore IV contract with an injected provider; this one proves the production provider can
 * generate a non-exportable AES-256 key in the device keystore and seal/open a real credential with
 * it. It exercises the same app-private file the runtime host uses, then removes it.
 *
 * Requires a connected device or emulator: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialDeviceTest {
  @Test fun theKeystoreKeySealsAndReopensACredentialOnThisDevice() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val target = File(context.filesDir, "keystore-vault-test.json")
    target.delete()
    val vault = CredentialVault(target, KeystoreCredentialKeyProvider("pi-mobile-credentials-test"))
    val payload = """{"type":"oauth","access":"device-token","refresh":"device-refresh","expires":1}"""
    vault.put("xai", payload)
    assertFalse(target.readText().contains("device-token"))
    vault.close()

    val reopened = CredentialVault(target, KeystoreCredentialKeyProvider("pi-mobile-credentials-test"))
    assertEquals(payload, reopened.read("xai"))
    assertEquals(1, reopened.list().size)
    reopened.delete("xai")
    assertTrue(reopened.list().isEmpty())
    reopened.close()
    target.delete()
  }

  @Test fun theKeyIsNotExportable() {
    val key = KeystoreCredentialKeyProvider("pi-mobile-credentials-test").key()
    assertEquals("AES", key.algorithm)
    // A Keystore-held key has no exportable encoding; the vault's guarantee rests on that.
    assertNull(key.encoded)
  }

  /**
   * The root cause of the "Credential store modify failed" write failure, asserted on the real
   * platform: a Keystore key is created with `randomizedEncryptionRequired = true`, so the Keystore
   * *refuses* a caller-supplied IV for encryption (the old `seal()` did exactly that), and the
   * permitted path generates the nonce itself. If this ever stops throwing, the platform contract
   * changed and `CredentialVault.seal()` must be re-examined.
   */
  @Test fun theKeystoreRejectsACallerSuppliedIvForEncryption() {
    val key = KeystoreCredentialKeyProvider("pi-mobile-credentials-test").key()
    val rejected = try {
      Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
      false
    } catch (expected: InvalidAlgorithmParameterException) {
      true
    }
    assertTrue("Android Keystore must reject a caller-supplied IV for encryption", rejected)
    // The permitted direction: no IV supplied, so the cipher generates the 12-byte GCM nonce.
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    assertEquals(12, cipher.iv?.size)
  }

  /**
   * Every seal uses a fresh cipher-generated IV, and the IV stored in the vault file is the one the
   * Keystore actually encrypted with: it decrypts that exact ciphertext. This is what the JVM tests
   * cannot prove — the platform provider, not a stand-in.
   */
  @Test fun eachSealStoresAFreshCipherGeneratedIvThatDecryptsItsCiphertext() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val target = File(context.filesDir, "keystore-vault-iv-test.json")
    target.delete()
    val alias = "pi-mobile-credentials-test"
    val payload = """{"type":"api_key","key":"device-iv-check"}"""
    CredentialVault(target, KeystoreCredentialKeyProvider(alias)).use { vault ->
      // The same payload twice, so only the IV can differ.
      vault.put("openai", payload)
      vault.put("xai", payload)
    }

    val entries = org.json.JSONObject(target.readText()).getJSONObject("entries")
    val stored = listOf("openai", "xai").map { provider ->
      val entry = entries.getString(provider)
      entry.substring(0, entry.indexOf(':')) to entry.substring(entry.indexOf(':') + 1)
    }
    assertNotEquals("each seal must use a fresh nonce", stored[0].first, stored[1].first)

    // The stored IV is exactly what the Keystore generated: it decrypts the stored ciphertext.
    val key = KeystoreCredentialKeyProvider(alias).key()
    val iv = Base64.decode(stored[0].first, Base64.DEFAULT)
    assertEquals(12, iv.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    assertEquals(payload, String(cipher.doFinal(Base64.decode(stored[0].second, Base64.DEFAULT)), Charsets.UTF_8))

    target.delete()
  }
}
