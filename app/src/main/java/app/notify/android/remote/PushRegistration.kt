package app.notify.android.remote

import app.notify.android.controller.Relationship
import app.notify.android.controller.encodeKey
import org.json.JSONObject
import org.json.JSONTokener

class PushRegistration(
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun update(record: Relationship, token: String?, sign: (ByteArray) -> ByteArray) {
        require(record.enrolled && (token == null || Regex("[A-Za-z0-9_:.-]{16,2048}").matches(token))) {
            "A paired phone and valid notification token are required."
        }
        val begin = post("/v1/remote/push/begin", JSONObject().put("macID", record.macID)
            .put("deviceID", record.deviceID).put("token", token ?: JSONObject.NULL))
        if (token == null && begin.opt("enabled") == false && begin.opt("absent") == true &&
            begin.opt("macID") == record.macID && begin.opt("deviceID") == record.deviceID) return
        val id = begin.opt("id") as? String
        val challenge = begin.opt("challenge") as? String
        val expires = begin.opt("expiresAt") as? Number
        val current = now()
        require(id != null && Regex("[A-Za-z0-9_-]{43}").matches(id) && challenge != null &&
            challenge.toByteArray().size <= 4096 && expires != null &&
            expires.toDouble() == expires.toLong().toDouble() && expires.toLong() > current &&
            expires.toLong() <= current + 30000) { "Invalid notification authorization challenge." }
        val parser = JSONTokener(challenge)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000' &&
            value.keys().asSequence().toSet() == setOf("purpose", "origin", "macID", "deviceID", "token", "challenge") &&
            value.opt("purpose") == "push" && value.opt("origin") == record.account.issuer &&
            value.opt("macID") == record.macID && value.opt("deviceID") == record.deviceID &&
            value.opt("token") == (token ?: JSONObject.NULL) &&
            (value.opt("challenge") as? String)?.matches(Regex("[A-Za-z0-9_-]{43}")) == true) {
            "Notification authorization does not match this phone, Mac and token."
        }
        val signature = sign(("Notify remote authorization v1\u0000" + challenge).toByteArray(Charsets.UTF_8))
        require(now() < expires.toLong()) { "Notification authorization expired. Try again." }
        val result = post("/v1/remote/push/finish", JSONObject().put("id", id).put("signature", encodeKey(signature)))
        check(result.opt("enabled") == (token != null)) { "Notification registration was not confirmed. Retry explicitly." }
    }
}
