package dev.duardo.neotransfer.plus.platform

import android.app.Application
import android.content.Context
import dev.duardo.neotransfer.plus.app.codeMessage
import dev.duardo.neotransfer.plus.app.waitText
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The access code: creating and checking it, its waits, the tenth failure and its fingerprint key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccessCodeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keys = FakeKeys()
    private val device = FakeDeviceLock(fingerprint = true, screenLock = true)
    private var boot = 3
    private var uptime = 1_000_000L
    private var erased = 0
    /** What protects the payment PIN this test pretends is saved; null for none. */
    private var savedLock: PinLock? = null

    private fun code() = AccessCode(
        context, keys, device,
        savedPinLock = { savedLock },
        erasePin = { (savedLock != null).also { if (it) { erased++; savedLock = null } } },
        uptime = { AccessCode.Uptime(boot, uptime) },
        iterations = { 1000 },
    )

    private fun AccessCode.wrong(times: Int = 1) = repeat(times) {
        assertIs<AccessCode.Check.Wrong>(check("000000".toCharArray()))
        uptime += remaining()
    }

    @Test fun theRightCodeOpensAndTheCodeIsNeverStored() {
        val code = code()
        assertFalse(code.active.value)
        code.create("123456".toCharArray())
        assertTrue(code.active.value)
        assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray()))
        assertEquals(AccessCode.Check.Wrong(1, erased = false), code.check("123457".toCharArray()))
        val stored = context.getSharedPreferences("access_code", Context.MODE_PRIVATE).all.values.map(Any?::toString)
        assertTrue(stored.none { "123456" in it })
        // The right code ends the run of failures.
        assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray()))
        assertEquals(0, code.failures.value)
    }

    @Test fun fromTheFifthFailureItWaitsDoubling() {
        val code = code()
        code.create("123456".toCharArray())
        repeat(4) {
            code.check("000000".toCharArray())
            assertEquals(0, code.remaining())
        }
        code.check("000000".toCharArray())
        assertEquals(30_000, code.remaining())
        // Nothing is checked, not even the right code, while it waits.
        assertEquals(AccessCode.Check.Waiting(30_000), code.check("123456".toCharArray()))
        uptime += 10_000
        assertEquals(20_000, code.remaining())
        uptime += 20_000
        code.check("000000".toCharArray())
        assertEquals(60_000, code.remaining())
        uptime += 60_000
        code.check("000000".toCharArray())
        assertEquals(120_000, code.remaining())
        assertEquals(3_600_000, AccessCode.waitAfter(30))
    }

    @Test fun theCountAndTheWaitSurviveARestartOfTheApp() {
        code().apply { create("123456".toCharArray()) }.wrong(5)
        uptime -= 30_000 // back to right after the fifth failure
        uptime += 12_000
        val restarted = code()
        assertEquals(5, restarted.failures.value)
        assertEquals(18_000, restarted.remaining())
    }

    @Test fun aRebootStartsTheWaitOverInFullAndItNeverExceedsItsLength() {
        val code = code()
        code.create("123456".toCharArray())
        code.wrong(4)
        code.check("000000".toCharArray())
        uptime += 25_000
        assertEquals(5_000, code.remaining())
        // Another boot: the time since boot restarted, so the wait does too, from now.
        boot++
        uptime = 2_000
        assertEquals(30_000, code.remaining())
        uptime += 10_000
        assertEquals(20_000, code.remaining())
        // Time since boot never goes back on the same boot; if a record says so, the wait starts over, not longer.
        uptime -= 50_000
        assertEquals(30_000, code.remaining())
        // Nothing but time since boot moves it: the wall clock plays no part.
        uptime += 30_000
        assertEquals(0, code.remaining())
    }

    @Test fun theTenthFailureErasesThePinOnlyWhenTheCodeProtectsIt() {
        savedLock = PinLock.Fingerprint
        val alone = code()
        alone.create("123456".toCharArray())
        alone.wrong(12)
        assertEquals(0, erased)

        savedLock = PinLock.Code
        val protecting = code()
        protecting.create("123456".toCharArray())
        protecting.wrong(9)
        assertEquals(0, erased)
        assertEquals(AccessCode.Check.Wrong(10, erased = true), protecting.check("000000".toCharArray()))
        assertEquals(1, erased)
        uptime += protecting.remaining()
        assertEquals(AccessCode.Check.Wrong(11, erased = false), protecting.check("000000".toCharArray()))
        assertEquals(1, erased)
    }

    @Test fun aTenthFailureCutShortStillErasesOnTheNextOne() {
        savedLock = PinLock.Code
        code().create("123456".toCharArray())
        // The app was stopped during the tenth check: counted, but nothing erased.
        context.getSharedPreferences("access_code", Context.MODE_PRIVATE).edit().putInt("failures", 10).commit()
        val code = code()
        uptime += code.remaining()
        assertEquals(AccessCode.Check.Wrong(11, erased = true), code.check("000000".toCharArray()))
        assertEquals(1, erased)
        assertNull(savedLock)
    }

    @Test fun aTenthFailureWithoutASavedPinErasesNothing() {
        val code = code()
        code.create("123456".toCharArray())
        code.protectPin(true)
        code.wrong(9)
        assertEquals(AccessCode.Check.Wrong(10, erased = false), code.check("000000".toCharArray()))
    }

    @Test fun theSavedPinsOwnLockSaysWhetherTheCodeProtectsIt() {
        val code = code()
        code.create("123456".toCharArray())
        assertFalse(code.pinUnderCode())
        code.protectPin(true)
        assertTrue(code.pinUnderCode())
        // A PIN moved under the code whose choice was never written: the PIN decides.
        code.protectPin(false)
        savedLock = PinLock.Code
        assertTrue(code.pinUnderCode())
        // And the other way round.
        code.protectPin(true)
        savedLock = PinLock.Fingerprint
        assertFalse(code.pinUnderCode())
    }

    @Test fun theMessagesSayWhatTheFailuresMean() {
        val nb = ' '
        assertEquals("Código incorrecto", codeMessage(1, 0, wrong = true, erased = false, protectsPin = true))
        assertNull(codeMessage(1, 0, wrong = false, erased = false, protectsPin = false))
        assertEquals("Demasiados intentos. Prueba en 30${nb}s", codeMessage(5, 30_000, wrong = true, erased = false, protectsPin = false))
        assertEquals(
            "Demasiados intentos. Prueba en 4${nb}min\nTe quedan 2 intentos antes de borrar el PIN de pago",
            codeMessage(8, 240_000, wrong = true, erased = false, protectsPin = true),
        )
        assertEquals(
            "Demasiados intentos. Prueba en 8${nb}min\nTe queda 1 intento antes de borrar el PIN de pago",
            codeMessage(9, 480_000, wrong = true, erased = false, protectsPin = true),
        )
        assertEquals("Demasiados intentos. Prueba en 8${nb}min", codeMessage(9, 480_000, wrong = true, erased = false, protectsPin = false))
        assertEquals(
            "Demasiados intentos. Prueba en 16${nb}min\nSe borró el PIN de pago guardado",
            codeMessage(10, 960_000, wrong = true, erased = true, protectsPin = true),
        )
        assertEquals("59${nb}s", waitText(59_000))
        assertEquals("1${nb}min", waitText(59_001))
        assertEquals("2${nb}min", waitText(119_000))
    }

    @Test fun theFingerprintOpensOnlyWithTheFingersEnrolledWhenTheCodeWasTyped() {
        val code = code()
        code.create("123456".toCharArray())
        val cipher = assertNotNull(code.fingerprintCipher())
        assertTrue(code.fingerprintOpens(cipher))
        // A new enrollment kills the key: no fingerprint until the code is typed again.
        keys.invalidated += "dev.duardo.neotransfer.plus.access-code.fingerprint"
        assertNull(code.fingerprintCipher())
        assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray()))
        assertNotNull(code.fingerprintCipher())
    }

    @Test fun aFingerprintUnlockEndsTheRunOfWrongCodes() {
        val code = code()
        code.create("123456".toCharArray())
        code.wrong(6)
        assertEquals(6, code.failures.value)
        assertTrue(code.fingerprintOpens(assertNotNull(code.fingerprintCipher())))
        assertEquals(0, code.failures.value)
        assertEquals(0, code.remaining())
        assertEquals(0, code().failures.value)
    }

    @Test fun aPhoneThatRejectsFingerprintKeysOffersOnlyTheCode() {
        keys.rejectsFingerprint = true
        val code = code()
        code.create("123456".toCharArray())
        assertFalse(code.fingerprintOpens(assertNotNull(code.fingerprintCipher())))
        assertTrue(keys.fingerprintRejected)
        assertNull(code.fingerprintCipher())
        assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray()))
        assertNull(code.fingerprintCipher())
    }

    @Test fun withoutAFingerprintTheLockOffersOnlyTheCode() {
        device.fingerprint = false
        val code = code()
        code.create("123456".toCharArray())
        assertNull(code.fingerprintCipher())
    }

    @Test fun changingTheCodeMovesWhatItProtects() {
        val vault = PaymentPasswordVault(context, keys, device)
        val code = code()
        val first = code.create("123456".toCharArray())
        vault.store(vault.sealWithCode(first, "482915".toCharArray()))
        val second = code.create("654321".toCharArray()) { key -> vault.reseal(vault.sealWithCode(key, vault.openWithCode(first))) }
        assertEquals("482915", String(vault.openWithCode(second)))
        assertIs<AccessCode.Check.Wrong>(code.check("123456".toCharArray()))
        assertIs<AccessCode.Check.Right>(code.check("654321".toCharArray()))
    }

    @Test fun aChangeWhoseMoveFailsKeepsTheOldCodeAndThePin() {
        val vault = PaymentPasswordVault(context, keys, device)
        val code = code()
        val first = code.create("123456".toCharArray())
        vault.store(vault.sealWithCode(first, "482915".toCharArray()))
        val wrong = CodeKey(AccessCode.derive("999999".toCharArray(), ByteArray(16), 1000))
        // As Ajustes does it: a PIN the old key does not open throws, and the old code stays.
        val failed = runCatching {
            code.create("654321".toCharArray()) { key -> vault.reseal(vault.sealWithCode(key, vault.openWithCode(wrong))) }
        }
        assertTrue(failed.isFailure)
        assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray()))
        assertTrue(vault.saved.value)
        assertEquals("482915", String(vault.openWithCode(first)))
    }

    @Test fun removingTheCodeForgetsEverything() {
        val code = code()
        code.create("123456".toCharArray())
        code.protectPin(true)
        code.remove()
        assertFalse(code.active.value)
        assertFalse(code().protectsPin.value)
        assertFalse(keys.has("dev.duardo.neotransfer.plus.access-code.fingerprint"))
    }
}
