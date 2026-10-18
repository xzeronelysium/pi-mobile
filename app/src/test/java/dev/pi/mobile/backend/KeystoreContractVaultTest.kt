package dev.pi.mobile.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A JCE provider whose AES/GCM cipher enforces the Android Keystore's `randomizedEncryptionRequired`
 * contract, which plain JVM providers do not:
 *
 * - ENCRYPT_MODE with a caller-supplied `GCMParameterSpec` is rejected with
 *   `InvalidAlgorithmParameterException` ("Caller-provided IV not permitted");
 * - ENCRYPT_MODE without one makes the cipher generate the nonce itself;
 * - DECRYPT_MODE accepts the stored IV.
 *
 * The old `seal()` supplied its own IV, so `CredentialVault` cannot be exercised against this
 * provider without failing — the point of the regression. Test-only; unregistered with `Security` so
 * it never shadows the platform provider, and never used by production code.
 */
class KeystoreContractProvider : Provider(
  "KeystoreContract",
  1.0,
  "Test double for the Android Keystore AES/GCM IV contract",
) {
  init {
    putService(
      Provider.Service(
        this,
        "Cipher",
        "AES/GCM/NoPadding",
        KeystoreContractCipherSpi::class.java.name,
        emptyList<String>(),
        emptyMap<String, String>(),
      ),
    )
  }
}

/** The SPI behind [KeystoreContractProvider]. Delegates to the platform AES/GCM implementation. */
open class KeystoreContractCipherSpi : CipherSpi() {
  // Resolved through the platform provider list (this provider is not registered), so no recursion.
  private val delegate: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
  private var iv: ByteArray? = null

  // The delegate is pinned to AES/GCM/NoPadding, so the parsed mode/padding are accepted as-is.
  override fun engineSetMode(mode: String) = Unit
  override fun engineSetPadding(padding: String) = Unit
  override fun engineGetBlockSize(): Int = delegate.blockSize
  override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)
  override fun engineGetIV(): ByteArray? = iv
  override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

  override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) {
    if (opmode == Cipher.ENCRYPT_MODE && params is GCMParameterSpec) {
      throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
    }
    // A Keystore key in randomized encryption mode generates the nonce; the caller must not.
    val spec = if (opmode == Cipher.ENCRYPT_MODE && params == null) {
      val generated = ByteArray(NONCE_BYTES).also { (random ?: SecureRandom()).nextBytes(it) }
      GCMParameterSpec(TAG_BITS, generated)
    } else params
    delegate.init(opmode, key, spec, random)
    iv = delegate.iv
  }

  override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) {
    delegate.init(opmode, key, params, random)
    iv = delegate.iv
  }

  override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
    delegate.init(opmode, key, random)
    iv = delegate.iv
  }

  override fun engineUpdate(input: ByteArray, offset: Int, len: Int): ByteArray =
    delegate.update(input, offset, len)

  override fun engineUpdate(input: ByteArray, offset: Int, len: Int, output: ByteArray, outputOffset: Int): Int =
    delegate.update(input, offset, len, output, outputOffset)

  override fun engineUpdate(input: ByteBuffer, output: ByteBuffer): Int = delegate.update(input, output)

  override fun engineDoFinal(input: ByteArray, offset: Int, len: Int): ByteArray =
    delegate.doFinal(input, offset, len)

  override fun engineDoFinal(input: ByteArray, offset: Int, len: Int, output: ByteArray, outputOffset: Int): Int =
    delegate.doFinal(input, offset, len, output, outputOffset)

  override fun engineDoFinal(input: ByteBuffer, output: ByteBuffer): Int = delegate.doFinal(input, output)

  private companion object {
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
  }
}

/**
 * The Android Keystore's encryption contract, reproduced on the JVM.
 *
 * `CredentialVaultTest` injects a plain JVM key, so the platform provider happily accepts a
 * caller-supplied GCM IV — which is exactly why the device-only regression (the Android Keystore
 * rejects a caller IV for encryption) got past it. These tests inject a provider that enforces the
 * real contract, so they go red the moment `seal()` supplies its own IV again.
 */
