package dev.duardo.neotransfer.plus.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ENZONA payment password, AES-GCM under a Keystore key that needs a strong biometric on every use and dies
 * when a new fingerprint is enrolled. Ciphers are authorized with `BiometricPrompt.CryptoObject` before [save] or
 * [open]. Outside Android backup (the app disables it) and never exported.
 */
class PaymentPasswordVault(context: Context) {
    private val preferences = context.getSharedPreferences("payment_password", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val mutableSaved = MutableStateFlow(preferences.contains(CIPHERTEXT) && preferences.contains(IV))
    val saved: StateFlow<Boolean> = mutableSaved.asStateFlow()

    fun clear() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar la contraseña" }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        mutableSaved.value = false
    }

    fun encryptCipher(): Cipher {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build())
                generateKey()
            }
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear()
            encryptCipher()
        }
    }

    /** Stores [password] and zeroes the bytes it used; the caller still owns (and zeroes) [password]. */
    fun save(authorized: Cipher, password: CharArray) {
        val bytes = utf8(password)
        try {
            val encrypted = authorized.doFinal(bytes)
            check(preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(authorized.iv, Base64.NO_WRAP))
                .commit()) { "No se pudo guardar la contraseña" }
            mutableSaved.value = true
        } finally { bytes.fill(0) }
    }

    /** Null when a new fingerprint invalidated the key: the password is gone and must be typed again. */
    fun decryptCipher(): Cipher? {
        val iv = preferences.getString(IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear()
            null
        }
    }

    /** The password for one payment. The caller hands it to the payment, which zeroes it. */
    fun open(authorized: Cipher): CharArray {
        val bytes = authorized.doFinal(Base64.decode(requireNotNull(preferences.getString(CIPHERTEXT, null)), Base64.NO_WRAP))
        try {
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            return CharArray(chars.remaining()).also { chars.get(it); chars.array().fill('\u0000') }
        } finally { bytes.fill(0) }
    }

    private fun utf8(password: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        return ByteArray(buffer.remaining()).also { buffer.get(it); buffer.array().fill(0) }
    }

    private fun key(): SecretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

    private companion object {
        const val KEY_ALIAS = "dev.duardo.neotransfer.plus.payment-password"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
    }
}
