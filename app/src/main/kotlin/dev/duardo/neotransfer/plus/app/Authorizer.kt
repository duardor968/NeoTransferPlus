package dev.duardo.neotransfer.plus.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.platform.AccessCode
import dev.duardo.neotransfer.plus.platform.CodeKey
import dev.duardo.neotransfer.plus.platform.PaymentPasswordVault
import dev.duardo.neotransfer.plus.platform.PinLock
import dev.duardo.neotransfer.plus.platform.Prompts
import dev.duardo.neotransfer.plus.ui.lock.CodeSheetActions
import dev.duardo.neotransfer.plus.ui.model.CodeSheetUi
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.model.PinProtection
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheetActions
import dev.duardo.neotransfer.plus.ui.payment.isPaymentPassword
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Why the payment password is asked although one is saved: ENZONA locks it after three wrong ones. */
const val PasswordInDoubt = "El último pago no se confirmó. Escribe de nuevo tu PIN de pago. ENZONA lo bloquea tras 3 intentos fallidos."

/** Without a screen lock (and without the access code protecting it) the payment PIN cannot be kept. */
const val NoLockToSave = "Pon un bloqueo de pantalla en el teléfono para guardar el PIN de pago"

/** The tenth wrong code in a row erased it, or turning the code off left nothing to keep it with. */
const val PinErased = "Se borró el PIN de pago guardado"

/**
 * Turns what protects the payment PIN on this phone ([PaymentPasswordVault.lockFor]) into the ENZONA payment password
 * for one reviewed payment: the fingerprint, the screen lock where there is no fingerprint, or the access code when it
 * also protects the PIN. The password is typed the first time and kept once that payment is confirmed; it is typed
 * again while the last payment made with one was answered without being confirmed ([PaymentPasswordVault.unverified]).
 * Without any lock it is typed for every payment and never kept.
 */
