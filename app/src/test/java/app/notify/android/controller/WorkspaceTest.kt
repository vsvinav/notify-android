package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    private val offEpoch1 = "00000000-0000-8000-8000-000000000001"
    private val offEpoch2 = "00000000-0000-8000-8000-000000000002"
    private val session = Session("s", "Build", "project", "copilot", "working", true, "t", Capabilities(true, true, true))
    private val detail = SessionDetail("s", "t", listOf(Agent("child", "Helper", "running")), emptyList(), emptyList())
    private fun workspace() = WorkspaceState(snapshot = WorkspaceSnapshot(true, listOf(session), detail),
        sessionID = "s", agentID = "child", receivedAt = 100)

    @Test fun legacyDangerousPolicyIsExplicitlyUnsupportedNotAnUnknownAction() {
        val state = workspace().select("s")
        val reason = state.unavailable("dangerous_auto_yes", 101)
        assertNotNull(reason)
        assertTrue(reason!!.contains("unsupported", ignoreCase = true))
    }

    private fun policyWorkspace(enabled: Boolean = false) = workspace().select("s").copy(
        snapshot = WorkspaceSnapshot(true, listOf(session.copy(capabilities = Capabilities(false, false, false, true),
            dangerousAutoYes = DangerousAutoYes("p1", 1, enabled, false, offEpoch1))), detail))

    @Test fun policyNeedsOnlyNativeCapabilityPreservesDraftAndRequiresFreshExactSession() {
        val state = policyWorkspace().edit("keep draft")
        val command = state.preparePolicy(true, 101)
        assertEquals("dangerous_auto_yes", command.action)
        assertEquals("p1", command.json().getString("policyTicket"))
        assertEquals(offEpoch1, command.json().getString("offTicket"))
        assertTrue(command.json().getBoolean("enabled"))
        assertFalse(command.json().has("agentID"))
        assertEquals("", command.text)
        assertThrows(IllegalStateException::class.java) { state.select("s", "child").preparePolicy(true, 101) }
        assertThrows(IllegalStateException::class.java) { state.preparePolicy(true, 10_100) }
        val pending = state.sending(command)
        assertFalse(pending.session!!.dangerousAutoYes!!.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            pending.acknowledgePolicy(command, DangerousAutoYes("changed", 1, true, false, offEpoch1), true, "")
        }
        val acked = pending.acknowledgePolicy(command, DangerousAutoYes("p2", 2, true, false, offEpoch1), true, "")
        assertTrue(acked.session!!.dangerousAutoYes!!.enabled)
        assertEquals("keep draft", acked.draft)
        assertEquals(state.target, acked.target)
        assertNull(acked.delivery)
    }

    @Test fun newerMacOffRejectsReviewedEnableAndOutOfOrderAckCannotRestoreIt() {
        val state = policyWorkspace()
        val enable = state.preparePolicy(true, 101)
        val pending = state.sending(enable)
        val newer = state.snapshot!!.copy(sessions = listOf(state.session!!.copy(
            dangerousAutoYes = DangerousAutoYes("off", 3, false, false, offEpoch2))))
        val off = pending.receive(newer, 200)
        assertThrows(IllegalStateException::class.java) { off.validate(enable, 201) }
        val lateAck = off.acknowledgePolicy(enable, DangerousAutoYes("on", 2, true, false, offEpoch1), true, "")
        assertFalse(lateAck.session!!.dangerousAutoYes!!.enabled)
        assertFalse(lateAck.policyDelivery!!.status.endsWith("On."))
        val lateSnapshot = lateAck.receive(state.snapshot, 300)
        assertEquals("off", lateSnapshot.session!!.dangerousAutoYes!!.ticket)
        val urgentOff = state.preparePolicy(false, 101)
        assertThrows(IllegalStateException::class.java) { off.validate(urgentOff, 201) }
        val enabledWhileOffWasReviewed = state.receive(state.snapshot.copy(sessions = listOf(state.session!!.copy(
            dangerousAutoYes = DangerousAutoYes("on", 2, true, false, offEpoch1)))), 200)
        enabledWhileOffWasReviewed.validate(urgentOff, 201)
        assertEquals(offEpoch1, urgentOff.json().getString("offTicket"))
        assertEquals(offEpoch1, urgentOff.id)
        assertThrows(IllegalStateException::class.java) {
            enabledWhileOffWasReviewed.validate(urgentOff.copy(id = "00000000-0000-4000-8000-000000000001"), 201)
        }
    }

    @Test fun disconnectRetainsLastKnownOnAndUnknownOffCannotBeReplayedInTheSameEpoch() {
        val state = policyWorkspace(true)
        val off = state.preparePolicy(false, 101)
        val lost = state.sending(off).uncertain(off).receive(WorkspaceSnapshot(false, emptyList(), null), 200)
        assertTrue(lost.session!!.dangerousAutoYes!!.enabled)
        assertThrows(IllegalStateException::class.java) { lost.preparePolicy(false, 201) }
        val live = lost.receive(state.snapshot!!, 300)
        assertThrows(IllegalStateException::class.java) { live.preparePolicy(true, 301) }
        assertThrows(IllegalStateException::class.java) { live.preparePolicy(false, 301) }
        val reset = live.receive(state.snapshot.copy(sessions = listOf(state.session!!.copy(
            dangerousAutoYes = DangerousAutoYes("reset", 2, true, false, offEpoch2)))), 302)
        assertEquals(offEpoch2, reset.preparePolicy(false, 303).id)
        assertEquals("keep", live.edit("keep").draft)
    }

    @Test fun failedOffAuditStillReportsAuthoritativeOffWithoutClaimingSuccess() {
        val state = policyWorkspace(true)
        val command = state.preparePolicy(false, 101)
        val ack = state.sending(command).acknowledgePolicy(command, DangerousAutoYes("off", 2, false, false, offEpoch2),
            false, "Off, but audit could not be saved")
        assertFalse(ack.session!!.dangerousAutoYes!!.enabled)
        assertTrue(ack.policyDelivery!!.status.contains("audit"))
        assertThrows(IllegalArgumentException::class.java) {
            state.sending(command).acknowledgePolicy(command, DangerousAutoYes("bad", 2, true, false, offEpoch2), true, "")
        }

    }

    @Test fun rejectedOffRemainsAttachedToItsSessionWhenSelectionChangesBeforeTheResponse() {
        val state = policyWorkspace(true).edit("keep draft")
        val command = state.preparePolicy(false, 101)
        val other = state.session!!.copy(id = "other", ticket = "other-ticket",
            dangerousAutoYes = DangerousAutoYes("other-policy", 99, true, false, offEpoch2))
        val snapshot = state.snapshot!!.copy(sessions = state.snapshot.sessions + other)
        val switched = state.sending(command).receive(snapshot, 102).select("other")
        val rejected = switched.rejectPolicyBeforeDispatch(command, "Workspace was stale.")
        assertEquals("other", rejected.sessionID)
        val restored = rejected.receive(snapshot, 103).select("s")
        assertNotNull(restored.policyDelivery)
        assertFalse(restored.policyDelivery!!.blocked)
        assertTrue(restored.policyDelivery!!.status.contains("not been confirmed"))
        assertEquals("keep draft", restored.draft)
        assertEquals(offEpoch1, restored.preparePolicy(false, 104).id)
    }

    @Test fun sessionPolicyDoesNotChangeSelectedHelperDraftOrPositionAndRejectsHelperPayload() {
        val child = policyWorkspace(true).select("s", "child").edit("helper draft")
        val off = child.select("s").preparePolicy(false, 101)
        child.validate(off, 101)
        assertThrows(IllegalStateException::class.java) { child.validate(off.copy(agentID = "child"), 101) }
        val ack = child.sending(off).acknowledgePolicy(off, DangerousAutoYes("off", 2, false, false, offEpoch2), true, "")
        assertEquals(child.target, ack.target)
        assertEquals("helper draft", ack.draft)
        val reviewed = ControllerState(now = 100, workspace = child, controlReview = child.select("s").preparePolicy(true, 101))
        assertNotNull(reviewed.receiveWorkspace(child.snapshot!!, 102).controlReview)
        val newer = child.snapshot.copy(sessions = listOf(child.session!!.copy(dangerousAutoYes = DangerousAutoYes("new", 3, false, false, offEpoch2))))
        assertNull(reviewed.receiveWorkspace(newer, 103).controlReview)
    }

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
