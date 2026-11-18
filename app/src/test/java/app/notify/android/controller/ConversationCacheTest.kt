package app.notify.android.controller

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConversationCacheTest {
    @Test fun sendReceiptsKeepTruthfulOfflineLabelsAndDeduplicateOnlyMatchingActivity() {
        val receipts = listOf("submitted", "unknown", "failed").mapIndexed { index, status ->
            ControlReceipt("private-receipt-$index", "send", "$status instruction", status, "", "$index")
        }
        val detail = snapshot().detail!!.copy(activity = listOf(
            Activity("runtime", "user", "Received", "consumed instruction", "3", clientTurnID = "consumed-receipt"),
            Activity("helper", "user", "Helper message", "different target", "4", "helper", "private-receipt-0")
        ), controls = receipts + ControlReceipt("consumed-receipt", "send", "consumed instruction", "received", "", "3"))
        val projected = conversationMessages(detail, "")
        assertEquals(4, projected.size)
        assertEquals(listOf("Your message · submitted", "Your message · unknown", "Your message · failed", "Received"),
            projected.map { it.title })
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val scope = ConversationScope.from(relationship)
        store.save(store.open(scope), snapshot().copy(detail = detail), 100)
        val restored = ConversationCacheStore(storage).let { it.restore(it.open(scope)) }
        assertEquals(projected.map { it.title }, restored.conversations.single { it.target.agentID.isEmpty() }.messages.map { it.title })
        val bytes = storage.data!!.toString(Charsets.UTF_8)
        for (private in listOf("private-receipt", "consumed-receipt", "clientTurnID", "controls", "capabilities"))
            assertFalse(bytes.contains(private))
    }

    private val relationship = Relationship(Account("issuer", "subject"), "mac", "pin", "device", "public", "Phone", true)
    private val session = Session("s", "Build", "private-project", "copilot", "working", true,
        "secret-ticket", Capabilities(true, true, true))
    private fun snapshot() = WorkspaceSnapshot(true, listOf(session), SessionDetail("s", session.ticket,
        listOf(Agent("helper", "Reviewer", "running")), (0..11).flatMap { index ->
            listOf("", "helper").map { agent ->
                Activity("$agent-$index", "assistant", "Response", "$agent message $index", "%02d".format(index), agent)
            }
        } + Activity("tool", "tool", "Secret tool", "not conversation", "99"), emptyList(), truncated = true))
    private class Storage : ConversationStorage {
        var data: ByteArray? = null
        var fail = false
        override fun read() = data
        override fun write(value: ByteArray) { check(!fail) { "disk full" }; data = value }
        override fun clear() { data = null }
    }

    @Test fun keepsLatestEightPerExactTargetWithoutTruncatingLiveHistory() {
        val source = snapshot()
        val cache = ConversationHistory().receive(source, 123)
        assertEquals(25, source.detail!!.activity.size)
        assertEquals(2, cache.conversations.size)
        for (conversation in cache.conversations) {
            assertEquals(8, conversation.messages.size)
            assertEquals("${conversation.target.agentID} message 4", conversation.messages.first().text)
            assertTrue(conversation.sourcePartial)
        }
        assertEquals(2, cache.receive(source.copy(connected = false), 999).conversations.size)
        val other = source.copy(sessions = listOf(session.copy(id = "s2")),
            detail = source.detail.copy(sessionID = "s2"))
        assertEquals(4, cache.receive(other, 124).conversations.size)
    }

    @Test fun replacementUsesActualFreshContentRatherThanMergingOldMessages() {
        val source = snapshot()
        val initial = ConversationHistory().receive(source, 123)
        val fresh = source.copy(detail = source.detail!!.copy(activity = listOf(
            Activity("new", "assistant", "", "fresh partial message", "00")), truncated = false))
        val updated = initial.receive(fresh, 124)
        assertEquals(listOf("fresh partial message"), updated.conversations.single { it.target.agentID.isEmpty() }.messages.map { it.text })
        assertFalse(updated.conversations.any { it.target.agentID == "helper" })
    }

    @Test fun emptyHelpersCannotEvictMainHistoryAndEmptyReplacementsClearOldMessages() {
        val source = snapshot()
        val main = source.detail!!.activity.filter { it.agentID.isEmpty() && it.kind == "assistant" }.takeLast(8)
        val detail = source.detail.copy(
            agents = (1..100).map { Agent("helper-$it", "Empty helper $it", "idle") },
            activity = main,
        )
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        val history = store.save(lease, source.copy(detail = detail), 1)!!
        assertEquals(1, history.conversations.size)
        assertEquals("", history.conversations.single().target.agentID)
        assertEquals(main.map { it.detail }, history.conversations.single().messages.map { it.text })
        val cleared = store.save(lease, source.copy(detail = detail.copy(activity = emptyList())), 2)!!
        assertTrue(cleared.conversations.isEmpty())
        val restarted = ConversationCacheStore(storage)
        assertTrue(restarted.restore(restarted.open(ConversationScope.from(relationship))).conversations.isEmpty())
    }

    @Test fun restartRestoresOnlyDisplayDataAndRejectsOtherAccountMacOrPairing() {
        val storage = Storage()
        val scope = ConversationScope.from(relationship)
        val store = ConversationCacheStore(storage)
        val lease = store.open(scope)
        val saved = store.save(lease, snapshot(), 123)!!
        val restored = ConversationCacheStore(storage).let { it.restore(it.open(scope)) }
        assertEquals(saved, restored)
        val encoded = storage.data!!.toString(Charsets.UTF_8)
        for (forbidden in listOf("secret-ticket", "capabilities", "controls", "private-project", "clientTurnID", "draft", "command"))
            assertFalse(forbidden, encoded.contains(forbidden))
        for (other in listOf(relationship.copy(account = Account("issuer", "other")),
            relationship.copy(macID = "other"), relationship.copy(macPublicKey = "other"),
            relationship.copy(deviceID = "other"))) {
            val restarted = ConversationCacheStore(storage)
            assertThrows(ConversationCacheException::class.java) {
                restarted.restore(restarted.open(ConversationScope.from(other)))
            }
        }
    }

    @Test fun explicitClearFencesLateWritesAndRestartCannotRestore() {
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        store.save(lease, snapshot(), 1)
        store.revoke()
        assertNull(store.save(lease, snapshot(), 2))
        store.clear()
        assertNull(store.save(lease, snapshot(), 3))
        assertNull(storage.data)
        val restarted = ConversationCacheStore(storage)
        assertTrue(restarted.restore(restarted.open(ConversationScope.from(relationship))).conversations.isEmpty())
    }

    @Test fun newPairingLeaseCannotCarryPreviousAccountsInMemoryHistory() {
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        store.save(store.open(ConversationScope.from(relationship)), snapshot(), 1)
        val replacement = ConversationScope.from(relationship.copy(account = Account("issuer", "new")))
        val lease = store.open(replacement)
        val source = snapshot()
        val saved = store.save(lease, source.copy(sessions = listOf(session.copy(id = "new-session")),
            detail = source.detail!!.copy(sessionID = "new-session")), 2)!!
        assertTrue(saved.conversations.all { it.target.sessionID == "new-session" })
    }

    @Test fun invalidShapesAndDeeplyNestedStorageFailBoundedly() {
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        store.save(lease, snapshot(), 1)
        val valid = storage.data!!.toString(Charsets.UTF_8)
        val nineMessages = JSONObject(valid).apply {
            val messages = getJSONArray("conversations").getJSONObject(0).getJSONArray("messages")
            messages.put(messages.getJSONObject(0))
        }.toString()
        val wrongRole = JSONObject(valid).apply {
            getJSONArray("conversations").getJSONObject(0).getJSONArray("messages").getJSONObject(0).put("role", "tool")
        }.toString()
        for (invalid in listOf(nineMessages, wrongRole, "[".repeat(10_000) + "]".repeat(10_000))) {
            storage.data = invalid.toByteArray()
            assertThrows(ConversationCacheException::class.java) { store.restore(lease) }
        }
    }

    @Test fun clearAfterAnInFlightWriteCannotResurrectOldHistory() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var data: ByteArray? = null
        val storage = object : ConversationStorage {
            override fun read() = data
            override fun write(value: ByteArray) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                data = value
            }
            override fun clear() { data = null }
        }
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        val result = AtomicReference<ConversationHistory?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { result.set(store.save(lease, snapshot(), 1)) } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            store.revoke()
        } finally { release.countDown() }
        store.clear()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertNull(result.get())
        assertNull(data)
        assertNull(store.save(lease, snapshot(), 2))
    }

    @Test fun cacheProjectsDeliveredTextWithoutPersistingControlIdentifiersOrState() {
        val source = snapshot()
        val detail = source.detail!!.copy(activity = listOf(Activity("activity", "user", "", "reported", "1",
            clientTurnID = "private-receipt")), controls = listOf(
            ControlReceipt("private-receipt", "queue", "reported", "received", "secret-detail", "1"),
            ControlReceipt("pending-receipt", "interrupt", "exact instruction\n", "unknown", "secret-detail", "2"),
            ControlReceipt("stop-id", "stop", "not a message", "stopped", "", "3")))
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val saved = store.save(store.open(ConversationScope.from(relationship)), source.copy(detail = detail), 1)!!
        assertEquals(listOf("reported", "exact instruction\n"), saved.conversations.single { it.target.agentID.isEmpty() }.messages.map { it.text })
        val raw = storage.data!!.toString(Charsets.UTF_8)
        for (forbidden in listOf("private-receipt", "pending-receipt", "secret-detail", "interrupt", "stop-id"))
            assertFalse(forbidden, raw.contains(forbidden))
    }

    @Test fun savedInstructionAttemptsRetainUncertainAndFailedDeliveryLabelsWithoutAuthority() {
        val source = snapshot()
        val detail = source.detail!!.copy(activity = emptyList(), controls = listOf(
            ControlReceipt("private-unknown-id", "queue", "Uncertain attempt", "unknown", "private-ledger-detail", "1"),
            ControlReceipt("private-failed-id", "interrupt", "Failed attempt", "failed", "private-ledger-detail", "2")))
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val scope = ConversationScope.from(relationship)
        store.save(store.open(scope), source.copy(detail = detail), 1)
        val restarted = ConversationCacheStore(storage)
        val messages = restarted.restore(restarted.open(scope)).conversations.single().messages
        assertEquals(listOf("Your message · unknown", "Your message · failed"), messages.map { it.title })
        assertEquals(listOf("Uncertain attempt", "Failed attempt"), messages.map { it.text })
        val encoded = storage.data!!.toString(Charsets.UTF_8)
        for (forbidden in listOf("private-unknown-id", "private-failed-id", "private-ledger-detail", "\"status\"", "\"action\"", "\"controls\"", "\"ticket\""))
            assertFalse(forbidden, encoded.contains(forbidden))
    }

    @Test fun rejectsMalformedOversizeAndUnknownAuthorityFieldsWithExplicitErrors() {
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        store.save(lease, snapshot(), 123)
        val valid = storage.data!!
        val unknown = JSONObject(valid.toString(Charsets.UTF_8)).put("ticket", "authority").toString().toByteArray()
        for (invalid in listOf("not json".toByteArray(), valid + byteArrayOf(1), unknown,
            ByteArray(MAX_CONVERSATION_CACHE_BYTES + 1))) {
            storage.data = invalid
            assertThrows(ConversationCacheException::class.java) { store.restore(lease) }
        }
        storage.data = valid
        storage.fail = true
        assertThrows(ConversationCacheException::class.java) { store.save(lease, snapshot(), 124) }
    }

    @Test fun boundsConversationCountAndEncodedBytes() {
        var history = ConversationHistory()
        repeat(40) { index ->
            val source = snapshot()
            history = history.receive(source.copy(sessions = listOf(session.copy(id = "s$index")),
                detail = source.detail!!.copy(sessionID = "s$index")), index.toLong())
        }
        assertEquals(32, history.conversations.size)
        assertFalse(history.conversations.any { it.target.sessionID == "s0" })
        val storage = Storage()
        val store = ConversationCacheStore(storage)
        val lease = store.open(ConversationScope.from(relationship))
        repeat(12) { index ->
            val source = snapshot()
            store.save(lease, source.copy(sessions = listOf(session.copy(id = "large$index")),
                detail = source.detail!!.copy(sessionID = "large$index", activity = source.detail.activity.map {
                    it.copy(detail = "😀".repeat(32_768))
                })), index.toLong())
        }
        assertTrue(storage.data!!.size <= MAX_CONVERSATION_CACHE_BYTES)
        assertTrue(store.restore(lease).conversations.size < 24)
    }
}
