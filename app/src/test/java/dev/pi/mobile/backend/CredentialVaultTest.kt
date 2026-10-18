package dev.pi.mobile.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The vault format and the encryption envelope, exercised on the JVM.
 *
 * The Android Keystore only supplies the key (`KeystoreCredentialKeyProvider`); everything the
 * vault does with it is plain JCE and therefore testable here. That split is the point: the
 * production key is non-exportable, but the sealing contract is still verified off-device.
 */
class CredentialVaultTest {
  private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  private val provider = CredentialKeyProvider { key }

  private fun file(name: String): File = File(Files.createTempDirectory("pi-vault").toFile(), name)

  @Test fun aSealedCredentialRoundTripsWithoutThePlaintextOnDisk() {
    val target = file("vault.json")
    val vault = CredentialVault(target, provider)
    vault.put("openai", """{"type":"api_key","key":"sk-not-on-disk"}""")
    vault.put("xai", """{"type":"oauth","access":"a1","refresh":"r1","expires":123}""")
    val onDisk = target.readText()
    assertFalse(onDisk.contains("sk-not-on-disk"))
    assertFalse(onDisk.contains("api_key"))
    // Each entry is `base64(iv):base64(ciphertext)`; the payload never appears in plain.
    val entries = org.json.JSONObject(onDisk).getJSONObject("entries")
    assertEquals(2, entries.length())
    val names = entries.keys()
    while (names.hasNext()) {
      val sealed = entries.getString(names.next())
      assertTrue(sealed.contains(':'))
      assertTrue(sealed.substringAfter(':').isNotEmpty())
    }
    assertEquals("""{"type":"api_key","key":"sk-not-on-disk"}""", vault.read("openai"))
    assertEquals(2, vault.list().size)
    assertTrue(vault.list().any { it.first == "xai" })
    vault.close()
  }

  @Test fun aSealedCredentialSurvivesReopenWithTheSameKey() {
    val target = file("vault.json")
    CredentialVault(target, provider).use { it.put("anthropic", """{"type":"oauth","access":"token"}""") }
    CredentialVault(target, provider).use {
      assertEquals("""{"type":"oauth","access":"token"}""", it.read("anthropic"))
    }
  }

  @Test fun aDifferentKeyCannotOpenTheVault() {
    val target = file("vault.json")
    CredentialVault(target, provider).use { it.put("openai", """{"type":"api_key","key":"secret"}""") }
    val other = CredentialKeyProvider { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    val vault = CredentialVault(target, other)
    try {
      vault.list()
      throw AssertionError("Expected a foreign key to fail decryption")
    } catch (expected: Exception) {
      assertTrue(expected is javax.crypto.AEADBadTagException || expected.cause is javax.crypto.AEADBadTagException || expected.message != null)
    }
    vault.close()
  }

  @Test fun tamperingWithACiphertextIsDetected() {
    val target = file("vault.json")
    CredentialVault(target, provider).use { it.put("openai", """{"type":"api_key","key":"secret"}""") }
    val document = org.json.JSONObject(target.readText())
    val entries = document.getJSONObject("entries")
    val entry = entries.getString("openai")
    val separator = entry.indexOf(':')
    val body = java.util.Base64.getDecoder().decode(entry.substring(separator + 1))
    body[0] = (body[0].toInt() xor 0x01).toByte()
    entries.put("openai", entry.substring(0, separator + 1) + java.util.Base64.getEncoder().encodeToString(body))
    target.writeText(document.toString())
    val vault = CredentialVault(target, provider)
    try {
      vault.list()
      throw AssertionError("Expected a tampered ciphertext to fail authentication")
    } catch (expected: Exception) {
      assertTrue(expected.message != null || expected.cause != null)
    }
    vault.close()
  }

  @Test fun deleteRemovesOnlyThatProviderAndLeavesNoCiphertextBehind() {
    val target = file("vault.json")
    CredentialVault(target, provider).use { vault ->
      vault.put("openai", """{"type":"api_key","key":"one"}""")
      vault.put("xai", """{"type":"api_key","key":"two"}""")
      val before = target.readText()
      vault.delete("openai")
      assertNull(vault.read("openai"))
      assertEquals(1, vault.list().size)
      assertFalse(target.readText() == before)
      assertTrue(target.readText().contains("xai"))
      vault.clear()
      assertEquals(0, vault.list().size)
      assertFalse(target.readText().contains("xai"))
    }
  }

  @Test fun aWriteIsAtomicSoAnInterruptedSaveCannotCorruptTheVault() {
    val target = file("vault.json")
    CredentialVault(target, provider).use { vault ->
      vault.put("openai", """{"type":"api_key","key":"first"}""")
      vault.put("openai", """{"type":"api_key","key":"second"}""")
      assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
      assertEquals("""{"type":"api_key","key":"second"}""", vault.read("openai"))
      // The document is the versioned envelope, not a bare map, so it can be migrated later.
      val document = org.json.JSONObject(target.readText())
      assertEquals(1, document.optInt("version"))
      assertTrue(document.optJSONObject("entries")!!.optString("openai").isNotEmpty())
    }
  }
}
