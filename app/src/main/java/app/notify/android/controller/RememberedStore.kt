package app.notify.android.controller

import app.notify.android.remote.*
import org.json.JSONObject
import org.json.JSONTokener

data class RememberedState(
    val operation: Long = 0,
    val committed: RememberAuthorization? = null,
    val pending: RememberProposal? = null,
) {
    val authorizations get() = listOfNotNull(committed, pending?.authorization).distinctBy { it.generation }
    fun matching(record: Relationship?) = authorizations.any { it.relationship == record }
}

class RememberedStore(private val storage: RelationshipStorage) {
    @Volatile var persistenceFailed = false
        private set
    @Volatile var state = try { load() } catch (_: Exception) { persistenceFailed = true; RememberedState() }
        private set

    @Synchronized fun current(operation: Long, record: Relationship) =
        !persistenceFailed && state.operation == operation &&
            (state.authorizations.isEmpty() || state.matching(record))

    @Synchronized fun begin(record: Relationship): Long {
        check(!persistenceFailed) { "Storage must be repaired before remembering this phone." }
        save(if (state.matching(record)) state.copy(operation = state.operation + 1)
            else RememberedState(operation = state.operation + 1))
        return state.operation
    }
    @Synchronized fun propose(operation: Long, proposal: RememberProposal) {
        check(current(operation, proposal.authorization.relationship))
        save(state.copy(pending = proposal))
    }
    @Synchronized fun confirm(operation: Long, authorization: RememberAuthorization): Boolean {
        if (!current(operation, authorization.relationship) || authorization !in state.authorizations) return false
        save(state.copy(committed = authorization,
            pending = state.pending?.takeUnless { it.authorization == authorization }))
        return true
    }
    @Synchronized fun off(): RememberedState {
        val previous = state
        save(RememberedState(operation = previous.operation + 1))
        return previous
    }
    @Synchronized fun reject(operation: Long, authorization: RememberAuthorization) {
        if (!current(operation, authorization.relationship)) return
        if (state.committed == authorization)
            save(state.copy(operation = state.operation + 1, committed = null))
    }
    @Synchronized fun reconcile(operation: Long, record: Relationship, response: JSONObject) {
        exact(response, setOf("macID", "deviceID", "committed", "pending", "push"))
        require(response.opt("macID") == record.macID && response.opt("deviceID") == record.deviceID)
        PushMetadata.parse(response.getJSONObject("push"))
        val committed = response.opt("committed")
        var found: RememberAuthorization? = null
        if (committed != JSONObject.NULL) {
            require(committed is JSONObject)
            exact(committed, setOf("generation", "authorizationExpiresAt", "state"))
            require(committed.opt("state") in setOf("active", "revoked", "expired"))
            val candidate = RememberAuthorization(record, committed.getString("generation"),
                time(committed.opt("authorizationExpiresAt")))
            if (committed.opt("state") == "active") found = candidate
        }
        val pending = response.opt("pending")
        val proposal = if (pending == JSONObject.NULL) null else {
            require(pending is JSONObject)
            RememberProposal.parse(pending, record, status = true)
        }
        if (!current(operation, record) || !state.matching(record)) return
        val active = found?.takeIf { it in state.authorizations }
        save(state.copy(operation = state.operation + if (state.committed != null && active == null) 1 else 0,
            committed = active, pending = state.pending?.takeIf {
            it == proposal && it.authorization != active
        }))
    }
    private fun save(next: RememberedState) {
        try {
            storage.write(JSONObject().put("version", 1).put("operation", next.operation)
                .put("committed", next.committed?.let(::authorizationJson) ?: JSONObject.NULL)
                .put("pending", next.pending?.let { JSONObject().put("id", it.id).put("expiresAt", it.expiresAt)
                    .put("authorization", authorizationJson(it.authorization)) } ?: JSONObject.NULL).toString())
            state = next
            persistenceFailed = false
        } catch (error: Exception) {
            state = RememberedState(operation = next.operation + 1)
            persistenceFailed = true
            throw IllegalStateException("Could not confirm remembered access is off in phone storage. Repair storage and sign out again before closing Notify.", error)
        }
    }
    private fun load(): RememberedState {
        val raw = storage.read() ?: return RememberedState()
        require(raw.length <= 16384)
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000')
        exact(value, setOf("version", "operation", "committed", "pending"))
        require(value.opt("version") == 1)
        val committed = if (value.isNull("committed")) null else authorization(value.getJSONObject("committed"))
        val pending = if (value.isNull("pending")) null else value.getJSONObject("pending").let {
            exact(it, setOf("id", "expiresAt", "authorization"))
            require(b32(it.getString("id")))
            RememberProposal(it.getString("id"), authorization(it.getJSONObject("authorization")), time(it.opt("expiresAt")))
        }
        require(committed == null || pending == null || committed.relationship == pending.authorization.relationship)
        return RememberedState(time(value.opt("operation")), committed, pending)
    }
    private fun authorizationJson(value: RememberAuthorization): JSONObject {
        val r = value.relationship
        return JSONObject().put("generation", value.generation).put("expiresAt", value.expiresAt)
            .put("account", r.account.json()).put("macID", r.macID).put("macPublicKey", r.macPublicKey)
            .put("deviceID", r.deviceID).put("publicKey", r.publicKey).put("name", r.name).put("enrolled", r.enrolled)
    }
    private fun authorization(value: JSONObject): RememberAuthorization {
        exact(value, setOf("generation", "expiresAt", "account", "macID", "macPublicKey", "deviceID", "publicKey", "name", "enrolled"))
        val record = Relationship(Account.parse(value.getJSONObject("account")), value.text("macID").also(::canonicalUUID),
            value.text("macPublicKey").also(::decodeKey), value.text("deviceID").also(::canonicalUUID),
            value.text("publicKey").also(::decodeKey), value.text("name", 80, true), value.bool("enrolled"))
        return RememberAuthorization(record, value.getString("generation"), time(value.opt("expiresAt")))
    }
}
