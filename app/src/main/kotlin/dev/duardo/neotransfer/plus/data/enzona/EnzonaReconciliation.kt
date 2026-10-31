package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.core.read.RemoteDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What ENZONA's history says of one unconfirmed attempt. The store applies it; nothing here sends or retries. */
internal sealed interface EnzonaReconciliationChange {
    val id: String

    /** A history row settles it. */
    data class Settle(override val id: String, val state: EnzonaIntentState, val remoteId: String, val matchedBy: EnzonaHistoryMatch) :
        EnzonaReconciliationChange

    /** A row for it is there but settles nothing (pending, or one of several): it is not missing, and a person's mark is undone. */
    data class Seen(override val id: String) : EnzonaReconciliationChange

    /** A read that covered its time, read at [at], had no row for it. */
    data class NotFound(override val id: String, val at: Long) : EnzonaReconciliationChange
}

/**
 * Settles UNKNOWN attempts against ENZONA's history, by `transaction_status_code` as ENZONA's own history reads it
 * (1111 Aceptada, 1121 Pendiente, 1112 Fallida):
 * - with the `transaction_uuid` a reply gave, its row decides: 1111 CONFIRMED, 1112 REJECTED, anything else waits;
 * - without it, one outgoing row from the same card to the same destination, with the exact amount and currency and
 *   created from [BEFORE] before to [AFTER] after the attempt, CONFIRMED only when 1111 ("por importe y hora"). Several
 *   candidates, or a row another attempt already owns, settle nothing. QR payments have no destination in the history.
 * An attempt is "not found" only when no row from that card with that amount, currency and time is there at all,
 * whatever its destination, and the own card's last four digits are known. Never REJECTED by inference.
 */
internal object EnzonaReconciliation {
    const val ACCEPTED = 1111
    const val FAILED = 1112
    const val BEFORE = 2 * 60_000L
    const val AFTER = 24 * 60 * 60_000L
    /** A history read this long after an unconfirmed attempt, without its row, settles it as not done. */
    const val NOT_DONE_AFTER = 15 * 60_000L

    /**
     * [ownLast4]: last four digits of each own card by its uuid. [read]: the dates the last history read asked for,
     * [observedAt] when it arrived; only a read that covered an attempt's whole time can say it is not there.
     */
    fun plan(
        journal: List<EnzonaJournalEntry>, history: List<EnzonaTransaction>, ownLast4: Map<String, String>,
        zone: ZoneId, read: ClosedRange<LocalDate>, observedAt: Long,
    ): List<EnzonaReconciliationChange> {
        val byUuid = history.associateBy { it.transactionUuid }
        val claimed = journal.mapNotNullTo(hashSetOf()) { it.remoteId }
        val open = journal.filter { it.state == EnzonaIntentState.UNKNOWN && (it.remoteId != null || it.intent.cardUuid in ownLast4) }
        // Rows that could be each attempt; those also to its destination can confirm it.
        val alike = open.filter { it.remoteId == null }.associate { entry ->
            entry.intent.id to history.filter { it.transactionUuid !in claimed && alike(entry.intent, it, ownLast4, zone) }
        }
        val candidates = open.filter { it.remoteId == null }.associate { entry ->
            entry.intent.id to alike.getValue(entry.intent.id).filter { sameDestination(entry.intent, it) }
        }
        // A row two attempts could both be is neither's.
        val takers = candidates.values.flatten().groupingBy { it.transactionUuid }.eachCount()
        return open.mapNotNull { entry ->
            val id = entry.intent.id
            val remote = entry.remoteId
            val seen = if (remote != null) listOfNotNull(byUuid[remote]) else alike.getValue(id)
            val rows = if (remote != null) seen else candidates.getValue(id)
            val single = rows.singleOrNull()
            when {
                seen.isEmpty() -> EnzonaReconciliationChange.NotFound(id, observedAt)
                    .takeIf { covers(entry.intent, zone, read, observedAt) && observedAt > (entry.notFoundAt ?: Long.MIN_VALUE) }
                remote != null && single?.transactionStatusCode == ACCEPTED ->
                    EnzonaReconciliationChange.Settle(id, EnzonaIntentState.CONFIRMED, remote, EnzonaHistoryMatch.UUID)
                remote != null && single?.transactionStatusCode == FAILED ->
                    EnzonaReconciliationChange.Settle(id, EnzonaIntentState.REJECTED, remote, EnzonaHistoryMatch.UUID)
                remote == null && single != null && single.transactionStatusCode == ACCEPTED && takers[single.transactionUuid] == 1 ->
                    EnzonaReconciliationChange.Settle(id, EnzonaIntentState.CONFIRMED, single.transactionUuid, EnzonaHistoryMatch.AMOUNT_AND_TIME)
                else -> EnzonaReconciliationChange.Seen(id).takeIf { entry.notFoundAt != null || entry.markedNotDoneAt != null }
            }
        }
    }

    /** From the own card, with the exact amount and currency, at the attempt's time: it could be the attempt. */
    private fun alike(intent: EnzonaIntent, row: EnzonaTransaction, ownLast4: Map<String, String>, zone: ZoneId): Boolean {
        val own = ownLast4[intent.cardUuid] ?: return false
        val amount = row.amount?.abs() ?: return false
        val reviewed = intent.amount.toBigDecimalOrNull() ?: return false
        val at = RemoteDates.parse(row.createdAt?.value, zone)?.toEpochMilli() ?: return false
        return row.source?.last4 == own && amount.compareTo(reviewed) == 0 && row.currency.equals(intent.currency, ignoreCase = true) &&
            at in intent.preparedAt - BEFORE..intent.preparedAt + AFTER
    }

    private fun sameDestination(intent: EnzonaIntent, row: EnzonaTransaction): Boolean = when (intent.type) {
        EnzonaIntentType.CARD_TRANSFER -> row.recipient?.last4 == intent.destination.value.takeLast(4)
        EnzonaIntentType.ACCOUNT_TRANSFER -> row.recipient?.username.let { it != null && it == intent.destination.username }
        EnzonaIntentType.INVOICE_PAYMENT -> row.clientId == intent.destination.value
        EnzonaIntentType.QR_MERCHANT_PAYMENT -> false
    }

    private fun covers(intent: EnzonaIntent, zone: ZoneId, read: ClosedRange<LocalDate>, observedAt: Long): Boolean {
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return read.start <= day(intent.preparedAt - BEFORE) && read.endInclusive >= day(minOf(intent.preparedAt + AFTER, observedAt))
    }
}
