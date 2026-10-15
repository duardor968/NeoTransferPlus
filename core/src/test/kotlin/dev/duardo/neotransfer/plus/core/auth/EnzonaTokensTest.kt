package dev.duardo.neotransfer.plus.core.auth

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaTokensTest {
    private fun jwt(subject: String): String = "synthetic." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(buildJsonObject { put("sub", subject) }.toString().toByteArray()) + ".signature"

    private fun response(subject: String = "example@carbon.super", vararg changes: Pair<String, JsonElement?>): String {
        val base = mutableMapOf<String, JsonElement>(
            "access_token" to JsonPrimitive("ACCESS_TEST_ONLY"), "refresh_token" to JsonPrimitive("REFRESH_TEST_ONLY"),
            "id_token" to JsonPrimitive(jwt(subject)), "token_type" to JsonPrimitive("Bearer"), "expires_in" to JsonPrimitive(3600),
        )
        changes.forEach { (key, value) -> if (value == null) base.remove(key) else base[key] = value }
        return JsonObject(base).toString()
    }

    @Test fun expiryUsesSecondsAndPreservesTheExactSubject() {
        val token = EnzonaTokens.fromResponse(response(), 1000)
        assertEquals(4600L, token.expiresAtEpochSeconds)
        assertEquals("example@carbon.super", token.accountSubject())
        assertFalse(token.needsRefresh(4569))
        assertTrue(token.needsRefresh(4570))
        assertTrue(token.needsRefresh(4700))
    }

    @Test fun refreshRotatesCredentialsAndCannotSilentlyChangeAccounts() {
        val old = EnzonaTokens.fromResponse(response(), 1000)
        val rotated = arrayOf<Pair<String, JsonElement?>>("access_token" to JsonPrimitive("NEW_ACCESS"),
            "refresh_token" to JsonPrimitive("NEW_REFRESH"))
        val current = EnzonaTokens.fromResponse(response("example@carbon.super", *rotated), 4500, old)
        assertEquals("NEW_REFRESH", current.refreshToken)
        assertEquals(8100L, current.expiresAtEpochSeconds)
        assertFailsWith<IllegalArgumentException> { EnzonaTokens.fromResponse(response("other-account"), 4500, old) }
        val withoutRotation = response("example@carbon.super", "access_token" to JsonPrimitive("NEW_ACCESS"),
            "refresh_token" to null, "id_token" to null)
        val kept = EnzonaTokens.fromResponse(withoutRotation, 4500, old)
        assertEquals(old.refreshToken, kept.refreshToken)
        assertEquals(old.idToken, kept.idToken)
    }

    @Test fun missingOrMalformedDataCannotCreateAnAuthenticatedSession() {
        val invalid = listOf("{}", "[]", "", response(changes = arrayOf("access_token" to JsonPrimitive(""))),
            response(changes = arrayOf("access_token" to JsonPrimitive("token\nvalue"))),
            response(changes = arrayOf("access_token" to JsonPrimitive("token value"))),
            response(changes = arrayOf("expires_in" to JsonPrimitive("3600"))),
            response(changes = arrayOf("expires_in" to JsonPrimitive(false))),
            response(changes = arrayOf("expires_in" to JsonPrimitive(3600.0))),
            response(changes = arrayOf("expires_in" to null)),
            response(changes = arrayOf("expires_in" to JsonPrimitive(-1))),
            response(changes = arrayOf("expires_in" to JsonPrimitive(0))),
            response(changes = arrayOf("token_type" to JsonPrimitive("Basic"))),
            response(changes = arrayOf("id_token" to JsonPrimitive("invalid"))),
            response(changes = arrayOf("error_description" to JsonPrimitive("PRIVATE_SYNTHETIC_MARKER"))),
            response(changes = arrayOf("error" to JsonPrimitive("invalid_grant"))),
            response(changes = arrayOf("id_token" to JsonPrimitive(jwt("")))))
        for (body in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { EnzonaTokens.fromResponse(body, 1000) }
            assertFalse(error.toString().contains("PRIVATE_SYNTHETIC_MARKER"))
            assertNull(error.cause)
        }
        assertFailsWith<IllegalArgumentException> {
            EnzonaTokens.fromResponse(response(changes = arrayOf("expires_in" to JsonPrimitive(Long.MAX_VALUE))), 1000)
        }
    }

    @Test fun encryptedStorageRoundTripDoesNotTurnExpiredCredentialsIntoAFreshSession() {
        val original = EnzonaTokens.fromResponse(response(), 1000)
        val restored = EnzonaTokens.fromEncryptedStorage(original.forEncryptedStorage())
        assertEquals(original.accessToken, restored.accessToken)
        assertEquals(original.refreshToken, restored.refreshToken)
        assertEquals(original.expiresAtEpochSeconds, restored.expiresAtEpochSeconds)
        assertTrue(restored.needsRefresh(9000))
        assertEquals("EnzonaTokens(redacted)", restored.toString())
        val withoutRefresh = EnzonaTokens("ACCESS", null, original.idToken, 5000)
        assertNull(EnzonaTokens.fromEncryptedStorage(withoutRefresh.forEncryptedStorage()).refreshToken)
        for (corrupt in listOf("{}", "not json", original.forEncryptedStorage().replace("\"expires_at\":4600", "\"expires_at\":\"4600\""))) {
            assertFailsWith<IllegalArgumentException> { EnzonaTokens.fromEncryptedStorage(corrupt) }
        }
    }
}
