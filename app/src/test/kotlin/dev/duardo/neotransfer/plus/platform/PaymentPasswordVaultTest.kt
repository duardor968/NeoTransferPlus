package dev.duardo.neotransfer.plus.platform

import android.app.Application
import android.content.Context
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which key keeps the payment PIN on each kind of phone, and the realme fallback, over an in-memory Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PaymentPasswordVaultTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keys = FakeKeys()
    private val device = FakeDeviceLock(fingerprint = true, screenLock = true)
    private val vault get() = PaymentPasswordVault(context, keys, device)

    @Before fun clean() {
        context.getSharedPreferences("payment_password", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun eachPhoneGetsTheLockItCanUse() {
        assertEquals(PinLock.Fingerprint, pinLock(codeProtects = false, fingerprintRejected = false, fingerprint = true, screenLock = true))
        // Screen lock without a fingerprint, or a phone that turns fingerprint keys down: the "any" key.
        assertEquals(PinLock.ScreenLock, pinLock(codeProtects = false, fingerprintRejected = false, fingerprint = false, screenLock = true))
        assertEquals(PinLock.ScreenLock, pinLock(codeProtects = false, fingerprintRejected = true, fingerprint = true, screenLock = true))
        // No lock at all: nothing keeps it.
        assertNull(pinLock(codeProtects = false, fingerprintRejected = false, fingerprint = false, screenLock = false))
        // The access code wins on any phone, also without a lock.
        assertEquals(PinLock.Code, pinLock(codeProtects = true, fingerprintRejected = false, fingerprint = true, screenLock = true))
        assertEquals(PinLock.Code, pinLock(codeProtects = true, fingerprintRejected = false, fingerprint = false, screenLock = false))
    }

    @Test fun aFingerprintPhoneKeepsThePerUseFingerprintKey() {
        val vault = vault
        val sealed = vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint)
        vault.store(sealed)
        assertEquals(KeyKind.Fingerprint, keys.kindOf("dev.duardo.neotransfer.plus.payment-password"))
        assertEquals(PinLock.Fingerprint, vault.lock.value)
        assertEquals("482915", String(vault.open(vault.decryptCipher()!!)))
    }

    @Test fun aNewFingerprintClearsThePin() {
        val vault = vault
        vault.store(vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint))
        keys.invalidated += "dev.duardo.neotransfer.plus.payment-password"
        assertNull(vault.decryptCipher())
        assertFalse(vault.saved.value)
    }

    @Test fun aPinSavedBefore030OpensWithTheFingerprintKey() {
        val vault = vault
        vault.store(vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint))
        context.getSharedPreferences("payment_password", Context.MODE_PRIVATE).edit().remove("lock").commit()
        val reopened = this.vault
        assertEquals(PinLock.Fingerprint, reopened.lock.value)
        assertEquals("482915", String(reopened.open(reopened.decryptCipher()!!)))
    }

    @Test fun theRealmeRejectionMovesThePhoneForGoodToTheAnyKey() {
        keys.rejectsFingerprint = true
        val vault = vault
        assertEquals(PinLock.Fingerprint, vault.lockFor(codeProtects = false))
        assertFailsWith<PaymentPasswordVault.FingerprintRejected> {
            vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint)
        }
        assertTrue(keys.fingerprintRejected)
        assertFalse(keys.has("dev.duardo.neotransfer.plus.payment-password"))
        assertEquals(PinLock.ScreenLock, vault.lockFor(codeProtects = false))
        // The retry right after, under a new prompt.
        vault.store(vault.seal(vault.encryptCipher(PinLock.ScreenLock), "482915".toCharArray(), PinLock.ScreenLock))
        assertEquals(KeyKind.Any, keys.kindOf("dev.duardo.neotransfer.plus.payment-password.any"))
        // Erasing the PIN keeps the phone on the any key.
        vault.clear()
        assertTrue(keys.fingerprintRejected)
        assertEquals(PinLock.ScreenLock, vault.lockFor(codeProtects = false))
    }

    @Test fun openingNeverTakesAFailureForTheRealmeRejection() {
        val vault = vault
        vault.store(vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint))
        // A saved fingerprint PIN proves the key works here: a refusal now is something else, and changes nothing.
        keys.rejectsFingerprint = true
        assertFailsWith<Exception> { vault.open(vault.decryptCipher()!!) }
        assertFalse(keys.fingerprintRejected)
        assertTrue(vault.saved.value)
        assertEquals(PinLock.Fingerprint, vault.lock.value)
    }

    @Test fun aMissingKeyOnTheDecryptPathIsNeverMadeAgain() {
        val vault = vault
        vault.store(vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint))
        keys.delete("dev.duardo.neotransfer.plus.payment-password")
        assertNull(vault.decryptCipher())
        assertFalse(vault.saved.value)
        assertFalse(keys.has("dev.duardo.neotransfer.plus.payment-password"))
    }

    @Test fun otherSealingFailuresAreNotTakenForTheRealmeRejection() {
        val vault = vault
        val cipher = vault.encryptCipher(PinLock.Fingerprint)
        cipher.doFinal(ByteArray(1)) // A used GCM cipher refuses to encrypt again with the same IV.
        assertFailsWith<IllegalStateException> { vault.seal(cipher, "482915".toCharArray(), PinLock.Fingerprint) }
        assertFalse(keys.fingerprintRejected)
    }

    @Test fun aScreenLockPinStaysUnderItsKeyAfterAFingerprintIsEnrolled() {
        device.fingerprint = false
        val vault = vault
        val lock = vault.lockFor(codeProtects = false)!!
        assertEquals(PinLock.ScreenLock, lock)
        vault.store(vault.seal(vault.encryptCipher(lock), "482915".toCharArray(), lock))
        assertEquals("482915", String(vault.open(vault.decryptCipher()!!)))
        device.fingerprint = true
        // New PINs would take the fingerprint key; the saved one keeps its own until the vault is emptied.
        assertEquals(PinLock.Fingerprint, vault.lockFor(codeProtects = false))
        assertEquals(PinLock.ScreenLock, vault.lock.value)
        assertEquals("482915", String(vault.open(vault.decryptCipher()!!)))
    }

    @Test fun reEncryptingKeepsTheDoubt() {
        val vault = vault
        vault.store(vault.seal(vault.encryptCipher(PinLock.Fingerprint), "482915".toCharArray(), PinLock.Fingerprint))
        vault.markUnverified()
        // A phone that then turned the fingerprint key down: the screen-lock key is the one that stays.
        keys.fingerprintRejected = true
        vault.reseal(vault.seal(vault.encryptCipher(PinLock.ScreenLock), "482915".toCharArray(), PinLock.ScreenLock))
        assertTrue(vault.unverified.value)
        assertEquals(PinLock.ScreenLock, vault.lock.value)
        assertEquals("482915", String(vault.open(vault.decryptCipher()!!)))
    }

    @Test fun aCodePinNeedsTheCodesKey() {
        val vault = vault
        val salt = ByteArray(16)
        val right = CodeKey(AccessCode.derive("123456".toCharArray(), salt, 1000))
        val wrong = CodeKey(AccessCode.derive("654321".toCharArray(), salt, 1000))
        vault.store(vault.sealWithCode(right, "482915".toCharArray()))
        assertEquals(PinLock.Code, vault.lock.value)
        assertNull(vault.decryptCipher())
        assertEquals("482915", String(vault.openWithCode(right)))
        assertFailsWith<javax.crypto.AEADBadTagException> { vault.openWithCode(wrong) }
        // Bound to this phone too: without its device key the blob does not open.
        keys.delete("dev.duardo.neotransfer.plus.device")
        assertFailsWith<Exception> { vault.openWithCode(right) }
    }
}
