package dev.duardo.neotransfer.plus.core.qr

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaQrJsonTest {
    private val owner = "synthetic-owner"

    @Test fun onlyTracedPrefixesAreCandidatesAndAPrefixProvesNothingElse() {
        for (code in listOf("01-synthetic", "05-synthetic/?x=y&z=1", "07-opaque:ñ", "053")) assertTrue(EnzonaQrCode.isCandidate(code), code)
        for (code in listOf("https://api.enzona.net/qr/05-synthetic", "https://unrelated.invalid/07-synthetic",
            "WIFI:T:WPA;S:SYNTHETIC;P:SYNTHETIC;;", "mailto:synthetic@example.invalid", "arbitrary", "03-synthetic",
            "05", "05-synthetic\n", "", "0".repeat(2) + "1".repeat(16_383))) {
            assertFalse(EnzonaQrCode.isCandidate(code), code)
        }
    }

    @Test fun accountResolutionKeepsRecipientIdentityForReviewOnly() {
        val result = EnzonaQrJson.resolved(
            "{\"vendor_identity_code\":\"05-code\",\"uuid\":\"recipient\",\"username\":\"other-owner\",\"amount\":\"1.2300\",\"currency\":\"cup\"}",
            "05-code", owner, 123)
        assertEquals(EnzonaQrKind.ACCOUNT, result.kind)
        assertEquals("recipient", result.accountUuid)
        assertEquals(owner, result.ownerSubject)
        assertEquals("other-owner", result.username)
        assertEquals(BigDecimal("1.2300"), result.amount)
        assertEquals(123L, result.observedAt)
        assertFalse(result.toString().contains("recipient"))
    }

    @Test fun distinctResponseFamiliesRemainDistinct() {
        val fixtures = listOf(
            "{\"merchant_uuid\":\"merchant\",\"qr_code\":\"07-code\",\"require_password\":false}" to EnzonaQrKind.MERCHANT_PAYMENT,
            "{\"owners\":[]}" to EnzonaQrKind.MERCHANT_INFO,
            "{\"vendor_identity_code\":\"01-code\"}" to EnzonaQrKind.ACCOUNT,
            "{\"username\":\"recipient\"}" to EnzonaQrKind.ACCOUNT,
        )
        for ((json, kind) in fixtures) assertEquals(kind, EnzonaQrJson.resolved(json, "05-synthetic", owner, 0).kind)
        val merchant = EnzonaQrJson.resolved(fixtures.first().first, "07-scanned", owner, 0)
        assertEquals("07-code", merchant.code)
        assertEquals("merchant", merchant.merchantUuid)
    }

    @Test fun malformedOrUnclassifiedResponsesUseSanitizedErrors() {
        for (json in listOf("{SENSITIVE_FIXTURE", "[]", "{}", "{\"fault\":\"SENSITIVE_FIXTURE\"}",
            "{\"merchant_uuid\":\"merchant\",\"username\":\"cannot-reclassify-as-account\"}",
            "{\"vendor_identity_code\":\"05-code\",\"amount\":true}", "{\"vendor_identity_code\":5}")) {
            val error = assertFailsWith<EnzonaQrResponseException> { EnzonaQrJson.resolved(json, "05-synthetic", owner, 0) }
            assertEquals("La respuesta QR de ENZONA no es válida", error.message)
            assertNull(error.cause)
        }
        assertFailsWith<EnzonaQrResponseException> { EnzonaQrJson.ownQr("{}", owner, 0) }
    }

    @Test fun ownQrKeepsTheServerIssuedCodeLiterally() {
        val own = EnzonaQrJson.ownQr("{\"vendor_identity_code\":\"05-issued-by-fixture\",\"amount\":\"4.2500\",\"currency\":\"cup\",\"description\":\"reply\"}", owner, 9)
        assertEquals("05-issued-by-fixture", own.code)
        assertEquals(BigDecimal("4.2500"), own.amount)
        assertEquals("cup", own.currency)
        assertEquals("EnzonaOwnQr(redacted)", own.toString())
    }
}
