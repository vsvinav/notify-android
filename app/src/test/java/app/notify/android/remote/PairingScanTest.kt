package app.notify.android.remote

import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PairingScanTest {
    private val origin = "https://notify-remote.onrender.com/".toHttpUrl()
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65).also { it[0] = 4 })
    private val link = "${origin}#setup=invite&mac=mac&key=$key"

    @Test fun invalidScanCanRecoverButAcceptedOrCancelledScansNeverEmitTwice() {
        val scan = PairingScan(origin)
        assertThrows(IllegalArgumentException::class.java) { scan.accept("https://other.example/#setup=no") }
        assertEquals("mac", scan.accept(link)?.macID)
        assertNull(scan.accept(link))
        assertNull(scan.accept(link.replace("invite", "different")))
        val cancelled = PairingScan(origin)
        cancelled.stop()
        assertNull(cancelled.accept(link))
    }
}
