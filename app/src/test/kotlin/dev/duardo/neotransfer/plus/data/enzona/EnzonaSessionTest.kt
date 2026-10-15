package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnzonaSessionTest {
    private class MemoryTokens(var value: EnzonaTokens? = null) : TokenPersistence {
        var loads = 0
        var saves = 0
        var clears = 0
        override fun load(): EnzonaTokens? { loads++; return value }
        override fun save(tokens: EnzonaTokens) { saves++; value = tokens }
        override fun clear() { clears++; value = null }
    }

    private class FakeNetwork : EnzonaSessionNetwork {
        val calls = mutableListOf<String>()
        var exchange: suspend () -> EnzonaTokens = { token("new") }
        var refresh: suspend (EnzonaTokens) -> EnzonaTokens = { token("renewed", it.accountSubject()) }
        var record: suspend () -> Unit = {}
        var account: suspend (EnzonaTokens) -> EnzonaFetched<EnzonaAccount> = { account("synthetic") }
        override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens { calls += "exchange"; return this.exchange() }
        override suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens { calls += "refresh"; return refresh.invoke(tokens) }
        override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) { calls += "device"; record() }
        override suspend fun account(tokens: EnzonaTokens): EnzonaFetched<EnzonaAccount> { calls += "account"; return account.invoke(tokens) }
    }

    private fun session(store: TokenPersistence, network: FakeNetwork, close: () -> Unit = {},
        onAccount: suspend (String, EnzonaFetched<EnzonaAccount>) -> Unit = { _, _ -> }) =
        EnzonaSession(store, network, { mapOf("accessType" to "MOBILE") }, Dispatchers.Unconfined, { 1000 }, close,
            ioDispatcher = Dispatchers.Unconfined, onAccount = onAccount)

    @Test fun expiredStoredSessionRestoresWithoutAnyServerRequest() = runBlocking {
        val cached = token("old", expiry = 900)
        val store = MemoryTokens(cached)
        val network = FakeNetwork()
        session(store, network).use { manager ->
            manager.restore()
            manager.restore()
            assertTrue(manager.state.value.signedIn)
            assertTrue(manager.state.value.restored)
            assertEquals(cached.accountSubject(), manager.state.value.ownerSubject)
            assertEquals(1, store.loads)
            assertTrue(network.calls.isEmpty())
            assertEquals(0, store.saves)
        }
    }

    @Test fun backgroundWorkRestoresLazilyAndRefreshesAnExpiredSessionOnce() = runBlocking {
        val store = MemoryTokens(token("expired", expiry = 900))
        val network = FakeNetwork()
        session(store, network).use { manager ->
            assertEquals("renewed", manager.usableTokens().accessToken)
            assertEquals("renewed", manager.usableTokens().accessToken)
            assertEquals(1, store.loads)
            assertEquals(listOf("refresh"), network.calls)
            assertEquals("renewed", store.value!!.accessToken)
        }
    }

    @Test fun corruptStoredSessionIsReportedButNotErased() = runBlocking {
        val store = object : TokenPersistence {
            var clears = 0
            override fun load(): EnzonaTokens? = throw IllegalArgumentException("PRIVATE_SYNTHETIC_MARKER")
            override fun save(tokens: EnzonaTokens) = Unit
            override fun clear() { clears++ }
        }
        session(store, FakeNetwork()).use { manager ->
            manager.restore()
            assertFalse(manager.state.value.signedIn)
            assertTrue(manager.state.value.restored)
            assertEquals(EnzonaSessionFailure.RESTORE_FAILED, manager.state.value.failure)
            assertEquals(EnzonaSessionFailure.SIGN_IN_AGAIN,
                assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }.failure)
            assertEquals(0, store.clears)
        }
    }

    @Test fun restoreInFlightNeverUndoesASignOutOrALoginThatFollows() = runBlocking {
        for (action in listOf("signOut", "login")) withTimeout(5000) {
            val stale = token("stale", "other@carbon.super")
            val enteredLoad = CompletableDeferred<Unit>()
            val releaseLoad = CountDownLatch(1)
            val store = object : TokenPersistence {
                override fun load(): EnzonaTokens { enteredLoad.complete(Unit); check(releaseLoad.await(5, TimeUnit.SECONDS)); return stale }
                override fun save(tokens: EnzonaTokens) = Unit
                override fun clear() = Unit
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSession(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined, { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        val restoring = async(start = CoroutineStart.UNDISPATCHED) { manager.restore() }
                        enteredLoad.await()
                        val acted = async(start = CoroutineStart.UNDISPATCHED) {
                            if (action == "signOut") manager.signOut() else {
                                manager.beginLogin()
                                manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
                            }
                        }
                        releaseLoad.countDown()
                        restoring.await()
                        acted.await()
                        while (manager.state.value.busy) yield()
                        if (action == "signOut") {
                            assertFalse(manager.state.value.signedIn)
                            assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
                        } else {
                            assertEquals("synthetic@carbon.super", manager.state.value.ownerSubject)
                            assertEquals("new", manager.usableTokens().accessToken)
                        }
                    } finally { releaseLoad.countDown() }
                }
            }
        }
    }

    @Test fun logoutDuringExchangeRejectsEvenAnUncooperativeLateResult() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("old"))
            val network = FakeNetwork()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            network.exchange = { withContext(NonCancellable) { entered.complete(Unit); release.await(); token("new", "other@carbon.super") } }
            session(store, network).use { manager ->
                manager.restore()
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.state.value.authorizationUrl!!)))
                entered.await()
                assertTrue(manager.signOut())
                release.complete(Unit)
                yield()
                assertNull(store.value)
                assertEquals(0, store.saves)
                assertFalse(manager.state.value.signedIn)
                assertEquals(listOf("exchange"), network.calls)
            }
        }
    }

    @Test fun twoReadersShareOneRefreshAndItsRotatedTokens() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            val renewed = token("renewed")
            network.refresh = { release.await(); renewed }
            session(store, network).use { manager ->
                manager.restore()
                val first = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                assertEquals(listOf("refresh"), network.calls)
                assertTrue(manager.state.value.busy)
                release.complete(Unit)
                assertSame(renewed, first.await())
                assertSame(renewed, second.await())
                assertSame(renewed, store.value)
                assertEquals(1, store.saves)
                assertFalse(manager.state.value.busy)
                assertSame(renewed, manager.usableTokens())
                assertEquals(listOf("refresh"), network.calls)
            }
        }
    }

    @Test fun cancellingOneReaderDoesNotCancelRefreshForAnother() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            network.refresh = { release.await(); token("renewed") }
            session(store, network).use { manager ->
                manager.restore()
                val first = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                first.cancel()
                release.complete(Unit)
                assertEquals("renewed", second.await().accessToken)
                assertEquals(1, store.saves)
                assertEquals(listOf("refresh"), network.calls)
            }
        }
    }

    @Test fun logoutDuringRefreshPreventsResaving() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            network.refresh = { withContext(NonCancellable) { release.await(); token("renewed") } }
            session(store, network).use { manager ->
                manager.restore()
                val reader = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                manager.signOut()
                release.complete(Unit)
                assertFailsWith<CancellationException> { reader.await() }
                assertNull(store.value)
                assertEquals(0, store.saves)
                assertNull(manager.state.value.ownerSubject)
                assertFalse(manager.state.value.busy)
            }
        }
    }

    @Test fun timeoutKeepsSavedSessionAndDoesNotRetry() = runBlocking {
        val old = token("expired", expiry = 900)
        val store = MemoryTokens(old)
        val transportFailure = SocketTimeoutException("PRIVATE_BODY")
        val network = FakeNetwork().apply { refresh = { throw transportFailure } }
        fun assertSanitizedChain(error: Throwable) {
            val visited = mutableSetOf<Throwable>()
            var cause: Throwable? = error
            while (cause != null) {
                assertTrue(visited.add(cause), "Cyclic exception cause chain")
                assertTrue(cause is EnzonaSessionUnavailableException)
                assertEquals(EnzonaSessionFailure.UNAVAILABLE, cause.failure)
                assertFalse(cause === transportFailure)
                assertFalse(cause.toString().contains("PRIVATE_BODY"))
                assertTrue(cause.suppressed.isEmpty())
                cause = cause.cause
            }
        }
        session(store, network).use { manager ->
            manager.restore()
            // Coroutine stack recovery can copy this sanitized exception and chain the original.
            assertSanitizedChain(assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() })
            assertSame(old, store.value)
            assertTrue(manager.state.value.signedIn)
            assertEquals(EnzonaSessionFailure.UNAVAILABLE, manager.state.value.failure)
            assertEquals(0, store.clears)
            assertEquals(0, store.saves)
            assertEquals(listOf("refresh"), network.calls)
        }
    }

    @Test fun invalidGrantRequiresLoginButRejectedClientKeepsTokensForDiagnosis() = runBlocking {
        for (failure in listOf(EnzonaOAuthFailure.INVALID_GRANT, EnzonaOAuthFailure.INVALID_CLIENT, EnzonaOAuthFailure.UNAUTHORIZED_CLIENT)) {
            val old = token("expired", expiry = 900)
            val store = MemoryTokens(old)
            val network = FakeNetwork().apply { refresh = { throw EnzonaApiException(400, failure) } }
            session(store, network).use { manager ->
                manager.restore()
                val error = assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
                if (failure == EnzonaOAuthFailure.INVALID_GRANT) {
                    assertNull(store.value)
                    assertFalse(manager.state.value.signedIn)
                    assertEquals(EnzonaSessionFailure.SIGN_IN_AGAIN, error.failure)
                    assertEquals(1, store.clears)
                } else {
                    assertSame(old, store.value)
                    assertTrue(manager.state.value.signedIn)
                    assertEquals(EnzonaSessionFailure.CLIENT_REJECTED, error.failure)
                    assertEquals(0, store.clears)
                }
                assertEquals(listOf("refresh"), network.calls)
            }
        }
    }

    @Test fun loginFailuresKeepTheirMeaningForTheUser() = runBlocking {
        val cases = listOf<Pair<Exception, EnzonaSessionFailure>>(
            EnzonaApiException(400, EnzonaOAuthFailure.ACCESS_DENIED) to EnzonaSessionFailure.ACCESS_DENIED,
            EnzonaApiException(503, EnzonaOAuthFailure.TEMPORARILY_UNAVAILABLE) to EnzonaSessionFailure.SERVER_ERROR,
            EnzonaApiException(500, EnzonaOAuthFailure.UNKNOWN) to EnzonaSessionFailure.SERVER_ERROR,
            EnzonaApiException(400, EnzonaOAuthFailure.UNAUTHORIZED_CLIENT) to EnzonaSessionFailure.CLIENT_REJECTED,
            IOException("PRIVATE") to EnzonaSessionFailure.UNAVAILABLE,
        )
        for ((failure, expected) in cases) {
            val network = FakeNetwork().apply { exchange = { throw failure } }
            session(MemoryTokens(), network).use { manager ->
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.state.value.authorizationUrl!!)))
                assertEquals(expected, manager.state.value.failure)
                assertFalse(manager.state.value.signedIn)
                assertNull(manager.state.value.authorizationUrl)
            }
        }
        // ENZONA answering the account read with an HTTP error is ENZONA's problem, not the connection's.
        val network = FakeNetwork().apply { account = { throw EnzonaApiException(500) } }
        session(MemoryTokens(), network).use { manager ->
            manager.beginLogin()
            manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
            assertEquals(EnzonaSessionFailure.SERVER_ERROR, manager.state.value.failure)
        }
    }

    @Test fun expireForcesOneRefreshOnlyForTheRejectedToken() = runBlocking {
        val store = MemoryTokens(token("current"))
        val network = FakeNetwork()
        session(store, network).use { manager ->
            val current = manager.usableTokens()
            manager.expire(token("some-other-token"))
            assertSame(current, manager.usableTokens())
            manager.expire(current)
            assertEquals("renewed", manager.usableTokens().accessToken)
            assertEquals(listOf("refresh"), network.calls)
            manager.expire(current) // Stale rejection after the renewal changes nothing.
            assertEquals("renewed", manager.usableTokens().accessToken)
            assertEquals(listOf("refresh"), network.calls)
        }
    }

    @Test fun missingRefreshTokenMeansSignInAgainWithoutNetwork() = runBlocking {
        val store = MemoryTokens(EnzonaTokens("A", null, token("x").idToken, 900))
        val network = FakeNetwork()
        session(store, network).use { manager ->
            assertEquals(EnzonaSessionFailure.SIGN_IN_AGAIN,
                assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }.failure)
            assertTrue(network.calls.isEmpty())
        }
    }

    @Test fun cancelRejectsOldCallbackWithoutConsumingNewAttempt() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork()
        session(store, network).use { manager ->
            manager.beginLogin()
            val oldCallback = callback(manager.state.value.authorizationUrl!!)
            manager.cancelLogin()
            assertFalse(manager.acceptCallback(oldCallback))
            assertFalse(manager.state.value.signedIn)
            manager.beginLogin()
            val current = callback(manager.state.value.authorizationUrl!!)
            assertFalse(manager.acceptCallback(oldCallback))
            assertEquals(EnzonaSessionFailure.CALLBACK_REJECTED, manager.state.value.failure)
            assertTrue(network.calls.isEmpty())
            assertTrue(manager.acceptCallback(current))
            assertFalse(manager.acceptCallback(current))
            assertTrue(manager.state.value.signedIn)
            assertNull(manager.state.value.failure)
            assertNull(manager.state.value.authorizationUrl)
            assertEquals(listOf("exchange", "device", "account"), network.calls)
            assertEquals(1, store.saves)
        }
    }

    @Test fun cancellationDuringBootstrapPreservesPreviousOwner() = runBlocking {
        withTimeout(5000) {
            val old = token("old")
            val store = MemoryTokens(old)
            val network = FakeNetwork()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            network.exchange = { token("new", "other@carbon.super") }
            network.account = { withContext(NonCancellable) { entered.complete(Unit); release.await(); account("other") } }
            session(store, network).use { manager ->
                manager.restore()
                manager.beginLogin()
                manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
                entered.await()
                manager.cancelLogin()
                release.complete(Unit)
                yield()
                assertSame(old, store.value)
                assertEquals(old.accountSubject(), manager.state.value.ownerSubject)
                assertEquals(0, store.saves)
                assertNull(manager.state.value.authorizationUrl)
                assertFalse(manager.state.value.busy)
            }
        }
    }

    @Test fun failedNewLoginNeverAttributesOldTokensToNewOwner() = runBlocking {
        val old = token("old")
        val store = MemoryTokens(old)
        val network = FakeNetwork().apply {
            exchange = { token("new", "other@carbon.super") }
            account = { throw IOException("PRIVATE_ACCOUNT_BODY") }
        }
        session(store, network).use { manager ->
            manager.restore()
            manager.beginLogin()
            manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
            assertSame(old, store.value)
            assertSame(old, manager.usableTokens())
            assertEquals(old.accountSubject(), manager.state.value.ownerSubject)
            assertEquals(EnzonaSessionFailure.UNAVAILABLE, manager.state.value.failure)
            assertFalse(manager.state.value.toString().contains("PRIVATE"))
            assertEquals(0, store.saves)
        }
    }

    @Test fun registrationFailureStillGetsAccountAndSavesOnlyAfterValidation() = runBlocking {
        val store = MemoryTokens()
        val delivered = mutableListOf<String>()
        val network = FakeNetwork().apply {
            record = { throw IOException("PRIVATE_METADATA") }
            account = { assertNull(store.value); account("synthetic") }
        }
        session(store, network, onAccount = { owner, account ->
            assertEquals(1, store.saves) // Delivered only after the session is committed.
            delivered += "$owner:${account.value.username}"
        }).use { manager ->
            manager.beginLogin()
            manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
            assertEquals(listOf("exchange", "device", "account"), network.calls)
            assertEquals(1, store.saves)
            assertTrue(manager.state.value.signedIn)
            assertNull(manager.state.value.failure)
            assertEquals(listOf("synthetic@carbon.super:synthetic"), delivered)
        }
    }

    @Test fun accountCacheFailureDoesNotUndoACommittedLogin() = runBlocking {
        val store = MemoryTokens()
        session(store, FakeNetwork(), onAccount = { _, _ -> throw IllegalStateException("disk full") }).use { manager ->
            manager.beginLogin()
            assertTrue(manager.acceptCallback(callback(manager.state.value.authorizationUrl!!)))
            assertTrue(manager.state.value.signedIn)
            assertNull(manager.state.value.failure)
            assertEquals("new", store.value?.accessToken)
        }
    }

    @Test fun descriptiveAccountUsernameDoesNotOverrideLiteralSubject() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork().apply { account = { account("different-descriptive-name") } }
        session(store, network).use { manager ->
            manager.beginLogin()
            manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
            assertEquals("synthetic@carbon.super", manager.state.value.ownerSubject)
            assertEquals("synthetic@carbon.super", store.value!!.accountSubject())
        }
    }

    @Test fun differentRefreshSubjectIsRejected() = runBlocking {
        val old = token("old", expiry = 900)
        val store = MemoryTokens(old)
        val network = FakeNetwork().apply { refresh = { token("wrong", "wrong@carbon.super") } }
        session(store, network).use { manager ->
            manager.restore()
            assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
            assertSame(old, store.value)
            assertEquals(old.accountSubject(), manager.state.value.ownerSubject)
            assertEquals(0, store.saves)
        }
    }

    @Test fun oldRefreshCannotInvalidateASuccessfulNewLogin() = runBlocking {
        withTimeout(5000) {
            val old = token("old", expiry = 900)
            val store = MemoryTokens(old)
            val network = FakeNetwork()
            val exchangeRelease = CompletableDeferred<Unit>()
            val refreshRelease = CompletableDeferred<Unit>()
            network.exchange = { exchangeRelease.await(); token("new", "other@carbon.super") }
            network.refresh = { withContext(NonCancellable) {
                refreshRelease.await(); throw EnzonaApiException(400, EnzonaOAuthFailure.INVALID_GRANT)
            } }
            session(store, network).use { manager ->
                manager.restore()
                manager.beginLogin()
                manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
                val reader = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                exchangeRelease.complete(Unit)
                refreshRelease.complete(Unit)
                assertFailsWith<CancellationException> { reader.await() }
                assertEquals("new", store.value!!.accessToken)
                assertEquals("other@carbon.super", manager.state.value.ownerSubject)
                assertEquals(0, store.clears)
                assertEquals(1, store.saves)
            }
        }
    }

    @Test fun subframeAndInvalidCallbackDoNotConsumePendingAttempt() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork()
        session(store, network).use { manager ->
            manager.beginLogin()
            val valid = callback(manager.state.value.authorizationUrl!!)
            assertFalse(manager.acceptCallback(valid, isMainFrame = false))
            assertFalse(manager.acceptCallback(valid.replace("apk-callback", "apk-callback.example")))
            assertFalse(manager.acceptCallback("http://apk-callback/?code=SYNTHETIC"))
            assertTrue(network.calls.isEmpty())
            assertTrue(manager.acceptCallback(valid))
            assertEquals(1, store.saves)
        }
    }

    @Test fun refusedAuthorizationEndsTheAttempt() = runBlocking {
        val network = FakeNetwork()
        session(MemoryTokens(), network).use { manager ->
            manager.beginLogin()
            val url = manager.state.value.authorizationUrl!!
            val refused = callback(url).replace("code=SYNTHETIC_CODE", "error=access_denied")
            assertFalse(manager.acceptCallback(refused))
            assertNull(manager.state.value.authorizationUrl)
            assertFalse(manager.acceptCallback(callback(url)))
            assertTrue(network.calls.isEmpty())
        }
    }

    @Test fun closeIsIdempotentAndPreservesEncryptedSession() = runBlocking {
        val old = token("old")
        val store = MemoryTokens(old)
        var closes = 0
        val manager = session(store, FakeNetwork(), { closes++ })
        manager.restore()
        manager.beginLogin()
        val late = callback(manager.state.value.authorizationUrl!!)
        manager.close()
        manager.close()
        assertEquals(1, closes)
        assertSame(old, store.value)
        assertFalse(manager.acceptCallback(late))
        assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
        assertEquals(0, store.clears)
    }

    @Test fun stateToStringRedactsAllPrivateFields() {
        val state = EnzonaSessionState("PRIVATE_OWNER", true, "PRIVATE_URL", true, EnzonaSessionFailure.UNAVAILABLE)
        assertFalse(state.toString().contains("PRIVATE"))
        assertTrue(state.toString().contains("redacted"))
    }

    @Test fun allPersistenceRunsOffCallerThreadAndLogoutWaitsForAnInProgressSave() = runBlocking {
        withTimeout(5000) {
            val callerThread = Thread.currentThread()
            val backing = MemoryTokens(token("old"))
            val enteredSave = CompletableDeferred<Unit>()
            val releaseSave = CountDownLatch(1)
            val store = object : TokenPersistence {
                override fun load(): EnzonaTokens? { assertTrue(Thread.currentThread() !== callerThread); return backing.load() }
                override fun save(tokens: EnzonaTokens) {
                    assertTrue(Thread.currentThread() !== callerThread)
                    if (tokens.accessToken == "new") { enteredSave.complete(Unit); check(releaseSave.await(5, TimeUnit.SECONDS)) }
                    backing.save(tokens)
                }
                override fun clear() { assertTrue(Thread.currentThread() !== callerThread); backing.clear() }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSession(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined, { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        manager.restore()
                        manager.beginLogin()
                        manager.acceptCallback(callback(manager.state.value.authorizationUrl!!))
                        enteredSave.await()
                        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.signOut() }
                        // These immediate actions must not block on the worker's disk operation.
                        assertFalse(manager.state.value.signedIn)
                        assertFalse(logout.isCompleted)
                        manager.beginLogin()
                        assertNull(manager.state.value.authorizationUrl)
                        releaseSave.countDown()
                        assertTrue(logout.await())
                        assertNull(backing.value)
                        assertFalse(manager.state.value.busy)
                    } finally { releaseSave.countDown() }
                }
            }
        }
    }

    @Test fun logoutDeletionContinuesWhenItsCallerIsCancelled() = runBlocking {
        withTimeout(5000) {
            val backing = MemoryTokens(token("old"))
            val enteredClear = CompletableDeferred<Unit>()
            val releaseClear = CountDownLatch(1)
            val store = object : TokenPersistence by backing {
                override fun clear() { enteredClear.complete(Unit); check(releaseClear.await(5, TimeUnit.SECONDS)); backing.clear() }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSession(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined, { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        manager.restore()
                        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.signOut() }
                        enteredClear.await()
                        logout.cancel()
                        releaseClear.countDown()
                        logout.join()
                        assertNull(backing.value)
                        assertFalse(manager.state.value.signedIn)
                        assertFalse(manager.state.value.busy)
                    } finally { releaseClear.countDown() }
                }
            }
        }
    }

    private companion object {
        fun token(access: String, subject: String = "synthetic@carbon.super", expiry: Long = 5000): EnzonaTokens =
            syntheticTokens(subject, access, "refresh-$access", expiry)

        fun account(username: String): EnzonaFetched<EnzonaAccount> {
            val json = """{"account":{"username":"$username"}}"""
            return EnzonaFetched(EnzonaReadJson.account(json), json, 1)
        }

        fun callback(authorizationUrl: String): String {
            val state = URI(authorizationUrl).rawQuery.split('&').map { it.split('=', limit = 2) }.single { it[0] == "state" }[1]
            return "http://apk-callback/?code=SYNTHETIC_CODE&state=${URLDecoder.decode(state, "UTF-8")}"
        }
    }
}
