package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequest
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Session renewal and financial recovery belong to the caller, never to HTTP retries. */
internal fun enzonaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

/**
 * The four money POSTs each open a connection of their own and close it after the reply: a pooled connection ENZONA
 * already closed would swallow the payment's body and leave it "Sin confirmar". Everything else shares the pool.
 */
internal class EnzonaTransport(
    private val client: OkHttpClient = enzonaHttpClient(),
    private val origins: Set<HttpUrl> = setOf(
        "https://api.enzona.net/".toHttpUrl(),
        "https://identity.enzona.net/".toHttpUrl(),
    ),
) : AutoCloseable {
    init {
        require(!client.retryOnConnectionFailure && !client.followRedirects && !client.followSslRedirects)
        require(client.authenticator === Authenticator.NONE && client.proxyAuthenticator === Authenticator.NONE)
        require(origins.isNotEmpty() && origins.all { it.isHttps && it.username.isEmpty() && it.password.isEmpty() })
    }

    // No idle connection is ever kept: each money POST connects anew.
    private val money = client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()

    fun newCall(request: Request): Call {
        val url = request.url
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            origins.any { it.scheme == url.scheme && it.host == url.host && it.port == url.port }) {
            "Destino de conexión no permitido"
        }
        val body = request.body
        val protectedRequest = if (body == null || body is SingleSendBody) request else
            request.newBuilder().method(request.method, SingleSendBody(body)).build()
        return (if (isMoney(request)) money else client).newCall(protectedRequest)
    }

    private fun isMoney(request: Request) = request.method == "POST" && request.url.encodedPath in setOf(
        EnzonaPaymentRequest.CARD, EnzonaPaymentRequest.ACCOUNT, EnzonaPaymentRequest.MERCHANT, EnzonaPaymentRequest.INVOICE)

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        money.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Also prevents server-driven follow-ups such as HTTP 503 + Retry-After: 0. */
internal class SingleSendBody(private val delegate: RequestBody) : RequestBody() {
    private val sent = AtomicBoolean(false)
    /** False while not one byte of the body was handed to the connection: the request never reached ENZONA. */
    val written: Boolean get() = sent.get()
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
        if (!sent.compareAndSet(false, true)) throw IOException("La solicitud ya se ha enviado")
        delegate.writeTo(sink)
    }
}

/** The request's body never left the phone (no connection, no TLS, or a failure before writing it): nothing reached ENZONA. */
internal class EnzonaNotDeliveredException(cause: IOException) : IOException("La solicitud no salió del teléfono", cause)
