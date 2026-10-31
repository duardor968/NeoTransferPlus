package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.core.card.CardBank
import dev.duardo.neotransfer.plus.core.card.CardNumbers
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaBankOperation
import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaIncomingCredit
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.core.read.RemoteDates
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaWalletCard
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHistoryMatch
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionFailure
import dev.duardo.neotransfer.plus.designsystem.format.DecimalSeparator
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.ui.common.moneyMoved
import dev.duardo.neotransfer.plus.ui.model.AccountCardUi
import dev.duardo.neotransfer.plus.ui.model.AccountUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.LastMovementUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.ReadProblem
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import java.math.BigDecimal
import java.text.Collator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Card issuers by what ENZONA says of them, or by the card number ([CardNumbers.issuer]) for cards of other people. */
object Banks {
    fun ofPan(pan: String): String? = CardNumbers.issuer(pan).bank?.let(::shortName)

    fun shortName(bank: CardBank): String = when (bank) {
        CardBank.BPA -> "BPA"
        CardBank.BANDEC -> "BANDEC"
        CardBank.BANMET -> "Metropolitano"
    }

    /** Someone else's card, with the bank and currency its number tells. */
    fun foreignCard(id: String, pan: String, label: String? = null): ForeignCardUi {
        val issuer = CardNumbers.issuer(pan)
        return ForeignCardUi(id, pan, issuer.bank?.let(::shortName), label, issuer.currency)
    }

    /** `BANDEC · CUP`: what the number of [pan] tells; null when it tells nothing. */
    fun issuerOf(pan: String): String? = foreignCard("", pan).issuer

    /** The short name people use. ENZONA's own words when they match no known bank. */
    fun shortName(bankName: String?, bankCode: String?, pan: String?): String? {
        val text = listOfNotNull(bankName, bankCode).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            "popular" in text || bankCode == "01" || "bpa" in text -> "BPA"
            "crédito" in text || "credito" in text || bankCode == "02" || "bandec" in text -> "BANDEC"
            "metropolitano" in text || bankCode == "03" || "banmet" in text -> "Metropolitano"
            else -> pan?.let(::ofPan) ?: bankName?.trim()?.takeIf(String::isNotEmpty)
        }
    }
}

private fun fullName(name: String?, lastName: String?): String? =
    listOfNotNull(name?.trim(), lastName?.trim()).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }

private val expiryDigits = Regex("""(\d{2})/?(\d{2})""")
private val expiryIso = Regex("""(\d{4})-(\d{2}).*""")

/** Expiry as printed on cards, `MM/AA`; ENZONA's text when its shape is not one of the known ones. */
fun expiryText(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    expiryIso.matchEntire(value)?.let { return "${it.groupValues[2]}/${it.groupValues[1].takeLast(2)}" }
    expiryDigits.matchEntire(value)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
    return value
}

fun EnzonaWalletCard.toUi(): CardUi {
    val bank = Banks.shortName(card.bankName, card.bankCode, pan)
    return CardUi(
        id = card.fundingSourceUuid,
        bank = bank ?: "Tarjeta",
        art = CardArt.of(bank),
        number = pan,
        last4 = card.last4 ?: pan?.takeLast(4),
        currency = card.currency ?: balance?.currency ?: "CUP",
        holder = card.cardholder,
        expiry = expiryText(card.expiryDate),
    )
}

/** The card ENZONA uses by default: `primary_source` true, as a boolean or as `1`. */
val EnzonaWalletCard.primary: Boolean get() = card.primarySource?.lowercase(Locale.ROOT) in setOf("true", "1")

/** The account's cards in Ajustes; those in [busy] wait for ENZONA. */
fun accountCardsUi(cards: List<EnzonaWalletCard>, busy: Set<String>): List<AccountCardUi> =
    cards.map { AccountCardUi(it.toUi(), it.primary, it.card.fundingSourceUuid in busy) }

