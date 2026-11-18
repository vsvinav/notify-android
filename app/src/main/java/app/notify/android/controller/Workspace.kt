package app.notify.android.controller

import java.util.UUID
import org.json.JSONObject

data class Capabilities(val steering: Boolean, val subagentChat: Boolean, val subagentStop: Boolean,
    val dangerousAutoYes: Boolean = false, val subagentCreate: Boolean = false,
    val subagentRemove: Boolean = false, val subagentCleanup: Boolean = false)
data class SubagentCreation(val name: String = "", val agentType: String = "", val prompt: String = "", val model: String? = null) {
    fun withinLimits() = name.length <= 160 && agentType.length <= 128 && prompt.length <= 16_000 &&
        (model == null || model.length <= 200)
    fun valid() = withinLimits() && name.isNotBlank() && agentType.isNotBlank() && prompt.isNotBlank() &&
        (model == null || model.isNotBlank())
    fun json() = JSONObject().put("name", name).put("agentType", agentType).put("prompt", prompt)
        .also { if (model != null) it.put("model", model) }
}
data class CreationDraft(val value: SubagentCreation = SubagentCreation(), val revision: Long = 0)
data class DangerousAutoYes(val ticket: String, val revision: Long, val enabled: Boolean, val normalEnabled: Boolean,
    val offTicket: String? = null) {
    val hasOffAuthority get() = offTicket?.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$")) == true
}
data class Session(val id: String, val name: String, val project: String, val agent: String,
    val state: String, val connected: Boolean, val ticket: String, val capabilities: Capabilities,
    val runningCount: Int? = null, val idleCount: Int? = null, val dangerousAutoYes: DangerousAutoYes? = null,
    val managementTicket: String? = null, val subagentTypes: List<String> = emptyList(), val subagentModels: List<String> = emptyList(),
    val color: Int? = null, val sessionActions: SessionActions? = null)
data class SessionColor(val name: String, val rgb: Int)
data class ResumeAuthority(val epoch: String, val retired: Long)
data class SessionActions(val ticket: String, val sourceID: String, val group: String, val rgb: Int, val overridden: Boolean,
    val colors: List<SessionColor>, val editable: Boolean, val removable: Boolean, val resumable: Boolean,
    val resumeStatus: String, val resumeDetail: String, val moveUp: String? = null, val moveDown: String? = null,
    val supported: Boolean = false, val resumeAuthority: ResumeAuthority? = null)
val sessionActionStatuses = mapOf("set_session_color" to "color_saved", "reset_session_color" to "color_reset",
    "move_session" to "session_moved", "remove_session" to "session_removed", "resume_session" to "launched")
data class Agent(val id: String, val name: String, val status: String, val task: String = "",
    val model: String = "", val parentKnown: Boolean = false, val parentAgentID: String? = null)
data class Activity(val id: String, val kind: String, val title: String, val detail: String,
    val timestamp: String, val agentID: String = "", val clientTurnID: String = "")
data class ControlReceipt(val id: String, val action: String, val message: String, val status: String,
    val detail: String, val timestamp: String, val agentID: String = "")
data class SessionDetail(val sessionID: String, val ticket: String, val agents: List<Agent>,
    val activity: List<Activity>, val controls: List<ControlReceipt>, val truncated: Boolean = false, val teamError: String = "",
    val teamAvailable: Boolean = true)
data class WorkspaceSnapshot(val connected: Boolean, val sessions: List<Session>, val detail: SessionDetail?,
    val truncated: Boolean = false, val error: String = "")
