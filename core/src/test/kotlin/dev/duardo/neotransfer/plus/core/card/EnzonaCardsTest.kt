package dev.duardo.neotransfer.plus.core.card

import dev.duardo.neotransfer.plus.core.crypto.EnzonaCipher
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Contract of adding a card, offline: synthetic keys and a synthetic bank certificate, never ENZONA's. */
class EnzonaCardsTest {
    private val requests = EnzonaCardRequests(EnzonaCipher(enzonaKeys.public as RSAPublicKey))
    private val coordinates = EnzonaCoordinates("A02", "D05", "2", "4")
    private val bankKey = EnzonaCardRequests.bankKey(BANK_CERTIFICATE)

    private fun answers() = listOf("37", "18", "5", "9").map(String::toCharArray)

    private fun add(answers: List<CharArray> = answers(), pan: String = "9212060123456781", expiry: String = "0329") =
        requests.add(pan, expiry, "Laura Méndez Soto", "laura@carbon.super", coordinates, bankKey, answers)

    private fun body(request: EnzonaCardRequest): JsonObject = Json.parseToJsonElement(request.json).jsonObject

    @Test fun coordinatesAndBanksDecodeTheTracedEnvelopes() {
        val cells = EnzonaCardJson.coordinates("""{"coordinates":{"C1":"A02","C2":"D05","P1":2,"P2":"4"}}""")
        assertEquals(listOf("A02", "D05", "2", "4"), cells.labels)
        val banks = EnzonaCardJson.banks("""{"banks":[{"name":"Banco Popular de Ahorro","code":"12","certificate":"$BANK_CERTIFICATE"},
            {"name":"Banco Central de Cuba","code":7,"certificate":null}]}""")
        assertEquals(listOf("Banco Popular de Ahorro", "Banco Central de Cuba"), banks.map { it.name })
        assertEquals(BANK_CERTIFICATE, banks[0].certificate)
        assertNull(banks[1].certificate)
        for (body in listOf("""{"coordinates":{"C1":"A02","C2":"D05","P1":"2"}}""", """{"coordinates":[]}""",
            """{"coordinates":{"C1":"","C2":"D05","P1":"2","P2":"4"}}""", """{"fault":{"code":1006}}""", "not json")) {
            assertFailsWith<IllegalArgumentException>(body) { EnzonaCardJson.coordinates(body) }
        }
        for (body in listOf("""{"banks":{}}""", """{"banks":[1]}""", """{"error":"x","banks":[]}""")) {
            assertFailsWith<IllegalArgumentException>(body) { EnzonaCardJson.banks(body) }
        }
    }

    @Test fun eachBankIsTheOneCatalogueEntryWithItsName() {
        val bpa = EnzonaBank("Banco Popular de Ahorro", "a")
        val bandec = EnzonaBank("BANCO DE CRÉDITO Y COMERCIO", "b")
        val banmet = EnzonaBank("Banco Metropolitano S.A.", "c")
        val catalogue = listOf(EnzonaBank("Banco Central de Cuba", "z"), bpa, bandec, banmet, EnzonaBank(null, "n"))
        assertSame(bpa, EnzonaBanks.find(catalogue, CardBank.BPA))
        assertSame(bandec, EnzonaBanks.find(catalogue, CardBank.BANDEC))
        assertSame(banmet, EnzonaBanks.find(catalogue, CardBank.BANMET))
        assertNull(EnzonaBanks.find(listOf(bandec, banmet), CardBank.BPA))
        // Two entries with the name: which one is not guessed.
        assertNull(EnzonaBanks.find(catalogue + EnzonaBank("BPA", "d"), CardBank.BPA))
    }

    @Test fun bodyCarriesTheTracedKeysInTheirOrder() {
        val request = add()
        assertTrue(request.json.startsWith("{\"pan\":\""))
        val json = body(request)
        assertEquals(listOf("pan", "cardholder", "expdate", "cadenaEncript", "CM", "userName"), json.keys.toList())
        assertEquals("Laura Méndez Soto", json.getValue("cardholder").jsonPrimitive.content)
        assertEquals("0329", json.getValue("expdate").jsonPrimitive.content)
        assertEquals("", json.getValue("CM").jsonPrimitive.content)
        assertEquals("laura@carbon.super", json.getValue("userName").jsonPrimitive.content)
        assertTrue(json.values.all { it.jsonPrimitive.isString })
        assertEquals("https://api.enzona.net/coremz/1.0.2/card", EnzonaCardRequest.URL)
    }

