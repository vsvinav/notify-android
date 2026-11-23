package app.notify.android.remote

import app.notify.android.controller.Relationship
import app.notify.android.controller.encodeKey
import org.json.JSONObject

class PushRegistration(
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    suspend fun update(record: Relationship, token: String?, registrationGeneration: String? = null,
        current: () -> Boolean = { true }, sign: (ByteArray) -> ByteArray): PushMetadata {
        require(record.enrolled && (token == null || Regex("[A-Za-z0-9_:.-]{16,2048}").matches(token))) {
            "A paired phone and valid notification token are required."
        }
        require(registrationGeneration == null || b32(registrationGeneration))
        check(current())
        val started = elapsed()
        val begin = post("/v1/remote/push/begin", JSONObject().put("macID", record.macID)
            .put("deviceID", record.deviceID).put("token", token ?: JSONObject.NULL)
            .put("registrationGeneration", registrationGeneration ?: JSONObject.NULL))
        if (token == null && begin.opt("enabled") == false && begin.opt("absent") == true &&
            begin.opt("macID") == record.macID && begin.opt("deviceID") == record.deviceID) {
            exact(begin, setOf("enabled", "absent", "macID", "deviceID"))
            check(current())
            return PushMetadata(false, registrationGeneration)
        }
        exact(begin, setOf("id", "challenge", "expiresAt"))
        val id = begin.opt("id") as? String
        val challenge = begin.opt("challenge") as? String
        require(id != null && b32(id) && challenge != null)
        val bytes = RememberProof.accountPush(challenge, record, token, registrationGeneration, time(begin.opt("expiresAt")))
        fun timely() { require(elapsed() - started in 0 until 30_000L) { "Notification authorization expired. Retry explicitly." } }
        timely(); check(current())
        val signature = sign(bytes)
        timely(); check(current())
        val result = post("/v1/remote/push/finish", JSONObject().put("id", id).put("signature", encodeKey(signature)))
        return PushMetadata.parse(result).also {
            check(it.enabled == (token != null) && it.registrationGeneration != null) {
                "Notification registration was not confirmed. Retry explicitly."
            }
        }
    }
}