/** The amount people can spend: available when ENZONA gives it, the book balance otherwise. */
fun EnzonaWalletCard.balanceUi(): BalanceUi {
    val balance = balance ?: return BalanceUi.Unknown
    val amount = balance.availableBalance ?: balance.bookBalance ?: return BalanceUi.Unknown
    val at = balanceObservedAt ?: return BalanceUi.Unknown
    return BalanceUi.Known(Money(amount, balance.currency ?: card.currency ?: "CUP"), Instant.ofEpochMilli(at))
}

fun walletUi(cards: List<EnzonaWalletCard>, refreshing: Set<String>): List<WalletCardUi> =
    cards.map { WalletCardUi(it.toUi(), it.balanceUi(), it.card.fundingSourceUuid in refreshing) }

fun EnzonaAccount.toUi(): AccountUi? {
    val name = fullName(name, lastName) ?: username ?: return null
    return AccountUi(name, username, phone, email)
}

/** [addressBook]: ids that come from the phone's address book. [movements]: the money moved with them, newest first. */
fun Contact.toUi(addressBook: Set<String>? = null, movements: List<OperationUi> = emptyList()): ContactUi = ContactUi(
    id, name,
    cards.map { Banks.foreignCard(it.id, it.pan, it.label) },
    phones,
    fromAddressBook = addressBook?.contains(id) == true,
    last = movements.firstOrNull()?.let { LastMovementUi(it.amount, it.at) },
)

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

/** The Contacts tab: people with a movement or a saved card, newest movement first, then by name. */
fun List<ContactUi>.people(): List<ContactUi> = filter { it.last != null || it.cards.isNotEmpty() }
    .sortedWith(compareByDescending<ContactUi, Instant?>(nullsFirst()) { it.last?.at }.thenComparator { a, b -> collator.compare(a.name, b.name) })

/** The contact that owns [pan], only when exactly one does. */
fun List<Contact>.ownerOf(pan: String): Contact? = filter { contact -> contact.cards.any { it.pan == pan } }.singleOrNull()

/**
 * The movements tied to each contact, by contact id, newest first. ENZONA's history names the other side and four
 * digits, never enough to tell who: only the card transfers sent from this phone, whose full number the journal
 * keeps, are tied, and only to the one contact that has that card. Each is the history row once ENZONA lists it, so
 * it opens the same receipt as in Activity. Money that certainly did not move is left out.
 */
fun contactMovements(
    journal: List<EnzonaJournalEntry>, transactions: List<EnzonaTransaction>, ownUsername: String?,
    cards: List<CardUi>, contacts: List<Contact>, zone: ZoneId,
): Map<String, List<OperationUi>> {
    val history = transactions.associateBy { it.transactionUuid }
    return journal.filter { it.intent.type == EnzonaIntentType.CARD_TRANSFER && it.state != EnzonaIntentState.PREPARED }
        .sortedByDescending { it.intent.preparedAt }
        .mapNotNull { entry ->
            val contact = contacts.ownerOf(entry.intent.destination.value) ?: return@mapNotNull null
            val sentAt = Instant.ofEpochMilli(entry.intent.preparedAt)
            val row = entry.remoteId?.let(history::get)?.toUi(ownUsername, cards, zone)?.let { it.copy(at = it.at ?: sentAt) }
                ?: entry.toUi(cards, contacts)
            (contact.id to row.copy(contactId = contact.id)).takeIf { row.status.moneyMoved }
        }
        .groupBy({ it.first }, { it.second })
}

// ---- Operations ---------------------------------------------------------------------------------------------

private val confirmedWords = setOf("aceptada", "aceptado", "completada", "completado", "exitosa", "exitoso", "confirmada", "aprobada", "pagada", "realizada")
private val pendingWords = setOf("pendiente", "en proceso", "procesando")
private val rejectedWords = setOf("fallida", "fallido", "rechazada", "rechazado", "cancelada", "denegada", "error")

