package app.notify.android.ui

import app.notify.android.controller.*
import app.notify.android.remote.RequestCard

internal enum class SessionGroup(val title: String) {
    NeedsYou("Needs you"), Monitoring("Monitoring"), NotConnected("Not connected"), Saved("Saved")
}

internal fun decisionCards(state: ControllerState, sessionID: String?): List<RequestCard> =
    state.inbox.visibleCards.filter { sessionID == null || it.request.sessionID == sessionID }

internal fun sessionGroup(state: ControllerState, session: Session): SessionGroup = when {
    session.sessionActions?.group == "saved" -> SessionGroup.Saved
    session.sessionActions?.group == "needs_you" -> SessionGroup.NeedsYou
    session.sessionActions?.group == "monitoring" -> SessionGroup.Monitoring
    session.sessionActions?.group == "not_connected" -> SessionGroup.NotConnected
    session.state == "waiting" || state.inbox.visibleCards.any { it.request.sessionID == session.id } -> SessionGroup.NeedsYou
    !session.connected -> SessionGroup.NotConnected
    else -> SessionGroup.Monitoring
}

internal fun teamFitsBeside(contentWidthDp: Float, fontScale: Float): Boolean =
    contentWidthDp >= 700f * fontScale.coerceAtLeast(1f)

internal enum class AgentPresence(val title: String) {
    Working("Working"), Waiting("Needs you"), Resting("At rest"), Completed("Completed"),
    Stopped("Stopped"), Failed("Needs attention"), Starting("Starting"),
    Disconnected("Not connected"), Unknown("State unavailable")
}

internal data class AgentPresentation(val name: String, val presence: AgentPresence, val current: Boolean) {
    val label get() = if (current) presence.title else "Last reported · ${presence.title}"
    val canOpen get() = presence != AgentPresence.Stopped
}

internal data class TeamPresentation(
    val rows: List<AgentRow>, val idleCount: Int, val inactiveRows: List<AgentRow>,
) {
    val inactiveCount get() = inactiveRows.size
}

private fun workspaceCurrent(state: ControllerState, session: Session?): Boolean {
    val received = state.workspace.receivedAt ?: return false
    return state.live && state.now >= received && state.now - received < 10_000 &&
        state.workspace.snapshot?.connected == true && session?.connected == true
}

internal fun sessionPresentation(state: ControllerState, session: Session?): AgentPresentation {
    val presence = when {
        session == null -> AgentPresence.Unknown
        !session.connected -> AgentPresence.Disconnected
        state.inbox.visibleCards.any { it.request.sessionID == session.id } -> AgentPresence.Waiting
        else -> when (session.state) {
            "working" -> AgentPresence.Working
            "waiting" -> AgentPresence.Waiting
            "ready", "idle" -> AgentPresence.Resting
            "stopped" -> AgentPresence.Stopped
            "error" -> AgentPresence.Failed
            "starting" -> AgentPresence.Starting
            else -> AgentPresence.Unknown
        }
    }
    return AgentPresentation("Main agent", presence, workspaceCurrent(state, session))
}

internal fun helperPresentation(state: ControllerState, agent: Agent): AgentPresentation {
    val presence = when (agent.status) {
        "running" -> AgentPresence.Working
        "idle" -> AgentPresence.Resting
        "completed" -> AgentPresence.Completed
        "cancelled" -> AgentPresence.Stopped
        "failed" -> AgentPresence.Failed
        else -> AgentPresence.Unknown
    }
    return AgentPresentation(agent.name, presence, workspaceCurrent(state, state.workspace.session) &&
        state.workspace.detail?.teamError == "")
}

internal fun targetPresentation(state: ControllerState): AgentPresentation {
    val workspace = state.workspace
    if (workspace.agentID.isEmpty()) return sessionPresentation(state, workspace.session)
    val agent = workspace.detail?.agents?.singleOrNull { it.id == workspace.agentID }
        ?: return AgentPresentation("Helper unavailable", AgentPresence.Unknown, false)
    return helperPresentation(state, agent)
}

internal fun teamPresentation(agents: List<Agent>, showIdle: Boolean = false): TeamPresentation {
    val (inactive, active) = agents.partition { it.status in setOf("completed", "failed", "cancelled") }
    return TeamPresentation(
        agentHierarchy(active, hideIdle = !showIdle),
        active.count { it.status == "idle" },
        agentHierarchy(inactive),
    )
}
