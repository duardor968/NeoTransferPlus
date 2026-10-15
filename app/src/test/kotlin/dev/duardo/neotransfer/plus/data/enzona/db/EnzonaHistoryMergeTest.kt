package dev.duardo.neotransfer.plus.data.enzona.db

import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnzonaHistoryMergeTest {
    private val owner = "owner@carbon.super"

    private fun row(uuid: String, recipient: String = "me", sender: String = "sender", extra: String = ""): EnzonaTransaction =
        EnzonaReadJson.transactions("""{"transaction":[{"transaction_uuid":"$uuid","amount":"10.00","currency":"CUP",
            "source":{"funding_source_username":"$sender"},"recipient":{"funding_recipient_username":"$recipient"}$extra}],
            "pagination":{"total":1}}""").transactions.single()

    private fun plan(incoming: List<EnzonaTransaction>, existing: List<EnzonaTransactionRow> = emptyList(), at: Long = 100,
        baseline: Boolean = true, notify: Boolean = true) =
        EnzonaHistoryMerge.plan(owner, incoming, existing.associateBy { it.transactionUuid }, at, baseline, notify, "me")

    @Test fun firstSyncAfterSignInEstablishesTheBaselineWithoutNotifyingOldMoney() {
        assertTrue(plan(listOf(row("a"), row("b")), baseline = false).all { it.notice == EnzonaCreditNotice.NONE })
    }

    @Test fun newIncomingCreditOnARecentSyncIsQueuedOnceAndOutgoingRowsAreNot() {
        val first = plan(listOf(row("credit"), row("debit", recipient = "someone", sender = "me")))
        assertEquals(EnzonaCreditNotice.PENDING, first.single { it.transactionUuid == "credit" }.notice)
        assertEquals(EnzonaCreditNotice.NONE, first.single { it.transactionUuid == "debit" }.notice)
        assertEquals(100L, first.single { it.transactionUuid == "credit" }.firstSeenAt)
        val delivered = first.map { if (it.notice == EnzonaCreditNotice.PENDING) it.copy(notice = EnzonaCreditNotice.DELIVERED) else it }
        val again = plan(listOf(row("credit", extra = ""","transaction_status":"Aceptada"""")), delivered, at = 200)
        val credit = again.single()
        assertEquals(EnzonaCreditNotice.DELIVERED, credit.notice) // never re-notified
        assertEquals(100L, credit.firstSeenAt)
        assertEquals(200L, credit.observedAt)
        assertTrue(credit.json.contains("Aceptada"))
    }

    @Test fun olderDatesBrowsingNeverNotifiesAndAnOlderReadNeverOverwritesANewerOne() {
        assertEquals(EnzonaCreditNotice.NONE, plan(listOf(row("old-credit")), notify = false).single().notice)
        val newer = plan(listOf(row("x", extra = ""","transaction_status":"new"""")), at = 300)
        val stale = plan(listOf(row("x", extra = ""","transaction_status":"old"""")), newer, at = 250).single()
        assertEquals(300L, stale.observedAt)
        assertTrue(stale.json.contains("\"new\""))
    }

    @Test fun repeatedUuidsInOnePageCollapseToOneRow() {
        assertEquals(1, plan(listOf(row("dup"), row("dup"))).size)
    }

    @Test fun storedRowsAreSanitizedAndStillDecodable() {
        val secret = row("s", extra = ""","payment_password":"SECRET_FIXTURE","metadata":{"PIN":"1234","kept":false}""")
        val stored = plan(listOf(secret)).single()
        assertFalse(stored.json.contains("SECRET_FIXTURE"))
        assertFalse(stored.json.contains("1234"))
        assertTrue(stored.json.contains("\"kept\":false"))
        assertEquals("s", EnzonaReadJson.transaction(stored.json).transactionUuid)
        assertFailsWith<IllegalArgumentException> { EnzonaHistoryMerge.plan(" ", listOf(secret), emptyMap(), 1, true, true, "me") }
    }

    @Test fun cacheSanitizerValidatesEachKindAndDropsAuthorizationMaterial() {
        val account = EnzonaCacheSanitizer.sanitize(EnzonaReadKind.ACCOUNT,
            """{"account":{"username":"u","payment_password":"X1","oldPaymentPassword":"X2","totpCode":"X3","fingerprint":"X4"}}""")
        listOf("X1", "X2", "X3", "X4").forEach { assertFalse(account.contains(it)) }
        assertTrue(account.contains("\"username\":\"u\""))
        assertFailsWith<IllegalArgumentException> { EnzonaCacheSanitizer.sanitize(EnzonaReadKind.CARDS, """{"fault":{}}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaCacheSanitizer.sanitize(EnzonaReadKind.BALANCE, """{"balance":1}""") }
        val balance = EnzonaCacheSanitizer.sanitize(EnzonaReadKind.BALANCE, """{"balance":10.0000,"operation":[]}""")
        assertTrue(balance.contains("10.0000"))
    }
}
