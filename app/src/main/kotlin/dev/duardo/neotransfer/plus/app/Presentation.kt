package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaBankOperation
import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaIncomingCredit
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.data.EnzonaWalletCard
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.ui.model.AccountUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RecentReferenceUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Card issuers by what ENZONA says of them, or by the number's prefix for cards of other people. */
object Banks {
    private val prefixes = mapOf("9212" to "BPA", "9205" to "BANDEC", "9225" to "BANMET")

    fun ofPan(pan: String): String? = prefixes[pan.take(4)]

    /** The short name people use. ENZONA's own words when they match no known bank. */
    fun shortName(bankName: String?, bankCode: String?, pan: String?): String? {
        val text = listOfNotNull(bankName, bankCode).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            "popular" in text || bankCode == "01" || "bpa" in text -> "BPA"
            "crédito" in text || "credito" in text || bankCode == "02" || "bandec" in text -> "BANDEC"
            "metropolitano" in text || bankCode == "03" || "banmet" in text -> "BANMET"
            else -> pan?.let(::ofPan) ?: bankName?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    fun art(short: String?): CardArt = when (short) {
        "BPA" -> CardArt.BPA
        "BANDEC" -> CardArt.BANDEC
        else -> CardArt.Neutral
    }
}

/**
 * ENZONA's date text, read without guessing a calendar: ISO with or without offset, or `yyyy-MM-dd HH:mm[:ss]`.
 * Text without an offset is Cuban local time. Anything else stays unread and is shown as sent.
 */
object RemoteDates {
    private val local = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    )

    fun parse(text: String?, zone: ZoneId): Instant? {
        val value = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        runCatching { return OffsetDateTime.parse(value).toInstant() }
        runCatching { return Instant.parse(value) }
        for (format in local) runCatching { return LocalDateTime.parse(value, format).atZone(zone).toInstant() }
        runCatching { return LocalDate.parse(value).atStartOfDay(zone).toInstant() }
        return null
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
        art = Banks.art(bank),
        number = pan,
        last4 = card.last4 ?: pan?.takeLast(4),
        currency = card.currency ?: balance?.currency ?: "CUP",
        holder = card.cardholder,
        expiry = expiryText(card.expiryDate),
    )
}

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

fun Contact.toUi(): ContactUi = ContactUi(
    id, name,
    cards.map { ForeignCardUi(it.id, it.pan, Banks.ofPan(it.pan), it.label) },
    phones,
)

private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }

fun List<Contact>.sortedByName(): List<Contact> = sortedWith { a, b -> collator.compare(a.name, b.name) }

/** The contact that owns [pan], only when exactly one does. */
fun List<Contact>.ownerOf(pan: String): Contact? = filter { contact -> contact.cards.any { it.pan == pan } }.singleOrNull()

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

const val UnconfirmedReason = "ENZONA no confirmó la operación. Revisa la actividad antes de repetirla."

private fun EnzonaPaymentNotice?.reason(state: EnzonaIntentState): String? = when {
    this == EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED -> "No se envió: la autorización caducó al salir de la app."
    this == EnzonaPaymentNotice.NOT_SAVED -> "No se pudo guardar el resultado. Revisa la actividad antes de repetirla."
    state == EnzonaIntentState.UNKNOWN -> UnconfirmedReason
    state == EnzonaIntentState.REJECTED -> "ENZONA rechazó la operación."
    else -> null
}

fun EnzonaInvoiceService.kind(): OperationKind = when (this) {
    EnzonaInvoiceService.ELECTRICITY -> OperationKind.Electricity
    EnzonaInvoiceService.FIXED_PHONE -> OperationKind.Landline
    EnzonaInvoiceService.NAUTA -> OperationKind.Nauta
    EnzonaInvoiceService.NAUTA_HOME -> OperationKind.NautaHome
}

/**
 * A payment sent from this phone. [title] names a QR merchant or account holder while this session still knows it;
 * [notSent] says why a payment never left the phone.
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
    val status = when {
        notSent != null && state == EnzonaIntentState.PREPARED -> OperationStatus.NotSent
        else -> when (state) {
        EnzonaIntentState.PREPARED, EnzonaIntentState.SUBMITTING -> OperationStatus.Sending
        EnzonaIntentState.UNKNOWN -> OperationStatus.Unconfirmed
        EnzonaIntentState.CONFIRMED -> OperationStatus.Confirmed
        EnzonaIntentState.REJECTED -> OperationStatus.Rejected
        }
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
            OperationKind.CardTransfer -> CounterpartUi.Card(destination, Banks.ofPan(destination))
            OperationKind.AccountTransfer -> intent.destination.username?.let { CounterpartUi.Account(it) }
            OperationKind.QrPayment -> null
            else -> CounterpartUi.Reference(destination)
        },
        contactId = contact?.id,
        reason = if (status == OperationStatus.NotSent) notSent else notice.reason(state),
        transactionId = remoteId,
        description = intent.description,
    )
}

/** Attempts still worth showing next to the history: in flight, unconfirmed, or rejected and not in the history. */
private fun EnzonaJournalEntry.pending(known: Set<String>): Boolean =
    state in setOf(EnzonaIntentState.SUBMITTING, EnzonaIntentState.UNKNOWN, EnzonaIntentState.REJECTED) && remoteId !in known

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

/** Payments of this phone that need watching on Home: in flight or unconfirmed. */
fun inProgress(journal: List<EnzonaJournalEntry>, known: Set<String>, cards: List<CardUi>, contacts: List<Contact>): List<OperationUi> =
    journal.filter { (it.state == EnzonaIntentState.SUBMITTING || it.state == EnzonaIntentState.UNKNOWN) && it.remoteId !in known }
        .sortedByDescending { it.intent.preparedAt }
        .map { it.toUi(cards, contacts) }

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
            RecipientUi.Card(ForeignCardUi(card?.id ?: "pan:$pan", pan, Banks.ofPan(pan), card?.label), contact?.name, contact?.id)
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

/** References paid from this phone, newest first, with the last confirmed amount. */
fun recentReferences(journal: List<EnzonaJournalEntry>, limit: Int = 6): List<RecentReferenceUi> =
    journal.filter { it.intent.type == EnzonaIntentType.INVOICE_PAYMENT && it.state != EnzonaIntentState.PREPARED }
        .sortedByDescending { it.intent.preparedAt }
        .groupBy { it.intent.invoiceService to it.intent.destination.value }
        .mapNotNull { (key, entries) ->
            val service = EnzonaInvoiceService.entries.firstOrNull { it.name == key.first } ?: return@mapNotNull null
            val paid = entries.firstOrNull { it.state == EnzonaIntentState.CONFIRMED }
            RecentReferenceUi(service.serviceKind(), key.second, paid?.intent?.amount?.toBigDecimalOrNull()?.let { Money(it, paid.intent.currency) })
        }
        .take(limit)

/** "Laura Méndez te envió 300 CUP": the sender as ENZONA names them, never a guess from the local agenda. */
fun EnzonaIncomingCredit.noticeTitle(): String {
    val sender = fullName(senderName, senderLastName) ?: senderUsername ?: "Alguien"
    val amount = amount.stripTrailingZeros().let { if (it.scale() <= 0) it.setScale(0) else it.setScale(2) }.toPlainString()
    return "$sender te envió $amount ${currency ?: "CUP"}"
}
