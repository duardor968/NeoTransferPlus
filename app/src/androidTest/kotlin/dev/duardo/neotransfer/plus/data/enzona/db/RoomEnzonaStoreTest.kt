package dev.duardo.neotransfer.plus.data.enzona.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.data.enzona.EnzonaDestination
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SQL invariants the JVM fakes mirror. Synthetic data only; in-memory database. */
@RunWith(AndroidJUnit4::class)
class RoomEnzonaStoreTest {
    private lateinit var database: EnzonaDatabase
    private lateinit var store: RoomEnzonaStore
    private val owner = "owner@carbon.super"
    private val other = "other@carbon.super"

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, EnzonaDatabase::class.java).build()
        store = RoomEnzonaStore(database)
    }

    @After fun close() = database.close()

    private fun intent(ownerSubject: String = owner) = EnzonaIntent(UUID.randomUUID().toString(), ownerSubject, "card",
        EnzonaIntentType.CARD_TRANSFER, EnzonaDestination("9200000000000099"), "300.00", "CUP", preparedAt = 1)

    @Test fun concurrentSubmissionsWinTheCasExactlyOnce() = runBlocking {
        val prepared = store.prepareIntent(intent()).intent
        val winners = (1..8).map { async(Dispatchers.IO) { store.tryBeginSubmission(owner, prepared.id) } }.awaitAll()
        assertEquals(1, winners.count { it })
        assertFalse(store.tryBeginSubmission(owner, prepared.id))
        assertTrue(store.markTimedOut(owner, prepared.id))
        assertFalse(store.markTimedOut(owner, prepared.id))
        assertFalse(store.tryBeginSubmission(owner, prepared.id)) // UNKNOWN never becomes sendable again
        assertTrue(store.recordResult(owner, prepared.id, EnzonaIntentState.CONFIRMED, "remote-1"))
        assertFalse(store.recordResult(owner, prepared.id, EnzonaIntentState.REJECTED, null))
        assertFalse(store.recordResult(owner, prepared.id, EnzonaIntentState.CONFIRMED, "remote-2"))
        assertEquals("remote-1", store.intent(owner, prepared.id)?.remoteId)
    }

    @Test fun preparedIntentsAreImmutableAndOwnerScoped() = runBlocking {
        val original = intent()
        store.prepareIntent(original)
        assertEquals(original, store.prepareIntent(original).intent)
        assertFailsWith<IllegalArgumentException> { store.prepareIntent(original.copy(amount = "301.00")) }
        assertNull(store.intent(other, original.id))
        assertFalse(store.tryBeginSubmission(other, original.id))
        assertFalse(store.recordResult(owner, original.id, EnzonaIntentState.CONFIRMED, null)) // still PREPARED
        assertTrue(store.observeJournal(other).first().isEmpty())
        assertFailsWith<IllegalArgumentException> { store.prepareIntent(original.copy(id = "not-a-uuid")) }
    }

    @Test fun cacheKeepsTheNewestObservationPerOwner() = runBlocking {
        store.cache(owner, EnzonaReadKind.BALANCE, "card", """{"balance":10.0000,"operation":[]}""", 100)
        store.cache(other, EnzonaReadKind.BALANCE, "card", """{"balance":20,"operation":[]}""", 100)
        store.cache(owner, EnzonaReadKind.BALANCE, "card", """{"balance":1,"operation":[]}""", 99)
        assertEquals("""{"balance":10.0000,"operation":[]}""", store.cached(owner, EnzonaReadKind.BALANCE, "card")?.json)
        assertEquals(1, store.observeCache(other, EnzonaReadKind.BALANCE).first().size)
        assertFailsWith<IllegalArgumentException> { store.cache(owner, EnzonaReadKind.CARDS, "cards", """{"fault":{}}""", 1) }
    }

    @Test fun historyBaselineQueuesOnlyNewCreditsUntilDelivered() = runBlocking {
        fun page(vararg uuids: String) = EnzonaReadJson.transactions("""{"transaction":[${uuids.joinToString(",") {
            """{"transaction_uuid":"$it","amount":"5.00","source":{"funding_source_username":"s"},"recipient":{"funding_recipient_username":"me"}}"""
        }}],"pagination":{"total":0}}""").transactions
        store.saveHistory(owner, page("a"), 100, notifyCredits = true, accountUsername = "me")
        assertTrue(store.observePendingNotices(owner).first().isEmpty())
        store.saveHistory(owner, page("a", "b"), 200, notifyCredits = true, accountUsername = "me")
        assertEquals(listOf("b"), store.observePendingNotices(owner).first().map { it.transactionUuid })
        store.markNoticesDelivered(owner, listOf("b"))
        store.saveHistory(owner, page("a", "b"), 300, notifyCredits = true, accountUsername = "me")
        assertTrue(store.observePendingNotices(owner).first().isEmpty())
        assertEquals(2, store.observeTransactions(owner).first().size)
        assertTrue(store.observeTransactions(other).first().isEmpty())
    }

    @Test fun cardLinksRequireAFullPan() = runBlocking {
        store.putCardLink(EnzonaCardLink(owner, "card", "9200000000000007", "Nómina"))
        assertEquals("Nómina", store.observeCardLinks(owner).first().single().alias)
        assertFailsWith<IllegalArgumentException> { store.putCardLink(EnzonaCardLink(owner, "card", "0007")) }
    }
}
