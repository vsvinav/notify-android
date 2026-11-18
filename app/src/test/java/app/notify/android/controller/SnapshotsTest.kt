package app.notify.android.controller

import app.notify.android.remote.AnswerDraft
import app.notify.android.remote.RequestInbox
import app.notify.android.remote.ReplyDecision
import app.notify.android.remote.RemoteStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotsTest {
    @Test fun liveManagementCatalogNeedsBothHelperAndNativeSupport() {
        val wire = JSONObject("""{"connected":true,"sessions":[{"id":"s","name":"Build","project":"","agent":"copilot","state":"working","connected":true,"ticket":"t","managementTicket":"m","subagentTypes":["reviewer"],"subagentModels":[],"capabilities":{"steering":true,"subagentChat":true,"subagentStop":true,"subagentCreate":true,"subagentRemove":true,"subagentCleanup":true}}]}""")
        assertFalse(Snapshots.workspace(wire).sessions.single().capabilities.subagentCreate)
        wire.put("subagentManagementControl", true)
        val parsed = Snapshots.workspace(wire).sessions.single()
        assertTrue(parsed.capabilities.subagentCreate)
        assertTrue(parsed.capabilities.subagentRemove)
        assertTrue(parsed.capabilities.subagentCleanup)
        assertEquals(listOf("reviewer"), parsed.subagentTypes)
        assertEquals(emptyList<String>(), parsed.subagentModels)
        val row = wire.getJSONArray("sessions").getJSONObject(0)
        row.put("subagentTypes", org.json.JSONArray(listOf("reviewer", "reviewer")))
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(wire) }
        row.put("subagentTypes", org.json.JSONArray(listOf("reviewer")))
        row.put("subagentModels", org.json.JSONArray(listOf("😀".repeat(101))))
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(wire) }
    }
    @Test fun policySnapshotIsOptionalButNeverInventsOffOrAcceptsMalformedSupport() {
        val wire = JSONObject("""{"connected":true,"sessions":[{"id":"s","name":"Build","project":"","agent":"copilot","state":"working","connected":true,"ticket":"t","capabilities":{"steering":false,"subagentChat":false,"subagentStop":false}}]}""")
        assertNull(Snapshots.workspace(wire).sessions.single().dangerousAutoYes)
        val row = wire.getJSONArray("sessions").getJSONObject(0)
        row.getJSONObject("capabilities").put("dangerousAutoYes", true)
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(wire) }
        row.put("dangerousAutoYes", JSONObject("""{"ticket":"p","revision":1,"enabled":true,"normalEnabled":false}"""))
        assertTrue(Snapshots.workspace(wire).sessions.single().dangerousAutoYes!!.enabled)
        assertFalse("Old helpers must not advertise a control they cannot dispatch",
            Snapshots.workspace(wire).sessions.single().capabilities.dangerousAutoYes)
        wire.put("dangerousAutoYesControl", true)
        assertFalse(Snapshots.workspace(wire).sessions.single().capabilities.dangerousAutoYes)
        wire.put("dangerousAutoYesOffEpoch", true)
        assertFalse("Legacy native policy cannot enable without bounded Off authority",
            Snapshots.workspace(wire).sessions.single().capabilities.dangerousAutoYes)
        row.getJSONObject("dangerousAutoYes").put("offTicket", "off-epoch-1")
        assertFalse(Snapshots.workspace(wire).sessions.single().capabilities.dangerousAutoYes)
        row.getJSONObject("dangerousAutoYes").put("offTicket", "00000000-0000-8000-8000-000000000001")
        assertTrue(Snapshots.workspace(wire).sessions.single().capabilities.dangerousAutoYes)
        row.getJSONObject("dangerousAutoYes").put("enabled", "false")
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(wire) }
    }

    @Test fun workspaceRetainsReportedHierarchyAndCountsButRejectsMalformedParentClaims() {
        val value = JSONObject("""{"connected":true,"sessions":[{"id":"s","name":"Build","project":"Notify","agent":"copilot","state":"working","connected":true,"ticket":"t","runningCount":4,"idleCount":2,"capabilities":{"steering":true,"subagentChat":true,"subagentStop":true}}],"detail":{"sessionID":"s","ticket":"t","team":{"agents":[{"id":"a","name":"Review","status":"running","task":"Check changes","model":"reported-model","parentKnown":true}],"runningCount":4,"idleCount":2,"truncated":true},"activity":[],"controls":[],"truncated":true}}""")
        val snapshot = Snapshots.workspace(value)
        assertEquals(4, snapshot.sessions.single().runningCount)
        assertEquals("Check changes", snapshot.detail!!.agents.single().task)
        assertEquals("reported-model", snapshot.detail!!.agents.single().model)
        assertTrue(snapshot.detail!!.agents.single().parentKnown)
        val agent = value.getJSONObject("detail").getJSONObject("team").getJSONArray("agents").getJSONObject(0)
        agent.put("parentAgentID", "a")
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(value) }
        agent.put("parentAgentID", "other").put("parentKnown", false)
        assertThrows(IllegalArgumentException::class.java) { Snapshots.workspace(value) }
    }

    private val wire = """{"connected":true,"requests":[{"ticket":"t","sessionID":"s","sessionName":"Build","fields":[{"label":"Command","value":"echo 'literal'","presentation":"code"}],"request":{"id":"r","kind":"question","title":"Where?","questions":[{"id":"q","text":"Destination","multiple":false,"allowFreeform":true,"options":[{"label":"First"}]}]}}]}"""

    @Test fun productionSnapshotUpdateKeepsFreshInboxLiveBetweenClockTicks() {
        val state = ControllerState(status = RemoteStatus("connected", "Encrypted channel connected."),
            foreground = true, now = 1000)
        val updated = state.receiveRequests(Snapshots.requests(JSONObject(wire)), 1100)
        assertTrue("A new live snapshot must not await the next clock tick", updated.live)
        assertEquals(1100L, updated.now)
        assertTrue(updated.inbox.isLive(updated.now))
        assertEquals(state.status, updated.status)
    }

    @Test fun productionSnapshotUpdateRetainsFutureAndExpiryReplyGuards() {
        val updated = ControllerState(status = RemoteStatus("connected", ""), foreground = true, now = 1000)
            .receiveRequests(Snapshots.requests(JSONObject(wire)), 1100)
        assertTrue(updated.copy(now = 11099).live)
        for (now in listOf(1099L, 11100L)) {
            val unavailable = updated.copy(now = now)
            assertFalse(unavailable.live)
            assertThrows(IllegalStateException::class.java) {
                unavailable.inbox.prepare("t", ReplyDecision.Deny, unavailable.now)
            }
        }
    }

    @Test fun productionSnapshotUpdateDoesNotOverrideDisconnectedOrBackgroundState() {
        val snapshot = Snapshots.requests(JSONObject(wire))
        val connected = ControllerState(status = RemoteStatus("connected", ""), foreground = true, now = 1000)
            .receiveRequests(snapshot, 1100)
        val unavailable = connected.receiveRequests(snapshot.copy(connected = false), 1200)
        assertFalse(unavailable.live)
        assertFalse(unavailable.inbox.connected)
        assertEquals(connected.status, unavailable.status)
        assertThrows(IllegalStateException::class.java) {
            unavailable.inbox.prepare("t", ReplyDecision.Deny, unavailable.now)
        }
        assertFalse(connected.copy(foreground = false).receiveRequests(snapshot, 1300).live)
        assertFalse(connected.copy(status = RemoteStatus("connecting", "")).receiveRequests(snapshot, 1300).live)
    }

    @Test fun productionWorkspaceUpdateKeepsFreshControlsAndReviewBetweenClockTicks() {
        val snapshot = WorkspaceSnapshot(true,
            listOf(Session("s", "Build", "Notify", "copilot", "working", true, "t", Capabilities(true, true, true))),
            SessionDetail("s", "t", emptyList(), emptyList(), emptyList()))
        val review = ControlCommand("control", "s", "t", "stop", "", "")
        val state = ControllerState(now = 1100, workspace = WorkspaceState(sessionID = "s"), controlReview = review)
        val updated = state.receiveWorkspace(snapshot, 1200)
        assertNull("A fresh workspace must not await the next clock tick", updated.workspace.unavailable("stop", updated.now))
        assertEquals(1200L, updated.now)
        assertEquals(review, updated.controlReview)
        for (now in listOf(1199L, 11200L)) {
            assertNotNull(updated.workspace.unavailable("stop", now))
            assertThrows(IllegalStateException::class.java) { updated.workspace.prepare("stop", now) }
        }
        val offline = updated.receiveWorkspace(snapshot.copy(connected = false), 1300)
        assertNotNull(offline.workspace.unavailable("stop", offline.now))
        assertNull(offline.controlReview)
        val replaced = snapshot.copy(sessions = snapshot.sessions.map { it.copy(ticket = "replacement") })
        assertNull(updated.receiveWorkspace(replaced, 1300).controlReview)
        val unsupported = snapshot.copy(sessions = snapshot.sessions.map {
            it.copy(capabilities = Capabilities(false, false, false))
        })
        assertNull(updated.receiveWorkspace(unsupported, 1300).controlReview)
    }

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
