package dev.duardo.neotransfer.plus.app

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.SystemClock
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.data.EnzonaDataGraph
import dev.duardo.neotransfer.plus.data.contacts.ContactsStore
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentApproval
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentAuthorizationExpiredException
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionState
import dev.duardo.neotransfer.plus.platform.CreditNotifier
import dev.duardo.neotransfer.plus.platform.PaymentPasswordVault
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Activity tab shows; kept here so Home can open it on one card's record. */
data class ActivityQuery(
    val source: ActivitySource = ActivitySource.Account,
    val query: String = "",
    val filter: ActivityFilter = ActivityFilter.All,
    val card: String? = null,
)

/**
 * Process-wide state of the app: session, lock, the ENZONA reads as flows, refreshes, payments and navigation.
 * Survives the Activity (rotation, theme); the lock and the payment approval follow the process lifecycle.
 */
class PlusApp(context: Context) {
    private val app = context.applicationContext
    val zone: ZoneId = ZoneId.systemDefault()
    val repository = EnzonaDataGraph.get(app).repository
    val contacts = ContactsStore.open(app)
    val vault = PaymentPasswordVault(app)
    val notifier = CreditNotifier(app)
    val preferences = Preferences(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nav = Navigator()
    val approval = EnzonaPaymentApproval()

    val session = repository.sessionState
    val wallet = repository.cards.stateIn(scope, SharingStarted.Eagerly, null)
    val account = repository.account.stateIn(scope, SharingStarted.Eagerly, null)
    val transactions = repository.transactions.stateIn(scope, SharingStarted.Eagerly, null)
    val journal = repository.journal.stateIn(scope, SharingStarted.Eagerly, null)
    val contactList = contacts.contacts.map { it.sortedByName() }.stateIn(scope, SharingStarted.Eagerly, null)

    // Lock -------------------------------------------------------------------------------------------------------

    private val mutableLocked = MutableStateFlow(lockAvailable())
    /** The UI shows the lock while true and the session is signed in. */
    val locked: StateFlow<Boolean> = mutableLocked.asStateFlow()
    private val mutableLockEpoch = MutableStateFlow(0)
    /** Changes each time the lock should prompt by itself: when it engages and when the app returns while locked. */
    val lockEpoch: StateFlow<Int> = mutableLockEpoch.asStateFlow()
    @Volatile var foreground = false
        private set
    private var backgroundSince: Long? = null

    /** No screen lock on the phone: nothing to unlock with, so the app opens directly. */
    fun lockAvailable(): Boolean = app.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun unlock() {
        mutableLocked.value = false
        refresh()
    }

    fun onForeground() {
        foreground = true
        val since = backgroundSince
        backgroundSince = null
        if (since != null && SystemClock.elapsedRealtime() - since >= LockAfterMillis && lockAvailable()) mutableLocked.value = true
        if (locked.value) mutableLockEpoch.update { it + 1 } else if (session.value.signedIn) refresh()
    }

    fun onBackground() {
        foreground = false
        backgroundSince = SystemClock.elapsedRealtime()
        // Leaving the app ends any fingerprint approval of a reviewed payment.
        approval.invalidate()
    }

    // Reads ------------------------------------------------------------------------------------------------------

    private val mutableRefresh = MutableStateFlow(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = mutableRefresh.asStateFlow()
    private val mutableRefreshingCards = MutableStateFlow(emptySet<String>())
    val refreshingCards: StateFlow<Set<String>> = mutableRefreshingCards.asStateFlow()
    val selectedCard = MutableStateFlow(0)
    val activity = MutableStateFlow(ActivityQuery())

    /** First day of account history not loaded yet; the regular read covers the last seven days. */
    private val mutableOlderFrom = MutableStateFlow(LocalDate.now(zone).minusDays(8))
    val olderFrom: StateFlow<LocalDate> = mutableOlderFrom.asStateFlow()
    private val mutableLoadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = mutableLoadingOlder.asStateFlow()

    /** Account, cards, balances and the last week of history; one at a time. */
    fun refresh() {
        if (mutableRefresh.value == RefreshState.Refreshing || !session.value.signedIn) return
        mutableRefresh.value = RefreshState.Refreshing
        scope.launch {
            val failure = repository.refresh()
            mutableRefresh.value = if (failure == null) RefreshState.Idle else RefreshState.Failed
        }
    }

    fun refreshBalance(cardId: String) {
        if (cardId in mutableRefreshingCards.value) return
        mutableRefreshingCards.update { it + cardId }
        scope.launch {
            val failure = repository.refreshBalance(cardId)
            mutableRefreshingCards.update { it - cardId }
            mutableRefresh.value = if (failure == null) RefreshState.Idle else RefreshState.Failed
        }
    }

    /** The thirty days before what is loaded. Older history never notifies. */
    fun loadOlder() {
        if (mutableLoadingOlder.value) return
        mutableLoadingOlder.value = true
        scope.launch {
            val through = mutableOlderFrom.value
            val from = through.minusDays(29)
            if (repository.loadHistory(from, through) == null) mutableOlderFrom.value = from.minusDays(1)
            mutableLoadingOlder.value = false
        }
    }

    // Session ----------------------------------------------------------------------------------------------------

    private fun signedIn() {
        mutableLocked.value = false
        mutableOlderFrom.value = LocalDate.now(zone).minusDays(8)
        selectedCard.value = 0
        activity.value = ActivityQuery()
        refresh()
    }

    /** Local sign-out: session, cached ENZONA data and the saved payment password. Contacts stay. */
    suspend fun signOut(): Boolean {
        val cleared = repository.signOut()
        if (cleared) {
            runCatching { vault.clear() }
            nav.home()
        }
        return cleared
    }

    // Messages ---------------------------------------------------------------------------------------------------

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line problems shown at the bottom of whatever screen is open. */
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    fun say(message: String) {
        mutableMessages.tryEmit(message)
    }

    // Payments ---------------------------------------------------------------------------------------------------

    private val mutableNotices = MutableStateFlow(emptyMap<String, EnzonaPaymentNotice?>())
    /** Latest notice per intent from this process: why a payment is unconfirmed, or that it was never sent. */
    val notices: StateFlow<Map<String, EnzonaPaymentNotice?>> = mutableNotices.asStateFlow()
    private val mutableNotSent = MutableStateFlow(emptyMap<String, String>())
    val notSent: StateFlow<Map<String, String>> = mutableNotSent.asStateFlow()
    /** Names shown for payments of this session that the journal does not keep (merchant, account holder). */
    val titles = MutableStateFlow(emptyMap<String, String>())

    suspend fun prepare(draft: EnzonaPaymentDraft): EnzonaIntent = repository.preparePayment(draft)

    /**
     * One send of the reviewed [intent] with [password] (consumed). The approval is captured now, right after the
     * fingerprint, and stays valid only while the app is unlocked, in front and on the same account.
     */
    fun submit(intent: EnzonaIntent, password: CharArray) {
        val owner = session.value.ownerSubject
        if (owner == null) {
            password.fill('\u0000')
            mutableNotSent.update { it + (intent.id to "No se envió: la sesión de ENZONA terminó.") }
            return
        }
        val ticket = approval.capture(owner)
        scope.launch {
            try {
                val update = repository.submitPayment(intent, password) {
                    approval.isCurrent(ticket, session.value.ownerSubject, !locked.value, foreground)
                }
                mutableNotices.update { it + (intent.id to update.notice) }
            } catch (_: EnzonaPaymentAuthorizationExpiredException) {
                mutableNotSent.update { it + (intent.id to "No se envió: la autorización caducó al salir de la app.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Thrown only before the single dispatch was granted: nothing left the phone.
                mutableNotSent.update { it + (intent.id to "No se envió. No hay conexión con ENZONA o la sesión caducó.") }
            }
        }
    }

    // Notices ----------------------------------------------------------------------------------------------------

    init {
        scope.launch {
            repository.paymentUpdates.collect { update -> mutableNotices.update { it + (update.intentId to update.notice) } }
        }
        scope.launch {
            repository.creditNotices.collect { credits ->
                if (credits.isEmpty()) return@collect
                // Delivered even when notifications are off: turning them on later must not replay old credits.
                credits.forEach { notifier.post(it.transactionUuid, it.noticeTitle(), "tx:${it.transactionUuid}") }
                repository.markCreditNoticesDelivered(credits.map { it.transactionUuid })
            }
        }
        scope.launch {
            var previous: EnzonaSessionState? = null
            session.collect { current ->
                val before = previous
                previous = current
                when {
                    // Signing in proves the person: the app opens unlocked.
                    before != null && before.restored && before.ownerSubject == null && current.ownerSubject != null -> signedIn()
                    // The stored session was read: read ENZONA now if nothing has to be unlocked first.
                    before != null && !before.restored && current.restored && current.signedIn && !locked.value -> refresh()
                    // A session that ends while the app is open returns to the first screen.
                    before?.ownerSubject != null && current.ownerSubject == null -> nav.home()
                }
            }
        }
    }

    companion object {
        const val LockAfterMillis = 60_000L
    }
}
