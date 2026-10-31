package dev.duardo.neotransfer.plus.data

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.card.CardBank
import dev.duardo.neotransfer.plus.core.card.EnzonaCardRequests
import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.payment.EnzonaCardTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.data.enzona.EnzonaCardsApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaFetched
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHistoryMatch
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHttpReply
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentCoordinator
import dev.duardo.neotransfer.plus.data.enzona.EnzonaQrApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReadApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliationChange
import dev.duardo.neotransfer.plus.data.enzona.EnzonaServiceApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSession
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionNetwork
import dev.duardo.neotransfer.plus.data.enzona.TokenPersistence
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaCacheRow
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaCacheSanitizer
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaCardLink
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaCreditNotice
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaHistoryMerge
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaLocalStore
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaReadKind
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaTransactionRow
import dev.duardo.neotransfer.plus.data.enzona.db.couldBeUndelivered
import dev.duardo.neotransfer.plus.data.enzona.bodyText
import dev.duardo.neotransfer.plus.data.enzona.collectingLog
import dev.duardo.neotransfer.plus.data.enzona.syntheticTokens
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenario tests over the facade with synthetic HTTP replies and an in-memory store. No real network. */
class EnzonaRepositoryTest {
    private val owner = "owner@carbon.super"
    private val card = "00000000-0000-4000-8000-0000000000c1"

    private class MemoryTokens(var value: EnzonaTokens?) : TokenPersistence {
        override fun load() = value
        override fun save(tokens: EnzonaTokens) { value = tokens }
        override fun clear() { value = null }
    }

    private class Scheduler : EnzonaCreditScheduler {
        val events = mutableListOf<String>()
        override fun schedule() { events += "schedule" }
        override fun cancel() { events += "cancel" }
    }

    /** Same semantics as RoomEnzonaStore (the SQL itself is covered by the instrumented checks). */
    private class MemoryStore : EnzonaLocalStore {
        val cache = MutableStateFlow(mapOf<Triple<String, EnzonaReadKind, String>, EnzonaCacheRow>())
        val history = MutableStateFlow(mapOf<Pair<String, String>, EnzonaTransactionRow>())
        val baselines = mutableSetOf<String>()
        val links = MutableStateFlow(mapOf<Pair<String, String>, EnzonaCardLink>())
        val journal = MutableStateFlow(mapOf<String, EnzonaJournalEntry>())
        var failHistory = false

