package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.walletUi
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.DecimalSeparator
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi

/** What a failed ENZONA request means for the person, in one line. */
fun EnzonaFailure.message(): String = when (this) {
    EnzonaFailure.SIGNED_OUT -> "Tu sesión de ENZONA caducó. Entra de nuevo."
    EnzonaFailure.TIMEOUT -> "ENZONA tardó demasiado en responder."
    EnzonaFailure.NETWORK -> "No hay conexión con ENZONA."
    EnzonaFailure.REJECTED -> "ENZONA no aceptó la solicitud."
    EnzonaFailure.INVALID_RESPONSE -> "ENZONA respondió algo que la app no reconoce."
    EnzonaFailure.INVALID_INPUT -> "Revisa los datos."
}

/** The user's cards with their balances, as the wallet shows them. */
@Composable
fun rememberWallet(plus: PlusApp): List<WalletCardUi> {
    val wallet by plus.wallet.collectAsState()
    val refreshing by plus.refreshingCards.collectAsState()
    return remember(wallet, refreshing) { walletUi(wallet.orEmpty(), refreshing) }
}

/** Keypad text: at most twelve whole digits and two decimals, one separator, no leading zeros. */
fun typeAmount(current: String, key: KeypadKey): String = when (key) {
    KeypadKey.Backspace -> current.dropLast(1)
    KeypadKey.Decimal -> when {
        DecimalSeparator in current -> current
        current.isEmpty() -> "0$DecimalSeparator"
        else -> current + DecimalSeparator
    }
    is KeypadKey.Digit -> {
        val cut = current.indexOf(DecimalSeparator)
        when {
            cut >= 0 && current.length - cut > 2 -> current
            cut < 0 && current.length >= 12 -> current
            current == "0" -> key.value.toString()
            else -> current + key.value
        }
    }
}

/** Luhn check of a card number: catches a mistyped or misread digit before it reaches ENZONA. */
fun luhn(digits: String): Boolean {
    if (digits.isEmpty() || !digits.all(Char::isDigit)) return false
    var sum = 0
    digits.reversed().forEachIndexed { index, c ->
        var d = c - '0'
        if (index % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
        sum += d
    }
    return sum % 10 == 0
}

/** Why a typed card number cannot be used yet; null when it can. */
fun cardNumberError(digits: String): String? = when {
    digits.isEmpty() -> null
    digits.length < 16 -> "Número incompleto"
    !luhn(digits) -> "Número no válido: revisa los dígitos"
    else -> null
}

private val cardDigits = Regex("""(?<![0-9])[0-9](?:[ -]?[0-9]){15}(?![0-9])""")

/**
 * The card carried by a Transfermóvil transfer code (`TRANSFERMOVIL_ETECSA,TRANSFERENCIA,<card>,…`, separators as
 * each generator writes them) or by a bare Cuban card number; null for anything else or a number that fails Luhn.
 */
fun cardFromQr(text: String): String? {
    val value = text.trim()
    val upper = value.uppercase()
    val source = when {
        "TRANSFERMOVIL_ETECSA" in upper && "TRANSFERENCIA" in upper -> value.substring(upper.indexOf("TRANSFERENCIA") + "TRANSFERENCIA".length)
        // Bare numbers only for Cuban cards (92…): ENZONA's own codes can be all digits too.
        value.all { it.isDigit() || it == ' ' || it == '-' } && value.filter(Char::isDigit).startsWith("92") -> value
        else -> return null
    }
    val digits = cardDigits.find(source)?.value?.filter(Char::isDigit) ?: return null
    return digits.takeIf(::luhn)
}
