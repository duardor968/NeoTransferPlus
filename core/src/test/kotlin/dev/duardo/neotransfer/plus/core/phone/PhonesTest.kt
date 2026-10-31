package dev.duardo.neotransfer.plus.core.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhonesTest {
    @Test fun aPastedNumberLosesItsCountryCodeNotItsLastDigits() {
        for (pasted in listOf("+53 5312 4578", "53 5312 4578", "0053 5312 4578", "5312-4578", "(+53) 53124578")) {
            assertEquals("53124578", Phones.input(pasted), pasted)
            assertEquals("53124578", Phones.normalize(pasted), pasted)
        }
    }

    @Test fun aPrefixTypedKeyByKeyIsKeptUntilItCanBeDropped() {
        fun typed(text: String) = text.indices.fold("") { field, i -> Phones.input(field + text[i]) }
        assertEquals("53124578", typed("+53 5312 4578"))
        assertEquals("53124578", typed("0053 5312 4578"))
        assertEquals("53124578", typed("53124578"))
        assertEquals("535312457", Phones.input("+53 5312 457"))
        assertEquals("00535312457", Phones.input("0053 5312 457"))
    }

    @Test fun anythingElseKeepsAtMostEightDigits() {
        assertEquals("54417720", Phones.input("544177201"))
        assertEquals("5441", Phones.input("5441"))
        assertEquals("", Phones.input("abc"))
        assertNull(Phones.normalize("5441772"))
        assertNull(Phones.normalize("545312457812"))
        assertNull(Phones.normalize("+1 305 555 0101"))
    }
}
