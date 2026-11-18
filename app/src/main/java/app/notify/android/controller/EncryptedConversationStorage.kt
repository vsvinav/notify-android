package app.notify.android.controller

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Call only on the I/O dispatcher; keys and ciphertext never leave app-private storage. */
internal class EncryptedConversationStorage(
    file: File,
    private val alias: String = "app.notify.android.conversation-cache.v1",
) : ConversationStorage {
    private val atomic = AtomicFile(file)
    private val revoked = File("${file.path}.revoked")
    private val header = byteArrayOf(0x4e, 0x43, 0x48, 1)

    override fun read(): ByteArray? {
        if (revoked.exists()) return null
        if (!atomic.baseFile.exists() && !File("${atomic.baseFile.path}.bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val result = input.boundedBytes()
            require(result.size in 32..MAX_CONVERSATION_CACHE_BYTES + 32) { "Invalid encrypted history size." }
            result
        }
        require(bytes.copyOfRange(0, 4).contentEquals(header)) { "Invalid encrypted history format." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(header)
        return cipher.doFinal(bytes, 16, bytes.size - 16)
    }

    override fun write(value: ByteArray) {
        require(value.size <= MAX_CONVERSATION_CACHE_BYTES) { "History exceeds the storage limit." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        require(cipher.iv.size == 12) { "Unexpected AndroidKeyStore GCM nonce." }
        cipher.updateAAD(header)
        val ciphertext = header + cipher.iv + cipher.doFinal(value)
        val output = atomic.startWrite()
        try {
            output.write(ciphertext)
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().use { it.boundedBytes() }.contentEquals(ciphertext)) {
                "Encrypted conversation write could not be verified."
            }
            check(!revoked.exists() || revoked.delete()) { "New saved history could not replace the cleared cache." }
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    override fun clear() {
        // A durable tombstone prevents old history returning even if key/file cleanup subsequently fails.
        var failure: Exception? = null
        try { revoked.outputStream().use { it.write(1); it.fd.sync() } }
        catch (error: Exception) { failure = error }
        try { keyStore().deleteEntry(alias) }
        catch (error: Exception) { failure = failure ?: error }
        try {
            atomic.delete()
            check(listOf("", ".bak", ".new").none { File(atomic.baseFile.path + it).exists() }) {
                "Encrypted conversation files could not be removed."
            }
        } catch (error: Exception) { failure = failure ?: error }
        failure?.let { throw it }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun InputStream.boundedBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_CONVERSATION_CACHE_BYTES + 32) { "Encrypted history is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun key(create: Boolean): SecretKey {
        val store = keyStore()
        if (store.containsAlias(alias)) return checkNotNull(store.getKey(alias, null) as? SecretKey) {
            "Saved conversation key is unavailable; no replacement was created."
        }
        check(create) { "Saved conversation key is missing; no replacement was created." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
}
