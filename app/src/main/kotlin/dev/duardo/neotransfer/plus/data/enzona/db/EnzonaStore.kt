package dev.duardo.neotransfer.plus.data.enzona.db

import androidx.room.withTransaction
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentJournal
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliationChange
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Fixed resource IDs of the single-row cache kinds. */
internal const val ACCOUNT_RESOURCE = "account"
internal const val CARDS_RESOURCE = "cards"

/** Per-owner local data. Every read and write names the exact owner; nothing crosses accounts. */
internal interface EnzonaLocalStore : EnzonaPaymentJournal {
    suspend fun cached(owner: String, kind: EnzonaReadKind, resourceId: String): EnzonaCacheRow?
    /** Sanitizes and validates [json]; an older observation never replaces a newer one. */
    suspend fun cache(owner: String, kind: EnzonaReadKind, resourceId: String, json: String, observedAt: Long)
    fun observeCache(owner: String, kind: EnzonaReadKind): Flow<List<EnzonaCacheRow>>
    suspend fun saveHistory(owner: String, transactions: List<EnzonaTransaction>, observedAt: Long,
        notifyCredits: Boolean, accountUsername: String?)
    fun observeTransactions(owner: String): Flow<List<EnzonaTransactionRow>>
    fun observePendingNotices(owner: String): Flow<List<EnzonaTransactionRow>>
    suspend fun markNoticesDelivered(owner: String, ids: List<String>)
    fun observeCardLinks(owner: String): Flow<List<EnzonaCardLink>>
    suspend fun putCardLink(link: EnzonaCardLink)
    /** Erases what is kept of one card the account no longer has: its full number, alias and balance. */
    suspend fun forgetCard(owner: String, cardUuid: String)
    fun observeJournal(owner: String): Flow<List<EnzonaJournalEntry>>
    /** Applies what the history says of UNKNOWN attempts ([EnzonaReconciliation]); anything else is left as it is. */
    suspend fun reconcile(owner: String, changes: List<EnzonaReconciliationChange>)
    /**
     * The person's own resolution of an UNKNOWN attempt that a history read at least [EnzonaReconciliation.AFTER] after
     * it did not find. The state stays UNKNOWN; false when it does not qualify.
     */
    suspend fun markNotDone(owner: String, id: String, at: Long): Boolean
    /** Erases the owner's cached reads, history and card links. Never touches the payment journal. */
    suspend fun clearOwner(owner: String)
}

internal class RoomEnzonaStore(private val database: EnzonaDatabase) : EnzonaLocalStore {
    private val dao = database.enzona()

    override suspend fun cached(owner: String, kind: EnzonaReadKind, resourceId: String) =
        database.withTransaction { dao.cache(checkOwner(owner), kind, resourceId) }

    override suspend fun cache(owner: String, kind: EnzonaReadKind, resourceId: String, json: String, observedAt: Long) {
        require(resourceId.isNotBlank() && observedAt >= 0)
        val row = EnzonaCacheRow(checkOwner(owner), kind, resourceId, observedAt, EnzonaCacheSanitizer.sanitize(kind, json))
        database.withTransaction {
            val previous = dao.cache(owner, kind, resourceId)
            if (previous == null || observedAt >= previous.observedAt) dao.putCache(row)
        }
    }

    override fun observeCache(owner: String, kind: EnzonaReadKind) = dao.observeCache(checkOwner(owner), kind)

    override suspend fun saveHistory(owner: String, transactions: List<EnzonaTransaction>, observedAt: Long,
        notifyCredits: Boolean, accountUsername: String?) {
        checkOwner(owner)
        database.withTransaction {
            val ids = transactions.map { it.transactionUuid }.distinct()
            val existing = ids.chunked(500).flatMap { dao.transactions(owner, it) }.associateBy { it.transactionUuid }
            val baseline = dao.historySync(owner) != null
            dao.putTransactions(EnzonaHistoryMerge.plan(owner, transactions, existing, observedAt, baseline,
                notifyCredits, accountUsername))
            if (notifyCredits) dao.putHistorySync(EnzonaHistorySync(owner, observedAt))
        }
    }

    override fun observeTransactions(owner: String) = dao.observeTransactions(checkOwner(owner))
    override fun observePendingNotices(owner: String) = dao.observePendingNotices(checkOwner(owner))

    override suspend fun markNoticesDelivered(owner: String, ids: List<String>) {
        checkOwner(owner)
        database.withTransaction { ids.chunked(500).forEach { dao.markDelivered(owner, it) } }
    }

    override fun observeCardLinks(owner: String) = dao.observeCardLinks(checkOwner(owner))

    override suspend fun putCardLink(link: EnzonaCardLink) {
        validateCardLink(link)
        database.withTransaction { dao.putCardLink(link) }
    }

    override suspend fun forgetCard(owner: String, cardUuid: String) {
        checkOwner(owner)
        require(cardUuid.isNotBlank())
        database.withTransaction {
            dao.deleteCardLink(owner, cardUuid)
            dao.deleteCacheRow(owner, EnzonaReadKind.BALANCE, cardUuid)
        }
    }

    override fun observeJournal(owner: String) = dao.observeJournal(checkOwner(owner))

    override suspend fun reconcile(owner: String, changes: List<EnzonaReconciliationChange>) {
        checkOwner(owner)
        database.withTransaction {
            for (change in changes) when (change) {
                is EnzonaReconciliationChange.Settle -> dao.settle(owner, change.id, change.state, change.remoteId, change.matchedBy)
                is EnzonaReconciliationChange.Seen -> dao.seenInHistory(owner, change.id)
                is EnzonaReconciliationChange.NotFound -> dao.notFoundInHistory(owner, change.id, change.at)
            }
        }
    }

