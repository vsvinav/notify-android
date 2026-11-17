package app.notify.android.remote

import java.net.ProtocolException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LeaseTimingTest {
    private val start = LeaseStart(1_800_000_000_000, 1000)
    private fun grant(validity: Any = 30000) = JSONObject().put("expiresAt", start.wall + 30019).put("validForMs", validity)

    @Test fun durationChargesFullRequestTimeAgainstBothLocalClocks() {
        val result = leaseTiming(grant(), start, LeaseStart(start.wall + 900, 2100))
        assertEquals(start.wall + 30000, result.expiresAt)
        assertEquals(31000, result.deadline)
        assertEquals(28900, result.remaining)
        for (now in listOf(LeaseStart(start.wall + 30000, 31000),
            LeaseStart(start.wall + 10, 31000), LeaseStart(start.wall + 30000, 1010)))
            assertThrows(ProtocolException::class.java) { leaseTiming(grant(), start, now) }
    }
    @Test fun malformedDurationCannotFallBackAndRollbackFailsClosed() {
        for (bad in listOf(JSONObject.NULL, 0, -1, 30001, 1.5, "30000", true))
            assertThrows(ProtocolException::class.java) { leaseTiming(grant(bad), start, start) }
        for (bad in listOf(JSONObject.NULL, 0, -1, "1800000030000", 1.5, Long.MAX_VALUE))
            assertThrows(ProtocolException::class.java) { leaseTiming(grant().put("expiresAt", bad), start, start) }
        for (now in listOf(LeaseStart(start.wall - 1, 1001), LeaseStart(start.wall + 1, 999)))
            assertThrows(ProtocolException::class.java) { leaseTiming(grant(), start, now) }
    }
    @Test fun shortPendingAndLegacyLeasesAreNotExtendedByLatency() {
        val now = LeaseStart(start.wall + 900, 1900)
        assertEquals(100, leaseTiming(grant(1000), start, now).remaining)
        assertThrows(ProtocolException::class.java) { leaseTiming(grant(900), start, now) }
        assertEquals(29100, leaseTiming(JSONObject().put("expiresAt", start.wall + 30900), start, now).remaining)
        assertThrows(ProtocolException::class.java) {
            leaseTiming(JSONObject().put("expiresAt", start.wall + 30901), start, now)
        }
    }
}
