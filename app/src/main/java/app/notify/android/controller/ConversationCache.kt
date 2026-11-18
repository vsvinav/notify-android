package app.notify.android.controller

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

const val MAX_CONVERSATION_CACHE_BYTES = 4 * 1024 * 1024
data class ConversationScope(val account: Account, val macID: String, val macPublicKey: String, val deviceID: String) {
    companion object {
        fun from(record: Relationship) = ConversationScope(record.account, record.macID, record.macPublicKey, record.deviceID)
    }
}
data class CachedMessage(val role: String, val title: String, val text: String, val timestamp: String)
data class CachedConversation(val target: DraftTarget, val sessionName: String, val agentName: String,
    val savedAt: Long, val sourcePartial: Boolean, val messages: List<CachedMessage>)
data class ConversationHistory(val conversations: List<CachedConversation> = emptyList()) {
    fun receive(snapshot: WorkspaceSnapshot, savedAt: Long): ConversationHistory {
        if (!snapshot.connected) return this
        val detail = snapshot.detail ?: return this
        val session = snapshot.sessions.singleOrNull { it.id == detail.sessionID && it.ticket == detail.ticket } ?: return this
        val targets = (listOf("") + detail.agents.map { it.id } +
            detail.activity.filter { it.kind in setOf("user", "assistant") }.map { it.agentID } +
            detail.controls.filter { it.action in setOf("send", "queue", "interrupt") }.map { it.agentID }).distinct()
        val updates = targets.map { agent ->
            CachedConversation(DraftTarget(session.id, agent), session.name,
                if (agent.isEmpty()) "Main · ${session.agent}" else detail.agents.firstOrNull { it.id == agent }?.name ?: agent,
                savedAt, detail.truncated || snapshot.truncated,
                conversationMessages(detail, agent).takeLast(8).map {
                    CachedMessage(it.kind, it.title, it.detail, it.timestamp)
                })
        }
        val replaced = updates.map { it.target }.toSet()
        return ConversationHistory((conversations.filterNot { it.target in replaced } + updates)
            .filter { it.messages.isNotEmpty() }.takeLast(32))
    }
}

internal fun conversationMessages(detail: SessionDetail, agentID: String): List<Activity> {
    val messages = detail.activity.filter { it.agentID == agentID && it.kind in setOf("user", "assistant") }
        .map { it.copy(id = "activity:${it.id}") }.toMutableList()
    for (receipt in detail.controls.filter { it.agentID == agentID && it.action in setOf("send", "queue", "interrupt") }) {
        if (messages.none { it.clientTurnID == receipt.id }) messages.add(Activity("receipt:${receipt.id}", "user",
            "Your message · ${receipt.status}", receipt.message, receipt.timestamp, receipt.agentID))
    }
    return messages.sortedBy { it.timestamp }
}
interface ConversationStorage {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun clear()
}
class ConversationCacheException(cause: Exception) : Exception(
    "Saved conversations could not be read, saved or cleared. Check device storage/security, then retry. " +
        "Connect for fresh history; no cached actions are available.", cause)
data class ConversationLease(val scope: ConversationScope, val generation: Long)
class ConversationCacheStore(private val storage: ConversationStorage) {
    private val generation = AtomicLong()
    private var history = ConversationHistory()
    private var historyScope: ConversationScope? = null
    fun open(scope: ConversationScope) = ConversationLease(scope, generation.incrementAndGet())
    fun revoke() { generation.incrementAndGet() }
    fun current(lease: ConversationLease) = lease.generation == generation.get()

    @Synchronized fun restore(lease: ConversationLease): ConversationHistory = checked {
        if (!current(lease)) return@checked ConversationHistory()
        history = ConversationHistory()
        historyScope = lease.scope
        val bytes = storage.read() ?: return@checked history
        val restored = decode(bytes, lease.scope)
        if (current(lease)) history = restored
        history
    }

