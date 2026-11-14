package dev.duardo.neotransfer.plus.app

import android.app.Application
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.platform.AccessCode
import dev.duardo.neotransfer.plus.platform.CodeKey
import dev.duardo.neotransfer.plus.platform.FakeDeviceLock
import dev.duardo.neotransfer.plus.platform.FakeKeys
import dev.duardo.neotransfer.plus.platform.FakePrompts
import dev.duardo.neotransfer.plus.platform.PaymentPasswordVault
import dev.duardo.neotransfer.plus.platform.PinLock
import dev.duardo.neotransfer.plus.ui.model.PinProtection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.fail

/** How a payment gets its password on each kind of phone, over an in-memory Keystore and prompts that always pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AuthorizerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keys = FakeKeys()
    private val device = FakeDeviceLock(fingerprint = true, screenLock = true)
    private val prompts = FakePrompts()
    private val vault = PaymentPasswordVault(context, keys, device)
    private val code = AccessCode(
        context, keys, device, savedPinLock = { vault.lock.value }, erasePin = { vault.saved.value.also { if (it) vault.clear() } }, iterations = { 1000 },
    )
    private val authorizer = Authorizer(vault, code, prompts, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    /** What the payment got: the password and whether it came sealed to keep. */
    private class Paid(val password: String, val sealed: PaymentPasswordVault.Sealed?)

    private fun pay(): Paid? {
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", "a Juan", "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
            password.fill('\u0000')
        }, onProblem = { fail(it) })
        return paid
    }

    private fun type(password: String) {
        authorizer.value(password)
        authorizer.submit()
    }

    private fun typeCode(digits: String) = digits.forEach { authorizer.codeActions.key(KeypadKey.Digit(it - '0')) }

    @Test fun withoutAnyLockThePinIsTypedForEachPaymentAndNeverKept() {
        device.fingerprint = false
        device.screenLock = false
        assertNull(pay())
        val sheet = assertNotNull(authorizer.sheet)
        assertEquals("Transferir", sheet.action)
        assertEquals(PinProtection.None, sheet.protection)
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", "a Juan", "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        assertEquals("482915", paid?.password)
        assertNull(paid?.sealed)
        assertTrue(prompts.screenLocks.isEmpty())
        assertFalse(vault.saved.value)
        // The next payment asks again.
        pay()
        assertNotNull(authorizer.sheet)
    }

    @Test fun savingWithoutAnyLockSaysWhatIsMissing() {
        device.fingerprint = false
        device.screenLock = false
        var problem: String? = null
        authorizer.save("482915".toCharArray(), onSaved = { fail("saved") }, onProblem = { problem = it })
        assertEquals("Pon un bloqueo de pantalla en el teléfono para guardar el PIN de pago", problem)
    }

    @Test fun aFingerprintPhonePaysAsBefore() {
        pay()
        assertEquals(PinProtection.Fingerprint, authorizer.sheet?.protection)
        assertEquals("Guardar y transferir", authorizer.sheet?.action)
        lateinit var first: Paid
        authorizer.authorize("Transferir 300 CUP", "a Juan", "Guardar y transferir", onPassword = { password, sealed ->
            first = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        assertEquals(listOf(false), prompts.screenLocks)
        vault.store(assertNotNull(first.sealed))
        assertEquals(PinLock.Fingerprint, vault.lock.value)
        // Saved: the fingerprint alone opens it.
        val second = assertNotNull(pay())
        assertEquals("482915", second.password)
        assertNull(second.sealed)
        assertEquals(listOf(false, false), prompts.screenLocks)
    }

    @Test fun aPhoneWithOnlyAScreenLockUsesItInsteadOfTheFingerprint() {
        device.fingerprint = false
        pay()
        assertEquals(PinProtection.ScreenLock, authorizer.sheet?.protection)
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        vault.store(assertNotNull(paid?.sealed))
        assertEquals(PinLock.ScreenLock, vault.lock.value)
        assertEquals("482915", pay()?.password)
        assertEquals(listOf(true, true), prompts.screenLocks)
    }

    @Test fun theRealmeRejectionPromptsOnceMoreWithTheAnyKey() {
        keys.rejectsFingerprint = true
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        assertEquals(listOf(false, true), prompts.screenLocks)
        assertEquals("482915", paid?.password)
        vault.store(assertNotNull(paid?.sealed))
        assertEquals(PinLock.ScreenLock, vault.lock.value)
        // From then on, one prompt with the any key.
        assertEquals("482915", pay()?.password)
        assertEquals(listOf(false, true, true), prompts.screenLocks)
    }

    @Test fun aCancelledPromptPaysNothing() {
        prompts.cancel = true
        var paid = false
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { _, _ -> paid = true }, onProblem = { fail(it) })
        type("482915")
        assertFalse(paid)
    }

    @Test fun theCodeReplacesTheFingerprintForPaymentsWhenItProtectsThePin() {
        code.create("123456".toCharArray())
        code.protectPin(true)
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", "a Juan", "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        assertEquals("Transferir 300 CUP", authorizer.codeSheet?.title)
        assertEquals("a Juan", authorizer.codeSheet?.subtitle)
        typeCode("123456")
        assertNull(authorizer.codeSheet)
        // First time: typed after the code and sealed under it.
        assertEquals(PinProtection.Code, authorizer.sheet?.protection)
        type("482915")
        vault.store(assertNotNull(paid?.sealed))
        assertEquals(PinLock.Code, vault.lock.value)
        var again: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            again = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        typeCode("123456")
        assertEquals("482915", again?.password)
        assertNull(again?.sealed)
        // Never the fingerprint or the screen lock.
        assertTrue(prompts.screenLocks.isEmpty())
    }

    @Test fun theSavedPinOpensWithTheCode() {
        code.create("123456".toCharArray())
        code.protectPin(true)
        vault.store(vault.sealWithCode(rightKey(), "482915".toCharArray()))
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        typeCode("123457")
        assertNull(paid)
        assertEquals("Código incorrecto", authorizer.codeSheet?.entry?.message)
        typeCode("123456")
        assertEquals("482915", paid?.password)
        assertNull(paid?.sealed)
    }

    @Test fun theTenthWrongCodeErasesTheProtectedPin() {
        var now = 1_000_000L
        val code = AccessCode(
            context, keys, device, savedPinLock = { vault.lock.value }, erasePin = { vault.saved.value.also { if (it) vault.clear() } },
            uptime = { AccessCode.Uptime(1, now) }, iterations = { 1000 },
        )
        val authorizer = Authorizer(vault, code, prompts, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        code.create("123456".toCharArray())
        code.protectPin(true)
        vault.store(vault.sealWithCode(assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray())).key, "482915".toCharArray()))
        repeat(9) {
            assertIs<AccessCode.Check.Wrong>(code.check("000000".toCharArray()))
            now += code.remaining()
        }
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { _, _ -> fail("paid") }, onProblem = { fail(it) })
        "000000".forEach { authorizer.codeActions.key(KeypadKey.Digit(it - '0')) }
        assertFalse(vault.saved.value)
        // The sheet waits as the lock does: keypad off, dots empty, keys ignored.
        val entry = assertNotNull(authorizer.codeSheet).entry
        assertTrue(entry.waiting)
        assertEquals(0, entry.filled)
        assertFalse(entry.error)
        authorizer.codeActions.key(KeypadKey.Digit(1))
        assertEquals(0, authorizer.codeSheet?.entry?.filled)
        assertEquals(10, code.failures.value)
        assertEquals("Demasiados intentos. Prueba en 16 min\nSe borró el PIN de pago guardado", authorizer.codeSheet?.entry?.message)
        authorizer.codeActions.dismiss()
    }

    @Test fun turningTheCodeOnAndOffMovesThePinWithoutLosingIt() {
        // Saved with the fingerprint, then under the code with one prompt, then back.
        pay()
        lateinit var first: Paid
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            first = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        vault.store(assertNotNull(first.sealed))
        vault.markUnverified()
        code.create("123456".toCharArray())
        val key = rightKey()
        prompts.screenLocks.clear()
        var done = false
        authorizer.moveToCode(key, "Usar también para el PIN de pago", onDone = { done = true }, onProblem = { fail(it) })
        assertTrue(done)
        assertEquals(listOf(false), prompts.screenLocks)
        assertEquals(PinLock.Code, vault.lock.value)
        assertEquals("482915", String(vault.openWithCode(key)))
        assertTrue(vault.unverified.value)

        var erased: Boolean? = null
        authorizer.moveFromCode(key, "Usar también para el PIN de pago", onDone = { erased = it }, onProblem = { fail(it) })
        assertEquals(false, erased)
        assertEquals(listOf(false, false), prompts.screenLocks)
        assertEquals(PinLock.Fingerprint, vault.lock.value)
        assertEquals("482915", String(vault.open(assertNotNull(vault.decryptCipher()))))
    }

    @Test fun turningTheCodeOffWithoutAnyLockErasesThePin() {
        device.fingerprint = false
        device.screenLock = false
        code.create("123456".toCharArray())
        vault.store(vault.sealWithCode(rightKey(), "482915".toCharArray()))
        var erased: Boolean? = null
        authorizer.moveFromCode(rightKey(), "Desactivar código", onDone = { erased = it }, onProblem = { fail(it) })
        assertEquals(true, erased)
        assertFalse(vault.saved.value)
    }

    @Test fun aScreenLockPinKeepsItsPromptAfterAFingerprintIsEnrolled() {
        device.fingerprint = false
        assertEquals(PinProtection.ScreenLock, authorizer.approval())
        pay()
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        vault.store(assertNotNull(paid?.sealed))
        device.fingerprint = true
        // The prompt still takes the screen lock, as its key does, and its icon says so.
        assertEquals(PinProtection.ScreenLock, authorizer.approval())
        assertEquals("482915", pay()?.password)
        assertEquals(listOf(true, true), prompts.screenLocks)
        assertEquals(PinLock.ScreenLock, vault.lock.value)
    }

    @Test fun theApprovalSaysWhatEachPhoneAsks() {
        assertEquals(PinProtection.Fingerprint, authorizer.approval())
        keys.fingerprintRejected = true
        assertEquals(PinProtection.ScreenLock, authorizer.approval())
        keys.fingerprintRejected = false
        device.fingerprint = false
        device.screenLock = false
        assertEquals(PinProtection.None, authorizer.approval())
        code.create("123456".toCharArray())
        code.protectPin(true)
        assertEquals(PinProtection.Code, authorizer.approval())
    }

    @Test fun wrongCodesInASheetCountWithTheLocksOwn() {
        code.create("123456".toCharArray())
        code.protectPin(true)
        assertIs<AccessCode.Check.Wrong>(code.check("000000".toCharArray()))
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { _, _ -> fail("paid") }, onProblem = { fail(it) })
        typeCode("000000")
        assertEquals(2, code.failures.value)
        authorizer.codeActions.dismiss()
        authorizer.askCode("¿Desactivar el código?", null) { fail("right") }
        typeCode("111111")
        assertEquals(3, code.failures.value)
        authorizer.codeActions.dismiss()
    }

    @Test fun anInterruptedMoveToTheCodeNeverAsksToTypeThePinAgain() {
        lateinit var first: Paid
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            first = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        vault.store(assertNotNull(first.sealed))
        code.create("123456".toCharArray())
        // The PIN moved under the code, and the app stopped before the choice was written.
        authorizer.moveToCode(rightKey(), "Usar también para el PIN de pago", onDone = {}, onProblem = { fail(it) })
        assertFalse(code.protectsPin.value)
        var paid: Paid? = null
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            paid = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        assertNotNull(authorizer.codeSheet)
        typeCode("123456")
        assertNull(authorizer.sheet)
        assertEquals("482915", paid?.password)
    }

    @Test fun turningTheCodeOffWithAnUnreadablePinKeepsEverything() {
        code.create("123456".toCharArray())
        code.protectPin(true)
        val stale = CodeKey(AccessCode.derive("999999".toCharArray(), ByteArray(16), 1000))
        vault.store(vault.sealWithCode(stale, "482915".toCharArray()))
        var problem: String? = null
        authorizer.moveFromCode(rightKey(), "Desactivar código", onDone = { fail("done") }, onProblem = { problem = it })
        assertEquals("No se pudo leer el PIN guardado.", problem)
        assertTrue(vault.saved.value)
        assertEquals(PinLock.Code, vault.lock.value)
        assertTrue(code.active.value)
        assertTrue(prompts.screenLocks.isEmpty())
    }

    @Test fun aMissingKeyIsTreatedAsInvalidatedAndThePinIsAskedAgain() {
        lateinit var first: Paid
        authorizer.authorize("Transferir 300 CUP", null, "Guardar y transferir", onPassword = { password, sealed ->
            first = Paid(String(password), sealed)
        }, onProblem = { fail(it) })
        type("482915")
        vault.store(assertNotNull(first.sealed))
        keys.delete("dev.duardo.neotransfer.plus.payment-password")
        assertNull(pay())
        assertNotNull(authorizer.sheet)
        assertFalse(vault.saved.value)
        assertFalse(keys.has("dev.duardo.neotransfer.plus.payment-password"))
    }

    @Test fun withoutSavingDropsOnlyTheSaving() {
        assertEquals("Pagar", withoutSaving("Guardar y pagar"))
        assertEquals("Enviar", withoutSaving("Guardar y enviar"))
    }

    private fun rightKey(): CodeKey = assertIs<AccessCode.Check.Right>(code.check("123456".toCharArray())).key
}
