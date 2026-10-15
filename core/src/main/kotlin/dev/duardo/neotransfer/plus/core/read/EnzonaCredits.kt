package dev.duardo.neotransfer.plus.core.read

import java.math.BigDecimal

/** A received payment worth notifying: sender identity as reported by ENZONA, never a local contact guess. */
data class EnzonaIncomingCredit(
    val transactionUuid: String,
    val amount: BigDecimal,
    val currency: String?,
    val senderName: String?,
    val senderLastName: String?,
    val senderUsername: String?,
    val senderLast4: String?,
    val createdAt: EnzonaRemoteDate?,
) {
    override fun toString(): String = "EnzonaIncomingCredit(redacted)"
}

object EnzonaCredits {
    /**
     * STATIC INFERENCE, not server-validated: the AOT models expose no direction field for account
     * history. A row counts as an incoming credit only when its recipient username equals the
     * signed-in account username, its source username is present and different, and the amount is
     * positive. Anything ambiguous is not a credit, so a wrong guess stays silent instead of notifying.
     */
    fun incoming(transaction: EnzonaTransaction, accountUsername: String?): EnzonaIncomingCredit? {
        val own = accountUsername?.takeIf(String::isNotBlank) ?: return null
        val recipient = transaction.recipient?.username ?: return null
        val source = transaction.source ?: return null
        val sender = source.username?.takeIf(String::isNotBlank) ?: return null
        val amount = transaction.amount?.takeIf { it.signum() > 0 } ?: return null
        if (recipient != own || sender == own) return null
        return EnzonaIncomingCredit(
            transactionUuid = transaction.transactionUuid,
            amount = amount,
            currency = transaction.currency,
            senderName = source.name,
            senderLastName = source.lastName,
            senderUsername = sender,
            senderLast4 = source.last4,
            createdAt = transaction.createdAt,
        )
    }
}