    @Synchronized fun save(lease: ConversationLease, snapshot: WorkspaceSnapshot, savedAt: Long): ConversationHistory? = checked {
        if (!current(lease)) return@checked null
        if (historyScope != lease.scope) {
            history = ConversationHistory()
            historyScope = lease.scope
        }
        var updated = history.receive(snapshot, savedAt)
        val old = history.conversations.associateBy { it.target }
        if (updated.conversations.size == old.size && updated.conversations.all {
                it.copy(savedAt = old[it.target]?.savedAt ?: it.savedAt) == old[it.target]
            }) return@checked history
        var bytes = encode(updated, lease.scope)
        while (bytes.size > MAX_CONVERSATION_CACHE_BYTES && updated.conversations.size > 1) {
            updated = ConversationHistory(updated.conversations.drop(1))
            bytes = encode(updated, lease.scope)
        }
        require(bytes.size <= MAX_CONVERSATION_CACHE_BYTES) { "Conversation exceeds the cache byte limit." }
        require(decode(bytes, lease.scope) == updated) { "Saved history could not preserve the supplied text." }
        if (!current(lease)) return@checked null
        storage.write(bytes)
        // Clear runs under this same lock after revocation, including if revocation crossed an atomic write.
        if (!current(lease)) return@checked null
        history = updated
        history
    }

    @Synchronized fun clear() = checked {
        history = ConversationHistory()
        historyScope = null
        storage.clear()
    }

    private fun <T> checked(block: () -> T): T = try { block() }
        catch (error: Exception) { throw ConversationCacheException(error) }

    private fun encode(history: ConversationHistory, scope: ConversationScope): ByteArray =
        JSONObject().put("version", 1).put("scope", JSONObject().put("account", scope.account.json())
            .put("macID", scope.macID).put("macPublicKey", scope.macPublicKey).put("deviceID", scope.deviceID))
            .put("conversations", JSONArray(history.conversations.map { c ->
                JSONObject().put("sessionID", c.target.sessionID).put("agentID", c.target.agentID)
                    .put("sessionName", c.sessionName).put("agentName", c.agentName).put("savedAt", c.savedAt)
                    .put("sourcePartial", c.sourcePartial).put("messages", JSONArray(c.messages.map { m ->
                        JSONObject().put("role", m.role).put("title", m.title).put("text", m.text).put("timestamp", m.timestamp)
                    }))
            })).toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray, scope: ConversationScope): ConversationHistory {
        require(bytes.size <= MAX_CONVERSATION_CACHE_BYTES) { "Saved history exceeds its byte limit." }
        val raw = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (escaped) escaped = false
            else if (quoted && char == '\\') escaped = true
            else if (char == '"') quoted = !quoted
            else if (!quoted && (char == '[' || char == '{')) {
                depth++
                require(depth <= 8) { "Saved history nesting exceeds its limit." }
            } else if (!quoted && (char == ']' || char == '}')) depth--
        }
        val parser = JSONTokener(raw)
        val root = parser.nextValue()
        require(root is JSONObject && parser.nextClean() == '\u0000') { "Invalid saved history." }
        require(root.toString() == raw) { "Saved history is not in the app's canonical format." }
        root.only("version", "scope", "conversations")
        require(root.opt("version") == 1) { "Unsupported saved history version." }
        val s = root.getJSONObject("scope").also { it.only("account", "macID", "macPublicKey", "deviceID") }
        val account = s.getJSONObject("account").also { it.only("issuer", "subject") }
        require(ConversationScope(Account.parse(account), s.text("macID", 128, true),
            s.text("macPublicKey", 128, true), s.text("deviceID", 128, true)) == scope) { "Saved history belongs to another pairing." }
        val conversations = root.objects("conversations", 32).map { c ->
            c.only("sessionID", "agentID", "sessionName", "agentName", "savedAt", "sourcePartial", "messages")
            val saved = c.opt("savedAt")
            require((saved is Long || saved is Int) && (saved as Number).toLong() >= 0) { "Invalid saved time." }
            CachedConversation(DraftTarget(c.text("sessionID", 128, true), c.text("agentID", 128)),
                c.text("sessionName", 2048), c.text("agentName", 2048), (saved as Number).toLong(), c.bool("sourcePartial"),
                c.objects("messages", 8).map { m ->
                    m.only("role", "title", "text", "timestamp")
                    CachedMessage(m.text("role", 16).also { require(it in setOf("user", "assistant")) },
                        m.text("title", 300), m.text("text", 65_536), m.text("timestamp", 128))
                })
        }
        require(conversations.map { it.target }.distinct().size == conversations.size) { "Duplicate saved targets." }
        return ConversationHistory(conversations)
    }

    private fun JSONObject.only(vararg fields: String) {
        require(keys().asSequence().toSet() == fields.toSet()) { "Unexpected saved history fields." }
    }
}
