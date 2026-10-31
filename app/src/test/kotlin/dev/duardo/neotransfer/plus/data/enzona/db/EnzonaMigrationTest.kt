package dev.duardo.neotransfer.plus.data.enzona.db

import android.app.Application
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentFault
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHistoryMatch
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliationChange
import java.io.File
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The database 0.2.0 left on the phone, opened by this version: every attempt stays, and the new columns work in SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EnzonaMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"
    private val owner = "owner@carbon.super"
    private val unknown = "00000000-0000-4000-8000-0000000000a1"
    private val confirmed = "00000000-0000-4000-8000-0000000000a2"
    private val flying = "00000000-0000-4000-8000-0000000000a3"

    @After fun delete() { context.deleteDatabase(name) }

    /** Version 1 exactly as Room created it, from its exported schema, with three synthetic attempts. */
    private fun createVersion1() {
        val schema = Json.parseToJsonElement(File("schemas/dev.duardo.neotransfer.plus.data.enzona.db.EnzonaDatabase/1.json").readText())
            .jsonObject.getValue("database").jsonObject
        context.openOrCreateDatabase(name, 0, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                val statements = listOf(entity.getValue("createSql")) + entity["indices"]?.jsonArray?.map { it.jsonObject.getValue("createSql") }.orEmpty()
                statements.forEach { db.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            for ((id, state, remote) in listOf(Triple(unknown, "UNKNOWN", null), Triple(confirmed, "CONFIRMED", "remote-1"), Triple(flying, "SUBMITTING", null))) {
                db.execSQL("""INSERT INTO intent_journal (state, remoteId, id, ownerSubject, cardUuid, type, amount, currency, description,
                    preparedAt, destination_value) VALUES (?, ?, ?, ?, 'card', 'CARD_TRANSFER', '300.00', 'CUP', 'Concepto', 1000, '9200000000000099')""",
                    arrayOf(state, remote, id, owner))
            }
            db.version = 1
        }
    }

    @Test fun attemptsSurviveTheMigrationAndTheNewColumnsHoldWhatTheyShould() = runBlocking(Dispatchers.IO) {
        createVersion1()
        val database = EnzonaDatabase.open(context, name)
        try {
            val store = RoomEnzonaStore(database)
            val kept = store.intent(owner, unknown)!!
            assertEquals(EnzonaIntentState.UNKNOWN, kept.state)
            assertEquals("300.00", kept.intent.amount)
            assertEquals("Concepto", kept.intent.description)
            assertEquals("9200000000000099", kept.intent.destination.value)
            assertNull(kept.httpStatus)
            assertNull(kept.faultText)
            assertNull(kept.notFoundAt)
            assertEquals("remote-1", store.intent(owner, confirmed)!!.remoteId)
            assertEquals(EnzonaIntentState.CONFIRMED, store.intent(owner, confirmed)!!.state)

            // At start, a flight of an earlier process is unconfirmed; with no reply kept it may still prove undelivered.
            assertEquals(1, store.abandonFlights())
            assertEquals(EnzonaIntentState.UNKNOWN, store.intent(owner, flying)!!.state)
            assertTrue(store.markNotDelivered(owner, flying))
            assertEquals(EnzonaIntentState.NOT_DELIVERED, store.intent(owner, flying)!!.state)

            // A reply that proved nothing: its uuid, status and fault are kept, and it can no longer be "undelivered".
            val fault = EnzonaPaymentFault("0507", "Contraseña de pago incorrecta", listOf("cod", "description"))
            assertFalse(store.markUnknown(owner, unknown, EnzonaPaymentEvidence(EnzonaIntentState.UNKNOWN, 400, null, "observed", null, 5, fault = fault)))
            val replied = store.intent(owner, unknown)!!
            assertEquals("observed", replied.remoteId)
            assertEquals(400, replied.httpStatus)
            assertEquals("0507", replied.faultCode)
            assertEquals("Contraseña de pago incorrecta", replied.faultText)
            assertFalse(store.markNotDelivered(owner, unknown))

            // Missing from the history, then a row shows up: the miss is undone.
            store.reconcile(owner, listOf(EnzonaReconciliationChange.NotFound(unknown, 1000 + EnzonaReconciliation.NOT_DONE_AFTER)))
            assertEquals(1000 + EnzonaReconciliation.NOT_DONE_AFTER, store.intent(owner, unknown)!!.notFoundAt)
            store.reconcile(owner, listOf(EnzonaReconciliationChange.Seen(unknown)))
            assertNull(store.intent(owner, unknown)!!.notFoundAt)

            // Only its own uuid settles it.
            store.reconcile(owner, listOf(EnzonaReconciliationChange.Settle(unknown, EnzonaIntentState.CONFIRMED, "other", EnzonaHistoryMatch.UUID)))
            assertEquals(EnzonaIntentState.UNKNOWN, store.intent(owner, unknown)!!.state)
            store.reconcile(owner, listOf(EnzonaReconciliationChange.Settle(unknown, EnzonaIntentState.CONFIRMED, "observed", EnzonaHistoryMatch.UUID)))
            val settled = store.intent(owner, unknown)!!
            assertEquals(EnzonaIntentState.CONFIRMED, settled.state)
            assertEquals(EnzonaHistoryMatch.UUID, settled.matchedBy)
            assertEquals("Concepto", settled.intent.description)
        } finally {
            database.close()
        }
    }
}
