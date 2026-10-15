package dev.duardo.neotransfer.plus.platform

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

/** The system prompts: app unlock (fingerprint, or the screen lock as fallback) and payments (fingerprint only). */
class BiometricGate(private val activity: Activity) {
    private val manager get() = activity.getSystemService(BiometricManager::class.java)

    /** False when the phone has no screen lock at all: then there is nothing to unlock with. */
    fun canUnlock(): Boolean = manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun canPay(): Boolean = manager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

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

    /** Authorizes [cipher] (a Keystore key that needs a strong biometric on every use) for one operation. */
    fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String?,
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
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> cancelOnce() }
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
