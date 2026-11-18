package app.notify.android.ui

import app.notify.android.controller.*
import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.Prompt
import app.notify.android.remote.RemoteRequest
import org.junit.Assert.*
import org.junit.Test

class AgentPresentationTest {
    private val session = Session("s", "Build", "Notify", "copilot", "ready", true, "ticket",
        Capabilities(true, true, true))
    private val helper = Agent("helper", "Researcher", "running", parentKnown = true)
    private fun state(agents: List<Agent> = listOf(helper)): ControllerState =
        ControllerState(foreground = true, status = RemoteStatus("connected", ""), now = 100,
            workspace = WorkspaceState(sessionID = "s").receive(
                WorkspaceSnapshot(true, listOf(session), SessionDetail("s", "ticket", agents,
                    listOf(Activity("a", "assistant", "Done", "An arbitrary last reply", "1"),
                        Activity("r", "reasoning", "Thinking", "Old reasoning", "2")), emptyList())), 100))
            .receiveRequests(RequestSnapshot(true, emptyList()), 100)

    @Test fun mainIdleIsRestNotSuccessAndOldActivityDoesNotInventThinking() {
        val state = state()
        assertEquals(AgentPresence.Resting, sessionPresentation(state, session).presence)
        assertEquals(AgentPresence.Working, sessionPresentation(state, session.copy(state = "working")).presence)
        assertEquals(AgentPresence.Unknown, sessionPresentation(state, session.copy(state = "future-state")).presence)
        assertEquals(AgentPresence.Waiting, sessionPresentation(state, session.copy(state = "waiting")).presence)
        assertEquals(AgentPresence.Stopped, sessionPresentation(state, session.copy(state = "stopped")).presence)
    }

    @Test fun helperStatesRemainReportedAndCancelledIsNotInteractive() {
        val state = state()
        for ((status, presence) in mapOf("running" to AgentPresence.Working, "idle" to AgentPresence.Resting,
            "completed" to AgentPresence.Completed, "failed" to AgentPresence.Failed,
            "cancelled" to AgentPresence.Stopped, "future-state" to AgentPresence.Unknown)) {
            val presentation = helperPresentation(state, helper.copy(status = status))
            assertEquals(presence, presentation.presence)
            assertEquals(status != "cancelled", presentation.canOpen)
        }
    }

