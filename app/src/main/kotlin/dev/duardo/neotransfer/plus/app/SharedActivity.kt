package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.core.activity.ActivitySync
import dev.duardo.neotransfer.plus.core.activity.SyncDirection
import dev.duardo.neotransfer.plus.core.activity.SyncKind
import dev.duardo.neotransfer.plus.core.activity.SyncOperation
import dev.duardo.neotransfer.plus.core.activity.SyncStatus
import dev.duardo.neotransfer.plus.core.card.CardNumbers
import dev.duardo.neotransfer.plus.ui.common.label
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationOrigin
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import java.time.Instant
import kotlin.math.abs

// ---- What NeoTransfer reads from this app -------------------------------------------------------------------

/**
 * The ENZONA account's record as this app shows it ([accountActivity] rows, with this phone's attempts that have no
 * history row), in the shared form and window. Card statement lines and NeoTransfer's rows are never in it.
 */
fun exportedActivity(own: List<OperationUi>, now: Long): List<SyncOperation> =
    ActivitySync.window(own.mapNotNull { it.toSync() }, now)

private fun OperationUi.toSync(): SyncOperation? {
    if (kind == OperationKind.CardMovement || origin == OperationOrigin.NeoTransfer) return null
    val at = at?.toEpochMilli() ?: return null
    return SyncOperation(
        id = id,
        kind = kind.toSync(),
        status = when (status) {
            OperationStatus.Sending, OperationStatus.Pending -> SyncStatus.PENDING
            OperationStatus.Confirmed -> SyncStatus.CONFIRMED
            OperationStatus.Rejected -> SyncStatus.REJECTED
            // A state word of ENZONA's that this app does not read: as far as it knows, not confirmed.
            OperationStatus.Unconfirmed, OperationStatus.Other -> SyncStatus.UNCONFIRMED
            OperationStatus.NotSent -> SyncStatus.NOT_SENT
            OperationStatus.NotDone -> SyncStatus.NOT_DONE
        },
        title = title,
        at = at,
        amount = amount?.amount?.abs(),
        currency = (amount?.currency ?: card?.currency.orEmpty()).uppercase(),
        direction = if (kind.incoming) SyncDirection.IN else SyncDirection.OUT,
        card = card?.let { it.number ?: it.last4 }.orEmpty(),
        counterpart = when (val c = counterpart) {
            is CounterpartUi.Card -> c.number
            is CounterpartUi.CardEnding -> c.last4
            is CounterpartUi.Account -> c.username
            is CounterpartUi.Reference -> c.value
            null -> ""
        },
        contactId = contactId.orEmpty(),
    )
}

private fun OperationKind.toSync(): SyncKind = when (this) {
    OperationKind.CardTransfer -> SyncKind.CARD_TRANSFER
    OperationKind.AccountTransfer -> SyncKind.ACCOUNT_TRANSFER
    OperationKind.Incoming -> SyncKind.INCOMING
    OperationKind.QrPayment -> SyncKind.QR_PAYMENT
    OperationKind.Electricity -> SyncKind.ELECTRICITY
    OperationKind.Landline -> SyncKind.LANDLINE
    OperationKind.Nauta -> SyncKind.NAUTA
    OperationKind.NautaHome -> SyncKind.NAUTA_HOME
    OperationKind.Service -> SyncKind.SERVICE
    OperationKind.Water -> SyncKind.WATER
    OperationKind.MobileTopUp -> SyncKind.MOBILE_TOPUP
    OperationKind.CubacelTransfer -> SyncKind.CUBACEL_TRANSFER
    OperationKind.CardMovement, OperationKind.Other -> SyncKind.OTHER
}

// ---- What this app shows of NeoTransfer ---------------------------------------------------------------------

/** How far apart a card statement line and NeoTransfer's operation can be and still be the same movement. */
private const val SameMovementWindowMillis = 10 * 60_000L

/**
 * NeoTransfer's rows as this app shows them, read-only and with its dot; null when NeoTransfer is not there ([peer]
 * null). One that is the same movement as a line of [statements] (same card, amount and direction, ten minutes apart
 * at most, the only candidate on each side) becomes that line, under its id, told as NeoTransfer tells it.
 */
fun peerRows(peer: List<SyncOperation>?, statements: List<OperationUi>, cards: List<CardUi>): List<OperationUi>? {
    peer ?: return null
    val rows = peer.map { it.toUi(cards) }
    val candidates = rows.associate { row -> row.id to statements.filter { sameMovement(row, it) } }
    val claims = statements.associate { line -> line.id to rows.count { sameMovement(it, line) } }
    return rows.map { row ->
        val line = candidates.getValue(row.id).singleOrNull()?.takeIf { claims[it.id] == 1 } ?: return@map row
        line.copy(kind = row.kind, title = row.title, counterpart = row.counterpart, contactId = row.contactId,
            origin = OperationOrigin.NeoTransfer)
    }
}

/**
 * NeoTransfer's [row] and a bank statement [line]: same own card, amount and direction, close in time. A line the bank
 * marks as made through ENZONA is never NeoTransfer's.
 */
