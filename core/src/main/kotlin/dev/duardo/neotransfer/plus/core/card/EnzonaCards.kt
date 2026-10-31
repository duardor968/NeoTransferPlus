package dev.duardo.neotransfer.plus.core.card

import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The cells ENZONA asks for one card enrolment, named as it sends them (`GET /nomenclator/v1.0.0/nomenclator/coordinates`,
 * CordenateModel.fromJson 0x78498c): two of the coordinates card ([c1], [c2]) and two positions of the card's PIN
 * ([p1], [p2]). Each request names other cells.
 */
class EnzonaCoordinates(val c1: String, val c2: String, val p1: String, val p2: String) {
    val labels: List<String> get() = listOf(c1, c2, p1, p2)
    override fun toString(): String = "EnzonaCoordinates(redacted)"
}

/** One bank of ENZONA's catalogue (`GET /nomenclator/v1.0.0/nomenclator/banks`); [certificate] is base64 X.509 DER. */
class EnzonaBank(val name: String?, val certificate: String?) {
    override fun toString(): String = "EnzonaBank(redacted)"
}

object EnzonaCardJson {
    fun coordinates(body: String): EnzonaCoordinates {
        val row = root(body)["coordinates"] as? JsonObject ?: throw IllegalArgumentException("Missing or invalid coordinates")
        return EnzonaCoordinates(row.cell("C1"), row.cell("C2"), row.cell("P1"), row.cell("P2"))
    }

    fun banks(body: String): List<EnzonaBank> {
        val rows = root(body)["banks"] as? JsonArray ?: throw IllegalArgumentException("Missing or invalid banks list")
        return rows.map { row ->
            val bank = row as? JsonObject ?: throw IllegalArgumentException("Invalid banks row")
            EnzonaBank(bank.string("name"), bank.string("certificate"))
        }
    }

    private fun root(body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            // Parser diagnostics can quote the body.
            throw IllegalArgumentException("Invalid ENZONA JSON")
        } as? JsonObject ?: throw IllegalArgumentException("Expected ENZONA object")
        require(listOf("fault", "error", "errors").none { it in root }) { "ENZONA error envelope" }
        return root
    }

    /** A cell's name, a string or a number as sent. */
    private fun JsonObject.cell(key: String): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
        require(!value.isNullOrBlank() && value.none(Char::isISOControl)) { "Missing or invalid $key" }
        return value
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
}

/** The catalogue entry of each bank, by its name: ENZONA's codes are not mapped to the banks anywhere traced. */
object EnzonaBanks {
    private val names = mapOf(
        CardBank.BPA to listOf("popular", "bpa"),
        CardBank.BANDEC to listOf("credito", "bandec"),
        CardBank.BANMET to listOf("metropolitano", "banmet"),
    )

    /** The one entry whose name is [bank]'s; null when none or several are. */
    fun find(catalogue: List<EnzonaBank>, bank: CardBank): EnzonaBank? {
        val words = names.getValue(bank)
        return catalogue.filter { entry -> entry.name?.let(::plain)?.let { name -> words.any { it in name } } == true }.singleOrNull()
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
}

/** Transient body of `POST /coremz/1.0.2/card`; never stored or logged. */
class EnzonaCardRequest internal constructor(val json: String) {
    override fun toString(): String = "EnzonaCardRequest(redacted)"

    companion object { const val URL = "https://api.enzona.net/coremz/1.0.2/card" }
}

/**
 * AddCardModel.toJson 0x9e868c (ENZONA 1.11.0): `pan` in PKCS#1 v1.5 under ENZONA's key, like the payments; and
 * `cadenaEncript`, every cell's name followed by what was typed for it (`C1 v1 C2 v2 P1 v3 P2 v4`, no separators,
 * AddCardController.loadEncrypt 0x9e8c80), in RSA-OAEP with SHA-1 under the certificate of the card's bank.
 */
class EnzonaCardRequests(private val cipher: EnzonaCipher, private val random: SecureRandom = SecureRandom()) {
    /**
     * [expiry] is `MMAA`; [cardholder] the account's name and last names; [userName] the token's `sub`. [answers] are
     * what was typed for [coordinates]' cells, in their order: cleared on success and on every failure.
     */
    fun add(
        pan: String, expiry: String, cardholder: String, userName: String,
        coordinates: EnzonaCoordinates, bankKey: RSAPublicKey, answers: List<CharArray>,
    ): EnzonaCardRequest {
        try {
            require(pan.length == 16 && pan.all { it in '0'..'9' }) { "La tarjeta debe contener 16 dígitos" }
            require(expiry.length == 4 && expiry.all { it in '0'..'9' }) { "El vencimiento debe contener 4 dígitos" }
            require(cardholder.isNotBlank() && userName.isNotBlank()) { "Faltan el titular o la cuenta" }
            require(answers.size == coordinates.labels.size && answers.all { typed -> typed.isNotEmpty() && typed.all { it in '0'..'9' } }) {
                "Las coordenadas deben ser dígitos"
            }
            val chain = chain(coordinates, answers, bankKey)
            val encryptedPan = cipher.encryptAndClear(pan.toByteArray(StandardCharsets.UTF_8))
            val body = buildJsonObject {
                put("pan", encryptedPan)
                put("cardholder", cardholder)
                put("expdate", expiry)
                put("cadenaEncript", chain)
                // «Carné militar o Pasaporte», optional in the official form; not asked.
                put("CM", "")
                put("userName", userName)
            }
            return EnzonaCardRequest(body.toString())
        } finally {
            answers.forEach { it.fill('\u0000') }
        }
    }

    private fun chain(coordinates: EnzonaCoordinates, answers: List<CharArray>, key: RSAPublicKey): String {
        val labels = coordinates.labels
        val plain = CharArray(labels.sumOf { it.length } + answers.sumOf { it.size })
        var encoded: ByteBuffer? = null
        try {
            var at = 0
            labels.forEachIndexed { index, label ->
                label.toCharArray(plain, at)
                at += label.length
                answers[index].copyInto(plain, at)
                at += answers[index].size
            }
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(plain))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            return encryptOaep(bytes, key)
        } finally {
            plain.fill('\u0000')
            encoded?.let { buffer -> if (buffer.hasArray()) buffer.array().fill(0) }
        }
    }

    /** [bytes] is cleared on success and on every failure. */
    private fun encryptOaep(bytes: ByteArray, key: RSAPublicKey): String = try {
        val blockBytes = (key.modulus.bitLength() + 7) / 8
        require(bytes.size <= blockBytes - 2 * 20 - 2) { "El texto supera el tamaño permitido por la clave del banco" }
        val oaep = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        oaep.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT), random)
        Base64.getEncoder().encodeToString(oaep.doFinal(bytes))
    } finally {
        bytes.fill(0)
    }

    companion object {
        /** The RSA key of a bank's catalogue [certificate] (base64 of X.509 DER, extractPublicKeyFromCrt). */
        fun bankKey(certificate: String): RSAPublicKey {
            val der = try {
                Base64.getDecoder().decode(certificate.filterNot(Char::isWhitespace))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("El certificado del banco no es base64")
            }
            val parsed = try {
                CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
            } catch (_: CertificateException) {
                throw IllegalArgumentException("El certificado del banco no es X.509")
            }
            return parsed.publicKey as? RSAPublicKey ?: throw IllegalArgumentException("La clave del banco debe ser RSA")
        }
    }
}
