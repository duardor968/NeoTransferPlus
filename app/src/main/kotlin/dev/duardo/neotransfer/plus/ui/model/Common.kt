package dev.duardo.neotransfer.plus.ui.model

import androidx.compose.runtime.Immutable
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import java.math.BigDecimal
import java.time.Instant

@Immutable
data class Money(val amount: BigDecimal, val currency: String)

/** Issuer art: BPA, BANDEC and Metropolitano have real card art; any other bank gets a neutral face. */
enum class CardArt {
    BPA, BANDEC, BANMET, Neutral;

    companion object {
        /** By the short bank name shown everywhere. */
        fun of(bank: String?): CardArt = when (bank) {
            "BPA" -> BPA
            "BANDEC" -> BANDEC
            "Metropolitano" -> BANMET
            else -> Neutral
        }
    }
}

/**
 * One of the user's cards as ENZONA lists it. [number] is the full PAN once the user completed it (ENZONA only
 * returns [last4]); [bank] is the short issuer name shown everywhere ("BPA", "BANDEC", "Metropolitano").
 */
@Immutable
data class CardUi(
    val id: String,
    val bank: String,
    val art: CardArt,
    val number: String?,
    val last4: String?,
    val currency: String,
    val holder: String? = null,
    val expiry: String? = null,
)

@Immutable
sealed interface BalanceUi {
    /** No balance read yet for this card. */
    data object Unknown : BalanceUi
    data class Known(val money: Money, val updatedAt: Instant) : BalanceUi
}

@Immutable
data class WalletCardUi(val card: CardUi, val balance: BalanceUi, val refreshing: Boolean = false)

/** A card of someone else. [bank] and [currency] only when the number tells them. */
@Immutable
data class ForeignCardUi(val id: String, val number: String, val bank: String? = null, val label: String? = null, val currency: String? = null) {
    /** `BANDEC · CUP`. */
    val issuer: String? get() = issuerOf(bank, currency)
    val art: CardArt get() = CardArt.of(bank)
}

/** `BANDEC · CUP`, as far as a card number tells, never split across lines; null when it tells nothing. */
fun issuerOf(bank: String?, currency: String?): String? = listOfNotNull(bank, currency?.uppercase()).joinToString("$NoBreak·$NoBreak").ifEmpty { null }

/** [fromAddressBook]: name and phones come from the phone's address book. [last]: the newest money sent to them. */
@Immutable
data class ContactUi(
    val id: String,
    val name: String,
    val cards: List<ForeignCardUi>,
    val phones: List<String>,
    val fromAddressBook: Boolean = false,
    val last: LastMovementUi? = null,
)

@Immutable
data class LastMovementUi(val amount: Money?, val at: Instant?)

/** Who receives a transfer: a card (named when it belongs to a contact) or an ENZONA account read from its QR. */
@Immutable
sealed interface RecipientUi {
    val name: String?

    data class Card(val card: ForeignCardUi, override val name: String? = null, val contactId: String? = null) : RecipientUi
    data class Account(val uuid: String, val username: String, override val name: String?) : RecipientUi
}

enum class Appearance(val label: String) { System("Sistema"), Light("Claro"), Dark("Oscuro") }
