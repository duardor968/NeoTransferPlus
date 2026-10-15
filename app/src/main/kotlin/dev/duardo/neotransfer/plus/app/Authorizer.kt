package dev.duardo.neotransfer.plus.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.platform.BiometricGate
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheetActions

/**
 * Turns a fingerprint into the ENZONA payment password for one reviewed payment. The password is typed once,
 * the first time, and kept behind the fingerprint ([dev.duardo.neotransfer.plus.platform.PaymentPasswordVault]).
 */
class Authorizer(private val plus: PlusApp, private val gate: BiometricGate) : PasswordSheetActions {
    var sheet by mutableStateOf<PasswordSheetUi?>(null)
        private set
    private var pending: Pending? = null

    private class Pending(val title: String, val subtitle: String?, val onPassword: (CharArray) -> Unit, val onProblem: (String) -> Unit)

    /** [onPassword] receives the password once the fingerprint approved [title]; it owns and zeroes it. */
    fun authorize(title: String, subtitle: String?, action: String, onPassword: (CharArray) -> Unit, onProblem: (String) -> Unit) {
        if (!gate.canPay()) return onProblem("Para pagar, registra una huella en el teléfono.")
        val cipher = if (plus.vault.saved.value) runCatching { plus.vault.decryptCipher() }.getOrNull() else null
        if (cipher == null) {
            pending = Pending(title, subtitle, onPassword, onProblem)
            sheet = PasswordSheetUi("", action)
            return
        }
        gate.authenticate(
            cipher, title, subtitle,
            onSuccess = { authorized ->
                val password = runCatching { plus.vault.open(authorized) }.getOrNull()
                if (password == null) onProblem("No se pudo leer la contraseña guardada.") else onPassword(password)
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
        val typed = sheet?.value?.takeIf(String::isNotEmpty) ?: return
        sheet = null
        pending = null
        val password = typed.toCharArray()
        val cipher = runCatching { plus.vault.encryptCipher() }.getOrNull()
        if (cipher == null) {
            password.fill('\u0000')
            return request.onProblem("No se pudo proteger la contraseña con la huella.")
        }
        gate.authenticate(
            cipher, request.title, request.subtitle,
            onSuccess = { authorized ->
                // Saving can fail without stopping this payment: the next one asks for the password again.
                runCatching { plus.vault.save(authorized, password) }
                request.onPassword(password)
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