    @Test fun staleDisconnectedAndFailedTeamAreAlwaysLastReported() {
        val state = state()
        assertTrue(sessionPresentation(state, session).current)
        assertTrue(helperPresentation(state, helper).current)
        val staleStates = listOf(state.copy(now = 10_100), state.copy(now = 99),
            state.copy(foreground = false), state.retireConnection(),
            state.copy(workspace = state.workspace.copy(receivedAt = null)),
            state.copy(workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(connected = false))))
        for (stale in staleStates) {
            assertFalse(sessionPresentation(stale, session).current)
            assertFalse(helperPresentation(stale, helper).current)
            assertTrue(helperPresentation(stale, helper).label.startsWith("Last reported"))
        }
        assertFalse(sessionPresentation(state, session.copy(connected = false)).current)
        val failedTeam = state.copy(workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(
            detail = state.workspace.detail!!.copy(teamError = "Team unavailable"))))
        assertFalse(helperPresentation(failedTeam, helper).current)
    }

    @Test fun twentyHelpersDefaultToOneRunningAndNineteenIdleInACount() {
        val agents = listOf(helper) + (1..19).map { Agent("idle$it", "Idle $it", "idle", parentKnown = true) }
        val team = teamPresentation(agents)
        assertEquals(listOf("helper"), team.rows.map { it.agent.id })
        assertEquals(19, team.idleCount)
        assertEquals(0, team.inactiveCount)
        assertEquals(20, teamPresentation(agents, showIdle = true).rows.size)
    }

    @Test fun collapsedParentsNeverLoseOrFalselyConnectTheirActiveChildren() {
        val agents = listOf(helper,
            Agent("idle", "Idle", "idle", parentKnown = true),
            Agent("child", "Active child", "running", parentKnown = true, parentAgentID = "idle"),
            Agent("done", "Done", "completed", parentKnown = true),
            Agent("orphan", "Active child", "running", parentKnown = true, parentAgentID = "done"))
        val team = teamPresentation(agents)
        assertEquals(setOf("helper", "child", "orphan"), team.rows.map { it.agent.id }.toSet())
        assertTrue(team.rows.single { it.agent.id == "child" }.unlinked)
        assertTrue(team.rows.single { it.agent.id == "orphan" }.unlinked)
        assertEquals(1, team.idleCount)
        assertEquals(1, team.inactiveCount)
        val expanded = teamPresentation(agents, showIdle = true)
        assertEquals(1, expanded.rows.single { it.agent.id == "child" }.depth)
        assertFalse(expanded.rows.single { it.agent.id == "child" }.unlinked)
        assertEquals(listOf("done"), expanded.inactiveRows.map { it.agent.id })
    }

    @Test fun realHierarchyKeepsMissingUnknownAndCyclicParentsUnlinked() {
        val agents = listOf(helper,
            Agent("child", "Child", "running", parentKnown = true, parentAgentID = helper.id),
            Agent("unknown", "Unknown", "running"),
            Agent("missing", "Missing", "running", parentKnown = true, parentAgentID = "absent"),
            Agent("a", "Cycle A", "running", parentKnown = true, parentAgentID = "b"),
            Agent("b", "Cycle B", "running", parentKnown = true, parentAgentID = "a"))
        val rows = teamPresentation(agents).rows
        assertEquals(agents.size, rows.size)
        assertEquals(1, rows.single { it.agent.id == "child" }.depth)
        assertEquals(setOf("unknown", "missing", "a", "b"), rows.filter { it.unlinked }.map { it.agent.id }.toSet())
    }

    @Test fun targetPresentationAndDraftsNeverFallBackToMainWhenHelperDisappearsOrSessionSwitches() {
        val state = state()
        val main = state.workspace.edit("Main draft")
        val selected = main.select("s", helper.id).edit("Helper draft")
        assertEquals("Researcher", targetPresentation(state.copy(workspace = selected)).name)
        assertEquals("helper", selected.target!!.agentID)
        val missing = selected.select("s", "missing")
        assertEquals("Helper unavailable", targetPresentation(state.copy(workspace = missing)).name)
        assertEquals(AgentPresence.Unknown, targetPresentation(state.copy(workspace = missing)).presence)
        assertEquals("missing", missing.target!!.agentID)
        assertEquals("Main draft", selected.select("s").draft)
        assertEquals("Helper draft", selected.select("other").select("s", helper.id).draft)
        assertEquals("Main agent", targetPresentation(state.copy(workspace = selected.select("other"))).name)
        assertFalse(targetPresentation(state.copy(workspace = selected.select("other"))).current)
    }

    @Test fun decisionsStayInTheExactSessionWhileGlobalInboxKeepsAllRequests() {
        val first = RemoteRequest("t1", "s", "Build", Prompt("r1", "permission", "First"))
        val other = RemoteRequest("t2", "other", "Other session", Prompt("r2", "permission", "Other"))
        val second = first.copy(ticket = "t3", prompt = first.prompt.copy(id = "r3", title = "Second"))
        val state = state().receiveRequests(RequestSnapshot(true, listOf(first, other, second)), 101)
        assertEquals(listOf(first, second), decisionCards(state, "s").map { it.request })
        assertEquals(listOf(other), decisionCards(state, "other").map { it.request })
        assertEquals(listOf(first, other, second), decisionCards(state, null).map { it.request })
        assertTrue(decisionCards(state, "missing").isEmpty())
        assertEquals(AgentPresence.Waiting, sessionPresentation(state, session).presence)
        assertEquals(SessionGroup.NeedsYou, sessionGroup(state, session))
        assertEquals(SessionGroup.Monitoring, sessionGroup(state(), session))
        assertEquals(SessionGroup.NotConnected, sessionGroup(state(), session.copy(connected = false)))
    }

    @Test fun sideTeamRequiresEnoughRemainingWidthAndScalesWithType() {
        assertFalse(teamFitsBeside(699f, 1f))
        assertTrue(teamFitsBeside(700f, 1f))
        assertFalse(teamFitsBeside(700f, 1.5f))
        assertTrue(teamFitsBeside(1050f, 1.5f))
    }
}
