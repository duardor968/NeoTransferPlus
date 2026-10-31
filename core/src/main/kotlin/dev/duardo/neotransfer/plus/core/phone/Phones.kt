package dev.duardo.neotransfer.plus.core.phone

/** Cuban phone numbers as people type and paste them, kept as their last eight digits. */
object Phones {
    /** A mobile or landline as its eight digits; `+53`, `53` and `0053` before them are dropped. Null for anything else. */
    fun normalize(value: String): String? {
        val digits = value.filter { it in '0'..'9' }
        return when {
            digits.length == 8 -> digits
            digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
            digits.length == 12 && digits.startsWith("0053") -> digits.drop(4)
            else -> null
        }
    }

    /**
     * What a phone field keeps of a keystroke or a paste: at most eight digits. A prefix typed key by key passes
     * through the longer lengths (`53` + 7 digits, `0053` + up to 7) until [normalize] can drop it.
     */
    fun input(value: String): String {
        normalize(value)?.let { return it }
        val digits = value.filter { it in '0'..'9' }
        return when {
            digits.length == 9 && digits.startsWith("53") -> digits
            digits.length in 9..11 && digits.startsWith("0053") -> digits
            else -> digits.take(8)
        }
    }
}