private fun statusOf(words: String?): Pair<OperationStatus, String?> {
    val text = words?.trim()?.takeIf(String::isNotEmpty) ?: return OperationStatus.Other to null
    val key = text.lowercase(Locale.ROOT)
    return when {
        key in confirmedWords -> OperationStatus.Confirmed to null
        key in pendingWords -> OperationStatus.Pending to null
        key in rejectedWords -> OperationStatus.Rejected to null
        else -> OperationStatus.Other to text
    }
}

private fun serviceKindOf(text: String): OperationKind {
    val key = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
    return when {
        "electric" in key -> OperationKind.Electricity
        "hogar" in key -> OperationKind.NautaHome
        "nauta" in key -> OperationKind.Nauta
        "fijo" in key || "telef" in key -> OperationKind.Landline
        else -> OperationKind.Service
    }
}

private val OperationKind.serviceTitle: String?
    get() = when (this) {
        OperationKind.Electricity -> "Electricidad"
        OperationKind.Landline -> "Teléfono fijo"
        OperationKind.Nauta -> "Nauta"
        OperationKind.NautaHome -> "Nauta Hogar"
        else -> null
    }

private fun List<CardUi>.byLast4(last4: String?): CardUi? = last4?.let { l -> filter { it.last4 == l }.singleOrNull() }

/** One row of the ENZONA account history as people read it. */
fun EnzonaTransaction.toUi(ownUsername: String?, cards: List<CardUi>, zone: ZoneId): OperationUi {
    val incoming = EnzonaCredits.incoming(this, ownUsername) != null
    val words = listOfNotNull(transactionDenomination, description, merchantName).joinToString(" ")
    val kind = when {
        incoming -> OperationKind.Incoming
        clientId != null || invoice != null || invoiceNumber != null -> serviceKindOf(words)
        merchantName != null || merchantAlias != null -> OperationKind.QrPayment
        recipient?.last4 != null -> OperationKind.CardTransfer
        recipient?.username != null -> OperationKind.AccountTransfer
        else -> serviceKindOf(words)
    }
    val (status, label) = statusOf(statusDenomination ?: transactionStatus)
    val source = source
    val recipient = recipient
    val title = when (kind) {
        OperationKind.Incoming -> fullName(source?.name, source?.lastName) ?: source?.username
        OperationKind.CardTransfer -> fullName(recipient?.name, recipient?.lastName) ?: recipient?.last4?.let { "•••• $it" }
        OperationKind.AccountTransfer -> fullName(recipient?.name, recipient?.lastName) ?: recipient?.username
        OperationKind.QrPayment -> merchantName ?: merchantAlias
        else -> kind.serviceTitle ?: transactionDenomination
    } ?: transactionDenomination ?: "Operación"
    val counterpart = when (kind) {
        OperationKind.Incoming -> source?.last4?.let { CounterpartUi.CardEnding(it) } ?: source?.username?.let { CounterpartUi.Account(it) }
        OperationKind.CardTransfer -> recipient?.last4?.let { CounterpartUi.CardEnding(it, recipient.bank) }
        OperationKind.AccountTransfer -> recipient?.username?.let { CounterpartUi.Account(it) }
        OperationKind.QrPayment -> null
        else -> clientId?.let { CounterpartUi.Reference(it) }
    }
    return OperationUi(
        id = "tx:$transactionUuid",
        kind = kind,
        title = title,
        status = status,
        at = RemoteDates.parse(createdAt?.value, zone),
        amount = (amount ?: totalAmount)?.let { Money(it.abs(), currency ?: "CUP") },
        card = if (incoming) cards.byLast4(recipient?.last4) else cards.byLast4(source?.last4),
        counterpart = counterpart,
        transactionId = transactionUuid,
        description = description,
        statusLabel = label,
        dateText = createdAt?.value,
    )
}

private const val CheckFirst = "Revisa la actividad antes de repetirla."
const val UnconfirmedReason = "ENZONA no confirmó la operación. $CheckFirst"
const val NoReplyReason = "Sin respuesta de ENZONA. $CheckFirst"
const val NotDeliveredReason = "No llegó a ENZONA. No se movió dinero."
const val NotInHistoryReason = "No aparece en tu historial de ENZONA. Si el saldo no bajó, no se movió dinero."
const val MarkedNotDoneReason = "La marcaste como no realizada. Si aparece en tu historial de ENZONA, se actualizará."
const val ByAmountReason = "Confirmada en tu historial de ENZONA por importe y hora."