    @Test fun cadenaEncriptIsEachCellFollowedByWhatWasTypedUnderTheBanksCertificate() {
        val json = body(add())
        val oaep = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, bankPrivateKey, OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
        val plain = oaep.doFinal(Base64.getDecoder().decode(json.getValue("cadenaEncript").jsonPrimitive.content))
        // C1 v1 C2 v2 P1 v3 P2 v4, no separators.
        assertEquals("A02" + "37" + "D05" + "18" + "2" + "5" + "4" + "9", String(plain, Charsets.UTF_8))
        // Not PKCS#1: the bank's padding is OAEP.
        assertFailsWith<Exception> {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, bankPrivateKey) }
                .doFinal(Base64.getDecoder().decode(json.getValue("cadenaEncript").jsonPrimitive.content))
        }
    }

    @Test fun panTravelsInPkcs1UnderEnzonasKey() {
        val encrypted = body(add()).getValue("pan").jsonPrimitive.content
        val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, enzonaKeys.private)
            doFinal(Base64.getDecoder().decode(encrypted))
        }
        assertEquals("9212060123456781", String(plain, Charsets.UTF_8))
        assertFalse("9212060123456781" in body(add()).toString())
    }

    @Test fun typedCellsAreClearedOnSuccessAndOnEveryRefusal() {
        fun cleared(answers: List<CharArray>) = answers.all { typed -> typed.all { it == '\u0000' } }
        answers().also { add(it); assertTrue(cleared(it)) }
        for (bad in listOf(listOf("37", "18", "5"), listOf("37", "", "5", "9"), listOf("3a", "18", "5", "9"), listOf("37", "18", "5", " 9"))) {
            val typed = bad.map(String::toCharArray)
            assertFailsWith<IllegalArgumentException>(bad.toString()) { add(typed) }
            assertTrue(cleared(typed), bad.toString())
        }
        for ((pan, expiry) in listOf("921206012345678" to "0329", "9212 0601 2345 6781" to "0329", "9212060123456781" to "03/29")) {
            val typed = answers()
            assertFailsWith<IllegalArgumentException> { add(typed, pan, expiry) }
            assertTrue(cleared(typed))
        }
        val typed = answers()
        assertFailsWith<IllegalArgumentException> { requests.add("9212060123456781", "0329", " ", "laura@carbon.super", coordinates, bankKey, typed) }
        assertTrue(cleared(typed))
    }

    @Test fun cellsAndRequestsNeverPrintTheirContent() {
        val request = add()
        assertEquals("EnzonaCardRequest(redacted)", request.toString())
        assertEquals("EnzonaCoordinates(redacted)", coordinates.toString())
        assertEquals("EnzonaBank(redacted)", EnzonaBank("Banco Popular de Ahorro", BANK_CERTIFICATE).toString())
    }

    @Test fun theBanksKeyIsTheOneOfItsCertificate() {
        assertEquals(bankPublicModulus(), bankKey.modulus)
        assertEquals(bankKey.modulus, EnzonaCardRequests.bankKey(BANK_CERTIFICATE.chunked(64).joinToString("\n")).modulus)
        for (bad in listOf("", "not base64!", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))) {
            assertFailsWith<IllegalArgumentException>(bad) { EnzonaCardRequests.bankKey(bad) }
        }
    }

    private fun bankPublicModulus() = (KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(BANK_PRIVATE_KEY)))
        as java.security.interfaces.RSAPrivateCrtKey).modulus

    private companion object {
        val enzonaKeys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** Synthetic self-signed certificate (CN=Synthetic Test Bank) and its key, made for this test only. */
        const val BANK_CERTIFICATE =
            "MIIDHzCCAgegAwIBAgIUZxW6HgcCRRNIZGPCKnn95AX9Gr0wDQYJKoZIhvcNAQELBQAwHjEcMBoGA1UEAwwTU3ludGhldGljIFRl" +
            "c3QgQmFuazAgFw0yNjEwMTkxMjU3MjFaGA8yMTI2MDkyNTEyNTcyMVowHjEcMBoGA1UEAwwTU3ludGhldGljIFRlc3QgQmFuazCC" +
            "ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBANoemQq0QEA9yobE893C6Jdzx64eiMXKSDZGCmL/RBPR8O84R6ybGzzglnqH" +
            "rVhCCf3PU8qXtvxsPmovg61RcaLJzkDE9DAhw5zqBGkZM5W/T8ucD1uwV3EbxPfQHw+9I4/krsRA0Omf25eOtl3tCTUia4IpZNkE" +
            "AIo8rwCDSBMz/tGMkifB01iNDMO21LwLXZtaEx3UW4ngoHRPM8iJ8KoTO0s+WE4QhVHEz29HESmr6oUCDaDAU6fHgZeg5oRzx5U8" +
            "vvtXG5vgkM5OPGjBhZ2wZJvxmCWNX1TVSQkneY+QZiLs90ac5jIs/yKM4SqHl+f8k42qPP4PB4KY8q21h0kCAwEAAaNTMFEwHQYD" +
            "VR0OBBYEFBG2bwaj2+3nAtSHFBQMrRr6Bz2IMB8GA1UdIwQYMBaAFBG2bwaj2+3nAtSHFBQMrRr6Bz2IMA8GA1UdEwEB/wQFMAMB" +
            "Af8wDQYJKoZIhvcNAQELBQADggEBAA1XhG66Or/3mEZLA1F/6tiBBCzNy+GAyM8RKrFbj4uT3dYeiYiPiUAa7bhi5L35pLYTWcj2" +
            "lx/DVBLLMNAIB/On4praFORQecGc21qe9Dw4ecivEIcVKacTCtdF3GfFIJJGFxOphJhSzA+v/SjGBO26mp67uIF/jCXflmtqqBqz" +
            "yjn+FtMv+mc11P7oTQ+l5s7Msw9yE9k2j5+kD8KRzVrbPVdSswZGwxF8IDi4lUEuar2kPQ/kTtN6hb+qymg1jJmQMyFim/5O+wZG" +
            "6S/347hQjFnTXZet6vhgZC4NSE7HUjla/5/VshdlmuDVN67RClTK4eu4Fil6v2wKhROVefc="

        const val BANK_PRIVATE_KEY =
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDaHpkKtEBAPcqGxPPdwuiXc8euHojFykg2Rgpi/0QT0fDvOEes" +
            "mxs84JZ6h61YQgn9z1PKl7b8bD5qL4OtUXGiyc5AxPQwIcOc6gRpGTOVv0/LnA9bsFdxG8T30B8PvSOP5K7EQNDpn9uXjrZd7Qk1" +
            "ImuCKWTZBACKPK8Ag0gTM/7RjJInwdNYjQzDttS8C12bWhMd1FuJ4KB0TzPIifCqEztLPlhOEIVRxM9vRxEpq+qFAg2gwFOnx4GX" +
            "oOaEc8eVPL77Vxub4JDOTjxowYWdsGSb8ZgljV9U1UkJJ3mPkGYi7PdGnOYyLP8ijOEqh5fn/JONqjz+DweCmPKttYdJAgMBAAEC" +
            "ggEAY4QkVULqMaU/mv+PODWuQzN6/lFGG9/v1HteT86hc92MbMijwve9lKfvYBY5G5R91IRceYmA3mhQjcgB7vjCvQAQD3MM6kzt" +
            "X9FRk9YAuCjiMkaQp0esjZma42ncUiT8bbFrn3TnHEBoQxzEzdPRKKH8pRVXwr/Vszx+p+A96SwvKJElNzG3QlGoDVPETgRCPgdd" +
            "BqCl47lt1TBiooWkX+AoHxGt1duYmcMJeR2Z4P0MtWqBcYhx4gpuy9YOhdHJFMGx7+eHBt9+3olvLnRq3Qa8GOkxosdKcmcWfwKZ" +
            "XwUIPAR0jzPZgLSkt29V/JlIfuhWSKGnJuflmgDBT0u7BQKBgQD1FYitqsKT7DZInrfsRnVUa+Boyg+jC2DfmjSNn9zF5u7exgow" +
            "LAn5si/V6khSOAg3vOIZ+Uyag9uFUBidcIsTO4c2rjNfg9b7P1q/Zp4qD1bp6svcbPGw9j5daTviisGgtUNPFMksSDwz2nxGh0cP" +
            "LqEwkKy5YwUxEtIpolTsbwKBgQDj1Zybf3I+O5trGqnX4JwR0CU2oEKsn+UihpwRSEH8RLeT8jP4H522ojsGTyjB89lUqzXjRFOz" +
            "Be+6cELW4DYlF+O5jQ5xUnc+Q6HbIqiQjGjUQf1lHMMfV52mkxx/vjsooYxwn9KkNJissueKAMst+mjK3/MunlfAlpva1/KTxwKB" +
            "gQDsS8XKw2/1FPLFk5n7CXfIXHYyMldfOPI+xDvVLIO8n0pBqalbBqrbfyxV0BvIgdOSgd9xcYalF2qIn1AMQ90jPurAErbfaYrW" +
            "FSBWmAb4wG9o+wNw0+03VEOO5o5hfqzvWLS7w2uu8TpyOD5H9dfSUIADBgoyd8EirmXI1IWSqwKBgHInxLkw6Uf/S8cla/ZLh0dx" +
            "mEO/lsixsPhUo/wQzWcd8zGAyMNI7BfnP1XQ8eYETV+cDXsa8CkiD1TGDOdnHhoF10dQQu1K2Y5sCrwmqHkMsTWw/srb13RrzQT6" +
            "buwen7ui/iAsJDm8VMcrONIvxZ36+uizv0AbfHJ1xA+p5dRfAoGBAMgkmLWsLUnuXqcv/kXNnVl19gsAN0vgPE1QmRA29pEZ+l1o" +
            "L+7hf6q4n482PpohFEfrJ6vJnaw+9r8T98WTfxmgGMyWg9jqzhb1sdWYITFOxTlOBT6USGidGIPJLocv00j2+ji9dsaMNtHhRiNO" +
            "qc6gWKsovM3wWKtdhvbMv0fk"

        val bankPrivateKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(BANK_PRIVATE_KEY)))
    }
}
