package app.notify.android.controller

import app.notify.android.remote.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionTest {
    private val account = Account("issuer", "owner")
    private val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
    private val connected = ControllerState(account = account, relationship = pairing, foreground = true, dashboardEnabled = true,
        status = RemoteStatus("connected", ""))

    @Test fun onlyAnActuallyRunningServiceKeepsTheTransportWhenHidden() {
        assertFalse(connected.keepTransportWhenHidden)
        assertFalse(connected.copy(backgroundStarting = true).keepTransportWhenHidden)
        assertTrue(connected.copy(backgroundRunning = true).keepTransportWhenHidden)
        assertFalse(connected.copy(backgroundRunning = true, account = null).keepTransportWhenHidden)
        assertFalse(connected.copy(backgroundRunning = true, relationship = pairing.copy(enrolled = false)).keepTransportWhenHidden)
    }

    @Test fun hidingStalesReadsAndPreviewWithoutRetiringAnInflightMutation() {
        val request = RemoteRequest("ticket", "session", "Build", Prompt("request", "permission", "Allow?"))
        val prepared = RequestInbox().receive(0, true, listOf(request), 100).prepare("ticket", ReplyDecision.Deny, 101)
        val confirm = PanelConfirm(PanelAction("action", "Action"), "Review", "Exact prompt", "P".repeat(43))
        val state = connected.copy(inbox = prepared.inbox, backgroundRunning = true,
            panel = PanelState(fresh = true, pending = PanelPending("send-id", "action", true), confirm = confirm))
        val hidden = state.visibility(false, 102)
        assertFalse(hidden.pollVisibleState)
        assertFalse(hidden.live)
        assertFalse(hidden.panel.fresh)
        assertNull(hidden.panel.confirm)
        assertEquals("send-id", hidden.panel.pending?.id)
        assertEquals(prepared.inbox.generation, hidden.inbox.generation)
        assertEquals(Delivery.Pending(prepared.command.id), hidden.inbox.cards.single().delivery)
        assertTrue(hidden.inbox.acknowledge(prepared.inbox.generation, prepared.command.id).visibleCards.isEmpty())
        val returned = hidden.visibility(true, 103)
        assertTrue(returned.pollVisibleState)
        assertFalse(returned.live)
        assertFalse(returned.panelActionsEnabled)
        assertTrue(returned.visibilityGeneration > state.visibilityGeneration)
        assertNull(state.copy(panel = state.panel.copy(pending = PanelPending("preview", "action", false)))
            .visibility(false, 102).panel.pending)
    }

    @Test fun permissionAndServiceStartAreOneUseAndFencedToCurrentForegroundPairing() {
        val gate = BackgroundConnectionConsent()
        val request = gate.begin(connected)
        assertNull(gate.permissionResult("old-callback", true, connected))
        val token = gate.permissionResult(request, true, connected)!!
        assertNull(gate.permissionResult(request, true, connected))
        assertFalse(gate.consumeStart(token, connected.copy(account = Account("issuer", "another"))))
        val second = gate.begin(connected)
        val current = gate.permissionResult(second, true, connected)!!
        assertTrue(gate.consumeStart(current, connected))
        assertFalse(gate.consumeStart(current, connected))
    }

    @Test fun deniedHiddenCancelledOrChangedPermissionResultsCannotEnable() {
        for (next in listOf(connected.copy(foreground = false), connected.copy(account = null),
            connected.copy(relationship = pairing.copy(deviceID = "new")),
            connected.copy(status = RemoteStatus("error", "")))) {
            val gate = BackgroundConnectionConsent()
            val request = gate.begin(connected)
            assertNull(gate.permissionResult(request, true, next))
        }
        val gate = BackgroundConnectionConsent()
        val denied = gate.begin(connected)
        assertNull(gate.permissionResult(denied, false, connected))
        val cancelled = gate.begin(connected)
        gate.cancel()
        val fresh = gate.begin(connected)
        assertNull(gate.permissionResult(cancelled, true, connected))
        assertNotNull(gate.permissionResult(fresh, true, connected))
        gate.cancel()
        assertFalse(gate.consumeStart(fresh, connected))
        assertThrows(IllegalStateException::class.java) { gate.begin(connected.copy(foreground = false)) }
    }

    @Test fun stoppingRetiresPendingDeliveryButKeepsAccountAndPairing() {
        val sending = connected.copy(backgroundRunning = true,
            panel = PanelState(pending = PanelPending("old", "action", true)))
        val stopped = sending.stopBackground()
        assertFalse(stopped.backgroundRunning)
        assertFalse(stopped.backgroundStarting)
        assertEquals(account, stopped.account)
        assertEquals(pairing, stopped.relationship)
        assertNull(stopped.panel.pending)
        assertTrue(stopped.panel.message!!.contains("uncertain"))
        assertNull(stopped.endAccountSession().account)
        assertFalse(stopped.endAccountSession().backgroundRunning)
    }
}
