package app.notify.android.controller

import java.util.UUID
import org.json.JSONObject

data class Capabilities(val steering: Boolean, val subagentChat: Boolean, val subagentStop: Boolean)
data class Session(val id: String, val name: String, val project: String, val agent: String,
    val state: String, val connected: Boolean, val ticket: String, val capabilities: Capabilities,
    val runningCount: Int? = null, val idleCount: Int? = null)
data class Agent(val id: String, val name: String, val status: String, val task: String = "",
    val model: String = "", val parentKnown: Boolean = false, val parentAgentID: String? = null)
data class Activity(val id: String, val kind: String, val title: String, val detail: String,
    val timestamp: String, val agentID: String = "", val clientTurnID: String = "")
data class ControlReceipt(val id: String, val action: String, val message: String, val status: String,
    val detail: String, val timestamp: String, val agentID: String = "")
data class SessionDetail(val sessionID: String, val ticket: String, val agents: List<Agent>,
    val activity: List<Activity>, val controls: List<ControlReceipt>, val truncated: Boolean = false, val teamError: String = "")
data class WorkspaceSnapshot(val connected: Boolean, val sessions: List<Session>, val detail: SessionDetail?,
    val truncated: Boolean = false, val error: String = "")
data class Target(val sessionID: String, val ticket: String, val agentID: String)
data class ControlCommand(val id: String, val sessionID: String, val ticket: String, val action: String,
    val text: String, val agentID: String) {
    val target get() = Target(sessionID, ticket, agentID)
    fun json() = JSONObject().put("id", id).put("sessionID", sessionID).put("ticket", ticket)
        .put("action", action).put("text", text).also { if (agentID.isNotEmpty()) it.put("agentID", agentID) }
}
data class ControlDelivery(val commandID: String, val status: String, val blocked: Boolean, val receiptID: String? = null)
data class WorkspaceState(
    val snapshot: WorkspaceSnapshot? = null, val sessionID: String? = null, val agentID: String = "",
    val receivedAt: Long? = null, val drafts: Map<Target, String> = emptyMap(),
    val deliveries: Map<Target, ControlDelivery> = emptyMap(),
) {
    val session get() = snapshot?.sessions?.singleOrNull { it.id == sessionID }
    val detail get() = snapshot?.detail?.takeIf { it.sessionID == sessionID && it.ticket == session?.ticket }
    val target get() = session?.let { Target(it.id, it.ticket, agentID) }
    val draft get() = drafts[target] ?: ""
    val delivery get() = deliveries[target]
    fun select(sessionID: String?, agentID: String = "") = copy(sessionID = sessionID, agentID = agentID)
    fun receive(value: WorkspaceSnapshot, now: Long): WorkspaceState {
        if (!value.connected) return copy(snapshot = snapshot?.copy(connected = false, error = value.error) ?: value, receivedAt = null)
        val current = value.sessions.associate { it.id to it.ticket }
        val retained = deliveries.filterKeys { current[it.sessionID] == it.ticket }.mapValues { (target, delivery) ->
            val receipt = value.detail?.takeIf { it.sessionID == target.sessionID && it.ticket == target.ticket }
                ?.controls?.singleOrNull { it.id == delivery.receiptID && it.agentID == target.agentID }
            if (receipt == null) delivery else delivery.copy(
                status = listOf(receipt.status, receipt.detail).filter { it.isNotBlank() }.joinToString(": "),
                blocked = receipt.status in setOf("unknown", "sending"),
            )
        }
        return copy(snapshot = value, receivedAt = now,
            drafts = drafts.filterKeys { current[it.sessionID] == it.ticket },
            deliveries = retained)
    }
    fun edit(text: String): WorkspaceState {
        val target = checkNotNull(target) { "Select a current session first." }
        require(text.length <= 16_000) { "Message exceeds 16,000 characters." }
        val updated = if (text.isEmpty()) drafts - target else drafts + (target to text)
        require(updated.size <= 100 && updated.values.sumOf { it.toByteArray().size } <= 524_288) {
            "Phone draft storage is full. Clear an unused draft."
        }
        return copy(drafts = updated)
    }
    fun unavailable(action: String, now: Long): String? {
        if (receivedAt == null || now < receivedAt || now - receivedAt >= 10_000 ||
            snapshot?.connected != true || session?.connected != true || detail == null)
            return "Reconnect and wait for a fresh update for this exact session."
        if (delivery?.blocked == true) return delivery!!.status
        val s = session!!
        if (s.agent != "copilot") return "Claude and other providers are read-only here. Use your Mac for controls."
        if (agentID.isNotEmpty()) {
            if (detail!!.teamError.isNotEmpty()) return detail!!.teamError
            val agent = detail!!.agents.singleOrNull { it.id == agentID }
            if (agent == null || agent.status !in setOf("running", "idle")) return "This subagent is unavailable or ended. Nothing will be sent to main."
            if (action == "interrupt") return "Interrupt is only supported for the main agent."
            if (!(if (action == "stop") s.capabilities.subagentStop else s.capabilities.subagentChat))
                return "This connection does not support that subagent control. Check its Notify extension on Mac."
        } else if (!s.capabilities.steering) return "Main controls are unavailable. Check this session’s Notify extension on Mac."
        return null
    }
    fun prepare(action: String, now: Long): ControlCommand {
        require(action in setOf("queue", "interrupt", "stop"))
        check(unavailable(action, now) == null) { unavailable(action, now)!! }
        require(action == "stop" || draft.isNotBlank()) { "Write a message for this agent." }
        return ControlCommand(UUID.randomUUID().toString(), sessionID!!, session!!.ticket, action,
            if (action == "stop") "" else draft, agentID)
    }
    fun validate(command: ControlCommand, now: Long) {
        check(target == command.target && unavailable(command.action, now) == null) {
            "The session, target, or connection changed. Review it again; nothing was sent."
        }
        check(command.action == "stop" || draft == command.text) { "The message changed. Review it again." }
    }
    fun sending(command: ControlCommand) = copy(deliveries = deliveries + (command.target to
        ControlDelivery(command.id, "Waiting for acknowledgment…", true)))
    fun uncertain(command: ControlCommand) = copy(deliveries = deliveries + (command.target to
        ControlDelivery(command.id, "Delivery unknown. Check this exact target on Mac; nothing will be resent.", true)))
    fun acknowledge(command: ControlCommand, commandID: String, status: String): WorkspaceState {
        require(deliveries[command.target]?.commandID == command.id && commandID.isNotBlank() && commandID.length <= 128) {
            "Acknowledgment does not match a pending command. Check your Mac."
        }
        require(status in setOf("queued", "received", "stopped")) { "The Mac did not confirm this control." }
        // RPC UUID correlation is enforced by RemoteConnection; the Mac creates a distinct receipt UUID.
        return copy(deliveries = deliveries + (command.target to ControlDelivery(command.id, status, false, commandID)),
            drafts = if (command.action != "stop" && drafts[command.target] == command.text) drafts - command.target else drafts)
    }
}