        override suspend fun cached(owner: String, kind: EnzonaReadKind, resourceId: String) = cache.value[Triple(owner, kind, resourceId)]
        override suspend fun cache(owner: String, kind: EnzonaReadKind, resourceId: String, json: String, observedAt: Long) {
            val row = EnzonaCacheRow(owner, kind, resourceId, observedAt, EnzonaCacheSanitizer.sanitize(kind, json))
            val previous = cache.value[Triple(owner, kind, resourceId)]
            if (previous == null || observedAt >= previous.observedAt) cache.update { it + (Triple(owner, kind, resourceId) to row) }
        }
        override fun observeCache(owner: String, kind: EnzonaReadKind) =
            cache.map { rows -> rows.values.filter { it.ownerSubject == owner && it.kind == kind }.sortedBy { it.resourceId } }
        override suspend fun saveHistory(owner: String, transactions: List<EnzonaTransaction>, observedAt: Long,
            notifyCredits: Boolean, accountUsername: String?) {
            check(!failHistory) { "PRIVATE_STORE" }
            val existing = history.value.values.filter { it.ownerSubject == owner }.associateBy { it.transactionUuid }
            val planned = EnzonaHistoryMerge.plan(owner, transactions, existing, observedAt, owner in baselines, notifyCredits, accountUsername)
            history.update { it + planned.associateBy { row -> owner to row.transactionUuid } }
            if (notifyCredits) baselines += owner
        }
        override fun observeTransactions(owner: String) =
            history.map { rows -> rows.values.filter { it.ownerSubject == owner }.sortedByDescending { it.firstSeenAt } }
        override fun observePendingNotices(owner: String) =
            history.map { rows -> rows.values.filter { it.ownerSubject == owner && it.notice == EnzonaCreditNotice.PENDING } }
        override suspend fun markNoticesDelivered(owner: String, ids: List<String>) = history.update { rows ->
            rows.mapValues { (_, row) ->
                if (row.ownerSubject == owner && row.transactionUuid in ids && row.notice == EnzonaCreditNotice.PENDING)
                    row.copy(notice = EnzonaCreditNotice.DELIVERED) else row
            }
        }
        override fun observeCardLinks(owner: String) = links.map { rows -> rows.values.filter { it.ownerSubject == owner } }
        override suspend fun putCardLink(link: EnzonaCardLink) = links.update { it + ((link.ownerSubject to link.cardUuid) to link) }
        override suspend fun forgetCard(owner: String, cardUuid: String) {
            links.update { it - (owner to cardUuid) }
            cache.update { it - Triple(owner, EnzonaReadKind.BALANCE, cardUuid) }
        }
        override fun observeJournal(owner: String) = journal.map { rows -> rows.values.filter { it.intent.ownerSubject == owner } }
        override suspend fun clearOwner(owner: String) {
            cache.update { rows -> rows.filterKeys { it.first != owner } }
            history.update { rows -> rows.filterKeys { it.first != owner } }
            links.update { rows -> rows.filterKeys { it.first != owner } }
            baselines -= owner
        }
        override suspend fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry {
            journal.update { if (intent.id in it) it else it + (intent.id to EnzonaJournalEntry(intent)) }
            return journal.value.getValue(intent.id).also { require(it.intent == intent) }
        }
        override suspend fun intent(owner: String, id: String) = journal.value[id]?.takeIf { it.intent.ownerSubject == owner }
        private fun transition(owner: String, id: String, from: EnzonaIntentState, to: EnzonaIntentState): Boolean {
            val current = journal.value[id]?.takeIf { it.intent.ownerSubject == owner && it.state == from } ?: return false
            journal.update { it + (id to current.copy(state = to)) }
            return true
        }
        override suspend fun tryBeginSubmission(owner: String, id: String) = transition(owner, id, EnzonaIntentState.PREPARED, EnzonaIntentState.SUBMITTING)
        override suspend fun markUnknown(owner: String, id: String, evidence: EnzonaPaymentEvidence?): Boolean {
            val current = journal.value[id]?.takeIf { it.intent.ownerSubject == owner } ?: return false
            if (current.state != EnzonaIntentState.SUBMITTING && current.state != EnzonaIntentState.UNKNOWN) return false
            journal.update { it + (id to current.copy(state = EnzonaIntentState.UNKNOWN, remoteId = current.remoteId ?: evidence?.transactionUuid,
                httpStatus = evidence?.httpStatus ?: current.httpStatus ?: 0, faultCode = evidence?.fault?.code ?: current.faultCode,
                faultText = evidence?.fault?.text ?: current.faultText)) }
            return current.state == EnzonaIntentState.SUBMITTING
        }
        override suspend fun markNotDelivered(owner: String, id: String): Boolean {
            val current = journal.value[id]?.takeIf { it.intent.ownerSubject == owner && it.couldBeUndelivered() } ?: return false
            journal.update { it + (id to current.copy(state = EnzonaIntentState.NOT_DELIVERED)) }
            return true
        }
        override suspend fun abandonFlights(): Int {
            val flying = journal.value.values.filter { it.state == EnzonaIntentState.SUBMITTING }
            journal.update { rows -> rows + flying.associate { it.intent.id to it.copy(state = EnzonaIntentState.UNKNOWN) } }
            return flying.size
        }
        override suspend fun reconcile(owner: String, changes: List<EnzonaReconciliationChange>) = changes.forEach { change ->
            val current = journal.value[change.id]?.takeIf { it.intent.ownerSubject == owner && it.state == EnzonaIntentState.UNKNOWN } ?: return@forEach
            val next = when (change) {
                is EnzonaReconciliationChange.Settle -> current.takeIf { it.remoteId == null || it.remoteId == change.remoteId }?.copy(
                    state = change.state, remoteId = change.remoteId, matchedBy = change.matchedBy, notFoundAt = null, markedNotDoneAt = null)
                is EnzonaReconciliationChange.Seen -> current.copy(notFoundAt = null, markedNotDoneAt = null)
                is EnzonaReconciliationChange.NotFound -> current.copy(notFoundAt = change.at)
            } ?: return@forEach
            journal.update { it + (change.id to next) }
        }
        override suspend fun markNotDone(owner: String, id: String, at: Long): Boolean {
            val current = journal.value[id]?.takeIf {
                it.intent.ownerSubject == owner && it.state == EnzonaIntentState.UNKNOWN && it.markedNotDoneAt == null &&
                    (it.notFoundAt ?: return false) >= it.intent.preparedAt + EnzonaReconciliation.AFTER
            } ?: return false
            journal.update { it + (id to current.copy(markedNotDoneAt = at)) }
            return true
        }
        override suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?): Boolean {
            val current = journal.value[id]?.takeIf { it.intent.ownerSubject == owner } ?: return false
            if (current.state == EnzonaIntentState.PREPARED || (current.state.isTerminal && current.state != state)) return false
            journal.update { it + (id to current.copy(state = state, remoteId = current.remoteId ?: remoteId)) }
            return true
        }
    }

    /** Synthetic ENZONA: routes by path, records every request. */
    private inner class FakeEnzona {
        val requests = mutableListOf<Request>()
        var history = """[]"""
        var rejectNextCardsRead = false
        var rejectOwnQr = false
        var historyFailure: Exception? = null
        var banks = """{"banks":[{"name":"Banco Popular de Ahorro","code":"12","certificate":"${bpaBank.der()}"},
            {"name":"Banco de Crédito y Comercio","code":"06","certificate":"${bandecBank.der()}"}]}"""
        var cardReply = EnzonaHttpReply(200, """{"funding_source_uuid":"new-card","last4":"6781"}""")
        var transferReply = EnzonaHttpReply(200, """{"statusCode":1111,"transaction_uuid":"remote-1"}""")
        val historyReads = AtomicInteger()
        var cardFailure: Exception? = null
        val execute: suspend (Request) -> EnzonaHttpReply = { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/account/v1.0.0/account" -> EnzonaHttpReply(200, """{"account":{"username":"me","name":"Ana","lastname":"Pérez"}}""")
                path == "/nomenclator/v1.0.0/nomenclator/banks" -> EnzonaHttpReply(200, banks)
                path == "/nomenclator/v1.0.0/nomenclator/coordinates" ->
                    EnzonaHttpReply(200, """{"coordinates":{"C1":"A02","C2":"D05","P1":"2","P2":"4"}}""")
                path == "/coremz/1.0.2/card" -> cardFailure?.let { throw it } ?: cardReply
                path == "/cards/v1.0.0/cards/default" || path == "/cards/v1.0.0/cards/$card/delete" -> cardReply
                path.startsWith("/coremz/1.0.2/card/username/") ->
                    if (rejectNextCardsRead) { rejectNextCardsRead = false; EnzonaHttpReply(401, INVALID_CREDENTIALS) }
                    else EnzonaHttpReply(200, """{"card":[{"funding_source_uuid":"$card","last4":"0007","currency":"CUP"}]}""")
                path.startsWith("/coremz/1.0.2/api/balance/lastOperations/") ->
                    EnzonaHttpReply(200, """{"balance":"1500.25","balance_available":"1400.00","currency":"CUP","operation":[]}""")
                path == "/operations/v1.0.0/operations/account" -> historyFailure?.let { throw it }
                    ?: EnzonaHttpReply(200, """{"transaction":$history,"pagination":{"total":0}}""").also { historyReads.incrementAndGet() }
                path == "/qr/v1.0.0/qr/account" ->
                    if (rejectOwnQr) EnzonaHttpReply(401, INVALID_CREDENTIALS) else EnzonaHttpReply(200, """{"vendor_identity_code":"05-own"}""")
                path == "/transfer/v1.0.0/transfer/card" -> transferReply
                else -> error("Unexpected synthetic request $path")
            }
        }
    }

    private inner class Harness(signedIn: Boolean = true, accessExpiry: Long = 5000, recheckMillis: Long = 60_000) : AutoCloseable {
        val enzona = FakeEnzona()
        /** When payments are prepared and marked; [readAt]: when ENZONA's replies arrive. */
        var clock = PAID_AT
        var readAt = 2000L
        val store = MemoryStore()
        val scheduler = Scheduler()
        val tokens = MemoryTokens(if (signedIn) syntheticTokens(owner, "ACCESS", "REFRESH", accessExpiry) else null)
        var refreshes = 0
        var refreshFailure: Exception? = null
        var webCleared = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = EnzonaSession(tokens, object : EnzonaSessionNetwork {
            override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange) = error("not used")
            override suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens {
                refreshes++
                refreshFailure?.let { throw it }
                return syntheticTokens(owner, "RENEWED", "REFRESH", 9000)
            }
            override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) = Unit
            override suspend fun account(tokens: EnzonaTokens): EnzonaFetched<EnzonaAccount> = error("not used")
        }, { emptyMap() }, Dispatchers.Unconfined, { 1000 }, ioDispatcher = Dispatchers.Unconfined)
        val payments = EnzonaPaymentCoordinator(session::usableTokens, store, EnzonaPaymentApi(enzona.execute) { readAt },
            { EnzonaPaymentRequests(EnzonaCipher(key)) }, Dispatchers.Unconfined, 1_000) { clock }
        val repository = EnzonaRepository(session, EnzonaSessionApi(enzona.execute) { 1000 }, EnzonaReadApi(enzona.execute) { readAt },
            EnzonaQrApi(enzona.execute), EnzonaServiceApi(enzona.execute), EnzonaCardsApi(enzona.execute),
            { EnzonaCardRequests(EnzonaCipher(key)) }, payments, store, scheduler, scope,
            { webCleared++ }, { LocalDate.of(2026, 10, 15) }, havana, { clock }, recheckMillis)

        fun paths() = enzona.requests.map { it.url.encodedPath }
        override fun close() { scope.cancel(); payments.close(); session.close() }
    }

    private fun credit(uuid: String, sender: String = "luis") = """{"transaction_uuid":"$uuid","amount":"250.00","currency":"CUP",
        "source":{"funding_source_username":"$sender","funding_source_name":"Luis"},"recipient":{"funding_recipient_username":"me"}}"""

    @Test fun signedInOwnerSchedulesTheCreditCheckAndSignOutCancelsItWithTheWebSession() = runBlocking {
        Harness().use { h ->
            assertTrue(h.repository.sessionState.value.signedIn)
            assertEquals(listOf("schedule"), h.scheduler.events)
            assertTrue(h.repository.signOut())
            assertEquals(listOf("schedule", "cancel", "cancel"), h.scheduler.events)
            assertEquals(1, h.webCleared)
            assertNull(h.tokens.value)
        }
        Harness(signedIn = false).use { h -> assertEquals(listOf("cancel"), h.scheduler.events) }
    }

    @Test fun signOutErasesTheOwnersCacheButKeepsThePaymentJournal() = runBlocking {
        Harness().use { h ->
            h.enzona.history = "[${credit("t1")}]"
            assertNull(h.repository.refresh())
            assertNull(h.repository.saveCardLink(card, "9205000000000007", null))
            h.repository.preparePayment(EnzonaCardTransferDraft(card, "9205111122223333", BigDecimal("10"), "CUP"))
            assertTrue(h.repository.signOut())
            assertTrue(h.store.cache.value.keys.none { it.first == owner })
            assertTrue(h.store.history.value.keys.none { it.first == owner })
            assertTrue(h.store.links.value.keys.none { it.first == owner })
            assertEquals(1, h.store.journal.value.size)
        }
    }

    @Test fun refreshFillsAccountCardsBalancesAndTheSevenDayHistoryForTheOwner() = runBlocking {
        Harness().use { h ->
            h.enzona.history = "[${credit("t1")}]"
            assertNull(h.repository.refresh())
            assertEquals(listOf("/account/v1.0.0/account", "/coremz/1.0.2/card/username/$owner",
                "/coremz/1.0.2/api/balance/lastOperations/$card", "/operations/v1.0.0/operations/account"), h.paths())
            val history = h.enzona.requests.last().url
            assertEquals("2026-10-08", history.queryParameter("start_date_filter"))
            assertEquals("2026-10-15", history.queryParameter("end_date_filter"))
            val wallet = h.repository.cards.first().single()
            assertEquals("0007", wallet.card.last4)
            assertEquals(BigDecimal("1500.25"), wallet.balance?.bookBalance)
            assertEquals(BigDecimal("1400.00"), wallet.balance?.availableBalance)
            assertEquals(2000L, wallet.balanceObservedAt)
            assertEquals("Ana", h.repository.account.first()?.name)
            assertEquals(listOf("t1"), h.repository.transactions.first().map { it.transactionUuid })
            // The first sync after sign-in is the baseline: money already there is not "new".
            assertTrue(h.repository.creditNotices.first().isEmpty())
            h.enzona.requests.clear()
            assertNull(h.repository.refresh())
            assertTrue("/account/v1.0.0/account" !in h.paths()) // cached account is reused
        }
    }

    @Test fun backgroundSyncQueuesOnlyNewCreditsAndDeliveredNoticesNeverReturn() = runBlocking {
        Harness().use { h ->
            h.enzona.history = "[${credit("old")}]"
            assertNull(h.repository.syncCredits())
            assertTrue(h.repository.creditNotices.first().isEmpty())
            h.enzona.history = "[${credit("old")},${credit("new")},${credit("mine", sender = "me")}]"
            assertNull(h.repository.syncCredits())
            val notices = h.repository.creditNotices.first()
            assertEquals(listOf("new"), notices.map { it.transactionUuid })
            assertEquals(BigDecimal("250.00"), notices.single().amount)
            assertEquals("Luis", notices.single().senderName)
            assertTrue(h.paths().none { it.startsWith("/coremz") || it.startsWith("/transfer") }) // history only
            h.repository.markCreditNoticesDelivered(listOf("new"))
            assertTrue(h.repository.creditNotices.first().isEmpty())
            assertNull(h.repository.syncCredits())
            assertTrue(h.repository.creditNotices.first().isEmpty())
        }
        Harness(signedIn = false).use { h ->
            assertNull(h.repository.syncCredits())
            assertTrue(h.enzona.requests.isEmpty())
        }
    }

    @Test fun olderHistoryNeverNotifies() = runBlocking {
        Harness().use { h ->
            assertNull(h.repository.syncCredits())
            h.enzona.history = "[${credit("september")}]"
            assertNull(h.repository.loadHistory(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
            assertEquals(listOf("september"), h.repository.transactions.first().map { it.transactionUuid })
            assertTrue(h.repository.creditNotices.first().isEmpty())
            assertEquals(EnzonaFailure.INVALID_INPUT, h.repository.loadHistory(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
        }
    }

    @Test fun rejectedTokenRenewsOnceForReadsButNeverRepeatsAPost() = runBlocking {
        Harness().use { h ->
            h.enzona.rejectNextCardsRead = true
            assertNull(h.repository.refresh())
            assertEquals(1, h.refreshes)
            assertEquals(2, h.paths().count { it.startsWith("/coremz/1.0.2/card/username/") })
            assertEquals("Bearer RENEWED", h.enzona.requests.last().header("Authorization"))
            h.enzona.rejectOwnQr = true
            val result = h.repository.createOwnQr(BigDecimal("10.00"), "CUP", null)
            assertEquals(EnzonaResult.Failed(EnzonaFailure.REJECTED), result)
            assertEquals(1, h.paths().count { it == "/qr/v1.0.0/qr/account" })
        }
    }

    @Test fun eachFailedReadKeepsItsKindAndIsLoggedWithoutPrivateData() = runBlocking {
        val lines = collectingLog { lines ->
            Harness().use { h ->
                h.enzona.historyFailure = SocketTimeoutException("PRIVATE")
                assertEquals(EnzonaFailure.TIMEOUT, h.repository.refresh())
                // The balance was read before the history timed out: fresh balance, failed read.
                assertEquals(2000L, h.repository.cards.first().single().balanceObservedAt)
                h.enzona.historyFailure = UnknownHostException("PRIVATE")
                assertEquals(EnzonaFailure.NETWORK, h.repository.syncCredits())
                h.enzona.historyFailure = null
                h.store.failHistory = true
                assertEquals(EnzonaFailure.LOCAL, h.repository.refresh())
            }
            Harness(accessExpiry = 900).use { h ->
                h.refreshFailure = SocketTimeoutException("PRIVATE")
                assertEquals(EnzonaFailure.TIMEOUT, h.repository.refreshBalance(card))
                assertTrue(h.enzona.requests.isEmpty())
            }
            lines.toList()
        }
        assertEquals(listOf(
            "read failed: op=refresh.history type=TIMEOUT error=SocketTimeoutException",
            "read failed: op=credits type=NETWORK error=UnknownHostException",
            "read failed: op=refresh.history type=LOCAL error=IllegalStateException",
            "read failed: op=balance type=TIMEOUT error=EnzonaSessionUnavailableException(TIMEOUT)",
        ), lines.filter { it.startsWith("read failed") })
        assertTrue(lines.any { it.startsWith("session renewal failed: failure=TIMEOUT error=SocketTimeoutException") })
        assertTrue(lines.none { "PRIVATE" in it || owner in it || card in it || "ACCESS" in it || "REFRESH" in it })
    }

    @Test fun signedOutOrInvalidInputNeverReachesTheNetwork() = runBlocking {
        Harness(signedIn = false).use { h ->
            assertEquals(EnzonaFailure.SIGNED_OUT, h.repository.refresh())
            assertEquals(EnzonaResult.Failed(EnzonaFailure.SIGNED_OUT), h.repository.electricityBills("123"))
            assertTrue(h.enzona.requests.isEmpty())
        }
        Harness().use { h ->
            assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT), h.repository.resolveQr("https://example.invalid/05x"))
            assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT), h.repository.electricityBills(" "))
            assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT), h.repository.createOwnQr(BigDecimal.ZERO, "CUP", null))
            assertTrue(h.enzona.requests.isEmpty())
        }
    }

    @Test fun ownCardNumberMustMatchTheKnownLast4() = runBlocking {
        Harness().use { h ->
            assertEquals(EnzonaFailure.INVALID_INPUT, h.repository.saveCardLink(card, "9200 0000 0000 0007", null)) // cards not read yet
            assertNull(h.repository.refresh())
            assertEquals(EnzonaFailure.INVALID_INPUT, h.repository.saveCardLink(card, "9200000000000008", null))
            assertEquals(EnzonaFailure.INVALID_INPUT, h.repository.saveCardLink(card, "92000000000007", null))
            assertNull(h.repository.saveCardLink(card, "9200 0000 0000 0007", " Nómina "))
            val wallet = h.repository.cards.first().single()
            assertEquals("9200000000000007", wallet.pan)
            assertEquals("Nómina", wallet.alias)
        }
    }

    @Test fun confirmedTransferIsJournaledOnceAndRefreshesItsSourceCard() = runBlocking {
        Harness().use { h ->
            val intent = h.repository.preparePayment(EnzonaCardTransferDraft(card, "9200000000000099", BigDecimal("300.00"), "CUP"))
            assertEquals(EnzonaIntentState.PREPARED, h.repository.journal.first().single().state)
            val update = h.repository.submitPayment(intent, "135790".toCharArray()) { true }
            assertEquals(EnzonaIntentState.CONFIRMED, update.state)
            val entry = h.repository.journal.first().single()
            assertEquals(EnzonaIntentState.CONFIRMED, entry.state)
            assertEquals("remote-1", entry.remoteId)
            assertEquals(1, h.paths().count { it == "/transfer/v1.0.0/transfer/card" })
            assertTrue("/coremz/1.0.2/api/balance/lastOperations/$card" in h.paths())
            val again = h.repository.submitPayment(intent, "135790".toCharArray()) { true }
            assertEquals(false, again.submissionStarted)
            assertEquals(1, h.paths().count { it == "/transfer/v1.0.0/transfer/card" })
        }
    }

    /** A row of ENZONA's history for a transfer of [amount] CUP from the own card to a card ending in 0099. */
    private fun sent(uuid: String, code: Int, at: Long, amount: String = "300.00", to: String = "0099") = """{"transaction_uuid":"$uuid",
        "amount":"$amount","currency":"CUP","transaction_status_code":$code,"transaction_created_at":"${local(at)}",
        "source":{"funding_source_last4":"0007"},"recipient":{"funding_recipient_last4":"$to"}}"""
    private fun local(millis: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(millis).atZone(havana))

    private suspend fun Harness.unconfirmedTransfer(reply: EnzonaHttpReply): String {
        assertNull(repository.refresh()) // the own card's last four digits
        enzona.transferReply = reply
        val intent = repository.preparePayment(EnzonaCardTransferDraft(card, "9200000000000099", BigDecimal("300.00"), "CUP"))
        assertEquals(EnzonaIntentState.UNKNOWN, repository.submitPayment(intent, "135790".toCharArray()) { true }.state)
        return intent.id
    }

    private suspend fun Harness.entry(id: String) = repository.journal.first().single { it.intent.id == id }

    @Test fun anUnconfirmedReplyKeepsItsUuidAndTheHistorySettlesItByItAtOnce() = runBlocking {
        for ((code, expected) in listOf(1111 to EnzonaIntentState.CONFIRMED, 1112 to EnzonaIntentState.REJECTED, 1121 to EnzonaIntentState.UNKNOWN)) {
            Harness().use { h ->
                // Far from the payment's time and amount: only the uuid ties them.
                h.enzona.history = "[${sent("remote-9", code, PAID_AT - 3 * DAY, amount = "1.00", to = "1234")}]"
                val id = h.unconfirmedTransfer(EnzonaHttpReply(400, """{"fault":{"cod":"0507","description":"Contraseña incorrecta"},"transaction_uuid":"remote-9"}"""))
                val entry = h.entry(id)
                assertEquals(expected, entry.state, "code $code")
                assertEquals("remote-9", entry.remoteId)
                assertEquals(400, entry.httpStatus)
                assertEquals("Contraseña incorrecta", entry.faultText)
                assertEquals(if (expected == EnzonaIntentState.UNKNOWN) null else EnzonaHistoryMatch.UUID, entry.matchedBy)
                // The unconfirmed reply was followed by a history and balance read right away.
                assertEquals(2, h.paths().count { it == "/operations/v1.0.0/operations/account" })
            }
        }
    }

    @Test fun withoutAUuidOnlyOneOutgoingRowOfTheSameAmountAndTimeConfirmsIt() = runBlocking {
        Harness().use { h ->
            h.enzona.history = "[${sent("near", 1111, PAID_AT + 60_000)},${sent("other-card", 1111, PAID_AT, to = "1111")}," +
                "${sent("other-amount", 1111, PAID_AT, amount = "300.01")},${sent("too-late", 1111, PAID_AT + DAY + 60_000)}]"
            val id = h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            val entry = h.entry(id)
            assertEquals(EnzonaIntentState.CONFIRMED, entry.state)
            assertEquals("near", entry.remoteId)
            assertEquals(EnzonaHistoryMatch.AMOUNT_AND_TIME, entry.matchedBy)
        }
        Harness().use { h ->
            // Two candidates: neither; and never REJECTED by a failed row of the same amount.
            h.enzona.history = "[${sent("a", 1111, PAID_AT + 60_000)},${sent("b", 1111, PAID_AT + 120_000)}]"
            val two = h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            assertEquals(EnzonaIntentState.UNKNOWN, h.entry(two).state)
        }
        Harness().use { h ->
            h.enzona.history = "[${sent("failed", 1112, PAID_AT + 60_000)}]"
            val failed = h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            assertEquals(EnzonaIntentState.UNKNOWN, h.entry(failed).state)
            assertNull(h.entry(failed).remoteId)
        }
    }

    @Test fun aDayLaterWithNoRowThePersonMayMarkItNotDoneUntilTheRowAppears() = runBlocking {
        Harness().use { h ->
            h.readAt = PAID_AT + 60_000
            val id = h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            assertEquals(PAID_AT + 60_000, h.entry(id).notFoundAt)
            assertFalse(h.repository.markNotDone(id)) // too soon to say
            h.readAt = PAID_AT + DAY + 3_600_000
            assertNull(h.repository.refresh())
            assertEquals(h.readAt, h.entry(id).notFoundAt)
            h.clock = h.readAt + 1
            assertTrue(h.repository.markNotDone(id))
            assertEquals(EnzonaIntentState.UNKNOWN, h.entry(id).state)
            assertEquals(h.clock, h.entry(id).markedNotDoneAt)
            // The row shows up, still pending: the mark is undone. Accepted later: confirmed by amount and time.
            h.enzona.history = "[${sent("late-row", 1121, PAID_AT + 600_000)}]"
            assertNull(h.repository.refresh())
            assertNull(h.entry(id).markedNotDoneAt)
            assertNull(h.entry(id).notFoundAt)
            h.enzona.history = "[${sent("late-row", 1111, PAID_AT + 600_000)}]"
            assertNull(h.repository.refresh())
            assertEquals(EnzonaIntentState.CONFIRMED, h.entry(id).state)
        }
    }

    @Test fun anUnconfirmedPaymentReadsTheHistoryAgainAfterAWhile() = runBlocking {
        Harness(recheckMillis = 1).use { h ->
            h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            withTimeout(5_000) { while (h.enzona.historyReads.get() < 3) delay(10) }
            assertEquals(1, h.paths().count { it == "/transfer/v1.0.0/transfer/card" })
        }
    }

    @Test fun onlyAPaymentThatNeverReachedEnzonaCanBePreparedAgain() = runBlocking {
        Harness().use { h ->
            val id = h.unconfirmedTransfer(EnzonaHttpReply(504, ""))
            assertFailsWith<IllegalArgumentException> { h.repository.prepareAgain(h.entry(id).intent) }
            h.store.journal.update { rows -> rows + (id to rows.getValue(id).copy(state = EnzonaIntentState.NOT_DELIVERED)) }
            val again = h.repository.prepareAgain(h.entry(id).intent)
            assertTrue(again.id != id)
            assertEquals(h.entry(id).intent.copy(id = again.id, preparedAt = again.preparedAt), again)
            assertEquals(EnzonaIntentState.PREPARED, h.entry(again.id).state)
        }
    }

    private fun typed() = listOf("37", "18", "5", "9").map(String::toCharArray)
    private fun cleared(answers: List<CharArray>) = answers.all { typed -> typed.all { it == '\u0000' } }
    private suspend fun Harness.challenge(bank: CardBank = CardBank.BPA) =
        requireNotNull((repository.cardChallenge(bank) as EnzonaResult.Ok).value)

    @Test fun addingACardReadsTheCatalogueThenTheCellsAndSendsOnePostWithTheAccountsHolder() = runBlocking {
        Harness().use { h ->
            val challenge = h.challenge()
            assertEquals(listOf("A02", "D05", "2", "4"), challenge.coordinates.labels)
            assertEquals(listOf("/nomenclator/v1.0.0/nomenclator/banks", "/nomenclator/v1.0.0/nomenclator/coordinates"), h.paths())
            h.enzona.requests.clear()
            val answers = typed()
            assertEquals(EnzonaResult.Ok(Unit), h.repository.addCard("9212060123456781", "0329", challenge, answers))
            assertTrue(cleared(answers))
            assertEquals(listOf("/account/v1.0.0/account", "/coremz/1.0.2/card", "/coremz/1.0.2/card/username/$owner"), h.paths())
            val post = h.enzona.requests[1]
            assertEquals("POST", post.method)
            val body = Json.parseToJsonElement(requireNotNull(post.bodyText())).jsonObject
            fun text(key: String) = body.getValue(key).jsonPrimitive.content
            assertEquals(listOf("pan", "cardholder", "expdate", "cadenaEncript", "CM", "userName"), body.keys.toList())
            assertEquals("Ana Pérez", text("cardholder"))
            assertEquals("0329", text("expdate"))
            assertEquals(owner, text("userName"))
            assertEquals("9212060123456781", Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.DECRYPT_MODE, keys.private)
                String(doFinal(Base64.getDecoder().decode(text("pan"))))
            })
            // Under the certificate the catalogue gives BPA: BANDEC's key does not open it.
            assertEquals("A0237D05182549", oaep(bpaBank.keyPair.private, text("cadenaEncript")))
            assertFailsWith<Exception> { oaep(bandecBank.keyPair.private, text("cadenaEncript")) }
            assertEquals("EnzonaCardChallenge(redacted)", challenge.toString())
        }
    }

    @Test fun aBankTheCatalogueDoesNotNameStopsBeforeAnyCell() = runBlocking {
        Harness().use { h ->
            assertEquals(EnzonaResult.Ok(null), h.repository.cardChallenge(CardBank.BANMET))
            assertEquals(listOf("/nomenclator/v1.0.0/nomenclator/banks"), h.paths())
            h.enzona.banks = """{"banks":[{"name":"Banco Popular de Ahorro","code":"12","certificate":null}]}"""
            assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_RESPONSE), h.repository.cardChallenge(CardBank.BPA))
            assertTrue(h.paths().none { it.endsWith("/coordinates") })
        }
    }

    @Test fun aRefusedCardCarriesEnzonasFaultAndNoSendIsEverRepeated() = runBlocking {
        Harness().use { h ->
            val challenge = h.challenge()
            h.enzona.cardReply = EnzonaHttpReply(400, """{"fault":{"code":"1006","message":"x","description":"Coordenadas incorrectas"}}""")
            var answers = typed()
            val refused = h.repository.addCard("9212060123456781", "0329", challenge, answers) as EnzonaResult.Failed
            assertEquals(EnzonaFailure.REJECTED, refused.reason)
            assertEquals("Coordenadas incorrectas", refused.serviceFault?.description)
            assertTrue(cleared(answers))
            // A fault in an HTTP 200 is a refusal too.
            h.enzona.cardReply = EnzonaHttpReply(200, """{"fault":{"description":"No se pudo registrar la tarjeta correctamente"}}""")
            assertEquals(EnzonaFailure.REJECTED, (h.repository.addCard("9212060123456781", "0329", challenge, typed()) as EnzonaResult.Failed).reason)
            h.enzona.cardReply = EnzonaHttpReply(401, INVALID_CREDENTIALS)
            assertEquals(EnzonaResult.Failed(EnzonaFailure.REJECTED), h.repository.addCard("9212060123456781", "0329", challenge, typed()))
            h.enzona.cardFailure = SocketTimeoutException("PRIVATE")
            answers = typed()
            assertEquals(EnzonaResult.Failed(EnzonaFailure.TIMEOUT), h.repository.addCard("9212060123456781", "0329", challenge, answers))
            assertTrue(cleared(answers))
            assertEquals(4, h.paths().count { it == "/coremz/1.0.2/card" })
            // What cannot be sent never leaves the phone, and is cleared too.
            for (bad in listOf(listOf("37", "", "5", "9"), listOf("3a", "18", "5", "9"), listOf("37", "18", "5"))) {
                answers = bad.map(String::toCharArray)
                assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT), h.repository.addCard("9212060123456781", "0329", challenge, answers))
                assertTrue(cleared(answers))
            }
            assertEquals(EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT), h.repository.addCard("921206012345678", "0329", challenge, typed()))
            assertEquals(4, h.paths().count { it == "/coremz/1.0.2/card" })
            // Nothing was added: the list is not read again.
            assertTrue(h.paths().none { it.startsWith("/coremz/1.0.2/card/username/") })
        }
    }

    @Test fun theDefaultCardAndRemovalSendTheTracedRequestsAndReadTheListAgain() = runBlocking {
        Harness().use { h ->
            assertNull(h.repository.refresh())
            assertNull(h.repository.saveCardLink(card, "9200000000000007", "Nómina"))
            h.enzona.requests.clear()
            assertEquals(EnzonaResult.Ok(Unit), h.repository.setDefaultCard(card))
            val default = h.enzona.requests[0]
            assertEquals("POST", default.method)
            assertEquals("/cards/v1.0.0/cards/default", default.url.encodedPath)
            assertEquals("""{"funding_source_uuid":"$card"}""", default.bodyText())
            assertEquals("/coremz/1.0.2/card/username/$owner", h.enzona.requests[1].url.encodedPath)
            h.enzona.requests.clear()
            assertEquals(EnzonaResult.Ok(Unit), h.repository.removeCard(card))
            val removal = h.enzona.requests[0]
            assertEquals("DELETE", removal.method)
            assertEquals("/cards/v1.0.0/cards/$card/delete", removal.url.encodedPath)
            assertNull(removal.body)
            assertEquals("/coremz/1.0.2/card/username/$owner", h.enzona.requests[1].url.encodedPath)
            // What the phone kept of it goes with it.
            assertTrue(h.store.links.value.isEmpty())
            assertTrue(h.store.cache.value.keys.none { it.second == EnzonaReadKind.BALANCE })
            h.enzona.cardReply = EnzonaHttpReply(500, """{"fault":{"description":"No se pudo"}}""")
            val refused = h.repository.setDefaultCard(card) as EnzonaResult.Failed
            assertEquals(EnzonaFailure.REJECTED, refused.reason)
            assertEquals("No se pudo", refused.serviceFault?.description)
            h.enzona.cardReply = EnzonaHttpReply(401, INVALID_CREDENTIALS)
            assertEquals(EnzonaResult.Failed(EnzonaFailure.REJECTED), h.repository.removeCard(card))
            // The removal above and this one: a rejected token is not tried again.
            assertEquals(2, h.paths().count { it == "/cards/v1.0.0/cards/$card/delete" })
        }
    }

    @Test fun cardChangesLogTheirKindAndStatusButNeverCellsNumbersOrReplies() = runBlocking {
        val lines = collectingLog { lines ->
            Harness().use { h ->
                val challenge = h.challenge()
                h.enzona.cardReply = EnzonaHttpReply(400, """{"fault":{"description":"PRIVATE REPLY"}}""")
                h.repository.addCard("9212060123456781", "0329", challenge, typed())
                h.repository.removeCard(card)
            }
            lines.toList()
        }
        assertEquals(listOf(
            "read failed: op=cards.add type=REJECTED error=EnzonaServiceException http=400",
            "read failed: op=cards.remove type=REJECTED error=EnzonaServiceException http=400",
        ), lines.filter { it.startsWith("read failed") })
        val private = listOf("9212060123456781", "6781", "0329", "A02", "D05", "37", "PRIVATE", owner, card, "Ana", "Pérez")
        assertTrue(lines.none { line -> private.any { it in line } })
    }

    private fun oaep(key: PrivateKey, encrypted: String): String = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
        init(Cipher.DECRYPT_MODE, key, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        String(doFinal(Base64.getDecoder().decode(encrypted)))
    }

    private companion object {
        val havana: ZoneId = ZoneId.of("America/Havana")
        /** 2026-10-14 12:00 in Havana, inside the harness's history window (2026-10-08 to 2026-10-15). */
        val PAID_AT = Instant.parse("2026-10-14T16:00:00Z").toEpochMilli()
        const val DAY = 24 * 60 * 60_000L
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = keys.public as RSAPublicKey
        /** Synthetic banks: self-signed certificates made for these tests. */
        val bpaBank: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("Synthetic BPA").build()
        val bandecBank: HeldCertificate = HeldCertificate.Builder().rsa2048().commonName("Synthetic BANDEC").build()
        fun HeldCertificate.der(): String = Base64.getEncoder().encodeToString(certificate.encoded)
        const val INVALID_CREDENTIALS = """{"fault":{"code":900901,"message":"Invalid Credentials","description":"Invalid Credentials"}}"""
    }
}
