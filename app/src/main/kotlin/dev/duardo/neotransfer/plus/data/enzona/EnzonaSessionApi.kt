package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaAuthContract
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.read.EnzonaAccount
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Requests traced in ENZONA 1.11.0; server acceptance remains a separate device check. */
internal class EnzonaSessionApi(
    private val execute: EnzonaExecute,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens = token(exchange.tokenRequest, null)

    suspend fun refresh(previous: EnzonaTokens): EnzonaTokens = token(
        EnzonaAuthContract.refreshTokenRequest(requireNotNull(previous.refreshToken) { "Vuelve a iniciar sesión" }), previous,
    )

    private suspend fun token(form: EnzonaAuthContract.FormRequest, previous: EnzonaTokens?): EnzonaTokens {
        // Copied from the APK token call. These are response-header names sent on a request; the
        // server requiring them is not demonstrated, and dropping them is not demonstrated harmless.
        val request = Request.Builder().url(form.endpoint.toString())
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Credentials", "true")
            .header("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS")
            .header("Access-Control-Allow-Headers", "X-Requested-With, Origin, Content-Type, X-Auth-Token")
            .post(form.body.toRequestBody(form.contentType.toMediaType())).build()
        val response = execute(request)
        if (response.status != 200) throw EnzonaApiException(response.status, oauthFailure(response.body))
        return EnzonaTokens.fromResponse(response.body, nowEpochSeconds(), previous)
    }

    suspend fun account(tokens: EnzonaTokens): EnzonaFetched<EnzonaAccount> {
        // The literal id_token `sub`, suffixes included; never an email/phone/UUID substitute.
        val url = "https://api.enzona.net/account/v1.0.0/account".toHttpUrl().newBuilder()
            .addQueryParameter("username", tokens.accountSubject()).build()
        val response = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (response.status != 200) {
            throw EnzonaApiException(response.status, invalidCredentials = response.isInvalidCredentials)
        }
        val account = try {
            EnzonaReadJson.account(response.body)
        } catch (_: Exception) {
            // JSON diagnostics may quote private response contents.
            throw IllegalArgumentException("La respuesta de la cuenta no es válida")
        }
        return EnzonaFetched(account, response.body, nowEpochSeconds() * 1000)
    }

    suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) {
        // The traced client wraps Dart Map.toString(), not a nested JSON device object.
        val deviceText = fields.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${it.value}" }
        val body = buildJsonObject { put("data", deviceText) }.toString()
        val request = enzonaApiRequest("https://api.enzona.net/account/v1.0.0/account/authentication", tokens)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = execute(request)
        if (response.status != 200) throw EnzonaApiException(response.status)
    }

    private fun oauthFailure(body: String): EnzonaOAuthFailure = try {
        val value = ((Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        EnzonaOAuthFailure.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == value } ?: EnzonaOAuthFailure.UNKNOWN
    } catch (_: Exception) { EnzonaOAuthFailure.UNKNOWN }
}
