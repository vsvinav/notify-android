package app.notify.android.controller

import java.util.UUID
import org.json.JSONObject

data class Capabilities(val steering: Boolean, val subagentChat: Boolean, val subagentStop: Boolean,
    val dangerousAutoYes: Boolean = false)
data class DangerousAutoYes(val ticket: String, val revision: Long, val enabled: Boolean, val normalEnabled: Boolean,
    val offTicket: String? = null) {
    val hasOffAuthority get() = offTicket?.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$")) == true
}
data class Session(val id: String, val name: String, val project: String, val agent: String,
    val state: String, val connected: Boolean, val ticket: String, val capabilities: Capabilities,
    val runningCount: Int? = null, val idleCount: Int? = null, val dangerousAutoYes: DangerousAutoYes? = null)
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
    val text: String, val agentID: String, val enabled: Boolean? = null, val policyTicket: String? = null,
    val offTicket: String? = null) {
    val isPolicy get() = action == "dangerous_auto_yes"
    val target get() = Target(sessionID, ticket, agentID)
    fun json() = JSONObject().put("id", id).put("sessionID", sessionID).put("ticket", ticket)
        .put("action", action).put("text", text).also {
            if (agentID.isNotEmpty()) it.put("agentID", agentID)
            if (isPolicy) it.put("enabled", enabled).put("policyTicket", policyTicket).put("offTicket", offTicket)
        }
}
data class ControlDelivery(val commandID: String, val status: String, val blocked: Boolean, val receiptID: String? = null,
    val policyRevision: Long? = null)
