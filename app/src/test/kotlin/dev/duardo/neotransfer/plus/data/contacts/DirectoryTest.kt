package dev.duardo.neotransfer.plus.data.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The address book names people; the app adds their cards. Synthetic people and numbers. */
class DirectoryTest {
    private val ana = PhoneContact("k-ana", "Ana", listOf("52222222"))
    private val luis = PhoneContact("k-luis", "Luis", listOf("53333333", "64444444"))
    private val card1 = ContactCard("x1", "9204069900000015", "Salario")
    private val card2 = ContactCard("x2", "9204129900000023", null)

    @Test fun theAddressBookNamesEveryoneAndTheAppAddsTheCards() {
        val stored = listOf(Contact("phone:k-ana", "Ana vieja", listOf(card1), listOf("50000000")), Contact("x", "Abuela", listOf(card2), emptyList()))
        assertEquals(
            listOf(
                Contact("x", "Abuela", listOf(card2), emptyList()),
                Contact("phone:k-ana", "Ana", listOf(card1), listOf("52222222")),
                Contact("phone:k-luis", "Luis", emptyList(), listOf("53333333", "64444444")),
            ),
            Directory.merge(stored, listOf(luis, ana)),
        )
        // Unreadable address book: the stored copies stand in.
        assertEquals(listOf("Abuela", "Ana vieja"), Directory.merge(stored, null).map { it.name })
    }

    @Test fun namesSortTheSpanishWay() {
        val names = listOf("Óscar", "Ñico", "ana", "Nora", "Álvaro").map { PhoneContact(it, it, listOf("51111111")) }
        assertEquals(listOf("Álvaro", "ana", "Nora", "Ñico", "Óscar"), Directory.merge(emptyList(), names).map { it.name })
    }

    @Test fun idsCarryTheLookupKey() {
        assertEquals("phone:k-ana", Directory.id(ana))
        assertEquals("k-ana", Directory.key("phone:k-ana"))
        assertNull(Directory.key("8c1f0e1a-uuid"))
    }

    @Test fun aContactKeptInTheAppJoinsTheOnePersonItSharesAPhoneWith() {
        val legacy = Contact("uuid-1", "Luisito", listOf(card1), listOf("64444444"))
        val (save, delete) = Directory.link(listOf(legacy), listOf(ana, luis))
        assertEquals(listOf(Contact("phone:k-luis", "Luis", listOf(card1), luis.phones)), save)
        assertEquals(listOf("uuid-1"), delete)
    }

    @Test fun joiningKeepsTheCardsAlreadySavedForThatPerson() {
        val saved = Contact("phone:k-luis", "Luis", listOf(card2), luis.phones)
        val stale = Contact("phone:old-key", "Luis", listOf(card1, card2.copy(id = "x3")), listOf("53333333"))
        val (save, delete) = Directory.link(listOf(saved, stale), listOf(luis))
        assertEquals(listOf(saved.copy(cards = listOf(card2, card1))), save)
        assertEquals(listOf("phone:old-key"), delete)
    }

    @Test fun ambiguousOrPhonelessContactsStayInTheApp() {
        val shared = PhoneContact("k-casa", "Casa", listOf("52222222"))
        val both = Contact("a", "Ana o Casa", listOf(card1), listOf("52222222"))
        val cardOnly = Contact("b", "Abuela", listOf(card2), emptyList())
        assertEquals(emptyList<Contact>() to emptyList(), Directory.link(listOf(both, cardOnly), listOf(ana, shared)))
    }
}
