package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why the session needs attention. The UI owns the wording. */
enum class EnzonaSessionFailure {
    /** The encrypted local session could not be read; sign in again. */
    RESTORE_FAILED,
    /** The login callback did not match this attempt (state, code or URI) or was refused. */
    CALLBACK_REJECTED,
    /** The refresh grant is gone (`invalid_grant`) or absent: sign in again. */
    SIGN_IN_AGAIN,
    /** `invalid_client`/`unauthorized_client`: the public client ID was not accepted for this app. */
    CLIENT_REJECTED,
    /** Network, timeout or an unexpected reply; the saved session is kept. */
    UNAVAILABLE,
    /** Local sign-out could not erase the stored session. */
    LOCAL_CLEAR_FAILED,
}

data class EnzonaSessionState(
    /** Literal id_token `sub` of a locally stored session; not a claim of current server validity. */
    val ownerSubject: String? = null,
    /** The stored session has been read once since process start. */
    val restored: Boolean = false,
    /** Load this in the login WebView while non-null. */
    val authorizationUrl: String? = null,
    val busy: Boolean = false,
    val failure: EnzonaSessionFailure? = null,
) {
    val signedIn: Boolean get() = ownerSubject != null
    override fun toString() = "EnzonaSessionState(signedIn=$signedIn, busy=$busy, failure=$failure, privateFields=redacted)"
}

internal interface EnzonaSessionNetwork {
    suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens
    suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens
    suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>)
    suspend fun account(tokens: EnzonaTokens): EnzonaFetched<EnzonaAccount>
}

internal class SessionNetwork(private val api: EnzonaSessionApi) : EnzonaSessionNetwork {
    override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange) = api.exchange(exchange)
    override suspend fun refresh(tokens: EnzonaTokens) = api.refresh(tokens)
    override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) = api.recordDevice(tokens, fields)
    override suspend fun account(tokens: EnzonaTokens) = api.account(tokens)
}

/** No original exception/cause, URL, code or response body crosses into presentation. */
class EnzonaSessionUnavailableException internal constructor(val failure: EnzonaSessionFailure) :
    IllegalStateException("La sesión de ENZONA no está disponible")

/**
 * Process-wide ENZONA session: WebView login with our own `state`, one shared refresh, encrypted storage
 * and local sign-out. It stays usable while the UI is locked so the background credit check can read
 * history; money still needs the UI's biometric approval per payment. No remote SSO revocation is claimed.
 */
