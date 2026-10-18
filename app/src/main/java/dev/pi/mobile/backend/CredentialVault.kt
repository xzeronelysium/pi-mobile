package dev.pi.mobile.backend

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.security.GeneralSecurityException
import java.security.Provider
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies the AES-256 key that seals credentials. Production uses the Android Keystore. */
fun interface CredentialKeyProvider {
  fun key(): SecretKey
}

/**
 * The app's credential vault: `CredentialStore` payloads for pi-ai, sealed with AES-256-GCM before
 * they touch app-private storage.
 *
 * The Android Keystore holds a non-exportable key, so the file alone is useless off the device. The
 * file format is one JSON object — `{"version":1,"entries":{"<provider>":"<iv>:<ciphertext>"}}` — and
 * every mutation rewrites it atomically (temp file + rename), so an interrupted write cannot leave a
 * half-decrypted vault.
 *
 * Only the vault owner (the bridge's credential worker) touches this class; the JS side serializes
 * per-provider mutations before they reach it.
 */
class CredentialVault(
  private val file: File,
  private val keys: CredentialKeyProvider,
  /**
   * Test seam only: the JCE provider the vault asks for its AES/GCM cipher. Production passes
   * `null`, which means the platform provider (SunJCE on the JVM, the Android Keystore on device);
   * the JVM tests inject a provider that enforces the Keystore's IV contract.
   */
  private val provider: Provider? = null,
) : Closeable {
  private val sealed = linkedMapOf<String, String>()
  private var opened = false

  @Synchronized
  fun list(): List<Pair<String, String>> {
    open()
    return sealed.entries.map { it.key to unseal(it.value) }
  }

  @Synchronized
  fun read(provider: String): String? {
    open()
    return sealed[provider]?.let(::unseal)
  }

  @Synchronized
  fun put(provider: String, payload: String) {
    open()
    sealed[provider] = seal(payload)
    persist()
  }

  @Synchronized
  fun delete(provider: String) {
    open()
    if (sealed.remove(provider) != null) persist()
  }

  @Synchronized
  fun clear() {
    sealed.clear()
    persist()
  }

  /**
   * Reads the sealed entries from disk.
   *
   * An existing vault file that cannot be read or parsed must fail loudly. Treating it as empty
   * would let the next `put()` persist a partial map over the stored file and lose every
   * credential — the silent downgrade this vault must never do.
   */
  private fun open() {
    if (opened) return
    if (!file.exists()) {
      opened = true
      return
    }
    val text = try {
      file.readText()
    } catch (error: Exception) {
      throw NativeFailure(VAULT_FAILURE, "Credential vault is unreadable: ${detail(error)}")
    }
    val document = try {
      JSONObject(text)
    } catch (error: Exception) {
      throw NativeFailure(VAULT_FAILURE, "Credential vault is not valid JSON: ${detail(error)}")
    }
    val entries = document.optJSONObject("entries")
      ?: throw NativeFailure(VAULT_FAILURE, "Credential vault has no entries object")
    val names = entries.keys()
    while (names.hasNext()) {
      val provider = names.next()
      val value = entries.optString(provider)
      if (value.isNotEmpty()) sealed[provider] = value
    }
    // Only a fully-read vault counts as open. A failed open throws every time (keeping the error
    // loud), so a later put() can never persist a partial map over the file.
    opened = true
  }

  private fun detail(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

  /** The platform AES/GCM cipher in production; the injected one under test. */
  private fun newCipher(): Cipher =
    if (provider == null) Cipher.getInstance(TRANSFORMATION) else Cipher.getInstance(TRANSFORMATION, provider)

  /**
   * The Keystore key, wrapped so a failure (missing/invalidated key, locked keystore, user auth
   * required) reaches the bridge reply as a coded [NativeFailure] carrying the platform's reason
   * instead of a bare JCE exception the UI can only render as "Credential store modify failed".
   */
  private fun key(): SecretKey = try {
    keys.key()
  } catch (error: Exception) {
    throw NativeFailure(KEYSTORE_FAILURE, "Credential key unavailable: ${detail(error)}")
  }

  /**
   * Seals one payload as `base64(iv):base64(ciphertext)`.
   *
   * Contract: the Android Keystore generates AES keys with `randomizedEncryptionRequired = true`
   * (the platform default, set explicitly in [KeystoreCredentialKeyProvider]). Under that contract a
   * Keystore-backed key *refuses* to encrypt with a caller-supplied IV —
   * `init(ENCRYPT_MODE, key, GCMParameterSpec(...))` throws `InvalidAlgorithmParameterException`
   * ("Caller-provided IV not permitted"), which is exactly what broke sign-in. So encryption lets
   * the cipher generate the GCM nonce and reads it back; only DECRYPTION (a permitted direction)
   * supplies the stored IV. Do not reintroduce a caller IV here.
   */
  private fun seal(payload: String): String {
    val iv: ByteArray
    val body: ByteArray
    try {
      val cipher = newCipher()
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val generated = cipher.iv ?: throw NativeFailure(KEYSTORE_FAILURE, "Cipher generated no IV for encryption")
      if (generated.size != IV_BYTES) throw NativeFailure(KEYSTORE_FAILURE, "Cipher generated a ${generated.size}-byte IV, expected $IV_BYTES")
      iv = generated
      body = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
    } catch (error: GeneralSecurityException) {
      throw NativeFailure(KEYSTORE_FAILURE, "Cannot encrypt credential: ${detail(error)}")
    }
    return "${BridgeBase64.encode(iv)}:${BridgeBase64.encode(body)}"
  }

  private fun unseal(value: String): String {
    val separator = value.indexOf(':')
    if (separator <= 0) throw NativeFailure(VAULT_FAILURE, "Credential vault entry is malformed")
    val iv = BridgeBase64.decode(value.substring(0, separator))
    val body = BridgeBase64.decode(value.substring(separator + 1))
    try {
      val cipher = newCipher()
      // Decryption is the direction that may supply the stored IV.
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
      return String(cipher.doFinal(body), Charsets.UTF_8)
    } catch (error: GeneralSecurityException) {
      // A wrong key or a tampered ciphertext lands here; the entry stays sealed on disk.
      throw NativeFailure(KEYSTORE_FAILURE, "Cannot decrypt credential: ${detail(error)}")
    }
  }

  private fun persist() {
    file.parentFile?.mkdirs()
    val entries = JSONObject()
    for ((provider, value) in sealed) entries.put(provider, value)
    val document = JSONObject().put("version", 1).put("entries", entries)
    val temporary = File(file.parentFile, "${file.name}.tmp")
    try {
      temporary.writeText(document.toString())
      if (!temporary.renameTo(file)) {
        file.writeText(document.toString())
        temporary.delete()
      }
    } catch (error: Exception) {
      temporary.delete()
      throw NativeFailure(VAULT_FAILURE, "Cannot write the credential vault: ${detail(error)}")
    }
  }

  override fun close() {
    sealed.clear()
    opened = false
  }

  private companion object {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    /** Bridge failure code for anything the Keystore/cipher refused to do. */
    const val KEYSTORE_FAILURE = "keystore"
    /** Bridge failure code for a vault file that cannot be read, parsed or written. */
    const val VAULT_FAILURE = "vault"
  }
}

/**
 * The Keystore-held AES key. Generated on first use; never exported, never derived from a password,
 * so it dies with the app's keystore entry (uninstall, or a user-triggered credential wipe).
 */
class KeystoreCredentialKeyProvider(private val alias: String = ALIAS) : CredentialKeyProvider {
  override fun key(): SecretKey {
    val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        // Contract, stated explicitly: encryption must never use a caller-supplied IV/nonce. The
        // cipher generates the GCM IV and CredentialVault reads it back from `cipher.iv`; a
        // caller-provided IV is rejected by the Keystore. This is the platform default — do not
        // turn it off, and do not reintroduce a caller IV in `seal()`.
        .setRandomizedEncryptionRequired(true)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "pi-mobile-credentials-v1"
  }
}
