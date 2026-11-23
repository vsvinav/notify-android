package app.notify.android.controller

import app.notify.android.remote.*
import java.net.URI
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class RequestSnapshot(val connected: Boolean, val requests: List<RemoteRequest>, val attention: AttentionProjection? = null)

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
        return RequestSnapshot(value.bool("connected"), requests, attention(value.opt("attention") as? JSONObject))
    }
    private fun attention(value: JSONObject?): AttentionProjection? = runCatching {
        require(value != null && value.opt("version") == 1)
        val epoch = value.opt("epoch") as? String
        require(epoch != null && epoch.matches(Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")))
        val revision = value.opt("revision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() in 1..9_007_199_254_740_991L)
        val count = value.opt("additionalCount")
        require(count is Int && count in 0..256 && value.has("current"))
        fun text(c: JSONObject, key: String, max: Int, nonempty: Boolean = true): String {
            val text = c.opt(key)
            require(text is String && text.length <= max && (!nonempty || text.isNotEmpty()) && '\u0000' !in text)
            return text
        }
        val current = if (value.isNull("current")) {
            require(count == 0)
            null
        } else value.getJSONObject("current").let {
            AttentionItem(text(it, "sessionID", 128), text(it, "requestID", 128),
                text(it, "sessionName", 256, false), text(it, "requestTitle", 300))
        }
        AttentionProjection(epoch, (revision as Number).toLong(), current, count)
    }.getOrNull()
    fun workspace(value: JSONObject): WorkspaceSnapshot {
        bounded(value)
        val policyControls = value.bool("dangerousAutoYesControl", false) && value.bool("dangerousAutoYesOffEpoch", false)
        val managementControls = value.bool("subagentManagementControl", false)
        val sessionControls = value.bool("sessionActionsControl", false)
        val immediateControls = value.bool("steeringImmediateControl", false)
        val sessions = value.objects("sessions", 256).map { s ->
            val c = s.getJSONObject("capabilities")
            val policy = if (!s.has("dangerousAutoYes")) null else policy(s.getJSONObject("dangerousAutoYes"))
            val capable = c.bool("dangerousAutoYes", false)
            require(!capable || policy != null) { "Missing Mac policy state." }
            val managementTicket = if (s.has("managementTicket")) s.text("managementTicket", 256, true) else null
            val types = s.catalog("subagentTypes", 100, 128)
            val models = s.catalog("subagentModels", 200, 200)
            val create = c.bool("subagentCreate", false)
            val remove = c.bool("subagentRemove", false)
            val cleanup = c.bool("subagentCleanup", false)
            require(!(create || remove || cleanup) || managementTicket != null) { "Missing management authority." }
            require(!create || types.isNotEmpty() && s.has("subagentModels")) { "Missing live creation catalog." }
            Session(s.text("id", 128, true), s.text("name", 2048), s.text("project", 2048), s.text("agent", 256),
                s.text("state", 256), s.bool("connected"), s.text("ticket", 256),
                Capabilities(c.bool("steering"), c.bool("subagentChat"), c.bool("subagentStop"), capable && policyControls && policy?.hasOffAuthority == true,
                    create && managementControls, remove && managementControls, cleanup && managementControls,
                    c.bool("steeringImmediate", false) && immediateControls),
                s.reportedCount("runningCount"), s.reportedCount("idleCount"), policy, managementTicket, types, models,
                if (s.has("color")) s.text("color", 7).also { require(it.matches(Regex("#[a-fA-F0-9]{6}"))) }.drop(1).toInt(16) else null,
                if (s.has("sessionActions")) sessionActions(s.getJSONObject("sessionActions"), sessionControls) else null)
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
                d.bool("truncated", false), team?.optionalText("error") ?: "", team != null)
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
    private fun rgb(value: JSONObject): Int {
        val rgb = value.opt("rgb")
        require(rgb is Int && rgb in 0..0xFFFFFF) { "Invalid Mac RGB color. Alpha and coercion are not supported." }
        return rgb
    }
    private fun sessionActions(a: JSONObject, supported: Boolean): SessionActions {
        val group = a.text("group", 32, true)
        require(group in setOf("saved", "needs_you", "monitoring", "not_connected")) { "Invalid Mac session group." }
        val status = a.text("resumeStatus", 32, true)
        require(status in setOf("available", "unavailable", "launching", "awaiting_connection", "connected",
            "owner_detected", "unconfirmed", "failed", "unknown")) { "Invalid Mac launch state." }
        val colors = a.objects("colors", 20).map { SessionColor(it.text("name", 64, true), rgb(it)) }
        require(colors.map { it.rgb }.distinct().size == colors.size) { "Ambiguous Mac color catalog." }
        return SessionActions(a.text("ticket", 256, true), a.text("sourceID", 128, true), group, rgb(a), a.bool("overridden"),
            colors, a.bool("editable"), a.bool("removable"), a.bool("resumable"), status, a.text("resumeDetail", 4000),
            if (!a.has("moveUp") || a.isNull("moveUp")) null else a.text("moveUp", 128, true),
            if (!a.has("moveDown") || a.isNull("moveDown")) null else a.text("moveDown", 128, true), supported,
            if (!a.has("resumeAuthority") || a.isNull("resumeAuthority")) null else resumeAuthority(a.getJSONObject("resumeAuthority")))
    }
    private fun resumeAuthority(value: JSONObject): ResumeAuthority {
        val retired = value.opt("retired")
        require(retired is Int || retired is Long) { "Invalid native resume retirement generation." }
        val generation = (retired as Number).toLong()
        require(generation in 0..9_007_199_254_740_991L) { "Invalid native resume retirement generation." }
        return ResumeAuthority(value.text("epoch", 256, true), generation)
    }
    private fun JSONObject.reportedCount(name: String): Int? {
        if (!has(name)) return null
        val count = opt(name)
        require(count is Int && count in 0..1_000_000) { "Invalid reported agent count." }
        return count
    }
    private fun JSONObject.catalog(name: String, max: Int, length: Int): List<String> {
        if (!has(name)) return emptyList()
        val array = getJSONArray(name)
        require(array.length() <= max) { "Live catalog exceeds its limit." }
        val values = (0 until array.length()).map {
            val entry = array.get(it)
            require(entry is String && entry.isNotBlank() && entry.length <= length && !entry.contains('\u0000')) { "Invalid live catalog ID." }
            entry
        }
        require(values.distinct().size == values.size) { "Ambiguous live catalog IDs." }
        return values
    }
    private val PANEL_TAB_ID = Regex("^[a-z][a-z0-9-]{0,31}$")
    private val PANEL_ACTION_ID = Regex("^[A-Za-z0-9:._-]{1,128}$")
    private val PANEL_CONFIRMATION = Regex("^[A-Za-z0-9_-]{43}$")
    private fun JSONObject.panelText(key: String, max: Int): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= max && '\u0000' !in it }
    private fun JSONObject.panelList(key: String, max: Int): List<JSONObject> {
        val array = opt(key) as? JSONArray ?: return emptyList()
        return (0 until minOf(array.length(), max)).mapNotNull { array.opt(it) as? JSONObject }
    }
    // Lenient by design: invalid entries are dropped so one bad row never hides the whole dashboard.
    fun panel(value: JSONObject): PanelReply = try {
        if (value.opt("available") != true) PanelReply(false, reason = value.panelText("reason", 200) ?: "Dashboard unavailable.")
        else {
            val feed = value.opt("panel") as? JSONObject
            if (feed == null || feed.toString().toByteArray().size > 262_144) PanelReply(false, reason = "Dashboard reply was invalid.")
            else PanelReply(true, panelFeed(feed))
        }
    } catch (_: Exception) { PanelReply(false, reason = "Dashboard reply was invalid.") }
    private fun panelFeed(feed: JSONObject): PanelFeed {
        val bridge = feed.opt("copilot") as? JSONObject
        val updatedAt = feed.panelText("updatedAt", 64)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val tabs = feed.panelList("tabs", 6).mapNotNull { tab ->
            val id = tab.panelText("id", 32)?.takeIf(PANEL_TAB_ID::matches) ?: return@mapNotNull null
            val label = tab.panelText("label", 24) ?: return@mapNotNull null
            val badge = (tab.opt("badge") as? Int)?.takeIf { it in 0..999 }
            val sections = tab.panelList("sections", 12).mapNotNull { section ->
                val title = runCatching { section.displayText("title", 60, empty = true) }.getOrNull() ?: return@mapNotNull null
                PanelSection(title, section.panelList("items", 60).mapNotNull(::panelItem).distinctBy { it.id },
                    section.opt("emphasis") == "attention")
            }
            PanelTab(id, label, badge, sections, panelForm(tab.opt("form")), panelTrack(tab.opt("track"), sections))
        }.distinctBy { it.id }
        return PanelFeed(feed.panelText("title", 60) ?: "Dashboard", updatedAt,
            bridge?.opt("connected") == true, bridge?.opt("busy") == true, tabs)
    }
    private fun panelItem(item: JSONObject): PanelItem? {
        val id = item.panelText("id", 64) ?: return null
        val title = item.panelText("title", 140) ?: return null
        val tone = PanelTone.entries.firstOrNull { it.name.lowercase() == item.opt("tone") } ?: PanelTone.Idle
        val url = item.panelText("url", 300)?.takeIf { it.startsWith("https://") && runCatching { URI(it).host }.getOrNull() != null }
        val actions = item.panelList("actions", 4).mapNotNull { action ->
            val actionID = action.panelText("id", 128)?.takeIf(PANEL_ACTION_ID::matches) ?: return@mapNotNull null
            val label = action.panelText("label", 24) ?: return@mapNotNull null
            PanelAction(actionID, label, PanelActionStyle.entries.firstOrNull { it.name.lowercase() == action.opt("style") }
                ?: PanelActionStyle.Default)
        }.distinctBy { it.id }
        return PanelItem(id, title, item.panelText("subtitle", 280), tone, url, actions, panelLog(item.opt("log")))
    }
    private fun JSONObject.displayText(key: String, max: Int, empty: Boolean = false): String {
        val text = opt(key)
        require(text is String && text.length <= max && (empty || text.isNotEmpty()) &&
            text.none { it <= '\u001f' || it == '\u007f' })
        return text
    }
    private fun panelForm(value: Any?): PanelForm? = runCatching {
        require(value is JSONObject && value.opt("version") == 1)
        val action = value.displayText("actionId", 128).also { require(PANEL_ACTION_ID.matches(it)) }
        val min = value.opt("minLength")
        val max = value.opt("maxLength")
        require(min is Int && max is Int && min in 1..300 && max in min..300)
        PanelForm(action, value.displayText("title", 60), value.displayText("submitLabel", 24),
            if (value.has("placeholder")) value.displayText("placeholder", 140, true) else null, min, max)
    }.getOrNull()
    private fun panelTrack(value: Any?, sections: List<PanelSection>): PanelTrack? = runCatching {
        require(value is JSONObject && value.opt("version") == 1)
        val rawStages = value.opt("stages")
        val rawPlacements = value.opt("placements")
        require(rawStages is JSONArray && rawStages.length() in 1..24)
        require(rawPlacements is JSONArray && rawPlacements.length() <= 60)
        val stages = (0 until rawStages.length()).map { index ->
            val stage = rawStages.getJSONObject(index)
            val id = stage.displayText("id", 32).also { require(PANEL_TAB_ID.matches(it)) }
            val kind = PanelStageKind.entries.single { it.name.lowercase() == stage.opt("kind") }
            PanelStage(id, stage.displayText("label", 40),
                if (stage.has("hint")) stage.displayText("hint", 80, true) else null, kind)
        }
        require(stages.map { it.id }.distinct().size == stages.size)
        val items = sections.flatMap { it.items }.groupingBy { it.id }.eachCount()
        val placements = (0 until rawPlacements.length()).map { index ->
            val placement = rawPlacements.getJSONObject(index)
            val item = placement.displayText("itemId", 64)
            val stage = placement.displayText("stageId", 32)
            require(items[item] == 1 && stages.any { it.id == stage })
            PanelPlacement(item, stage)
        }
        require(placements.map { it.itemId }.distinct().size == placements.size)
        PanelTrack(value.displayText("title", 60), stages, placements)
    }.getOrNull()
    private fun panelLog(value: Any?): PanelLog? {
        if (value !is JSONObject || value.opt("truncated") !is Boolean) return null
        val raw = value.opt("entries") as? JSONArray ?: return null
        var truncated = value.opt("truncated") == true || raw.length() > 8
        val entries = (0 until minOf(raw.length(), 8)).mapNotNull { index ->
            runCatching {
                val entry = raw.getJSONObject(index)
                PanelLogEntry(if (entry.has("at")) entry.displayText("at", 40, true) else null,
                    entry.displayText("text", 1000))
            }.getOrElse { truncated = true; null }
        }
        return PanelLog(entries, truncated)
    }
    fun panelPreview(action: PanelAction, value: JSONObject, submission: PanelSubmission? = null): PanelConfirm? {
        require(value.opt("ok") == true) { (value.opt("error") as? String)?.take(1000) ?: "The Mac did not prepare this action. Nothing was sent." }
        if (value.opt("demo") == true) return null
        val prompt = value.panelText("confirm", 1000) ?: throw IllegalArgumentException("The Mac did not return a prompt to confirm. Nothing was sent.")
        val token = value.panelText("confirmationToken", 43)?.takeIf(PANEL_CONFIRMATION::matches)
            ?: throw IllegalArgumentException("Update the Mac panel provider to support bound confirmations. Nothing was sent.")
        return PanelConfirm(action, value.panelText("label", 1000) ?: action.label, prompt, token, submission)
    }
    fun panelPreviewRequest(action: PanelAction, submission: PanelSubmission? = null) =
        JSONObject().put("actionId", action.id).put("preview", true).also {
            submission?.let { text -> it.put("text", text.text) }
        }
    fun panelAction(confirm: PanelConfirm): JSONObject = JSONObject().put("actionId", confirm.action.id)
        .put("preview", false).put("confirmationToken", confirm.confirmationToken).also {
            confirm.submission?.let { text -> it.put("text", text.text) }
        }
    fun panelResult(value: JSONObject) = PanelResult(value.opt("ok") == true, value.opt("sent") == true,
        value.opt("demo") == true, value.opt("wasBusy") == true, (value.opt("error") as? String)?.take(1000).orEmpty())
    fun reply(command: ReplyCommand): JSONObject = JSONObject().put("sessionID", command.sessionID)
        .put("requestID", command.requestID).put("ticket", command.ticket).put("decision", command.decision)
        .put("answers", JSONObject(command.answers)).also {
            command.permissionOptionID?.let { id -> it.put("permissionOptionID", id) }
            command.feedback?.let { text -> it.put("feedback", text) }
        }
}
