package app.notify.android.controller

fun filterSessions(sessions: List<Session>, query: String, provider: String?, needsAttention: Boolean): List<Session> {
    val search = query.trim()
    return sessions.filter { session ->
        (provider == null || session.agent == provider) &&
            (!needsAttention || session.state == "waiting") &&
            (search.isEmpty() || listOf(session.name, session.project, session.agent).any { it.contains(search, ignoreCase = true) })
    }
}

data class AgentRow(val agent: Agent, val depth: Int, val unlinked: Boolean)

fun agentHierarchy(agents: List<Agent>, hideIdle: Boolean = false): List<AgentRow> {
    val all = agents.associateBy { it.id }
    val visible = agents.filterNot { hideIdle && it.status == "idle" }
    val visibleIDs = visible.map { it.id }.toSet()
    fun unlinked(agent: Agent): Boolean {
        var current = agent
        val visited = mutableSetOf(agent.id)
        while (true) {
            if (!current.parentKnown) return true
            val parent = current.parentAgentID ?: return false
            if (parent !in visibleIDs || !visited.add(parent)) return true
            current = all[parent] ?: return true
        }
    }
    val disconnected = visible.filter(::unlinked)
    val linked = visible.filterNot { it in disconnected }
    val children = linked.groupBy { it.parentAgentID }
    val rows = mutableListOf<AgentRow>()
    fun append(agent: Agent, depth: Int) {
        rows.add(AgentRow(agent, depth, false))
        children[agent.id]?.forEach { append(it, depth + 1) }
    }
    children[null]?.forEach { append(it, 0) }
    return rows + disconnected.map { AgentRow(it, 0, true) }
}