/** ENZONA's own words, quoted: «ENZONA respondió: “Saldo insuficiente”.» */
private fun answered(text: String) = "ENZONA respondió: “${text.removeSuffix(".")}”."

/** Unconfirmed and missing from a history read made at least a day after it. */
private val EnzonaJournalEntry.missing: Boolean
    get() = state == EnzonaIntentState.UNKNOWN && notFoundAt?.let { it >= intent.preparedAt + EnzonaReconciliation.AFTER } == true

/** Why this attempt is where it is; [notice] is what this process saw of it. Null for a plain confirmation. */
private fun EnzonaJournalEntry.reason(notice: EnzonaPaymentNotice?): String? = when (state) {
    EnzonaIntentState.NOT_DELIVERED ->
        if (notice == EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED) "No se envió: la autorización caducó al salir de la app." else NotDeliveredReason
    EnzonaIntentState.UNKNOWN -> when {
        markedNotDoneAt != null -> MarkedNotDoneReason
        missing -> NotInHistoryReason
        notice == EnzonaPaymentNotice.NOT_SAVED -> "No se pudo guardar el resultado. $CheckFirst"
        faultText != null -> "${answered(faultText)} $CheckFirst"
        httpStatus == 0 -> NoReplyReason
        else -> UnconfirmedReason
    }
    EnzonaIntentState.REJECTED -> faultText?.let(::answered) ?: "ENZONA rechazó la operación."
    EnzonaIntentState.CONFIRMED -> ByAmountReason.takeIf { matchedBy == EnzonaHistoryMatch.AMOUNT_AND_TIME }
    EnzonaIntentState.PREPARED, EnzonaIntentState.SUBMITTING -> null
}

/** What a failed read means on screen. INVALID_INPUT never comes from a read; if it did, the app failed. */
fun EnzonaFailure.readProblem(): ReadProblem = when (this) {
    EnzonaFailure.NETWORK -> ReadProblem.Offline
    EnzonaFailure.TIMEOUT -> ReadProblem.Timeout
    EnzonaFailure.REJECTED -> ReadProblem.Rejected
    EnzonaFailure.INVALID_RESPONSE -> ReadProblem.Unrecognized
    EnzonaFailure.SIGNED_OUT -> ReadProblem.Expired
    EnzonaFailure.LOCAL, EnzonaFailure.INVALID_INPUT -> ReadProblem.Local
}

/** Why a reviewed payment was not sent: the session failure that stopped it, or null when this phone could not prepare it. */
fun notSentText(session: EnzonaSessionFailure?): String = when (session) {
    EnzonaSessionFailure.UNAVAILABLE -> "Sin conexión. No se movió dinero."
    EnzonaSessionFailure.TIMEOUT -> "ENZONA tardó demasiado en responder. No se movió dinero."
    EnzonaSessionFailure.SIGN_IN_AGAIN, EnzonaSessionFailure.RESTORE_FAILED, EnzonaSessionFailure.LOCAL_CLEAR_FAILED ->
        "La sesión de ENZONA terminó antes del envío."
    EnzonaSessionFailure.CLIENT_REJECTED, EnzonaSessionFailure.ACCESS_DENIED, EnzonaSessionFailure.SERVER_ERROR,
    EnzonaSessionFailure.CALLBACK_REJECTED -> "ENZONA no aceptó la solicitud. No se movió dinero."
    EnzonaSessionFailure.LOCAL_SAVE_FAILED, null -> "No se pudo preparar el pago. No se movió dinero."
}

