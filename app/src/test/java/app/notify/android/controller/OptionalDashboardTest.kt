package app.notify.android.controller

import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.RequestInbox
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class OptionalDashboardTest {
    private val account = Account("synthetic", "owner")
    private val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
    private val live = ControllerState(account = account, relationship = pairing, foreground = true,
        status = RemoteStatus("connected", ""), now = 100,
        inbox = RequestInbox().receive(0, true, emptyList(), 100), panel = PanelState(fresh = true))

    @Test fun firstInstallAndUpgradeWithoutConsentCannotDispatchEvenWithFreshFeed() {
        assertTrue(live.live)
        assertFalse("A fresh provider feed must not opt the phone in", live.panelActionsEnabled)
    }

    @Test fun consentPersistsBothExplicitChoicesAndFailedWritesDoNotEnable() {
        var stored: Boolean? = null
        fun load() = PanelPreference({ stored }, { stored = it; true })
        assertFalse(load().enabled)
        load().set(true)
        assertTrue(load().enabled)
        load().set(false)
        assertFalse(load().enabled)
        val failed = PanelPreference({ null }, { false })
        assertThrows(IllegalStateException::class.java) { failed.set(true) }
        assertFalse(failed.enabled)
    }

    @Test fun offStopsDiscoveryWithoutTouchingConnectionAlertsOrAccount() {
        assertNull(live.panelReadDelay(false))
        val enabled = live.dashboardSetting(true)
        assertTrue(enabled.panelActionsEnabled)
        assertEquals(60_000L, enabled.panelReadDelay(false))
        val available = enabled.copy(panel = enabled.panel.copy(available = true))
        assertNull(available.panelReadDelay(false))
        assertEquals(15_000L, available.panelReadDelay(true))
        assertNull(available.copy(foreground = false, backgroundRunning = true).panelReadDelay(true))
        val off = available.dashboardSetting(false)
        assertNull(off.panelReadDelay(true))
        assertEquals(available.status, off.status)
        assertEquals(available.notifications, off.notifications)
        assertEquals(account, off.account)
        assertEquals(pairing, off.relationship)
        assertTrue(enabled.endAccountSession().dashboardEnabled)
        assertFalse(live.endAccountSession().dashboardEnabled)
    }

    private val form = PanelForm("capture", "Add a note", "Preview", "Your note", 1, 300)
    private val key = PanelDraftKey(ConversationScope.from(pairing), "notes", form.actionId)
    private val raw = "  \"Café 🪴\"\t\nexact  "
    private val action = PanelAction(form.actionId, form.submitLabel)
    private val reply = JSONObject().put("ok", true).put("confirm", "Provider's exact instruction\n  ")
        .put("confirmationToken", "P".repeat(43))

    @Test fun textIsValidatedWithoutNormalizationAndBoundToPreviewAndSend() {
        assertNull(form.error(raw))
        for (invalid in listOf("", " \n\t", "x".repeat(301), "a\u0000b", "a\u0085b", "a\u007fb"))
            assertNotNull(form.error(invalid))
        assertNull(form.error("🪴".repeat(150)))
        assertNotNull(form.error("🪴".repeat(151)))
        assertNotNull(form.copy(minLength = 4).error("abc"))
        val draft = PanelState().edit(key, raw)
        val submission = draft.submission(key)!!
        val confirm = Snapshots.panelPreview(action, reply, submission)!!
        assertEquals(raw, confirm.submission!!.text)
        assertEquals("Provider's exact instruction\n  ", confirm.prompt)
        assertEquals(raw, Snapshots.panelAction(confirm).getString("text"))
        assertEquals(raw, Snapshots.panelPreviewRequest(action, submission).getString("text"))
        assertFalse(Snapshots.panelPreviewRequest(action).has("text"))
    }

    @Test fun draftsAreScopedToExactPairingTabAndActionNotFeedTitle() {
        val draft = PanelState().edit(key, raw)
        assertEquals(raw, draft.drafts[key]?.text)
        for (other in listOf(key.copy(tabId = "other"), key.copy(actionId = "other"),
            key.copy(scope = key.scope.copy(macID = "another")),
            key.copy(scope = key.scope.copy(account = Account("synthetic", "another"))),
            key.copy(scope = key.scope.copy(deviceID = "repaired")))) assertNull(draft.submission(other))
        assertEquals(raw, draft.retire().drafts[key]?.text)
        assertTrue(live.copy(panel = draft).endAccountSession().panel.drafts.isEmpty())
    }

    private fun pending(): PanelState {
        val draft = PanelState().edit(key, raw)
        return draft.copy(pending = PanelPending("send", action.id, true, draft.submission(key)))
    }
    private val sent = PanelResult(true, true, false, false, "")

    @Test fun onlyExactConfirmedSentClearsAnUnchangedDraft() {
        assertTrue(pending().complete("send", sent).drafts.isEmpty())
        val edited = pending().edit(key, "new")
        assertEquals("new", edited.complete("send", sent).drafts[key]?.text)
        val editedBack = edited.edit(key, raw)
        assertEquals(raw, editedBack.complete("send", sent).drafts[key]?.text)
        assertEquals(raw, pending().complete("stale", sent).drafts[key]?.text)
        for (result in listOf(sent.copy(demo = true), sent.copy(sent = false), sent.copy(ok = false)))
            assertEquals(raw, pending().complete("send", result).drafts[key]?.text)
        assertEquals(raw, pending().finish("send", message = "Delivery is uncertain").drafts[key]?.text)
    }

    @Test fun offRetiresPreviewButKeepsCorrelatedDispatchedOutcomeWithoutReplay() {
        val sending = live.copy(dashboardEnabled = true, panel = pending())
        val off = sending.dashboardSetting(false)
        assertFalse(off.panelActionsEnabled)
        assertEquals("send", off.panel.pending?.id)
        assertEquals("Sent to Copilot", off.panel.complete("send", sent).message)
        assertTrue(off.panel.complete("send", sent).drafts.isEmpty())
        val unknown = off.retireConnection().panel
        assertNull(unknown.pending)
        assertTrue(unknown.message!!.contains("uncertain"))
        assertEquals(raw, unknown.drafts[key]?.text)
        assertEquals(unknown, unknown.complete("send", sent))
        val preview = sending.copy(panel = pending().copy(pending = PanelPending("preview", action.id, false),
            confirm = Snapshots.panelPreview(action, reply)))
        val cancelled = preview.dashboardSetting(false).panel
        assertNull(cancelled.pending)
        assertNull(cancelled.confirm)
        assertEquals(cancelled, cancelled.finish("preview", Snapshots.panelPreview(action, reply)))
        assertEquals(raw, cancelled.drafts[key]?.text)
    }

    @Test fun queuedDispatchCannotStartAfterOffEvenIfEnabledAgain() {
        val queued = live.dashboardSetting(true).copy(panel = pending().copy(fresh = true))
        val consent = queued.dashboardGeneration
        assertTrue(queued.panelMayDispatch("send", consent))
        assertFalse(queued.panelMayDispatch("other-request", consent))
        assertFalse(queued.dashboardSetting(false).panelMayDispatch("send", consent))
        val enabledAgain = queued.dashboardSetting(false).dashboardSetting(true).let { it.copy(panel = it.panel.copy(fresh = true)) }
        assertFalse(enabledAgain.panelMayDispatch("send", consent))
        assertFalse(queued.visibility(false, 101).panelMayDispatch("send", consent))
    }

    @Test fun editedDraftRetiresItsPreviewAndRejectsLateConfirmationButKeepsOtherTabs() {
        val otherKey = key.copy(tabId = "other")
        val draft = PanelState().edit(key, raw).edit(otherKey, "Other note")
        val submission = draft.submission(key)!!
        val waiting = draft.copy(pending = PanelPending("preview", action.id, false, submission))
        val edited = waiting.edit(key, "Changed note")
        assertNull(edited.pending)
        assertEquals(edited, edited.finish("preview", Snapshots.panelPreview(action, reply, submission)))
        assertEquals("Other note", edited.drafts[otherKey]?.text)
        assertNull(draft.copy(confirm = Snapshots.panelPreview(action, reply, submission)).edit(key, "Edited").confirm)
    }

    @Test fun feedTitleAndProviderRefreshAreNotDraftIdentityOrConfirmationAuthority() {
        val draft = PanelState().edit(key, raw)
        val first = PanelFeed("One title", null, true, false, emptyList())
        val renamed = draft.receive(PanelReply(true, first)).receive(PanelReply(true, first.copy(title = "Other title")))
        assertEquals(raw, renamed.drafts[key]?.text)
        val retired = renamed.copy(confirm = Snapshots.panelPreview(action, reply, renamed.submission(key))).retire()
        assertNull(retired.confirm)
        assertEquals(raw, retired.receive(PanelReply(true, first)).drafts[key]?.text)
    }
}
