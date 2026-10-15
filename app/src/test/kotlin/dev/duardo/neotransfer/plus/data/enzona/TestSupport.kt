package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okio.Buffer

/** Synthetic tokens only; the JWT signature is never verified by the client. */
internal fun syntheticTokens(subject: String = "synthetic@carbon.super", access: String = "SYNTHETIC_ACCESS",
    refresh: String? = null, expiry: Long = Long.MAX_VALUE): EnzonaTokens {
    val payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(buildJsonObject { put("sub", subject) }.toString().toByteArray())
    return EnzonaTokens(access, refresh, "header.$payload.signature", expiry)
}

internal fun syntheticTokenBody(subject: String = "synthetic@carbon.super", access: String = "ACCESS_TEST_ONLY") = buildJsonObject {
    put("access_token", access)
    put("refresh_token", "REFRESH_TEST_ONLY")
    put("id_token", syntheticTokens(subject).idToken)
    put("token_type", "Bearer")
    put("expires_in", 3600)
}.toString()

internal fun Request.bodyText(): String? = body?.let { body -> Buffer().also(body::writeTo).readUtf8() }