fun EnzonaInvoiceService.kind(): OperationKind = when (this) {
    EnzonaInvoiceService.ELECTRICITY -> OperationKind.Electricity
    EnzonaInvoiceService.FIXED_PHONE -> OperationKind.Landline
    EnzonaInvoiceService.NAUTA -> OperationKind.Nauta
    EnzonaInvoiceService.NAUTA_HOME -> OperationKind.NautaHome
}

/**
 * A payment sent from this phone. [title] names a QR merchant or account holder while this session still knows it;
 * [notSent] says why a payment never left the phone; [notice] is what this process saw of it.
 */
fun EnzonaJournalEntry.toUi(cards: List<CardUi>, contacts: List<Contact>, notice: EnzonaPaymentNotice? = null,
    title: String? = null, notSent: String? = null): OperationUi {
    val intent = intent
    val service = intent.invoiceService?.let { name -> EnzonaInvoiceService.entries.firstOrNull { it.name == name } }
    val kind = when (intent.type) {
        EnzonaIntentType.CARD_TRANSFER -> OperationKind.CardTransfer
        EnzonaIntentType.ACCOUNT_TRANSFER -> OperationKind.AccountTransfer
        EnzonaIntentType.QR_MERCHANT_PAYMENT -> OperationKind.QrPayment
        EnzonaIntentType.INVOICE_PAYMENT -> service?.kind() ?: OperationKind.Service
    }
    val destination = intent.destination.value
    val contact = if (kind == OperationKind.CardTransfer) contacts.ownerOf(destination) else null
    val status = when (state) {
        EnzonaIntentState.PREPARED -> if (notSent != null) OperationStatus.NotSent else OperationStatus.Sending
        EnzonaIntentState.SUBMITTING -> OperationStatus.Sending
        EnzonaIntentState.UNKNOWN -> if (markedNotDoneAt != null) OperationStatus.NotDone else OperationStatus.Unconfirmed
        EnzonaIntentState.CONFIRMED -> OperationStatus.Confirmed
        EnzonaIntentState.REJECTED -> OperationStatus.Rejected
        EnzonaIntentState.NOT_DELIVERED -> OperationStatus.NotSent
    }
    return OperationUi(
        id = "j:${intent.id}",
        kind = kind,
        title = when (kind) {
            OperationKind.CardTransfer -> contact?.name ?: formatCardNumber(destination)
            OperationKind.AccountTransfer -> title ?: intent.destination.username ?: destination
            OperationKind.QrPayment -> title ?: "Pago con QR"
            else -> kind.serviceTitle ?: "Pago de servicio"
        },
        status = status,
        at = Instant.ofEpochMilli(intent.preparedAt),
        amount = intent.amount.toBigDecimalOrNull()?.let { Money(it, intent.currency) },
        card = cards.firstOrNull { it.id == intent.cardUuid },
        counterpart = when (kind) {
            OperationKind.CardTransfer -> CounterpartUi.Card(destination, Banks.issuerOf(destination))
            OperationKind.AccountTransfer -> intent.destination.username?.let { CounterpartUi.Account(it) }
            OperationKind.QrPayment -> null
            else -> CounterpartUi.Reference(destination)
        },
        contactId = contact?.id,
        reason = if (state == EnzonaIntentState.PREPARED) notSent else reason(notice),
        transactionId = remoteId,
        description = intent.description,
        canMarkNotDone = missing && markedNotDoneAt == null,
    )
}

/** Attempts still worth showing next to the history: in flight, unconfirmed, rejected or undelivered, and not in the history. */
private fun EnzonaJournalEntry.pending(known: Set<String>): Boolean = state in setOf(EnzonaIntentState.SUBMITTING,
    EnzonaIntentState.UNKNOWN, EnzonaIntentState.REJECTED, EnzonaIntentState.NOT_DELIVERED) && remoteId !in known

