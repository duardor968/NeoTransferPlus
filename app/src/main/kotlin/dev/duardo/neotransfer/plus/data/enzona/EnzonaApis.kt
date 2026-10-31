package dev.duardo.neotransfer.plus.data.enzona

import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import dev.duardo.neotransfer.plus.core.card.EnzonaBank
import dev.duardo.neotransfer.plus.core.card.EnzonaCardJson
import dev.duardo.neotransfer.plus.core.card.EnzonaCardRequest
import dev.duardo.neotransfer.plus.core.card.EnzonaCoordinates
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentEvidence
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentJson
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentRequest
import dev.duardo.neotransfer.plus.core.qr.EnzonaOwnQr
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrCode
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrJson
import dev.duardo.neotransfer.plus.core.qr.EnzonaResolvedQr
import dev.duardo.neotransfer.plus.core.read.EnzonaCardBalance
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.core.read.EnzonaRemoteCard
import dev.duardo.neotransfer.plus.core.read.EnzonaTransactionPage
import dev.duardo.neotransfer.plus.core.service.EnzonaElectricityBill
import dev.duardo.neotransfer.plus.core.service.EnzonaEtecsaQuery
import dev.duardo.neotransfer.plus.core.service.EnzonaInvoiceService
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceException
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceJson
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_UTF8 = "application/json; charset=utf-8".toMediaType()

private fun api(vararg segments: String): HttpUrl = "https://api.enzona.net/".toHttpUrl().newBuilder().apply {
    segments.forEach(::addPathSegment)
}.build()

private fun identifier(value: String): String {
    require(value.isNotBlank() && value !in setOf(".", "..") && value.none(Char::isISOControl)) { "Identificador no válido" }
    return value
}

private fun checkOwner(tokens: EnzonaTokens, owner: String) =
    require(owner.isNotBlank() && tokens.accountSubject() == owner) { "La cuenta de ENZONA cambió" }

/** Cards, balance + last bank operations, and ENZONA account history. GET only. */
internal class EnzonaReadApi(private val execute: EnzonaExecute, private val now: () -> Long = { Instant.now().toEpochMilli() }) {
    suspend fun cards(tokens: EnzonaTokens): EnzonaFetched<List<EnzonaRemoteCard>> =
        fetch(api("coremz", "1.0.2", "card", "username", identifier(tokens.accountSubject())), tokens, EnzonaReadJson::cards)

    suspend fun balance(tokens: EnzonaTokens, cardUuid: String): EnzonaFetched<EnzonaCardBalance> =
        fetch(api("coremz", "1.0.2", "api", "balance", "lastOperations", identifier(cardUuid)), tokens, EnzonaReadJson::balance)

    /**
     * Main history flow (TransactionControllerNew): limit 100000, offset 0, `yyyy-MM-dd` dates. Like the APK,
     * pagination links are not followed; their shape is not established by the AOT.
     */
    suspend fun transactions(
        tokens: EnzonaTokens, from: LocalDate, through: LocalDate, offset: Int = 0, limit: Int = 100_000,
    ): EnzonaFetched<EnzonaTransactionPage> {
        require(!from.isAfter(through) && offset >= 0 && limit > 0)
        val url = api("operations", "v1.0.0", "operations", "account").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("start_date_filter", from.toString())
            .addQueryParameter("end_date_filter", through.toString()).build()
        return fetch(url, tokens, EnzonaReadJson::transactions)
    }

    private suspend fun <T> fetch(url: HttpUrl, tokens: EnzonaTokens, decode: (String) -> T): EnzonaFetched<T> {
        val response = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (response.status != 200) throw EnzonaApiException(response.status, invalidCredentials = response.isInvalidCredentials)
        return EnzonaFetched(decode(response.body), response.body, now())
    }
}

internal class EnzonaQrApi(private val execute: EnzonaExecute, private val now: () -> Long = { Instant.now().toEpochMilli() }) {
    /** Resolution only: GET with `cash_advance=false`. It never pays or prepares a payment. */
    suspend fun resolve(tokens: EnzonaTokens, ownerSubject: String, code: String): EnzonaResolvedQr {
        checkOwner(tokens, ownerSubject)
        require(EnzonaQrCode.isCandidate(code)) { "Código QR no compatible con ENZONA" }
        val url = "https://api.enzona.net/qr/v1.0.0/qr/".toHttpUrl().newBuilder()
            .addPathSegment(identifier(code)).addQueryParameter("cash_advance", "false").build()
        val reply = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (reply.status != 200) throw EnzonaApiException(reply.status, invalidCredentials = reply.isInvalidCredentials)
        return EnzonaQrJson.resolved(reply.body, code, ownerSubject, now())
    }

    /**
     * Creates a server-issued receive code; it does not submit any transfer/payment. Without [amount] the key is left
     * out and the payer types it (not yet checked against the server).
     */
    suspend fun createOwn(tokens: EnzonaTokens, ownerSubject: String, amount: BigDecimal?, currency: String,
        description: String? = null): EnzonaOwnQr {
        checkOwner(tokens, ownerSubject)
        require((amount == null || amount.signum() > 0) && currency.isNotBlank() && currency.none(Char::isISOControl)) { "Importe o moneda no válido" }
        // AddQrCodeWithAmountDescriptionUseCaseParams.toJson 0x8b72dc: amount as a string.
        val json = buildJsonObject {
            amount?.let { put("amount", Amounts.wire(it)) }; put("currency", currency); put("description", description.orEmpty())
        }
        val reply = execute(enzonaApiRequest("https://api.enzona.net/qr/v1.0.0/qr/account", tokens)
            .post(json.toString().toRequestBody(JSON_UTF8)).build())
        if (reply.status != 200) throw EnzonaApiException(reply.status, invalidCredentials = reply.isInvalidCredentials)
        return EnzonaQrJson.ownQr(reply.body, ownerSubject, now())
    }
}

