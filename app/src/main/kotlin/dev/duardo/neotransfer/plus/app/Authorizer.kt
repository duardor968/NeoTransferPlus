package dev.duardo.neotransfer.plus.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.platform.BiometricGate
import dev.duardo.neotransfer.plus.platform.PaymentPasswordVault
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheetActions
import dev.duardo.neotransfer.plus.ui.payment.isPaymentPassword

/** Why the payment password is asked although one is saved: ENZONA locks it after three wrong ones. */
const val PasswordInDoubt = "El último pago no se confirmó. Escribe de nuevo tu PIN de pago. ENZONA lo bloquea tras 3 intentos fallidos."

/**
 * Turns a fingerprint into the ENZONA payment password for one reviewed payment. The password is typed the first
 * time and kept behind the fingerprint ([PaymentPasswordVault]) once that payment is confirmed; it is typed again
 * while the last payment made with one was answered without being confirmed ([PaymentPasswordVault.unverified]).
 */
class Authorizer(private val plus: PlusApp, private val gate: BiometricGate) : PasswordSheetActions {
    var sheet by mutableStateOf<PasswordSheetUi?>(null)
        private set
    private var pending: Pending? = null

    private class Pending(val title: String, val subtitle: String?, val onPassword: (CharArray, PaymentPasswordVault.Sealed?) -> Unit,
        val onProblem: (String) -> Unit)

    /**
     * [onPassword] receives the password once the fingerprint approved [title]; it owns and zeroes it. With it comes the
     * typed password sealed for this phone, to store once the payment is confirmed; null when the saved one was used.
     */
    fun authorize(title: String, subtitle: String?, action: String, onPassword: (CharArray, PaymentPasswordVault.Sealed?) -> Unit,
        onProblem: (String) -> Unit) {
        if (!gate.canPay()) return onProblem("Para pagar, registra una huella en el teléfono.")
        val doubt = plus.vault.unverified.value
        val cipher = if (plus.vault.saved.value && !doubt) runCatching { plus.vault.decryptCipher() }.getOrNull() else null
        if (cipher == null) {
            pending = Pending(title, subtitle, onPassword, onProblem)
            sheet = PasswordSheetUi("", action, notice = PasswordInDoubt.takeIf { doubt })
            return
        }
        gate.authenticate(
            cipher, title, subtitle,
            onSuccess = { authorized ->
                val password = runCatching { plus.vault.open(authorized) }.getOrNull()
                if (password == null) onProblem("No se pudo leer el PIN guardado.") else onPassword(password, null)
            },
            onCancelled = {},
            onError = onProblem,
        )
    }

    override fun value(value: String) {
        sheet = sheet?.copy(value = value, error = null)
    }

    override fun submit() {
        val request = pending ?: return
        val typed = sheet?.value?.takeIf(::isPaymentPassword) ?: return
        sheet = null
        pending = null
        val password = typed.toCharArray()
        val cipher = runCatching { plus.vault.encryptCipher() }.getOrNull()
        if (cipher == null) {
            password.fill('\u0000')
            return request.onProblem("No se pudo proteger el PIN con la huella.")
        }
        gate.authenticate(
            cipher, request.title, request.subtitle,
            onSuccess = { authorized ->
                // Sealing can fail without stopping this payment: the next one asks for the password again.
                request.onPassword(password, runCatching { plus.vault.seal(authorized, password) }.getOrNull())
            },
            onCancelled = { password.fill('\u0000') },
            onError = { message -> password.fill('\u0000'); request.onProblem(message) },
        )
    }

    override fun dismiss() {
        sheet = null
        pending = null
    }
}
