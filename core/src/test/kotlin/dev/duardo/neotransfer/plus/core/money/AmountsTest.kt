package dev.duardo.neotransfer.plus.core.money

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountsTest {
    @Test fun userEntryIsExactWithEitherDecimalSeparator() {
        assertEquals(BigDecimal("300"), Amounts.parse("300"))
        assertEquals(BigDecimal("300.50"), Amounts.parse(" 300,50 "))
        assertEquals(BigDecimal("0.01"), Amounts.parse("0.01"))
        assertEquals(2, Amounts.parse("12.30")!!.scale())
    }

    @Test fun ambiguousOrNonPositiveEntryIsRejectedNotGuessed() {
        for (text in listOf("", " ", "0", "0.00", "-1", "+1", "1,000.00", "1.000,00", "1.234", "1e3", "1E+3",
            "NaN", "１２", "12.", ".5", "1 000", "9999999999999")) {
            assertNull(Amounts.parse(text), text)
        }
    }

    @Test fun wireTextNeverUsesScientificNotationOrDropsScale() {
        assertEquals("100000000000000000000", Amounts.wire(BigDecimal("1E+20")))
        assertEquals("10.2300", Amounts.wire(BigDecimal("10.2300")))
    }
}
