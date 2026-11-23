package app.notify.android.remote

import app.notify.android.controller.Account
import app.notify.android.controller.Relationship
import app.notify.android.controller.decodeKey
import app.notify.android.controller.encodeKey
import java.util.Base64
import org.json.JSONObject
import org.json.JSONTokener

data class RememberAuthorization(val relationship: Relationship, val generation: String, val expiresAt: Long) {
    init { require(b32(generation) && expiresAt in 1..9_007_199_254_740_991L && relationship.enrolled) }
}

internal data class RememberRevocation(val generation: String, val cancelledPending: Boolean)

data class PushMetadata(val enabled: Boolean, val registrationGeneration: String?) {
    companion object {
        fun parse(value: JSONObject): PushMetadata {
            exact(value, setOf("enabled", "registrationGeneration"))
            val generation = nullableGeneration(value.opt("registrationGeneration"))
            val enabled = value.opt("enabled") as? Boolean ?: error("Invalid notification routing state.")
            require(!enabled || generation != null)
            return PushMetadata(enabled, generation)
        }
    }
}

data class RememberProposal(val id: String, val authorization: RememberAuthorization, val expiresAt: Long) {
    companion object {
        fun parse(value: JSONObject, record: Relationship, status: Boolean = false): RememberProposal {
            exact(value, setOf("id", "generation", "deviceID", "publicKey", "name", "account", "macID",
                "macPublicKey", "expiresAt", "authorizationExpiresAt") + if (status) setOf("awaitingMac") else emptySet())
            require(value.opt("name") == record.name)
            boundIdentity(value, record)
            if (status) require(value.opt("awaitingMac") is Boolean)
            val id = value.getString("id").also { require(b32(it)) }
            val authorization = RememberAuthorization(record, value.getString("generation"), time(value.opt("authorizationExpiresAt")))
            val expiresAt = time(value.opt("expiresAt"))
            require(expiresAt <= authorization.expiresAt)
            return RememberProposal(id, authorization, expiresAt)
        }
    }
}

internal fun b32(value: String): Boolean = try {
    value.length == 43 && Base64.getUrlDecoder().decode(value).let { it.size == 32 && encodeKey(it) == value }
} catch (_: IllegalArgumentException) { false }
internal fun time(value: Any?): Long {
    val result = when (value) { is Int -> value.toLong(); is Long -> value; else -> -1 }
    require(result in 0..9_007_199_254_740_991L) { "Invalid authorization expiry." }
    return result
}
internal fun nullableGeneration(value: Any?): String? {
    require(value == JSONObject.NULL || value is String && b32(value)) { "Invalid notification registration generation." }
    return value as? String
}
internal fun exact(value: JSONObject, fields: Set<String>) {
    require(value.keys().asSequence().toSet() == fields) { "Unsupported authorization schema." }
}
private fun boundIdentity(value: JSONObject, record: Relationship) {
    val account = value.opt("account") as? JSONObject
    require(account != null)
    exact(account, setOf("issuer", "subject"))
    require(Account.parse(account) == record.account && value.opt("macID") == record.macID &&
        value.opt("macPublicKey") == record.macPublicKey && value.opt("deviceID") == record.deviceID &&
        value.opt("publicKey") == record.publicKey) { "Authorization does not match the paired owner and complete keys." }
    decodeKey(record.macPublicKey); decodeKey(record.publicKey)
}

internal object RememberProof {
    const val PREFIX = "Notify remote authorization v1\u0000"
    private val common = setOf("purpose", "origin", "scope", "operation", "version", "account", "macID",
        "macPublicKey", "deviceID", "publicKey", "generation", "authorizationExpiresAt", "challengeExpiresAt", "challenge")

    fun validate(raw: String, authorization: RememberAuthorization, operation: String, args: JSONObject,
        expiresAt: Long): ByteArray {
        val expected = when (operation) {
            "enable" -> setOf("optIn")
            "grant" -> setOf("role")
            "renew" -> setOf("role", "connectionID")
            "push" -> setOf("token", "registrationGeneration")
            "revoke" -> emptySet()
            else -> error("Unsupported remembered operation.")
        }
        exact(args, expected)
        if (operation == "enable") require(args.opt("optIn") == true)
        if (operation in setOf("grant", "renew")) require(args.opt("role") == "phone")
        if (operation == "renew") require((args.opt("connectionID") as? String)?.let(::b32) == true)
        if (operation == "push") {
            nullableGeneration(args.opt("registrationGeneration"))
            require(args.opt("token") == JSONObject.NULL ||
                (args.opt("token") as? String)?.matches(Regex("[A-Za-z0-9_:.-]{16,2048}")) == true)
        }
        val value = parseChallenge(raw)
        exact(value, common + expected)
        boundIdentity(value, authorization.relationship)
        require(value.opt("purpose") == "remember-$operation" && value.opt("operation") == operation &&
            value.opt("scope") == "remembered" && value.opt("version") == 1 &&
            value.opt("origin") == authorization.relationship.account.issuer &&
            value.opt("generation") == authorization.generation &&
            time(value.opt("authorizationExpiresAt")) == authorization.expiresAt &&
            time(value.opt("challengeExpiresAt")) == expiresAt && expiresAt in 1..authorization.expiresAt &&
            (value.opt("challenge") as? String)?.let(::b32) == true &&
            expected.all { value.opt(it) == args.opt(it) }) { "Remembered authorization context changed." }
        require(authorization.expiresAt - expiresAt <= 2_592_000_000L &&
            (operation != "enable" || authorization.expiresAt - expiresAt == 2_592_000_000L - 180_000L)) {
            "Remembered authorization exceeds the fixed 30-day proposal."
        }
        return raw.toByteArray(Charsets.UTF_8)
    }

