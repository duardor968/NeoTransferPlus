package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaTransportTest {
    private fun withServer(block: (MockWebServer, EnzonaTransport) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory())
            server.start(InetAddress.getLoopbackAddress(), 0)
            val client = enzonaHttpClient().newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .callTimeout(3, TimeUnit.SECONDS).build()
            EnzonaTransport(client, setOf(server.url("/"))).use { transport -> block(server, transport) }
        }
    }

    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/synthetic-payment"))
        .header("Authorization", "Bearer synthetic-test-only")
        .post("{\"amount\":\"1.00\"}".toRequestBody("application/json".toMediaType())).build()

    @Test fun successfulRequestIsSentOnceOverTls() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().body("{\"reference\":\"SYNTHETIC\"}").build())
        transport.newCall(request(server)).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("{\"reference\":\"SYNTHETIC\"}", response.body.string())
        }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun serverRetryInstructionsDoNotRepeatMoneyRequests() {
        for (code in listOf(401, 408, 421, 503)) withServer { server, transport ->
            server.enqueue(MockResponse.Builder().code(code).addHeader("Retry-After", "0").build())
            server.enqueue(MockResponse.Builder().body("must not be requested").build())
            transport.newCall(request(server)).execute().use { assertEquals(code, it.code) }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS), "HTTP $code caused a second request")
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lostResponseDoesNotCauseASecondSend() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().body("must not be requested").build())
        assertFailsWith<IOException> { transport.newCall(request(server)).execute().close() }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun failedExchangesAreLoggedByRouteAndStatusOrErrorClassOnly() = withServer { server, transport ->
        val lines = collectingLog { lines ->
            val http = EnzonaHttp(transport)
            server.enqueue(MockResponse.Builder().code(503).body("PRIVATE_BODY").build())
            server.enqueue(MockResponse.Builder().body("{}").build())
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            runBlocking {
                assertEquals(503, http.execute(request(server)).status)
                assertEquals(200, http.execute(request(server)).status)
                assertFailsWith<IOException> { http.execute(request(server)) }
            }
            lines.toList()
        }
        assertEquals(2, lines.size, lines.toString())
        assertTrue(Regex("""http POST [\w.]+/\* -> 503 \(\d+ ms\)""").matches(lines[0]), lines[0])
        assertTrue(Regex("""http POST [\w.]+/\* -> \w+(<-\w+)? \(\d+ ms\)""").matches(lines[1]), lines[1])
        assertTrue(lines.none { "PRIVATE" in it || "synthetic" in it || "Bearer" in it || "amount" in it })
    }

    @Test fun aReadWhoseConnectionBrokeIsSentOnceMore() = withServer { server, transport ->
        val lines = collectingLog { lines ->
            val http = EnzonaHttp(transport)
            val read = Request.Builder().url(server.url("/synthetic-read")).build()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse.Builder().body("{}").build())
            runBlocking { assertEquals(200, http.execute(read).status) }
            assertEquals(2, server.requestCount)
            // Twice broken: the read fails, never a third send.
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse.Builder().body("must not be requested").build())
            runBlocking { assertFailsWith<IOException> { http.execute(read) } }
            assertEquals(4, server.requestCount)
            lines.toList()
        }
        assertEquals(4, lines.size, lines.toString())
        assertTrue(Regex("""http GET [\w.]+/\* -> retry ok \(\d+ ms\)""").matches(lines[1]), lines[1])
        assertTrue(lines[3].contains(" -> again "), lines[3])
    }

    @Test fun aReadRetriedIntoAnErrorSaysSo() = withServer { server, transport ->
        val lines = collectingLog { lines ->
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueue(MockResponse.Builder().code(503).body("PRIVATE").build())
            runBlocking { assertEquals(503, EnzonaHttp(transport).execute(Request.Builder().url(server.url("/synthetic-read")).build()).status) }
            lines.toList()
        }
        assertEquals(2, lines.size, lines.toString())
        assertTrue(Regex("""http GET [\w.]+/\* -> again 503 \(\d+ ms\)""").matches(lines[1]), lines[1])
    }

    @Test fun moneyPostsOpenAConnectionOfTheirOwnAndReadsKeepThePool() = withServer { server, transport ->
        val read = Request.Builder().url(server.url("/synthetic-read")).build()
        val money = Request.Builder().url(server.url(EnzonaPaymentRequest.CARD))
            .post("{\"amount\":\"1.00\"}".toRequestBody("application/json".toMediaType())).build()
        repeat(5) { server.enqueue(MockResponse.Builder().body("{}").build()) }
        for (request in listOf(read, money, read, money, read)) transport.newCall(request).execute().use { assertEquals(200, it.code) }
        val connections = List(5) { server.takeRequest(1, TimeUnit.SECONDS)!!.connectionIndex }
        assertEquals(setOf(connections[0]), setOf(connections[0], connections[2], connections[4]))
        assertEquals(3, connections.toSet().size, connections.toString())
    }

    @Test fun aBodyThatNeverLeftThePhoneIsReportedAsNotDelivered() {
        // A port nobody listens on: the connection is refused before TLS, let alone the body.
        val closed = MockWebServer().apply { start(InetAddress.getLoopbackAddress(), 0) }
        val origin = "https://${closed.hostName}:${closed.port}/".toHttpUrl()
        closed.close()
        val url = origin.resolve(EnzonaPaymentRequest.CARD)!!
        val client = enzonaHttpClient().newBuilder().connectTimeout(2, TimeUnit.SECONDS).build()
        EnzonaHttp(EnzonaTransport(client, setOf(origin))).use { http ->
            val sent = Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()
            val failure = assertFailsWith<EnzonaNotDeliveredException> { runBlocking { http.execute(sent) } }
            assertTrue(failure.cause is ConnectException, failure.cause.toString())
            // A read has no body to keep: its failure stays what it was.
            val read = assertFailsWith<IOException> { runBlocking { http.execute(Request.Builder().url(url).build()) } }
            assertTrue(read !is EnzonaNotDeliveredException)
        }
    }

    @Test fun redirectCannotForwardCredentialsOrRepeatTheRequest() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/other-path").build())
        server.enqueue(MockResponse.Builder().body("must not be requested").build())
        transport.newCall(request(server)).execute().use { assertEquals(307, it.code) }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    @Test fun originValidationRejectsLookalikesCleartextAndUserInfoBeforeConnecting() {
        EnzonaTransport().use { transport ->
            for (url in listOf("http://api.enzona.net/", "https://api.enzona.net.evil.example/",
                "https://evil.example/", "https://api.enzona.net:8443/", "https://user@api.enzona.net/",
                "https://api.enzona.net/#secret")) {
                assertFailsWith<IllegalArgumentException> { transport.newCall(Request.Builder().url(url).build()) }
            }
        }
    }

    @Test fun oneShotBodyRejectsReuseEvenOutsideHttpRecovery() {
        val body = SingleSendBody("synthetic".toRequestBody())
        val first = Buffer()
        body.writeTo(first)
        assertEquals("synthetic", first.readUtf8())
        assertFailsWith<IOException> { body.writeTo(Buffer()) }
    }
}
