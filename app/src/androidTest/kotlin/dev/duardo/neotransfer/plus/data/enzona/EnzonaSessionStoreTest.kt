package dev.duardo.neotransfer.plus.data.enzona

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** AndroidKeyStore round trip with an isolated file and alias; synthetic tokens only. */
@RunWith(AndroidJUnit4::class)
class EnzonaSessionStoreTest {
    @Test fun encryptedSessionRoundTripsAndClearsKeyAndFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "enzona-session-test-${UUID.randomUUID()}.bin"
        val store = EnzonaSessionStore(context, name, "test-${UUID.randomUUID()}")
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"synthetic\"}".toByteArray())
        val tokens = EnzonaTokens("ACCESS_TEST_ONLY", "REFRESH_TEST_ONLY", "h.$payload.s", 5000)
        try {
            assertNull(store.load())
            store.save(tokens)
            val file = File(context.noBackupFilesDir, name)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("ACCESS_TEST_ONLY"))
            val loaded = store.load()!!
            assertEquals(tokens.accessToken, loaded.accessToken)
            assertEquals(tokens.refreshToken, loaded.refreshToken)
            assertEquals(5000L, loaded.expiresAtEpochSeconds)
        } finally {
            store.clear()
        }
        assertNull(store.load())
    }
}
