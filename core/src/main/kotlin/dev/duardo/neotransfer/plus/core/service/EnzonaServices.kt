package dev.duardo.neotransfer.plus.core.service

import java.io.IOException
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Only service codes connected to request constructors in ENZONA code20003. Electricity's code is
 * an integer on the payment wire; ETECSA codes are strings. Water and Cubacel have no contract here.
 */
enum class EnzonaInvoiceService(val code: String, val responseType: String?) {
    ELECTRICITY("2222", null), FIXED_PHONE("4441", "tb"), NAUTA("4443", "nauta"), NAUTA_HOME("4445", "adsl_nauta"),
}

data class EnzonaElectricityBill(
    val clientId: String?, val owner: String?, val folio: String?, val route: String?,
    val period: String?, val amount: BigDecimal?, val totalAmount: BigDecimal?,
    val charged: Boolean?, val original: JsonObject,
) { override fun toString() = "EnzonaElectricityBill(redacted)" }

/** Debt fields are reported, never paid by inference: recharging is not settling a debt. */
data class EnzonaEtecsaAccount(
    val serviceType: String?, val serviceName: String?, val number: String?, val isValid: String?,
    val toPay: BigDecimal?, val currentDebt: BigDecimal?, val unpaidDebt: BigDecimal?,
    val currentUnpaidDebt: BigDecimal?, val minimumAmount: BigDecimal?, val maximumAmount: BigDecimal?,
    val original: JsonObject,
) {
    val service: EnzonaInvoiceService? get() = EnzonaInvoiceService.entries.firstOrNull {
        it.responseType != null && it.responseType == serviceType
    }
    override fun toString() = "EnzonaEtecsaAccount(redacted)"
}

data class EnzonaEtecsaQuery(
    val resultMessage: String?, val success: String?, val name: String?, val accounts: List<EnzonaEtecsaAccount>,
) { override fun toString() = "EnzonaEtecsaQuery(redacted)" }

/** A terminal UI status is evidence of the provider response, not an independently reconciled debit. */
enum class EnzonaServicePaymentState { ACCEPTED, PENDING, FAILED, UNKNOWN }

data class EnzonaServiceFault(val code: String?, val type: String?, val message: String?, val description: String?) {
    override fun toString() = "EnzonaServiceFault(redacted)"
}

class EnzonaServiceException(val httpStatus: Int, val fault: EnzonaServiceFault?) :
    IOException("ENZONA no pudo consultar el servicio")

/** No raw payment response retained: providers can echo authorization fields. */
data class EnzonaServicePaymentResult(
    val state: EnzonaServicePaymentState, val httpStatus: Int, val statusCode: String?, val status: String?,
    val uuid: String?, val invoiceNumber: String?, val passwordIntent: Boolean, val fault: EnzonaServiceFault?,
) { override fun toString() = "EnzonaServicePaymentResult(state=$state, privateFields=redacted)" }

/** Explicit read envelopes and payment states from the AOT; no generic wrapper fallback. */
object EnzonaServiceJson {
    // getElectricMonthBill 0x70f8bc: {"client":[{"invoice":[...]}]}.
    fun electricityBills(body: String): List<EnzonaElectricityBill> = serviceEnvelope(body).serviceRows("client")
        .flatMap { it.serviceRows("invoice") }.map { row ->
            EnzonaElectricityBill(row.serviceString("client_id"), row.serviceString("owner"), row.serviceString("folio"),
                row.serviceString("route"), row.serviceText("period"), row.serviceDecimal("amount"), row.serviceDecimal("total_amount"),
                row.serviceText("charged")?.let { value -> when (value) { "true" -> true; "false" -> false; else -> null } }, row)
        }

    fun etecsaAccounts(body: String): EnzonaEtecsaQuery {
        val root = serviceEnvelope(body)
        val data = root["datalist"] as? JsonObject ?: throw IllegalArgumentException("Falta datalist")
        return EnzonaEtecsaQuery(root.serviceString("resultmsg"), root.serviceString("success"), root.serviceString("name"),
            data.serviceRows("data").map { row ->
                EnzonaEtecsaAccount(row.serviceString("service_type"), row.serviceString("service_name"), row.serviceString("number"),
                    row.serviceString("is_valid"), row.serviceDecimal("to_pay"), row.serviceDecimal("current_debt"),
                    row.serviceDecimal("unpaid_debt"), row.serviceDecimal("current_unpaid_debt"),
                    row.serviceDecimal("minimum_amount"), row.serviceDecimal("maximum_amount"), row)
            })
    }

    fun payment(service: EnzonaInvoiceService, httpStatus: Int, body: String): EnzonaServicePaymentResult {
        val root = serviceObject(body)
        val fault = fault(body)
        val invoice = root?.get("charged_invoice") as? JsonObject
        // AOT defaults absent/null to an empty map (0x700a14, 0x869b00), not a password error.
        val intents = listOf(root?.get("getPaymentPasswordIntent"), invoice?.get("getPaymentPasswordIntent"))
        val intent = intents.any { it is JsonObject && it.isNotEmpty() }
        val malformedIntent = intents.any { it != null && it != JsonNull && it !is JsonObject }
        val code = invoice?.serviceText("status_code")
        val status = invoice?.serviceText("status")
        val error = root == null || listOf("fault", "error", "errors", "error_description").any { it in root }
        val state = when {
            httpStatus != 200 || error || intent || malformedIntent || invoice == null -> EnzonaServicePaymentState.UNKNOWN
            service == EnzonaInvoiceService.ELECTRICITY -> {
                val integer = (invoice["status_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (integer == 1111 && status !in setOf("Pendiente", "Fallida")) EnzonaServicePaymentState.ACCEPTED else EnzonaServicePaymentState.UNKNOWN
            }
            status == "Aceptada" -> EnzonaServicePaymentState.ACCEPTED
            status == "Pendiente" -> EnzonaServicePaymentState.PENDING
            status == "Fallida" -> EnzonaServicePaymentState.FAILED
            else -> EnzonaServicePaymentState.UNKNOWN
        }
        return EnzonaServicePaymentResult(state, httpStatus, code, status, invoice?.serviceString("uuid"),
            invoice?.serviceText("invoice_number"), intent, fault)
    }

    fun fault(body: String): EnzonaServiceFault? = (serviceObject(body)?.get("fault") as? JsonObject)?.let { row ->
        EnzonaServiceFault(row.serviceText("code") ?: row.serviceText("cod"), row.serviceString("type"),
            row.serviceString("message"), row.serviceString("description"))
    }
}

private fun serviceObject(body: String): JsonObject? = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
private fun serviceEnvelope(body: String): JsonObject {
    val root = serviceObject(body) ?: throw IllegalArgumentException("Respuesta de servicio no válida")
    require(listOf("fault", "error", "errors", "error_description").none { it in root }) { "La respuesta contiene un error de servicio" }
    return root
}
private fun JsonObject.serviceRows(key: String): List<JsonObject> {
    val rows = this[key] as? JsonArray ?: throw IllegalArgumentException("Lista de servicio ausente o no válida")
    return rows.map { it as? JsonObject ?: throw IllegalArgumentException("Elemento de servicio no válido") }
}
private fun JsonObject.serviceString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.serviceText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
private fun JsonObject.serviceDecimal(key: String): BigDecimal? {
    val value: JsonElement = this[key]?.takeIf { it != JsonNull } ?: return null
    val text = (value as? JsonPrimitive)?.content ?: throw IllegalArgumentException("Importe de servicio no válido")
    return text.toBigDecimalOrNull() ?: throw IllegalArgumentException("Importe de servicio no válido")
}
