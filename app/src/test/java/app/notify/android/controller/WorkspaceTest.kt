package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WorkspaceTest {
    private fun immediateWorkspace(): WorkspaceState {
        val snapshot = Snapshots.workspace(JSONObject("""{"connected":true,"steeringImmediateControl":true,"sessions":[{"id":"s","name":"Build","project":"","agent":"copilot","state":"working","connected":true,"ticket":"t","capabilities":{"steering":true,"steeringImmediate":true,"subagentChat":true,"subagentStop":true}}],"detail":{"sessionID":"s","ticket":"t","team":{"agents":[{"id":"child","name":"Helper","status":"running"}]},"activity":[],"controls":[]}}"""))
        return WorkspaceState(sessionID = "s").receive(snapshot, 100).edit("  steer now\n")
    }

    @Test fun mainSendUsesExactInstructionWhileExplicitQueueAndHelperRoutingRemainAvailable() {
        val state = immediateWorkspace()
        val send = state.prepare("send", 101)
        assertEquals("send", send.json().getString("action"))
        assertFalse(send.json().has("agentID"))
        assertFalse(send.requiresConfirmation)
        assertEquals("  steer now\n", send.text)
        state.validate(send, 101)
        assertEquals("queue", state.prepare("queue", 101).action)
        assertFalse(state.prepare("queue", 101).requiresConfirmation)
        assertTrue(state.prepare("interrupt", 101).requiresConfirmation)
        assertTrue(state.prepare("stop", 101).requiresConfirmation)
        assertEquals("queue", workspace().select("s").edit("legacy").prepare("queue", 101).action)
        assertNotNull(workspace().select("s").unavailable("send", 101))
        val child = state.select("s", "child").edit("exact helper")
        val queued = child.prepare("queue", 101)
        assertEquals("child", queued.json().getString("agentID"))
        assertThrows(IllegalStateException::class.java) { child.prepare("send", 101) }
        assertThrows(IllegalStateException::class.java) { child.validate(queued.copy(action = "send"), 101) }
    }

    @Test fun sendValidationRetainsFreshnessAuthorityCapabilityAndDraftGuards() {
        val state = immediateWorkspace()
        val command = state.prepare("send", 101)
        assertThrows(IllegalStateException::class.java) { state.validate(command, 10_100) }
        assertThrows(IllegalStateException::class.java) { state.edit("changed").validate(command, 101) }
        for (replacement in listOf(
            state.session!!.copy(capabilities = Capabilities(true, true, true)),
            state.session!!.copy(ticket = "new"),
            state.session!!.copy(connected = false),
            state.session!!.copy(agent = "claude"),
        )) {
            val updated = state.receive(state.snapshot!!.copy(sessions = listOf(replacement)), 102)
            assertThrows(IllegalStateException::class.java) { updated.validate(command, 103) }
            assertEquals(state.draft, updated.draft)
        }
        for (blocked in listOf(state.sending(command), state.sending(command).uncertain(command))) {
            assertEquals("keep editing", blocked.edit("keep editing").draft)
            assertThrows(IllegalStateException::class.java) { blocked.prepare("send", 102) }
        }
    }

    @Test fun submittedAckClearsOnlyExactSentRevisionWithoutClaimingConsumption() {
        val state = workspace().select("s").edit("hello")
        val command = state.prepare("queue", 101).copy(action = "send")
        val pending = state.sending(command)
        for (invalid in listOf("queued", "stopped", "sending", "unknown", "failed"))
            assertThrows(IllegalArgumentException::class.java) { pending.acknowledge(command, "receipt", invalid) }
        val submitted = pending.acknowledge(command, "receipt", "submitted")
        assertEquals("", submitted.draft)
        assertEquals("submitted", submitted.delivery!!.status)
        assertFalse(submitted.delivery!!.blocked)
        for (edited in listOf(pending.edit("new"), pending.edit("").edit("hello"), pending.edit("new").edit("hello")))
            assertEquals(edited.draft, edited.acknowledge(command, "receipt", "submitted").draft)
        val receipt = ControlReceipt("receipt", "send", "hello", "submitted", "Awaiting next model request", "1")
        val refreshed = submitted.receive(state.snapshot!!.copy(detail = detail.copy(controls = listOf(receipt))), 102)
        assertTrue(refreshed.delivery!!.status.startsWith("submitted"))
        assertFalse(refreshed.delivery!!.blocked)
        for (status in listOf("unknown", "sending", "failed")) {
            val updated = submitted.receive(state.snapshot.copy(detail = detail.copy(
                controls = listOf(receipt.copy(status = status)))), 102)
            assertEquals(status != "failed", updated.delivery!!.blocked)
            assertTrue(updated.delivery!!.status.startsWith(status))
        }
        assertEquals("received", refreshed.receive(state.snapshot.copy(detail = detail.copy(
            controls = listOf(receipt.copy(status = "received", detail = "")))), 103).delivery!!.status)
        assertThrows(IllegalArgumentException::class.java) { pending.acknowledge(command.copy(id = "wrong"), "receipt", "submitted") }
    }

    @Test fun earlyRuntimeReceivedResponseRetainsConsumptionAndExactDraftRevisionGuards() {
        val state = immediateWorkspace()
        val command = state.prepare("send", 101)
        val pending = state.sending(command)
        val received = pending.acknowledge(command, "runtime-receipt", "received")
        assertEquals("received", received.delivery!!.status)
        assertEquals("runtime-receipt", received.delivery!!.receiptID)
        assertFalse(received.delivery!!.blocked)
        assertEquals("", received.draft)
        for (edited in listOf(pending.edit("new"), pending.edit("").edit(command.text),
            pending.edit("new").edit(command.text))) {
            val result = edited.acknowledge(command, "runtime-receipt", "received")
            assertEquals(edited.draft, result.draft)
            assertEquals("received", result.delivery!!.status)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pending.acknowledge(command.copy(id = "wrong"), "runtime-receipt", "received")
        }
        for (invalid in listOf("queued", "stopped"))
            assertThrows(IllegalArgumentException::class.java) { pending.acknowledge(command, "runtime-receipt", invalid) }
    }

    @Test fun legacySteeringMustNotPretendToSupportSubagentManagement() {
        val state = workspace().select("s")
        for (action in listOf("create_subagent", "remove_subagent", "stop_idle_subagents"))
            assertNotNull("Old Mac steering cannot dispatch $action", state.unavailable(action, 101))
    }
    private val offEpoch1 = "00000000-0000-8000-8000-000000000001"
    private val offEpoch2 = "00000000-0000-8000-8000-000000000002"
    private val session = Session("s", "Build", "project", "copilot", "working", true, "t", Capabilities(true, true, true))
    private val detail = SessionDetail("s", "t", listOf(Agent("child", "Helper", "running")), emptyList(), emptyList())
    private fun workspace() = WorkspaceState(snapshot = WorkspaceSnapshot(true, listOf(session), detail),
        sessionID = "s", agentID = "child", receivedAt = 100)
    private fun managementWorkspace() = workspace().copy(snapshot = WorkspaceSnapshot(true, listOf(session.copy(
        capabilities = session.capabilities.copy(subagentCreate = true, subagentRemove = true, subagentCleanup = true),
        managementTicket = "m1", subagentTypes = listOf("reviewer"), subagentModels = listOf("live-model"))), detail))
    private val creation = SubagentCreation("Review", "reviewer", "  Exact instructions\n", "live-model")
    private fun managementAck(status: String, ok: Boolean = true) = JSONObject()
        .put("ok", ok).put("status", status).put("commandID", "runtime-receipt")
        .put("detail", "Stopped 2; skipped 1; failed 0; uncertain 0")

    @Test fun creationRoutesToSessionWithExactLiveCatalogAndNeverTouchesChatDrafts() {
        val state = managementWorkspace().edit("helper draft").select("s").edit("main draft")
            .select("s", "child").editCreation(creation)
        val command = state.prepareManagement("create_subagent", 101)
        assertEquals("s", command.sessionID)
        assertEquals("", command.agentID)
        assertEquals("", command.text)
        assertEquals("m1", command.json().getString("managementTicket"))
        assertEquals(creation.prompt, command.json().getJSONObject("creation").getString("prompt"))
        assertEquals("live-model", command.json().getJSONObject("creation").getString("model"))
        assertEquals(4, java.util.UUID.fromString(command.id).version())
        state.validate(command, 101)
        for (status in listOf("received", "queued", "removed", "cleaned")) assertThrows(IllegalArgumentException::class.java) {
            state.sending(command).acknowledgeManagement(command, managementAck(status))
        }
        val done = state.sending(command).acknowledgeManagement(command, managementAck("created"))
        assertEquals(SubagentCreation(), done.creationDraft.value)
        assertEquals("helper draft", done.draft)
        assertEquals("main draft", done.select("s").draft)
        assertEquals("child", done.agentID)
        assertNull(done.delivery)
    }

    @Test fun creationKeepsNewEditsAndSessionIdentityDuringPendingResultsOrReconnect() {
        val state = managementWorkspace().editCreation(creation)
        val command = state.prepareManagement("create_subagent", 101)
        val editing = state.sending(command).editCreation(creation.copy(name = "New"))
            .editCreation(creation).select("other")
        val done = editing.acknowledgeManagement(command, managementAck("created"))
        assertEquals("other", done.sessionID)
        assertEquals(creation, done.select("s").creationDraft.value)
        val rejected = state.sending(command).acknowledgeManagement(command, managementAck("failed", false))
        assertEquals(creation, rejected.creationDraft.value)
        assertFalse(rejected.managementDelivery!!.blocked)
        val unknown = state.sending(command).uncertain(command).receive(WorkspaceSnapshot(false, emptyList(), null), 102)
            .receive(state.snapshot!!, 103)
        assertEquals(creation, unknown.creationDraft.value)
        assertThrows(IllegalStateException::class.java) { unknown.prepareManagement("create_subagent", 104) }
    }

    @Test fun liveCapabilityModelChangesInvalidateReviewWithoutLosingAnyDraft() {
        val state = managementWorkspace().edit("chat").editCreation(creation)
        val command = state.prepareManagement("create_subagent", 101)
        for (session in listOf(state.session!!.copy(subagentModels = emptyList()),
            state.session!!.copy(managementTicket = "m2"),
            state.session!!.copy(capabilities = state.session!!.capabilities.copy(subagentCreate = false)),
            state.session!!.copy(agent = "claude"), state.session!!.copy(connected = false))) {
            val changed = ControllerState(workspace = state, controlReview = command)
                .receiveWorkspace(state.snapshot!!.copy(sessions = listOf(session)), 102)
            assertNull(changed.controlReview)
            assertEquals("chat", changed.workspace.draft)
            assertEquals(creation, changed.workspace.creationDraft.value)
            assertThrows(IllegalStateException::class.java) { changed.workspace.validate(command, 103) }
        }
        val defaults = state.copy(snapshot = state.snapshot!!.copy(sessions = listOf(state.session!!.copy(subagentModels = emptyList()))))
            .editCreation(creation.copy(model = null))
        assertFalse(defaults.prepareManagement("create_subagent", 101).json().getJSONObject("creation").has("model"))
        assertThrows(IllegalStateException::class.java) { state.prepareManagement("create_subagent", 10100) }
        assertThrows(IllegalStateException::class.java) { state.select("other").validate(command, 101) }
        assertThrows(IllegalArgumentException::class.java) { state.editCreation(creation.copy(name = "😀".repeat(81))) }
    }

    @Test fun removalRequiresExactEndedHelperAndCleanupIsOnlySelectedSession() {
        val state = managementWorkspace().edit("helper draft")
        assertThrows(IllegalStateException::class.java) { state.prepareManagement("remove_subagent", 101, "child") }
        val ended = state.copy(snapshot = state.snapshot!!.copy(detail = detail.copy(agents = listOf(Agent("child", "Helper", "failed")))))
        val remove = ended.prepareManagement("remove_subagent", 101, "child")
        assertEquals("child", remove.agentID)
        assertEquals("removed", ended.sending(remove).acknowledgeManagement(remove, managementAck("removed")).managementDeliveries[remove.target]!!.status.substringBefore(":"))
        assertThrows(IllegalStateException::class.java) { ended.prepareManagement("remove_subagent", 101, "missing") }
        val cleanup = state.prepareManagement("stop_idle_subagents", 101)
        assertFalse(cleanup.json().has("agentID"))
        assertFalse(cleanup.json().has("creation"))
        assertEquals("s", cleanup.sessionID)
        val done = state.sending(cleanup).acknowledgeManagement(cleanup, managementAck("cleaned"))
        assertTrue(done.managementDelivery!!.status.contains("skipped 1"))
        assertEquals("helper draft", done.draft)
        val partial = state.sending(cleanup).acknowledgeManagement(cleanup, managementAck("unknown", false)
            .put("detail", "Cancelled 2; skipped 3; failed 1; uncertain 1"))
        assertTrue(partial.managementDelivery!!.status.contains("uncertain 1"))
        assertTrue(partial.managementDelivery!!.blocked)
        assertThrows(IllegalStateException::class.java) { partial.prepareManagement("stop_idle_subagents", 102) }
        assertThrows(IllegalArgumentException::class.java) { state.prepareManagement("stop_idle_subagents", 101, "child") }
    }

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
    @Test fun uncertainDeliveryBlocksReplayAndTicketChangesPreserveItsDraft() {
        val state = workspace().edit("hello")
        val command = state.prepare("queue", 101)
        val pending = state.sending(command).uncertain(command)
        assertThrows(IllegalStateException::class.java) { pending.prepare("queue", 102) }
        val refreshed = pending.receive(state.snapshot!!, 200)
        assertThrows(IllegalStateException::class.java) { refreshed.prepare("queue", 201) }
        val changed = state.receive(state.snapshot.copy(sessions = listOf(session.copy(ticket = "new"))), 200)
        assertEquals("hello", changed.draft)
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
    @Test fun refreshedAuthorityDoesNotEraseAnUnsentConversationDraft() {
        val state = workspace().edit("I am still typing")
        val reviewed = state.prepare("queue", 101)
        val refreshed = state.receive(state.snapshot!!.copy(
            sessions = listOf(session.copy(ticket = "new")),
            detail = detail.copy(ticket = "new")), 200)
        assertEquals("I am still typing", refreshed.draft)
        assertEquals("I am still typin", refreshed.edit("I am still typin").draft)
        assertThrows(IllegalStateException::class.java) { refreshed.validate(reviewed, 201) }
    }

    @Test fun acknowledgmentDoesNotEraseADraftEditedBackToTheSameText() {
        val state = workspace().edit("hello")
        val command = state.prepare("queue", 101)
        val edited = state.sending(command).edit("hello again").edit("hello")
        assertEquals("hello", edited.acknowledge(command, "native-receipt", "received").draft)
    }
    @Test fun acknowledgmentDoesNotEraseADraftClearedAndRetyped() {
        val state = workspace().edit("hello")
        val command = state.prepare("queue", 101)
        val edited = state.sending(command).edit("").edit("hello")
        assertEquals("hello", edited.acknowledge(command, "native-receipt", "received").draft)
    }
    @Test fun disconnectedSnapshotKeepsConversationAndDraftButDisablesControls() {
        val state = workspace().edit("keep me")
        val offline = state.receive(WorkspaceSnapshot(false, emptyList(), null), 200)
        assertEquals("keep me", offline.draft)
        assertEquals(detail, offline.detail)
        assertThrows(IllegalStateException::class.java) { offline.prepare("queue", 201) }
    }
}
