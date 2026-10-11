package ir.sabou.app.data

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint sign-in (ADR-0023). The user's PIN is sealed with an Android Keystore key that can only be used
 * right after a strong biometric check; the sealed PIN lives in no-backup storage. Unsealing hands the PIN to
 * the ordinary login, so locks, audit and permissions are exactly those of typing it. A new fingerprint enrolled
 * on the phone invalidates the key (and the user types the PIN again).
 */
class BiometricVault(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "biometric")

    /** Android 9+ has the system biometric prompt with crypto binding. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** The user whose PIN is sealed on this phone, if any. */
    fun enrolledUser(): String? = if (!supported) null else dir.listFiles()?.firstOrNull { it.name.endsWith(".pin") }?.name?.removeSuffix(".pin")

    fun disable() {
        dir.listFiles()?.forEach { it.delete() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /** Seals [pin] for [username] after a fingerprint check. Calls back on the main thread. */
    fun enable(username: String, pin: CharArray, title: String, onResult: (Result<Unit>) -> Unit) {
        if (!supported) return onResult(Result.failure(IllegalStateException("unsupported")))
        val cipher = try {
            disable()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        } catch (e: Exception) { return onResult(Result.failure(e)) }
        prompt(title, cipher) { result ->
            onResult(result.mapCatching { c ->
                val sealed = c.doFinal(String(pin).toByteArray(Charsets.UTF_8))
                dir.mkdirs()
                File(dir, "$username.pin").writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + sealed)
            })
        }
    }

    /** After a fingerprint check, the sealed PIN of [username]; calls back on the main thread. */
    fun unlock(username: String, title: String, onResult: (Result<CharArray>) -> Unit) {
        val file = File(dir, "$username.pin")
        val bytes = runCatching { file.readBytes() }.getOrElse { return onResult(Result.failure(it)) }
        val ivSize = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivSize)
        val sealed = bytes.copyOfRange(1 + ivSize, bytes.size)
        val cipher = try {
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(); return onResult(Result.failure(e))       // a new fingerprint was enrolled: type the PIN
        } catch (e: Exception) { return onResult(Result.failure(e)) }
        prompt(title, cipher) { result -> onResult(result.mapCatching { c -> String(c.doFinal(sealed), Charsets.UTF_8).toCharArray() }) }
    }

    private fun prompt(title: String, cipher: Cipher, done: (Result<Cipher>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return done(Result.failure(IllegalStateException("unsupported")))
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context).setTitle(title)
            .setNegativeButton("رمز را تایپ می‌کنم", executor) { _, _ -> done(Result.failure(CancelledException())) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                done(if (c != null) Result.success(c) else Result.failure(IllegalStateException("no_cipher")))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(Result.failure(CancelledException(errString.toString())))
        })
    }

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The person chose to type the PIN, or the prompt closed. */
    class CancelledException(message: String = "cancelled") : Exception(message)

    private companion object {
        const val ALIAS = "sabou.biometric.pin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
