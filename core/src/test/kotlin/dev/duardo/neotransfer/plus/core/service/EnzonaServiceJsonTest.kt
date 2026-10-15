package dev.duardo.neotransfer.plus.core.service

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaServiceJsonTest {
    @Test fun lookupsDecodeExactAmountsAndKeepDebtSeparateFromToPay() {
        val bill = EnzonaServiceJson.electricityBills("""{"client":[{"invoice":[{"client_id":"000000000001","owner":"SYNTHETIC OWNER",
            "folio":"01","route":"02","period":202609,"amount":"125.50","total_amount":120.00,"charged":"false"}]}]}""").single()
        assertEquals("000000000001", bill.clientId)
        assertEquals("202609", bill.period)
        assertEquals(BigDecimal("125.50"), bill.amount)
        assertEquals(BigDecimal("120.00"), bill.totalAmount)
        assertEquals(false, bill.charged)
        val account = EnzonaServiceJson.etecsaAccounts("""{"success":"true","datalist":{"data":[{"service_type":"tb",
            "service_name":"7000000","is_valid":"true","to_pay":"80.00","current_debt":"50.00"}]}}""").accounts.single()
        assertEquals(EnzonaInvoiceService.FIXED_PHONE, account.service)
        assertEquals(BigDecimal("80.00"), account.toPay)
        assertEquals(BigDecimal("50.00"), account.currentDebt)
        assertNull(account.unpaidDebt)
        assertFalse(account.toString().contains("7000000"))
    }

    @Test fun paymentsOnlyRecognizeFamilySpecificConclusiveEvidence() {
        fun decode(service: EnzonaInvoiceService, json: String, http: Int = 200) = EnzonaServiceJson.payment(service, http, json).state
        val electric = EnzonaInvoiceService.ELECTRICITY
        val nauta = EnzonaInvoiceService.NAUTA
        assertEquals(EnzonaServicePaymentState.ACCEPTED, decode(electric, """{"charged_invoice":{"status_code":1111}}"""))
        for (json in listOf("""{"charged_invoice":{"status_code":"1111"}}""", """{"charged_invoice":{"status_code":1121}}""",
            """{"charged_invoice":{"status_code":1111,"status":"Fallida"}}""", """{"status_code":1111}""", "not-json")) {
            assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(electric, json))
        }
        for ((status, expected) in listOf("Aceptada" to EnzonaServicePaymentState.ACCEPTED, "Pendiente" to EnzonaServicePaymentState.PENDING,
            "Fallida" to EnzonaServicePaymentState.FAILED, "NEW_STATUS" to EnzonaServicePaymentState.UNKNOWN)) {
            assertEquals(expected, decode(nauta, """{"charged_invoice":{"status":"$status"}}"""))
        }
        assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(nauta, """{"charged_invoice":{"status":"Aceptada"}}""", 401))
        assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(nauta, """{"fault":{"code":123},"charged_invoice":{"status":"Aceptada"}}"""))
        val intent = EnzonaServiceJson.payment(electric, 200, """{"getPaymentPasswordIntent":{"synthetic":"AUTHORIZATION_RESULT"},"charged_invoice":{"status_code":1111}}""")
        assertTrue(intent.passwordIntent)
        assertEquals(EnzonaServicePaymentState.UNKNOWN, intent.state)
    }

    @Test fun absentEmptyAndNullPasswordIntentPreserveTheFamilyPaymentState() {
        for (service in EnzonaInvoiceService.entries) {
            val statuses = if (service == EnzonaInvoiceService.ELECTRICITY) {
                listOf("\"status_code\":1111" to EnzonaServicePaymentState.ACCEPTED, "\"status_code\":1121" to EnzonaServicePaymentState.UNKNOWN)
            } else {
                listOf("\"status\":\"Aceptada\"" to EnzonaServicePaymentState.ACCEPTED, "\"status\":\"Pendiente\"" to EnzonaServicePaymentState.PENDING,
                    "\"status\":\"Fallida\"" to EnzonaServicePaymentState.FAILED)
            }
            for ((status, expected) in statuses) {
                for (rootIntent in listOf("", "\"getPaymentPasswordIntent\":{},", "\"getPaymentPasswordIntent\":null,")) {
                    for (nestedIntent in listOf("", ",\"getPaymentPasswordIntent\":{}", ",\"getPaymentPasswordIntent\":null")) {
                        val result = EnzonaServiceJson.payment(service, 200, "{$rootIntent\"charged_invoice\":{$status$nestedIntent}}")
                        assertEquals(expected, result.state)
                        assertFalse(result.passwordIntent)
                    }
                }
            }
            for (json in listOf("{}", """{"getPaymentPasswordIntent":{}}""", """{"getPaymentPasswordIntent":null}""")) {
                assertEquals(EnzonaServicePaymentState.UNKNOWN, EnzonaServiceJson.payment(service, 200, json).state)
            }
        }
    }

    @Test fun nonEmptyOrMalformedPasswordIntentCannotConfirmAPayment() {
        for (service in EnzonaInvoiceService.entries) {
            for (value in listOf("""{"synthetic":"AUTHORIZATION_RESULT"}""", "false", "[]", "\"\"", "0")) {
                for (nested in listOf(false, true)) {
                    val intent = "\"getPaymentPasswordIntent\":$value"
                    val status = if (service == EnzonaInvoiceService.ELECTRICITY) "\"status_code\":1111" else "\"status\":\"Aceptada\""
                    val json = if (nested) "{\"charged_invoice\":{$status,$intent}}" else "{$intent,\"charged_invoice\":{$status}}"
                    val result = EnzonaServiceJson.payment(service, 200, json)
                    assertEquals(EnzonaServicePaymentState.UNKNOWN, result.state)
                    assertEquals(value.startsWith("{"), result.passwordIntent)
                }
            }
        }
    }

    @Test fun malformedEnvelopesAreNotEmptyListsAndFaultsRemainStructured() {
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.electricityBills("""{"invoice":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.etecsaAccounts("""{"data":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.electricityBills("""{"fault":{},"client":[]}""") }
        assertEquals(emptyList(), EnzonaServiceJson.electricityBills("""{"client":[]}"""))
        val fault = EnzonaServiceJson.fault("""{"fault":{"cod":123,"message":"SYNTHETIC"}}""")
        assertEquals("123", fault?.code)
        assertFalse(fault.toString().contains("SYNTHETIC"))
        assertFalse(EnzonaServiceException(200, fault).toString().contains("SYNTHETIC"))
    }
}
