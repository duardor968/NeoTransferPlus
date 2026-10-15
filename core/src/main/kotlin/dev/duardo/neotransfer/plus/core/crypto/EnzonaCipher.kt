package dev.duardo.neotransfer.plus.core.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * `Base64(RSAES-PKCS1-v1_5(ENZONA public key, UTF-8(text)))`, traced at AccountController.cifrar
 * 0x6ae608 (ENZONA 1.11.0). Used separately for the payment password and the recipient PAN.
 * The bank-certificate OAEP-SHA1 contract (card enrolment) is a different, unimplemented path.
 */
class EnzonaCipher(
    private val publicKey: RSAPublicKey,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Encodes and encrypts; the caller keeps ownership of [password] and must clear it. */
    fun encryptPassword(password: CharArray): String {
        var encoded: ByteBuffer? = null
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            return encryptAndClear(bytes)
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("La contraseña de pago no contiene texto UTF-8 válido")
        } finally {
            encoded?.let { buffer ->
                if (buffer.hasArray()) buffer.array().fill(0) else {
                    buffer.clear()
                    while (buffer.hasRemaining()) buffer.put(0.toByte())
                }
            }
        }
    }

    /** [bytes] is cleared on success and on every failure. */
    fun encryptAndClear(bytes: ByteArray): String = try {
        val blockBytes = (publicKey.modulus.bitLength() + 7) / 8
        require(blockBytes > 11 && bytes.size <= blockBytes - 11) {
            "El texto supera el tamaño permitido por la clave de cifrado"
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
        Base64.getEncoder().encodeToString(cipher.doFinal(bytes))
    } finally {
        bytes.fill(0)
    }

    companion object {
        /** SPKI `PUBLIC KEY` PEM (res/raw/enzona_public_key.pem, or a synthetic test key). */
        fun publicKeyFromPem(pem: String): RSAPublicKey {
            val begin = "-----BEGIN PUBLIC KEY-----"
            val end = "-----END PUBLIC KEY-----"
            val text = pem.trim()
            require(text.startsWith(begin) && text.endsWith(end)) { "La clave de cifrado debe tener formato SPKI PEM" }
            val content = text.substring(begin.length, text.length - end.length).filterNot(Char::isWhitespace)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(content)))
            return key as? RSAPublicKey ?: throw IllegalArgumentException("La clave de cifrado debe ser RSA")
        }
    }
}
