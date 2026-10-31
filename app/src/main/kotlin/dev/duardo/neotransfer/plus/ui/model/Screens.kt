package dev.duardo.neotransfer.plus.ui.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

// ---- Access -------------------------------------------------------------------------------------------------

@Immutable
sealed interface LockUi {
    /** The system prompt is on top; only the brand and the locked state show behind it. */
    data object Prompting : LockUi
    /** The prompt was dismissed: one action brings it back, no loop. */
    data object Cancelled : LockUi
}

/** Why access to ENZONA needs attention, in the user's terms. */
enum class LoginProblem { Rejected, ClientRejected, Denied, ServerError, Expired, Timeout, Offline, PageFailed, RestoreFailed, ClearFailed, SaveFailed }

@Immutable
sealed interface LoginUi {
    data class Start(val problem: LoginProblem? = null) : LoginUi
    /** ENZONA's own sign-in page, inside the app. [loading] while a page loads. */
    data class Web(val loading: Boolean) : LoginUi
    /** The page answered; the app reads the account. */
    data object Connecting : LoginUi
}

// ---- Home ---------------------------------------------------------------------------------------------------

/** Why the last read of ENZONA failed, in the user's terms. */
enum class ReadProblem { Offline, Timeout, Rejected, Unrecognized, Expired, Local }

@Immutable
sealed interface RefreshState {
    data object Idle : RefreshState
    data object Refreshing : RefreshState
    /** [since]: when the failed read began. A refresh reads several things, and the failure may be another step's. */
    data class Failed(val problem: ReadProblem, val since: Instant) : RefreshState {
        /** The problem for a balance read at [readAt]: none when that balance was read after the failed read began. */
        fun problemFor(readAt: Instant): ReadProblem? = problem.takeIf { readAt < since }
    }
}

/** [recent] null until the history has been read once. [recharge]: the saved Nauta accounts, while «Recargar» is open. */
@Immutable
data class HomeUi(
    val cards: List<WalletCardUi>,
    val selected: Int,
    val inProgress: List<OperationUi>,
    val recent: List<OperationUi>?,
    val refresh: RefreshState = RefreshState.Idle,
    val recharge: List<SavedReferenceUi>? = null,
)

/** One card opened from the wallet: its full number, or the field to complete it. */
@Immutable
data class CardSheetUi(val card: CardUi, val input: String, val error: String? = null, val saving: Boolean = false)

// ---- Activity -----------------------------------------------------------------------------------------------

enum class ActivitySource(val label: String) { Account("Cuenta"), Cards("Tarjetas") }

enum class ActivityFilter(val label: String) { All("Todo"), In("Entradas"), Out("Salidas") }

@Immutable
sealed interface ActivityContent {
    data object Loading : ActivityContent
    data object Empty : ActivityContent
    data object NoResults : ActivityContent
    data class Items(val operations: List<OperationUi>) : ActivityContent
}

/** [olderFrom]: first day not loaded yet; [loadingOlder] while ENZONA answers. */
@Immutable
data class ActivityUi(
    val source: ActivitySource,
    val query: String,
    val filter: ActivityFilter,
    val cards: List<CardUi>,
    val card: String?,
    val content: ActivityContent,
    val olderFrom: LocalDate? = null,
    val loadingOlder: Boolean = false,
    val refresh: RefreshState = RefreshState.Idle,
)

// ---- Contacts -----------------------------------------------------------------------------------------------

@Immutable
sealed interface ContactsContent {
    /** No search: [people] with money sent or a saved card, newest movement first. */
    data class People(val people: List<ContactUi>) : ContactsContent
    /** A search: matches among the people first, then everyone else ([others]). */
    data class Results(val people: List<ContactUi>, val others: List<ContactUi>) : ContactsContent
    data object NoResults : ContactsContent
}

/** [everyone]: how many contacts there are in all; [addressBook] false while the phone's address book cannot be read. */
@Immutable
data class ContactsUi(val query: String, val content: ContactsContent, val everyone: Int, val addressBook: Boolean)

/** Everyone: the phone's address book and the contacts kept only in the app, by name. */
@Immutable
data class AllContactsUi(val query: String, val contacts: List<ContactUi>)

/** A card number as typed, with what it tells so far ([bank], [currency]) and what is wrong with it. */
@Immutable
data class CardFieldUi(val number: String, val label: String, val bank: String? = null, val currency: String? = null, val error: String? = null) {
    /** How the card will look and read once saved. */
    val card: ForeignCardUi get() = ForeignCardUi("", number, bank, label.ifBlank { null }, currency)
}

