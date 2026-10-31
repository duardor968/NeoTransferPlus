package dev.duardo.neotransfer.plus.core.card

/** The Cuban banks a card number can name. */
enum class CardBank { BPA, BANDEC, BANMET }

/** The bank and currency (`CUP`, `USD`) a card number tells; null where it does not. */
data class CardIssuer(val bank: CardBank?, val currency: String?)

object CardNumbers {
    private val dollarProducts = setOf("25", "26", "28", "29", "35", "40", "45")

    /**
     * What a number, whole or while typed, says about its card, read as Transfermóvil reads it. On national cards
     * (`92…`) digits 5–6 are the bank (12 BPA, 06 BANDEC, 95 Metropolitano) and digits 3–4 the product: dollars for
     * 25, 26, 28, 29, 35, 40 (BANDEC's Tropical) and 45, unknown for 50, pesos for the rest. Fincimex's Clásica
     * (`9760`) and AIS (`9560`) are dollar cards of no bank.
     */
    fun issuer(digits: String): CardIssuer {
        if (digits.length !in 4..16 || !digits.all { it in '0'..'9' }) return CardIssuer(null, null)
        if (digits.startsWith("9760") || digits.startsWith("9560")) return CardIssuer(null, "USD")
        if (!digits.startsWith("92")) return CardIssuer(null, null)
        val bank = if (digits.length < 6) null else when (digits.substring(4, 6)) {
            "12" -> CardBank.BPA
            "06" -> CardBank.BANDEC
            "95" -> CardBank.BANMET
            else -> null
        }
        val currency = when (digits.substring(2, 4)) {
            in dollarProducts -> "USD"
            "50" -> null
            else -> "CUP"
        }
        return CardIssuer(bank, currency)
    }
}
