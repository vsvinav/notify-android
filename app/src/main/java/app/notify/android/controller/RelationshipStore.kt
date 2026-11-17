package app.notify.android.controller

import app.notify.android.remote.PairingLink
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

const val SERVICE_ORIGIN = "https://notify-remote.onrender.com"
data class Account(val issuer: String, val subject: String) {
    fun json() = JSONObject().put("issuer", issuer).put("subject", subject)
    companion object {
        fun parse(value: JSONObject) = Account(value.text("issuer", 2048, true), value.identifier("subject"))
    }
}
data class Relationship(
    val account: Account, val macID: String, val macPublicKey: String,
    val deviceID: String, val publicKey: String, val name: String, val enrolled: Boolean = false,
)
interface RelationshipStorage {
    fun read(): String?
    fun write(value: String?)
}
interface IdentityKeys<T> {
    fun create(id: String): ByteArray
    fun load(id: String, expected: ByteArray): T
    fun delete(id: String)
}
class RelationshipStore<T>(private val storage: RelationshipStorage, private val keys: IdentityKeys<T>) {
    fun read(): Relationship? {
        val raw = storage.read() ?: return null
        require(raw.length <= 8192) { "Saved pairing is invalid. Explicitly forget it before pairing again." }
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000') { "Saved pairing is invalid." }
        require(value.text("origin") == SERVICE_ORIGIN) { "Saved pairing belongs to a different service." }
        return Relationship(Account.parse(value.getJSONObject("account")), value.identifier("macID"),
            value.text("macPublicKey").also(::decodeKey), value.text("deviceID").also(::canonicalUUID),
            value.text("publicKey").also(::decodeKey), value.text("name", 80, true), value.bool("enrolled"))
    }
    fun create(account: Account, setup: PairingLink, name: String): Relationship {
        check(read() == null) { "A pairing is already saved. Connect or explicitly forget it first." }
        require(name.isNotBlank() && name.length <= 80) { "Enter a phone name, up to 80 characters." }
        val id = UUID.randomUUID().toString()
        val public = keys.create(id)
        return Relationship(account, setup.macID, setup.macPublicKey, id, encodeKey(public), name).also(::save)
    }
    fun identity(record: Relationship): T {
        check(read() == record) { "The saved relationship changed." }
        return keys.load(record.deviceID, decodeKey(record.publicKey))
    }
    fun enrolled(record: Relationship): Relationship {
        check(read() == record) { "The saved relationship changed." }
        return record.copy(enrolled = true).also(::save)
    }
    fun forget() {
        val record = read()
        if (record != null) keys.delete(record.deviceID)
        storage.write(null)
    }
    private fun save(record: Relationship) {
        storage.write(JSONObject().put("origin", SERVICE_ORIGIN).put("account", record.account.json())
            .put("macID", record.macID).put("macPublicKey", record.macPublicKey).put("deviceID", record.deviceID)
            .put("publicKey", record.publicKey).put("name", record.name).put("enrolled", record.enrolled).toString())
    }
}
fun encodeKey(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
fun decodeKey(value: String): ByteArray {
    require(value.length == 87) { "A complete P-256 public key is required." }
    val bytes = Base64.getUrlDecoder().decode(value)
    require(bytes.size == 65 && bytes[0] == 4.toByte() && encodeKey(bytes) == value) { "Invalid pinned public key." }
    return bytes
}
internal fun canonicalUUID(value: String) {
    require(value.length == 36 && UUID.fromString(value).toString() == value) { "Invalid device identity alias." }
}
internal fun JSONObject.text(key: String, max: Int = 65_535, nonempty: Boolean = false): String {
    val value = opt(key)
    require(value is String && value.length <= max && (!nonempty || value.isNotBlank())) { "Invalid $key in the Mac or service response." }
    return value
}
internal fun JSONObject.optionalText(key: String, max: Int = 65_535): String =
    if (!has(key) || isNull(key)) "" else text(key, max)
internal fun JSONObject.identifier(key: String): String = text(key, 128, true).also {
    require(Regex("[A-Za-z0-9_-]+").matches(it)) { "Invalid $key." }
}
internal fun JSONObject.bool(key: String, default: Boolean? = null): Boolean {
    if (!has(key) && default != null) return default
    return opt(key) as? Boolean ?: throw IllegalArgumentException("Invalid $key in response.")
}
internal fun JSONObject.objects(key: String, max: Int, optional: Boolean = false): List<JSONObject> {
    if (optional && (!has(key) || isNull(key))) return emptyList()
    val array = getJSONArray(key)
    require(array.length() <= max) { "Too many $key in the Mac response." }
    return List(array.length()) { array.getJSONObject(it) }
}