    fun sign(flow: JSONObject, authorization: RememberAuthorization, operation: String, args: JSONObject,
        started: Long, elapsed: () -> Long, signer: (ByteArray) -> ByteArray): JSONObject {
        val id = flow.opt("id") as? String
        require(id != null && b32(id))
        val raw = flow.opt("challenge") as? String ?: error("Missing authorization challenge.")
        val bytes = validate(raw, authorization, operation, args, time(flow.opt("expiresAt")))
        fun timely() { require(elapsed() - started in 0 until if (operation == "enable") 180_000L else 30_000L) {
            "Authorization proof expired. Retry explicitly."
        } }
        timely()
        val signature = signer(PREFIX.toByteArray(Charsets.UTF_8) + bytes)
        timely()
        return JSONObject().put("id", id).put("signature", encodeKey(signature))
    }

    fun accountPush(raw: String, record: Relationship, token: String?, guard: String?, expiresAt: Long): ByteArray {
        val value = parseChallenge(raw)
        exact(value, common - setOf("generation", "authorizationExpiresAt") + setOf("token", "registrationGeneration"))
        boundIdentity(value, record)
        require(value.opt("purpose") == "push" && value.opt("operation") == "push" && value.opt("scope") == "account" &&
            value.opt("version") == 1 && value.opt("origin") == record.account.issuer &&
            value.opt("token") == (token ?: JSONObject.NULL) &&
            value.opt("registrationGeneration") == (guard ?: JSONObject.NULL) &&
            time(value.opt("challengeExpiresAt")) == expiresAt &&
            (value.opt("challenge") as? String)?.let(::b32) == true) { "Notification authorization context changed." }
        return (PREFIX + raw).toByteArray(Charsets.UTF_8)
    }

    private fun parseChallenge(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..4096)
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000')
        return value
    }
}

internal class RememberApi(
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val elapsed: () -> Long,
) {
    suspend fun enable(record: Relationship, current: () -> Boolean, persist: (RememberProposal) -> Unit,
        signer: (ByteArray) -> ByteArray): RememberProposal {
        check(current())
        val started = elapsed()
        val flow = post("/v1/remote/remember/begin", ids(record).put("optIn", true))
        exact(flow, setOf("id", "challenge", "expiresAt", "generation", "authorizationExpiresAt"))
        val authorization = RememberAuthorization(record, flow.getString("generation"), time(flow.opt("authorizationExpiresAt")))
        val proposal = RememberProposal(flow.getString("id"), authorization, time(flow.opt("expiresAt")))
        check(current())
        val finish = RememberProof.sign(flow, authorization, "enable", JSONObject().put("optIn", true), started, elapsed) {
            check(current()); signer(it)
        }
        check(current())
        // Persist before the only mutation dispatch: a lost response can now be reconciled, never replayed.
        persist(proposal)
        check(current())
        val response = post("/v1/remote/remember/finish", finish)
        check(current())
        return RememberProposal.parse(response, record).also { require(it == proposal) }
    }
    suspend fun revoke(authorization: RememberAuthorization, current: () -> Boolean,
        signer: (ByteArray) -> ByteArray): RememberRevocation {
        val result = mutate(authorization, "revoke", JSONObject(), current, signer)
        exact(result, setOf("generation", "revoked", "cancelledPending"))
        require(result.opt("generation") == authorization.generation && result.opt("revoked") == true &&
            result.opt("cancelledPending") is Boolean)
        return RememberRevocation(result.getString("generation"), result.getBoolean("cancelledPending"))
    }
    suspend fun push(authorization: RememberAuthorization, token: String?, guard: String?, current: () -> Boolean,
        signer: (ByteArray) -> ByteArray): PushMetadata =
        PushMetadata.parse(mutate(authorization, "push", JSONObject().put("token", token ?: JSONObject.NULL)
            .put("registrationGeneration", guard ?: JSONObject.NULL), current, signer)).also {
            require(it.enabled == (token != null) && it.registrationGeneration != null)
        }
    private suspend fun mutate(authorization: RememberAuthorization, operation: String, args: JSONObject,
        current: () -> Boolean, signer: (ByteArray) -> ByteArray): JSONObject {
        check(current())
        val started = elapsed()
        val input = ids(authorization.relationship).put("generation", authorization.generation)
        args.keys().forEach { input.put(it, args.get(it)) }
        val flow = post("/v1/remote/remember/$operation/begin", input)
        exact(flow, setOf("id", "challenge", "expiresAt"))
        check(current())
        val finish = RememberProof.sign(flow, authorization, operation, args, started, elapsed) {
            check(current()); signer(it)
        }
        check(current())
        return post("/v1/remote/remember/$operation/finish", finish)
    }
    companion object {
        fun ids(record: Relationship) = JSONObject().put("macID", record.macID).put("deviceID", record.deviceID)
    }
}
