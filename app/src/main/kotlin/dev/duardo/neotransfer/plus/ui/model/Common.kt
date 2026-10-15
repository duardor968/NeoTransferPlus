package dev.duardo.neotransfer.plus.ui.model

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant

@Immutable
data class Money(val amount: BigDecimal, val currency: String)

/** Issuer art. Only BPA and BANDEC have real card art; any other bank gets a neutral face with its name. */
enum class CardArt { BPA, BANDEC, Neutral }

/**
 * One of the user's cards as ENZONA lists it. [number] is the full PAN once the user completed it (ENZONA only
 * returns [last4]); [bank] is the short issuer name shown everywhere ("BPA", "BANDEC", "BANMET").
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

/** A card of someone else. [bank] only when the number tells it. */
@Immutable
data class ForeignCardUi(val id: String, val number: String, val bank: String? = null, val label: String? = null)

@Immutable
data class ContactUi(val id: String, val name: String, val cards: List<ForeignCardUi>, val phones: List<String>)

/** Who receives a transfer: a card (named when it belongs to a contact) or an ENZONA account read from its QR. */
@Immutable
sealed interface RecipientUi {
    val name: String?

    data class Card(val card: ForeignCardUi, override val name: String? = null, val contactId: String? = null) : RecipientUi
    data class Account(val uuid: String, val username: String, override val name: String?) : RecipientUi
}

enum class Appearance(val label: String) { System("Sistema"), Light("Claro"), Dark("Oscuro") }
