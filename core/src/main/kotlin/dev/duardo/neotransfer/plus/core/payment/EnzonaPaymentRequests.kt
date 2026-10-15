package dev.duardo.neotransfer.plus.core.payment

import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Transient encoded body; submitting, session headers and persistence belong to the caller. */
class EnzonaPaymentRequest internal constructor(
    val path: String,
    val json: String,
    val service: EnzonaInvoiceService? = null,
) {
    val method: String = "POST"
    val url: String get() = "https://api.enzona.net$path"

    /** One of the four traced money endpoints, with the decoder family an invoice requires. */
    val isTraced: Boolean get() = if (path == INVOICE) service != null else service == null && path in setOf(CARD, ACCOUNT, MERCHANT)

    override fun toString(): String = "EnzonaPaymentRequest(redacted)"

    companion object {
        const val CARD = "/transfer/v1.0.0/transfer/card"
        const val ACCOUNT = "/transfer/v1.0.0/transfer/account"
        const val MERCHANT = "/payment/v1.0.0/payments/vendor/code"
        const val INVOICE = "/invoice/v1.0.0/invoice/payment"
    }
}

/** Password branch traced at 0x7af5dc, 0x8f08cc, 0x8c694c and the invoice forms in ENZONA 1.11.0. */
class EnzonaPaymentRequests(private val cipher: EnzonaCipher) {
    /** Consumes password: the supplied array is cleared on success and on every failure. */
    fun encode(draft: EnzonaPaymentDraft, password: CharArray): EnzonaPaymentRequest {
        try {
            require(draft.fundingSourceUuid.isNotBlank() && draft.fundingSourceUuid.none(Char::isISOControl)) {
                "Selecciona la tarjeta de origen"
            }
            require(draft.amount.signum() > 0) { "El importe debe ser mayor que cero" }
            require(draft.currency.isNotBlank() && draft.currency.none(Char::isISOControl)) { "La moneda de origen no es válida" }
            when (draft) {
                is EnzonaAccountTransferDraft -> require(draft.fundingRecipientUuid.isNotBlank() &&
                    draft.fundingRecipientUsername.isNotBlank()) { "Selecciona el destinatario" }
                is EnzonaQrMerchantPaymentDraft -> require(draft.vendorIdentityCode.isNotBlank()) { "El código de pago no es válido" }
                is EnzonaCardTransferDraft -> Unit
                is EnzonaInvoicePaymentDraft -> {
                    require(listOf(draft.clientId, draft.owner).all { it.isNotBlank() && it.none(Char::isISOControl) }) {
                        "Faltan datos de la factura"
                    }
                    if (draft.service == EnzonaInvoiceService.ELECTRICITY) {
                        require(listOf(draft.folio, draft.route, draft.period).all { !it.isNullOrBlank() && it.none(Char::isISOControl) }) {
                            "Faltan datos de la factura eléctrica"
                        }
                    } else require(draft.clientId == draft.owner) { "Los datos de la cuenta ETECSA no coinciden" }
                }
            }
            require(password.isNotEmpty()) { "La contraseña de pago está vacía" }
            val encryptedPan = if (draft is EnzonaCardTransferDraft) {
                val pan = draft.recipientPan.replace(" ", "")
                require(pan.length == 16 && pan.all { it in '0'..'9' }) { "La tarjeta destinataria debe contener 16 dígitos" }
                cipher.encryptAndClear(pan.toByteArray(StandardCharsets.UTF_8))
            } else null
            val encryptedPassword = cipher.encryptPassword(password)
            val body = buildJsonObject {
                put("amount", Amounts.wire(draft.amount))
                put("funding_source_uuid", draft.fundingSourceUuid)
                if (draft !is EnzonaInvoicePaymentDraft) {
                    put("description", draft.description.orEmpty())
                    put("currency", draft.currency)
                    // The APK's password branch keeps this empty field; no signature is generated.
                    put("fingerprint", "")
                }
                when (draft) {
                    is EnzonaCardTransferDraft -> {
                        put("pan", requireNotNull(encryptedPan))
                        put("phone", draft.phone)
                    }
                    is EnzonaAccountTransferDraft -> {
                        put("funding_recipient_username", draft.fundingRecipientUsername)
                        put("funding_recipient_uuid", draft.fundingRecipientUuid)
                        put("type_transfer", "")
                    }
                    is EnzonaQrMerchantPaymentDraft -> {
                        put("vendor_identity_code", draft.vendorIdentityCode)
                        put("cash_advance", false)
                        put("phone", draft.phone)
                    }
                    is EnzonaInvoicePaymentDraft -> {
                        put("client_id", draft.clientId)
                        put("owner", draft.owner)
                        if (draft.service == EnzonaInvoiceService.ELECTRICITY) {
                            // Integer code; folio/route travel as a JSON *string*, not a nested object.
                            put("service_code", 2222)
                            put("metadata", buildJsonObject {
                                put("folio", requireNotNull(draft.folio))
                                put("route", requireNotNull(draft.route))
                            }.toString())
                            put("period", requireNotNull(draft.period))
                        } else put("service_code", draft.service.code)
                    }
                }
                put("payment_password", encryptedPassword)
            }
            val path = when (draft) {
                is EnzonaCardTransferDraft -> EnzonaPaymentRequest.CARD
                is EnzonaAccountTransferDraft -> EnzonaPaymentRequest.ACCOUNT
                is EnzonaQrMerchantPaymentDraft -> EnzonaPaymentRequest.MERCHANT
                is EnzonaInvoicePaymentDraft -> EnzonaPaymentRequest.INVOICE
            }
            return EnzonaPaymentRequest(path, body.toString(), (draft as? EnzonaInvoicePaymentDraft)?.service)
        } finally {
            password.fill('\u0000')
        }
    }
}
