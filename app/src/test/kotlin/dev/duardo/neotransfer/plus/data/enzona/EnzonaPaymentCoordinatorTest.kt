package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.payment.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaInvoicePaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentFault
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequest
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.core.service.EnzonaServicePaymentState
import dev.duardo.neotransfer.plus.data.enzona.db.couldBeUndelivered

import java.io.IOException
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaPaymentCoordinatorTest {
    private val owner = "synthetic-owner"
    private class Journal : EnzonaPaymentJournal {
        val rows = mutableMapOf<String, EnzonaJournalEntry>()
        val steps = mutableListOf<String>()
        var failPrepare = false
        var failRecord = false
        var afterBegin: suspend () -> Unit = {}
        override suspend fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry = synchronized(rows) {
            steps += "prepare"
            check(!failPrepare)
            rows.getOrPut(intent.id) { EnzonaJournalEntry(intent) }.also { require(it.intent == intent) }
        }
        override suspend fun intent(owner: String, id: String) = synchronized(rows) { rows[id]?.takeIf { it.intent.ownerSubject == owner } }
        override suspend fun tryBeginSubmission(owner: String, id: String): Boolean {
            val won = synchronized(rows) {
                steps += "CAS"
                val current = rows[id] ?: return@synchronized false
                if (current.intent.ownerSubject != owner || current.state != EnzonaIntentState.PREPARED) return@synchronized false
                rows[id] = current.copy(state = EnzonaIntentState.SUBMITTING)
                true
            }
            if (won) afterBegin()
            return won
        }
        override suspend fun markUnknown(owner: String, id: String, evidence: EnzonaPaymentEvidence?): Boolean = synchronized(rows) {
            val current = rows[id] ?: return@synchronized false
            if (current.intent.ownerSubject != owner || current.state !in setOf(EnzonaIntentState.SUBMITTING, EnzonaIntentState.UNKNOWN)) return@synchronized false
            rows[id] = current.copy(state = EnzonaIntentState.UNKNOWN, remoteId = current.remoteId ?: evidence?.transactionUuid,
                httpStatus = evidence?.httpStatus ?: current.httpStatus ?: 0, faultCode = evidence?.fault?.code ?: current.faultCode,
                faultText = evidence?.fault?.text ?: current.faultText)
            current.state == EnzonaIntentState.SUBMITTING
        }
        override suspend fun markNotDelivered(owner: String, id: String): Boolean = synchronized(rows) {
            val current = rows[id] ?: return@synchronized false
            if (current.intent.ownerSubject != owner || !current.couldBeUndelivered()) return@synchronized false
            rows[id] = current.copy(state = EnzonaIntentState.NOT_DELIVERED)
            true
        }
        override suspend fun abandonFlights(): Int = synchronized(rows) {
            steps += "abandon"
            val flying = rows.values.filter { it.state == EnzonaIntentState.SUBMITTING }
            flying.forEach { rows[it.intent.id] = it.copy(state = EnzonaIntentState.UNKNOWN) }
            flying.size
        }
        override suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?): Boolean = synchronized(rows) {
            check(!failRecord)
            val current = rows[id] ?: return@synchronized false
            if (current.intent.ownerSubject != owner || current.state == EnzonaIntentState.PREPARED) return@synchronized false
            if (current.state in setOf(EnzonaIntentState.CONFIRMED, EnzonaIntentState.REJECTED) && current.state != state) return@synchronized false
            if (current.remoteId != null && remoteId != null && current.remoteId != remoteId) return@synchronized false
            rows[id] = current.copy(state = state, remoteId = current.remoteId ?: remoteId)
            true
        }
    }

    private fun tokens(subject: String = owner): EnzonaTokens = syntheticTokens(subject)
    private fun draft() = EnzonaAccountTransferDraft("remote-card", "recipient-uuid", "recipient-user", BigDecimal("10.2300"), "cup", "reviewed")
    private fun success(transactionUuid: String? = "observed-id", uuid: String? = null) =
        EnzonaPaymentEvidence(EnzonaIntentState.CONFIRMED, 200, "1111", transactionUuid, uuid, 123)
    private fun password() = "135790".toCharArray()
    private fun cleared(value: CharArray) = assertTrue(value.all { it == '\u0000' })
    private fun manager(journal: Journal, wait: Long = 500, tokenSource: suspend () -> EnzonaTokens = { tokens() },
        send: suspend (EnzonaTokens, EnzonaPaymentRequest) -> EnzonaPaymentEvidence) =
        EnzonaPaymentCoordinator(tokenSource, journal, EnzonaPaymentNetwork(send), { EnzonaPaymentRequests(EnzonaCipher(publicKey)) }, Dispatchers.Unconfined, wait, { 100 })

    @Test fun prepareFreezesReviewAndTokensPrecedeTheOnlyCasAndPost() = runBlocking {
        val journal = Journal()
        manager(journal, tokenSource = { journal.steps += "tokens"; tokens() }) { _, request ->
            journal.steps += "POST"
            assertEquals(EnzonaIntentState.SUBMITTING, journal.rows.values.single().state)
            assertEquals("/transfer/v1.0.0/transfer/account", request.path)
            success()
        }.use { coordinator ->
            val reviewed = draft()
            val intent = coordinator.prepare(owner, reviewed)
            assertEquals(listOf("abandon", "prepare"), journal.steps)
            assertEquals("10.2300", intent.amount)
            assertEquals(reviewed.fundingSourceUuid, intent.cardUuid)
            assertEquals(reviewed.fundingRecipientUuid, intent.destination.value)
            assertEquals(reviewed.fundingRecipientUsername, intent.destination.username)
            assertEquals(reviewed.currency, intent.currency)
            val secret = password()
            val result = coordinator.submit(intent, secret)
            cleared(secret)
            assertEquals(EnzonaIntentState.CONFIRMED, result.state)
            assertEquals(listOf("abandon", "prepare", "tokens", "CAS", "POST"), journal.steps)
            assertEquals(intent, journal.rows.getValue(intent.id).intent)
            assertEquals("observed-id", journal.rows.getValue(intent.id).remoteId)
            assertFalse(coordinator.submit(intent, password()).submissionStarted)
            assertEquals(1, journal.steps.count { it == "POST" })
        }
    }

    @Test fun aPaymentThatFailsSaysWhyAndIsLoggedWithoutItsDetails() = runBlocking {
        val lines = collectingLog { lines ->
            val journal = Journal()
            manager(journal, tokenSource = { throw EnzonaSessionUnavailableException(EnzonaSessionFailure.TIMEOUT) }) { _, _ ->
                error("never sent")
            }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val stopped = assertFailsWith<EnzonaPaymentPreparationException> { coordinator.submit(intent, password()) }
                assertEquals(EnzonaSessionFailure.TIMEOUT, stopped.session)
            }
            manager(journal) { _, _ -> throw SocketTimeoutException("PRIVATE") }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                assertEquals(EnzonaPaymentNotice.TIMEOUT, coordinator.submit(intent, password()).notice)
            }
            lines.toList()
        }
        assertEquals(listOf(
            "payment not sent: POST /transfer/v1.0.0/transfer/account session=TIMEOUT error=EnzonaSessionUnavailableException(TIMEOUT)",
            "payment request failed: POST /transfer/v1.0.0/transfer/account error=SocketTimeoutException",
            "payment UNKNOWN: POST /transfer/v1.0.0/transfer/account notice=TIMEOUT http=- status_code=- sent=true late=false",
        ), lines)
        assertTrue(lines.none { "10.23" in it || "recipient" in it || "remote-card" in it || "fixture" in it || owner in it })
    }

    @Test fun racingCallbacksGrantOnlyOnePost() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaPaymentEvidence>()
            var sends = 0
            manager(journal) { _, _ -> sends++; reply.await() }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val firstPassword = password()
                val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.submit(intent, firstPassword) }
                val duplicatePassword = password()
                val duplicate = coordinator.submit(intent, duplicatePassword)
                assertFalse(duplicate.submissionStarted)
                assertEquals(EnzonaIntentState.SUBMITTING, duplicate.state)
                cleared(firstPassword); cleared(duplicatePassword)
                reply.complete(success())
                assertEquals(EnzonaIntentState.CONFIRMED, first.await().state)
                assertEquals(1, sends)
            }
        }
    }

    @Test fun deadlineMarksUnknownAndOriginalLateReplyConfirmsWithoutResending() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaPaymentEvidence>()
            var sends = 0
            manager(journal, wait = 15) { _, _ -> sends++; reply.await() }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val late = async(start = CoroutineStart.UNDISPATCHED) { coordinator.updates.first { it.late } }
                val secret = password()
                val timeout = coordinator.submit(intent, secret)
                cleared(secret)
                assertEquals(EnzonaIntentState.UNKNOWN, timeout.state)
                assertEquals(EnzonaPaymentNotice.TIMEOUT, timeout.notice)
                assertFalse(coordinator.submit(intent, password()).submissionStarted)
                reply.complete(success("history-id", "different-uuid"))
                val observed = late.await()
                assertEquals(EnzonaIntentState.CONFIRMED, observed.state)
                assertEquals("different-uuid", observed.evidence!!.uuid)
                assertEquals("history-id", journal.rows.getValue(intent.id).remoteId)
                assertEquals(1, sends)
            }
        }
    }

    @Test fun lateUnresolvedEvidenceDoesNotFabricateATerminalState() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaPaymentEvidence>()
            var sends = 0
            manager(journal, 15) { _, _ -> sends++; reply.await() }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val late = async(start = CoroutineStart.UNDISPATCHED) { coordinator.updates.first { it.late } }
                coordinator.submit(intent, password())
                reply.complete(EnzonaPaymentEvidence(EnzonaIntentState.UNKNOWN, 200, "9999", "observed", null, 456))
                assertEquals("9999", late.await().evidence!!.statusCode)
                assertEquals(EnzonaIntentState.UNKNOWN, journal.rows.getValue(intent.id).state)
                // Kept to find its row in the history, never as a result.
                assertEquals("observed", journal.rows.getValue(intent.id).remoteId)
                assertEquals(200, journal.rows.getValue(intent.id).httpStatus)
                assertEquals(1, sends)
            }
        }
    }

    @Test fun cancelingUiWaiterKeepsOriginalFlightAndRecordsLateEvidence() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaPaymentEvidence>()
            manager(journal) { _, _ -> reply.await() }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val late = async(start = CoroutineStart.UNDISPATCHED) { coordinator.updates.first { it.late } }
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.submit(intent, password()) }
                waiter.cancelAndJoin()
                assertEquals(EnzonaIntentState.UNKNOWN, journal.rows.getValue(intent.id).state)
                reply.complete(success())
                assertEquals(EnzonaIntentState.CONFIRMED, late.await().state)
            }
        }
    }

    @Test fun timeoutFromTransportIsUnknownAndNeverRetryable() = runBlocking {
        val journal = Journal()
        var sends = 0
        manager(journal) { _, _ -> sends++; throw SocketTimeoutException("private-fixture") }.use { coordinator ->
            val intent = coordinator.prepare(owner, draft())
            val result = coordinator.submit(intent, password())
            assertEquals(EnzonaIntentState.UNKNOWN, result.state)
            assertEquals(EnzonaPaymentNotice.TIMEOUT, result.notice)
            assertEquals(0, journal.rows.getValue(intent.id).httpStatus) // no reply arrived
            assertFalse(coordinator.submit(intent, password()).submissionStarted)
            assertEquals(1, sends)
        }
    }

    @Test fun aBodyThatNeverLeftThePhoneIsNotDeliveredAndCanNeverBeSentAgain() = runBlocking {
        val lines = collectingLog { lines ->
            for (cause in listOf<IOException>(ConnectException("PRIVATE"), SocketTimeoutException("PRIVATE"))) {
                val journal = Journal()
                var sends = 0
                manager(journal) { _, _ -> sends++; throw EnzonaNotDeliveredException(cause) }.use { coordinator ->
                    val intent = coordinator.prepare(owner, draft())
                    val result = coordinator.submit(intent, password())
                    assertEquals(EnzonaIntentState.NOT_DELIVERED, result.state)
                    assertTrue(result.submissionStarted)
                    assertNull(result.notice)
                    assertEquals(EnzonaIntentState.NOT_DELIVERED, journal.rows.getValue(intent.id).state)
                    val again = coordinator.submit(intent, password())
                    assertFalse(again.submissionStarted)
                    assertEquals(EnzonaPaymentNotice.NOT_RESENDABLE, again.notice)
                    assertEquals(1, sends)
                }
            }
            lines.toList()
        }
        assertTrue(lines.any { it.startsWith("payment NOT_DELIVERED: POST /transfer/v1.0.0/transfer/account notice=- http=-") }, lines.toString())
        assertTrue(lines.none { "PRIVATE" in it })
    }

    @Test fun aReplyWithAFaultStaysUnknownAndKeepsWhatEnzonaSaid() = runBlocking {
        val lines = collectingLog { lines ->
            val journal = Journal()
            val fault = EnzonaPaymentFault("0507", "Contraseña de pago incorrecta", listOf("cod", "description"))
            manager(journal) { _, _ -> EnzonaPaymentEvidence(EnzonaIntentState.UNKNOWN, 400, null, "fault-id", null, 1, fault = fault) }
                .use { coordinator ->
                    val intent = coordinator.prepare(owner, draft())
                    val result = coordinator.submit(intent, password())
                    assertEquals(EnzonaIntentState.UNKNOWN, result.state)
                    assertEquals(EnzonaPaymentNotice.UNCONFIRMED, result.notice)
                    val row = journal.rows.getValue(intent.id)
                    assertEquals(400, row.httpStatus)
                    assertEquals("0507", row.faultCode)
                    assertEquals("Contraseña de pago incorrecta", row.faultText)
                    assertEquals("fault-id", row.remoteId)
                }
            lines.toList()
        }
        assertEquals(listOf("payment UNKNOWN: POST /transfer/v1.0.0/transfer/account notice=UNCONFIRMED http=400 status_code=- " +
            "fault=cod,description sent=true late=false"), lines)
    }

    @Test fun whatAnEarlierProcessLeftInFlightIsUnconfirmedBeforeAnyNewSend() = runBlocking {
        val journal = Journal()
        val earlier = manager(journal) { _, _ -> error("not sent") }.use { it.prepare(owner, draft()) }
        journal.rows[earlier.id] = journal.rows.getValue(earlier.id).copy(state = EnzonaIntentState.SUBMITTING)
        val lines = collectingLog { lines ->
            manager(journal) { _, _ -> success() }.use { coordinator ->
                assertEquals(EnzonaIntentState.UNKNOWN, journal.rows.getValue(earlier.id).state)
                assertEquals(EnzonaIntentState.CONFIRMED, coordinator.submit(coordinator.prepare(owner, draft()), password()).state)
            }
            lines.toList()
        }
        assertEquals(listOf("payments in flight at start: 1 marked UNKNOWN"), lines)
        assertTrue(journal.steps.lastIndexOf("abandon") < journal.steps.indexOf("CAS"))
    }

    @Test fun tokenFailureOrDifferentOwnerLeavesPreparedAndErasesPassword() = runBlocking {
        for (provider in listOf<suspend () -> EnzonaTokens>({ throw IllegalStateException("private-fixture") }, { tokens("other") })) {
            val journal = Journal()
            manager(journal, tokenSource = provider) { _, _ -> error("must not send") }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val secret = password()
                val failure = assertFailsWith<EnzonaPaymentPreparationException> { coordinator.submit(intent, secret) }
                cleared(secret)
                assertNull(failure.cause)
                assertEquals(EnzonaIntentState.PREPARED, journal.rows.getValue(intent.id).state)
                assertFalse("CAS" in journal.steps)
            }
        }
    }

    @Test fun expiredAuthorizationIsCheckedAfterTokensAndAgainImmediatelyBeforeCas() = runBlocking {
        for (rejectAt in 1..2) {
            val journal = Journal()
            manager(journal, tokenSource = { journal.steps += "tokens"; tokens() }) { _, _ -> error("must not send") }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                var checks = 0
                val secret = password()
                assertFailsWith<EnzonaPaymentAuthorizationExpiredException> {
                    coordinator.submit(intent, secret) {
                        assertTrue("tokens" in journal.steps)
                        cleared(secret) // encryption has already consumed it at both authorization gates
                        ++checks < rejectAt
                    }
                }
                cleared(secret)
                assertEquals(rejectAt, checks)
                assertFalse("CAS" in journal.steps)
                assertEquals(EnzonaIntentState.PREPARED, journal.rows.getValue(intent.id).state)
            }
        }
    }

    @Test fun cancelingDuringRefreshCannotConsumeTheCas() = runBlocking {
        val journal = Journal()
        val refreshing = CompletableDeferred<Unit>()
        manager(journal, tokenSource = { refreshing.await(); tokens() }) { _, _ -> error("must not send") }.use { coordinator ->
            val intent = coordinator.prepare(owner, draft())
            val secret = password()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { coordinator.submit(intent, secret) }
            waiting.cancelAndJoin()
            cleared(secret)
            assertFalse("CAS" in journal.steps)
            assertEquals(EnzonaIntentState.PREPARED, journal.rows.getValue(intent.id).state)
        }
    }

    @Test fun pauseAndResumeWhileCasIsPendingNeverSendsThePayment() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val casCommitted = CompletableDeferred<Unit>()
            val releaseCas = CompletableDeferred<Unit>()
            journal.afterBegin = { casCommitted.complete(Unit); releaseCas.await() }
            val access = EnzonaPaymentApproval()
            val ticket = access.capture(owner)
            var resumed = true
            var sends = 0
            manager(journal) { _, _ -> sends++; success() }.use { coordinator ->
                val intent = coordinator.prepare(owner, draft())
                val secret = password()
                val submitting = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.submit(intent, secret) { access.isCurrent(ticket, owner, unlocked = true, resumed) }
                }
                casCommitted.await()
                access.invalidate()
                resumed = false
                assertFalse(access.isCurrent(ticket, owner, unlocked = true, resumed))
                resumed = true
                releaseCas.complete(Unit)
                val result = submitting.await()
                assertEquals(0, sends)
                assertEquals(EnzonaIntentState.NOT_DELIVERED, result.state)
                assertEquals(EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED, result.notice)
                cleared(secret)
            }
        }
    }

    @Test fun authorizationChangingWhileCasReturnsPreventsPostAndCannotReactivateAfterLogin() = runBlocking {
        withTimeout(5000) {
            for (invalidation in listOf("deadline", "owner", "logout", "locked", "background")) {
                val journal = Journal()
                val casCommitted = CompletableDeferred<Unit>()
                val releaseCas = CompletableDeferred<Unit>()
                journal.afterBegin = { casCommitted.complete(Unit); releaseCas.await() }
                var clock = 1_000L
                val authorizedAt = clock
                var currentOwner: String? = owner
                var unlocked = true
                var resumed = true
                var checks = 0
                var sends = 0
                val canDispatch = {
                    checks++
                    resumed && unlocked && currentOwner == owner && clock - authorizedAt < 60_000
                }
                manager(journal) { _, _ -> sends++; success() }.use { coordinator ->
                    val intent = coordinator.prepare(owner, draft())
                    val secret = password()
                    val submitting = async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.submit(intent, secret, canDispatch)
                    }
                    casCommitted.await()
                    assertEquals(2, checks)
                    assertEquals(EnzonaIntentState.SUBMITTING, journal.rows.getValue(intent.id).state)
                    when (invalidation) {
                        "deadline" -> clock += 60_001
                        "owner" -> currentOwner = "different-owner"
                        "logout" -> currentOwner = null
                        "locked" -> unlocked = false
                        "background" -> resumed = false
                    }
                    releaseCas.complete(Unit)
                    val result = submitting.await()
                    cleared(secret)
                    assertEquals(3, checks)
                    assertEquals(0, sends)
                    assertTrue(result.submissionStarted) // CAS was consumed, not a network send.
                    assertEquals(EnzonaIntentState.NOT_DELIVERED, result.state)
                    assertEquals(EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED, result.notice)
                    assertNull(result.evidence)
                    assertEquals(EnzonaIntentState.NOT_DELIVERED, journal.rows.getValue(intent.id).state)
                    clock = authorizedAt
                    currentOwner = owner
                    unlocked = true
                    resumed = true
                    val later = coordinator.submit(intent, password(), canDispatch)
                    assertFalse(later.submissionStarted)
                    assertEquals(EnzonaIntentState.NOT_DELIVERED, later.state)
                    assertEquals(0, sends)
                    assertEquals(1, journal.steps.count { it == "CAS" })
                }
            }
        }
    }

    @Test fun modifiedReviewNonPreparedIntentAndFailedEncodingCannotDispatch() = runBlocking {
        val journal = Journal()
        manager(journal) { _, _ -> error("must not send") }.use { coordinator ->
            val intent = coordinator.prepare(owner, draft())
            val changedPassword = password()
            assertFailsWith<EnzonaPaymentPreparationException> { coordinator.submit(intent.copy(amount = "999"), changedPassword) }
            cleared(changedPassword)
            val oversize = CharArray(246) { 'x' }
            assertFailsWith<EnzonaPaymentPreparationException> { coordinator.submit(intent, oversize) }
            cleared(oversize)
            for (state in listOf(EnzonaIntentState.UNKNOWN, EnzonaIntentState.SUBMITTING, EnzonaIntentState.REJECTED)) {
                journal.rows[intent.id] = journal.rows.getValue(intent.id).copy(state = state)
                val notPrepared = password()
                val result = coordinator.submit(intent, notPrepared)
                assertFalse(result.submissionStarted)
                assertEquals(EnzonaPaymentNotice.NOT_RESENDABLE, result.notice)
                cleared(notPrepared)
            }
            assertFalse("CAS" in journal.steps)
        }
    }

    @Test fun prepareMustBeDurableBeforeItCanBeReviewedOrSent() = runBlocking {
        val journal = Journal().also { it.failPrepare = true }
        manager(journal) { _, _ -> error("must not send") }.use { coordinator ->
            assertFailsWith<EnzonaPaymentPreparationException> { coordinator.prepare(owner, draft()) }
            assertTrue(journal.rows.isEmpty())
            assertFalse("CAS" in journal.steps)
        }
    }

    @Test fun uuidAloneIsNotStoredAsAnAccountHistoryIdentifier() = runBlocking {
        val journal = Journal()
        manager(journal) { _, _ -> success(null, "separate-uuid") }.use { coordinator ->
            val intent = coordinator.prepare(owner, draft())
            val result = coordinator.submit(intent, password())
            assertEquals(EnzonaIntentState.CONFIRMED, result.state)
            assertEquals("separate-uuid", result.evidence!!.uuid)
            assertNull(journal.rows.getValue(intent.id).remoteId)
        }
    }

    @Test fun persistenceFailureAfterReplyRetainsEvidenceAndDoesNotAllowResend() = runBlocking {
        val journal = Journal().also { it.failRecord = true }
        manager(journal) { _, _ -> success() }.use { coordinator ->
            val intent = coordinator.prepare(owner, draft())
            val result = coordinator.submit(intent, password())
            assertEquals(EnzonaIntentState.UNKNOWN, result.state)
            assertEquals("observed-id", result.evidence!!.transactionUuid)
            assertFalse(coordinator.submit(intent, password()).submissionStarted)
        }
    }

    @Test fun closingCoordinatorMakesAnInflightAttemptUnknown() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaPaymentEvidence>()
            val coordinator = manager(journal) { _, _ -> reply.await() }
            val intent = coordinator.prepare(owner, draft())
            val caller = async(start = CoroutineStart.UNDISPATCHED) { coordinator.submit(intent, password()) }
            coordinator.close()
            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(EnzonaIntentState.UNKNOWN, journal.rows.getValue(intent.id).state)
        }
    }

    private fun invoiceDraft(service: EnzonaInvoiceService) = EnzonaInvoicePaymentDraft(service, "client-id",
        if (service == EnzonaInvoiceService.ELECTRICITY) "invoice-owner" else "client-id", "remote-card", BigDecimal("12.3400"),
        "cup", "reviewed invoice", if (service == EnzonaInvoiceService.ELECTRICITY) "folio" else null,
        if (service == EnzonaInvoiceService.ELECTRICITY) "route" else null, if (service == EnzonaInvoiceService.ELECTRICITY) "202610" else null)

    @Test fun invoiceJournalFreezesAllReviewFieldsAndRebuildsOnlyATransientRequest() = runBlocking {
        for (service in EnzonaInvoiceService.entries) {
            val journal = Journal()
            var sent: EnzonaPaymentRequest? = null
            val api = EnzonaPaymentApi({ EnzonaHttpReply(200, if (service == EnzonaInvoiceService.ELECTRICITY)
                """{"charged_invoice":{"status_code":1111,"uuid":"invoice-uuid"}}""" else
                """{"charged_invoice":{"status":"Aceptada","uuid":"invoice-uuid"}}""") })
            manager(journal) { tokens, request -> sent = request; api.submit(tokens, request) }.use { coordinator ->
                val draft = invoiceDraft(service)
                val intent = coordinator.prepare(owner, draft)
                assertEquals(EnzonaIntentType.INVOICE_PAYMENT, intent.type)
                assertEquals(owner, intent.ownerSubject)
                assertEquals(draft.clientId, intent.destination.value)
                assertNull(intent.destination.username)
                assertEquals(service.name, intent.invoiceService)
                assertEquals(draft.owner, intent.invoiceOwner)
                assertEquals(draft.folio, intent.invoiceFolio)
                assertEquals(draft.route, intent.invoiceRoute)
                assertEquals(draft.period, intent.invoicePeriod)
                assertEquals("12.3400", intent.amount)
                assertEquals(draft.currency, intent.currency)
                val secret = password()
                val result = coordinator.submit(intent, secret)
                cleared(secret)
                assertEquals(EnzonaIntentState.CONFIRMED, result.state)
                assertEquals(service, sent!!.service)
                assertEquals(intent, journal.rows.getValue(intent.id).intent)
                assertNull(journal.rows.getValue(intent.id).remoteId)
                assertEquals("invoice-uuid", result.evidence!!.uuid)
                assertEquals(1, journal.steps.count { it == "CAS" })
                assertFalse(coordinator.submit(intent, password()).submissionStarted)
            }
        }
    }

    @Test fun pendingInvoicesRemainUnknownAndLateFailedEvidenceIsRecordedWithoutResend() = runBlocking {
        withTimeout(5000) {
            val journal = Journal()
            val reply = CompletableDeferred<EnzonaHttpReply>()
            var sends = 0
            val api = EnzonaPaymentApi({ sends++; reply.await() })
            manager(journal, 15) { tokens, request -> api.submit(tokens, request) }.use { coordinator ->
                val intent = coordinator.prepare(owner, invoiceDraft(EnzonaInvoiceService.NAUTA_HOME))
                val late = async(start = CoroutineStart.UNDISPATCHED) { coordinator.updates.first { it.late } }
                val timeout = coordinator.submit(intent, password())
                assertEquals(EnzonaIntentState.UNKNOWN, timeout.state)
                assertEquals(EnzonaPaymentNotice.TIMEOUT, timeout.notice)
                reply.complete(EnzonaHttpReply(200, """{"charged_invoice":{"status":"Fallida","uuid":"invoice-only-id"}}"""))
                assertEquals(EnzonaIntentState.REJECTED, late.await().state)
                assertEquals(EnzonaIntentState.REJECTED, journal.rows.getValue(intent.id).state)
                assertNull(journal.rows.getValue(intent.id).remoteId)
                assertFalse(coordinator.submit(intent, password()).submissionStarted)
                assertEquals(1, sends)
            }
        }
        val journal = Journal()
        val api = EnzonaPaymentApi({ EnzonaHttpReply(200, """{"charged_invoice":{"status":"Pendiente"}}""") })
        manager(journal) { tokens, request -> api.submit(tokens, request) }.use { coordinator ->
            val intent = coordinator.prepare(owner, invoiceDraft(EnzonaInvoiceService.FIXED_PHONE))
            val result = coordinator.submit(intent, password())
            assertEquals(EnzonaIntentState.UNKNOWN, result.state)
            assertEquals(EnzonaPaymentNotice.UNCONFIRMED, result.notice)
            assertEquals(EnzonaServicePaymentState.PENDING, result.evidence!!.serviceResult!!.state)
        }
    }

    @Test fun invoiceReviewChangesOrExpiredBiometricsNeverConsumeTheCas() = runBlocking {
        val journal = Journal()
        manager(journal) { _, _ -> error("must not send") }.use { coordinator ->
            val intent = coordinator.prepare(owner, invoiceDraft(EnzonaInvoiceService.ELECTRICITY))
            for (changed in listOf(intent.copy(invoicePeriod = "changed"), intent.copy(invoiceService = "NAUTA"),
                intent.copy(invoiceOwner = "changed"), intent.copy(invoiceRoute = "changed"), intent.copy(invoiceFolio = "changed"))) {
                val secret = password()
                assertFailsWith<EnzonaPaymentPreparationException> { coordinator.submit(changed, secret) }
                cleared(secret)
            }
            var checks = 0
            val secret = password()
            assertFailsWith<EnzonaPaymentAuthorizationExpiredException> {
                coordinator.submit(intent, secret) { ++checks < 2 }
            }
            cleared(secret)
            assertEquals(EnzonaIntentState.PREPARED, journal.rows.getValue(intent.id).state)
            assertFalse("CAS" in journal.steps)
        }
    }

    private companion object {
        val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
    }
}
