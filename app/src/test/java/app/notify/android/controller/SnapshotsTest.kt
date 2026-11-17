package app.notify.android.controller

import app.notify.android.remote.AnswerDraft
import app.notify.android.remote.RequestInbox
import app.notify.android.remote.ReplyDecision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotsTest {
    private val wire = """{"connected":true,"requests":[{"ticket":"t","sessionID":"s","sessionName":"Build","fields":[{"label":"Command","value":"echo 'literal'","presentation":"code"}],"request":{"id":"r","kind":"question","title":"Where?","questions":[{"id":"q","text":"Destination","multiple":false,"allowFreeform":true,"options":[{"label":"First"}]}]}}]}"""

    @Test fun actualRequestWireKeepsExactEvidenceAndRepliesAcrossMemberReordering() {
        val snapshot = Snapshots.requests(JSONObject(wire))
        val inbox = RequestInbox().receive(0, snapshot.connected, snapshot.requests, 100)
            .edit("t", "q", AnswerDraft(custom = "  exact\nreply "))
        val reordered = JSONObject(wire).apply { put("connected", remove("connected")) }
        val parsed = Snapshots.requests(reordered)
        val refreshed = inbox.receive(0, parsed.connected, parsed.requests, 200)
        assertSame(inbox.cards.single(), refreshed.cards.single())
        assertEquals("echo 'literal'", refreshed.cards.single().request.fields.single().value)
        val payload = Snapshots.reply(refreshed.prepare("t", ReplyDecision.Answer, 201).command)
        assertEquals("  exact\nreply ", payload.getJSONObject("answers").getJSONArray("q").getString(0))
        assertFalse(payload.has("id"))
    }
    @Test fun malformedAndAmbiguousAuthorityFieldsFailClosed() {
        val value = JSONObject(wire)
        value.put("connected", "true")
        assertThrows(IllegalArgumentException::class.java) { Snapshots.requests(value) }
        value.put("connected", true)
        val requests = value.getJSONArray("requests")
        requests.put(requests.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { Snapshots.requests(value) }
    }
    @Test fun staleReadDisablesNewRepliesWithoutLosingAnInflightAcknowledgment() {
        val snapshot = Snapshots.requests(JSONObject(wire))
        val prepared = RequestInbox().receive(0, true, snapshot.requests, 100).prepare("t", ReplyDecision.Deny, 101)
        val stale = prepared.inbox.copy(connected = false)
        assertFalse(stale.isLive(102))
        assertTrue(stale.acknowledge(0, prepared.command.id).visibleCards.isEmpty())
    }
    @Test fun workspaceDetailForAnotherTicketNeverBecomesSelectedConversation() {
        val snapshot = Snapshots.workspace(JSONObject("""{"connected":true,"sessions":[{"id":"s","name":"Build","project":"Notify","agent":"copilot","state":"working","connected":true,"ticket":"current","capabilities":{"steering":true,"subagentChat":true,"subagentStop":true}}],"detail":{"sessionID":"s","ticket":"old","team":null,"activity":[],"controls":[],"truncated":false}}"""))
        assertNull(WorkspaceState(snapshot, "s", receivedAt = 100).detail)
    }
}
