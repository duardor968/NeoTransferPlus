package dev.duardo.neotransfer.plus.core.money

import java.math.BigDecimal

/** Money is always [BigDecimal]: no floating-point intermediate between the user, the wire and storage. */
object Amounts {
    private val userAmount = Regex("""[0-9]{1,12}([.,][0-9]{1,2})?""")

    /**
     * An amount typed by the user: digits with an optional `.` or `,` decimal separator and at most
     * two decimals. Thousands separators, signs and exponents are rejected rather than guessed.
     */
    fun parse(text: String): BigDecimal? {
        val value = text.trim()
        if (!userAmount.matches(value)) return null
        return BigDecimal(value.replace(',', '.')).takeIf { it.signum() > 0 }
    }

    /** Exact wire/storage text; never scientific notation (`1E+20` becomes `100000000000000000000`). */
    fun wire(amount: BigDecimal): String = amount.toPlainString()
}
