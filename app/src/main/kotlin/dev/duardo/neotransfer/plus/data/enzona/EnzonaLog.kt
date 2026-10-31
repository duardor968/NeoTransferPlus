package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.read.EnzonaShapeException
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceException
import okhttp3.HttpUrl

/**
 * Diagnostics in logcat (`adb logcat -s NeoTransferPlus`): why a read or a payment failed and why the session was
 * lost or asks to sign in again. Lines carry kinds, exception class names, HTTP statuses, OAuth error codes, masked
 * endpoints and durations only: never tokens, headers, bodies, queries, account names, card numbers or amounts.
 */
internal object EnzonaLog {
    const val TAG = "NeoTransferPlus"

    /** Installed by the process graph. JVM tests leave it unset or collect the lines. */
    @Volatile var sink: ((warning: Boolean, line: String) -> Unit)? = null

    fun info(line: String) { sink?.invoke(false, line) }
    fun warn(line: String) { sink?.invoke(true, line) }

    /**
     * The exception's class and its cause's class. Never the message, which can quote a URL or a reply, except a reply
     * shape's: it names the field the decoders refused.
     */
    fun error(failure: Throwable): String {
        val name = when (failure) {
            is EnzonaSessionUnavailableException -> "EnzonaSessionUnavailableException(${failure.failure})"
            is EnzonaShapeException -> "EnzonaShapeException(${failure.message})"
            else -> failure.javaClass.simpleName
        }
        val cause = failure.cause?.takeIf { it !== failure }?.javaClass?.simpleName
        return if (cause == null) name else "$name<-$cause"
    }

    /** ` http=400 oauth=invalid_grant` for an ENZONA error reply, empty otherwise. */
    fun reply(failure: Throwable): String = when (failure) {
        is EnzonaApiException -> " http=${failure.httpStatus}" + (failure.oauthFailure?.let { " oauth=${it.name.lowercase()}" } ?: "")
        is EnzonaServiceException -> " http=${failure.httpStatus}"
        else -> ""
    }

    /** Method, host and path with its identifiers printed as `*` (usernames, card UUIDs, QR codes); never the query. */
    fun endpoint(method: String, url: HttpUrl): String =
        "$method ${url.host}/" + url.pathSegments.joinToString("/") { if (it in knownSegments) it else "*" }

    /** How the access token stands now: `access_expires_in=40s`, `access_expired_ago=25h12m` or `access_rejected`. */
    fun access(tokens: EnzonaTokens, nowEpochSeconds: Long): String {
        val left = tokens.expiresAtEpochSeconds - nowEpochSeconds
        return when {
            // EnzonaSession.expire sets 0 after ENZONA refused the access token.
            tokens.expiresAtEpochSeconds == 0L -> "access_rejected"
            left >= 0 -> "access_expires_in=${duration(left)}"
            else -> "access_expired_ago=${duration(-left)}"
        }
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3600}h${seconds % 3600 / 60}m"
    }

    /** Literal path segments of the traced endpoints; anything else (usernames, card UUIDs, QR codes) prints as `*`. */
    private val knownSegments = setOf(
        "account", "api", "authentication", "authorize", "balance", "banks", "card", "cards", "code", "coordinates", "coremz",
        "default", "delete", "invoice", "lastOperations", "logout", "nomenclator", "oauth2", "oidc", "operations", "payment",
        "payments", "qr", "search", "token", "transfer", "username", "vendor", "v1.0.0", "1.0.2",
    )
}
