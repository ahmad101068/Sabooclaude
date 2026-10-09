package ir.sabou.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyUnavailableException(cause: Throwable) : IllegalStateException("DEVICE_KEY_UNAVAILABLE", cause)

/**
 * Keys that never leave the device's Android Keystore:
 * - an AES-GCM key that wraps the random 32-byte SQLCipher passphrase (stored wrapped in private prefs);
 * - an HMAC key that signs the integrity anchors kept outside the database (ADR-0004).
 */
class DeviceKeys(private val context: Context) {

    @Synchronized
    fun databasePassphrase(): ByteArray = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(WRAPPED_DB_KEY, null)
        if (wrapped != null) {
            unwrap(wrapped).also { require(it.size == PASSPHRASE_BYTES) { "bad_passphrase_length" } }
        } else {
            val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
            // Must be durably stored before the database is created with it.
            check(prefs.edit().putString(WRAPPED_DB_KEY, wrap(passphrase)).commit()) { "key_not_persisted" }
            passphrase
        }
    } catch (e: Throwable) {
        throw DeviceKeyUnavailableException(e)
    }

    /**
     * Forgets the wrapped database key so the next open creates a new one. Only for a database that is
     * being erased or replaced (factory reset, restore when the old key is unusable).
     */
    @Synchronized
    fun forgetDatabaseKey() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().remove(WRAPPED_DB_KEY).commit()) { "key_not_removed" }
    }

    fun hmac(data: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(hmacKey())
        doFinal(data)
    }

    private fun wrap(value: ByteArray): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }

    private fun unwrap(value: String): ByteArray {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "bad_wrapped_key" }
        val cipher = Cipher.getInstance(AES_GCM)
        // Never generate a fresh key to unwrap: a lost Keystore key must surface as an error, not as a new key.
        val key = keyStore().getKey(AES_ALIAS, null) as? SecretKey ?: error("KEYSTORE_KEY_MISSING")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aesKey(): SecretKey {
        (keyStore().getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun hmacKey(): SecretKey {
        (keyStore().getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "sabou_keys"
        const val WRAPPED_DB_KEY = "wrapped_db_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "sabou_db_wrap"
        const val HMAC_ALIAS = "sabou_anchor_hmac"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val HMAC = "HmacSHA256"
        const val PASSPHRASE_BYTES = 32
        const val IV_BYTES = 12
    }
}