private fun sameMovement(row: OperationUi, line: OperationUi): Boolean {
    if (line.channel?.contains("ENZONA", ignoreCase = true) == true) return false
    val amount = row.amount?.amount ?: return false
    val at = row.at ?: return false
    val lineAt = line.at ?: return false
    return line.amount?.amount?.compareTo(amount) == 0 && row.kind.incoming == line.kind.incoming &&
        abs(at.toEpochMilli() - lineAt.toEpochMilli()) <= SameMovementWindowMillis && sameCard(row.card, line.card)
}

/** Full numbers when both have one; otherwise the last four digits. */
private fun sameCard(a: CardUi?, b: CardUi?): Boolean {
    if (a == null || b == null) return false
    val numbers = listOfNotNull(a.number, b.number)
    if (numbers.size == 2) return numbers[0] == numbers[1]
    val last4 = listOf(a.last4 ?: a.number?.takeLast(4), b.last4 ?: b.number?.takeLast(4))
    return last4[0] != null && last4[0] == last4[1]
}

/** The account's record with NeoTransfer's: every row with its app's dot. Without NeoTransfer, [own] as it is. */
fun withPeer(own: List<OperationUi>, peer: List<OperationUi>?): List<OperationUi> {
    peer ?: return own
    return (own.map { it.copy(origin = OperationOrigin.Enzona) } + peer)
        .sortedWith(compareByDescending<OperationUi, Instant?>(nullsFirst()) { it.at })
}

/** A card's statement, each line NeoTransfer told replaced by the merged row. */
fun List<OperationUi>.withPeerLines(peer: List<OperationUi>?): List<OperationUi> {
    val merged = peer?.filter { it.id.startsWith(StatementPrefix) }?.associateBy { it.id } ?: return this
    return map { merged[it.id] ?: it }
}

private const val StatementPrefix = "m:"
const val PeerPrefix = "p:"

private fun SyncOperation.toUi(cards: List<CardUi>): OperationUi {
    val kind = if (direction == SyncDirection.IN) OperationKind.Incoming else when (kind) {
        SyncKind.CARD_TRANSFER -> OperationKind.CardTransfer
        SyncKind.ACCOUNT_TRANSFER -> OperationKind.AccountTransfer
        SyncKind.QR_PAYMENT -> OperationKind.QrPayment
        SyncKind.ELECTRICITY -> OperationKind.Electricity
        SyncKind.LANDLINE -> OperationKind.Landline
        SyncKind.WATER -> OperationKind.Water
        SyncKind.NAUTA -> OperationKind.Nauta
        SyncKind.NAUTA_HOME -> OperationKind.NautaHome
        SyncKind.MOBILE_TOPUP -> OperationKind.MobileTopUp
        SyncKind.CUBACEL_TRANSFER -> OperationKind.CubacelTransfer
        SyncKind.SERVICE -> OperationKind.Service
        SyncKind.INCOMING, SyncKind.OTHER -> OperationKind.Other
    }
    val card = ownCard(card, cards)
    return OperationUi(
        id = PeerPrefix + id,
        kind = kind,
        title = title.ifEmpty { kind.label },
        status = when (status) {
            SyncStatus.CONFIRMED -> OperationStatus.Confirmed
            SyncStatus.PENDING -> OperationStatus.Pending
            SyncStatus.UNCONFIRMED -> OperationStatus.Unconfirmed
            SyncStatus.REJECTED -> OperationStatus.Rejected
            SyncStatus.NOT_DONE -> OperationStatus.NotDone
            SyncStatus.NOT_SENT -> OperationStatus.NotSent
        },
        at = Instant.ofEpochMilli(at),
        amount = amount?.let { Money(it, currency.ifEmpty { card?.currency ?: "CUP" }) },
        card = card,
        counterpart = counterpartOf(kind, counterpart),
        contactId = contactId.ifEmpty { null },
        origin = OperationOrigin.NeoTransfer,
        readOnly = true,
    )
}

/** One of the account's cards when [number] is unambiguously it; otherwise the card as far as its digits tell. */
private fun ownCard(number: String, cards: List<CardUi>): CardUi? {
    if (number.isEmpty()) return null
    val full = number.length == 16
    cards.filter { card -> if (full && card.number != null) card.number == number else (card.last4 ?: card.number?.takeLast(4)) == number.takeLast(4) }
        .singleOrNull()?.let { return it }
    val issuer = if (full) CardNumbers.issuer(number) else null
    val bank = issuer?.bank?.let(Banks::shortName)
    return CardUi("peer:$number", bank ?: "Tarjeta", CardArt.of(bank), number.takeIf { full }, number.takeLast(4), issuer?.currency ?: "CUP")
}

private fun counterpartOf(kind: OperationKind, value: String): CounterpartUi? = when {
    value.isEmpty() -> null
    kind == OperationKind.AccountTransfer -> CounterpartUi.Account(value)
    (kind == OperationKind.CardTransfer || kind == OperationKind.Incoming) && value.length == 16 && value.all(Char::isDigit) ->
        CounterpartUi.Card(value, Banks.issuerOf(value))
    (kind == OperationKind.CardTransfer || kind == OperationKind.Incoming) && value.length == 4 && value.all(Char::isDigit) ->
        CounterpartUi.CardEnding(value)
    else -> CounterpartUi.Reference(value)
}
