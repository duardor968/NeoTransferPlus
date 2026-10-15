package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.payment.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaInvoicePaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequests
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrKind
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: every request is answered by an interceptor or a lambda, never by api.enzona.net. */
class EnzonaApisTest {
    private val owner = "owner@carbon.super"
    private val tokens = syntheticTokens(owner)

    @Test fun readRequestsUseTheTracedIdentityAndDistinguishCardHistoryFromAccountHistory() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request().also(requests::add)
            val json = when {
                request.url.encodedPath.contains("/card/username/") -> "{\"card\":[]}"
                request.url.encodedPath.contains("/balance/lastOperations/") -> "{\"balance\":12.50,\"currency\":\"cup\",\"operation\":[]}"
                else -> "{\"transaction\":[],\"pagination\":{\"total\":0,\"next\":false}}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val api = EnzonaReadApi(http::execute) { 1234 }
            assertEquals(1234L, api.cards(tokens).observedAt)
            assertEquals("cup", api.balance(tokens, "synthetic-card-uuid").value.currency)
            api.transactions(tokens, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
        }
        assertEquals("/coremz/1.0.2/card/username/owner@carbon.super", requests[0].url.encodedPath)
        assertEquals("/coremz/1.0.2/api/balance/lastOperations/synthetic-card-uuid", requests[1].url.encodedPath)
        assertEquals("/operations/v1.0.0/operations/account", requests[2].url.encodedPath)
        assertEquals("100000", requests[2].url.queryParameter("limit"))
        assertEquals("0", requests[2].url.queryParameter("offset"))
        assertEquals("2026-10-01", requests[2].url.queryParameter("start_date_filter"))
        assertEquals("2026-10-07", requests[2].url.queryParameter("end_date_filter"))
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals("Bearer SYNTHETIC_ACCESS", request.header("Authorization"))
            assertEquals("application/json", request.header("accept"))
            assertEquals("1.9.11.241103", request.header("Version"))
        }
    }

    @Test fun rejectedTokenIsReportedSoTheSessionRenewsInsteadOfDecodingAnError() = runBlocking {
        val api = EnzonaReadApi({ EnzonaHttpReply(401, """{"fault":{"code":900901,"description":"Invalid Credentials"}}""") })
        assertTrue(assertFailsWith<EnzonaApiException> { api.cards(tokens) }.invalidCredentials)
        val other = EnzonaReadApi({ EnzonaHttpReply(401, """{"fault":{"description":"Other"}}""") })
        assertFalse(assertFailsWith<EnzonaApiException> { other.cards(tokens) }.invalidCredentials)
    }

    @Test fun qrResolutionUsesOneEncodedPathAndNeverRequestsCashAdvance() = runBlocking {
        var seen: Request? = null
        val api = EnzonaQrApi({ seen = it; EnzonaHttpReply(200,
            "{\"vendor_identity_code\":\"05-code\",\"uuid\":\"recipient\",\"username\":\"other-owner\",\"amount\":\"1.2300\",\"currency\":\"cup\"}") }, { 123 })
        val scanned = "05-code/?cash_advance=true&other=value"
        val result = api.resolve(tokens, owner, scanned)
        assertEquals("GET", seen!!.method)
        assertEquals("api.enzona.net", seen!!.url.host)
        assertEquals(scanned, seen!!.url.pathSegments.last())
        assertEquals(setOf("cash_advance"), seen!!.url.queryParameterNames)
        assertEquals("false", seen!!.url.queryParameter("cash_advance"))
        assertNull(seen!!.body)
        assertEquals(EnzonaQrKind.ACCOUNT, result.kind)
        assertEquals(123L, result.observedAt)
    }

    @Test fun ownQrPostsExactDecimalStrings() = runBlocking {
        var seen: Request? = null
        val api = EnzonaQrApi({ seen = it; EnzonaHttpReply(200, "{\"vendor_identity_code\":\"05-issued-by-fixture\",\"amount\":\"4.2500\"}") })
        val description = "reviewed \"quote\"\nñ"
        val result = api.createOwn(tokens, owner, BigDecimal("4.2500"), "cup", description)
        assertEquals("https://api.enzona.net/qr/v1.0.0/qr/account", seen!!.url.toString())
        assertEquals("POST", seen!!.method)
        val json = Json.parseToJsonElement(seen!!.bodyText()!!).jsonObject
        assertEquals(setOf("amount", "currency", "description"), json.keys)
        assertTrue(json.getValue("amount").jsonPrimitive.isString)
        assertEquals("4.2500", json.getValue("amount").jsonPrimitive.content)
        assertEquals(description, json.getValue("description").jsonPrimitive.content)
        assertEquals("05-issued-by-fixture", result.code)
    }

    @Test fun qrOwnerMismatchUnrelatedDataAndHttpErrorsNeverReachOrRepeatTheTransport() = runBlocking {
        var calls = 0
        val silent = EnzonaQrApi({ calls++; error("must not send") })
        assertFailsWith<IllegalArgumentException> { silent.resolve(tokens, "other", "05-synthetic") }
        assertFailsWith<IllegalArgumentException> { silent.createOwn(tokens, "other", BigDecimal.ONE, "cup") }
        for (code in listOf(".", "..", "", "05-synthetic\n", "https://api.enzona.net/qr/05-synthetic",
            "WIFI:T:WPA;S:SYNTHETIC;P:SYNTHETIC;;", "arbitrary", "03-synthetic")) {
            assertFailsWith<IllegalArgumentException> { silent.resolve(tokens, owner, code) }
        }
        assertEquals(0, calls)
        val failing = EnzonaQrApi({ calls++; EnzonaHttpReply(401, "private-fixture") })
        assertFailsWith<EnzonaApiException> { failing.resolve(tokens, owner, "05-synthetic") }
        assertEquals(1, calls)
    }

    @Test fun serviceQueriesUseProvenCodesAndCorrectlyEncodeFullIdentifiers() = runBlocking {
        val sent = mutableListOf<Request>()
        val api = EnzonaServiceApi({ request ->
            sent += request
            EnzonaHttpReply(200, if (request.url.queryParameter("service_code") == "2222") """{"client":[]}"""
                else """{"success":"true","datalist":{"data":[]}}""")
        }, now = { 1234 })
        assertEquals(1234L, api.electricityBills(tokens, "0001").observedAt)
        api.etecsaAccounts(tokens, EnzonaInvoiceService.FIXED_PHONE, "70000000")
        api.etecsaAccounts(tokens, EnzonaInvoiceService.NAUTA, "test+synthetic@nauta.cu&x=1")
        api.etecsaAccounts(tokens, EnzonaInvoiceService.NAUTA_HOME, "synthetic@nauta.com.cu")
        assertEquals(listOf("2222", "4441", "4443", "4445"), sent.map { it.url.queryParameter("service_code") })
        assertEquals("test+synthetic@nauta.cu&x=1", sent[2].url.queryParameter("client_id"))
        assertNull(sent[2].url.queryParameter("x"))
        sent.forEach { request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals("/invoice/v1.0.0/invoice/search", request.url.encodedPath)
            assertEquals("Bearer SYNTHETIC_ACCESS", request.header("Authorization"))
            assertEquals("1.9.11.241103", request.header("Version"))
        }
        assertFailsWith<IllegalArgumentException> { api.etecsaAccounts(tokens, EnzonaInvoiceService.ELECTRICITY, "1") }
        assertFailsWith<IllegalArgumentException> { api.electricityBills(tokens, " ") }
        assertEquals(4, sent.size)
    }

    @Test fun serviceFaultsStayStructuredAndRejectedTokensAreDistinguished() = runBlocking {
        val fault = EnzonaServiceApi({ EnzonaHttpReply(200, """{"fault":{"cod":123,"message":"SYNTHETIC"}}""") })
        val exception = assertFailsWith<EnzonaServiceException> { fault.electricityBills(tokens, "id") }
        assertEquals("123", exception.fault?.code)
        assertFalse(exception.toString().contains("SYNTHETIC"))
        val rejected = EnzonaServiceApi({ EnzonaHttpReply(401, """{"fault":{"code":900901,"description":"Invalid Credentials"}}""") })
        assertTrue(assertFailsWith<EnzonaApiException> { rejected.electricityBills(tokens, "id") }.invalidCredentials)
    }

    private val requests = EnzonaPaymentRequests(EnzonaCipher(
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey))
    private fun transfer() = requests.encode(EnzonaAccountTransferDraft("card", "recipient-uuid", "recipient-user",
        BigDecimal("1.00"), "CUP"), charArrayOf('t'))

    @Test fun paymentSendsOneTracedPostWithSessionHeaders() = runBlocking {
        var sends = 0
        val request = transfer()
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            sends++
            val outgoing = chain.request()
            assertEquals("https://api.enzona.net/transfer/v1.0.0/transfer/account", outgoing.url.toString())
            assertEquals("POST", outgoing.method)
            assertEquals("Bearer SYNTHETIC_ACCESS", outgoing.header("Authorization"))
            assertEquals("1.9.11.241103", outgoing.header("Version"))
            assertEquals("application/json; charset=utf-8", outgoing.body!!.contentType().toString())
            assertEquals(request.json, outgoing.bodyText())
            Response.Builder().request(outgoing).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"status_code\":\"1111\",\"transaction_uuid\":\"observed-id\"}".toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val evidence = EnzonaPaymentApi(http::execute).submit(tokens, request)
            assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
            assertEquals("observed-id", evidence.transactionUuid)
        }
        assertEquals(1, sends)
    }

    @Test fun paymentFailuresNeverTriggerASecondExecution() = runBlocking {
        for (status in listOf(401, 400, 500, 503)) {
            var sends = 0
            val api = EnzonaPaymentApi({ sends++; EnzonaHttpReply(status, "{\"status_code\":\"1111\"}") })
            assertEquals(EnzonaIntentState.UNKNOWN, api.submit(tokens, transfer()).state)
            assertEquals(1, sends)
        }
        var sends = 0
        val timeout = EnzonaPaymentApi({ sends++; throw SocketTimeoutException("synthetic") })
        assertFailsWith<SocketTimeoutException> { timeout.submit(tokens, transfer()) }
        assertEquals(1, sends)
        val invoice = requests.encode(EnzonaInvoicePaymentDraft(EnzonaInvoiceService.NAUTA, "n@nauta.com.cu", "n@nauta.com.cu",
            "card", BigDecimal.TEN, "CUP"), charArrayOf('t'))
        val api = EnzonaPaymentApi({ outgoing ->
            assertEquals("https://api.enzona.net/invoice/v1.0.0/invoice/payment", outgoing.url.toString())
            EnzonaHttpReply(200, """{"charged_invoice":{"status":"Fallida"}}""")
        })
        assertEquals(EnzonaIntentState.REJECTED, api.submit(tokens, invoice).state)
    }
}
