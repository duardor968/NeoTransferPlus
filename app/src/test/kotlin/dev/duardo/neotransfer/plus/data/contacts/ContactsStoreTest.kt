package dev.duardo.neotransfer.plus.data.contacts

import android.app.Application
import dev.duardo.neotransfer.plus.core.contact.SyncCard
import dev.duardo.neotransfer.plus.core.contact.SyncContact
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the store keeps for the other app: change times, tombstones, local card ids, and the v1 database. Synthetic data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContactsStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "contacts-test.db"
    private var clock = 1_000L
    private val salary = ContactCard("c1", "9204069900000015", "Salario")
    private val ana = Contact("uuid-ana", "Ana", listOf(salary), listOf("52222222"))

    @After fun delete() { context.deleteDatabase(name) }

    private fun <T> withStore(block: suspend (ContactsStore) -> T): T = runBlocking(Dispatchers.IO) {
        val database = ContactsDatabase.open(context, name)
        try { block(ContactsStore(database) { clock }) } finally { database.close() }
    }

    @Test fun deletingLeavesAHiddenTombstoneAndSavingAgainRevivesIt() = withStore { store ->
        store.save(ana)
        assertEquals(listOf(SyncContact("uuid-ana", "Ana", listOf("52222222"), listOf(SyncCard(salary.pan, "Salario")), 1_000, false)), store.exported())
        clock = 2_000
        store.delete("uuid-ana")
        assertEquals(emptyList(), store.contacts.first())
        assertEquals(listOf(SyncContact("uuid-ana", "Ana", emptyList(), emptyList(), 2_000, true)), store.exported())
        clock = 3_000
        store.save(ana)
        assertEquals(listOf(ana), store.contacts.first())
        assertEquals(3_000, store.exported().single().updated)
    }

    @Test fun relinkingBuriesWhatItJoins() = withStore { store ->
        store.save(ana)
        clock = 2_000
        val luis = Contact("phone:k-luis", "Luis", listOf(salary), listOf("52222222"))
        store.relink(listOf(luis), listOf("uuid-ana"))
        assertEquals(listOf(luis), store.contacts.first())
        assertEquals(mapOf("uuid-ana" to true, "phone:k-luis" to false), store.exported().associate { it.id to it.deleted })
        assertTrue(store.exported().all { it.updated == 2_000L })
    }

    @Test fun mergingKeepsCardIdsForKnownNumbersAndTheRemoteTime() = withStore { store ->
        store.save(ana)
        val added = SyncCard("9227069900000031", "Ahorro")
        val remote = SyncContact("uuid-ana", "Ana María", listOf("53333333"), listOf(added, SyncCard(salary.pan, "Nómina")), 5_000, false)
        val luis = SyncContact("phone:k-luis", "Luis", emptyList(), listOf(added), 4_000, false)
        val gone = SyncContact("uuid-gone", "", emptyList(), emptyList(), 4_000, true)
        assertEquals(2, store.merge(listOf(remote, luis, gone)))
        val merged = store.contacts.first().associateBy { it.id }
        val cards = merged.getValue("uuid-ana").cards
        assertEquals(listOf(added.pan, salary.pan), cards.map { it.pan })
        assertEquals("c1", cards[1].id)
        assertEquals("Nómina", cards[1].label)
        assertTrue(cards[0].id != "c1" && merged.getValue("phone:k-luis").cards.single().id != cards[0].id)
        assertEquals(mapOf("uuid-ana" to 5_000L, "phone:k-luis" to 4_000L), store.exported().associate { it.id to it.updated })
        // The same rows again change nothing.
        assertEquals(0, store.merge(listOf(remote, luis, gone)))
    }

    @Test fun version1ContactsSurviveAsLiveAndNeverChanged() {
        val schema = Json.parseToJsonElement(File("schemas/dev.duardo.neotransfer.plus.data.contacts.ContactsDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        context.openOrCreateDatabase(name, 0, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                val statements = listOf(entity.getValue("createSql")) + entity["indices"]?.jsonArray?.map { it.jsonObject.getValue("createSql") }.orEmpty()
                statements.forEach { db.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO contacts (id, name) VALUES ('uuid-ana', 'Ana')")
            db.execSQL("INSERT INTO contact_cards (id, contactId, pan, label, position) VALUES ('c1', 'uuid-ana', '9204069900000015', 'Salario', 0)")
            db.execSQL("INSERT INTO contact_phones (id, contactId, number, position) VALUES ('uuid-ana:0', 'uuid-ana', '52222222', 0)")
            db.version = 1
        }
        withStore { store ->
            assertEquals(listOf(ana), store.contacts.first())
            assertEquals(listOf(SyncContact("uuid-ana", "Ana", listOf("52222222"), listOf(SyncCard(salary.pan, "Salario")), 0, false)), store.exported())
        }
    }
}
