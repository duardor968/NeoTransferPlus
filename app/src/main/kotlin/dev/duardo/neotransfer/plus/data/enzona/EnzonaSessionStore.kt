package dev.duardo.neotransfer.plus.data.enzona

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.duardo.neotransfer.plus.core.auth.EnzonaTokens
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Blocking storage contract. The session serializes all calls on its IO dispatcher, never Main. */
internal interface TokenPersistence {
    fun load(): EnzonaTokens?
    fun save(tokens: EnzonaTokens)
    fun clear()
}

/**
 * Device-bound session material (AES-256-GCM, AndroidKeyStore), outside Android backup and any user export.
 * The key does not require an unlocked screen: the periodic credit check reads history while the device is
 * locked. It is still unusable before the first unlock after boot (credential-encrypted storage).
 */
internal class EnzonaSessionStore(
    context: Context,
    fileName: String = "enzona-session.bin",
    private val keyAlias: String = KEY_ALIAS,
) : TokenPersistence {
    init {
        require(fileName.isNotBlank() && fileName != "." && fileName != ".." &&
            '/' !in fileName && '\\' !in fileName) { "Nombre de sesión no válido" }
        require(keyAlias.isNotBlank()) { "Alias de sesión no válido" }
    }

    // Constructor is safe on Main; file resolution and KeyStore initialization occur on first
    // load/save/clear, which the session serializes on IO. Custom names are for isolated tests.
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, fileName)) }
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized override fun load(): EnzonaTokens? {
        if (!file.baseFile.exists()) return null
        val encrypted = file.openRead().use { input ->
            val data = input.readNBytes(MAX_STORED_BYTES + 1)
            require(data.size <= MAX_STORED_BYTES) { "La sesión guardada no es válida" }
            data
        }
        require(encrypted.size > 1 + IV_LENGTH + 16 && encrypted[0] == FORMAT_VERSION) { "La sesión guardada no es válida" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, requireNotNull(keyStore.getKey(keyAlias, null) as? SecretKey),
                GCMParameterSpec(128, encrypted.copyOfRange(1, 1 + IV_LENGTH)))
        }
        val plaintext = cipher.doFinal(encrypted, 1 + IV_LENGTH, encrypted.size - 1 - IV_LENGTH)
        return try { EnzonaTokens.fromEncryptedStorage(String(plaintext, Charsets.UTF_8)) }
        finally { plaintext.fill(0) }
    }

    @Synchronized override fun save(tokens: EnzonaTokens) {
        if (!keyStore.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build())
                generateKey()
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, keyStore.getKey(keyAlias, null) as SecretKey)
        }
        val plaintext = tokens.forEncryptedStorage().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            require(plaintext.size + 1 + IV_LENGTH + 16 <= MAX_STORED_BYTES) { "La sesión supera el tamaño admitido" }
            byteArrayOf(FORMAT_VERSION) + cipher.iv + cipher.doFinal(plaintext)
        } finally { plaintext.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }

    @Synchronized override fun clear() {
        file.delete()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private companion object {
        const val KEY_ALIAS = "dev.duardo.neotransfer.plus.enzona-session"
        const val IV_LENGTH = 12
        const val MAX_STORED_BYTES = 256 * 1024
        const val FORMAT_VERSION: Byte = 1
    }
}
