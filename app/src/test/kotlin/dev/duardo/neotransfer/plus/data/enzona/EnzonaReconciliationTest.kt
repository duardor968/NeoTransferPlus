package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the history may and may not say of an unconfirmed attempt. Synthetic rows only. */
class EnzonaReconciliationTest {
    private val havana = ZoneId.of("America/Havana")
    private val paidAt = Instant.parse("2026-10-14T16:00:00Z").toEpochMilli()
    private val day = EnzonaReconciliation.AFTER
    private val week = LocalDate.of(2026, 10, 8)..LocalDate.of(2026, 10, 15)
    private val own = mapOf("card" to "0007")

    private fun qr(id: String = "00000000-0000-4000-8000-0000000000b1", card: String = "card") = EnzonaJournalEntry(
        EnzonaIntent(id, "owner", card, EnzonaIntentType.QR_MERCHANT_PAYMENT, EnzonaDestination("07code"), "480.00", "CUP", preparedAt = paidAt),
        EnzonaIntentState.UNKNOWN,
    )

    private fun row(uuid: String, at: Long, code: Int = 1111, amount: String = "480.00") = EnzonaReadJson.transaction("""{"transaction_uuid":"$uuid",
        "amount":"$amount","currency":"CUP","transaction_status_code":$code,"merchant_name":"Cafetería",
        "transaction_created_at":"${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(at).atZone(havana))}",
        "source":{"funding_source_last4":"0007"}}""")

    @Test fun aQrPaymentIsNeverConfirmedByAmountButASimilarRowMeansItMayBeThere() {
        val entry = qr().copy(notFoundAt = paidAt + day)
        val changes = EnzonaReconciliation.plan(listOf(entry), listOf(row("r", paidAt + 60_000)), own, havana, week, paidAt + day + 1)
        assertEquals(listOf<EnzonaReconciliationChange>(EnzonaReconciliationChange.Seen(entry.intent.id)), changes)
        // With nothing alike, after a full day, it is not there.
        val missing = EnzonaReconciliation.plan(listOf(qr()), listOf(row("r", paidAt + 60_000, amount = "480.01")), own, havana, week, paidAt + day + 1)
        assertEquals(listOf<EnzonaReconciliationChange>(EnzonaReconciliationChange.NotFound(qr().intent.id, paidAt + day + 1)), missing)
    }

    @Test fun nothingIsSaidWithoutTheOwnCardOrWithoutAReadCoveringTheWholeTime() {
        assertTrue(EnzonaReconciliation.plan(listOf(qr(card = "unknown-card")), emptyList(), own, havana, week, paidAt + day).isEmpty())
        val later = LocalDate.of(2026, 10, 15)..LocalDate.of(2026, 10, 22)
        assertTrue(EnzonaReconciliation.plan(listOf(qr()), emptyList(), own, havana, later, paidAt + day).isEmpty())
        // An older read never moves "not found" back.
        assertTrue(EnzonaReconciliation.plan(listOf(qr().copy(notFoundAt = paidAt + 2 * day)), emptyList(), own, havana, week, paidAt + day).isEmpty())
    }
}
