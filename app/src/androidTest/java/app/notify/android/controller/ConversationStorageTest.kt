@file:Suppress("DEPRECATION")

package app.notify.android.controller

import android.test.InstrumentationTestCase
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Synthetic keys/files only; never opens the production cache or channel identity. */
class ConversationStorageTest : InstrumentationTestCase() {
    fun testKeyRetirementStillRunsWhenStorageClearFails() {
        val context = instrumentation.targetContext
        assertEquals("app.notify.android.layoutcheck", context.packageName)
        val id = UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "conversation-failure-fixture-$id.enc")
        val alias = "app.notify.android.test.conversations.$id"
        val storage = EncryptedConversationStorage(file, alias)
        try {
            storage.write("Synthetic".toByteArray())
            val unavailable = EncryptedConversationStorage(File(context.noBackupFilesDir, "absent-$id/cache.enc"), alias)
            assertFails { unavailable.clear() }
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            assertFails { storage.read() }
        } finally {
            cleanup(storage, file)
        }
    }

    fun testEncryptedRestartTamperBoundsAndClear() {
        val context = instrumentation.targetContext
        assertEquals("Use the isolated layoutCheck package", "app.notify.android.layoutcheck", context.packageName)
        val id = UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "conversation-fixture-$id.enc")
        val alias = "app.notify.android.test.conversations.$id"
        val storage = EncryptedConversationStorage(file, alias)
        val plain = "Synthetic private message\n".toByteArray()
        try {
            storage.write(plain)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("Synthetic private message"))
            assertTrue(EncryptedConversationStorage(file, alias).read()!!.contentEquals(plain))
            val encrypted = file.readBytes()
            file.writeBytes(encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFails { storage.read() }
            file.writeBytes(ByteArray(MAX_CONVERSATION_CACHE_BYTES + 33))
            assertFails { storage.read() }
            file.writeBytes(encrypted)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            assertFails { storage.read() }
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            storage.clear()
            assertNull(storage.read())
            assertFalse(file.exists())
            file.writeBytes(encrypted)
            assertNull("A cleared cache must stay retired even if an old file remains", storage.read())
            storage.write(plain)
            assertTrue(storage.read()!!.contentEquals(plain))
            val scope = ConversationScope(Account("fixture-issuer", "fixture-account"), "fixture-mac", "fixture-pin", "fixture-phone")
            val session = Session("fixture-session", "Synthetic", "", "copilot", "idle", true, "never-save-ticket",
                Capabilities(true, true, true))
            val snapshot = WorkspaceSnapshot(true, listOf(session), SessionDetail(session.id, session.ticket, emptyList(),
                listOf(Activity("fixture-message", "assistant", "Response", "# Synthetic\n\n```sh\n  echo hello  \n```", "1")), emptyList()))
            val store = ConversationCacheStore(storage)
            val saved = store.save(store.open(scope), snapshot, 123)
            val restarted = ConversationCacheStore(EncryptedConversationStorage(file, alias))
            assertEquals(saved, restarted.restore(restarted.open(scope)))
        } finally {
            cleanup(storage, file)
        }
    }

    private fun cleanup(storage: EncryptedConversationStorage, file: File) {
        try { storage.clear() } finally {
            val remaining = listOf("", ".bak", ".new", ".revoked").filter { suffix ->
                val owned = File(file.path + suffix)
                owned.exists() && !owned.delete()
            }
            assertTrue("Fixture-owned files could not be removed", remaining.isEmpty())
        }
    }

    private fun assertFails(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Invalid or unreadable ciphertext must fail explicitly without a plaintext fallback.")
    }
}
