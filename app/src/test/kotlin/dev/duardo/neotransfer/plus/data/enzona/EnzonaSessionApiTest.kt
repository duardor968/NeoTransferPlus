package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnzonaSessionApiTest {
    private fun form(body: String): Map<String, String> = body.split('&').associate { field ->
        val pair = field.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    @Test fun exchangeBootstrapAndRefreshFollowTheTracedRequestShapes() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String?>()
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            bodies.add(request.bodyText())
            val body = when (request.url.encodedPath) {
                "/oauth2/token" -> syntheticTokenBody()
                "/account/v1.0.0/account/authentication" -> "{}"
                "/account/v1.0.0/account" -> "{\"account\":{\"username\":\"synthetic\",\"name\":\"Ana\"}}"
                else -> error("Unexpected synthetic request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val api = EnzonaSessionApi(http::execute) { 1000 }
            val attempt = EnzonaAuthContract.newAttempt()
            val state = form(attempt.authorizationUri.rawQuery).getValue("state")
            val exchange = attempt.consumeCallback("http://apk-callback/?code=SYNTHETIC_CODE&state=$state")
            val token = api.exchange(exchange)
            api.recordDevice(token, linkedMapOf("accessType" to "MOBILE", "imei" to "SYNTHETIC_BUILD_ID"))
            val account = api.account(token)
            assertEquals("synthetic", account.value.username)
            assertEquals("Ana", account.value.name)
            assertEquals(1_000_000L, account.observedAt)
            api.refresh(token)
        }
        assertEquals(listOf("POST", "POST", "GET", "POST"), requests.map { it.method })
        assertEquals("authorization_code", form(requireNotNull(bodies[0]))["grant_type"])
        assertEquals("http://apk-callback", form(requireNotNull(bodies[0]))["redirect_uri"])
        assertNull(requests[0].header("Authorization"))
        assertEquals("{accessType: MOBILE, imei: SYNTHETIC_BUILD_ID}",
            Json.parseToJsonElement(requireNotNull(bodies[1])).jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals("synthetic@carbon.super", requests[2].url.queryParameter("username"))
        assertNull(bodies[2])
        for (request in requests.slice(1..2)) {
            assertEquals("Bearer ACCESS_TEST_ONLY", request.header("Authorization"))
            assertEquals("1.9.11.241103", request.header("Version"))
        }
        assertEquals(mapOf("client_id" to EnzonaAuthContract.CLIENT_ID, "grant_type" to "refresh_token",
            "refresh_token" to "REFRESH_TEST_ONLY"), form(requireNotNull(bodies[3])))
    }

    @Test fun failedRefreshIsReturnedWithoutAutomaticRetry() = runBlocking {
        var calls = 0
        val api = EnzonaSessionApi({ calls++; EnzonaHttpReply(401, "{\"error\":\"invalid_grant\"}") })
        val original = EnzonaTokens.fromResponse(syntheticTokenBody(), 1000)
        val error = assertFailsWith<EnzonaApiException> { api.refresh(original) }
        assertEquals(401, error.httpStatus)
        assertEquals(EnzonaOAuthFailure.INVALID_GRANT, error.oauthFailure)
        assertEquals("REFRESH_TEST_ONLY", original.refreshToken)
        assertEquals(1, calls)
    }

    @Test fun oauthErrorsStayDistinguishableWithoutKeepingTheirDescriptions() = runBlocking {
        for (failure in listOf(EnzonaOAuthFailure.INVALID_GRANT, EnzonaOAuthFailure.INVALID_CLIENT,
            EnzonaOAuthFailure.UNAUTHORIZED_CLIENT, EnzonaOAuthFailure.TEMPORARILY_UNAVAILABLE)) {
            val body = buildJsonObject {
                put("error", failure.name.lowercase()); put("error_description", "PRIVATE_SYNTHETIC_MARKER")
            }.toString()
            val error = assertFailsWith<EnzonaApiException> {
                EnzonaSessionApi({ EnzonaHttpReply(400, body) }).refresh(EnzonaTokens.fromResponse(syntheticTokenBody(), 1000))
            }
            assertEquals(failure, error.oauthFailure)
            assertEquals("ENZONA no pudo completar la solicitud", error.message)
            assertNull(error.cause)
        }
        val unknown = assertFailsWith<EnzonaApiException> {
            EnzonaSessionApi({ EnzonaHttpReply(500, "<html>") }).refresh(EnzonaTokens.fromResponse(syntheticTokenBody(), 1000))
        }
        assertEquals(EnzonaOAuthFailure.UNKNOWN, unknown.oauthFailure)
    }

    @Test fun malformedOrFaultAccountCannotLeakItsContentsThroughAnException() = runBlocking {
        for (body in listOf("{\"account\":{\"PRIVATE_SYNTHETIC_MARKER\":", "{\"fault\":{\"description\":\"PRIVATE_SYNTHETIC_MARKER\"}}",
            "{\"account\":{}}")) {
            val error = assertFailsWith<IllegalArgumentException> {
                EnzonaSessionApi({ EnzonaHttpReply(200, body) }).account(EnzonaTokens.fromResponse(syntheticTokenBody(), 1000))
            }
            assertEquals("La respuesta de la cuenta no es válida", error.message)
            assertNull(error.cause)
        }
    }
}
