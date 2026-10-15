package dev.duardo.neotransfer.plus.core.payment

import dev.duardo.neotransfer.plus.core.service.EnzonaServiceJson
import dev.duardo.neotransfer.plus.core.service.EnzonaServicePaymentResult
import dev.duardo.neotransfer.plus.core.service.EnzonaServicePaymentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Local journal states. UNKNOWN is "sin confirmar": never retried, reconciled against history. */
enum class EnzonaIntentState { PREPARED, SUBMITTING, UNKNOWN, CONFIRMED, REJECTED;
    val isTerminal: Boolean get() = this == CONFIRMED || this == REJECTED
}

/** Only decoded evidence; never retains a response body that could echo payment authorization. */
class EnzonaPaymentEvidence(
    val state: EnzonaIntentState,
    val httpStatus: Int,
    val statusCode: String?,
    val transactionUuid: String?,
    /** Distinct provider field. Not established as an account-history transaction identifier. */
    val uuid: String?,
    val observedAt: Long,
    val serviceResult: EnzonaServicePaymentResult? = null,
) {
    init { require(state in setOf(EnzonaIntentState.CONFIRMED, EnzonaIntentState.REJECTED, EnzonaIntentState.UNKNOWN)) }
    override fun toString() = "EnzonaPaymentEvidence(state=$state, privateFields=redacted)"
}

object EnzonaPaymentJson {
    /**
     * HTTP 200 alone proves nothing. Transfers confirm only on `status_code` "1111" (card transfers also
     * accept the traced camelCase `statusCode`); merchant payments never confirm from the reply; invoices
     * use their own family states. Everything else is UNKNOWN, never REJECTED by inference.
     */
    fun evidence(request: EnzonaPaymentRequest, httpStatus: Int, body: String, observedAt: Long): EnzonaPaymentEvidence {
        request.service?.let { service ->
            val result = EnzonaServiceJson.payment(service, httpStatus, body)
            val state = when (result.state) {
                EnzonaServicePaymentState.ACCEPTED -> EnzonaIntentState.CONFIRMED
                EnzonaServicePaymentState.FAILED -> EnzonaIntentState.REJECTED
                EnzonaServicePaymentState.PENDING, EnzonaServicePaymentState.UNKNOWN -> EnzonaIntentState.UNKNOWN
            }
            // charged_invoice.uuid identifies an invoice; it is not transaction_uuid from history.
            return EnzonaPaymentEvidence(state, httpStatus, result.statusCode, null, result.uuid, observedAt, result)
        }
        val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val error = root == null || listOf("fault", "error", "errors", "error_description").any { it in root }
        // Camel-case normalization is demonstrated for the card transfer decoder (0x7afcd0) only.
        val names = if (request.path == EnzonaPaymentRequest.CARD) listOf("statusCode", "status_code") else listOf("status_code")
        val codes = root?.let { obj -> names.filter { it in obj }.map { code(obj[it]) } }.orEmpty()
        val code = codes.firstOrNull()?.takeIf { candidate -> codes.all { it == candidate } }
        fun id(name: String) = (root?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        val confirmed = httpStatus == 200 && !error && request.path != EnzonaPaymentRequest.MERCHANT && code == "1111"
        return EnzonaPaymentEvidence(
            if (confirmed) EnzonaIntentState.CONFIRMED else EnzonaIntentState.UNKNOWN,
            httpStatus, code, id("transaction_uuid"), id("uuid"), observedAt,
        )
    }

    private fun code(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
        ?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
}