    override suspend fun markNotDone(owner: String, id: String, at: Long) =
        database.withTransaction { dao.markNotDone(checkOwner(owner), id, at, EnzonaReconciliation.AFTER) == 1 }

    override suspend fun clearOwner(owner: String) {
        checkOwner(owner)
        database.withTransaction {
            dao.deleteCache(owner)
            dao.deleteHistory(owner)
            dao.deleteHistorySync(owner)
            dao.deleteCardLinks(owner)
        }
    }

    /** Duplicate UUID with the exact same intent is idempotent, including after submission. */
    override suspend fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry {
        validateIntent(intent)
        return database.withTransaction {
            dao.insertIntent(EnzonaJournalEntry(intent))
            val stored = dao.intent(intent.ownerSubject, intent.id)
            require(stored?.intent == intent) { "Intent UUID conflict" }
            checkNotNull(stored)
        }
    }

    override suspend fun intent(owner: String, id: String) = database.withTransaction { dao.intent(checkOwner(owner), id) }

    override suspend fun tryBeginSubmission(owner: String, id: String) =
        database.withTransaction { dao.tryBeginSubmission(checkOwner(owner), id) == 1 }

    override suspend fun markUnknown(owner: String, id: String, evidence: EnzonaPaymentEvidence?) = database.withTransaction {
        val current = dao.intent(checkOwner(owner), id) ?: return@withTransaction false
        if (current.state != EnzonaIntentState.SUBMITTING && current.state != EnzonaIntentState.UNKNOWN) return@withTransaction false
        dao.recordReply(owner, id, EnzonaIntentState.UNKNOWN, current.remoteId ?: evidence?.transactionUuid,
            evidence?.httpStatus ?: current.httpStatus ?: 0, evidence?.fault?.code ?: current.faultCode,
            evidence?.fault?.text ?: current.faultText)
        current.state == EnzonaIntentState.SUBMITTING
    }

    override suspend fun markNotDelivered(owner: String, id: String) = database.withTransaction {
        val current = dao.intent(checkOwner(owner), id) ?: return@withTransaction false
        if (!current.couldBeUndelivered()) return@withTransaction false
        dao.recordReply(owner, id, EnzonaIntentState.NOT_DELIVERED, null, current.httpStatus, null, null)
        true
    }

    override suspend fun abandonFlights() = database.withTransaction { dao.abandonFlights() }

    /** Accepts only a decoded terminal result and an actually observed remote ID; never undoes one. */
    override suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?): Boolean {
        checkOwner(owner)
        require(state.isTerminal && (remoteId == null || remoteId.isNotBlank()))
        return database.withTransaction {
            val current = dao.intent(owner, id) ?: return@withTransaction false
            if (current.state == EnzonaIntentState.PREPARED) return@withTransaction false
            if (current.state.isTerminal && current.state != state) return@withTransaction false
            if (current.remoteId != null && remoteId != null && current.remoteId != remoteId) return@withTransaction false
            dao.recordResult(owner, id, state, current.remoteId ?: remoteId)
            true
        }
    }
}

private fun checkOwner(owner: String): String = owner.also { require(it.isNotBlank()) { "ENZONA ownerSubject is required" } }

/** In flight, or unconfirmed with no trace of a reply: only then can "never left the phone" be true. */
internal fun EnzonaJournalEntry.couldBeUndelivered(): Boolean =
    (state == EnzonaIntentState.SUBMITTING || state == EnzonaIntentState.UNKNOWN) && remoteId == null && (httpStatus ?: 0) == 0

internal fun validateCardLink(link: EnzonaCardLink) {
    require(link.ownerSubject.isNotBlank() && link.cardUuid.isNotBlank())
    require(link.pan == null || link.pan.length == 16 && link.pan.all { it in '0'..'9' }) { "A known full PAN is required" }
    require(link.alias == null || link.alias.isNotBlank())
}

internal fun validateIntent(intent: EnzonaIntent) {
    require(intent.ownerSubject.isNotBlank())
    require(runCatching { UUID.fromString(intent.id).toString() == intent.id }.getOrDefault(false)) { "Local UUID is required" }
    require(intent.cardUuid.isNotBlank() && intent.destination.value.isNotBlank())
    if (intent.type == EnzonaIntentType.ACCOUNT_TRANSFER) require(!intent.destination.username.isNullOrBlank())
    else require(intent.destination.username == null)
    if (intent.type == EnzonaIntentType.INVOICE_PAYMENT) {
        val service = EnzonaInvoiceService.entries.firstOrNull { it.name == intent.invoiceService }
        require(service != null && !intent.invoiceOwner.isNullOrBlank()) { "Invoice service and owner are required" }
        if (service == EnzonaInvoiceService.ELECTRICITY) {
            require(!intent.invoiceFolio.isNullOrBlank() && !intent.invoiceRoute.isNullOrBlank() && !intent.invoicePeriod.isNullOrBlank()) {
                "Electricity invoice folio, route and period are required"
            }
        } else require(intent.invoiceFolio == null && intent.invoiceRoute == null && intent.invoicePeriod == null) {
            "Unexpected electricity invoice fields"
        }
    } else require(intent.invoiceService == null && intent.invoiceOwner == null && intent.invoiceFolio == null &&
        intent.invoiceRoute == null && intent.invoicePeriod == null) { "Invoice fields require INVOICE_PAYMENT" }
    require(intent.amount.toBigDecimalOrNull()?.signum() == 1) { "Positive exact amount is required" }
    require(intent.currency.isNotBlank() && intent.preparedAt >= 0)
}
