package dev.duardo.neotransfer.plus.data

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.card.CardBank
import dev.duardo.neotransfer.plus.core.card.EnzonaBanks
import dev.duardo.neotransfer.plus.core.card.EnzonaCardRequests
import dev.duardo.neotransfer.plus.core.card.EnzonaCoordinates
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.core.qr.EnzonaOwnQr
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrCode
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrResponseException
import dev.duardo.neotransfer.plus.core.qr.EnzonaResolvedQr
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaCardBalance
import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaIncomingCredit
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.core.read.EnzonaRemoteCard
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import dev.duardo.neotransfer.plus.core.service.EnzonaElectricityBill
import dev.duardo.neotransfer.plus.core.service.EnzonaEtecsaQuery
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceException
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceFault
import dev.duardo.neotransfer.plus.data.enzona.EnzonaApiException
import dev.duardo.neotransfer.plus.data.enzona.EnzonaCardsApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaLog
import dev.duardo.neotransfer.plus.data.enzona.EnzonaNotDeliveredException
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentCoordinator
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentUpdate
import dev.duardo.neotransfer.plus.data.enzona.EnzonaQrApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReadApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliationChange
import dev.duardo.neotransfer.plus.data.enzona.EnzonaServiceApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSession
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionFailure
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionState
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionUnavailableException
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaCardLink
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaLocalStore
import dev.duardo.neotransfer.plus.data.enzona.db.ACCOUNT_RESOURCE
import dev.duardo.neotransfer.plus.data.enzona.db.CARDS_RESOURCE
import dev.duardo.neotransfer.plus.data.enzona.db.EnzonaReadKind
import dev.duardo.neotransfer.plus.data.enzona.db.validateCardLink
import dev.duardo.neotransfer.plus.data.enzona.draft
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why a read failed. The UI owns the wording. */
enum class EnzonaFailure {
    /** No usable session: sign in again. */
    SIGNED_OUT,
    TIMEOUT,
    NETWORK,
    /** ENZONA answered with an error (HTTP status or service fault). */
    REJECTED,
    /** The reply did not match the traced contract. */
    INVALID_RESPONSE,
    /** The caller's input cannot be sent (for example, a QR outside the 01/05/07 families). */
    INVALID_INPUT,
    /** This phone failed (local storage or an unexpected state); ENZONA is not implicated. */
    LOCAL,
}

sealed interface EnzonaResult<out T> {
    data class Ok<T>(val value: T) : EnzonaResult<T>
    data class Failed(val reason: EnzonaFailure, val serviceFault: EnzonaServiceFault? = null) : EnzonaResult<Nothing>
}

/** An own card as the wallet shows it: ENZONA card, the user's full PAN/alias, and the last observed balance. */
data class EnzonaWalletCard(
    val card: EnzonaRemoteCard,
    val pan: String?,
    val alias: String?,
    val balance: EnzonaCardBalance?,
    val balanceObservedAt: Long?,
) {
    override fun toString(): String = "EnzonaWalletCard(redacted)"
}

/** The holder ENZONA takes for a card added to the account: the account's name and last names (0x9e7bb4). */
val EnzonaAccount.cardholder: String get() = listOfNotNull(name, lastName).filter(String::isNotBlank).joinToString(" ")

/**
 * What adding a card asks before its secrets: the [coordinates] ENZONA names for this attempt and the key of the
 * card's bank from ENZONA's catalogue. Asking again names other cells.
 */
class EnzonaCardChallenge internal constructor(val coordinates: EnzonaCoordinates, internal val bankKey: RSAPublicKey) {
    override fun toString(): String = "EnzonaCardChallenge(redacted)"
}

/** Periodic credit check (~15 min, with network) for the signed-in owner. */
internal interface EnzonaCreditScheduler {
    fun schedule()
    fun cancel()
}

