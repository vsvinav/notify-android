package app.notify.android.remote

import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PairingLinkTest {
    private val origin = "https://notify-remote.onrender.com/".toHttpUrl()
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65).also { it[0] = 4 })
    private val link = "${origin}#setup=invitation&mac=mac-1&key=$key"

    @Test fun retainsTheCompleteMacKey() {
        val parsed = PairingLink.parse(link, origin)
        assertEquals("invitation", parsed.setupID)
        assertEquals("mac-1", parsed.macID)
        assertEquals(key, parsed.macPublicKey)
    }

    @Test fun rejectsOtherOriginsDuplicateParametersAndIncompleteKeys() {
        for (invalid in listOf(
            link.replace("onrender.com", "onrender.com.attacker.test"),
            link.replace("https:", "http:"),
            "$link&mac=other",
            link.replace(key, "BA"),
            link.replace("/#", "/other#"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(invalid, origin) }
        }
    }
}
