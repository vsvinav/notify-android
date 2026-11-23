package app.notify.android.controller

enum class PanelTone { Ok, Warn, Red, Info, Idle }
enum class PanelActionStyle { Primary, Danger, Default }

data class PanelAction(val id: String, val label: String, val style: PanelActionStyle = PanelActionStyle.Default)
data class PanelItem(val id: String, val title: String, val subtitle: String? = null, val tone: PanelTone = PanelTone.Idle,
    val url: String? = null, val actions: List<PanelAction> = emptyList(), val log: PanelLog? = null)
data class PanelSection(val title: String, val items: List<PanelItem>, val attention: Boolean = false)
data class PanelTab(val id: String, val label: String, val badge: Int?, val sections: List<PanelSection>,
    val form: PanelForm? = null, val track: PanelTrack? = null)
data class PanelFeed(val title: String, val updatedAt: Long?, val connected: Boolean, val busy: Boolean, val tabs: List<PanelTab>)

enum class PanelStageKind { Normal, Gate, Decision }
data class PanelStage(val id: String, val label: String, val hint: String?, val kind: PanelStageKind)
data class PanelPlacement(val itemId: String, val stageId: String)
data class PanelTrack(val title: String, val stages: List<PanelStage>, val placements: List<PanelPlacement>)
data class PanelLogEntry(val at: String?, val text: String)
data class PanelLog(val entries: List<PanelLogEntry>, val truncated: Boolean)
data class PanelForm(val actionId: String, val title: String, val submitLabel: String, val placeholder: String?,
    val minLength: Int, val maxLength: Int) {
    fun error(text: String): String? = when {
        text.isBlank() -> "Enter some text before reviewing."
        text.length !in minLength..maxLength -> "Use $minLength–$maxLength UTF-16 units."
        text.any { it in '\u0000'..'\u0008' || it in '\u000b'..'\u000c' ||
            it in '\u000e'..'\u001f' || it in '\u007f'..'\u009f' } -> "Remove unsupported control characters."
        else -> null
    }
}

class PanelPreference(read: () -> Boolean?, private val write: (Boolean) -> Boolean) {
    var enabled = read() == true
        private set
    fun set(value: Boolean) {
        check(write(value)) { "Dashboard setting could not be saved. Check device storage." }
        enabled = value
    }
}

// One configured provider slot per paired Mac; display titles never identify a draft.
data class PanelDraftKey(val scope: ConversationScope, val tabId: String, val actionId: String)
data class PanelDraft(val text: String, val revision: Long)
data class PanelSubmission(val key: PanelDraftKey, val text: String, val revision: Long)
data class PanelReply(val available: Boolean, val feed: PanelFeed? = null, val reason: String = "")
data class PanelConfirm(val action: PanelAction, val label: String, val prompt: String, val confirmationToken: String,
    val submission: PanelSubmission? = null)
data class PanelPending(val id: String, val actionID: String, val sending: Boolean, val submission: PanelSubmission? = null)
data class PanelResult(val ok: Boolean, val sent: Boolean, val demo: Boolean, val wasBusy: Boolean, val error: String)

data class PanelState(
    val fresh: Boolean = false,
    val available: Boolean = false, val feed: PanelFeed? = null, val reason: String = "", val error: String? = null,
    val pending: PanelPending? = null, val confirm: PanelConfirm? = null, val message: String? = null,
    val drafts: Map<PanelDraftKey, PanelDraft> = emptyMap(), val draftRevision: Long = 0,
) {
    fun edit(key: PanelDraftKey, text: String): PanelState {
        if (drafts[key]?.text == text) return this
        val revision = draftRevision + 1
        return copy(drafts = drafts + (key to PanelDraft(text, revision)), draftRevision = revision,
            confirm = confirm?.takeUnless { it.submission?.key == key },
            pending = pending?.takeUnless { !it.sending && it.submission?.key == key })
    }
    fun submission(key: PanelDraftKey) = drafts[key]?.let { PanelSubmission(key, it.text, it.revision) }
    fun disable() = copy(fresh = false, confirm = null, pending = pending?.takeIf { it.sending })
    fun retire() = copy(fresh = false, pending = null, confirm = null, message = if (pending?.sending == true)
        "Delivery is uncertain. Check the destination session. Nothing was resent." else message)
    fun finish(id: String, confirm: PanelConfirm? = null, message: String? = null): PanelState =
        if (pending?.id != id) this else copy(pending = null, confirm = confirm, message = message)
    fun receive(reply: PanelReply) = if (reply.available && reply.feed != null)
        copy(available = true, fresh = true, feed = reply.feed, reason = "", error = null)
        else copy(available = false, fresh = false, feed = null, reason = reply.reason, error = null, confirm = null,
            pending = pending?.takeIf { it.sending })
    fun complete(id: String, value: PanelResult): PanelState {
        val request = pending?.takeIf { it.id == id && it.sending } ?: return this
        val submitted = request.submission
        val clear = value.ok && value.sent && !value.demo && submitted != null &&
            drafts[submitted.key] == PanelDraft(submitted.text, submitted.revision)
        return finish(id, message = result(value).message).copy(drafts = if (clear) drafts - submitted!!.key else drafts)
    }
    fun result(value: PanelResult) = copy(message = when {
        value.demo -> "Demo mode: nothing sent"
        value.ok && value.sent -> "Sent to Copilot"
        else -> value.error.ifBlank { "Not sent. Check the dashboard on your Mac." }
    })
}

// Visible dashboards refresh every 15 s; an unavailable or unknown panel is re-probed every 60 s.
fun panelPollDelay(available: Boolean, visible: Boolean): Long? = when {
    !available -> 60_000L
    visible -> 15_000L
    else -> null
}

fun panelAge(updatedAt: Long?, now: Long): String {
    if (updatedAt == null) return "Update time unknown"
    val minutes = (now - updatedAt).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated $minutes min ago"
        minutes < 48 * 60 -> "Updated ${minutes / 60} h ago"
        else -> "Updated ${minutes / (24 * 60)} d ago"
    }
}
