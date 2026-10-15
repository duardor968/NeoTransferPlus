package dev.duardo.neotransfer.plus.ui.model

import androidx.compose.runtime.Immutable
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
enum class LoginProblem { Rejected, ClientRejected, Expired, Offline, PageFailed, RestoreFailed, ClearFailed }

@Immutable
sealed interface LoginUi {
    data class Start(val problem: LoginProblem? = null) : LoginUi
    /** ENZONA's own sign-in page, inside the app. [loading] while a page loads. */
    data class Web(val loading: Boolean) : LoginUi
    /** The page answered; the app reads the account. */
    data object Connecting : LoginUi
}

// ---- Home ---------------------------------------------------------------------------------------------------

enum class RefreshState { Idle, Refreshing, Failed }

/** [recent] null until the history has been read once. */
@Immutable
data class HomeUi(
    val cards: List<WalletCardUi>,
    val selected: Int,
    val inProgress: List<OperationUi>,
    val recent: List<OperationUi>?,
    val refresh: RefreshState = RefreshState.Idle,
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
    data object Empty : ContactsContent
    data object NoResults : ContactsContent
    data class Items(val contacts: List<ContactUi>) : ContactsContent
}

@Immutable
data class ContactsUi(val query: String, val content: ContactsContent)

@Immutable
data class EditableCardUi(val id: String, val number: String, val label: String, val bank: String? = null, val error: String? = null)

@Immutable
data class EditablePhoneUi(val id: String, val number: String, val error: String? = null)

/** [id] null when creating. */
@Immutable
data class ContactEditUi(
    val id: String?,
    val name: String,
    val cards: List<EditableCardUi>,
    val phones: List<EditablePhoneUi>,
    val canSave: Boolean,
)

// ---- Services -----------------------------------------------------------------------------------------------

enum class Utility(val label: String) { Electricity("Electricidad"), Landline("Teléfono fijo"), Internet("Internet") }

enum class ServiceKind(val utility: Utility, val label: String, val referenceLabel: String) {
    Electricity(Utility.Electricity, "Electricidad", "Código de cliente"),
    Landline(Utility.Landline, "Teléfono fijo", "Número de teléfono"),
    Nauta(Utility.Internet, "Nauta", "Cuenta Nauta"),
    NautaHome(Utility.Internet, "Nauta Hogar", "Cuenta Nauta Hogar"),
}

/** A reference already paid from this phone, one tap from the catalogue. */
@Immutable
data class RecentReferenceUi(val kind: ServiceKind, val value: String, val lastPaid: Money?)

@Immutable
data class ServicesUi(val recent: List<RecentReferenceUi>)

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
    val lookup: ServiceLookupUi,
    val amount: String = "",
    val amountError: String? = null,
    val currency: String,
    val cards: List<WalletCardUi>,
    val source: Int,
    val canPay: Boolean,
    val paying: Boolean = false,
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

/** [saveContact] null when the recipient is already a contact or is an ENZONA account. */
@Immutable
data class TransferReviewUi(
    val recipient: RecipientUi,
    val amount: Money,
    val source: CardUi,
    val saveContact: Boolean?,
    val sending: Boolean = false,
)

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
    data class Unsupported(val message: String) : PayQrUi
}

/** Cobrar: the amount first, then the QR ENZONA issues for it. */
@Immutable
sealed interface ReceiveUi {
    data class Amount(val input: String, val currency: String, val creating: Boolean = false, val error: String? = null) : ReceiveUi
    data class Code(val payload: String, val amount: Money, val owner: String?) : ReceiveUi
}

/** The fingerprint unlocks the saved payment password; this asks for it once, the first time. */
@Immutable
data class PasswordSheetUi(val value: String, val action: String, val error: String? = null)

/** Outcome of one payment. [operation] carries what was sent. */
@Immutable
data class PaymentResultUi(val operation: OperationUi)

// ---- Settings -----------------------------------------------------------------------------------------------

@Immutable
data class AccountUi(val name: String, val username: String?, val phone: String?, val email: String?)

@Immutable
data class SettingsUi(
    val account: AccountUi?,
    val passwordSaved: Boolean,
    val appearance: Appearance,
    val notifications: Boolean,
    val version: String,
    val confirmingSignOut: Boolean = false,
    val signingOut: Boolean = false,
)

@Immutable
data class PasswordSettingsUi(val saved: Boolean, val value: String, val error: String? = null, val confirmingRemoval: Boolean = false)