class EnzonaSession internal constructor(
    private val persistence: TokenPersistence,
    private val network: EnzonaSessionNetwork,
    private val deviceInfo: () -> Map<String, String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    private val closeOwnedNetwork: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Receives the account read during login, after the session is committed. Failures are ignored. */
    private val onAccount: suspend (owner: String, account: EnzonaFetched<EnzonaAccount>) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val storageMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(EnzonaSessionState())
    val state: StateFlow<EnzonaSessionState> = mutableState.asStateFlow()
    private var tokens: EnzonaTokens? = null
    private var attempt: EnzonaAuthContract.LoginAttempt? = null
    private var loginJob: Job? = null
    private var refreshFlight: Deferred<EnzonaTokens>? = null
    private var generation = 0L
    /** Bumped whenever the stored session is replaced or erased; a slower restore must not undo it. */
    private var epoch = 0L
    private var restored = false
    private var closed = false
    private var disconnecting = false

    /** Reads the stored session once (even if expired). Never performs a network request. */
    suspend fun restore() {
        if (synchronized(lock) { closed || restored }) return
        withContext(ioDispatcher) {
            storageMutex.withLock {
                val expected = synchronized(lock) {
                    if (closed || restored) return@withLock
                    epoch
                }
                var failure: EnzonaSessionFailure? = null
                val saved = try { persistence.load() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failure = EnzonaSessionFailure.RESTORE_FAILED; null }
                synchronized(lock) {
                    restored = true
                    // A login, refresh or sign-out that completed meanwhile already defines the session.
                    mutableState.value = if (!closed && epoch == expected && tokens == null) {
                        tokens = saved
                        mutableState.value.copy(ownerSubject = saved?.accountSubject(), restored = true,
                            failure = failure ?: mutableState.value.failure)
                    } else mutableState.value.copy(restored = true)
                }
            }
        }
    }

    fun beginLogin(): Unit = synchronized(lock) {
        if (closed || disconnecting) return
        invalidateWork()
        attempt = EnzonaAuthContract.newAttempt()
        mutableState.value = mutableState.value.copy(authorizationUrl = attempt!!.authorizationUri.toString(),
            busy = false, failure = null)
    }

    fun cancelLogin(): Unit = synchronized(lock) {
        if (closed || disconnecting) return
        invalidateWork()
        mutableState.value = mutableState.value.copy(authorizationUrl = null, busy = false)
    }

    /** The WebView supplies main-frame callbacks only; core additionally verifies exact URI and state. */
    fun acceptCallback(value: String, isMainFrame: Boolean = true): Boolean = synchronized(lock) {
        if (closed || !isMainFrame) return false
        val pending = attempt ?: return false
        val exchange = try { pending.consumeCallback(value) }
        catch (failure: EnzonaAuthContract.CallbackException) {
            if (failure.reason == EnzonaAuthContract.CallbackFailure.AUTHORIZATION_REJECTED) {
                attempt = null
                mutableState.value = mutableState.value.copy(authorizationUrl = null)
            }
            mutableState.value = mutableState.value.copy(failure = EnzonaSessionFailure.CALLBACK_REJECTED)
            return false
        }
        attempt = null
        val expected = generation
        // Keep the login screen mounted until bootstrap finishes so its back action can cancel requests.
        mutableState.value = mutableState.value.copy(busy = true, failure = null)
        loginJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val candidate = network.exchange(exchange)
                ensureCurrent(expected)
                val subject = candidate.accountSubject()
                try {
                    network.recordDevice(candidate, deviceInfo())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // APK setAuthenticationInfo discards its result and continues to getAccount.
                    // This does not prove registration is optional on the server; never log metadata.
                }
                ensureCurrent(expected)
                val account = network.account(candidate)
                ensureCurrent(expected)
                // The authenticated request uses the literal sub. Account.username is descriptive;
                // its representation/equivalence to sub (including tenant suffixes) is not proven.
                saveAndCommit(expected, candidate) {
                    // An old account's refresh cannot clear/replace the newly validated account.
                    generation++
                    epoch++
                    val oldRefresh = refreshFlight
                    refreshFlight = null
                    loginJob = null
                    oldRefresh?.cancel()
                    restored = true
                    mutableState.value = EnzonaSessionState(ownerSubject = subject, restored = true)
                }
                try { onAccount(subject, account) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Display data only; the next refresh reads it again. */ }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(lock) {
                    if (isCurrent(expected)) mutableState.value = mutableState.value.copy(
                        authorizationUrl = null, failure = failureFor(failure))
                }
            } finally {
                synchronized(lock) {
                    if (isCurrent(expected)) {
                        loginJob = null
                        mutableState.value = mutableState.value.copy(busy = refreshFlight != null)
                    }
                }
            }
        }
        loginJob!!.start()
        true
    }

    /** Never retries a request: callers decide how to use tokens, and payments are never resubmitted. */
    suspend fun usableTokens(): EnzonaTokens {
        currentCoroutineContext().ensureActive()
        restore()
        val expected: Long
        val flight: Deferred<EnzonaTokens>
        synchronized(lock) {
            if (closed) throw EnzonaSessionUnavailableException(EnzonaSessionFailure.UNAVAILABLE)
            val previous = tokens ?: throw EnzonaSessionUnavailableException(EnzonaSessionFailure.SIGN_IN_AGAIN)
            if (!previous.needsRefresh(nowEpochSeconds())) return previous
            if (previous.refreshToken == null) {
                mutableState.value = mutableState.value.copy(failure = EnzonaSessionFailure.SIGN_IN_AGAIN)
                throw EnzonaSessionUnavailableException(EnzonaSessionFailure.SIGN_IN_AGAIN)
            }
            expected = generation
            flight = refreshFlight ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val renewed = network.refresh(previous)
                    ensureCurrent(expected)
                    require(renewed.accountSubject() == previous.accountSubject())
                    saveAndCommit(expected, renewed) {
                        mutableState.value = mutableState.value.copy(failure = null)
                    }
                    renewed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if ((failure as? EnzonaApiException)?.oauthFailure == EnzonaOAuthFailure.INVALID_GRANT) {
                        clearInvalidSession(expected)
                    }
                    synchronized(lock) {
                        checkCurrent(expected)
                        val reason = failureFor(failure)
                        mutableState.value = mutableState.value.copy(failure = reason)
                        throw EnzonaSessionUnavailableException(reason)
                    }
                } finally {
                    synchronized(lock) {
                        if (isCurrent(expected)) {
                            refreshFlight = null
                            mutableState.value = mutableState.value.copy(busy = loginJob != null)
                        }
                    }
                }
            }.also {
                refreshFlight = it
                mutableState.value = mutableState.value.copy(busy = true)
            }
            flight.start()
        }
        // Cancelling a reader must not cancel the shared refresh owned by the session scope.
        val usable = flight.await()
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkCurrent(expected) }
        return usable
    }

    /**
     * After an API 401 "Invalid Credentials", forces a refresh on the next [usableTokens]. Memory only,
     * and only if [rejected] is still current. The rejected request itself is never repeated here.
     */
    fun expire(rejected: EnzonaTokens): Unit = synchronized(lock) {
        val current = tokens ?: return
        if (current.accessToken == rejected.accessToken) {
            tokens = EnzonaTokens(current.accessToken, current.refreshToken, current.idToken, 0)
        }
    }

    /** Local sign-out. Returns false on a deletion failure; never claims deletion succeeded in that case. */
    suspend fun signOut(): Boolean {
        synchronized(lock) {
            if (closed || disconnecting) return false
            invalidateWork()
            disconnecting = true
            epoch++
            tokens = null
            mutableState.value = EnzonaSessionState(restored = true, busy = true)
        }
        // Once sign-out is requested, caller cancellation/close cannot skip the disk deletion.
        return withContext(NonCancellable + ioDispatcher) {
            val cleared = try { storageMutex.withLock { persistence.clear() }; true }
            catch (_: Exception) { false }
            synchronized(lock) {
                disconnecting = false
                restored = true
                if (!closed) mutableState.value = EnzonaSessionState(restored = true,
                    failure = if (cleared) null else EnzonaSessionFailure.LOCAL_CLEAR_FAILED)
            }
            cleared
        }
    }

    fun clearFailure(): Unit = synchronized(lock) {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        invalidateWork()
        tokens = null
        mutableState.value = EnzonaSessionState()
        scope.cancel()
        closeOwnedNetwork()
    }

    /** No disk I/O under the state monitor. A save already in progress is rolled back on cancel;
     * sign-out's clear waits for this transaction, so late writes cannot outlive a completed sign-out. */
    private suspend fun saveAndCommit(expected: Long, candidate: EnzonaTokens, commit: () -> Unit) {
        ensureCurrent(expected)
        withContext(NonCancellable + ioDispatcher) {
            storageMutex.withLock {
                val previous = synchronized(lock) { checkCurrent(expected); tokens }
                persistence.save(candidate)
                val accepted = synchronized(lock) {
                    if (isCurrent(expected)) {
                        tokens = candidate
                        commit()
                        true
                    } else false
                }
                if (!accepted) {
                    // Preserve the last committed account on cancel/close. An explicit sign-out
                    // has already queued its clear behind this mutex and will erase it next.
                    if (previous == null) persistence.clear() else persistence.save(previous)
                    throw CancellationException("La operación de ENZONA se canceló")
                }
            }
        }
    }

    private suspend fun clearInvalidSession(expected: Long) = withContext(NonCancellable + ioDispatcher) {
        storageMutex.withLock {
            synchronized(lock) {
                checkCurrent(expected)
                epoch++
                tokens = null
                mutableState.value = mutableState.value.copy(ownerSubject = null)
            }
            try { persistence.clear() } catch (_: Exception) {
                synchronized(lock) {
                    if (isCurrent(expected)) mutableState.value = mutableState.value.copy(failure = EnzonaSessionFailure.LOCAL_CLEAR_FAILED)
                }
                throw EnzonaSessionUnavailableException(EnzonaSessionFailure.LOCAL_CLEAR_FAILED)
            }
        }
    }

    private fun invalidateWork() {
        generation++
        attempt = null
        val login = loginJob
        val refresh = refreshFlight
        loginJob = null
        refreshFlight = null
        login?.cancel()
        refresh?.cancel()
    }

    private fun isCurrent(expected: Long) = !closed && generation == expected
    private fun checkCurrent(expected: Long) {
        if (!isCurrent(expected)) throw CancellationException("La operación de ENZONA se canceló")
    }
    private suspend fun ensureCurrent(expected: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkCurrent(expected) }
    }

    private fun failureFor(failure: Exception): EnzonaSessionFailure = when ((failure as? EnzonaApiException)?.oauthFailure) {
        EnzonaOAuthFailure.INVALID_GRANT -> EnzonaSessionFailure.SIGN_IN_AGAIN
        EnzonaOAuthFailure.INVALID_CLIENT, EnzonaOAuthFailure.UNAUTHORIZED_CLIENT -> EnzonaSessionFailure.CLIENT_REJECTED
        else -> EnzonaSessionFailure.UNAVAILABLE
    }
}
