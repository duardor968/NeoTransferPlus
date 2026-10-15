package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal class EnzonaHttp(private val transport: EnzonaTransport = EnzonaTransport()) : AutoCloseable {
    suspend fun execute(request: Request): EnzonaHttpReply = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        // Read on OkHttp's worker, not the presentation thread; retain no HTTP cache.
                        val source = it.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("La respuesta supera el tamaño admitido")
                        EnzonaHttpReply(it.code, source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(reply))
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    override fun close() = transport.close()

    private companion object { const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024 }
}

/** One HTTP execution. Production passes [EnzonaHttp.execute]; tests pass a synthetic reply. */
internal typealias EnzonaExecute = suspend (Request) -> EnzonaHttpReply

internal class EnzonaHttpReply(val status: Int, val body: String) {
    override fun toString(): String = "EnzonaHttpReply(status=$status, body=redacted)"

    /** APK refresh trigger (0x995144): HTTP 401 whose fault *description* is "Invalid Credentials". */
    val isInvalidCredentials: Boolean get() = status == 401 && try {
        val fault = (Json.parseToJsonElement(body) as? JsonObject)?.get("fault") as? JsonObject
        (fault?.get("description") as? JsonPrimitive)?.content == "Invalid Credentials"
    } catch (_: IllegalArgumentException) { false }
}

internal enum class EnzonaOAuthFailure {
    INVALID_GRANT, INVALID_CLIENT, INVALID_REQUEST, INVALID_SCOPE, UNAUTHORIZED_CLIENT,
    UNSUPPORTED_GRANT_TYPE, ACCESS_DENIED, TEMPORARILY_UNAVAILABLE, SERVER_ERROR, UNKNOWN,
}

internal class EnzonaApiException(
    val httpStatus: Int,
    val oauthFailure: EnzonaOAuthFailure? = null,
    /** The access token was rejected; the session should renew before the next request. */
    val invalidCredentials: Boolean = false,
) : IOException("ENZONA no pudo completar la solicitud")

/** Fetched value with the exact body, for the sanitized local cache. */
internal class EnzonaFetched<T>(val value: T, val json: String, val observedAt: Long) {
    override fun toString(): String = "EnzonaFetched(redacted)"
}

internal fun enzonaApiRequest(url: String, tokens: EnzonaTokens): Request.Builder = Request.Builder().url(url)
    .header("Authorization", "Bearer ${tokens.accessToken}")
    .header("accept", "application/json")
    // Traced header value (getHttpDefaulHeader 0x6a9d88), distinct from the APK version 1.11.0.
    .header("Version", "1.9.11.241103")
