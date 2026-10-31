package dev.duardo.neotransfer.plus.core.contact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each rule of the merge between the two apps, and the exported row. Synthetic people and numbers. */
class ContactSyncTest {
    private val salary = SyncCard("9204069900000015", "Salario")
    private val plain = SyncCard("9204129900000023", null)
    private val other = SyncCard("9227069900000031", "Ahorro")
    private fun ana(updated: Long, cards: List<SyncCard> = listOf(salary), phones: List<String> = listOf("52222222"), deleted: Boolean = false) =
        SyncContact("uuid-ana", "Ana", phones, cards, updated, deleted)

    @Test fun anUnknownContactIsTakenUnlessItWasDeleted() {
        assertEquals(ana(5), ContactSync.merge(null, ana(5)))
        assertNull(ContactSync.merge(null, ana(5, emptyList(), emptyList(), deleted = true)))
    }

    @Test fun theNewerRemoteRecordIsTakenWithItsOwnTime() {
        val remote = ana(9, listOf(other), listOf("53333333"))
        assertEquals(remote, ContactSync.merge(ana(5), remote))
        // Deleted there later: deleted here, with that time.
        val buried = ana(9, emptyList(), emptyList(), deleted = true)
        assertEquals(buried, ContactSync.merge(ana(5), buried))
        // Saved there after being deleted here: it comes back.
        assertEquals(ana(9), ContactSync.merge(ana(5, emptyList(), emptyList(), deleted = true), ana(9)))
        assertNull(ContactSync.merge(ana(9), ana(9).copy()))
    }

    @Test fun anOlderRemoteRecordLeavesTheLocalOne() {
        assertNull(ContactSync.merge(ana(9), ana(5, listOf(other))))
        assertNull(ContactSync.merge(ana(9), ana(5, emptyList(), emptyList(), deleted = true)))
    }

    @Test fun recordsOfTheSameTimeAreJoinedLocalFirst() {
        val local = ana(0, listOf(salary, plain), listOf("52222222"))
        val remote = ana(0, listOf(other, plain.copy(label = "Casa"), salary.copy(label = "Otro")), listOf("53333333", "52222222"))
        assertEquals(
            ana(0, listOf(salary, plain.copy(label = "Casa"), other), listOf("52222222", "53333333")),
            ContactSync.merge(local, remote),
        )
        // Nothing new: nothing to write.
        assertNull(ContactSync.merge(local, ana(0, listOf(plain), emptyList())))
        // Deleted only when both are.
        val buried = ana(3, emptyList(), emptyList(), deleted = true)
        assertNull(ContactSync.merge(buried, buried.copy()))
        assertEquals(ana(3), ContactSync.merge(buried, ana(3)))
    }

    @Test fun mergeWritesOnlyWhatChanges() {
        val luis = SyncContact("phone:k-luis", "Luis", listOf("53333333"), listOf(plain), 7, false)
        assertEquals(listOf(luis), ContactSync.merge(listOf(ana(5)), listOf(ana(5), luis)))
    }

    @Test fun anExportedRowReadsBackAsItWas() {
        val contact = SyncContact("phone:k-ana", "Ana", listOf("52222222", "53333333"), listOf(salary, plain), 1_700_000_000_000, false)
        val row = ContactSync.encode(contact)
        assertEquals(listOf("phone:k-ana", "Ana", "52222222\n53333333", "9204069900000015\tSalario\n9204129900000023\t", 1_700_000_000_000L, 0), row.toList())
        assertEquals(contact, decode(row))
        val buried = SyncContact("uuid-x", "Abuela", emptyList(), emptyList(), 4, true)
        assertEquals(buried, decode(ContactSync.encode(buried)))
        // A label never breaks the row apart.
        val odd = contact.copy(cards = listOf(SyncCard(salary.pan, "Casa\tde\nmamá")))
        assertEquals("Casa de mamá", decode(ContactSync.encode(odd))!!.cards.single().label)
    }

    @Test fun malformedRowsAndValuesAreDropped() {
        assertNull(ContactSync.decode(null, "Ana", "", "", 0, false))
        assertNull(ContactSync.decode("uuid", " ", "", "", 0, false))
        assertEquals("", ContactSync.decode("uuid", null, null, null, 2, true)!!.name)
        val read = ContactSync.decode("uuid", " Ana ", "5222\n52222222\n52222222", "123\tx\n9204069900000015\t Salario \n9204069900000015", 0, false)!!
        assertEquals(SyncContact("uuid", "Ana", listOf("52222222"), listOf(salary), 0, false), read)
    }

    private fun decode(row: Array<Any>) =
        ContactSync.decode(row[0] as String, row[1] as String, row[2] as String, row[3] as String, row[4] as Long, row[5] == 1)
}
