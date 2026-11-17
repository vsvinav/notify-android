package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    private val session = Session("s", "Build", "project", "copilot", "working", true, "t", Capabilities(true, true, true))
    private val detail = SessionDetail("s", "t", listOf(Agent("child", "Helper", "running")), emptyList(), emptyList())
    private fun workspace() = WorkspaceState(snapshot = WorkspaceSnapshot(true, listOf(session), detail),
        sessionID = "s", agentID = "child", receivedAt = 100)

    @Test fun exactChildAndTicketAreCarriedInControlAndTextIsNotTrimmed() {
        val command = workspace().edit("  keep exact\n").prepare("queue", 101)
        assertEquals("child", command.agentID)
        assertEquals("s", command.sessionID)
        assertEquals("t", command.ticket)
        assertEquals("  keep exact\n", command.text)
        assertEquals("child", command.json().getString("agentID"))
    }
    @Test fun wrongDetailStaleEndedAndUnsupportedTargetsCannotSend() {
        val current = workspace().edit("hi")
        for (state in listOf(
            current.copy(snapshot = current.snapshot!!.copy(detail = detail.copy(sessionID = "another"))),
            current.copy(snapshot = current.snapshot!!.copy(detail = detail.copy(ticket = "old"))),
            current.copy(snapshot = current.snapshot!!.copy(detail = detail.copy(agents = listOf(Agent("child", "Helper", "completed"))))),
            current.copy(agentID = "missing"),
            current.copy(snapshot = current.snapshot!!.copy(sessions = listOf(session.copy(agent = "claude"))))
        )) assertThrows(IllegalStateException::class.java) { state.prepare("queue", 101) }
        assertThrows(IllegalStateException::class.java) { current.prepare("queue", 10_100) }
        assertThrows(IllegalStateException::class.java) { current.prepare("interrupt", 101) }
    }
    @Test fun uncertainDeliveryBlocksReplayAndTicketChangesClearOnlyItsDraft() {
        val state = workspace().edit("hello")
        val command = state.prepare("queue", 101)
        val pending = state.sending(command).uncertain(command)
        assertThrows(IllegalStateException::class.java) { pending.prepare("queue", 102) }
        val refreshed = pending.receive(state.snapshot!!, 200)
        assertThrows(IllegalStateException::class.java) { refreshed.prepare("queue", 201) }
        val changed = state.receive(state.snapshot.copy(sessions = listOf(session.copy(ticket = "new"))), 200)
        assertEquals("", changed.draft)
    }
    @Test fun equivalentSnapshotsKeepDraftAndRpcAcknowledgmentTracksDistinctNativeReceipt() {
        val state = workspace().edit("child draft").select("s", "").edit("main draft").select("s", "child")
        val refreshed = state.receive(state.snapshot!!.copy(), 200)
        assertEquals("child draft", refreshed.draft)
        val command = refreshed.prepare("queue", 201)
        val pending = refreshed.sending(command)
        assertThrows(IllegalArgumentException::class.java) { pending.acknowledge(command.copy(id = "wrong"), "native-receipt", "received") }
        val acknowledged = pending.acknowledge(command, "native-receipt", "received")
        assertEquals("", acknowledged.draft)
        assertEquals("native-receipt", acknowledged.delivery?.receiptID)
        assertEquals("main draft", refreshed.select("s", "").draft)
    }
    @Test fun disconnectedSnapshotKeepsConversationAndDraftButDisablesControls() {
        val state = workspace().edit("keep me")
        val offline = state.receive(WorkspaceSnapshot(false, emptyList(), null), 200)
        assertEquals("keep me", offline.draft)
        assertEquals(detail, offline.detail)
        assertThrows(IllegalStateException::class.java) { offline.prepare("queue", 201) }
    }
}
