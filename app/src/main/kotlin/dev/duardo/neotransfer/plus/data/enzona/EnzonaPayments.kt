package dev.duardo.neotransfer.plus.data.enzona

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.payment.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaCardTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaInvoicePaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequest
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.core.payment.EnzonaQrMerchantPaymentDraft
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import java.io.InterruptedIOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class EnzonaIntentType { CARD_TRANSFER, ACCOUNT_TRANSFER, QR_MERCHANT_PAYMENT, INVOICE_PAYMENT }

/** PAN, recipient account UUID, vendor identity code, or invoice client_id. Only account transfer needs username. */
data class EnzonaDestination(val value: String, val username: String? = null) {
    override fun toString(): String = "EnzonaDestination(redacted)"
}

/** Exact reviewed values. No payment password, fingerprint or encoded request is ever stored. */
data class EnzonaIntent(
    val id: String,
    val ownerSubject: String,
    val cardUuid: String,
    val type: EnzonaIntentType,
    @Embedded(prefix = "destination_") val destination: EnzonaDestination,
    val amount: String,
    val currency: String,
    val description: String? = null,
    val phone: String? = null,
    val preparedAt: Long,
    val invoiceService: String? = null,
    val invoiceOwner: String? = null,
    val invoiceFolio: String? = null,
    val invoiceRoute: String? = null,
    val invoicePeriod: String? = null,
) {
    override fun toString(): String = "EnzonaIntent(redacted)"
}

/** One local money attempt. [remoteId] is only an observed `transaction_uuid`, never an invoice uuid. */
@Entity(tableName = "intent_journal", primaryKeys = ["id"], indices = [Index("ownerSubject")])
data class EnzonaJournalEntry(
    @Embedded val intent: EnzonaIntent,
    val state: EnzonaIntentState = EnzonaIntentState.PREPARED,
    val remoteId: String? = null,
) {
    override fun toString(): String = "EnzonaJournalEntry(state=$state, privateFields=redacted)"
}

internal interface EnzonaPaymentJournal {
    suspend fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry
    suspend fun intent(owner: String, id: String): EnzonaJournalEntry?
    /** Compare-and-set PREPARED → SUBMITTING. A winning CAS grants exactly one dispatch, ever. */
    suspend fun tryBeginSubmission(owner: String, id: String): Boolean
    suspend fun markTimedOut(owner: String, id: String): Boolean
    suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?): Boolean
}

/** Why an update needs attention. The UI owns the wording. */
enum class EnzonaPaymentNotice {
    /** This attempt was already dispatched or is not PREPARED: prepare and review a new one. */
    NOT_RESENDABLE,
    /** The wait expired: "Sin confirmar". Check history; never resend. */
    TIMEOUT,
    /** The reply did not prove acceptance or rejection: "Sin confirmar". */
    UNCONFIRMED,
    /** Approval expired after the CAS: nothing was sent, and this attempt cannot be sent anymore. */
    NOT_SENT_AUTHORIZATION_EXPIRED,
    /** A terminal reply conflicted with the stored result. */
    NEEDS_REVIEW,
    /** The result could not be stored. Check history. */
    NOT_SAVED,
}

class EnzonaPaymentUpdate internal constructor(
    val ownerSubject: String,
    val intentId: String,
    val state: EnzonaIntentState,
    /** A successful local CAS, not a claim that the server received the request. */
    val submissionStarted: Boolean,
    val notice: EnzonaPaymentNotice? = null,
    val evidence: EnzonaPaymentEvidence? = null,
    /** Evidence that arrived after the caller stopped waiting. */
    val late: Boolean = false,
) {
    override fun toString() = "EnzonaPaymentUpdate(state=$state, late=$late, notice=$notice, privateFields=redacted)"
}

class EnzonaPaymentPreparationException internal constructor() : IllegalStateException("No se pudo preparar el pago de ENZONA")
class EnzonaPaymentAuthorizationExpiredException internal constructor() : CancellationException("La autorización del pago ha caducado")

/**
 * The UI reviews the returned intent and runs biometrics before [submit]. Reuse that SAME intent on
 * duplicate callbacks; preparing again creates a different user-approved operation. Its own scope outlives
 * the caller's wait, so late evidence from the single original POST is still recorded. Never resends.
 */
