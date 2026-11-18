package app.notify.android.remote

import java.net.URLDecoder
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class PairingLink(val setupID: String, val macID: String, val macPublicKey: String) {
    companion object {
        fun parse(value: String, origin: HttpUrl): PairingLink {
            require(value.length <= 2048) { "The pairing link is too long." }
            require(origin.isHttps && value.none { it.isWhitespace() || it.code < 32 } &&
                Regex("https://[^/\\\\?#]+/", RegexOption.IGNORE_CASE).matches(value.substringBefore('#'))) {
                "Use the complete HTTPS pairing link from Notify on your Mac for this service."
            }
            val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Scan the pairing QR code on your Mac.")
            require(url.isHttps && url.host == origin.host && url.port == origin.port &&
                url.encodedPath == "/" && url.query == null && url.username.isEmpty() && url.password.isEmpty()) {
                "This pairing link belongs to another service. Scan the QR code in Notify on your Mac."
            }
            val fragment = url.encodedFragment ?: throw IllegalArgumentException("The link has no Mac pairing information.")
            require(fragment.length <= 1024) { "Invalid Mac pairing information." }
            val entries = fragment.split('&').map { entry ->
                val fields = entry.split('=', limit = 2)
                require(fields.size == 2) { "Invalid Mac pairing information." }
                URLDecoder.decode(fields[0], "UTF-8") to URLDecoder.decode(fields[1], "UTF-8")
            }
            require(entries.size == 3 && entries.map { it.first }.toSet() == setOf("setup", "mac", "key")) {
                "The pairing link has missing or repeated fields."
            }
            val fields = entries.toMap()
            val setup = fields.getValue("setup")
            val mac = fields.getValue("mac")
            val key = fields.getValue("key")
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(setup) && Regex("[A-Za-z0-9_-]{1,128}").matches(mac)) {
                "Invalid Mac pairing identifiers."
            }
            val bytes = Base64.getUrlDecoder().decode(key)
            require(bytes.size == 65 && bytes[0] == 4.toByte() &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == key) {
                "A complete P-256 Mac public key is required. Scan the QR code again."
            }
            return PairingLink(setup, mac, key)
        }
    }
}
