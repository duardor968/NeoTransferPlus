package dev.duardo.neotransfer.plus.platform

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.crypto.Cipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What protects the saved payment PIN. */
enum class PinLock {
    /** A Keystore key that a strong fingerprint opens on every use and a new enrollment kills. */
    Fingerprint,
    /** A Keystore key that a strong fingerprint or the screen lock opens on every use. */
    ScreenLock,
    /** A key derived from the access code, wrapped by a Keystore key of this phone ([AccessCode]). */
    Code,
}

/**
 * What protects a payment PIN saved now; null when nothing can, and then it is typed for every payment. The access
 * code when it also protects the PIN; else the fingerprint key, unless this phone turns it down; else the screen lock.
 */
fun pinLock(codeProtects: Boolean, fingerprintRejected: Boolean, fingerprint: Boolean, screenLock: Boolean): PinLock? = when {
    codeProtects -> PinLock.Code
    fingerprint && !fingerprintRejected -> PinLock.Fingerprint
    screenLock || fingerprint -> PinLock.ScreenLock
    else -> null
}

/**
 * The ENZONA payment PIN, AES-GCM under the key of its [PinLock]. Keystore ciphers are authorized with
 * `BiometricPrompt.CryptoObject` before [seal] or [open]; the code's key opens [sealWithCode] / [openWithCode]. A
 * typed PIN is sealed when its payment is approved and [store]d only once ENZONA confirmed that payment. Outside
 * Android backup (the app disables it) and never exported.
 *
 * Some phones (realme Note 60) turn down the fingerprint key right after a valid fingerprint: [seal] then moves the
 * phone for good to the screen-lock key ([Keys.fingerprintRejected]) and throws [FingerprintRejected], so the caller
 * seals again under a new prompt.
 */
class PaymentPasswordVault(context: Context, private val keys: Keys, private val device: DeviceLock) {
    private val preferences = context.getSharedPreferences("payment_password", Context.MODE_PRIVATE)
    private val mutableSaved = MutableStateFlow(preferences.contains(CIPHERTEXT) && preferences.contains(IV))
    val saved: StateFlow<Boolean> = mutableSaved.asStateFlow()
    // A password saved before payments confirmed it (no [CHECKED]) was never accepted by ENZONA: it is in doubt too.
    private val mutableUnverified = MutableStateFlow(preferences.getBoolean(UNVERIFIED, false) || (mutableSaved.value && !preferences.contains(CHECKED)))
    /** ENZONA answered the last payment made with a password without confirming it: the next one asks for it again. */
    val unverified: StateFlow<Boolean> = mutableUnverified.asStateFlow()
    private val mutableLock = MutableStateFlow(storedLock())
    /** What protects the saved PIN; null while none is saved. */
    val lock: StateFlow<PinLock?> = mutableLock.asStateFlow()

    /** The password encrypted for this phone and not stored yet. Not secret without its key, kept only in memory. */
    class Sealed internal constructor(internal val ciphertext: String, internal val iv: String, internal val lock: PinLock) {
        override fun toString() = "Sealed(redacted)"
    }

    /** The phone turned down the fingerprint key: seal again with [PinLock.ScreenLock], under a new prompt. */
    class FingerprintRejected : Exception("Fingerprint key rejected")

    /** What protects a PIN saved now, given whether the access code also protects it. */
    fun lockFor(codeProtects: Boolean): PinLock? =
        pinLock(codeProtects, keys.fingerprintRejected, device.fingerprint(), device.screenLock())

