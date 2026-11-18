package app.notify.android.remote

import org.junit.Assert.*
import org.junit.Test

class ConnectionTraceTest {
    @Test fun captureIsExplicitBoundedAndMemoryOnlyWithNumericAllowlistedFields() {
        var now = 1000L
        val trace = ConnectionTrace { now }
        trace.record(TraceEvent.RELAY_FAILURE, category = TraceCategory.IO)
        assertTrue(trace.snapshot().entries.isEmpty())
        trace.start()
        val attempt = trace.newAttempt()
        repeat(80) {
            now++
            trace.record(TraceEvent.RELAY_CLOSED, attempt, TracePhase.READY, TraceCategory.PEER_CLOSE,
                httpCode = 9999, wsCode = 1001, retryMs = 999999, leaseMs = -10)
        }
        val snapshot = trace.snapshot()
        assertEquals(64, snapshot.entries.size)
        assertEquals(80L, snapshot.entries.last().elapsedMs)
        assertNull(snapshot.entries.last().httpCode)
        assertEquals(1001, snapshot.entries.last().wsCode)
        assertNull(snapshot.entries.last().retryMs)
        assertEquals(0L, snapshot.entries.last().leaseMs)
        assertFalse(snapshot.text.contains("1000ms"))
        trace.stop()
        val stopped = trace.snapshot().entries
        trace.record(TraceEvent.RELAY_OPENED)
        assertEquals(stopped, trace.snapshot().entries)
    }

    @Test fun deadlineStopsWithoutTrafficAndRestartClearsPreviousCapture() {
        var now = 50_000L
        val trace = ConnectionTrace { now }
        trace.start()
        now += 60_001
        assertFalse(trace.snapshot().capturing)
        assertEquals(TraceEvent.CAPTURE_TIMEOUT, trace.snapshot().entries.last().event)
        assertEquals(60_000L, trace.snapshot().entries.last().elapsedMs)
        trace.start()
        assertEquals(1, trace.snapshot().entries.size)
        assertEquals(1, trace.newAttempt())
        assertEquals(0L, trace.snapshot().entries.first().elapsedMs)
    }

    @Test fun restartedCaptureDoesNotAttributeExistingChannelToANewAttempt() {
        val trace = ConnectionTrace { 100L }
        trace.start()
        val oldAttempt = trace.newAttempt()
        trace.stop()
        trace.start()
        trace.record(TraceEvent.RELAY_OPENED, oldAttempt)
        assertEquals(0, trace.snapshot().entries.last().attempt)
        val newAttempt = trace.newAttempt()
        trace.record(TraceEvent.CONNECTED, newAttempt)
        assertEquals(1, trace.snapshot().entries.last().attempt)
    }
}
