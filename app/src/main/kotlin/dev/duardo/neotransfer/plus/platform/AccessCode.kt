package dev.duardo.neotransfer.plus.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The key derived from the access code; only in memory, while one operation needs it. */
class CodeKey internal constructor(internal val key: SecretKey) {
    override fun toString() = "CodeKey(redacted)"
}

/**
 * The app's own six-digit code (like Telegram's passcode). Never stored: PBKDF2-HMAC-SHA256 with a random salt
 * derives a key that must open a known blob (GCM tag), itself wrapped by a Keystore key of this phone, so guesses only
 * run here and through [check]. Failures in a row and their waits survive restarts; while the code protects the saved
 * payment PIN ([pinUnderCode]), the tenth in a row erases it ([erasePin]). Waits run on the time since boot, never on
 * the clock the person can set; after a reboot the current wait starts over.
 *
 * The lock also opens with a fingerprint, authorized with a per-use key made when the code is set and killed by a
 * new enrollment: fingers enrolled after the code was set never open the app until the code is typed again.
 */
class AccessCode(
    context: Context,
    private val keys: Keys,
    private val device: DeviceLock,
    /** What protects the saved payment PIN; null while none is saved ([PaymentPasswordVault.lock]). */
    private val savedPinLock: () -> PinLock?,
    /** Erases the saved payment PIN; false when none was saved. */
    private val erasePin: () -> Boolean,
    private val uptime: () -> Uptime = { Uptime.now(context) },
    private val iterations: () -> Int = ::calibrated,
) {
    /** Time since boot, and which boot: a wait measured on it survives a clock set forward and restarts on a reboot. */
    data class Uptime(val boot: Int, val millis: Long) {
        companion object {
            fun now(context: Context) =
                Uptime(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0), SystemClock.elapsedRealtime())
        }
    }

    private val preferences = context.getSharedPreferences("access_code", Context.MODE_PRIVATE)
    private val mutableActive = MutableStateFlow(preferences.contains(VERIFIER))
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private val mutableProtectsPin = MutableStateFlow(preferences.getBoolean(PROTECTS_PIN, false))
    /**
     * «Usar también para el PIN de pago» as last chosen: what a PIN saved from now on goes under. For a PIN already
     * saved, its own recorded lock decides ([pinUnderCode]), so an interrupted change never asks to type it again.
     */
    val protectsPin: StateFlow<Boolean> = mutableProtectsPin.asStateFlow()

    /** The code opens the payment PIN: the saved one is under it, or, with none saved, the next one will be. */
    fun pinUnderCode(): Boolean = underCode(savedPinLock(), mutableProtectsPin.value)
    private val mutableFailures = MutableStateFlow(preferences.getInt(FAILURES, 0))
    /** Wrong codes in a row. */
    val failures: StateFlow<Int> = mutableFailures.asStateFlow()

    sealed interface Check {
        class Right(val key: CodeKey) : Check
        /** [erased]: this failure erased the saved payment PIN. */
        data class Wrong(val failures: Int, val erased: Boolean) : Check
        /** Still waiting after earlier failures: nothing was checked. */
        data class Waiting(val millis: Long) : Check
    }

    /** What is left of the wait after the last failure, never more than its full length; 0 when a code may be typed. */
    fun remaining(): Long {
        val wait = waitAfter(mutableFailures.value)
        if (wait == 0L) return 0
        val now = uptime()
        val since = preferences.getLong(FAILED_AT, 0)
        // Another boot (or no record): the time since boot says nothing of the wait, which starts over in full.
        if (preferences.getInt(FAILED_BOOT, -1) != now.boot || now.millis < since) {
            markFailure(now)
            return wait
        }
        return (wait - (now.millis - since)).coerceIn(0, wait)
    }

    private fun markFailure(now: Uptime) =
        preferences.edit().putLong(FAILED_AT, now.millis).putInt(FAILED_BOOT, now.boot).commit()

    /**
     * Sets a new code (a first one, a change or after a forgotten one). [before] runs with its key before it replaces
     * the old one, to move what the old key protects; it may throw to keep the old code. Blocking (key derivation).
     */
    fun create(code: CharArray, before: (CodeKey) -> Unit = {}): CodeKey {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val rounds = iterations()
        val key = CodeKey(derive(code, salt, rounds))
        val (verifier, iv) = keys.sealTwice(key, KNOWN)
        before(key)
        kotlin.check(preferences.edit()
            .putString(SALT, encode(salt)).putInt(ITERATIONS, rounds)
            .putString(VERIFIER, encode(verifier)).putString(VERIFIER_IV, encode(iv))
            .putInt(FAILURES, 0).remove(FAILED_AT).remove(FAILED_BOOT)
            .commit()) { "No se pudo guardar el código" }
        mutableActive.value = true
        mutableFailures.value = 0
        renewFingerprint(replace = true)
        return key
    }

    /** Checks a typed code. The failure is counted before the check, so stopping the app mid-check still counts it. Blocking. */
    fun check(code: CharArray): Check {
        kotlin.check(mutableActive.value) { "No code" }
        remaining().takeIf { it > 0 }?.let { return Check.Waiting(it) }
        val failures = mutableFailures.value + 1
        val now = uptime()
        kotlin.check(preferences.edit().putInt(FAILURES, failures).putLong(FAILED_AT, now.millis).putInt(FAILED_BOOT, now.boot).commit()) {
            "No se pudo comprobar el código"
        }
        mutableFailures.value = failures
        val key = CodeKey(derive(code, decode(preferences.getString(SALT, null)!!), preferences.getInt(ITERATIONS, 0)))
        val right = try {
            keys.openTwice(key, decode(preferences.getString(VERIFIER, null)!!), decode(preferences.getString(VERIFIER_IV, null)!!))
                .contentEquals(KNOWN)
        } catch (_: GeneralSecurityException) {
            false
        }
        if (right) {
            resetFailures()
            renewFingerprint(replace = false)
            return Check.Right(key)
        }
        // From the tenth on: a tenth stopped mid-check is counted, and must still erase on the next one.
        val erased = failures >= ERASE_AT && pinUnderCode() && erasePin()
        return Check.Wrong(failures, erased)
    }

    fun protectPin(on: Boolean) {
        kotlin.check(preferences.edit().putBoolean(PROTECTS_PIN, on).commit()) { "No se pudo guardar el ajuste" }
        mutableProtectsPin.value = on
    }

    fun remove() {
        kotlin.check(preferences.edit().clear().commit()) { "No se pudo borrar el código" }
        keys.delete(FINGERPRINT_ALIAS)
        mutableActive.value = false
        mutableProtectsPin.value = false
        mutableFailures.value = 0
    }

    /**
     * What the lock's fingerprint prompt authorizes; null when it cannot open the app now: no code, no strong
     * fingerprint, a phone that turns fingerprint keys down, or an enrollment since the code was last typed.
     */
    fun fingerprintCipher(): Cipher? {
        if (!mutableActive.value || keys.fingerprintRejected || !device.fingerprint() || !keys.has(FINGERPRINT_ALIAS)) return null
        return try {
            keys.cipher(FINGERPRINT_ALIAS, KeyKind.Fingerprint, Cipher.ENCRYPT_MODE)
        } catch (_: KeyPermanentlyInvalidatedException) {
            keys.delete(FINGERPRINT_ALIAS)
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether the fingerprint that authorized [authorized] opens the app. A phone that turns the key down right after
     * a valid fingerprint is marked for good and offers only the code from then on. Opening ends the run of wrong codes.
     */
    fun fingerprintOpens(authorized: Cipher): Boolean = try {
        authorized.doFinal(ByteArray(16))
        resetFailures()
        true
    } catch (e: Exception) {
        if (rejectedAfterFingerprint(e)) {
            keys.fingerprintRejected = true
            keys.delete(FINGERPRINT_ALIAS)
        }
        false
    }

    private fun resetFailures() {
        preferences.edit().putInt(FAILURES, 0).remove(FAILED_AT).remove(FAILED_BOOT).commit()
        mutableFailures.value = 0
    }

    /** After the code: a fingerprint key that trusts the fingers enrolled now, unless a valid one is there. */
    private fun renewFingerprint(replace: Boolean) {
        if (keys.fingerprintRejected || !device.fingerprint()) return
        if (replace) keys.delete(FINGERPRINT_ALIAS)
        else if (fingerprintCipher() != null) return
        runCatching { keys.cipher(FINGERPRINT_ALIAS, KeyKind.Fingerprint, Cipher.ENCRYPT_MODE) }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        /** The failure in a row that erases the PIN the code protects. */
        const val ERASE_AT = 10
        private const val FIRST_WAIT_AT = 5
        private const val FIRST_WAIT = 30_000L
        private const val LONGEST_WAIT = 3_600_000L
        private val KNOWN = "neotransfer-plus-access-code".toByteArray()
        private const val FINGERPRINT_ALIAS = "dev.duardo.neotransfer.plus.access-code.fingerprint"
        private const val SALT = "salt"
        private const val ITERATIONS = "iterations"
        private const val VERIFIER = "verifier"
        private const val VERIFIER_IV = "verifier_iv"
        private const val FAILURES = "failures"
        /** Time since boot of the last failure, and its boot. */
        private const val FAILED_AT = "failed_uptime"
        private const val FAILED_BOOT = "failed_boot"
        private const val PROTECTS_PIN = "protects_pin"

        /** [saved]: what protects the saved PIN, null with none; [wanted]: the last choice, for the next one. */
        fun underCode(saved: PinLock?, wanted: Boolean): Boolean = saved?.let { it == PinLock.Code } ?: wanted

        /** The wait after [failures] in a row: none before the fifth, then 30 s doubling each time, at most an hour. */
        fun waitAfter(failures: Int): Long =
            if (failures < FIRST_WAIT_AT) 0 else (FIRST_WAIT shl (failures - FIRST_WAIT_AT).coerceAtMost(20)).coerceAtMost(LONGEST_WAIT)

        internal fun derive(code: CharArray, salt: ByteArray, iterations: Int): SecretKey {
            val spec = PBEKeySpec(code, salt, iterations, 256)
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
            } finally { spec.clearPassword() }
        }

        /** Rounds that take about 250 ms on this phone, never fewer than 100 000. */
        private fun calibrated(): Int {
            val probe = 20_000
            val started = System.nanoTime()
            derive("000000".toCharArray(), ByteArray(16), probe)
            val millis = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            return (probe * 250L / millis).coerceIn(100_000L, 2_000_000L).toInt()
        }
    }
}

/** Keystore alias of the key that binds code-protected data to this phone; it needs no one to authenticate. */
private const val DEVICE_ALIAS = "dev.duardo.neotransfer.plus.device"

/** [plaintext] under [key], then under this phone's own key: both are needed to read it. Returns ciphertext and IV. */
internal fun Keys.sealTwice(key: CodeKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val inner = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key.key) }
    val blob = inner.iv + inner.doFinal(plaintext)
    try {
        val outer = cipher(DEVICE_ALIAS, KeyKind.Device, Cipher.ENCRYPT_MODE)
        return outer.doFinal(blob) to outer.iv
    } finally { blob.fill(0) }
}

/** The plaintext of [sealTwice]; throws (`AEADBadTagException`) when [key] is not the one. */
internal fun Keys.openTwice(key: CodeKey, ciphertext: ByteArray, iv: ByteArray): ByteArray {
    val blob = cipher(DEVICE_ALIAS, KeyKind.Device, Cipher.DECRYPT_MODE, iv).doFinal(ciphertext)
    try {
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key.key, GCMParameterSpec(128, blob, 0, GCM_IV)) }
            .doFinal(blob, GCM_IV, blob.size - GCM_IV)
    } finally { blob.fill(0) }
}

private const val GCM_IV = 12
