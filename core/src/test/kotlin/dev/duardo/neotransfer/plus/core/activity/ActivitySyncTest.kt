package dev.duardo.neotransfer.plus.core.activity

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared activity row: what is exported reads back the same, and the window. Synthetic numbers. */
class ActivitySyncTest {
    private val transfer = SyncOperation(
        "tx:7d1c", SyncKind.CARD_TRANSFER, SyncStatus.CONFIRMED, "Juan Carlos Pérez", 1_760_000_000_000, BigDecimal("1500"), "CUP",
        SyncDirection.OUT, "9212060123456781", "9204069901447185", "phone:juan",
    )

    private fun roundTrip(operation: SyncOperation): SyncOperation? {
        val row = ActivitySync.encode(operation)
        assertEquals(ActivitySync.COLUMNS.size, row.size)
        return ActivitySync.decode(
            row[0] as String, row[1] as String, row[2] as String, row[3] as String, row[4] as Long, row[5] as String,
            row[6] as String, row[7] as String, row[8] as String, row[9] as String, row[10] as String,
        )
    }

    @Test fun anExportedRowReadsBackTheSame() {
        val read = roundTrip(transfer)!!
        assertEquals(transfer.copy(amount = BigDecimal("1500.00")), read)
        assertEquals("1500.00", ActivitySync.encode(transfer)[5])
        // No amount, last four digits only, no counterpart or contact.
        val bare = transfer.copy(id = "j:9", kind = SyncKind.INCOMING, status = SyncStatus.NOT_SENT, amount = null, direction = SyncDirection.IN,
            card = "6781", counterpart = "", contactId = "")
        assertEquals(bare, roundTrip(bare))
        assertEquals("", ActivitySync.encode(bare)[5])
    }

    @Test fun amountsArePlainAndAbsolute() {
        assertEquals("10.50", ActivitySync.encode(transfer.copy(amount = BigDecimal("-10.5")))[5])
        assertEquals("12480.125", ActivitySync.encode(transfer.copy(amount = BigDecimal("12480.125")))[5])
        assertEquals("1000.00", ActivitySync.encode(transfer.copy(amount = BigDecimal("1E+3")))[5])
    }

    @Test fun rowsThisAppCannotReadAreDropped() {
        fun decode(id: String? = "p1", kind: String? = "CARD_TRANSFER", status: String? = "CONFIRMED", direction: String? = "OUT") =
            ActivitySync.decode(id, kind, status, "Ana", 1L, "5", "CUP", direction, "", "", "")
        assertNull(decode(id = " "))
        assertNull(decode(kind = "LOTTERY"))
        assertNull(decode(status = "LOST"))
        assertNull(decode(direction = "SIDEWAYS"))
        assertEquals(SyncKind.CARD_TRANSFER, decode()?.kind)
    }

    @Test fun malformedFieldsAreLeftEmpty() {
        val read = ActivitySync.decode("p1", "SERVICE", "PENDING", null, 1L, "diez", null, "OUT", "92120601", null, null)!!
        assertNull(read.amount)
        assertEquals("", read.title)
        assertEquals("", read.card)
        assertEquals("", read.currency)
    }

    @Test fun theWindowKeepsTheLast120DaysNewestFirstAndAtMost500() {
        val now = 1_760_000_000_000
        val day = 24 * 60 * 60_000L
        val rows = listOf(transfer.copy(id = "old", at = now - 120 * day - 1), transfer.copy(id = "edge", at = now - 120 * day), transfer.copy(id = "new", at = now))
        assertEquals(listOf("new", "edge"), ActivitySync.window(rows, now).map { it.id })
        val many = (0 until 600).map { transfer.copy(id = "r$it", at = now - it * 1000L) }
        val kept = ActivitySync.window(many.shuffled(), now)
        assertEquals(500, kept.size)
        assertEquals("r0", kept.first().id)
        assertEquals("r499", kept.last().id)
    }
}
