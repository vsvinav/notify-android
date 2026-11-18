package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test

class SessionBrowseTest {
    @Test fun combinesSearchProviderAndAttentionWithoutChangingSessionIdentity() {
        val sessions = listOf(
            Session("one", "Build Android", "Notify", "copilot", "waiting", true, "t1", Capabilities(true, true, true)),
            Session("two", "Build Android", "Elsewhere", "claude", "waiting", true, "t2", Capabilities(false, false, false)),
            Session("three", "Review", "Notify", "copilot", "working", true, "t3", Capabilities(true, true, true)),
        )
        assertEquals(listOf(sessions[0]), filterSessions(sessions, "  ANDROID ", "copilot", true))
        assertEquals(listOf(sessions[0], sessions[2]), filterSessions(sessions, "notify", null, false))
        assertTrue(filterSessions(sessions, "missing", null, false).isEmpty())
    }

    @Test fun hierarchySeparatesUnknownMissingAndCyclicAncestryWithoutInventingParents() {
        val agents = listOf(
            Agent("root", "Root", "running", parentKnown = true),
            Agent("child", "Child", "idle", parentKnown = true, parentAgentID = "root"),
            Agent("unknown", "Unknown", "running"),
            Agent("missing", "Missing", "running", parentKnown = true, parentAgentID = "absent"),
            Agent("a", "Cycle A", "running", parentKnown = true, parentAgentID = "b"),
            Agent("b", "Cycle B", "idle", parentKnown = true, parentAgentID = "a"),
        )
        val rows = agentHierarchy(agents)
        assertEquals(agents.map { it.id }.toSet(), rows.map { it.agent.id }.toSet())
        assertEquals(1, rows.single { it.agent.id == "child" }.depth)
        assertEquals(setOf("unknown", "missing", "a", "b"), rows.filter { it.unlinked }.map { it.agent.id }.toSet())
        val active = agentHierarchy(agents, hideIdle = true)
        assertFalse(active.any { it.agent.status == "idle" })
        assertTrue(active.single { it.agent.id == "a" }.unlinked)
    }
}
