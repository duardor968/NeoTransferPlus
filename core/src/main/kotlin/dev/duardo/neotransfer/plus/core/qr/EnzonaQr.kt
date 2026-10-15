package dev.duardo.neotransfer.plus.core.qr

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EnzonaQrCode {
    /**
     * Raw codes from the provider scanner start with `01` (friend/account), `05` (ENZONA account)
     * or `07` (merchant). The prefix only routes a candidate to remote resolution: it proves neither
     * issuer nor authenticity, and URLs are never mined for codes.
     */
    fun isCandidate(value: String): Boolean = value.length in 3..16_384 &&
        listOf("01", "05", "07").any(value::startsWith) && value.none(Char::isISOControl)
}

enum class EnzonaQrKind { ACCOUNT, MERCHANT_PAYMENT, MERCHANT_INFO }

/** Resolution is information for review, never a payment authorization or an automatic draft. */
class EnzonaResolvedQr(
    val ownerSubject: String,
    val kind: EnzonaQrKind,
    val code: String,
    val vendorIdentityCode: String?,
    /** For [EnzonaQrKind.ACCOUNT]: the provider `uuid`, used as `funding_recipient_uuid`. */
    val accountUuid: String?,
    val username: String?,
    val merchantUuid: String?,
    val name: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val description: String?,
    val requirePassword: JsonElement?,
    val observedAt: Long,
) {
    override fun toString() = "EnzonaResolvedQr(kind=$kind, privateFields=redacted)"
}

/** Server-issued receive code; render [code] literally, never a locally synthesized one. */
class EnzonaOwnQr(
    val ownerSubject: String, val code: String, val amount: BigDecimal?,
    val currency: String?, val description: String?, val observedAt: Long,
) {
    override fun toString() = "EnzonaOwnQr(redacted)"
}

class EnzonaQrResponseException : IllegalArgumentException("La respuesta QR de ENZONA no es válida")

/** Decoders for GET /qr/v1.0.0/qr/{code} (0x73304c) and POST /qr/v1.0.0/qr/account (0x8b66e4). */
object EnzonaQrJson {
    fun resolved(body: String, scannedCode: String, ownerSubject: String, observedAt: Long): EnzonaResolvedQr {
        val root = envelope(body)
        return decode {
            val vendor = root.text("vendor_identity_code")
            val qr = root.text("qr_code")
            val merchant = root.text("merchant_uuid")
            val username = root.text("username")
            val kind = when {
                merchant != null && qr?.startsWith("07") == true -> EnzonaQrKind.MERCHANT_PAYMENT
                root["owners"] is JsonArray -> EnzonaQrKind.MERCHANT_INFO
                merchant != null -> throw EnzonaQrResponseException()
                vendor?.startsWith("01") == true || vendor?.startsWith("05") == true || username != null -> EnzonaQrKind.ACCOUNT
                else -> throw EnzonaQrResponseException()
            }
            EnzonaResolvedQr(ownerSubject, kind, qr ?: vendor ?: scannedCode, vendor, root.text("uuid"), username, merchant,
                root.text("name") ?: root.text("merchant_name"), root.amount(), root.text("currency"),
                root.text("description"), root["require_password"], observedAt)
        }
    }

    fun ownQr(body: String, ownerSubject: String, observedAt: Long): EnzonaOwnQr {
        val root = envelope(body)
        return decode {
            EnzonaOwnQr(ownerSubject, requireNotNull(root.text("vendor_identity_code")), root.amount(),
                root.text("currency"), root.text("description"), observedAt)
        }
    }

    private fun envelope(body: String): JsonObject = decode {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw EnzonaQrResponseException()
        require(listOf("fault", "error", "errors", "error_description").none { it in root })
        root
    }

    private fun JsonObject.text(key: String): String? {
        val value = get(key) ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString)
        return value.content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.amount(): BigDecimal? {
        val value = get("amount") ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive)
        return value.content.toBigDecimalOrNull() ?: throw EnzonaQrResponseException()
    }

    // Parser diagnostics may quote the body: replace every decoding failure, without a cause.
    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw EnzonaQrResponseException() }
}
