package dev.duardo.neotransfer.plus.core.read

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A reply whose shape the decoders do not accept. Its message names the field and the problem («Invalid amount
 * decimal»), never a value, so it is safe to log.
 */
class EnzonaShapeException(message: String) : IllegalArgumentException(message)

/** Offline decoders for the three AOT-traced read envelopes. No status implies business success. */
object EnzonaReadJson {
    /** One retained history row, with the same envelope and field validation as a history page. */
    fun transaction(body: String): EnzonaTransaction = transaction(envelope(body))

    fun account(body: String): EnzonaAccount {
        val row = envelope(body)["account"] as? JsonObject ?: throw EnzonaShapeException("Missing or invalid account")
        shape(row.isNotEmpty() && listOf("fault", "error", "errors").none { it in row }) { "ENZONA account error" }
        return EnzonaAccount(
            username = row.label("username"),
            uuid = row.label("uuid"),
            name = row.label("name"),
            lastName = row.label("lastname"),
            phone = row.label("phone"),
            email = row.label("email"),
            avatar = row.label("avatar"),
            primaryCurrency = row.label("primary_currency"),
            original = row,
        )
    }

    fun cards(body: String): List<EnzonaRemoteCard> = envelope(body).rows("card").map(::card)

    fun balance(body: String): EnzonaCardBalance {
        val root = envelope(body)
        shape("balance" in root || "balance_available" in root) { "Missing balance fields" }
        return EnzonaCardBalance(
            bookBalance = root.decimal("balance"),
            availableBalance = root.decimal("balance_available"),
            currency = root.currency(),
            bankAccount = root.string("bank_account"),
            statusCode = root.int("status_code"),
            statusDenomination = root.string("status_denom"),
            transactionSignature = root.string("transaction_signature"),
            operations = root.rows("operation").map(::bankOperation),
            original = root,
        )
    }

    fun bankOperations(body: String): List<EnzonaBankOperation> =
        envelope(body).rows("operation").map(::bankOperation)

    fun transactions(body: String): EnzonaTransactionPage {
        val root = envelope(body)
        val pagination = root["pagination"] as? JsonObject
            ?: throw EnzonaShapeException("Missing or invalid pagination")
        return EnzonaTransactionPage(
            transactions = root.rows("transaction").map(::transaction),
            pagination = EnzonaPagination(
                first = pagination["first"],
                previous = pagination["prev"],
                next = pagination["next"],
                last = pagination["last"],
                total = pagination.long("total", allowString = false),
                original = pagination,
            ),
        )
    }

    // CardModel.fromJson 0x7c2618: only the fields with a traced toString are normalized.
    private fun card(row: JsonObject) = EnzonaRemoteCard(
        fundingSourceUuid = row.requiredId("funding_source_uuid"),
        fundingSourceId = row.string("funding_source_id"),
        last4 = row.text("last4"),
        cardholder = row.string("cardholder"),
        expiryDate = row.text("expdate"),
        createdAt = row.date("created_at"),
        updatedAt = row.date("updated_at"),
        status = row.text("status"),
        currency = row.currency(),
        primarySource = row.text("primary_source"),
        bankName = row.string("bank_name"),
        bankCode = row.text("bank_code"),
        verified = row.text("verified"),
        deleted = row["deleted"],
        bankCertificate = row.string("bank_certificate"),
        bin = row.int("bin"),
    )

    // OperationModel.fromJson 0x82a034: amount is a string in AOT, parsed exactly here.
    private fun bankOperation(row: JsonObject) = EnzonaBankOperation(
        operationDate = row.date("operationDate"),
        action = row.string("action"),
        channel = row.string("channel"),
        description = row.string("description"),
        amount = row.decimal("amount"),
        code = row.string("codigo"),
        avatar = row.string("avatar"),
        alias = row.string("alias"),
        original = row,
    )

    // TransactionModel.fromJson 0x6cb7dc. Repeated UUIDs remain repeated rows.
    private fun transaction(row: JsonObject) = EnzonaTransaction(
        transactionUuid = row.requiredId("transaction_uuid"),
        owner = row.loose("owner"),
        clientId = row.label("client_id"),
        invoice = row.label("invoice"),
        invoiceNumber = row.label("invoice_number"),
        merchantAlias = row.label("merchant_alias"),
        merchantAvatar = row.label("merchant_avatar"),
        merchantName = row.label("merchant_name"),
        statusCode = row.int("status_code"),
        transactionStatusCode = row.int("transaction_status_code"),
        transactionStatus = row.text("transaction_status"),
        createdAt = row.date("transaction_created_at"),
        signature = row.text("transaction_signature"),
        period = row.label("period"),
        amount = row.decimal("amount"),
        totalAmount = row.decimal("total_amount"),
        currency = row.currency(),
        transactionDenomination = row.label("transaction_denom"),
        statusDenomination = row.text("status_denom"),
        transactionCode = row.int("transaction_code"),
        barcode = row.boolean("barcode"),
        donationTitle = row.label("donation_title"),
        description = row.label("transaction_description"),
        source = row.optionalObject("source")?.let(::source),
        recipient = row.optionalObject("recipient")?.let(::recipient),
        metadata = row["metadata"],
        bankDebitDetail = row["bank_debit_detail"],
        original = row,
    )