data class Target(val sessionID: String, val ticket: String, val agentID: String)
data class DraftTarget(val sessionID: String, val agentID: String)
data class MessageDraft(val text: String, val revision: String = UUID.randomUUID().toString())
data class ControlCommand(val id: String, val sessionID: String, val ticket: String, val action: String,
    val text: String, val agentID: String, val enabled: Boolean? = null, val policyTicket: String? = null,
    val offTicket: String? = null, val managementTicket: String? = null, val creation: SubagentCreation? = null,
    val creationRevision: Long? = null, val sessionTicket: String? = null, val rgb: Int? = null,
    val targetID: String? = null, val targetTicket: String? = null, val sourceID: String? = null,
    val resumeAuthority: ResumeAuthority? = null, val draftRevision: String? = null) {
    val isPolicy get() = action == "dangerous_auto_yes"
    val isManagement get() = action in setOf("create_subagent", "remove_subagent", "stop_idle_subagents")
    val isSessionAction get() = action in sessionActionStatuses
    val sessionActionKey get() = "$sourceID\u0000$action"
    val target get() = Target(sessionID, ticket, agentID)
    val draftTarget get() = DraftTarget(sessionID, agentID)
    fun json() = JSONObject().put("id", id).put("sessionID", sessionID).put("ticket", ticket)
        .put("action", action).put("text", text).also {
            if (agentID.isNotEmpty()) it.put("agentID", agentID)
            if (isPolicy) it.put("enabled", enabled).put("policyTicket", policyTicket).put("offTicket", offTicket)
            if (isManagement) it.put("managementTicket", managementTicket)
            if (creation != null) it.put("creation", creation.json())
            if (isSessionAction) it.put("sessionTicket", sessionTicket)
            if (rgb != null) it.put("rgb", rgb)
            if (targetID != null) it.put("targetID", targetID).put("targetTicket", targetTicket)
        }
}
data class ControlDelivery(val commandID: String, val status: String, val blocked: Boolean, val receiptID: String? = null,
    val policyRevision: Long? = null)