class KeystoreContractVaultTest {
  private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  private fun keys(): CredentialKeyProvider = CredentialKeyProvider { key }
  private fun file(name: String): File = File(Files.createTempDirectory("pi-vault-keystore").toFile(), name)
  private fun vault(target: File, keyProvider: CredentialKeyProvider = keys()): CredentialVault =
    CredentialVault(target, keyProvider, KeystoreContractProvider())

  @Test fun aKeystoreStyleKeySealsAndReopensWithoutACallerSuppliedIv() {
    val target = file("vault.json")
    val payload = "{\"type\":\"api_key\",\"key\":\"sk-test-keystore-contract\"}"
    vault(target).use { it.put("openai", payload) }
    assertEquals(payload, vault(target).use { it.read("openai") })
  }

  @Test fun aCorruptVaultFileFailsLoudlyInsteadOfBeingRewrittenEmpty() {
    val target = file("vault.json")
    // A previously-sealed vault that this build cannot parse (truncated write, foreign format).
    val corrupt = "{\"version\":1,\"entries\":{\"openai\":\"AAAAAAAAAAAAAAAA:BBBB\""
    target.writeText(corrupt)

    val vault = vault(target)
    // The write must not proceed: silently treating an unreadable vault as empty would persist a
    // single-entry file and lose every stored credential.
    val failure = assertThrows(NativeFailure::class.java) { vault.put("openai", "{\"type\":\"api_key\",\"key\":\"new\"}") }
    assertEquals("vault", failure.code)
    assertEquals(corrupt, target.readText())
    vault.close()
  }

  @Test fun anUnreadableVaultFileFailsLoudlyAndIsLeftInPlace() {
    val target = file("vault.json")
    target.mkdirs() // exists but unreadable as a file
    val vault = vault(target)
    val failure = assertThrows(NativeFailure::class.java) { vault.list() }
    assertEquals("vault", failure.code)
    assertTrue(target.isDirectory)
    vault.close()
  }

  @Test fun anUnwritableVaultLocationIsReportedAsAVaultFailure() {
    val directory = Files.createTempDirectory("pi-vault-keystore").toFile()
    File(directory, "blocked").writeText("not a directory")
    val vault = vault(File(directory, "blocked/vault.json"))
    val failure = assertThrows(NativeFailure::class.java) { vault.put("openai", "{\"type\":\"api_key\",\"key\":\"x\"}") }
    assertEquals("vault", failure.code)
    vault.close()
  }

  @Test fun aKeystoreKeyFailureCarriesTheReasonAsANativeFailure() {
    val broken = CredentialKeyProvider { throw UnrecoverableKeyException("Key permanently invalidated") }
    val vault = vault(file("vault.json"), broken)
    val failure = assertThrows(NativeFailure::class.java) { vault.put("openai", "{\"type\":\"api_key\",\"key\":\"x\"}") }
    assertEquals("keystore", failure.code)
    assertTrue(failure.message!!.contains("Credential key unavailable"))
    assertTrue(failure.message!!.contains("Key permanently invalidated"))
    vault.close()
  }

  @Test fun aCipherFailureKeepsItsExceptionTextInTheNativeFailure() {
    // AES rejects an 8-byte key at init; this stands in for any JCE refusal on the device.
    val wrongSize = SecretKeySpec(ByteArray(8), "AES")
    val vault = vault(file("vault.json"), CredentialKeyProvider { wrongSize })
    val failure = assertThrows(NativeFailure::class.java) { vault.put("openai", "{\"type\":\"api_key\",\"key\":\"x\"}") }
    assertEquals("keystore", failure.code)
    assertTrue(failure.message!!.contains("Cannot encrypt credential"))
    vault.close()
  }

  @Test fun aFailedUnsealIsReportedAndDoesNotWipeTheVault() {
    val target = file("vault.json")
    vault(target).use { it.put("openai", "{\"type\":\"api_key\",\"key\":\"secret-one\"}") }
    val before = target.readText()
    val other = CredentialKeyProvider { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    val reopened = vault(target, other)
    val failure = assertThrows(NativeFailure::class.java) { reopened.list() }
    assertEquals("keystore", failure.code)
    assertTrue(failure.message!!.contains("Cannot decrypt credential"))
    // A decrypt failure must never rewrite or erase the sealed file.
    assertEquals(before, target.readText())
    reopened.close()
  }
}
