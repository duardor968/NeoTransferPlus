package dev.duardo.neotransfer.plus.platform

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Who may use a Keystore key. */
enum class KeyKind {
    /** A strong fingerprint on every use; dies when a new one is enrolled. */
    Fingerprint,
    /** A strong fingerprint or the screen lock on every use; survives enrollments. */
    Any,
    /** No one: the key only binds data to this phone. */
    Device,
}

/** The AES-GCM Keystore keys of the vault and the access code; faked in tests. */
interface Keys {
    /**
     * This phone turned down a [KeyKind.Fingerprint] key right after a valid fingerprint (realme Note 60): from then
     * on, [KeyKind.Any] keys only. Kept for good, also when the saved secrets are erased.
     */
    var fingerprintRejected: Boolean

    /**
     * A cipher of the key at [alias] for [mode]; [iv] to decrypt. The key is made on first use to encrypt; to decrypt,
     * a missing key is as dead as an invalidated one. Throws `KeyPermanentlyInvalidatedException` when an enrollment or
     * the screen lock killed the key, or it is missing to decrypt.
     */
    fun cipher(alias: String, kind: KeyKind, mode: Int, iv: ByteArray? = null): Cipher

    fun has(alias: String): Boolean

    fun delete(alias: String)
}

/** What can authenticate the person on this phone right now. */
interface DeviceLock {
    /** A strong fingerprint is enrolled and usable. */
    fun fingerprint(): Boolean

    /** A secure screen lock (PIN, pattern or password) is set. */
    fun screenLock(): Boolean
}

class AndroidDeviceLock(context: Context) : DeviceLock {
    private val manager = context.applicationContext.getSystemService(BiometricManager::class.java)
    override fun fingerprint() = manager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    override fun screenLock() = manager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
}

class AndroidKeys(context: Context) : Keys {
    private val device = context.getSharedPreferences("vault_device", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    override var fingerprintRejected: Boolean
        get() = device.getBoolean(REJECTED, false)
        set(value) { check(device.edit().putBoolean(REJECTED, value).commit()) { "No se pudo guardar el ajuste" } }

    override fun cipher(alias: String, kind: KeyKind, mode: Int, iv: ByteArray?): Cipher {
        if (!keyStore.containsAlias(alias)) {
            if (mode == Cipher.DECRYPT_MODE) throw KeyPermanentlyInvalidatedException()
            generate(alias, kind)
        }
        val key = keyStore.getKey(alias, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply { if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv)) }
    }

    override fun has(alias: String) = keyStore.containsAlias(alias)

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun generate(alias: String, kind: KeyKind) = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                when (kind) {
                    KeyKind.Fingerprint -> setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        .setInvalidatedByBiometricEnrollment(true)
                    // Per use, bound to the root SID the screen lock and any strong fingerprint both carry.
                    KeyKind.Any -> setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    KeyKind.Device -> Unit
                }
            }
            .build())
        generateKey()
    }

    private companion object {
        const val REJECTED = "fingerprint_rejected"
    }
}

internal const val TRANSFORMATION = "AES/GCM/NoPadding"

/** The Keystore turned down a per-use key that a fingerprint had just authorized. */
fun rejectedAfterFingerprint(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is UserNotAuthenticatedException ||
        (it is KeyStoreException && it.numericErrorCode == KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
}
