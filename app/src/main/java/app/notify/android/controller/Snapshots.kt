package app.notify.android.controller

import app.notify.android.remote.*
import org.json.JSONObject

data class RequestSnapshot(val connected: Boolean, val requests: List<RemoteRequest>)

object Snapshots {
    private fun bounded(value: JSONObject) {
        require(value.toString().toByteArray().size <= 1_064_960) { "The Mac snapshot is too large." }
    }
    fun requests(value: JSONObject): RequestSnapshot {
        bounded(value)
        val requests = value.objects("requests", 100).map { item ->
            val p = item.getJSONObject("request")
            val kind = p.text("kind", 32)
            require(kind in setOf("question", "permission")) { "Unsupported request type. Review it on your Mac." }
            val questions = p.objects("questions", 100, optional = kind != "question").map { q ->
                val minimum = if (q.has("minimumSelections")) q.opt("minimumSelections") else 1
                require(minimum is Int && minimum >= 0 && minimum <= 100) { "Invalid minimum selections." }
                val options = q.objects("options", 100).map { QuestionOption(it.text("label"), it.optionalText("description")) }
                require(options.map { it.label }.distinct().size == options.size) { "Ambiguous question options." }
                Question(q.text("id", 256, true), q.text("text"), q.bool("multiple"), q.bool("allowFreeform"),
                    options, q.bool("required", true), minimum)
            }
            require(questions.map { it.id }.distinct().size == questions.size && (kind != "question" || questions.isNotEmpty())) {
                "Ambiguous or missing request questions."
            }
            val permissions = p.objects("permissionOptions", 100, true).map {
                PermissionOption(it.text("id", 256, true), it.text("label"), it.optionalText("detail"))
            }
            require(permissions.map { it.id }.distinct().size == permissions.size) { "Ambiguous permission scopes." }
            RemoteRequest(item.text("ticket", 256, true), item.text("sessionID", 256, true), item.text("sessionName", 256),
                Prompt(p.text("id", 256, true), kind, p.text("title"), p.optionalText("detail"),
                    questions, permissions, p.bool("supportsFeedback", false)),
                item.optionalText("agent", 256), item.optionalText("project", 2048),
                item.objects("fields", 256).map { ContextField(it.text("label"), it.text("value"),
                    it.optionalText("presentation", 32), it.optionalText("target", 2048)) }, item.bool("sending", false))
        }
        require(requests.map { it.ticket }.distinct().size == requests.size) { "Ambiguous request tickets." }
        return RequestSnapshot(value.bool("connected"), requests)
    }
    fun workspace(value: JSONObject): WorkspaceSnapshot {
        bounded(value)
        val policyControls = value.bool("dangerousAutoYesControl", false) && value.bool("dangerousAutoYesOffEpoch", false)
        val sessions = value.objects("sessions", 256).map { s ->
            val c = s.getJSONObject("capabilities")
            val policy = if (!s.has("dangerousAutoYes")) null else policy(s.getJSONObject("dangerousAutoYes"))
            val capable = c.bool("dangerousAutoYes", false)
            require(!capable || policy != null) { "Missing Mac policy state." }
            Session(s.text("id", 128, true), s.text("name", 2048), s.text("project", 2048), s.text("agent", 256),
                s.text("state", 256), s.bool("connected"), s.text("ticket", 256),
                Capabilities(c.bool("steering"), c.bool("subagentChat"), c.bool("subagentStop"), capable && policyControls && policy?.hasOffAuthority == true),
                s.reportedCount("runningCount"), s.reportedCount("idleCount"), policy)
        }
        require(sessions.map { it.id }.distinct().size == sessions.size) { "Ambiguous session identifiers." }
        val detail = if (!value.has("detail") || value.isNull("detail")) null else {
            val d = value.getJSONObject("detail")
            val team = if (!d.has("team") || d.isNull("team")) null else d.getJSONObject("team")
            val agents = team?.objects("agents", 100)?.map {
                val id = it.text("id", 128, true)
                val known = if (!it.has("parentKnown") || it.isNull("parentKnown")) false else it.bool("parentKnown")
                val parent = if (!it.has("parentAgentID") || it.isNull("parentAgentID")) null else it.text("parentAgentID", 128, true)
                require(parent == null || known && parent != id) { "Invalid reported agent parent." }
                val status = it.text("status", 64)
                require(status in setOf("running", "idle", "completed", "failed", "cancelled")) { "Invalid reported agent status." }
                Agent(id, it.text("name", 2048), status, it.optionalText("task", 1000),
                    it.optionalText("model", 200), known, parent)
            } ?: emptyList()
            require(agents.map { it.id }.distinct().size == agents.size) { "Ambiguous subagent identifiers." }
            val activity = d.objects("activity", 300).map { Activity(it.text("id", 256, true), it.text("kind", 64),
                it.text("title", 300), it.text("detail", 65_536), it.text("timestamp", 128), it.optionalText("agentID", 128), it.optionalText("clientTurnID", 256)) }
            val controls = d.objects("controls", 100).map { ControlReceipt(it.text("id", 256, true), it.text("action", 64),
                it.text("message"), it.text("status", 64), it.text("detail"), it.text("createdAt", 128), it.optionalText("agentID", 128)) }
            require(activity.map { it.id }.distinct().size == activity.size) { "Ambiguous activity identifiers." }
            SessionDetail(d.text("sessionID", 128, true), d.text("ticket", 256, true), agents, activity, controls,
                d.bool("truncated", false), team?.optionalText("error") ?: "")
        }
        return WorkspaceSnapshot(value.bool("connected"), sessions, detail, value.bool("truncated", false), value.optionalText("error"))
    }
    fun policy(value: JSONObject): DangerousAutoYes {
        val revision = value.opt("revision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() in 1..9_007_199_254_740_991L) {
            "Invalid Mac policy revision."
        }
        val policy = DangerousAutoYes(value.text("ticket", 256, true), (revision as Number).toLong(),
            value.bool("enabled"), value.bool("normalEnabled"), if (value.has("offTicket")) value.text("offTicket", 256, true) else null)
        require(!(policy.enabled && policy.normalEnabled)) { "Conflicting Mac Auto-yes modes." }
        return policy
    }
    private fun JSONObject.reportedCount(name: String): Int? {
        if (!has(name)) return null
        val count = opt(name)
        require(count is Int && count in 0..1_000_000) { "Invalid reported agent count." }
        return count
    }
    fun reply(command: ReplyCommand): JSONObject = JSONObject().put("sessionID", command.sessionID)
        .put("requestID", command.requestID).put("ticket", command.ticket).put("decision", command.decision)
        .put("answers", JSONObject(command.answers)).also {
            command.permissionOptionID?.let { id -> it.put("permissionOptionID", id) }
            command.feedback?.let { text -> it.put("feedback", text) }
        }
}
