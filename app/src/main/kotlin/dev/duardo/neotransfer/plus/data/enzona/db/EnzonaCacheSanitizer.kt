package dev.duardo.neotransfer.plus.data.enzona.db

import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.core.read.EnzonaTransaction
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Drops authorization material at any depth before anything reaches disk; keeps numeric lexemes. */
internal object EnzonaCacheSanitizer {
    private val authorizationKeys = setOf(
        "access_token", "refresh_token", "id_token", "payment_password", "paymentpassword",
        "old_payment_password", "oldpaymentpassword", "fingerprint", "fingerpring", "totpcode",
        "client_secret", "authorization", "code_verifier", "password", "pin",
    )
    private val errorKeys = listOf("fault", "error", "errors", "error_description")

    fun sanitize(kind: EnzonaReadKind, json: String): String {
        val safeJson = sanitizedObject(json).toString()
        when (kind) {
            EnzonaReadKind.ACCOUNT -> EnzonaReadJson.account(safeJson)
            EnzonaReadKind.CARDS -> EnzonaReadJson.cards(safeJson)
            EnzonaReadKind.BALANCE -> EnzonaReadJson.balance(safeJson)
        }
        return safeJson
    }

    fun sanitizeTransaction(json: String): String = sanitizedObject(json).toString().also { EnzonaReadJson.transaction(it) }

    private fun sanitizedObject(json: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (_: IllegalArgumentException) {
            // Parser diagnostics may contain raw input. Do not propagate them or their cause.
            throw IllegalArgumentException("Invalid ENZONA read JSON")
        }
        require(parsed is JsonObject) { "Expected an ENZONA read object" }
        val safe = redact(parsed) as JsonObject
        require(errorKeys.none { it in safe }) { "ENZONA error envelope" }
        return safe
    }

    private fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it.lowercase(Locale.ROOT) !in authorizationKeys }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value
    }
}

/** Pure merge of one history read into stored rows; Room applies the result in one transaction. */
internal object EnzonaHistoryMerge {
    /**
     * New UUIDs are inserted with [observedAt] as first sight. A new incoming credit is queued for notice
     * only on a recent-window sync ([notifyCredits]) after the owner's baseline exists, so neither the
     * first sync after sign-in nor browsing older dates notifies old money. Known rows keep their first
     * sight and notice state; an older read never overwrites a newer one.
     */
    fun plan(
        owner: String,
        incoming: List<EnzonaTransaction>,
        existing: Map<String, EnzonaTransactionRow>,
        observedAt: Long,
        baseline: Boolean,
        notifyCredits: Boolean,
        accountUsername: String?,
    ): List<EnzonaTransactionRow> {
        require(owner.isNotBlank() && observedAt >= 0)
        val planned = linkedMapOf<String, EnzonaTransactionRow>()
        for (transaction in incoming) {
            val uuid = transaction.transactionUuid
            val json = EnzonaCacheSanitizer.sanitizeTransaction(transaction.original.toString())
            val previous = planned[uuid] ?: existing[uuid]
            planned[uuid] = when {
                previous == null -> EnzonaTransactionRow(owner, uuid, observedAt, observedAt, json,
                    if (notifyCredits && baseline && EnzonaCredits.incoming(transaction, accountUsername) != null)
                        EnzonaCreditNotice.PENDING else EnzonaCreditNotice.NONE)
                observedAt >= previous.observedAt -> previous.copy(observedAt = observedAt, json = json)
                else -> previous
            }
        }
        return planned.values.toList()
    }
}