@Immutable
data class EditablePhoneUi(val id: String, val number: String, val error: String? = null)

/** A contact kept only in the app. [id] null when creating, with the one [card] it starts with; a saved contact's cards are managed on its page. */
@Immutable
data class ContactEditUi(
    val id: String?,
    val name: String,
    val card: CardFieldUi?,
    val phones: List<EditablePhoneUi>,
    val canSave: Boolean,
)

/** One card of [contactName]: a new one, or [editing] a saved one. */
@Immutable
data class CardEditUi(val contactName: String, val editing: Boolean, val card: CardFieldUi, val canSave: Boolean)

// ---- Services -----------------------------------------------------------------------------------------------

enum class Utility(val label: String) { Electricity("Electricidad"), Landline("Teléfono fijo"), Internet("Internet") }

enum class ServiceKind(val utility: Utility, val label: String, val referenceLabel: String) {
    Electricity(Utility.Electricity, "Electricidad", "Código de cliente"),
    Landline(Utility.Landline, "Teléfono fijo", "Número de teléfono"),
    Nauta(Utility.Internet, "Nauta", "Cuenta Nauta"),
    NautaHome(Utility.Internet, "Nauta Hogar", "Cuenta Nauta Hogar"),
}

/** A reference ENZONA confirmed a payment to, kept in Guardados: [name] when the person gave it one. */
@Immutable
data class SavedReferenceUi(val kind: ServiceKind, val value: String, val name: String? = null, val lastPaid: Money? = null)

@Immutable
data class ServicesUi(val saved: List<SavedReferenceUi>)

/** The sheet of a saved reference: its name as typed, and [removing] while «Quitar de Guardados» asks first. */
@Immutable
data class SavedEditUi(val name: String, val removing: Boolean = false)

@Immutable
data class BillUi(val id: String, val period: String, val amount: Money, val paid: Boolean)

/** What ENZONA answered for a reference. */
@Immutable
sealed interface ServiceLookupUi {
    data object None : ServiceLookupUi
    data object Loading : ServiceLookupUi
    data class Failed(val message: String) : ServiceLookupUi
    data class Bills(val owner: String?, val bills: List<BillUi>, val selected: String?) : ServiceLookupUi
    /** Fixed phone: the bill as ETECSA reports it. */
    data class PhoneBill(val owner: String?, val due: Money) : ServiceLookupUi
    /** Nauta top-up: debt is reported, never paid by a top-up. */
    data class TopUp(val owner: String?, val debt: Money?, val min: Money?, val max: Money?) : ServiceLookupUi
}

@Immutable
data class ServiceFormUi(
    val kind: ServiceKind,
    val reference: String,
    val referenceError: String? = null,
    /** Guardados of this kind, one tap from the form; [selectedReference] is the value of the one chosen. */
    val references: List<SavedReferenceUi> = emptyList(),
    val selectedReference: String? = null,
    val lookup: ServiceLookupUi,
    val amount: String = "",
    val amountError: String? = null,
    val currency: String,
    val cards: List<WalletCardUi>,
    val source: Int,
    val canPay: Boolean,
    val paying: Boolean = false,
    /** The reference is in Guardados: the bar offers to edit it. */
    val saved: Boolean = false,
    val edit: SavedEditUi? = null,
)

// ---- Transfer, pay, receive ---------------------------------------------------------------------------------

/** [typed] is a valid card number typed or scanned that matches no contact. */
@Immutable
data class RecipientPickUi(
    val query: String,
    val typed: ForeignCardUi?,
    val typedError: String?,
    val recent: List<RecipientUi.Card>,
    val contacts: List<ContactUi>,
    val chooseCardOf: ContactUi? = null,
)

@Immutable
data class TransferAmountUi(
    val recipient: RecipientUi,
    val input: String,
    val currency: String,
    val sources: List<WalletCardUi>,
    val source: Int,
    val overBalance: Boolean,
    val canContinue: Boolean,
    /** Set by an account QR that fixes the amount. */
    val fixed: Boolean = false,
)

/** [duplicate] while the same transfer is still unconfirmed or on its way: asks before sending another. */
@Immutable
data class TransferReviewUi(
    val recipient: RecipientUi,
    val amount: Money,
    val source: CardUi,
    val sending: Boolean = false,
    val duplicate: DuplicateUi? = null,
)

/** Same card, destination and amount, sent at [sentAt] and still without ENZONA's confirmation. */
@Immutable
data class DuplicateUi(val amount: Money, val recipient: String, val sentAt: Instant)

