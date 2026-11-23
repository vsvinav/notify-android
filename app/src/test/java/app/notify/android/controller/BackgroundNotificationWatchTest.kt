package app.notify.android.controller

import app.notify.android.connection.BackgroundNotificationWatch
import app.notify.android.notifications.NotificationState
import app.notify.android.remote.RemoteStatus
import org.junit.Assert.*
import org.junit.Test

class BackgroundNotificationWatchTest {
    @Test fun hiddenHealthySessionStopsWhenActualAvailabilityChangesWithoutTransportEmission() {
        val account = Account("issuer", "owner")
        val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
        var state = ControllerState(account = account, relationship = pairing, backgroundRunning = true,
            foreground = false, status = RemoteStatus("connected", ""), notifications = NotificationState(consent = true))
        var reason: String? = null
        val stopped = mutableListOf<Pair<String, String>>()
        val watch = BackgroundNotificationWatch("session", { it == "session" && state.backgroundRunning },
            { reason }, { token, message -> stopped += token to message; state = state.stopBackground() })
        watch.changed()
        assertTrue(state.backgroundRunning)
        reason = "Background channel is blocked"
        watch.changed()
        watch.changed()
        assertEquals(listOf("session" to reason), stopped)
        assertFalse(state.backgroundRunning)
        assertEquals(account, state.account)
        assertEquals(pairing, state.relationship)
        assertTrue(state.notifications.consent)
    }

    @Test fun appAndGroupBlockingAreHandledFromFreshAvailabilityReads() {
        for (blocked in listOf("App notifications disabled", "Channel group blocked")) {
            var actual: String? = null
            var result: String? = null
            val watch = BackgroundNotificationWatch("session", { true }, { actual }, { _, reason -> result = reason })
            watch.changed()
            assertNull(result)
            actual = blocked
            watch.changed()
            assertEquals(blocked, result)
        }
    }

    @Test fun closedOrOldServiceCannotReadOrStopANewerSession() {
        var current = "old"
        var reads = 0
        var stops = 0
        val watch = BackgroundNotificationWatch("old", { it == current }, { reads++; "Blocked" }, { _, _ -> stops++ })
        current = "new"
        watch.changed()
        assertEquals(0, reads)
        current = "old"
        watch.close()
        watch.changed()
        assertEquals(0, reads)
        assertEquals(0, stops)
    }

    @Test fun tokenIsRecheckedAfterReadingAndroidState() {
        var current = "old"
        var stops = 0
        val watch = BackgroundNotificationWatch("old", { it == current },
            { current = "new"; "Blocked" }, { _, _ -> stops++ })
        watch.changed()
        assertEquals(0, stops)
    }
}
