package dev.duardo.neotransfer.plus.core.payment

import dev.duardo.neotransfer.plus.core.service.EnzonaServiceJson
import dev.duardo.neotransfer.plus.core.service.EnzonaServicePaymentResult
import dev.duardo.neotransfer.plus.core.service.EnzonaServicePaymentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local journal states. UNKNOWN is "sin confirmar": never retried, reconciled against history. NOT_DELIVERED: the
 * request body never left the phone, so no money moved; terminal, and only a new reviewed intent can pay again.
 */
enum class EnzonaIntentState { PREPARED, SUBMITTING, UNKNOWN, CONFIRMED, REJECTED, NOT_DELIVERED;
    /** ENZONA's own decision. */
    val isTerminal: Boolean get() = this == CONFIRMED || this == REJECTED
}

/**
 * ENZONA's `fault`, as the app keeps it: the code (`code` or `cod`) and its words (`description`, else `message`),
 * without control characters and at most [MAX_TEXT] long. [fields] names the members it carried, for the log.
 */
class EnzonaPaymentFault(val code: String?, val text: String?, val fields: List<String>) {
    override fun toString() = "EnzonaPaymentFault(fields=$fields, privateFields=redacted)"

    companion object {
        const val MAX_TEXT = 200
        private val names = listOf("code", "cod", "type", "message", "description", "status_code", "transaction_uuid")

        fun of(fault: JsonObject?): EnzonaPaymentFault? = fault?.let { row ->
            fun text(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
            EnzonaPaymentFault(
                clean(text("code") ?: text("cod"), 32),
                clean(text("description"), MAX_TEXT) ?: clean(text("message"), MAX_TEXT),
                names.filter { it in row },
            )
        }

        /** Control characters become spaces, runs of spaces one; blank is null. */
        fun clean(value: String?, max: Int): String? = value
            ?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
            ?.replace(Regex("""\s+"""), " ")?.trim()?.take(max)?.trimEnd()?.takeIf(String::isNotEmpty)
    }
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
    /** ENZONA explained an error; with a 4xx it refused the payment unless it named a transaction ([EnzonaPaymentJson.evidence]). */
    val fault: EnzonaPaymentFault? = null,
) {
    init { require(state in setOf(EnzonaIntentState.CONFIRMED, EnzonaIntentState.REJECTED, EnzonaIntentState.UNKNOWN)) }
    override fun toString() = "EnzonaPaymentEvidence(state=$state, privateFields=redacted)"
}

object EnzonaPaymentJson {
    /**
     * HTTP 200 alone proves nothing. Transfers confirm only on `status_code` "1111" (card transfers also
     * accept the traced camelCase `statusCode`); merchant payments never confirm from the reply; invoices
     * use their own family states. A `fault` with a 4xx is ENZONA refusing the request, REJECTED, when it created no
     * transaction or names it rejected (`status_code` 1112); one that names a transaction stays UNKNOWN until the history
     * read that follows settles it by its uuid. Code, words, `status_code` and `transaction_uuid` are kept.
     */
    fun evidence(request: EnzonaPaymentRequest, httpStatus: Int, body: String, observedAt: Long): EnzonaPaymentEvidence {
        val root = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val faultRow = root?.get("fault") as? JsonObject
        val fault = EnzonaPaymentFault.of(faultRow)
        request.service?.let { service ->
            val result = EnzonaServiceJson.payment(service, httpStatus, body)
            val state = when (result.state) {
                EnzonaServicePaymentState.ACCEPTED -> EnzonaIntentState.CONFIRMED
                EnzonaServicePaymentState.FAILED -> EnzonaIntentState.REJECTED
                EnzonaServicePaymentState.PENDING, EnzonaServicePaymentState.UNKNOWN -> EnzonaIntentState.UNKNOWN
            }
            // charged_invoice.uuid identifies an invoice; it is not transaction_uuid from history.
            return EnzonaPaymentEvidence(state, httpStatus, result.statusCode, null, result.uuid, observedAt, result, fault)
        }
        val error = root == null || listOf("fault", "error", "errors", "error_description").any { it in root }
        // Camel-case normalization is demonstrated for the card transfer decoder (0x7afcd0) only.
        val names = if (request.path == EnzonaPaymentRequest.CARD) listOf("statusCode", "status_code") else listOf("status_code")
        val codes = root?.let { obj -> names.filter { it in obj }.map { code(obj[it]) } }.orEmpty()
        val code = codes.firstOrNull()?.takeIf { candidate -> codes.all { it == candidate } }
        fun id(row: JsonObject?, name: String) = (row?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        val confirmed = httpStatus == 200 && !error && request.path != EnzonaPaymentRequest.MERCHANT && code == "1111"
        val statusCode = code ?: faultRow?.let { code(it["status_code"]) }
        val transaction = id(root, "transaction_uuid") ?: id(faultRow, "transaction_uuid")
        val refused = fault != null && httpStatus in 400..499 && (transaction == null || statusCode == "1112")
        return EnzonaPaymentEvidence(
            when {
                confirmed -> EnzonaIntentState.CONFIRMED
                refused -> EnzonaIntentState.REJECTED
                else -> EnzonaIntentState.UNKNOWN
            },
            httpStatus, statusCode, transaction, id(root, "uuid"), observedAt, fault = fault,
        )
    }

    private fun code(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
        ?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
}
