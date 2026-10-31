package dev.duardo.neotransfer.plus.app

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.SystemClock
import dev.duardo.neotransfer.plus.core.activity.SyncOperation
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.data.EnzonaDataGraph
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactsStore
import dev.duardo.neotransfer.plus.data.contacts.Directory
import dev.duardo.neotransfer.plus.data.contacts.PhoneContact
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaLog
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentApproval
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentAuthorizationExpiredException
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentPreparationException
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentUpdate
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionState
import dev.duardo.neotransfer.plus.data.services.SavedReferences
import dev.duardo.neotransfer.plus.platform.AndroidPhoneBook
import dev.duardo.neotransfer.plus.platform.CreditNotifier
import dev.duardo.neotransfer.plus.platform.PaymentPasswordVault
import dev.duardo.neotransfer.plus.platform.PeerActivity
import dev.duardo.neotransfer.plus.platform.PeerContacts
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import java.time.Instant
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /** Names and removals of the references in Guardados; the references come from the [journal]. */
    val savedMarks = SavedReferences(app)
    val saved: StateFlow<List<SavedReferenceUi>> = combine(journal, savedMarks.marks) { entries, marks ->
        savedReferences(entries.orEmpty(), marks.names, marks.removed)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Contacts ---------------------------------------------------------------------------------------------------

    private val phoneBook = AndroidPhoneBook(app)
    private val agendaLock = Mutex()
    /** One read of the address book; [people] null when it cannot be read. */
    private class Agenda(val people: List<PhoneContact>?)
    /** Null until the first read. */
    private val agenda = MutableStateFlow<Agenda?>(null)

    /** Everyone in the address book with the cards kept for them, plus the contacts kept only in the app, by name. */
    val contactList: StateFlow<List<Contact>?> = combine(contacts.contacts, agenda) { stored, book ->
        book?.let { Directory.merge(stored, it.people) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Ids of the [contactList] entries that come from the address book; null while it cannot be read. */
    val addressBook: StateFlow<Set<String>?> = agenda.map { book -> book?.people?.mapTo(hashSetOf(), Directory::id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Reads the address book (on each return to the app and after READ_CONTACTS is granted) and moves the cards kept
     * for someone onto their entry in it ([Directory.link]); first takes in what changed in NeoTransfer ([pullPeer]).
     */
    fun readAddressBook() {
        scope.launch {
            agendaLock.withLock {
                pullPeer()
                val people = withContext(Dispatchers.IO) {
                    try {
                        phoneBook.read()
                    } catch (_: SecurityException) {
                        null
                    }
                }
                if (people != null) {
                    val (save, delete) = Directory.link(contacts.contacts.first(), people)
                    if (save.isNotEmpty() || delete.isNotEmpty()) runCatching { contacts.relink(save, delete) }
                }
                agenda.value = Agenda(people)
            }
        }
    }

    /**
     * Takes in the contacts and cards added, changed or deleted in NeoTransfer ([ContactsStore.merge]); [contactList]
     * follows by itself. Without NeoTransfer, or one signed with another key, nothing happens.
     */
    private suspend fun pullPeer() {
        try {
            val changed = withContext(Dispatchers.IO) { PeerContacts.read(app)?.let { contacts.merge(it) } }
            if (changed != null && changed > 0) EnzonaLog.info("contacts sync: $changed from peer")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    // NeoTransfer's activity ---------------------------------------------------------------------------------------

    private val mutablePeerActivity = MutableStateFlow<List<SyncOperation>?>(null)
    /**
     * What NeoTransfer did, from its last read; null while it is not installed, not signed like this app or cannot be
     * read: then the activity shows this app's own rows only, without dots.
     */
    val peerActivity: StateFlow<List<SyncOperation>?> = mutablePeerActivity.asStateFlow()

    /** Reads NeoTransfer's activity again; called on each return to the app. */
    private fun readPeerActivity() {
        scope.launch {
            val read = try {
                withContext(Dispatchers.IO) { PeerActivity.read(app) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutablePeerActivity.value = read
            if (read != null) EnzonaLog.info("activity sync: ${read.size} from peer")
        }
    }

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
        readAddressBook()
        readPeerActivity()
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

    private val mutableRefresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
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
        val started = Instant.now()
        scope.launch {
            mutableRefresh.value = repository.refresh().toRefreshState(started)
        }
    }

    fun refreshBalance(cardId: String) {
        if (cardId in mutableRefreshingCards.value) return
        mutableRefreshingCards.update { it + cardId }
        val started = Instant.now()
        scope.launch {
            val failure = repository.refreshBalance(cardId)
            mutableRefreshingCards.update { it - cardId }
            mutableRefresh.value = failure.toRefreshState(started)
        }
    }

    private fun EnzonaFailure?.toRefreshState(since: Instant): RefreshState = this?.let { RefreshState.Failed(it.readProblem(), since) } ?: RefreshState.Idle

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
        nav.home()
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

    /** A line at the bottom of the screen; [action] undoes what just happened. */
    class Message(val text: String, val action: String? = null, val onAction: () -> Unit = {})

    private val mutableMessages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    /** One-line problems, or what just happened with its undo, shown at the bottom of whatever screen is open. */
    val messages: SharedFlow<Message> = mutableMessages.asSharedFlow()

    fun say(message: String, action: String? = null, onAction: () -> Unit = {}) {
        mutableMessages.tryEmit(Message(message, action, onAction))
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

    /** A new intent with the values of one that never reached ENZONA; it is sent with its own approval. */
    suspend fun prepareAgain(intent: EnzonaIntent): EnzonaIntent =
        repository.prepareAgain(intent).also { again -> titles.value[intent.id]?.let { name -> titles.update { it + (again.id to name) } } }

    /**
     * One send of the reviewed [intent] with [password] (consumed). The approval is captured now, right after the
     * fingerprint, and stays valid only while the app is unlocked, in front and on the same account. [sealed] is the
     * password typed for it, kept once this payment is confirmed; null when the saved one was used.
     */
    fun submit(intent: EnzonaIntent, password: CharArray, sealed: PaymentPasswordVault.Sealed?) {
        mutableNotSent.update { it - intent.id }
        val owner = session.value.ownerSubject
        if (owner == null) {
            password.fill('\u0000')
            mutableNotSent.update { it + (intent.id to "La sesión de ENZONA terminó antes del envío.") }
            return
        }
        passwords[intent.id] = PasswordUse(sealed)
        val ticket = approval.capture(owner)
        scope.launch {
            try {
                val update = repository.submitPayment(intent, password) {
                    approval.isCurrent(ticket, session.value.ownerSubject, !locked.value, foreground)
                }
                mutableNotices.update { it + (intent.id to update.notice) }
                settle(update)
            } catch (_: EnzonaPaymentAuthorizationExpiredException) {
                passwords -= intent.id
                mutableNotSent.update { it + (intent.id to "La autorización caducó al salir de la app.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Thrown only before the single dispatch was granted: nothing left the phone.
                passwords -= intent.id
                mutableNotSent.update { it + (intent.id to notSentText((failure as? EnzonaPaymentPreparationException)?.session)) }
            }
        }
    }

    // Payment password -------------------------------------------------------------------------------------------

    /** The password a payment of this process used: typed and [sealed] for this phone, or the saved one (null). */
    private class PasswordUse(val sealed: PaymentPasswordVault.Sealed?)
    private val passwords = mutableMapOf<String, PasswordUse>()

    private fun settle(update: EnzonaPaymentUpdate) {
        // A confirmation the journal failed to keep is still ENZONA's.
        val state = update.evidence?.state?.takeIf { it != EnzonaIntentState.UNKNOWN } ?: update.state
        settle(update.intentId, passwordOutcome(state, replied = update.evidence != null))
    }

    /** Keeps a typed password once its payment is confirmed; puts the password in doubt when ENZONA answered otherwise. */
    private fun settle(intentId: String, outcome: PasswordOutcome) {
        val use = passwords[intentId] ?: return
        when (outcome) {
            PasswordOutcome.Wait -> return
            PasswordOutcome.Confirmed -> runCatching { use.sealed?.let(vault::store) ?: vault.markVerified() }
            PasswordOutcome.InDoubt -> vault.markUnverified()
            PasswordOutcome.Unused -> Unit
        }
        passwords -= intentId
    }

    // Notices ----------------------------------------------------------------------------------------------------

    init {
        scope.launch {
            repository.paymentUpdates.collect { update ->
                mutableNotices.update { it + (update.intentId to update.notice) }
                settle(update)
            }
        }
        scope.launch {
            // The history can settle a payment long after its reply: its password follows.
            journal.collect { entries ->
                entries.orEmpty().filter { it.intent.id in passwords && it.state.isTerminal }
                    .forEach { settle(it.intent.id, passwordOutcome(it.state, replied = true)) }
            }
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

/** What a payment's outcome says of the payment password it used. */
enum class PasswordOutcome {
    /** ENZONA confirmed it: the typed password is kept, the saved one is no longer in doubt. */
    Confirmed,
    /** ENZONA answered without confirming: the next payment asks for the password again. */
    InDoubt,
    /** No answer yet: a late reply or the history may still tell. */
    Wait,
    /** It never reached ENZONA: the password says nothing. */
    Unused,
}

/** [replied]: an HTTP reply arrived for this payment. */
fun passwordOutcome(state: EnzonaIntentState, replied: Boolean): PasswordOutcome = when (state) {
    EnzonaIntentState.CONFIRMED -> PasswordOutcome.Confirmed
    EnzonaIntentState.REJECTED -> PasswordOutcome.InDoubt
    EnzonaIntentState.UNKNOWN -> if (replied) PasswordOutcome.InDoubt else PasswordOutcome.Wait
    EnzonaIntentState.SUBMITTING -> PasswordOutcome.Wait
    EnzonaIntentState.PREPARED, EnzonaIntentState.NOT_DELIVERED -> PasswordOutcome.Unused
}