/**
 * Facade for the future UI. Flows follow the signed-in owner and come from the Room cache, so screens
 * render offline; [refresh] on open and on pull-to-refresh updates them. Money goes through
 * [preparePayment] → UI review + biometrics → [submitPayment], one send per reviewed intent. Every history read
 * reconciles the unconfirmed ones ([EnzonaReconciliation]).
 *
 * Incoming-credit notices are a durable queue: collect [creditNotices] from `Application.onCreate`
 * (it also runs when WorkManager starts the process), post "contacto e importe", then call
 * [markCreditNoticesDelivered].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnzonaRepository internal constructor(
    private val session: EnzonaSession,
    private val sessionApi: EnzonaSessionApi,
    private val reads: EnzonaReadApi,
    private val qr: EnzonaQrApi,
    private val services: EnzonaServiceApi,
    private val accountCards: EnzonaCardsApi,
    /** Built on first use: it reads ENZONA's public key. */
    private val cardRequests: () -> EnzonaCardRequests,
    private val payments: EnzonaPaymentCoordinator,
    private val store: EnzonaLocalStore,
    private val scheduler: EnzonaCreditScheduler,
    private val scope: CoroutineScope,
    private val clearWebSession: suspend () -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { Instant.now().toEpochMilli() },
    /** An unconfirmed payment reads the history again right away and once more after this long. */
    private val recheckMillis: Long = 5 * 60_000L,
) {
    private val syncMutex = Mutex()
    private val mutableRefreshing = MutableStateFlow(false)
    private val owner: Flow<String?> = session.state.map { it.ownerSubject }.distinctUntilChanged()

    val sessionState: StateFlow<EnzonaSessionState> = session.state
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()
    val paymentUpdates: SharedFlow<EnzonaPaymentUpdate> = payments.updates

    val account: Flow<EnzonaAccount?> = owner.flatMapLatest { owner ->
        if (owner == null) flowOf(null) else store.observeCache(owner, EnzonaReadKind.ACCOUNT).map { rows ->
            rows.firstOrNull()?.let { decoded { EnzonaReadJson.account(it.json) } }
        }
    }

    val cards: Flow<List<EnzonaWalletCard>> = owner.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else combine(
            store.observeCache(owner, EnzonaReadKind.CARDS),
            store.observeCache(owner, EnzonaReadKind.BALANCE),
            store.observeCardLinks(owner),
        ) { cardRows, balanceRows, links ->
            val balances = balanceRows.associateBy { it.resourceId }
            val linked = links.associateBy { it.cardUuid }
            cardRows.firstOrNull()?.let { row -> decoded { EnzonaReadJson.cards(row.json) } }.orEmpty().map { card ->
                val balance = balances[card.fundingSourceUuid]
                EnzonaWalletCard(card, linked[card.fundingSourceUuid]?.pan, linked[card.fundingSourceUuid]?.alias,
                    balance?.let { decoded { EnzonaReadJson.balance(it.json) } }, balance?.observedAt)
            }
        }
    }

    /** Accumulated ENZONA account history, newest first sight first. Ordering by remote date is the UI's. */
    val transactions: Flow<List<EnzonaTransaction>> = owner.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else store.observeTransactions(owner).map { rows ->
            rows.mapNotNull { decoded { EnzonaReadJson.transaction(it.json) } }
        }
    }

    /** Local money attempts. UNKNOWN is "Sin confirmar": reconciled with [transactions] on every read, never resent. */
    val journal: Flow<List<EnzonaJournalEntry>> = owner.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else store.observeJournal(owner)
    }

    val creditNotices: Flow<List<EnzonaIncomingCredit>> = owner.flatMapLatest { owner ->
        if (owner == null) flowOf(emptyList()) else combine(store.observePendingNotices(owner), account) { rows, account ->
            rows.mapNotNull { row -> decoded { EnzonaReadJson.transaction(row.json) }?.let { EnzonaCredits.incoming(it, account?.username) } }
        }
    }

    init {
        scope.launch { session.restore() }
        scope.launch {
            session.state.map { it.restored to it.ownerSubject }.distinctUntilChanged().collect { (restored, owner) ->
                if (owner != null) scheduler.schedule() else if (restored) scheduler.cancel()
            }
        }
    }

    // Session --------------------------------------------------------------------------------------

    fun beginLogin() = session.beginLogin()
    fun cancelLogin() = session.cancelLogin()
    /** Called by the login WebView listener for main-frame callbacks. */
    fun acceptLoginCallback(url: String, isMainFrame: Boolean = true): Boolean = session.acceptCallback(url, isMainFrame)
    fun clearSessionFailure() = session.clearFailure()

    /**
     * Local sign-out: stored tokens, WebView cookies, the periodic check and the owner's cached reads (account, cards,
     * balances, history, completed card numbers). The payment journal stays: it is the record of money sent.
     */
    suspend fun signOut(): Boolean {
        val owner = session.state.value.ownerSubject
        val cleared = session.signOut()
        scheduler.cancel()
        if (!cleared) return false
        val cacheCleared = owner == null || try { store.clearOwner(owner); true }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        try { clearWebSession() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
        return cacheCleared
    }

    // Reads ----------------------------------------------------------------------------------------

    /** Account (if missing), cards, every card's balance and the last 7 days of history. */
    suspend fun refresh(): EnzonaFailure? = syncMutex.withLock {
        mutableRefreshing.value = true
        try {
            withTokens("refresh", retryRejectedToken = true) { owner, tokens ->
                var failure: EnzonaFailure? = null
                suspend fun step(op: String, block: suspend () -> Unit) {
                    try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    // A rejected token restarts the whole read once with renewed tokens.
                    catch (rejected: EnzonaApiException) { if (rejected.invalidCredentials) throw rejected; failed(op, rejected).let { failure = failure ?: it } }
                    catch (other: Exception) { failed(op, other).let { failure = failure ?: it } }
                }
                var account: EnzonaAccount? = null
                step("refresh.account") { account = ensureAccount(owner, tokens) }
                var cards = emptyList<EnzonaRemoteCard>()
                step("refresh.cards") {
                    val fetched = reads.cards(tokens)
                    store.cache(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE, fetched.json, fetched.observedAt)
                    cards = fetched.value
                }
                for (card in cards) step("refresh.balance") { cacheBalance(owner, tokens, card.fundingSourceUuid) }
                step("refresh.history") { syncRecentHistory(owner, tokens, account?.username) }
                failure
            }.let { if (it is EnzonaResult.Ok) it.value else (it as EnzonaResult.Failed).reason }
        } finally { mutableRefreshing.value = false }
    }

    suspend fun refreshBalance(cardUuid: String): EnzonaFailure? =
        withTokens("balance", retryRejectedToken = true) { owner, tokens -> cacheBalance(owner, tokens, cardUuid) }.failure()

    /** Older history for the activity screen. Never notifies: these credits are not new. */
    suspend fun loadHistory(from: LocalDate, through: LocalDate): EnzonaFailure? {
        if (from.isAfter(through)) return EnzonaFailure.INVALID_INPUT
        return withTokens("history.older", retryRejectedToken = true) { owner, tokens ->
            val page = reads.transactions(tokens, from, through)
            store.saveHistory(owner, page.value.transactions, page.observedAt, notifyCredits = false, accountUsername = null)
            reconcile(owner, from..through, page.observedAt)
        }.failure()
    }

    /** The full own-card number (ENZONA only returns last4) and an optional alias. */
    suspend fun saveCardLink(cardUuid: String, pan: String?, alias: String?): EnzonaFailure? {
        val owner = session.state.value.ownerSubject ?: return EnzonaFailure.SIGNED_OUT
        val digits = pan?.filterNot(Char::isWhitespace)?.takeIf(String::isNotEmpty)
        val cached = store.cached(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE)
            ?.let { row -> decoded { EnzonaReadJson.cards(row.json) } }?.singleOrNull { it.fundingSourceUuid == cardUuid }
            ?: return EnzonaFailure.INVALID_INPUT
        if (digits != null && (digits.length != 16 || !digits.all { it in '0'..'9' } ||
                (!cached.last4.isNullOrBlank() && digits.takeLast(4) != cached.last4))) return EnzonaFailure.INVALID_INPUT
        val link = EnzonaCardLink(owner, cardUuid, digits, alias?.trim()?.takeIf(String::isNotEmpty))
        return try { validateCardLink(link); store.putCardLink(link); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { EnzonaFailure.INVALID_INPUT }
    }

    suspend fun markCreditNoticesDelivered(transactionUuids: List<String>) {
        val owner = session.state.value.ownerSubject ?: return
        store.markNoticesDelivered(owner, transactionUuids)
    }

    /** Worker entry: recent history only, queuing new incoming credits. Never touches money. */
    suspend fun syncCredits(): EnzonaFailure? = syncMutex.withLock {
        session.restore()
        if (!session.state.value.signedIn) return@withLock null
        withTokens("credits", retryRejectedToken = true) { owner, tokens ->
            syncRecentHistory(owner, tokens, ensureAccount(owner, tokens)?.username)
        }.failure()
    }

    // QR and services ------------------------------------------------------------------------------

    /** Information for review only; resolving never pays or prepares a payment. */
    suspend fun resolveQr(code: String): EnzonaResult<EnzonaResolvedQr> {
        if (!EnzonaQrCode.isCandidate(code)) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens("qr.resolve", retryRejectedToken = true) { owner, tokens -> qr.resolve(tokens, owner, code) }
    }

    /** A server-issued receive code (POST, not repeated on a rejected token); without [amount], the payer types it. */
    suspend fun createOwnQr(amount: BigDecimal?, currency: String, description: String?): EnzonaResult<EnzonaOwnQr> {
        if (amount != null && amount.signum() <= 0 || currency.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens("qr.create", retryRejectedToken = false) { owner, tokens -> qr.createOwn(tokens, owner, amount, currency, description) }
    }

    suspend fun electricityBills(clientId: String): EnzonaResult<List<EnzonaElectricityBill>> {
        if (clientId.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens("bills.electricity", retryRejectedToken = true) { _, tokens -> services.electricityBills(tokens, clientId.trim()).value }
    }

    /** Fixed phone, Nauta or Nauta Hogar. Pass the full identifier (Nauta suffix included). */
    suspend fun etecsaAccounts(service: EnzonaInvoiceService, clientId: String): EnzonaResult<EnzonaEtecsaQuery> {
        if (service == EnzonaInvoiceService.ELECTRICITY || clientId.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens("bills.etecsa", retryRejectedToken = true) { _, tokens -> services.etecsaAccounts(tokens, service, clientId.trim()).value }
    }

    // Cards of the account --------------------------------------------------------------------------

    /** The cells for adding a card of [bank]; null when ENZONA's catalogue names no single bank as [bank]. */
    suspend fun cardChallenge(bank: CardBank): EnzonaResult<EnzonaCardChallenge?> =
        withTokens("cards.challenge", retryRejectedToken = true) { _, tokens ->
            val listed = EnzonaBanks.find(accountCards.banks(tokens), bank) ?: return@withTokens null
            val key = EnzonaCardRequests.bankKey(requireNotNull(listed.certificate) { "ENZONA bank without certificate" })
            EnzonaCardChallenge(accountCards.coordinates(tokens), key)
        }

    /**
     * Adds [pan] (expiry `MMAA`) to the account with [answers], what was typed for [challenge]'s cells in their order;
     * they are cleared on every path. One POST, never repeated: a rejected token only renews for a later attempt.
     * The holder is the account's name and last names.
     */
    suspend fun addCard(pan: String, expiry: String, challenge: EnzonaCardChallenge, answers: List<CharArray>): EnzonaResult<Unit> = try {
        val valid = pan.length == 16 && expiry.length == 4 && (pan + expiry).all { it in '0'..'9' } &&
            answers.size == challenge.coordinates.labels.size && answers.all { typed -> typed.isNotEmpty() && typed.all { it in '0'..'9' } }
        if (!valid) EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        else withTokens("cards.add", retryRejectedToken = false) { owner, tokens ->
            val holder = ensureAccount(owner, tokens)?.cardholder.orEmpty()
            val request = withContext(Dispatchers.Default) {
                cardRequests().add(pan, expiry, holder, tokens.accountSubject(), challenge.coordinates, challenge.bankKey, answers)
            }
            accountCards.add(tokens, request)
            reloadCards("cards.add", owner, tokens)
        }
    } finally {
        answers.forEach { it.fill('\u0000') }
    }

    /** Makes [cardUuid] the card ENZONA uses by default. */
    suspend fun setDefaultCard(cardUuid: String): EnzonaResult<Unit> =
        withTokens("cards.default", retryRejectedToken = false) { owner, tokens ->
            accountCards.setDefault(tokens, cardUuid)
            reloadCards("cards.default", owner, tokens)
        }

    /** Takes [cardUuid] out of the account, and its full number, alias and balance off this phone. */
    suspend fun removeCard(cardUuid: String): EnzonaResult<Unit> =
        withTokens("cards.remove", retryRejectedToken = false) { owner, tokens ->
            accountCards.remove(tokens, cardUuid)
            reloadCards("cards.remove", owner, tokens)
            try { store.forgetCard(owner, cardUuid) }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { failed("cards.remove.forget", failure) }
        }

    // Money ----------------------------------------------------------------------------------------

    /** Freezes the reviewed values in the journal. Throws EnzonaPaymentPreparationException or IllegalArgumentException. */
    suspend fun preparePayment(draft: EnzonaPaymentDraft): EnzonaIntent {
        val owner = session.state.value.ownerSubject ?: throw EnzonaSessionUnavailableException(EnzonaSessionFailure.SIGN_IN_AGAIN)
        return payments.prepare(owner, draft)
    }

    /**
     * One send for this reviewed [intent], consuming [password]. [canDispatch] must stay true only while
     * the biometric approval of exactly this review is current (see EnzonaPaymentApproval). A confirmed
     * payment refreshes the source balance and recent history in the background; an unconfirmed one does it at once
     * and again after [recheckMillis] while it stays unconfirmed (the app does it also on every return).
     */
    suspend fun submitPayment(intent: EnzonaIntent, password: CharArray, canDispatch: () -> Boolean): EnzonaPaymentUpdate {
        val update = payments.submit(intent, password, canDispatch)
        when (update.state) {
            EnzonaIntentState.CONFIRMED -> scope.launch { recheck(intent) }
            EnzonaIntentState.UNKNOWN -> scope.launch {
                recheck(intent)
                delay(recheckMillis)
                if (store.intent(intent.ownerSubject, intent.id)?.state == EnzonaIntentState.UNKNOWN) recheck(intent)
            }
            else -> Unit
        }
        return update
    }

    /** A new reviewed intent with the values of [intent], which never reached ENZONA. It needs its own approval. */
    suspend fun prepareAgain(intent: EnzonaIntent): EnzonaIntent {
        val stored = store.intent(intent.ownerSubject, intent.id)
        require(stored?.intent == intent && stored.state == EnzonaIntentState.NOT_DELIVERED) { "Solo un pago que no llegó a ENZONA" }
        return preparePayment(intent.draft())
    }

    /**
     * The person says [intentId], unconfirmed, did not happen: allowed once a history read made at least a day after it
     * covered its time and had no row for it. It stays UNKNOWN underneath, and a row that appears later undoes the mark.
     */
    suspend fun markNotDone(intentId: String): Boolean {
        val owner = session.state.value.ownerSubject ?: return false
        return try { store.markNotDone(owner, intentId, now()) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed("payment.not_done", failure); false }
    }

    private suspend fun recheck(intent: EnzonaIntent) {
        refreshBalance(intent.cardUuid)
        syncMutex.withLock {
            withTokens("payment.history", retryRejectedToken = true) { owner, tokens -> syncRecentHistory(owner, tokens, cachedAccount(owner)?.username) }
        }
    }

    // Internals ------------------------------------------------------------------------------------

    /** [op] names the read in the diagnostic line of a failure. */
    private suspend fun <T> withTokens(op: String, retryRejectedToken: Boolean,
        block: suspend (owner: String, tokens: EnzonaTokens) -> T): EnzonaResult<T> =
        try {
            val tokens = session.usableTokens()
            val owner = tokens.accountSubject()
            try {
                EnzonaResult.Ok(block(owner, tokens))
            } catch (rejected: EnzonaApiException) {
                if (!rejected.invalidCredentials) throw rejected
                // APK behaviour for reads (0x995144): renew once and repeat. Never applied to money.
                session.expire(tokens)
                if (!retryRejectedToken) throw rejected
                val renewed = session.usableTokens()
                check(renewed.accountSubject() == owner)
                EnzonaResult.Ok(block(owner, renewed))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            EnzonaResult.Failed(failed(op, failure), (failure as? EnzonaServiceException)?.fault)
        }

    /** What [failure] means for the person, logged with the read that failed. */
    private fun failed(op: String, failure: Exception): EnzonaFailure = failureOf(failure).also {
        EnzonaLog.warn("read failed: op=$op type=$it error=${EnzonaLog.error(failure)}${EnzonaLog.reply(failure)}")
    }

    private suspend fun cachedAccount(owner: String): EnzonaAccount? =
        store.cached(owner, EnzonaReadKind.ACCOUNT, ACCOUNT_RESOURCE)?.let { row -> decoded { EnzonaReadJson.account(row.json) } }

    private suspend fun ensureAccount(owner: String, tokens: EnzonaTokens): EnzonaAccount? {
        cachedAccount(owner)?.let { return it }
        val fetched = sessionApi.account(tokens)
        store.cache(owner, EnzonaReadKind.ACCOUNT, ACCOUNT_RESOURCE, fetched.json, fetched.observedAt)
        return fetched.value
    }

    /** The card list after a change ENZONA accepted. A failed read is logged; the change stands. */
    private suspend fun reloadCards(op: String, owner: String, tokens: EnzonaTokens) {
        try {
            val fetched = reads.cards(tokens)
            store.cache(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE, fetched.json, fetched.observedAt)
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { failed("$op.reload", failure) }
    }

    private suspend fun cacheBalance(owner: String, tokens: EnzonaTokens, cardUuid: String) {
        val fetched = reads.balance(tokens, cardUuid)
        store.cache(owner, EnzonaReadKind.BALANCE, cardUuid, fetched.json, fetched.observedAt)
    }

    /** The APK's main history window: today − 7 days through today. */
    private suspend fun syncRecentHistory(owner: String, tokens: EnzonaTokens, accountUsername: String?) {
        val through = today()
        val from = through.minusDays(7)
        val page = reads.transactions(tokens, from, through)
        store.saveHistory(owner, page.value.transactions, page.observedAt, notifyCredits = true, accountUsername = accountUsername)
        reconcile(owner, from..through, page.observedAt)
    }

    /** What the stored history says of the owner's UNKNOWN payments after a read of [read]. A failure here fails no read. */
    private suspend fun reconcile(owner: String, read: ClosedRange<LocalDate>, observedAt: Long) {
        try {
            val journal = store.observeJournal(owner).first()
            if (journal.none { it.state == EnzonaIntentState.UNKNOWN }) return
            val history = store.observeTransactions(owner).first().mapNotNull { row -> decoded { EnzonaReadJson.transaction(row.json) } }
            val ownLast4 = store.cached(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE)?.let { row -> decoded { EnzonaReadJson.cards(row.json) } }
                .orEmpty().mapNotNull { card -> card.last4?.let { card.fundingSourceUuid to it } }.toMap()
            val changes = EnzonaReconciliation.plan(journal, history, ownLast4, zone, read, observedAt)
            if (changes.isEmpty()) return
            store.reconcile(owner, changes)
            changes.filterIsInstance<EnzonaReconciliationChange.Settle>().forEach {
                EnzonaLog.info("payment reconciled: ${it.state} by=${it.matchedBy.name.lowercase()}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { EnzonaLog.warn("payment reconciliation failed: error=${EnzonaLog.error(failure)}") }
    }

    /** NETWORK only for a connection that failed; anything this phone failed at is LOCAL. */
    private fun failureOf(failure: Exception): EnzonaFailure = when (failure) {
        is EnzonaSessionUnavailableException -> when (failure.failure) {
            EnzonaSessionFailure.SIGN_IN_AGAIN, EnzonaSessionFailure.RESTORE_FAILED, EnzonaSessionFailure.LOCAL_CLEAR_FAILED -> EnzonaFailure.SIGNED_OUT
            EnzonaSessionFailure.CLIENT_REJECTED, EnzonaSessionFailure.ACCESS_DENIED, EnzonaSessionFailure.SERVER_ERROR,
            EnzonaSessionFailure.CALLBACK_REJECTED -> EnzonaFailure.REJECTED
            EnzonaSessionFailure.TIMEOUT -> EnzonaFailure.TIMEOUT
            EnzonaSessionFailure.UNAVAILABLE -> EnzonaFailure.NETWORK
            EnzonaSessionFailure.LOCAL_SAVE_FAILED -> EnzonaFailure.LOCAL
        }
        // The request did not leave the phone: no connection, or none in time.
        is EnzonaNotDeliveredException -> if (failure.cause is InterruptedIOException) EnzonaFailure.TIMEOUT else EnzonaFailure.NETWORK
        is InterruptedIOException -> EnzonaFailure.TIMEOUT
        is EnzonaApiException, is EnzonaServiceException -> EnzonaFailure.REJECTED
        is IOException -> EnzonaFailure.NETWORK
        is EnzonaQrResponseException, is IllegalArgumentException -> EnzonaFailure.INVALID_RESPONSE
        else -> EnzonaFailure.LOCAL
    }

    private fun EnzonaResult<*>.failure(): EnzonaFailure? = (this as? EnzonaResult.Failed)?.reason

    private inline fun <T> decoded(block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { null }
}
