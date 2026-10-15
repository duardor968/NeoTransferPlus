package dev.duardo.neotransfer.plus.core.auth

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Never include this object's contents in logs, state snapshots or user backups. */
class EnzonaTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String = "EnzonaTokens(redacted)"

    fun needsRefresh(nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds - nowEpochSeconds <= 30

    /** Routing hint received over the token backchannel; not an independently verified identity. */
    fun accountSubject(): String = try {
        val parts = idToken.split('.')
        require(parts.size == 3 && parts.all(String::isNotEmpty))
        val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)) as JsonObject
        val subject = payload["sub"] as JsonPrimitive
        require(subject.isString && subject.content.isNotBlank() && subject.content.none(Char::isISOControl))
        subject.content
    } catch (_: Exception) {
        throw IllegalArgumentException("La respuesta de acceso no identifica la cuenta")
    }

    fun forEncryptedStorage(): String = buildJsonObject {
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("id_token", idToken)
        put("expires_at", expiresAtEpochSeconds)
    }.toString()

    companion object {
        /** Token endpoint body; [previous] supplies the identity kept across a refresh. */
        fun fromResponse(json: String, nowEpochSeconds: Long, previous: EnzonaTokens? = null): EnzonaTokens = try {
            val value = Json.parseToJsonElement(json) as JsonObject
            require(value["error"].isAbsent() && value["error_description"].isAbsent())
            val access = value.requiredString("access_token")
            require(access.none(Char::isWhitespace))
            require(value.requiredString("token_type").equals("Bearer", ignoreCase = true))
            val lifetime = value.exactLong("expires_in")
            require(lifetime > 0 && nowEpochSeconds >= 0)
            val identity = value.optionalString("id_token") ?: requireNotNull(previous?.idToken)
            val refresh = value.optionalString("refresh_token") ?: previous?.refreshToken
            EnzonaTokens(access, refresh, identity, Math.addExact(nowEpochSeconds, lifetime)).also {
                val subject = it.accountSubject()
                if (previous != null) require(subject == previous.accountSubject())
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("La respuesta de acceso no es válida")
        }

        fun fromEncryptedStorage(json: String): EnzonaTokens = try {
            val value = Json.parseToJsonElement(json) as JsonObject
            val access = value.requiredString("access_token")
            require(access.none(Char::isWhitespace))
            val expiry = value.exactLong("expires_at")
            require(expiry > 0)
            EnzonaTokens(access, value.optionalString("refresh_token"), value.requiredString("id_token"), expiry)
                .also { it.accountSubject() }
        } catch (_: Exception) {
            throw IllegalArgumentException("No se pudo recuperar la sesión")
        }

        private fun Any?.isAbsent() = this == null || this == JsonNull

        private fun JsonObject.requiredString(name: String): String = requireNotNull(optionalString(name))

        private fun JsonObject.optionalString(name: String): String? {
            val item = this[name]
            if (item.isAbsent()) return null
            require(item is JsonPrimitive && item.isString && item.content.isNotBlank() && item.content.none(Char::isISOControl))
            return item.content
        }

        /** JSON integer only: strings, booleans and decimals never become a lifetime. */
        private fun JsonObject.exactLong(name: String): Long {
            val item = this[name]
            require(item is JsonPrimitive && !item.isString && item != JsonNull)
            return requireNotNull(item.content.toLongOrNull())
        }
    }
}
