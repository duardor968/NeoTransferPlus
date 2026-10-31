package dev.duardo.neotransfer.plus.core.card

import kotlin.test.Test
import kotlin.test.assertEquals

class CardIssuerTest {
    @Test fun theNumberTellsTheBankAndTheCurrencyAsTransfermovilReadsIt() {
        assertEquals(CardIssuer(CardBank.BANDEC, "CUP"), CardNumbers.issuer("9204069900000000"))
        assertEquals(CardIssuer(CardBank.BPA, "CUP"), CardNumbers.issuer("9212129900000000"))
        assertEquals(CardIssuer(CardBank.BANMET, "CUP"), CardNumbers.issuer("9205959800000000"))
        assertEquals(CardIssuer(CardBank.BPA, "USD"), CardNumbers.issuer("9225129900000000"))
        assertEquals(CardIssuer(CardBank.BANDEC, "USD"), CardNumbers.issuer("9240069900000000")) // Tropical
        for (product in listOf("25", "26", "28", "29", "35", "40", "45")) {
            assertEquals("USD", CardNumbers.issuer("92${product}129900000000").currency, product)
        }
        assertEquals(CardIssuer(CardBank.BANDEC, null), CardNumbers.issuer("9250069900000000"))
        assertEquals(CardIssuer(null, "CUP"), CardNumbers.issuer("9204779900000000")) // a bank the rule does not name
    }

    @Test fun fincimexCardsAreDollarsOfNoBank() {
        assertEquals(CardIssuer(null, "USD"), CardNumbers.issuer("9760030000000000")) // Clásica
        assertEquals(CardIssuer(null, "USD"), CardNumbers.issuer("9560120000000000")) // AIS
    }

    @Test fun foreignOrMalformedNumbersTellNothing() {
        assertEquals(CardIssuer(null, null), CardNumbers.issuer("4111111111111111"))
        assertEquals(CardIssuer(null, null), CardNumbers.issuer("92041299000000001"))
        assertEquals(CardIssuer(null, null), CardNumbers.issuer("9204 1299"))
        assertEquals(CardIssuer(null, null), CardNumbers.issuer(""))
    }

    @Test fun whileTypedTheCurrencyComesWithTheFourthDigitAndTheBankWithTheSixth() {
        assertEquals(CardIssuer(null, null), CardNumbers.issuer("920"))
        assertEquals(CardIssuer(null, "CUP"), CardNumbers.issuer("9204"))
        assertEquals(CardIssuer(null, "CUP"), CardNumbers.issuer("92040"))
        assertEquals(CardIssuer(CardBank.BANDEC, "CUP"), CardNumbers.issuer("920406"))
    }
}