    fun clear() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar el PIN" }
        keys.delete(FINGERPRINT_ALIAS)
        keys.delete(ANY_ALIAS)
        mutableSaved.value = false
        mutableUnverified.value = false
        mutableLock.value = null
    }

    fun markUnverified() {
        if (preferences.edit().putBoolean(UNVERIFIED, true).commit()) mutableUnverified.value = true
    }

    /** ENZONA confirmed a payment made with the saved password. */
    fun markVerified() {
        if (mutableUnverified.value && preferences.edit().remove(UNVERIFIED).putBoolean(CHECKED, true).commit()) mutableUnverified.value = false
    }

    /** A cipher to seal under [lock], a Keystore one. */
    fun encryptCipher(lock: PinLock): Cipher {
        val (alias, kind) = keyOf(lock)
        return try {
            keys.cipher(alias, kind, Cipher.ENCRYPT_MODE)
        } catch (_: KeyPermanentlyInvalidatedException) {
            if (this.lock.value == lock) clear() else keys.delete(alias)
            keys.cipher(alias, kind, Cipher.ENCRYPT_MODE)
        }
    }

    /**
     * Encrypts [password] without storing it and zeroes the bytes it used; the caller still owns (and zeroes)
     * [password]. Throws [FingerprintRejected] when this phone just turned the fingerprint key down for good.
     */
    fun seal(authorized: Cipher, password: CharArray, lock: PinLock): Sealed {
        val bytes = utf8(password)
        try {
            val encrypted = try {
                authorized.doFinal(bytes)
            } catch (e: Exception) {
                if (lock != PinLock.Fingerprint || !rejectedAfterFingerprint(e)) throw e
                keys.fingerprintRejected = true
                keys.delete(FINGERPRINT_ALIAS)
                throw FingerprintRejected()
            }
            return Sealed(encode(encrypted), encode(authorized.iv), lock)
        } finally { bytes.fill(0) }
    }

    /** [password] under the access code's [key]; as [seal]. */
    fun sealWithCode(key: CodeKey, password: CharArray): Sealed {
        val bytes = utf8(password)
        try {
            val (ciphertext, iv) = keys.sealTwice(key, bytes)
            return Sealed(encode(ciphertext), encode(iv), PinLock.Code)
        } finally { bytes.fill(0) }
    }

    /** Makes [sealed] the saved password, no longer in doubt. */
    fun store(sealed: Sealed) {
        check(write(sealed).remove(UNVERIFIED).putBoolean(CHECKED, true).commit()) { "No se pudo guardar el PIN" }
        mutableSaved.value = true
        mutableUnverified.value = false
        mutableLock.value = sealed.lock
    }

    /** The saved password under another lock: as trusted (or in doubt) as before. */
    fun reseal(sealed: Sealed) {
        check(write(sealed).commit()) { "No se pudo guardar el PIN" }
        mutableLock.value = sealed.lock
    }

    /**
     * The cipher that opens a PIN saved under a Keystore lock, whose prompt must match that lock even if the phone
     * would choose another one now. Null when the PIN is gone and must be typed again: a new fingerprint killed its key,
     * or the key is missing.
     */
    fun decryptCipher(): Cipher? {
        val lock = lock.value?.takeIf { it != PinLock.Code } ?: return null
        val iv = preferences.getString(IV, null)?.let(::decode) ?: return null
        val (alias, kind) = keyOf(lock)
        return try {
            keys.cipher(alias, kind, Cipher.DECRYPT_MODE, iv)
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear()
            null
        }
    }

    /**
     * The password for one payment. The caller hands it to the payment, which zeroes it. A failure here is never the
     * realme rejection: a PIN saved under the fingerprint key proves that key works on this phone.
     */
    fun open(authorized: Cipher): CharArray =
        chars(authorized.doFinal(decode(requireNotNull(preferences.getString(CIPHERTEXT, null)))))

    /** The password saved under the access code; throws when [key] does not open it. */
    fun openWithCode(key: CodeKey): CharArray {
        check(lock.value == PinLock.Code) { "PIN not under the code" }
        val ciphertext = decode(requireNotNull(preferences.getString(CIPHERTEXT, null)))
        val iv = decode(requireNotNull(preferences.getString(IV, null)))
        return chars(keys.openTwice(key, ciphertext, iv))
    }

    private fun write(sealed: Sealed) = preferences.edit()
        .putString(CIPHERTEXT, sealed.ciphertext).putString(IV, sealed.iv).putString(LOCK, sealed.lock.name)

    private fun storedLock(): PinLock? = if (!mutableSaved.value) null
    // Saved before 0.3.0: always the fingerprint key.
    else PinLock.entries.firstOrNull { it.name == preferences.getString(LOCK, null) } ?: PinLock.Fingerprint

    private fun keyOf(lock: PinLock): Pair<String, KeyKind> = when (lock) {
        PinLock.Fingerprint -> FINGERPRINT_ALIAS to KeyKind.Fingerprint
        PinLock.ScreenLock -> ANY_ALIAS to KeyKind.Any
        PinLock.Code -> error("The code's key is not in the Keystore")
    }

    private fun chars(bytes: ByteArray): CharArray = try {
        val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        CharArray(chars.remaining()).also { chars.get(it); chars.array().fill('\u0000') }
    } finally { bytes.fill(0) }

    private fun utf8(password: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        return ByteArray(buffer.remaining()).also { buffer.get(it); buffer.array().fill(0) }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val FINGERPRINT_ALIAS = "dev.duardo.neotransfer.plus.payment-password"
        const val ANY_ALIAS = "dev.duardo.neotransfer.plus.payment-password.any"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
        const val LOCK = "lock"
        const val UNVERIFIED = "unverified"
        const val CHECKED = "checked"
    }
}