/** The ENZONA account's record: history rows and this phone's payments without a history row, newest first. */
fun accountActivity(
    transactions: List<EnzonaTransaction>, journal: List<EnzonaJournalEntry>, ownUsername: String?,
    cards: List<CardUi>, contacts: List<Contact>, zone: ZoneId,
): List<OperationUi> {
    val known = transactions.map { it.transactionUuid }.toSet()
    val rows = transactions.distinctBy { it.transactionUuid }.map { it.toUi(ownUsername, cards, zone) } +
        journal.filter { it.pending(known) }.map { it.toUi(cards, contacts) }
    return rows.sortedWith(compareByDescending<OperationUi, Instant?>(nullsFirst()) { it.at })
}

/** How long a payment that never reached ENZONA waits on Home to be sent again. */
const val RetryWindowMillis = 12 * 60 * 60_000L

/** What makes two attempts the same payment: card, destination, amount and currency, and the service. */
private val EnzonaIntent.values: List<Any?> get() = listOf(type, cardUuid, destination.value, amount, currency, invoiceService)

/**
 * Payments of this phone that need watching on Home: in flight, unconfirmed and not marked as not done, and for
 * [RetryWindowMillis] the last attempt of a payment that never reached ENZONA, until it is sent again.
 */
fun inProgress(journal: List<EnzonaJournalEntry>, known: Set<String>, cards: List<CardUi>, contacts: List<Contact>, now: Long): List<OperationUi> {
    // An attempt prepared and never sent (the fingerprint cancelled) does not replace the one that waits.
    val latest = journal.filter { it.state != EnzonaIntentState.PREPARED }.groupBy { it.intent.values }
        .mapValues { (_, attempts) -> attempts.maxOf { it.intent.preparedAt } }
    return journal.filter {
        val waiting = when (it.state) {
            EnzonaIntentState.SUBMITTING -> true
            EnzonaIntentState.UNKNOWN -> it.markedNotDoneAt == null
            EnzonaIntentState.NOT_DELIVERED -> latest[it.intent.values] == it.intent.preparedAt && now - it.intent.preparedAt < RetryWindowMillis
            else -> false
        }
        waiting && it.remoteId !in known
    }
        .sortedByDescending { it.intent.preparedAt }
        .map { it.toUi(cards, contacts) }
}

/**
 * A transfer of [amount] [currency] from [cardUuid] to [destination] that is still on its way or unconfirmed and not
 * in the history ([known]): sending the same again may pay twice.
 */
fun pendingTransfer(journal: List<EnzonaJournalEntry>, known: Set<String>, cardUuid: String, destination: String, amount: BigDecimal, currency: String): EnzonaJournalEntry? =
    journal.filter {
        (it.intent.type == EnzonaIntentType.CARD_TRANSFER || it.intent.type == EnzonaIntentType.ACCOUNT_TRANSFER) &&
            it.intent.cardUuid == cardUuid && it.intent.destination.value == destination && it.intent.currency.equals(currency, ignoreCase = true) &&
            it.intent.amount.toBigDecimalOrNull()?.compareTo(amount) == 0 &&
            (it.state == EnzonaIntentState.SUBMITTING || it.state == EnzonaIntentState.UNKNOWN && it.markedNotDoneAt == null) &&
            it.remoteId !in known
    }.maxByOrNull { it.intent.preparedAt }

private val creditActions = setOf("cr", "c", "credito", "crédito", "credit", "abono", "entrada")

/** One card's own bank record (ATM, POS, Transfermóvil, ENZONA) from its last balance read. */
fun EnzonaWalletCard.movements(zone: ZoneId): List<OperationUi> {
    val card = toUi()
    val currency = balance?.currency ?: card.currency
    return balance?.operations.orEmpty().mapIndexed { index, operation -> operation.toUi(card, currency, index, zone) }
}

private fun EnzonaBankOperation.toUi(card: CardUi, currency: String, index: Int, zone: ZoneId): OperationUi {
    val credit = action?.trim()?.lowercase(Locale.ROOT) in creditActions
    return OperationUi(
        id = "m:${card.id}:$index",
        kind = if (credit) OperationKind.Incoming else OperationKind.CardMovement,
        title = description?.trim()?.takeIf(String::isNotEmpty) ?: alias ?: channel ?: "Movimiento",
        status = OperationStatus.Confirmed,
        at = RemoteDates.parse(operationDate?.value, zone),
        amount = amount?.let { Money(it.abs(), currency) },
        card = card,
        transactionId = code,
        channel = channel,
        dateText = operationDate?.value,
    )
}

