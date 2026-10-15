package dev.duardo.neotransfer.plus.core.read

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnzonaAccountAndCreditsTest {
    // Synthetic values only.
    @Test fun accountDecodesTheTracedEnvelopeWithoutTreatingProviderFalseAsText() {
        val account = EnzonaReadJson.account("""{"account":{
            "username":"synthetic-user","uuid":"00000000-0000-4000-8000-000000000009","name":"Ana","lastname":"Prueba",
            "phone":5350000000,"email":false,"avatar":null,"primary_currency":"CUP","payment_password":"SECRET_FIXTURE"
        }}""")
        assertEquals("synthetic-user", account.username)
        assertEquals("00000000-0000-4000-8000-000000000009", account.uuid)
        assertEquals("Ana", account.name)
        assertEquals("Prueba", account.lastName)
        assertEquals("5350000000", account.phone)
        assertNull(account.email)
        assertNull(account.avatar)
        assertEquals("CUP", account.primaryCurrency)
        assertFalse(account.toString().contains("SECRET_FIXTURE"))
        assertFalse(account.toString().contains("Ana"))
    }

    @Test fun accountFaultsAndEmptyObjectsAreNotAccounts() {
        for (body in listOf("{}", """{"account":{}}""", """{"account":null}""", """{"account":[]}""",
            """{"fault":{"description":"Invalid Credentials"}}""", """{"account":{"username":"u"},"error":"x"}""",
            """{"account":{"fault":{}}}""", "not-json")) {
            assertFailsWith<IllegalArgumentException> { EnzonaReadJson.account(body) }
        }
    }

    private fun transaction(source: String, recipient: String, amount: String = "\"125.50\"") =
        EnzonaReadJson.transactions("""{"transaction":[{"transaction_uuid":"00000000-0000-4000-8000-00000000000a",
            "amount":$amount,"currency":"CUP","transaction_created_at":"2026-10-15T09:00:00",
            "source":$source,"recipient":$recipient}],"pagination":{"total":1}}""").transactions.single()

    private val sender = """{"funding_source_username":"sender-user","funding_source_name":"Luis","funding_source_lastname":"Remitente","funding_source_last4":"0042"}"""
    private val me = """{"funding_recipient_username":"me-user","funding_recipient_last4":"0007"}"""

    @Test fun creditToTheSignedInUsernameCarriesSenderAndExactAmount() {
        val credit = assertNotNull(EnzonaCredits.incoming(transaction(sender, me), "me-user"))
        assertEquals(BigDecimal("125.50"), credit.amount)
        assertEquals("CUP", credit.currency)
        assertEquals("Luis", credit.senderName)
        assertEquals("Remitente", credit.senderLastName)
        assertEquals("sender-user", credit.senderUsername)
        assertEquals("0042", credit.senderLast4)
        assertEquals("2026-10-15T09:00:00", credit.createdAt?.value)
        assertEquals("EnzonaIncomingCredit(redacted)", credit.toString())
    }

    @Test fun ambiguousOrOutgoingRowsNeverBecomeCredits() {
        val toOther = """{"funding_recipient_username":"other-user"}"""
        val fromMe = """{"funding_source_username":"me-user"}"""
        val cases = listOf(
            transaction(sender, toOther) to "me-user",          // someone else received it
            transaction(fromMe, me) to "me-user",               // own movement between own cards
            transaction("false", me) to "me-user",              // no sender
            transaction("""{"funding_source_username":""}""", me) to "me-user",
            transaction(sender, "null") to "me-user",           // no recipient
            transaction(sender, me, "null") to "me-user",       // no amount
            transaction(sender, me, "\"-5\"") to "me-user",     // negative amount
            transaction(sender, me) to null,                    // account username unknown
            transaction(sender, me) to " ",
        )
        for ((row, username) in cases) assertNull(EnzonaCredits.incoming(row, username))
    }
}