/** Invoice lookups (`/invoice/v1.0.0/invoice/search`). Saved remote references are out of scope. */
internal class EnzonaServiceApi(private val execute: EnzonaExecute, private val now: () -> Long = { Instant.now().toEpochMilli() }) {
    // getElectricMonthBill 0x70f8bc.
    suspend fun electricityBills(tokens: EnzonaTokens, clientId: String): EnzonaFetched<List<EnzonaElectricityBill>> =
        fetch(tokens, search(EnzonaInvoiceService.ELECTRICITY.code, clientId), EnzonaServiceJson::electricityBills)

    /** Pass the full account identifier, including the selected Nauta navigation suffix. */
    suspend fun etecsaAccounts(tokens: EnzonaTokens, service: EnzonaInvoiceService, clientId: String): EnzonaFetched<EnzonaEtecsaQuery> {
        require(service != EnzonaInvoiceService.ELECTRICITY)
        return fetch(tokens, search(service.code, clientId), EnzonaServiceJson::etecsaAccounts)
    }

    private suspend fun <T> fetch(tokens: EnzonaTokens, url: HttpUrl, decode: (String) -> T): EnzonaFetched<T> {
        val reply = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        val fault = EnzonaServiceJson.fault(reply.body)
        if (reply.status == 401 && reply.isInvalidCredentials) throw EnzonaApiException(401, invalidCredentials = true)
        if (reply.status != 200 || fault != null) throw EnzonaServiceException(reply.status, fault)
        return EnzonaFetched(decode(reply.body), reply.body, now())
    }

    private fun search(code: String, clientId: String): HttpUrl = api("invoice", "v1.0.0", "invoice", "search").newBuilder()
        .addQueryParameter("service_code", code)
        .addQueryParameter("client_id", identifier(clientId)).build()
}

/** The account's cards (card module, ENZONA 1.11.0): what adding one asks, adding it, the default one, removing one. */
internal class EnzonaCardsApi(private val execute: EnzonaExecute) {
    // RemoteCordenateDataSourceImpl.getCordenate 0x784180: other cells on every request.
    suspend fun coordinates(tokens: EnzonaTokens): EnzonaCoordinates =
        read(api("nomenclator", "v1.0.0", "nomenclator", "coordinates"), tokens, EnzonaCardJson::coordinates)

    // bank_datasource getAll 0x9e9274.
    suspend fun banks(tokens: EnzonaTokens): List<EnzonaBank> = read(api("nomenclator", "v1.0.0", "nomenclator", "banks"), tokens, EnzonaCardJson::banks)

    // addEntity 0x9e806c.
    suspend fun add(tokens: EnzonaTokens, request: EnzonaCardRequest) =
        change(enzonaApiRequest(EnzonaCardRequest.URL, tokens).post(request.json.toRequestBody(JSON_UTF8)).build())

    // getSetAsDefault 0xa2cc58.
    suspend fun setDefault(tokens: EnzonaTokens, cardUuid: String) {
        val json = buildJsonObject { put("funding_source_uuid", identifier(cardUuid)) }
        change(enzonaApiRequest(api("cards", "v1.0.0", "cards", "default").toString(), tokens).post(json.toString().toRequestBody(JSON_UTF8)).build())
    }

    // deleteEntity 0xa2b844: no body.
    suspend fun remove(tokens: EnzonaTokens, cardUuid: String) =
        change(enzonaApiRequest(api("cards", "v1.0.0", "cards", identifier(cardUuid), "delete").toString(), tokens).method("DELETE", null).build())

    private suspend fun <T> read(url: HttpUrl, tokens: EnzonaTokens, decode: (String) -> T): T {
        val reply = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (reply.status != 200) throw EnzonaApiException(reply.status, invalidCredentials = reply.isInvalidCredentials)
        return decode(reply.body)
    }

    /** Done on HTTP 200 without a fault; otherwise the failure carries ENZONA's fault when it sends one. */
    private suspend fun change(request: Request) {
        val reply = execute(request)
        if (reply.isInvalidCredentials) throw EnzonaApiException(401, invalidCredentials = true)
        val fault = EnzonaServiceJson.fault(reply.body)
        if (reply.status != 200 || fault != null) throw EnzonaServiceException(reply.status, fault)
    }
}

internal fun interface EnzonaPaymentNetwork {
    suspend fun submit(tokens: EnzonaTokens, request: EnzonaPaymentRequest): EnzonaPaymentEvidence
}

/** Exactly one HTTP execution. Authentication renewal happens before the journal CAS, never after. */
internal class EnzonaPaymentApi(
    private val execute: EnzonaExecute,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) : EnzonaPaymentNetwork {
    override suspend fun submit(tokens: EnzonaTokens, request: EnzonaPaymentRequest): EnzonaPaymentEvidence {
        require(request.isTraced) { "Operación de pago no válida" }
        val reply = execute(enzonaApiRequest(request.url, tokens).post(request.json.toRequestBody(JSON_UTF8)).build())
        return EnzonaPaymentJson.evidence(request, reply.status, reply.body, now())
    }
}
