package app.notify.android.controller

import app.notify.android.remote.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionLifetimeTest {
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
