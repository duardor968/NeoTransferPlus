package dev.duardo.neotransfer.plus.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.platform.AccessCode
import dev.duardo.neotransfer.plus.platform.CodeKey
import dev.duardo.neotransfer.plus.ui.model.CODE_LENGTH
import dev.duardo.neotransfer.plus.ui.model.CodeEntryUi
import dev.duardo.neotransfer.plus.ui.model.CodeStep
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Up to six digits typed on the keypad, kept in a char array that is wiped, never in a string. */
private class Digits {
    private val chars = CharArray(CODE_LENGTH)
    var count by mutableIntStateOf(0)
        private set

    val full get() = count == CODE_LENGTH

    fun type(key: KeypadKey) {
        when (key) {
            is KeypadKey.Digit -> if (!full) chars[count++] = '0' + key.value
            KeypadKey.Backspace -> if (count > 0) chars[--count] = '\u0000'
            KeypadKey.Decimal -> Unit
        }
    }

    /** What was typed, as a copy the caller owns; the keypad's own copy is wiped. */
    fun take(): CharArray = chars.copyOf(count).also { wipe() }

    fun matches(other: CharArray) = count == other.size && (0 until count).all { chars[it] == other[it] }

    fun wipe() {
        chars.fill('\u0000')
        count = 0
    }
}

/**
 * The access code typed on the keypad and checked once its six digits are in, off the main thread ([background]).
 * While a wait runs, keys do nothing and the message counts it down. [onRight] gets the key the code derives.
 */
class CodeEntry(
    private val code: AccessCode,
    private val scope: CoroutineScope,
    private val background: CoroutineContext,
    private val onRight: (CodeKey) -> Unit,
) {
    private val digits = Digits()
    private var wrong by mutableStateOf(false)
    private var erased by mutableStateOf(false)
    private var checking by mutableStateOf(false)
    private var wait by mutableLongStateOf(0L)
    private var ticker: Job? = null

    init { countDown() }

    /** While a wait runs the dots stay empty and the keypad off; the message says how long. */
    val ui: CodeEntryUi
        get() = CodeEntryUi(
            if (wait > 0) 0 else digits.count, (wrong || erased) && wait == 0L,
            codeMessage(code.failures.value, wait, wrong, erased, code.pinUnderCode()),
            waiting = wait > 0,
        )

    fun key(key: KeypadKey) {
        if (checking || wait > 0) return
        if (key is KeypadKey.Digit) {
            wrong = false
            erased = false
        }
        digits.type(key)
        if (digits.full) check()
    }

    /** Stops the countdown and wipes what was typed when the entry goes away. */
    fun close() {
        ticker?.cancel()
        digits.wipe()
    }

    private fun check() {
        val typed = digits.take()
        checking = true
        scope.launch {
            val result = try {
                withContext(background) { code.check(typed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally { typed.fill('\u0000') }
            checking = false
            when (result) {
                is AccessCode.Check.Right -> onRight(result.key)
                is AccessCode.Check.Wrong -> {
                    wrong = true
                    erased = result.erased
                }
                is AccessCode.Check.Waiting, null -> wrong = result == null
            }
            countDown()
        }
    }

    private fun countDown() {
        ticker?.cancel()
        wait = code.remaining()
        if (wait > 0) ticker = scope.launch {
            while (wait > 0) {
                delay(wait % 1000 + 1)
                wait = code.remaining()
            }
        }
    }
}

/** A new code typed twice; a different second one starts over. Busy from then on, while the caller sets it. */
class NewCode {
    var step by mutableStateOf(CodeStep.Create)
        private set
    private var first: CharArray? = null
    private val digits = Digits()
    private var mismatch by mutableStateOf(false)
    private var busy by mutableStateOf(false)

    val ui: CodeEntryUi get() = CodeEntryUi(digits.count, mismatch, "Los códigos no coinciden".takeIf { mismatch })

    /** [onTyped] gets the code once typed twice the same; it owns (and zeroes) it. */
    fun key(key: KeypadKey, onTyped: (CharArray) -> Unit) {
        if (busy) return
        if (key is KeypadKey.Digit) mismatch = false
        digits.type(key)
        if (digits.full) typed(onTyped)
    }

    /** Wipes what was typed when the screen goes away. */
    fun close() {
        first?.fill('\u0000')
        first = null
        digits.wipe()
    }

    private fun typed(onTyped: (CharArray) -> Unit) {
        val previous = first
        when {
            previous == null -> {
                first = digits.take()
                step = CodeStep.Repeat
            }
            digits.matches(previous) -> {
                busy = true
                digits.wipe()
                first = null
                onTyped(previous)
            }
            else -> {
                close()
                mismatch = true
                step = CodeStep.Create
            }
        }
    }
}

/**
 * What the last attempt at the code means, one line each: the wrong code or the wait it started, then what the next
 * failures erase or what this one erased. Null when there is nothing to say.
 */
fun codeMessage(failures: Int, waitMillis: Long, wrong: Boolean, erased: Boolean, protectsPin: Boolean): String? {
    val left = AccessCode.ERASE_AT - failures
    return listOfNotNull(
        when {
            waitMillis > 0 -> "Demasiados intentos. Prueba en ${waitText(waitMillis)}"
            wrong -> "Código incorrecto"
            else -> null
        },
        when {
            erased -> "Se borró el PIN de pago guardado"
            protectsPin && left in 1..2 ->
                if (left == 1) "Te queda 1 intento antes de borrar el PIN de pago" else "Te quedan $left intentos antes de borrar el PIN de pago"
            else -> null
        },
    ).joinToString("\n").ifEmpty { null }
}

/** «30 s» under a minute, then whole minutes rounded up: «2 min». */
fun waitText(millis: Long): String {
    val seconds = (millis + 999) / 1000
    return if (seconds < 60) "$seconds${NoBreak}s" else "${(seconds + 59) / 60}${NoBreak}min"
}
