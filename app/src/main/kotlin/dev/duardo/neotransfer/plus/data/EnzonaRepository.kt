package dev.duardo.neotransfer.plus.data

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
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
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentCoordinator
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentUpdate
import dev.duardo.neotransfer.plus.data.enzona.EnzonaQrApi
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReadApi
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
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/** Periodic credit check (~15 min, with network) for the signed-in owner. */
internal interface EnzonaCreditScheduler {
    fun schedule()
    fun cancel()
}

/**
 * Facade for the future UI. Flows follow the signed-in owner and come from the Room cache, so screens
 * render offline; [refresh] on open and on pull-to-refresh updates them. Money goes through
 * [preparePayment] → UI review + biometrics → [submitPayment], one send per reviewed intent.
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
    private val payments: EnzonaPaymentCoordinator,
    private val store: EnzonaLocalStore,
    private val scheduler: EnzonaCreditScheduler,
    private val scope: CoroutineScope,
    private val clearWebSession: suspend () -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
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

    /** Local money attempts. UNKNOWN is "Sin confirmar": reconcile with [transactions], never resend. */
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
            withTokens(retryRejectedToken = true) { owner, tokens ->
                var failure: EnzonaFailure? = null
                suspend fun step(block: suspend () -> Unit) {
                    try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    // A rejected token restarts the whole read once with renewed tokens.
                    catch (rejected: EnzonaApiException) { if (rejected.invalidCredentials) throw rejected; failure = failure ?: failureOf(rejected) }
                    catch (other: Exception) { failure = failure ?: failureOf(other) }
                }
                var account: EnzonaAccount? = null
                step { account = ensureAccount(owner, tokens) }
                var cards = emptyList<EnzonaRemoteCard>()
                step {
                    val fetched = reads.cards(tokens)
                    store.cache(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE, fetched.json, fetched.observedAt)
                    cards = fetched.value
                }
                for (card in cards) step { cacheBalance(owner, tokens, card.fundingSourceUuid) }
                step { syncRecentHistory(owner, tokens, account?.username) }
                failure
            }.let { if (it is EnzonaResult.Ok) it.value else (it as EnzonaResult.Failed).reason }
        } finally { mutableRefreshing.value = false }
    }

    suspend fun refreshBalance(cardUuid: String): EnzonaFailure? =
        withTokens(retryRejectedToken = true) { owner, tokens -> cacheBalance(owner, tokens, cardUuid) }.failure()

    /** Older history for the activity screen. Never notifies: these credits are not new. */
    suspend fun loadHistory(from: LocalDate, through: LocalDate): EnzonaFailure? {
        if (from.isAfter(through)) return EnzonaFailure.INVALID_INPUT
        return withTokens(retryRejectedToken = true) { owner, tokens ->
            val page = reads.transactions(tokens, from, through)
            store.saveHistory(owner, page.value.transactions, page.observedAt, notifyCredits = false, accountUsername = null)
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
        withTokens(retryRejectedToken = true) { owner, tokens ->
            syncRecentHistory(owner, tokens, ensureAccount(owner, tokens)?.username)
        }.failure()
    }

    // QR and services ------------------------------------------------------------------------------

    /** Information for review only; resolving never pays or prepares a payment. */
    suspend fun resolveQr(code: String): EnzonaResult<EnzonaResolvedQr> {
        if (!EnzonaQrCode.isCandidate(code)) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens(retryRejectedToken = true) { owner, tokens -> qr.resolve(tokens, owner, code) }
    }

    /** A server-issued receive code (POST, not repeated on a rejected token). */
    suspend fun createOwnQr(amount: BigDecimal, currency: String, description: String?): EnzonaResult<EnzonaOwnQr> {
        if (amount.signum() <= 0 || currency.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens(retryRejectedToken = false) { owner, tokens -> qr.createOwn(tokens, owner, amount, currency, description) }
    }

    suspend fun electricityBills(clientId: String): EnzonaResult<List<EnzonaElectricityBill>> {
        if (clientId.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens(retryRejectedToken = true) { _, tokens -> services.electricityBills(tokens, clientId.trim()).value }
    }

    /** Fixed phone, Nauta or Nauta Hogar. Pass the full identifier (Nauta suffix included). */
    suspend fun etecsaAccounts(service: EnzonaInvoiceService, clientId: String): EnzonaResult<EnzonaEtecsaQuery> {
        if (service == EnzonaInvoiceService.ELECTRICITY || clientId.isBlank()) return EnzonaResult.Failed(EnzonaFailure.INVALID_INPUT)
        return withTokens(retryRejectedToken = true) { _, tokens -> services.etecsaAccounts(tokens, service, clientId.trim()).value }
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
     * payment refreshes the source balance and recent history in the background.
     */
    suspend fun submitPayment(intent: EnzonaIntent, password: CharArray, canDispatch: () -> Boolean): EnzonaPaymentUpdate {
        val update = payments.submit(intent, password, canDispatch)
        if (update.state == EnzonaIntentState.CONFIRMED) scope.launch {
            refreshBalance(intent.cardUuid)
            syncMutex.withLock {
                withTokens(retryRejectedToken = true) { owner, tokens -> syncRecentHistory(owner, tokens, cachedAccount(owner)?.username) }
            }
        }
        return update
    }

    // Internals ------------------------------------------------------------------------------------

    private suspend fun <T> withTokens(retryRejectedToken: Boolean, block: suspend (owner: String, tokens: EnzonaTokens) -> T): EnzonaResult<T> =
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
            EnzonaResult.Failed(failureOf(failure), (failure as? EnzonaServiceException)?.fault)
        }

    private suspend fun cachedAccount(owner: String): EnzonaAccount? =
        store.cached(owner, EnzonaReadKind.ACCOUNT, ACCOUNT_RESOURCE)?.let { row -> decoded { EnzonaReadJson.account(row.json) } }

    private suspend fun ensureAccount(owner: String, tokens: EnzonaTokens): EnzonaAccount? {
        cachedAccount(owner)?.let { return it }
        val fetched = sessionApi.account(tokens)
        store.cache(owner, EnzonaReadKind.ACCOUNT, ACCOUNT_RESOURCE, fetched.json, fetched.observedAt)
        return fetched.value
    }

    private suspend fun cacheBalance(owner: String, tokens: EnzonaTokens, cardUuid: String) {
        val fetched = reads.balance(tokens, cardUuid)
        store.cache(owner, EnzonaReadKind.BALANCE, cardUuid, fetched.json, fetched.observedAt)
    }

    /** The APK's main history window: today − 7 days through today. */
    private suspend fun syncRecentHistory(owner: String, tokens: EnzonaTokens, accountUsername: String?) {
        val through = today()
        val page = reads.transactions(tokens, through.minusDays(7), through)
        store.saveHistory(owner, page.value.transactions, page.observedAt, notifyCredits = true, accountUsername = accountUsername)
    }

    private fun failureOf(failure: Exception): EnzonaFailure = when (failure) {
        is EnzonaSessionUnavailableException -> when (failure.failure) {
            EnzonaSessionFailure.SIGN_IN_AGAIN, EnzonaSessionFailure.RESTORE_FAILED -> EnzonaFailure.SIGNED_OUT
            EnzonaSessionFailure.CLIENT_REJECTED, EnzonaSessionFailure.ACCESS_DENIED, EnzonaSessionFailure.SERVER_ERROR -> EnzonaFailure.REJECTED
            else -> EnzonaFailure.NETWORK
        }
        is InterruptedIOException -> EnzonaFailure.TIMEOUT
        is EnzonaApiException, is EnzonaServiceException -> EnzonaFailure.REJECTED
        is IOException -> EnzonaFailure.NETWORK
        is EnzonaQrResponseException, is IllegalArgumentException -> EnzonaFailure.INVALID_RESPONSE
        else -> EnzonaFailure.NETWORK
    }

    private fun EnzonaResult<*>.failure(): EnzonaFailure? = (this as? EnzonaResult.Failed)?.reason

    private inline fun <T> decoded(block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { null }
}
