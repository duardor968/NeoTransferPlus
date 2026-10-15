package dev.duardo.neotransfer.plus.data

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.payment.EnzonaCardTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.data.enzona.EnzonaFetched
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHttpReply
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentCoordinator
import dev.duardo.neotransfer.plus.data.enzona.EnzonaQrApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReadApi
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
import dev.duardo.neotransfer.plus.data.enzona.syntheticTokens
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override suspend fun markTimedOut(owner: String, id: String) = transition(owner, id, EnzonaIntentState.SUBMITTING, EnzonaIntentState.UNKNOWN)
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
        val execute: suspend (Request) -> EnzonaHttpReply = { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path == "/account/v1.0.0/account" -> EnzonaHttpReply(200, """{"account":{"username":"me","name":"Ana"}}""")
                path.startsWith("/coremz/1.0.2/card/username/") ->
                    if (rejectNextCardsRead) { rejectNextCardsRead = false; EnzonaHttpReply(401, INVALID_CREDENTIALS) }
                    else EnzonaHttpReply(200, """{"card":[{"funding_source_uuid":"$card","last4":"0007","currency":"CUP"}]}""")
                path.startsWith("/coremz/1.0.2/api/balance/lastOperations/") ->
                    EnzonaHttpReply(200, """{"balance":"1500.25","balance_available":"1400.00","currency":"CUP","operation":[]}""")
                path == "/operations/v1.0.0/operations/account" -> EnzonaHttpReply(200, """{"transaction":$history,"pagination":{"total":0}}""")
                path == "/qr/v1.0.0/qr/account" ->
                    if (rejectOwnQr) EnzonaHttpReply(401, INVALID_CREDENTIALS) else EnzonaHttpReply(200, """{"vendor_identity_code":"05-own"}""")
                path == "/transfer/v1.0.0/transfer/card" -> EnzonaHttpReply(200, """{"statusCode":1111,"transaction_uuid":"remote-1"}""")
                else -> error("Unexpected synthetic request $path")
            }
        }
    }

    private inner class Harness(signedIn: Boolean = true) : AutoCloseable {
        val enzona = FakeEnzona()
        val store = MemoryStore()
        val scheduler = Scheduler()
        val tokens = MemoryTokens(if (signedIn) syntheticTokens(owner, "ACCESS", "REFRESH", 5000) else null)
        var refreshes = 0
        var webCleared = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = EnzonaSession(tokens, object : EnzonaSessionNetwork {
            override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange) = error("not used")
            override suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens { refreshes++; return syntheticTokens(owner, "RENEWED", "REFRESH", 9000) }
            override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) = Unit
            override suspend fun account(tokens: EnzonaTokens): EnzonaFetched<EnzonaAccount> = error("not used")
        }, { emptyMap() }, Dispatchers.Unconfined, { 1000 }, ioDispatcher = Dispatchers.Unconfined)
        val payments = EnzonaPaymentCoordinator(session::usableTokens, store, EnzonaPaymentApi(enzona.execute),
            { EnzonaPaymentRequests(EnzonaCipher(key)) }, Dispatchers.Unconfined, 1_000)
        val repository = EnzonaRepository(session, EnzonaSessionApi(enzona.execute) { 1000 }, EnzonaReadApi(enzona.execute) { 2000 },
            EnzonaQrApi(enzona.execute), EnzonaServiceApi(enzona.execute), payments, store, scheduler, scope,
            { webCleared++ }, { LocalDate.of(2026, 10, 15) })

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
            val update = h.repository.submitPayment(intent, "fixture".toCharArray()) { true }
            assertEquals(EnzonaIntentState.CONFIRMED, update.state)
            val entry = h.repository.journal.first().single()
            assertEquals(EnzonaIntentState.CONFIRMED, entry.state)
            assertEquals("remote-1", entry.remoteId)
            assertEquals(1, h.paths().count { it == "/transfer/v1.0.0/transfer/card" })
            assertTrue("/coremz/1.0.2/api/balance/lastOperations/$card" in h.paths())
            val again = h.repository.submitPayment(intent, "fixture".toCharArray()) { true }
            assertEquals(false, again.submissionStarted)
            assertEquals(1, h.paths().count { it == "/transfer/v1.0.0/transfer/card" })
        }
    }

    private companion object {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        const val INVALID_CREDENTIALS = """{"fault":{"code":900901,"message":"Invalid Credentials","description":"Invalid Credentials"}}"""
    }
}
