package dev.duardo.neotransfer.plus.core.activity

import java.math.BigDecimal

/** What an operation was, in the words both apps share. */
enum class SyncKind {
    CARD_TRANSFER, ACCOUNT_TRANSFER, QR_PAYMENT, ELECTRICITY, LANDLINE, WATER, NAUTA, NAUTA_HOME, MOBILE_TOPUP,
    CUBACEL_TRANSFER, SERVICE, INCOMING, OTHER,
}

enum class SyncStatus { CONFIRMED, PENDING, UNCONFIRMED, REJECTED, NOT_DONE, NOT_SENT }

enum class SyncDirection { OUT, IN }

/**
 * One operation an app or its bank channel originated, as both apps share it. [title] is as that app shows it; [amount]
 * is absolute, null when there is none. [card] is the own card: 16 digits, its last four, or empty. [counterpart] is a
 * card number, an 8-digit phone, an ENZONA username or a service reference, empty when there is none. [contactId] is
 * `phone:<lookup key>` or the app's contact id, empty when there is none.
 */
data class SyncOperation(
    val id: String,
    val kind: SyncKind,
    val status: SyncStatus,
    val title: String,
    val at: Long,
    val amount: BigDecimal?,
    val currency: String,
    val direction: SyncDirection,
    val card: String,
    val counterpart: String,
    val contactId: String,
) {
    override fun toString() = "SyncOperation(redacted)"
}

/**
 * The activity NeoTransfer and NeoTransfer+ show each other: each exports what it or its bank channel originated, never
 * what it read from the other, and shows the other's rows next to its own while the other app is installed.
 */
object ActivitySync {
    /** The exported columns, in this order. */
    val COLUMNS = arrayOf("id", "kind", "status", "title", "at", "amount", "currency", "direction", "card", "counterpart", "contactId")

    const val MAX_ROWS = 500
    const val MAX_AGE_MILLIS = 120L * 24 * 60 * 60_000

    /** Newest first, the last [MAX_AGE_MILLIS] before [now] and at most [MAX_ROWS] of them. */
    fun window(operations: List<SyncOperation>, now: Long): List<SyncOperation> =
        operations.filter { it.at >= now - MAX_AGE_MILLIS }.sortedByDescending { it.at }.take(MAX_ROWS)

    /** One exported row, in [COLUMNS] order. */
    fun encode(operation: SyncOperation): Array<Any> = arrayOf(
        operation.id,
        operation.kind.name,
        operation.status.name,
        operation.title,
        operation.at,
        operation.amount?.let(::plain).orEmpty(),
        operation.currency,
        operation.direction.name,
        operation.card,
        operation.counterpart,
        operation.contactId,
    )

    /** `10.00`, `1250.5` → `1250.50`: absolute, no grouping, at least two decimals. */
    private fun plain(amount: BigDecimal): String = amount.abs().let { if (it.scale() < 2) it.setScale(2) else it }.toPlainString()

    /** One row read from the other app; null when it names no id, or a kind, state or direction this app does not know. */
    fun decode(
        id: String?, kind: String?, status: String?, title: String?, at: Long, amount: String?, currency: String?,
        direction: String?, card: String?, counterpart: String?, contactId: String?,
    ): SyncOperation? {
        if (id.isNullOrBlank()) return null
        return SyncOperation(
            id = id,
            kind = SyncKind.entries.firstOrNull { it.name == kind } ?: return null,
            status = SyncStatus.entries.firstOrNull { it.name == status } ?: return null,
            title = title.orEmpty().trim(),
            at = at,
            amount = amount?.trim()?.takeIf(String::isNotEmpty)?.toBigDecimalOrNull()?.abs(),
            currency = currency.orEmpty().trim(),
            direction = SyncDirection.entries.firstOrNull { it.name == direction } ?: return null,
            card = card.orEmpty().filter(Char::isDigit).takeIf { it.length == 16 || it.length == 4 }.orEmpty(),
            counterpart = counterpart.orEmpty().trim(),
            contactId = contactId.orEmpty().trim(),
        )
    }
}