/** Card recipients this phone sent money to, newest first, named only by an unambiguous contact. */
fun recentRecipients(journal: List<EnzonaJournalEntry>, contacts: List<Contact>, limit: Int = 8): List<RecipientUi.Card> =
    journal.filter { it.intent.type == EnzonaIntentType.CARD_TRANSFER && it.state != EnzonaIntentState.PREPARED }
        .sortedByDescending { it.intent.preparedAt }
        .map { it.intent.destination.value }
        .distinct()
        .take(limit)
        .map { pan ->
            val contact = contacts.ownerOf(pan)
            val card = contact?.cards?.first { it.pan == pan }
            RecipientUi.Card(Banks.foreignCard(card?.id ?: "pan:$pan", pan, card?.label), contact?.name, contact?.id)
        }

fun EnzonaInvoiceService.serviceKind(): ServiceKind = when (this) {
    EnzonaInvoiceService.ELECTRICITY -> ServiceKind.Electricity
    EnzonaInvoiceService.FIXED_PHONE -> ServiceKind.Landline
    EnzonaInvoiceService.NAUTA -> ServiceKind.Nauta
    EnzonaInvoiceService.NAUTA_HOME -> ServiceKind.NautaHome
}

fun ServiceKind.invoiceService(): EnzonaInvoiceService = when (this) {
    ServiceKind.Electricity -> EnzonaInvoiceService.ELECTRICITY
    ServiceKind.Landline -> EnzonaInvoiceService.FIXED_PHONE
    ServiceKind.Nauta -> EnzonaInvoiceService.NAUTA
    ServiceKind.NautaHome -> EnzonaInvoiceService.NAUTA_HOME
}

/** A reference in Guardados as [SavedReferences] keeps its name and removal: the service and the value ENZONA takes. */
fun referenceKey(kind: ServiceKind, value: String): String = "${kind.invoiceService().name}:$value"

/**
 * Guardados: every reference ENZONA confirmed a payment to, by service and then newest payment, with what went last
 * and the [names] given to them. One taken out ([removed], when) stays out until a payment prepared after that.
 */
fun savedReferences(journal: List<EnzonaJournalEntry>, names: Map<String, String>, removed: Map<String, Long>): List<SavedReferenceUi> =
    journal.filter { it.intent.type == EnzonaIntentType.INVOICE_PAYMENT && it.state == EnzonaIntentState.CONFIRMED }
        .sortedByDescending { it.intent.preparedAt }
        .groupBy { it.intent.invoiceService to it.intent.destination.value }
        .mapNotNull { (key, payments) ->
            val kind = EnzonaInvoiceService.entries.firstOrNull { it.name == key.first }?.serviceKind() ?: return@mapNotNull null
            val last = payments.first().intent
            val id = referenceKey(kind, key.second)
            if (removed[id]?.let { last.preparedAt <= it } == true) return@mapNotNull null
            SavedReferenceUi(kind, key.second, names[id], last.amount.toBigDecimalOrNull()?.let { Money(it, last.currency) })
        }
        .sortedBy { it.kind.ordinal }

private val noticeSymbols = DecimalFormatSymbols(Locale.ROOT).apply {
    decimalSeparator = DecimalSeparator
    groupingSeparator = NoBreak
}

/**
 * "Laura Méndez te envió 12 480 CUP", "… 300.50 CUP": the sender as ENZONA names them, never a guess from the local
 * agenda; thousands grouped as everywhere in the app.
 */
fun EnzonaIncomingCredit.noticeTitle(): String {
    val sender = fullName(senderName, senderLastName) ?: senderUsername ?: "Alguien"
    val pattern = if (amount.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
    return "$sender te envió ${DecimalFormat(pattern, noticeSymbols).format(amount)} ${currency ?: "CUP"}"
}
