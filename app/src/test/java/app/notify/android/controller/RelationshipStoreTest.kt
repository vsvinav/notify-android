package app.notify.android.controller

import app.notify.android.remote.PairingLink
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RelationshipStoreTest {
    private val public = byteArrayOf(4) + ByteArray(64) { 1 }
    private val pin = Base64.getUrlEncoder().withoutPadding().encodeToString(public)
    private class Memory : RelationshipStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }
    private inner class Keys : IdentityKeys<String> {
        var key: ByteArray? = null
        var creates = 0
        override fun create(id: String): ByteArray { creates++; key = public; return public }
        override fun load(id: String, expected: ByteArray): String {
            check(key != null && key!!.contentEquals(expected)) { "Missing or changed identity" }
            return id
        }
        override fun delete(id: String) { key = null }
    }
    @Test fun restartLoadsPinnedIdentityWithoutCreatingAndAccountExpiryKeepsMetadata() {
        val memory = Memory()
        val keys = Keys()
        val store = RelationshipStore(memory, keys)
        val record = store.create(Account("issuer", "subject"), PairingLink("setup", "00000000-0000-4000-8000-000000000001", pin), "Phone")
        val restarted = RelationshipStore(memory, keys)
        assertEquals(record, restarted.read())
        assertEquals(record.deviceID, restarted.identity(record))
        assertEquals(1, keys.creates)
        // Account cookies live outside this store; signing out never writes relationship metadata.
        assertEquals(record, RelationshipStore(memory, keys).read())
    }
    @Test fun missingOrChangedKeyRefusesReloadAndPreservesRelationship() {
        val memory = Memory()
        val keys = Keys()
        val store = RelationshipStore(memory, keys)
        val record = store.create(Account("issuer", "subject"), PairingLink("setup", "00000000-0000-4000-8000-000000000001", pin), "Phone")
        keys.key = null
        assertThrows(IllegalStateException::class.java) { store.identity(record) }
        keys.key = byteArrayOf(4) + ByteArray(64) { 2 }
        assertThrows(IllegalStateException::class.java) { store.identity(record) }
        assertEquals(record, store.read())
        assertEquals(1, keys.creates)
    }
}