@Immutable
data class PayReviewUi(
    val merchant: String,
    val amountInput: String,
    val amountEditable: Boolean,
    val description: String,
    val currency: String,
    val sources: List<WalletCardUi>,
    val source: Int,
    val canPay: Boolean,
    val paying: Boolean = false,
)

@Immutable
sealed interface PayQrUi {
    data object Scanning : PayQrUi
    data object Resolving : PayQrUi
    data class Review(val review: PayReviewUi) : PayQrUi
    /** [addCard]: the code is fine, a card in its currency is missing. */
    data class Unsupported(val message: String, val addCard: Boolean = false) : PayQrUi
}

/**
 * Cobrar: the account's QR as ENZONA issues it, at once and without an amount, or with the one asked for. [payload]
 * null while ENZONA answers or after it failed ([error]). [asking]: the amount being typed in «Pedir importe».
 */
@Immutable
data class ReceiveUi(
    val payload: String?,
    val amount: Money?,
    val currency: String,
    val owner: String?,
    val username: String?,
    val error: String? = null,
    val asking: String? = null,
)

/**
 * The fingerprint unlocks the saved payment password; this asks for it the first time, and again after a payment that
 * ENZONA answered without confirming ([notice]).
 */
@Immutable
data class PasswordSheetUi(val value: String, val action: String, val error: String? = null, val notice: String? = null)

/** Outcome of one payment. [operation] carries what was sent; [canSaveContact] for a card that is nobody's yet. */
@Immutable
data class PaymentResultUi(val operation: OperationUi, val canSaveContact: Boolean = false)

// ---- Settings -----------------------------------------------------------------------------------------------

@Immutable
data class AccountUi(val name: String, val username: String?, val phone: String?, val email: String?)

/** One card of the ENZONA account: [primary], the one ENZONA uses by default; [busy] while a change waits for ENZONA. */
@Immutable
data class AccountCardUi(val card: CardUi, val primary: Boolean, val busy: Boolean = false)

/**
 * [batteryExempt]: battery optimization leaves the periodic check alone. [autostart]: the phone has its own
 * autostart list ([offeringAutostart] while the app offers to open it). [cardMenu]: the card whose menu is open;
 * [removing]: the card whose removal is being confirmed.
 */
@Immutable
data class SettingsUi(
    val account: AccountUi?,
    val passwordSaved: Boolean,
    val appearance: Appearance,
    val notifications: Boolean,
    val version: String,
    val confirmingSignOut: Boolean = false,
    val signingOut: Boolean = false,
    val batteryExempt: Boolean = true,
    val autostart: Boolean = false,
    val offeringAutostart: Boolean = false,
    val cards: List<AccountCardUi> = emptyList(),
    val cardMenu: String? = null,
    val removing: CardUi? = null,
    /** The last payment with the saved password was answered without being confirmed. */
    val passwordUnverified: Boolean = false,
)

/** One cell ENZONA asks when a card is added, by its name, and what is typed for it. */
@Immutable
data class CellUi(val label: String, val value: String)

/** How a send that did not add the card ended. */
enum class NotAddedKind {
    /** ENZONA answered no. */
    Refused,
    /** It failed without ENZONA's no: this phone, the session or a reply the app does not read. */
    Failed,
    /** ENZONA's answer did not arrive: the card may be in the account. */
    Unconfirmed,
}

/** Adding a card to the ENZONA account: its number and expiry, then the cells ENZONA asks, then how it went. */
@Immutable
sealed interface AddCardUi {
    /**
     * [card]: the card as typed, with the account's name, which ENZONA takes as its holder. [checking] while ENZONA
     * is asked for the cells.
     */
    data class Form(
        val card: CardUi,
        val number: String,
        val expiry: String,
        val numberError: String? = null,
        val expiryError: String? = null,
        val canContinue: Boolean = false,
        val checking: Boolean = false,
    ) : AddCardUi

    /** The cells of this attempt; hidden from screenshots. */
    data class Cells(val card: CardUi, val cells: List<CellUi>, val canSend: Boolean) : AddCardUi

    /** The send and how it went, on a screen of their own. */
    sealed interface Sent : AddCardUi

    /** On its way to ENZONA. */
    data class Sending(val card: CardUi) : Sent

    data class Added(val card: CardUi) : Sent

    /** [reason]: ENZONA's own words when it gave them, as a sentence. */
    data class NotAdded(val kind: NotAddedKind, val reason: String) : Sent
}

@Immutable
data class PasswordSettingsUi(val saved: Boolean, val value: String, val error: String? = null, val confirmingRemoval: Boolean = false)