data class WorkspaceState(
    val snapshot: WorkspaceSnapshot? = null, val sessionID: String? = null, val agentID: String = "",
    val receivedAt: Long? = null, val drafts: Map<Target, String> = emptyMap(),
    val deliveries: Map<Target, ControlDelivery> = emptyMap(),
    val policyDeliveries: Map<Target, ControlDelivery> = emptyMap(),
) {
    val session get() = snapshot?.sessions?.singleOrNull { it.id == sessionID }
    val detail get() = snapshot?.detail?.takeIf { it.sessionID == sessionID && it.ticket == session?.ticket }
    val target get() = session?.let { Target(it.id, it.ticket, agentID) }
    val draft get() = drafts[target] ?: ""
    val delivery get() = deliveries[target]
    val policyDelivery get() = session?.let { policyDeliveries[Target(it.id, it.ticket, "")] }
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
        // Watch responses and control ACKs may cross; an older policy must not undo a newer Mac Off.
        val ordered = value.copy(sessions = value.sessions.map { incoming ->
            val previous = snapshot?.sessions?.singleOrNull { it.id == incoming.id && it.ticket == incoming.ticket }?.dangerousAutoYes
            if (previous != null && incoming.dangerousAutoYes != null && previous.revision > incoming.dangerousAutoYes.revision)
                incoming.copy(dangerousAutoYes = previous) else incoming
        })
        return copy(snapshot = ordered, receivedAt = now,
            drafts = drafts.filterKeys { current[it.sessionID] == it.ticket },
            policyDeliveries = policyDeliveries.filter { (target, delivery) ->
                current[target.sessionID] == target.ticket && (delivery.blocked || delivery.policyRevision == null ||
                    ordered.sessions.singleOrNull { it.id == target.sessionID }?.dangerousAutoYes?.revision == delivery.policyRevision)
            },
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
        if (action == "dangerous_auto_yes") return policyUnavailable(now, true)
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
    fun policyUnavailable(now: Long, enabling: Boolean): String? {
        if (receivedAt == null || now < receivedAt || now - receivedAt >= 10_000 ||
            snapshot?.connected != true || session?.connected != true)
            return "Reconnect for a fresh Mac policy. Off has not been confirmed."
        if (agentID.isNotEmpty()) return "Dangerous Auto-yes is a session policy, not a helper control. Select the main agent."
        if (session?.agent != "copilot" || session?.capabilities?.dangerousAutoYes != true || session?.dangerousAutoYes?.hasOffAuthority != true)
            return "Dangerous Auto-yes is unsupported on this connection. Update the Mac and helper, or use the Mac."
        if (enabling && policyDelivery?.blocked == true) return policyDelivery!!.status
        if (!enabling && policyDelivery?.blocked == true &&
            policyDelivery?.commandID.equals(session?.dangerousAutoYes?.offTicket, ignoreCase = true))
            return policyDelivery!!.status
        return null
    }
    fun preparePolicy(enabled: Boolean, now: Long): ControlCommand {
        check(policyUnavailable(now, enabled) == null) { policyUnavailable(now, enabled)!! }
        return ControlCommand(if (enabled) UUID.randomUUID().toString() else session!!.dangerousAutoYes!!.offTicket!!,
            sessionID!!, session!!.ticket, "dangerous_auto_yes",
            "", "", enabled, session!!.dangerousAutoYes!!.ticket, session!!.dangerousAutoYes!!.offTicket)
    }
    fun prepare(action: String, now: Long): ControlCommand {
        require(action in setOf("queue", "interrupt", "stop"))
        check(unavailable(action, now) == null) { unavailable(action, now)!! }
        require(action == "stop" || draft.isNotBlank()) { "Write a message for this agent." }
        return ControlCommand(UUID.randomUUID().toString(), sessionID!!, session!!.ticket, action,
            if (action == "stop") "" else draft, agentID)
    }
    fun validate(command: ControlCommand, now: Long) {
        if (command.isPolicy) {
            check(sessionID == command.sessionID && session?.ticket == command.ticket && command.agentID.isEmpty() && command.enabled != null &&
                select(sessionID).policyUnavailable(now, command.enabled) == null &&
                command.offTicket == session?.dangerousAutoYes?.offTicket &&
                (if (command.enabled) command.policyTicket == session?.dangerousAutoYes?.ticket
                 else command.id.equals(command.offTicket, ignoreCase = true))) {
                "The Mac policy, session, or connection changed. Review it again; nothing was sent."
            }
            return
        }
        check(target == command.target && unavailable(command.action, now) == null) {
            "The session, target, or connection changed. Review it again; nothing was sent."
        }
        check(command.action == "stop" || draft == command.text) { "The message changed. Review it again." }
    }
    fun sending(command: ControlCommand): WorkspaceState {
        val pending = ControlDelivery(command.id, "Waiting for Mac acknowledgment…", true,
            policyRevision = if (command.isPolicy) session?.dangerousAutoYes?.revision else null)
        return if (command.isPolicy) copy(policyDeliveries = policyDeliveries + (command.target to pending))
            else copy(deliveries = deliveries + (command.target to pending))
    }
    fun uncertain(command: ControlCommand): WorkspaceState {
        val unknown = ControlDelivery(command.id, "Delivery unknown. Check this exact target on Mac; nothing will be resent.", true)
        return if (command.isPolicy) {
            if (policyDeliveries[command.target]?.commandID != command.id) this
            else copy(policyDeliveries = policyDeliveries + (command.target to unknown))
        } else copy(deliveries = deliveries + (command.target to unknown))
    }
    fun rejectPolicyBeforeDispatch(command: ControlCommand, message: String): WorkspaceState {
        require(command.isPolicy && command.enabled == false)
        if (policyDeliveries[command.target]?.commandID != command.id) return this
        return copy(policyDeliveries = policyDeliveries + (command.target to ControlDelivery(command.id,
            "$message Off has not been confirmed.", false,
            policyRevision = policyDeliveries[command.target]?.policyRevision)))
    }
    fun acknowledgePolicy(command: ControlCommand, policy: DangerousAutoYes, ok: Boolean, message: String): WorkspaceState {
        require(command.isPolicy && policyDeliveries[command.target]?.commandID == command.id) {
            "Acknowledgment does not match the pending policy."
        }
        require(!ok || policy.enabled == command.enabled && policy.ticket != command.policyTicket &&
            policy.revision > (policyDeliveries[command.target]?.policyRevision ?: Long.MAX_VALUE) &&
            policy.hasOffAuthority && (command.enabled == true || policy.offTicket != command.offTicket)) {
            "The Mac did not confirm the requested policy."
        }
        val updated = snapshot?.copy(sessions = snapshot.sessions.map {
            if (it.id == command.sessionID && it.ticket == command.ticket &&
                (it.dangerousAutoYes?.revision ?: 0) <= policy.revision) it.copy(dangerousAutoYes = policy) else it
        })
        val superseded = updated?.sessions?.singleOrNull { it.id == command.sessionID && it.ticket == command.ticket }
            ?.dangerousAutoYes?.revision?.let { it > policy.revision } == true
        return copy(snapshot = updated, policyDeliveries = policyDeliveries + (command.target to ControlDelivery(command.id,
            if (superseded) "A newer Mac policy replaced this result."
            else if (!ok) message.ifBlank { "Policy change failed. Check the Mac." }
            else if (policy.enabled) "Mac confirmed Dangerous Auto-yes On." else "Mac confirmed Dangerous Auto-yes Off.",
            false, policyRevision = policy.revision)))
    }
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