data class SessionActionDelivery(val command: ControlCommand, val status: String, val blocked: Boolean)
data class WorkspaceState(
    val snapshot: WorkspaceSnapshot? = null, val sessionID: String? = null, val agentID: String = "",
    val receivedAt: Long? = null, val drafts: Map<DraftTarget, MessageDraft> = emptyMap(),
    val deliveries: Map<Target, ControlDelivery> = emptyMap(),
    val policyDeliveries: Map<Target, ControlDelivery> = emptyMap(),
    val creationDrafts: Map<String, CreationDraft> = emptyMap(),
    val managementDeliveries: Map<Target, ControlDelivery> = emptyMap(),
    val sessionDeliveries: Map<String, SessionActionDelivery> = emptyMap(),
) {
    val session get() = snapshot?.sessions?.singleOrNull { it.id == sessionID }
    val detail get() = snapshot?.detail?.takeIf { it.sessionID == sessionID && it.ticket == session?.ticket }
    val target get() = session?.let { Target(it.id, it.ticket, agentID) }
    val draftTarget get() = session?.let { DraftTarget(it.id, agentID) }
    val draft get() = drafts[draftTarget]?.text ?: ""
    val delivery get() = deliveries[target]
    val policyDelivery get() = session?.let { policyDeliveries[Target(it.id, it.ticket, "")] }
    val creationDraft get() = creationDrafts[sessionID] ?: CreationDraft()
    val managementDelivery get() = session?.let { managementDeliveries[Target(it.id, it.ticket, "")] }
    val sessionResults get() = sessionDeliveries.values.filter {
        it.command.sessionID == sessionID || it.command.sourceID == session?.sessionActions?.sourceID
    }
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
        val alias = if (current[sessionID] == null) session?.sessionActions?.sourceID?.let { source ->
            ordered.sessions.singleOrNull { it.agent == session?.agent && it.sessionActions?.sourceID == source }?.id
        } else null
        return copy(snapshot = ordered, receivedAt = now, sessionID = alias ?: sessionID,
            drafts = drafts.filterKeys { it.sessionID in current },
            policyDeliveries = policyDeliveries.filter { (target, delivery) ->
                current[target.sessionID] == target.ticket && (delivery.blocked || delivery.policyRevision == null ||
                    ordered.sessions.singleOrNull { it.id == target.sessionID }?.dangerousAutoYes?.revision == delivery.policyRevision)
            },
            deliveries = retained)
    }
    fun edit(text: String): WorkspaceState {
        val target = checkNotNull(draftTarget) { "Select a current session first." }
        require(text.length <= 16_000) { "Message exceeds 16,000 characters." }
        if (text == draft) return this
        val updated = if (text.isEmpty()) drafts - target else drafts + (target to MessageDraft(text))
        require(updated.size <= 100 && updated.values.sumOf { it.text.toByteArray().size } <= 524_288) {
            "Phone draft storage is full. Clear an unused draft."
        }
        return copy(drafts = updated)
    }
    fun unavailable(action: String, now: Long): String? {
        if (action == "dangerous_auto_yes") return policyUnavailable(now, true)
        if (action in setOf("create_subagent", "remove_subagent", "stop_idle_subagents"))
            return managementUnavailable(action, now, if (action == "remove_subagent") agentID else "")
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
            if (action == "stop") "" else draft, agentID, draftRevision = drafts[draftTarget]?.revision)
    }
    fun validate(command: ControlCommand, now: Long) {
        if (command.isSessionAction) {
            check(sessionID == command.sessionID && session?.ticket == command.ticket &&
                session?.sessionActions?.ticket == command.sessionTicket &&
                session?.sessionActions?.sourceID == command.sourceID &&
                (command.action != "resume_session" || session?.sessionActions?.resumeAuthority == command.resumeAuthority) &&
                sessionActionUnavailable(command.action, now, command.targetID) == null) {
                "This session or its Mac settings changed. Review it again; nothing was sent."
            }
            check(command.targetID == null || snapshot?.sessions?.singleOrNull { it.id == command.targetID }
                ?.sessionActions?.ticket == command.targetTicket) { "The move target changed. Review it again." }
            return
        }
        if (command.isManagement) {
            check(sessionID == command.sessionID && session?.ticket == command.ticket &&
                session?.managementTicket == command.managementTicket &&
                managementUnavailable(command.action, now, command.agentID) == null) {
                "The session, helper or live management catalog changed. Review it again; nothing was sent."
            }
            check(command.action != "create_subagent" || command.creation == creationDraft.value &&
                command.creationRevision == creationDraft.revision && creationUnavailable() == null) {
                "The form or selected live type/model changed. Review it again; no default was substituted."
            }
            return
        }
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
        check(command.action == "stop" || draft == command.text && drafts[draftTarget]?.revision == command.draftRevision) {
            "The message changed. Review it again."
        }
    }
    fun sending(command: ControlCommand): WorkspaceState {
        val pending = ControlDelivery(command.id, "Waiting for Mac acknowledgment…", true,
            policyRevision = if (command.isPolicy) session?.dangerousAutoYes?.revision else null)
        return if (command.isSessionAction) copy(sessionDeliveries = sessionDeliveries +
            (command.sessionActionKey to SessionActionDelivery(command, pending.status, true)))
            else if (command.isManagement) copy(managementDeliveries = managementDeliveries + (command.target to pending))
            else if (command.isPolicy) copy(policyDeliveries = policyDeliveries + (command.target to pending))
            else copy(deliveries = deliveries + (command.target to pending))
    }
    fun uncertain(command: ControlCommand): WorkspaceState {
        val unknown = ControlDelivery(command.id, "Delivery unknown. Check this exact target on Mac; nothing will be resent.", true)
        return if (command.isSessionAction) {
            if (sessionDeliveries[command.sessionActionKey]?.command?.id != command.id) this
            else copy(sessionDeliveries = sessionDeliveries + (command.sessionActionKey to SessionActionDelivery(command, unknown.status, true)))
        } else if (command.isManagement) {
            if (managementDeliveries[command.target]?.commandID != command.id) this
            else copy(managementDeliveries = managementDeliveries + (command.target to unknown))
        } else if (command.isPolicy) {
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
            drafts = if (command.action != "stop" && drafts[command.draftTarget]?.text == command.text &&
                drafts[command.draftTarget]?.revision == command.draftRevision) drafts - command.draftTarget else drafts)
    }
    fun sessionActionUnavailable(action: String, now: Long, moveTarget: String? = null): String? {
        if (receivedAt == null || now < receivedAt || now - receivedAt >= 10_000 || snapshot?.connected != true)
            return "Reconnect and wait for fresh session settings from the Mac."
        val s = session ?: return "Select a current session."
        val a = s.sessionActions
        if (action !in sessionActionStatuses || a?.supported != true || !a.editable)
            return "Session settings are unavailable on this Mac/helper. Update both, or use the Mac."
        if (snapshot.sessions.count { it.sessionActions?.sourceID == a.sourceID } != 1)
            return "This session identity is ambiguous. Refresh from the Mac."
        sessionDeliveries["${a.sourceID}\u0000$action"]?.takeIf { it.blocked }?.let {
            val reviewed = it.command.resumeAuthority
            val current = a.resumeAuthority
            val laterSavedGeneration = action == "resume_session" && a.resumable && a.resumeStatus == "available" &&
                reviewed != null && current != null && current.epoch == reviewed.epoch && current.retired > reviewed.retired
            if (!laterSavedGeneration) return it.status
        }
        if (sessionDeliveries.size >= 256 && "${a.sourceID}\u0000$action" !in sessionDeliveries)
            return "Session result storage is full. Check the Mac before starting a new connection."
        if (action == "remove_session" && (s.connected || !a.removable))
            return "Only a disconnected cached session without a live owner or pending launch can be removed."
        if (action == "resume_session" && (s.connected || !a.resumable || a.group != "saved"))
            return a.resumeDetail.ifBlank { "Resume is unavailable. Check the original session on the Mac." }
        if (action == "move_session") {
            val target = snapshot.sessions.singleOrNull { it.id == moveTarget }?.sessionActions
            if (moveTarget == null || moveTarget !in listOf(a.moveUp, a.moveDown) || target?.editable != true ||
                target.group != a.group || target.sourceID == a.sourceID ||
                snapshot.sessions.count { it.sessionActions?.sourceID == target.sourceID } != 1)
                return "No current same-group move target. Filters never change the Mac’s order."
        }
        return null
    }
    fun prepareSessionAction(action: String, now: Long, rgb: Int? = null, moveTarget: String? = null): ControlCommand {
        require(action in sessionActionStatuses)
        require(if (action == "set_session_color") rgb != null && rgb in 0..0xFFFFFF else rgb == null) { "Use an integer RGB color without alpha." }
        require(action == "move_session" || moveTarget == null)
        check(sessionActionUnavailable(action, now, moveTarget) == null) { sessionActionUnavailable(action, now, moveTarget)!! }
        val s = session!!
        val a = s.sessionActions!!
        return ControlCommand(UUID.randomUUID().toString(), s.id, s.ticket, action, "", "",
            sessionTicket = a.ticket, rgb = rgb, targetID = moveTarget, sourceID = a.sourceID,
            resumeAuthority = a.resumeAuthority,
            targetTicket = snapshot?.sessions?.singleOrNull { it.id == moveTarget }?.sessionActions?.ticket)
    }
    fun acknowledgeSessionAction(command: ControlCommand, result: JSONObject): WorkspaceState {
        require(command.isSessionAction && sessionDeliveries[command.sessionActionKey]?.command?.id == command.id)
        require(result.opt("sessionID") == command.sessionID && result.opt("action") == command.action &&
            result.opt("sessionTicket") == command.sessionTicket) { "Session acknowledgment does not match the original action." }
        val ok = result.opt("ok")
        val status = result.opt("status")
        val detail = result.opt("detail") ?: ""
        require(ok is Boolean && detail is String && detail.length <= 4000)
        require(if (ok) status == sessionActionStatuses[command.action] &&
            result.opt("commandID") is String && result.getString("commandID").length in 1..128
            else status in setOf("failed", "unknown")) { "The Mac did not confirm this exact session action." }
        val message = if (ok && command.action == "resume_session")
            "Launched on Mac; connection is not confirmed by this receipt. $detail"
            else "$status: ${detail.ifBlank { result.optString("error", "Check the Mac.") }}"
        // Results are receipts only; a fresh native snapshot owns color, order, removal and connection state.
        return copy(sessionDeliveries = sessionDeliveries + (command.sessionActionKey to SessionActionDelivery(
            command, message, status == "unknown" || ok && command.action == "resume_session")))
    }
    fun editCreation(value: SubagentCreation): WorkspaceState {
        val id = checkNotNull(session?.id) { "Select a session first." }
        require(value.withinLimits()) { "Name: 160, instructions: 16,000, type: 128 and model: 200 UTF-16 units maximum." }
        val updated = creationDrafts + (id to CreationDraft(value, creationDraft.revision + 1))
        require(updated.size <= 100 && updated.values.sumOf { it.value.json().toString().toByteArray().size } <= 524_288) {
            "Subagent form storage is full. Clear an unused form."
        }
        return copy(creationDrafts = updated)
    }
    fun managementUnavailable(action: String, now: Long, helperID: String = ""): String? {
        if (receivedAt == null || now < receivedAt || now - receivedAt >= 10_000 ||
            snapshot?.connected != true || session?.connected != true)
            return "Reconnect and wait for live management support from this session’s Mac."
        val s = session!!
        val supported = when (action) {
            "create_subagent" -> s.capabilities.subagentCreate && s.subagentTypes.isNotEmpty()
            "remove_subagent" -> s.capabilities.subagentRemove
            "stop_idle_subagents" -> s.capabilities.subagentCleanup
            else -> false
        }
        if (s.agent != "copilot" || !supported || s.managementTicket.isNullOrEmpty())
            return "Unavailable on this Mac/runtime. Update Notify and its helper; Claude does not support helper management."
        managementDeliveries.entries.firstOrNull { it.key.sessionID == s.id && it.value.blocked }?.let { return it.value.status }
        if (managementDeliveries.size >= 256 && Target(s.id, s.ticket, helperID) !in managementDeliveries)
            return "Management result storage is full. Check the Mac before starting a new connection."
        if (action != "create_subagent") {
            if (detail?.teamAvailable != true) return "Wait for a live team snapshot for this exact session."
            if (detail!!.teamError.isNotEmpty()) return detail!!.teamError
        }
        if (action == "remove_subagent" &&
            detail!!.agents.none { it.id == helperID && it.status in setOf("completed", "failed", "cancelled") })
            return "Only a currently known ended helper can be removed. Stop is a separate action."
        return null
    }
    fun creationUnavailable(): String? {
        val session = session ?: return "Select a current session before creating a helper."
        val value = creationDraft.value
        if (!value.valid()) return "Enter a name, instructions and a live agent type within the displayed limits."
        if (value.agentType !in session.subagentTypes) return "This agent type is no longer advertised. Select a live type."
        if (value.model != null && value.model !in session.subagentModels)
            return "This model is no longer advertised. Review the model choice; no default was substituted."
        return null
    }
    fun prepareManagement(action: String, now: Long, helperID: String = ""): ControlCommand {
        require(action in setOf("create_subagent", "remove_subagent", "stop_idle_subagents"))
        require(if (action == "remove_subagent") helperID.isNotEmpty() else helperID.isEmpty())
        check(managementUnavailable(action, now, helperID) == null) { managementUnavailable(action, now, helperID)!! }
        if (action == "create_subagent") check(creationUnavailable() == null) { creationUnavailable()!! }
        return ControlCommand(UUID.randomUUID().toString(), sessionID!!, session!!.ticket, action, "", helperID,
            managementTicket = session!!.managementTicket,
            creation = if (action == "create_subagent") creationDraft.value else null,
            creationRevision = if (action == "create_subagent") creationDraft.revision else null)
    }
    fun acknowledgeManagement(command: ControlCommand, result: JSONObject): WorkspaceState {
        require(command.isManagement && managementDeliveries[command.target]?.commandID == command.id) {
            "Acknowledgment does not match this management action."
        }
        val ok = result.opt("ok")
        val status = result.opt("status")
        val expected = when (command.action) {
            "create_subagent" -> "created"
            "remove_subagent" -> "removed"
            else -> "cleaned"
        }
        val receipt = result.opt("commandID")
        require(ok is Boolean && status is String && (if (ok) status == expected && receipt is String && receipt.isNotBlank() && receipt.length <= 128
            else status == "failed" || status == "unknown")) { "The Mac did not confirm this exact management action." }
        val detail = result.opt("detail") ?: ""
        val error = result.opt("error") ?: ""
        require(detail is String && detail.length <= 4000 && error is String && error.length <= 1000)
        val currentDraft = creationDrafts[command.sessionID]
        return copy(managementDeliveries = managementDeliveries + (command.target to ControlDelivery(command.id,
            listOf(status, detail, error).filter { it.isNotBlank() }.joinToString(": "), status == "unknown",
            receiptID = receipt as? String)),
            creationDrafts = if (ok && command.action == "create_subagent" && currentDraft?.revision == command.creationRevision &&
                currentDraft?.value == command.creation) creationDrafts - command.sessionID else creationDrafts)
    }
}
