package dev.duardo.neotransfer.plus.platform

import android.os.CancellationSignal
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Keystore in memory: software AES keys by alias. [rejectsFingerprint] plays the realme Note 60, whose Keystore
 * turns a fingerprint key down right after a valid fingerprint; [invalidated] plays an enrollment that killed a key.
 */
class FakeKeys : Keys {
    override var fingerprintRejected = false
    var rejectsFingerprint = false
    val invalidated = mutableSetOf<String>()
    private val keys = mutableMapOf<String, Pair<KeyKind, SecretKey>>()

    fun kindOf(alias: String): KeyKind? = keys[alias]?.first

    override fun cipher(alias: String, kind: KeyKind, mode: Int, iv: ByteArray?): Cipher {
        if (alias in invalidated) throw KeyPermanentlyInvalidatedException()
        // As the Android one: a missing key is made only to encrypt.
        if (alias !in keys && mode == Cipher.DECRYPT_MODE) throw KeyPermanentlyInvalidatedException()
        val (stored, key) = keys.getOrPut(alias) { kind to KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val real = Cipher.getInstance("AES/GCM/NoPadding").apply { if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv)) }
        return if (rejectsFingerprint && stored == KeyKind.Fingerprint) Rejecting(real, mode) else real
    }

    override fun has(alias: String) = alias in keys

    override fun delete(alias: String) {
        keys -= alias
        invalidated -= alias
    }

    /** A cipher whose operation the Keystore refuses as it does on that phone: `KEY_USER_NOT_AUTHENTICATED`. */
    private class Rejecting(real: Cipher, mode: Int) : Cipher(RejectingSpi(real), FakeProvider, "AES/GCM/NoPadding") {
        init { init(mode, null as Key?) }
    }

    @Suppress("DEPRECATION")
    private object FakeProvider : Provider("FakeKeystore", 1.0, "Test")

    private class RejectingSpi(private val real: Cipher) : CipherSpi() {
        override fun engineSetMode(mode: String?) = Unit
        override fun engineSetPadding(padding: String?) = Unit
        override fun engineGetBlockSize() = 16
        override fun engineGetOutputSize(inputLen: Int) = inputLen + 16
        override fun engineGetIV(): ByteArray? = real.iv
        override fun engineGetParameters(): AlgorithmParameters? = null
        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = Unit
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) = Unit
        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = throw rejection()
        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = throw rejection()
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = throw rejection()
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = throw rejection()
        private fun rejection() = IllegalBlockSizeException("Keystore operation failed").apply { initCause(UserNotAuthenticatedException()) }
    }
}

class FakeDeviceLock(var fingerprint: Boolean, var screenLock: Boolean) : DeviceLock {
    override fun fingerprint() = fingerprint
    override fun screenLock() = screenLock
}

/** Prompts that the person always passes ([cancel] to dismiss them instead); [screenLocks] records what each allowed. */
class FakePrompts : Prompts {
    val screenLocks = mutableListOf<Boolean>()
    var cancel = false

    override fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String?,
        screenLock: Boolean,
        onSuccess: (Cipher) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal {
        screenLocks += screenLock
        if (cancel) onCancelled() else onSuccess(cipher)
        return CancellationSignal()
    }
}