internal class EnzonaPaymentCoordinator(
    private val usableTokens: suspend () -> EnzonaTokens,
    private val journal: EnzonaPaymentJournal,
    private val network: EnzonaPaymentNetwork,
    private val encoder: () -> EnzonaPaymentRequests,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val responseWaitMillis: Long = 30_000,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) : AutoCloseable {
    init { require(responseWaitMillis > 0) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUpdates = MutableSharedFlow<EnzonaPaymentUpdate>(extraBufferCapacity = 16)
    /** Room is authoritative for persisted state. Unresolved late evidence is transient only. */
    val updates: SharedFlow<EnzonaPaymentUpdate> = mutableUpdates.asSharedFlow()

    suspend fun prepare(ownerSubject: String, draft: EnzonaPaymentDraft): EnzonaIntent {
        check(scope.isActive) { "El coordinador de pagos está cerrado" }
        require(ownerSubject.isNotBlank() && ownerSubject.none(Char::isISOControl)) { "Cuenta de ENZONA no válida" }
        val (type, destination) = when (draft) {
            is EnzonaCardTransferDraft -> {
                val pan = draft.recipientPan.replace(" ", "")
                require(pan.length == 16 && pan.all { it in '0'..'9' }) { "Tarjeta destinataria no válida" }
                EnzonaIntentType.CARD_TRANSFER to EnzonaDestination(pan)
            }
            is EnzonaAccountTransferDraft -> EnzonaIntentType.ACCOUNT_TRANSFER to
                EnzonaDestination(draft.fundingRecipientUuid, draft.fundingRecipientUsername)
            is EnzonaQrMerchantPaymentDraft -> EnzonaIntentType.QR_MERCHANT_PAYMENT to EnzonaDestination(draft.vendorIdentityCode)
            is EnzonaInvoicePaymentDraft -> EnzonaIntentType.INVOICE_PAYMENT to EnzonaDestination(draft.clientId)
        }
        val invoice = draft as? EnzonaInvoicePaymentDraft
        val intent = EnzonaIntent(UUID.randomUUID().toString(), ownerSubject, draft.fundingSourceUuid, type,
            destination, Amounts.wire(draft.amount), draft.currency, draft.description,
            when (draft) { is EnzonaCardTransferDraft -> draft.phone; is EnzonaQrMerchantPaymentDraft -> draft.phone; else -> null }, now(),
            invoiceService = invoice?.service?.name, invoiceOwner = invoice?.owner,
            invoiceFolio = invoice?.folio, invoiceRoute = invoice?.route, invoicePeriod = invoice?.period)
        try {
            return journal.prepareIntent(intent).also { check(it.intent == intent) }.intent
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw EnzonaPaymentPreparationException() }
    }

    /**
     * Password ownership transfers here, including failures before tokens/encryption/CAS. [canDispatch]
     * is the UI's approval check (biometric result still bound to this review, foreground, same owner);
     * it runs after token renewal and again immediately before and after the CAS.
     */
    suspend fun submit(intent: EnzonaIntent, password: CharArray, canDispatch: () -> Boolean = { true }): EnzonaPaymentUpdate {
        val request: EnzonaPaymentRequest
        val tokens: EnzonaTokens
        try {
            currentCoroutineContext().ensureActive()
            check(scope.isActive)
            val stored = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
            require(stored.intent == intent)
            if (stored.state != EnzonaIntentState.PREPARED) return update(stored, false, EnzonaPaymentNotice.NOT_RESENDABLE)
            tokens = usableTokens()
            require(tokens.accountSubject() == intent.ownerSubject)
            request = withContext(dispatcher) { encoder().encode(intent.draft(), password) }
            currentCoroutineContext().ensureActive()
            check(scope.isActive)
            if (!canDispatch()) throw EnzonaPaymentAuthorizationExpiredException()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw EnzonaPaymentPreparationException() }
        finally { password.fill('\u0000') }

        val decision = CompletableDeferred<Unit>()
        val won = AtomicBoolean(false)
        val late = AtomicBoolean(false)
        val flight = scope.async {
            var observed: EnzonaPaymentEvidence? = null
            try {
                // Once the database grants this dispatch it must never become eligible again.
                val allowed = withContext(NonCancellable) {
                    if (!scope.isActive || !canDispatch()) throw EnzonaPaymentAuthorizationExpiredException()
                    journal.tryBeginSubmission(intent.ownerSubject, intent.id)
                }
                won.set(allowed)
                decision.complete(Unit)
                if (!allowed) return@async update(requireNotNull(journal.intent(intent.ownerSubject, intent.id)), false,
                    EnzonaPaymentNotice.NOT_RESENDABLE)
                currentCoroutineContext().ensureActive()
                // Room may have waited for a lock while authorization, owner or foreground changed.
                if (!canDispatch()) return@async withContext(NonCancellable) {
                    uncertain(intent, EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED, late = late.get())
                        .also(mutableUpdates::tryEmit)
                }
                val evidence = network.submit(tokens, request)
                observed = evidence
                withContext(NonCancellable) {
                    if (evidence.state.isTerminal) {
                        val recorded = journal.recordResult(intent.ownerSubject, intent.id, evidence.state, evidence.transactionUuid)
                        val stored = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
                        update(stored, true, if (recorded) null else EnzonaPaymentNotice.NEEDS_REVIEW, evidence, late.get())
                    } else {
                        uncertain(intent, EnzonaPaymentNotice.UNCONFIRMED, evidence, late.get())
                    }.also(mutableUpdates::tryEmit)
                }
            } catch (cancelled: CancellationException) {
                if (won.get()) withContext(NonCancellable) {
                    uncertain(intent, EnzonaPaymentNotice.UNCONFIRMED, observed, late.get()).also(mutableUpdates::tryEmit)
                }
                throw cancelled
            } catch (failure: Exception) {
                if (!won.get()) throw EnzonaPaymentPreparationException()
                withContext(NonCancellable) {
                    uncertain(intent, if (failure is InterruptedIOException) EnzonaPaymentNotice.TIMEOUT
                        else EnzonaPaymentNotice.UNCONFIRMED, observed, late.get()).also(mutableUpdates::tryEmit)
                }
            } finally { decision.complete(Unit) }
        }
        flight.invokeOnCompletion { decision.complete(Unit) }
        try {
            decision.await()
            if (!won.get()) return flight.await()
            val immediate = withTimeoutOrNull(responseWaitMillis) { flight.await() }
            if (immediate != null) return immediate
            late.set(true)
            return withContext(NonCancellable) {
                uncertain(intent, EnzonaPaymentNotice.TIMEOUT).also(mutableUpdates::tryEmit)
            }
        } catch (cancelled: CancellationException) {
            if (won.get()) {
                late.set(true)
                withContext(NonCancellable) { uncertain(intent, EnzonaPaymentNotice.UNCONFIRMED).also(mutableUpdates::tryEmit) }
            }
            // Do not cancel the independent original HTTP flight when only its waiter leaves.
            throw cancelled
        }
    }

    private suspend fun uncertain(intent: EnzonaIntent, notice: EnzonaPaymentNotice, evidence: EnzonaPaymentEvidence? = null,
        late: Boolean = false): EnzonaPaymentUpdate {
        return try {
            journal.markTimedOut(intent.ownerSubject, intent.id)
            val entry = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
            update(entry, true, if (entry.state == EnzonaIntentState.UNKNOWN) notice else null, evidence, late)
        } catch (_: Exception) {
            // A database failure does not prove that the server rejected a dispatched operation.
            EnzonaPaymentUpdate(intent.ownerSubject, intent.id, EnzonaIntentState.UNKNOWN, true,
                EnzonaPaymentNotice.NOT_SAVED, evidence, late)
        }
    }

    private fun update(entry: EnzonaJournalEntry, started: Boolean, notice: EnzonaPaymentNotice? = null,
        evidence: EnzonaPaymentEvidence? = null, late: Boolean = false) =
        EnzonaPaymentUpdate(entry.intent.ownerSubject, entry.intent.id, entry.state, started, notice, evidence, late)

    private fun EnzonaIntent.draft(): EnzonaPaymentDraft = when (type) {
        EnzonaIntentType.CARD_TRANSFER -> EnzonaCardTransferDraft(cardUuid, destination.value, amount.toBigDecimal(), currency, description, phone.orEmpty())
        EnzonaIntentType.ACCOUNT_TRANSFER -> EnzonaAccountTransferDraft(cardUuid, destination.value, requireNotNull(destination.username), amount.toBigDecimal(), currency, description)
        EnzonaIntentType.QR_MERCHANT_PAYMENT -> EnzonaQrMerchantPaymentDraft(cardUuid, destination.value, amount.toBigDecimal(), currency, description, phone.orEmpty())
        EnzonaIntentType.INVOICE_PAYMENT -> EnzonaInvoicePaymentDraft(
            EnzonaInvoiceService.valueOf(requireNotNull(invoiceService)), destination.value, requireNotNull(invoiceOwner),
            cardUuid, amount.toBigDecimal(), currency, description, invoiceFolio, invoiceRoute, invoicePeriod)
    }

    override fun close() { scope.cancel() }
}

/**
 * Binds a biometric approval to the reviewed screen: leaving it (background, lock, another owner)
 * permanently invalidates the ticket, even after returning. Use it to build `canDispatch`.
 */
class EnzonaPaymentApproval {
    class Ticket internal constructor(internal val revision: Long, internal val owner: String)
    private val revision = AtomicLong()
    fun capture(owner: String) = Ticket(revision.get(), owner)
    fun invalidate() { revision.incrementAndGet() }
    fun isCurrent(ticket: Ticket, owner: String?, unlocked: Boolean, resumed: Boolean): Boolean =
        ticket.revision == revision.get() && ticket.owner == owner && unlocked && resumed
}
