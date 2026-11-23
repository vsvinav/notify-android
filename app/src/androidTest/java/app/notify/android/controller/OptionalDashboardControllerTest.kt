@file:Suppress("DEPRECATION")

package app.notify.android.controller

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import app.notify.android.MainActivity
import app.notify.android.NotifyApplication
import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.RequestInbox
import kotlinx.coroutines.flow.MutableStateFlow

/** Compiled acceptance fixture only. No account, socket, provider or dispatch is created. */
class OptionalDashboardControllerTest : InstrumentationTestCase() {
    fun testOffRetiresRealControllerPreviewAndCancelsDiscoveryWithoutDisconnecting() = fixture { controller, state ->
        val before = state.value
        val action = PanelAction("fictional", "Review")
        state.value = before.copy(panel = before.panel.copy(
            pending = PanelPending("preview", action.id, false),
            confirm = PanelConfirm(action, "Review", "Fictional instruction", "F".repeat(43))))
        controller.customDashboard(false)
        assertFalse(state.value.dashboardEnabled)
        assertNull(state.value.panel.pending)
        assertNull(state.value.panel.confirm)
        assertEquals(before.relationship, state.value.relationship)
        assertEquals(before.account, state.value.account)
        assertEquals(before.status, state.value.status)
        assertEquals(before.notifications, state.value.notifications)
        assertEquals(before.backgroundRunning, state.value.backgroundRunning)
        assertNull(ControllerViewModel::class.java.getDeclaredField("panelPolling").apply { isAccessible = true }.get(controller))
        controller.previewPanelAction(action)
        assertNull(state.value.panel.pending)
        assertTrue(state.value.panel.message!!.contains("Nothing was sent"))
    }

    fun testOffDuringPendingSendThenConnectionRetirementPreservesUnknownAndDraft() = fixture { controller, state ->
        val key = state.value.panelDraftKey("notes", "capture")!!
        controller.editPanelDraft(key.tabId, key.actionId, "  unsent or uncertain 🪴  ")
        val draft = state.value.panel.submission(key)!!
        state.value = state.value.copy(panel = state.value.panel.copy(
            pending = PanelPending("dispatched", key.actionId, true, draft)))
        controller.customDashboard(false)
        assertEquals("dispatched", state.value.panel.pending?.id)
        controller.sendPanelAction()
        assertEquals("dispatched", state.value.panel.pending?.id)
        controller.disconnect()
        assertNull(state.value.panel.pending)
        assertTrue(state.value.panel.message!!.contains("uncertain"))
        assertEquals(draft.text, state.value.panel.drafts[key]?.text)
        assertEquals(state.value.panel, state.value.panel.complete("dispatched", PanelResult(true, true, false, false, "")))
    }

    fun testControllerRejectsInvalidFormTextLocallyAndKeepsChatDraftSeparate() = fixture { controller, state ->
        val form = PanelForm("capture", "Add a note", "Review note", null, 2, 300)
        state.value = state.value.copy(panel = state.value.panel.copy(feed = PanelFeed("Fictional", null, true, false,
            listOf(PanelTab("notes", "Notes", null, emptyList(), form)))))
        val chat = state.value.workspace
        controller.editPanelDraft("notes", form.actionId, " \t ")
        controller.previewPanelForm("notes")
        assertNull(state.value.panel.pending)
        assertEquals("Enter some text before reviewing.", state.value.panel.message)
        assertEquals(" \t ", state.value.panel.drafts[state.value.panelDraftKey("notes", form.actionId)]?.text)
        assertEquals(chat, state.value.workspace)
    }

    private fun fixture(block: (ControllerViewModel, MutableStateFlow<ControllerState>) -> Unit) {
        val context = instrumentation.targetContext
        assertEquals("app.notify.android.layoutcheck", context.packageName)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val controller = (context.applicationContext as NotifyApplication).controller
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (controller.state.value.busy && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertFalse(controller.state.value.busy)
        val original = controller.state.value
        assertNull("Only fresh isolated fixture data is allowed", original.account)
        assertNull(original.relationship)
        val preferences = context.getSharedPreferences("notify-dashboard-v1", 0)
        val saved = if (preferences.contains("enabled")) preferences.getBoolean("enabled", false) else null
        @Suppress("UNCHECKED_CAST")
        val mutable = ControllerViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<ControllerState>
        instrumentation.runOnMainSync {
            try {
                val account = Account("fictional", "owner")
                val now = SystemClock.elapsedRealtime()
                mutable.value = original.copy(account = account,
                    relationship = Relationship(account, "fixture-mac", "pin", "fixture-phone", "public", "Fixture", true),
                    dashboardEnabled = true, foreground = true, now = now, refreshingVisibleState = false,
                    inbox = RequestInbox().receive(0, true, emptyList(), now),
                    status = RemoteStatus("connected", "Synthetic; no transport"), panel = PanelState(fresh = true))
                block(controller, mutable)
            } finally {
                controller.customDashboard(false)
                mutable.value = original
                controller.customDashboard(original.dashboardEnabled)
                preferences.edit().apply { if (saved == null) remove("enabled") else putBoolean("enabled", saved) }.commit()
                activity.finish()
            }
        }
    }
}
