package dev.duardo.neotransfer.plus.core.payment

import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaPaymentJsonTest {
    private val card = EnzonaPaymentRequest(EnzonaPaymentRequest.CARD, "{}")
    private val account = EnzonaPaymentRequest(EnzonaPaymentRequest.ACCOUNT, "{}")
    private val merchant = EnzonaPaymentRequest(EnzonaPaymentRequest.MERCHANT, "{}")
    private fun invoice(service: EnzonaInvoiceService) = EnzonaPaymentRequest(EnzonaPaymentRequest.INVOICE, "{}", service)
    private fun decode(request: EnzonaPaymentRequest, body: String, status: Int = 200) =
        EnzonaPaymentJson.evidence(request, status, body, 123)

    @Test fun onlyTransfer1111CanConfirmWithoutInventingARemoteIdentifier() {
        val evidence = decode(account, "{\"status_code\":\"1111\"}")
        assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
        assertEquals(123L, evidence.observedAt)
        assertNull(evidence.transactionUuid)
        assertNull(evidence.uuid)
        assertEquals(EnzonaIntentState.UNKNOWN, decode(merchant, "{\"status_code\":\"1111\"}").state)
        assertEquals(EnzonaIntentState.CONFIRMED, decode(card, "{\"statusCode\":1111,\"transaction_uuid\":\"observed-id\"}").state)
    }

    @Test fun malformedFaultUnknownAndConflictingResponsesNeverBecomeSuccessOrRejection() {
        for (json in listOf("[]", "{private-fixture", "{}", "{\"status_code\":0}",
            "{\"statusCode\":1111,\"fault\":null}", "{\"statusCode\":1111,\"error\":\"private-fixture\"}",
            "{\"statusCode\":1111,\"status_code\":2222}", "{\"statusCode\":1111,\"status_code\":null}",
            "{\"statusCode\":true}", "{\"statusCode\":1111.0}")) {
            assertEquals(EnzonaIntentState.UNKNOWN, decode(card, json).state, json)
        }
    }

    @Test fun differentProviderIdentifiersRemainDistinctAndDoNotInvalidate1111() {
        val evidence = decode(card, "{\"statusCode\":1111,\"transaction_uuid\":\"history-id\",\"uuid\":\"separate-id\"}")
        assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
        assertEquals("history-id", evidence.transactionUuid)
        assertEquals("separate-id", evidence.uuid)
        val onlyUuid = decode(card, "{\"statusCode\":1111,\"uuid\":\"separate-id\"}")
        assertEquals(EnzonaIntentState.CONFIRMED, onlyUuid.state)
        assertNull(onlyUuid.transactionUuid)
    }

    @Test fun camelCaseSuccessIsNotAssumedForAccountOrMerchant() {
        for (request in listOf(account, merchant)) assertEquals(EnzonaIntentState.UNKNOWN, decode(request, "{\"statusCode\":\"1111\"}").state)
    }

    @Test fun httpFailureNeverConfirms() {
        for (status in listOf(401, 400, 500, 503)) assertEquals(EnzonaIntentState.UNKNOWN, decode(card, "{\"statusCode\":1111}", status).state)
    }

    @Test fun invoiceEvidenceUsesOnlyFamilySpecificTerminalStates() {
        val fixtures = listOf(
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":1111,"uuid":"invoice-id","invoice_number":"0007"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":"1111"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":1111,"status":"Fallida"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.FIXED_PHONE, """{"charged_invoice":{"status":"Aceptada"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.FIXED_PHONE, """{"getPaymentPasswordIntent":{},"charged_invoice":{"status":"Aceptada"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.NAUTA, """{"charged_invoice":{"status":"Fallida"}}""", EnzonaIntentState.REJECTED),
            Triple(EnzonaInvoiceService.NAUTA_HOME, """{"charged_invoice":{"status":"Pendiente"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.NAUTA, """{"charged_invoice":{"status_code":1111}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.NAUTA, """{"getPaymentPasswordIntent":{"synthetic":"x"},"charged_invoice":{"status":"Aceptada"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.NAUTA, """{"fault":{},"charged_invoice":{"status":"Fallida"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.NAUTA, "not-json", EnzonaIntentState.UNKNOWN),
        )
        for ((service, response, expected) in fixtures) {
            val evidence = decode(invoice(service), response)
            assertEquals(expected, evidence.state, response)
            assertNull(evidence.transactionUuid) // charged_invoice.uuid is not a history transaction id
            if (response.contains("invoice-id")) {
                assertEquals("invoice-id", evidence.uuid)
                assertEquals("0007", evidence.serviceResult!!.invoiceNumber)
            }
        }
        assertEquals(EnzonaIntentState.UNKNOWN, decode(invoice(EnzonaInvoiceService.NAUTA), """{"charged_invoice":{"status":"Aceptada"}}""", 401).state)
    }

    @Test fun onlyTracedRoutesWithTheirDecoderFamilyAreSendable() {
        assertTrue(card.isTraced && account.isTraced && merchant.isTraced && invoice(EnzonaInvoiceService.NAUTA).isTraced)
        assertFalse(EnzonaPaymentRequest(EnzonaPaymentRequest.INVOICE, "{}").isTraced)
        assertFalse(EnzonaPaymentRequest(EnzonaPaymentRequest.CARD, "{}", EnzonaInvoiceService.NAUTA).isTraced)
        for (path in listOf("/untraced", "${EnzonaPaymentRequest.CARD}?extra=1", "https://other.invalid/")) {
            assertFalse(EnzonaPaymentRequest(path, "{}").isTraced)
        }
    }
}
