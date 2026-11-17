package app.notify.android.controller

import app.notify.android.notifications.Attention
import app.notify.android.notifications.NotificationState
import org.junit.Assert.*
import org.junit.Test

class AttentionTest {
    private val now = 1_800_000_000_000L
    private val event = mapOf("type" to "attention", "version" to "1",
        "eventID" to "12345678-1234-1234-1234-123456789012", "issuedAt" to now.toString())
    @Test fun enabledRequiresConsentPermissionChannelAndConfirmedRegistration() {
        assertTrue(NotificationState(true, true, true, true).enabled)
        assertFalse(NotificationState(false, true, true, true).enabled)
        assertFalse(NotificationState(true, false, true, true).enabled)
        assertFalse(NotificationState(true, true, false, true).enabled)
        assertFalse(NotificationState(true, true, true, false).enabled)
    }
    @Test fun onlyFreshGenericEventsCanDisplayAndDuplicateEventsAreIgnored() {
        assertEquals(event["eventID"], Attention.accept(event, now, emptySet()))
        assertNull(Attention.accept(event, now, setOf(event.getValue("eventID"))))
        assertNull(Attention.accept(event + ("request" to "secret"), now, emptySet()))
        assertNull(Attention.accept(event + ("type" to "approve"), now, emptySet()))
        assertNull(Attention.accept(event + ("version" to "2"), now, emptySet()))
        assertNull(Attention.accept(event, now + 120000, emptySet()))
        assertNull(Attention.accept(event, now - 5001, emptySet()))
        assertNull(Attention.accept(event + ("issuedAt" to "NaN"), now, emptySet()))
        assertNull(Attention.accept(event + ("eventID" to "not-a-uuid"), now, emptySet()))
    }
}
