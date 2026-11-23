package app.notify.android.controller

import app.notify.android.remote.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionLifetimeTest {
    @Test fun oldOrdinaryControlFailureCannotOverwriteAnotherRequestOrAccount() {
        val old = ControlCommand("old", "session", "ticket", "send", "Hello", "")
        val fresh = WorkspaceState().sending(old.copy(id = "new"))
        assertSame(fresh, fresh.uncertain(old))
        val otherAccount = WorkspaceState()
        assertSame(otherAccount, otherAccount.uncertain(old))
    }

    @Test fun retiringChannelImmediatelyMarksAllPendingControlsUncertain() {
        val send = ControlCommand("send", "session", "ticket", "send", "Hello", "")
        val policy = send.copy(id = "policy", action = "dangerous_auto_yes", enabled = true)
        val management = send.copy(id = "management", action = "remove_subagent")
        val session = send.copy(id = "session", action = "resume_session", sourceID = "source")
        val sending = WorkspaceState().sending(send).sending(policy).sending(management).sending(session)
        val retired = ControllerState(workspace = sending).retireConnection().workspace
        assertTrue(retired.deliveries.getValue(send.target).status.contains("unknown"))
        assertTrue(retired.policyDeliveries.getValue(policy.target).status.contains("unknown"))
        assertTrue(retired.managementDeliveries.getValue(management.target).status.contains("unknown"))
        assertTrue(retired.sessionDeliveries.getValue(session.sessionActionKey).status.contains("unknown"))
        assertTrue(retired.deliveries.getValue(send.target).blocked)
        assertTrue(retired.sessionDeliveries.getValue(session.sessionActionKey).blocked)
    }

    @Test fun retiredPanelActionCannotCompleteOrClearANewerRequest() {
        val confirm = PanelConfirm(PanelAction("status:APA-1", "Status"), "Status", "Exact prompt", "P".repeat(43))
        val sending = PanelState(pending = PanelPending("old-request", confirm.action.id, sending = true), confirm = confirm)
        val retired = ControllerState(panel = sending).retireConnection().panel
        assertNull(retired.pending)
        assertNull(retired.confirm)
        assertTrue(retired.message!!.contains("uncertain"))
        val fresh = retired.copy(pending = PanelPending("new-request", confirm.action.id, sending = false), message = null)
        assertSame(fresh, fresh.finish("old-request", message = "Old failure"))
        assertSame(fresh, fresh.finish("old-request", confirm = confirm))
        val completed = fresh.finish("new-request", confirm = confirm)
        assertNull(completed.pending)
        assertEquals(confirm, completed.confirm)
        assertNull(completed.message)
        assertNull(ControllerState(panel = fresh).retireConnection().panel.message)
    }

    private val question = Question("q", "Where?", false, true, emptyList())
    private val request = RemoteRequest("ticket", "session", "Build",
        Prompt("request", "question", "Destination", questions = listOf(question)))

    @Test fun retiringConnectionPreservesUnsentDraftButAdvancesGenerationAndRejectsOldSnapshots() {
        val inbox = RequestInbox().receive(0, true, listOf(request), 100)
            .edit("ticket", "q", AnswerDraft(custom = "  keep exactly "))
        val retired = ControllerState(inbox = inbox).retireConnection().inbox
        assertEquals(1L, retired.generation)
        assertFalse(retired.isLive(101))
        assertEquals("  keep exactly ", retired.cards.single().answers["q"]?.custom)
        assertSame(retired, retired.receive(0, true, listOf(request), 102))
        val fresh = retired.receive(1, true, listOf(request.copy()), 103)
        assertEquals("  keep exactly ", fresh.cards.single().answers["q"]?.custom)
    }
    @Test fun retiredPendingReplyBecomesUnknownAndCannotBeReplayedOrAcknowledgedByOldChannel() {
        val pending = RequestInbox().receive(0, true, listOf(request), 100).prepare("ticket", ReplyDecision.Deny, 101)
        val retired = ControllerState(inbox = pending.inbox).retireConnection().inbox
        assertEquals(Delivery.Unknown, retired.cards.single().delivery)
        assertSame(retired, retired.acknowledge(0, pending.command.id))
        val fresh = retired.receive(retired.generation, true, listOf(request), 103)
        assertEquals(Delivery.Unknown, fresh.cards.single().delivery)
        assertThrows(IllegalStateException::class.java) { fresh.prepare("ticket", ReplyDecision.Deny, 104) }
    }
    @Test fun staleReadIsNotChannelRetirementAndStillAcceptsTheExactInflightAcknowledgment() {
        val pending = RequestInbox().receive(0, true, listOf(request), 100).prepare("ticket", ReplyDecision.Deny, 101)
        val stale = ControllerState(inbox = pending.inbox).staleSnapshots().inbox
        assertEquals(pending.inbox.generation, stale.generation)
        assertFalse(stale.isLive(102))
        assertEquals(Delivery.Pending(pending.command.id), stale.cards.single().delivery)
        assertTrue(stale.acknowledge(stale.generation, pending.command.id).visibleCards.isEmpty())
    }
}