    private fun source(row: JsonObject) = EnzonaTransactionSource(
        last4 = row.label("funding_source_last4"),
        bank = row.int("funding_source_bank"),
        avatar = row.label("funding_source_avatar"),
        name = row.label("funding_source_name"),
        lastName = row.label("funding_source_lastname"),
        username = row.label("funding_source_username"),
        amount = row.decimal("funding_source_amount"),
        verified = row.int("funding_source_verified"),
        merchantAlias = row.label("merchant_alias"),
        merchantAvatar = row.label("merchant_avatar"),
        original = row,
    )

    private fun recipient(row: JsonObject) = EnzonaTransactionRecipient(
        last4 = row.label("funding_recipient_last4"),
        bank = row.text("funding_recipient_bank"),
        avatar = row.label("funding_recipient_avatar"),
        name = row.label("funding_recipient_name"),
        lastName = row.label("funding_recipient_lastname"),
        username = row.label("funding_recipient_username"),
        verified = row.text("funding_recipient_verified"),
        donationTitle = row.label("donation_title"),
        merchantAlias = row.label("merchant_alias"),
        merchantAvatar = row.label("merchant_avatar"),
        original = row,
    )

    private fun envelope(body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            // Serialization diagnostics can contain body text: do not retain them as a cause.
            throw EnzonaShapeException("Invalid ENZONA JSON")
        } as? JsonObject ?: throw EnzonaShapeException("Expected ENZONA object")
        shape(listOf("fault", "error", "errors").none { it in root }) { "ENZONA error envelope" }
        return root
    }

    private inline fun shape(accepted: Boolean, problem: () -> String) {
        if (!accepted) throw EnzonaShapeException(problem())
    }

    private fun JsonObject.rows(key: String): List<JsonObject> {
        val array = this[key] as? JsonArray
            ?: throw EnzonaShapeException("Missing or invalid $key list")
        return array.map { it as? JsonObject ?: throw EnzonaShapeException("Invalid $key row") }
    }

    private fun JsonObject.requiredId(key: String): String {
        val value = string(key)
        if (value.isNullOrBlank()) throw EnzonaShapeException("Missing or invalid $key")
        return value
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        return value as? JsonPrimitive ?: throw EnzonaShapeException("Invalid $key type: not a value")
    }

    private fun JsonObject.string(key: String): String? {
        val value = primitive(key) ?: return null
        shape(value.isString) { "Invalid $key type: not text" }
        return value.content
    }

    private fun JsonObject.text(key: String): String? = primitive(key)?.content

    /** A field the app does not read: its value when it is one, absent in any other shape; [EnzonaTransaction.original] keeps it. */
    private fun JsonObject.loose(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it == JsonNull || !it.isString && it.content == "false" }?.content

    /** Descriptive text (account, history): a JSON boolean (the provider's "not applicable", as `"client_id": false`) or blank is absent. */
    private fun JsonObject.label(key: String): String? = primitive(key)
        ?.takeIf { it.isString || it.content.toBooleanStrictOrNull() == null }?.content?.takeIf(String::isNotBlank)

    private fun JsonObject.date(key: String): EnzonaRemoteDate? = string(key)?.let(::EnzonaRemoteDate)

    private fun JsonObject.currency(): String? = string("currency")?.also {
        shape(it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid currency value" }
    }

    private fun JsonObject.decimal(key: String): BigDecimal? {
        val value = primitive(key) ?: return null
        if (!value.isString && value.content == "false") return null
        // JsonTreeReader 1.9.0 retains numeric lexemes; no floating-point intermediate.
        return try {
            BigDecimal(value.content)
        } catch (_: NumberFormatException) {
            throw EnzonaShapeException("Invalid $key decimal")
        }
    }

    private fun JsonObject.long(key: String, allowString: Boolean = true): Long? {
        val value = primitive(key) ?: return null
        shape(allowString || !value.isString) { "Invalid $key type: text, not a number" }
        return value.content.toLongOrNull() ?: throw EnzonaShapeException("Invalid $key integer")
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.let {
        shape(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key integer" }
        it.toInt()
    }

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.let {
        it.content.toBooleanStrictOrNull() ?: throw EnzonaShapeException("Invalid $key boolean")
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? {
        val value = this[key]
        if (value == null || value == JsonNull || value == JsonPrimitive(false)) return null
        return value as? JsonObject ?: throw EnzonaShapeException("Invalid $key object")
    }
}
