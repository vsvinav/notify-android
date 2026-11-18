package app.notify.android.controller

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionParityTest {
    private val metadata = SessionActions("m", "canonical", "saved", 0x010203, true,
        listOf(SessionColor("Sky", 0xA6C8FF)), true, true, true, "available", "", moveDown = "b", supported = true,
        resumeAuthority = ResumeAuthority("native-source-epoch", 0))
    private val saved = Session("a", "Saved", "project", "claude", "idle", false, "t",
        Capabilities(false, false, false), sessionActions = metadata)
    private val peer = saved.copy(id = "b", name = "Hidden", ticket = "tb",
        sessionActions = metadata.copy(ticket = "mb", sourceID = "other", moveDown = null, moveUp = "a"))
    private fun state() = WorkspaceState(WorkspaceSnapshot(true, listOf(saved, peer), null), "a", receivedAt = 100)
    private fun savedAfterOwner(epoch: String = "native-source-epoch") = Snapshots.workspace(JSONObject("""
        {"connected":true,"sessionActionsControl":true,"sessions":[{
          "id":"a","name":"Saved","project":"project","agent":"claude","state":"idle","connected":false,"ticket":"t",
          "capabilities":{"steering":false,"subagentChat":false,"subagentStop":false},
          "sessionActions":{"ticket":"after-owner","sourceID":"canonical","group":"saved","rgb":66051,
            "overridden":true,"colors":[],"editable":true,"removable":true,"resumable":true,
            "resumeStatus":"available","resumeDetail":"","resumeAuthority":{"epoch":"$epoch","retired":1}}
        }]}
    """)).sessions.single()
    private fun ack(c: ControlCommand, status: String = sessionActionStatuses[c.action]!!, ok: Boolean = true) =
        JSONObject().put("ok", ok).put("status", status).put("commandID", "native-id").put("sessionID", c.sessionID)
            .put("action", c.action).put("sessionTicket", c.sessionTicket).put("detail", "Authoritative result")

    @Test fun retiredResumeReviewSurvivesOtherPhonesAndMissedIntermediateSnapshots() {
        fun snapshot(retired: Int, ticket: String, epoch: String = "native-source-epoch"): WorkspaceSnapshot {
            val row = JSONObject("""
                {"connected":true,"sessionActionsControl":true,"sessions":[{
                  "id":"a","name":"Saved","project":"","agent":"claude","state":"idle","connected":false,"ticket":"t",
                  "capabilities":{"steering":false,"subagentChat":false,"subagentStop":false},
                  "sessionActions":{"ticket":"$ticket","sourceID":"canonical","group":"saved","rgb":1,"overridden":false,
                    "colors":[],"editable":true,"removable":true,"resumable":true,"resumeStatus":"available","resumeDetail":"",
                    "resumeAuthority":{"epoch":"$epoch","retired":$retired}}
                }]}
            """)
            return Snapshots.workspace(row)
        }
        val initial = WorkspaceState(sessionID = "a").receive(snapshot(0, "review-A"), 100)
        val a = initial.prepareSessionAction("resume_session", 101)
        val phoneA = initial.sending(a).uncertain(a)
        val afterA = snapshot(1, "review-B")
        val phoneB = WorkspaceState(sessionID = "a").receive(afterA, 102)
        val b = phoneB.prepareSessionAction("resume_session", 103)
        val afterB = snapshot(2, "review-C")
        for (prior in listOf(phoneA, phoneA.receive(afterA, 104))) {
            val recovered = prior.receive(afterB, 105)
            val fresh = recovered.prepareSessionAction("resume_session", 106)
            assertNotEquals(a.id, fresh.id)
            assertEquals("review-C", fresh.sessionTicket)
            assertEquals(ResumeAuthority("native-source-epoch", 2), fresh.resumeAuthority)
            assertFalse(fresh.json().has("resumeAuthority"))
            assertEquals(phoneA.sessionResults, recovered.sessionResults)
            assertThrows(IllegalStateException::class.java) { recovered.validate(a, 106) }
            val unobserved = recovered.sending(fresh).uncertain(fresh)
            for (counter in listOf(1, 2)) {
                assertThrows(IllegalStateException::class.java) {
                    unobserved.receive(snapshot(counter, "color-or-work-change"), 107)
                        .prepareSessionAction("resume_session", 108)
                }
            }
            val ineligible = afterB.copy(sessions = afterB.sessions.map { it.copy(sessionActions =
                it.sessionActions!!.copy(resumable = false, resumeStatus = "unknown", resumeAuthority = ResumeAuthority("native-source-epoch", 3))) })
            assertThrows(IllegalStateException::class.java) {
                unobserved.receive(ineligible, 107).prepareSessionAction("resume_session", 108)
            }
            assertThrows(IllegalStateException::class.java) {
                unobserved.receive(snapshot(3, "new-native-or-work", "replacement-epoch"), 107)
                    .prepareSessionAction("resume_session", 108)
            }
        }
        val recoveredB = phoneB.sending(b).uncertain(b).receive(afterB, 105)
        assertNotNull(recoveredB.prepareSessionAction("resume_session", 106))
        val oldNative = initial.copy(snapshot = initial.snapshot!!.copy(sessions = initial.snapshot.sessions.map {
            it.copy(sessionActions = it.sessionActions!!.copy(resumeAuthority = null))
        }))
        val oldAttempt = oldNative.prepareSessionAction("resume_session", 101)
        assertThrows(IllegalStateException::class.java) {
            oldNative.sending(oldAttempt).uncertain(oldAttempt).receive(afterB, 105).prepareSessionAction("resume_session", 106)
        }
    }

    @Test fun savedColorsNeedMetadataNotConnectedRuntimeAndNeverOptimisticallyChangeRows() {
        val s = state().edit("saved draft").select("b").edit("other draft").select("a", "helper")
        val command = s.prepareSessionAction("set_session_color", 101, 0x000000)
        assertEquals("", command.agentID)
        assertEquals(0, command.json().getInt("rgb"))
        assertFalse(command.json().has("sourceID"))
        assertFalse(command.json().has("agentID"))
        s.validate(command, 101)
        val done = s.sending(command).select("b").acknowledgeSessionAction(command, ack(command))
        assertEquals(s.snapshot, done.snapshot)
        assertEquals("other draft", done.draft)
        assertEquals(s.drafts, done.drafts)
        assertEquals("b", done.sessionID)
        val reset = s.prepareSessionAction("reset_session_color", 101)
        assertFalse(reset.json().has("rgb"))
        assertThrows(IllegalArgumentException::class.java) { s.prepareSessionAction("set_session_color", 101, 0x1000000) }
        assertThrows(IllegalStateException::class.java) { s.prepare("stop", 101) }
        assertThrows(IllegalArgumentException::class.java) { s.sending(command).acknowledgeSessionAction(command, ack(command, "received")) }
        val failed = s.sending(command).acknowledgeSessionAction(command, ack(command, "failed", false))
        assertEquals(0x010203, failed.session!!.sessionActions!!.rgb)
        assertFalse(failed.sessionResults.single().blocked)
    }

    @Test fun moveUsesMacNeighborIncludingFilteredRowsAndRechecksExactGroupAndTarget() {
        val s = state()
        assertEquals(listOf("a"), filterSessions(s.snapshot!!.sessions, "Saved", null, false).map { it.id })
        val move = s.prepareSessionAction("move_session", 101, moveTarget = "b")
        assertEquals("b", move.targetID)
        assertEquals("mb", move.targetTicket)
        s.validate(move, 101)
        for (changed in listOf(peer.copy(sessionActions = peer.sessionActions!!.copy(group = "monitoring")),
            peer.copy(sessionActions = peer.sessionActions!!.copy(ticket = "new")),
            peer.copy(sessionActions = peer.sessionActions!!.copy(sourceID = "canonical")))) {
            val updated = s.receive(s.snapshot.copy(sessions = listOf(saved, changed)), 102)
            assertThrows(IllegalStateException::class.java) { updated.validate(move, 103) }
        }
        for (target in listOf("a", "unknown")) assertThrows(IllegalStateException::class.java) {
            s.prepareSessionAction("move_session", 101, moveTarget = target)
        }
        val waiting = s.sending(move)
        val changed = waiting.receive(s.snapshot.copy(sessions = listOf(peer, saved.copy(sessionActions = metadata.copy(ticket = "new")))), 102)
        val done = changed.acknowledgeSessionAction(move, ack(move))
        assertEquals(listOf("b", "a"), done.snapshot!!.sessions.map { it.id })
        assertEquals("a", done.sessionID)
        assertEquals(done.snapshot, done.receive(done.snapshot, 103).snapshot)
    }

    @Test fun removalNeedsFreshDisconnectedAuthorityAndAckSurvivesRemovedRowWithoutDroppingOtherDraft() {
        val s = state().select("b").edit("retain").select("a")
        val remove = s.prepareSessionAction("remove_session", 101)
        val connected = s.receive(s.snapshot!!.copy(sessions = listOf(saved.copy(connected = true), peer)), 102)
        assertThrows(IllegalStateException::class.java) { connected.validate(remove, 103) }
        val pending = s.sending(remove)
        val removed = pending.receive(s.snapshot.copy(sessions = listOf(peer)), 102)
        val done = removed.acknowledgeSessionAction(remove, ack(remove))
        assertTrue(done.sessionResults.single().status.contains("session_removed"))
        assertEquals("retain", done.select("b").draft)
        assertEquals("a", done.sessionID)
    }

    @Test fun launchAckIsNotConnectionAndUnknownCannotReplayAfterReconnectButDoesNotBlockColor() {
        val s = state()
        val resume = s.prepareSessionAction("resume_session", 101)
        val pending = s.sending(resume)
        assertThrows(IllegalStateException::class.java) { pending.prepareSessionAction("resume_session", 102) }
        val launched = pending.acknowledgeSessionAction(resume, ack(resume))
        assertFalse(launched.session!!.connected)
        assertTrue(launched.sessionResults.single().status.contains("connection is not confirmed"))
        for (bad in listOf("connected", "received", "queued")) assertThrows(IllegalArgumentException::class.java) {
            pending.acknowledgeSessionAction(resume, ack(resume, bad))
        }
        val unknown = pending.uncertain(resume).receive(WorkspaceSnapshot(false, emptyList(), null), 102).receive(s.snapshot!!, 103)
        assertThrows(IllegalStateException::class.java) { unknown.prepareSessionAction("resume_session", 104) }
        val color = unknown.prepareSessionAction("set_session_color", 104, 0xFFFFFF)
        val recolored = unknown.sending(color).acknowledgeSessionAction(color, ack(color))
        assertThrows(IllegalStateException::class.java) { recolored.prepareSessionAction("resume_session", 105) }
        assertEquals(2, recolored.sessionResults.size)
        val unrelated = saved.copy(sessionActions = metadata.copy(ticket = "color-changed", rgb = 0xFFFFFF))
        assertThrows(IllegalStateException::class.java) {
            recolored.receive(s.snapshot.copy(sessions = listOf(unrelated, peer)), 106).prepareSessionAction("resume_session", 107)
        }
        assertThrows(IllegalStateException::class.java) {
            recolored.receive(s.snapshot.copy(sessions = listOf(savedAfterOwner("different-review"), peer)), 106)
                .prepareSessionAction("resume_session", 107)
        }
        val unobserved = savedAfterOwner().let { it.copy(sessionActions = it.sessionActions!!.copy(resumable = false, resumeStatus = "unknown")) }
        assertThrows(IllegalStateException::class.java) {
            recolored.receive(s.snapshot.copy(sessions = listOf(unobserved, peer)), 106).prepareSessionAction("resume_session", 107)
        }
        val laterSaved = savedAfterOwner()
        assertEquals(resume.ticket, laterSaved.ticket)
        val ended = recolored.receive(s.snapshot.copy(sessions = listOf(laterSaved, peer)), 106)
        assertEquals(unknown.sessionResults.single(), ended.sessionResults.first { it.command.action == "resume_session" })
        val freshReview = ended.prepareSessionAction("resume_session", 107)
        assertNotEquals(resume.id, freshReview.id)
        assertEquals("after-owner", freshReview.sessionTicket)
        assertThrows(IllegalStateException::class.java) { ended.validate(resume, 107) }
        assertThrows(IllegalArgumentException::class.java) {
            pending.acknowledgeSessionAction(resume, ack(resume).put("sessionID", "other"))
        }
    }

    @Test fun oldHelperAndMissingNativeMetadataStayUnavailableAndAliasKeepsSelectionOnly() {
        val s = state().select("b").edit("other draft").select("a")
        for (row in listOf(saved.copy(sessionActions = null), saved.copy(sessionActions = metadata.copy(supported = false)))) {
            val old = s.copy(snapshot = s.snapshot!!.copy(sessions = listOf(row, peer)))
            assertThrows(IllegalStateException::class.java) { old.prepareSessionAction("resume_session", 101) }
            assertThrows(IllegalStateException::class.java) { old.prepareSessionAction("reset_session_color", 101) }
        }
        val alias = saved.copy(id = "reconnected", connected = true, ticket = "new",
            sessionActions = metadata.copy(ticket = "new", group = "monitoring", resumable = false, resumeStatus = "connected"))
        val updated = s.receive(s.snapshot!!.copy(sessions = listOf(alias, peer)), 102)
        assertEquals("reconnected", updated.sessionID)
        assertEquals("other draft", updated.select("b").draft)
        assertEquals(0x010203, updated.session!!.sessionActions!!.rgb)
    }
}