class Authorizer(
    private val vault: PaymentPasswordVault,
    private val code: AccessCode,
    private val prompts: Prompts,
    private val scope: CoroutineScope,
    private val background: CoroutineContext = Dispatchers.Default,
) : PasswordSheetActions {
    var sheet by mutableStateOf<PasswordSheetUi?>(null)
        private set
    private var pending: Pending? = null
    private var asking by mutableStateOf<Asking?>(null)

    /** The access code asked in a sheet; null while none is asked. */
    val codeSheet: CodeSheetUi? get() = asking?.let { CodeSheetUi(it.title, it.subtitle, it.entry.ui) }

    val codeActions = object : CodeSheetActions {
        override fun key(key: KeypadKey) { asking?.entry?.key(key) }
        override fun dismiss() {
            val current = asking ?: return
            current.entry.close()
            asking = null
            current.onCancelled()
        }
    }

    /** How a typed password is sealed for this phone, if at all. */
    private sealed interface Seal {
        data class Keystore(val lock: PinLock) : Seal
        class Code(val key: CodeKey) : Seal
        data object None : Seal
    }

    private class Pending(val title: String, val subtitle: String?, val seal: Seal, val onPassword: (CharArray, PaymentPasswordVault.Sealed?) -> Unit,
        val onProblem: (String) -> Unit)

    private class Asking(val title: String, val subtitle: String?, val entry: CodeEntry, val onCancelled: () -> Unit)

    /**
     * [onPassword] receives the password once [title] was approved; it owns and zeroes it. With it comes the typed
     * password sealed for this phone, to store once the payment is confirmed; null when the saved one was used or none
     * can be kept.
     */
    fun authorize(title: String, subtitle: String?, action: String, onPassword: (CharArray, PaymentPasswordVault.Sealed?) -> Unit,
        onProblem: (String) -> Unit) {
        val doubt = vault.unverified.value
        val usable = vault.saved.value && !doubt
        fun type(seal: Seal) {
            pending = Pending(title, subtitle, seal, onPassword, onProblem)
            sheet = PasswordSheetUi(
                "", if (seal == Seal.None) withoutSaving(action) else action, notice = PasswordInDoubt.takeIf { doubt },
                protection = when (seal) {
                    is Seal.Keystore -> if (seal.lock == PinLock.ScreenLock) PinProtection.ScreenLock else PinProtection.Fingerprint
                    is Seal.Code -> PinProtection.Code
                    Seal.None -> PinProtection.None
                },
            )
        }
        when (val lock = vault.lockFor(code.pinUnderCode())) {
            null -> type(Seal.None)
            PinLock.Code -> askCode(title, subtitle) { key ->
                val saved = if (usable && vault.lock.value == PinLock.Code) runCatching { vault.openWithCode(key) }.getOrNull() else null
                if (saved != null) onPassword(saved, null) else type(Seal.Code(key))
            }
            else -> {
                val savedLock = vault.lock.value?.takeIf { usable && it != PinLock.Code }
                val cipher = savedLock?.let { runCatching { vault.decryptCipher() }.getOrNull() } ?: return type(Seal.Keystore(lock))
                prompts.authenticate(
                    cipher, title, subtitle, screenLock = savedLock == PinLock.ScreenLock,
                    onSuccess = { authorized ->
                        val password = runCatching { vault.open(authorized) }.getOrNull()
                        if (password == null) onProblem("No se pudo leer el PIN guardado.") else onPassword(password, null)
                    },
                    onCancelled = {},
                    onError = onProblem,
                )
            }
        }
    }

    /**
     * What the next payment's approval asks: the fingerprint alone, a prompt that also takes the screen lock (a PIN
     * saved under that key, or a phone without a usable fingerprint key), the access code, or nothing.
     */
    fun approval(): PinProtection = when (val lock = vault.lockFor(code.pinUnderCode())) {
        null -> PinProtection.None
        PinLock.Code -> PinProtection.Code
        else -> {
            val saved = vault.lock.value.takeIf { vault.saved.value && !vault.unverified.value }
            if (lock == PinLock.ScreenLock || saved == PinLock.ScreenLock) PinProtection.ScreenLock else PinProtection.Fingerprint
        }
    }

    /** Ajustes › PIN de pago: keeps [password] (consumed) with what protects it on this phone. */
    fun save(password: CharArray, onSaved: () -> Unit, onProblem: (String) -> Unit) {
        fun stored(sealed: PaymentPasswordVault.Sealed?) {
            password.fill('\u0000')
            if (sealed != null && runCatching { vault.store(sealed) }.isSuccess) onSaved() else onProblem("No se pudo guardar el PIN.")
        }
        when (val lock = vault.lockFor(code.pinUnderCode())) {
            null -> {
                password.fill('\u0000')
                onProblem(NoLockToSave)
            }
            PinLock.Code -> askCode(SaveTitle, null, onCancelled = { password.fill('\u0000') }) { key ->
                stored(runCatching { vault.sealWithCode(key, password) }.getOrNull())
            }
            else -> seal(lock, password, SaveTitle, null, ::stored, onCancelled = { password.fill('\u0000') }) {
                password.fill('\u0000')
                onProblem(it)
            }
        }
    }

    /** Asks the access code in a sheet; [onRight] gets its key. Dismissing the sheet calls [onCancelled]. */
    fun askCode(title: String, subtitle: String?, onCancelled: () -> Unit = {}, onRight: (CodeKey) -> Unit) {
        asking?.entry?.close()
        asking = Asking(title, subtitle, CodeEntry(code, scope, background) { key ->
            asking?.entry?.close()
            asking = null
            onRight(key)
        }, onCancelled)
    }

    /** The saved PIN from the fingerprint or the screen lock to the access code ([key]), with one prompt. */
    fun moveToCode(key: CodeKey, title: String, onDone: () -> Unit, onProblem: (String) -> Unit) {
        val lock = vault.lock.value
        if (lock == null || lock == PinLock.Code) return onDone()
        // A key a new fingerprint killed: the PIN is gone, nothing to move.
        val cipher = runCatching { vault.decryptCipher() }.getOrNull() ?: return onDone()
        prompts.authenticate(
            cipher, title, null, screenLock = lock == PinLock.ScreenLock,
            onSuccess = { authorized ->
                val password = runCatching { vault.open(authorized) }.getOrNull()
                if (password == null) return@authenticate onProblem("No se pudo leer el PIN guardado.")
                val moved = runCatching { vault.reseal(vault.sealWithCode(key, password)) }.isSuccess
                password.fill('\u0000')
                if (moved) onDone() else onProblem("No se pudo guardar el PIN.")
            },
            onCancelled = {},
            onError = onProblem,
        )
    }

    /**
     * The saved PIN from the access code ([key]) back to the fingerprint or the screen lock, with one prompt. Without any
     * lock nothing can keep it: it is erased and [onDone] says so. Any other failure keeps everything as it was.
     */
    fun moveFromCode(key: CodeKey, title: String, onDone: (erased: Boolean) -> Unit, onProblem: (String) -> Unit) {
        if (vault.lock.value != PinLock.Code) return onDone(false)
        val lock = vault.lockFor(codeProtects = false)
        if (lock == null) {
            runCatching { vault.clear() }
            return onDone(true)
        }
        // Unreadable now: everything stays as it is, the code included.
        val password = runCatching { vault.openWithCode(key) }.getOrNull() ?: return onProblem("No se pudo leer el PIN guardado.")
        seal(lock, password, title, null,
            onSealed = { sealed ->
                password.fill('\u0000')
                if (sealed != null && runCatching { vault.reseal(sealed) }.isSuccess) onDone(false) else onProblem("No se pudo guardar el PIN.")
            },
            onCancelled = { password.fill('\u0000') },
            onProblem = { password.fill('\u0000'); onProblem(it) },
        )
    }

    /**
     * Seals [password] under [lock], a Keystore one, after its prompt; null in [onSealed] when sealing failed after
     * it. A phone that turns the fingerprint key down moves for good to the screen lock and prompts once more.
     */
    private fun seal(lock: PinLock, password: CharArray, title: String, subtitle: String?, onSealed: (PaymentPasswordVault.Sealed?) -> Unit,
        onCancelled: () -> Unit, onProblem: (String) -> Unit) {
        val cipher = runCatching { vault.encryptCipher(lock) }.getOrNull() ?: return onProblem(
            if (lock == PinLock.ScreenLock) "No se pudo proteger el PIN con el bloqueo de pantalla." else "No se pudo proteger el PIN con la huella.",
        )
        prompts.authenticate(
            cipher, title, subtitle, screenLock = lock == PinLock.ScreenLock,
            onSuccess = { authorized ->
                val sealed = try {
                    vault.seal(authorized, password, lock)
                } catch (_: PaymentPasswordVault.FingerprintRejected) {
                    return@authenticate seal(PinLock.ScreenLock, password, title, subtitle, onSealed, onCancelled, onProblem)
                } catch (_: Exception) {
                    null
                }
                onSealed(sealed)
            },
            onCancelled = onCancelled,
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
        when (val seal = request.seal) {
            Seal.None -> request.onPassword(password, null)
            // Sealing can fail without stopping this payment: the next one asks for the password again.
            is Seal.Code -> request.onPassword(password, runCatching { vault.sealWithCode(seal.key, password) }.getOrNull())
            is Seal.Keystore -> seal(
                seal.lock, password, request.title, request.subtitle,
                onSealed = { sealed -> request.onPassword(password, sealed) },
                onCancelled = { password.fill('\u0000') },
                onProblem = { message -> password.fill('\u0000'); request.onProblem(message) },
            )
        }
    }

    override fun dismiss() {
        sheet = null
        pending = null
    }

    private companion object {
        const val SaveTitle = "Guardar el PIN de pago"
    }
}

/** «Guardar y transferir» → «Transferir»: the same action when nothing can keep the password. */
internal fun withoutSaving(action: String): String =
    action.removePrefix("Guardar y ").replaceFirstChar(Char::uppercaseChar)
