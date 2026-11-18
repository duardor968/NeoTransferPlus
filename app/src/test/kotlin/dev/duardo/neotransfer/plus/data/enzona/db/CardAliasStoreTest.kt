package dev.duardo.neotransfer.plus.data.enzona.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardAliasStoreTest {
    @Test fun concurrentPartialWritesMergeOnlyWithinTheSameAccountAndUuid() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EnzonaDatabase::class.java).build()
        try {
            val store = RoomEnzonaStore(db)
            val cached = """{"card":[{"funding_source_uuid":"a","last4":"0007"},{"funding_source_uuid":"b","last4":"0007"}]}"""
            for (owner in listOf("one", "two")) store.cache(owner, EnzonaReadKind.CARDS, CARDS_RESOURCE, cached, 100)
            val a = EnzonaCardLink("one", "a")
            val b = a.copy(cardUuid = "b", alias = "Nómina")
            val other = a.copy(ownerSubject = "two", alias = "Ahorro")
            store.putCardLink(b)
            store.putCardLink(other)
            coroutineScope {
                launch { store.putCardLink(a.copy(alias = "Nómina")) }
                launch { store.putCardLink(a.copy(pan = "9200000000000007")) }
            }
            assertEquals(listOf(a.copy(pan = "9200000000000007", alias = "Nómina"), b), store.observeCardLinks("one").first())
            store.putCardLink(b.copy(alias = null), updateAlias = true)
            assertEquals(listOf(a.copy(pan = "9200000000000007", alias = "Nómina")), store.observeCardLinks("one").first())
            assertEquals(listOf(other), store.observeCardLinks("two").first())
            store.clearOwner("two")
            assertFailsWith<IllegalArgumentException> { store.putCardLink(other) }
            assertTrue(store.observeCardLinks("two").first().isEmpty())
        } finally {
            db.close()
        }
    }
}
