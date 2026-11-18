package app.notify.android.controller

import app.notify.android.remote.RemoteStatus
import org.junit.Assert.*
import org.junit.Test

class NotificationConsentTest {
    private val account = Account("issuer", "owner")
    private val record = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)

    @Test fun reconnectDoesNotInvalidateExplicitConsentButCallbackIsConsumedOnce() {
        val gate = NotificationConsent()
        gate.begin(account, record)
        val callback = gate.take()!!
        for (phase in listOf("connecting", "pending", "connected")) {
            val state = ControllerState(account = account, relationship = record, status = RemoteStatus(phase, ""))
            assertTrue(gate.valid(callback, state.account, state.relationship))
        }
        assertNull(gate.take())
    }

    @Test fun changedAccountPairingOrExplicitCancellationRejectsLatePermissionGrant() {
        val gate = NotificationConsent()
        gate.begin(account, record)
        val callback = gate.take()!!
        assertFalse(gate.valid(callback, null, record))
        assertFalse(gate.valid(callback, Account("issuer", "other"), record))
        assertFalse(gate.valid(callback, account, record.copy(macID = "another")))
        assertFalse(gate.valid(callback, account, record.copy(deviceID = "replacement")))
        gate.cancel()
        assertFalse(gate.valid(callback, account, record))
        gate.begin(account, record)
        assertFalse(gate.valid(callback, account, record))
        assertTrue(gate.valid(gate.take()!!, account, record))
    }

    @Test fun unsignedMismatchedOrUnconfirmedPairingCannotStartPermissionConsent() {
        val gate = NotificationConsent()
        for ((owner, paired) in listOf(null to record, account to null,
            account to record.copy(enrolled = false), Account("issuer", "other") to record)) {
            try {
                gate.begin(owner, paired)
                fail("An authenticated matching enrolled pairing is required")
            } catch (_: IllegalStateException) { }
            assertNull(gate.take())
        }
        gate.begin(account, record)
        gate.cancel()
        assertNull(gate.take())
    }
}
