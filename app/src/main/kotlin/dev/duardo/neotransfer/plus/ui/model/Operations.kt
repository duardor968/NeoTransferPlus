package dev.duardo.neotransfer.plus.ui.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * State of a movement. Sending: the single request is out. Unconfirmed: ENZONA did not prove the result in time
 * ("Sin confirmar"); it is checked against the history and never resent. NotSent: the request never left the phone.
 * Other: a state ENZONA reports that the app does not interpret; its own words are shown.
 */
enum class OperationStatus { Sending, Confirmed, Pending, Rejected, Unconfirmed, NotSent, Other }

enum class OperationKind(val incoming: Boolean = false) {
    CardTransfer,
    AccountTransfer,
    Incoming(incoming = true),
    QrPayment,
    Electricity,
    Landline,
    Nauta,
    NautaHome,
    Service,
    /** A line of the bank's own record of a card: ATM, POS, Transfermóvil, ENZONA. */
    CardMovement,
}

/** The other side, as ENZONA identifies it. */
@Immutable
sealed interface CounterpartUi {
    data class Card(val number: String, val bank: String? = null) : CounterpartUi
    /** Only the last four digits are known. */
    data class CardEnding(val last4: String, val bank: String? = null) : CounterpartUi
    data class Account(val username: String) : CounterpartUi
    /** Service reference (client code, phone, Nauta account) or merchant. */
    data class Reference(val value: String) : CounterpartUi
}

/**
 * A movement of the ENZONA account, a card's bank record, or a payment still in flight. [title] is already human:
 * contact or ENZONA name, merchant, service. [at] is null when ENZONA sent a date the app cannot read; [dateText]
 * keeps it as sent. [statusLabel] is ENZONA's own word when [status] is [OperationStatus.Other].
 */
@Immutable
data class OperationUi(
    val id: String,
    val kind: OperationKind,
    val title: String,
    val status: OperationStatus,
    val at: Instant?,
    val amount: Money? = null,
    val card: CardUi? = null,
    val counterpart: CounterpartUi? = null,
    val contactId: String? = null,
    val reason: String? = null,
    val transactionId: String? = null,
    val description: String? = null,
    val statusLabel: String? = null,
    val dateText: String? = null,
    /** Bank channel of a card movement ("ATM", "POS", "ENZONA"). */
    val channel: String? = null,
)
