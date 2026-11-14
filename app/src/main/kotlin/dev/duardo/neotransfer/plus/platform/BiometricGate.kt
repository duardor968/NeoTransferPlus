package dev.duardo.neotransfer.plus.platform

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

/** The prompts that authorize a Keystore cipher for one operation; faked in tests. */
interface Prompts {
    /**
     * Authorizes [cipher] (a per-use Keystore key) for one operation: the fingerprint only, or with [screenLock] also
     * the screen lock, for keys that accept it.
     */
    fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String?,
        screenLock: Boolean,
        onSuccess: (Cipher) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal
}

/**
 * The system prompts: app unlock (fingerprint, or the screen lock as fallback) and the ciphers of the payment PIN and
 * the access code ([Prompts]).
 */
class BiometricGate(private val activity: Activity) : Prompts {
    fun unlock(title: String, onSuccess: () -> Unit, onCancelled: () -> Unit): CancellationSignal {
        val cancellation = CancellationSignal()
        var finished = false
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (finished || cancellation.isCanceled) return
                finished = true
                onSuccess()
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (finished) return
                finished = true
                onCancelled()
            }
        })
        return cancellation
    }

    override fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String?,
        screenLock: Boolean,
        onSuccess: (Cipher) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal {
        val cancellation = CancellationSignal()
        var finished = false
        fun cancelOnce() {
            if (!finished) {
                finished = true
                onCancelled()
            }
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .apply { subtitle?.takeIf(String::isNotBlank)?.let { setSubtitle(it) } }
            .apply {
                // With the screen lock the system draws its own way out; a negative button is then an error.
                if (screenLock) setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                else setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> cancelOnce() }
            }
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (finished) return
                    if (cancellation.isCanceled) return cancelOnce()
                    finished = true
                    val authorized = result.cryptoObject?.cipher
                    if (authorized === cipher) onSuccess(authorized)
                    else onError("No se pudo completar la verificación")
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (finished) return
                    if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) cancelOnce()
                    else {
                        finished = true
                        onError(message.toString())
                    }
                }
            })
        return cancellation
    }
}
