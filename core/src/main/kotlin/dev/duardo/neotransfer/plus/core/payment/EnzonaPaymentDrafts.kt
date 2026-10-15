package dev.duardo.neotransfer.plus.core.payment

import dev.duardo.neotransfer.plus.core.service.EnzonaElectricityBill
import dev.duardo.neotransfer.plus.core.service.EnzonaEtecsaAccount
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import java.math.BigDecimal

/** Values captured for review. Payment credentials never belong to a draft. */
sealed interface EnzonaPaymentDraft {
    val fundingSourceUuid: String
    val amount: BigDecimal
    val currency: String
    val description: String?
}

data class EnzonaCardTransferDraft(
    override val fundingSourceUuid: String,
    val recipientPan: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val phone: String = "",
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaCardTransferDraft(redacted)"
}

/**
 * `funding_recipient_uuid` and `funding_recipient_username` come from the recipient model, never from
 * the source card. The only implemented source is an ACCOUNT QR resolution: the recipient lookup
 * endpoint for typing a username/phone is not reconstructed.
 */
data class EnzonaAccountTransferDraft(
    override val fundingSourceUuid: String,
    val fundingRecipientUuid: String,
    val fundingRecipientUsername: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaAccountTransferDraft(redacted)"
}

data class EnzonaQrMerchantPaymentDraft(
    override val fundingSourceUuid: String,
    val vendorIdentityCode: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val phone: String = "",
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaQrMerchantPaymentDraft(redacted)"
}

/** Invoice identity and reviewed values; currency/description are local review fields, not wire keys. */
data class EnzonaInvoicePaymentDraft(
    val service: EnzonaInvoiceService,
    val clientId: String,
    val owner: String,
    override val fundingSourceUuid: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val folio: String? = null,
    val route: String? = null,
    val period: String? = null,
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaInvoicePaymentDraft(redacted)"

    companion object {
        // setPaymentForm 0x854830 -> PayElectricBillUseCaseParams.toJson 0x70a328: the queried bill's amount.
        fun electricity(bill: EnzonaElectricityBill, fundingSourceUuid: String, currency: String) = EnzonaInvoicePaymentDraft(
            EnzonaInvoiceService.ELECTRICITY, identifier(bill.clientId), identifier(bill.owner), fundingSourceUuid,
            positive(bill.amount), currency, folio = identifier(bill.folio), route = identifier(bill.route),
            period = identifier(bill.period),
        )

        // Payment form 0x86e1b0 takes service_name (field_23), not number (field_2b); fixed phone pays to_pay.
        fun fixedPhone(account: EnzonaEtecsaAccount, fundingSourceUuid: String, currency: String): EnzonaInvoicePaymentDraft {
            require(account.service == EnzonaInvoiceService.FIXED_PHONE) { "La cuenta no es de telefonía fija" }
            return etecsa(account, account.toPay, fundingSourceUuid, currency)
        }

        // 0x8670ac: the entered amount, even when the lookup reports debt. No debt-payment endpoint demonstrated.
        fun nautaTopUp(account: EnzonaEtecsaAccount, amount: BigDecimal, fundingSourceUuid: String, currency: String): EnzonaInvoicePaymentDraft {
            require(account.service in setOf(EnzonaInvoiceService.NAUTA, EnzonaInvoiceService.NAUTA_HOME)) { "La cuenta no es Nauta" }
            return etecsa(account, amount, fundingSourceUuid, currency)
        }

        private fun etecsa(account: EnzonaEtecsaAccount, amount: BigDecimal?, card: String, currency: String): EnzonaInvoicePaymentDraft {
            require(account.isValid == "true") { "La consulta no confirma una cuenta válida" }
            val name = identifier(account.serviceName)
            return EnzonaInvoicePaymentDraft(requireNotNull(account.service), name, name, card, positive(amount), currency)
        }

        private fun identifier(value: String?): String {
            require(!value.isNullOrBlank() && value.none(Char::isISOControl)) { "Falta un dato del servicio" }
            return value
        }

        private fun positive(value: BigDecimal?): BigDecimal {
            require(value != null && value.signum() > 0) { "El importe debe ser mayor que cero" }
            return value
        }
    }
}
