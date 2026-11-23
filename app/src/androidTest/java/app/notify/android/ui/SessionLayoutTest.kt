@file:Suppress("DEPRECATION")

package app.notify.android.ui

import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.WindowManager
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.notify.android.MainActivity
import app.notify.android.NotifyApplication
import app.notify.android.controller.*
import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.RequestInbox
import app.notify.android.notifications.NotificationSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class SessionLayoutTest : InstrumentationTestCase() {
    private fun optionalPanel() = Snapshots.panel(JSONObject("""{"available":true,"panel":{"version":1,"title":"Orchard notebook",
        "copilot":{"connected":true,"busy":false},"tabs":[
        {"id":"notes","label":"Notes","sections":[{"title":"Notes to review","emphasis":"attention","items":[
        {"id":"seed","title":"Plan the seed library","subtitle":"A fictional community project","actions":[{"id":"inspect-seed","label":"Review","style":"primary"}],
        "log":{"entries":[{"at":"Today","text":"A neighbor suggested an exchange shelf."},{"text":"Waiting for a volunteer."}],"truncated":true}}]}],
        "form":{"version":1,"actionId":"capture-note","title":"Add a note","submitLabel":"Review note","placeholder":"A short idea","minLength":1,"maxLength":300},
        "track":{"version":1,"title":"Project stages","stages":[{"id":"idea","label":"Collect ideas","kind":"normal"},
        {"id":"review","label":"Volunteer review","hint":"A person decides","kind":"gate"},{"id":"choose","label":"Choose next step","kind":"decision"}],
        "placements":[{"itemId":"seed","stageId":"review"}]}},
        {"id":"calendar","label":"Calendar","sections":[]},{"id":"people","label":"People","sections":[]},
        {"id":"archive","label":"Archive","sections":[]}]}}""")).feed!!

    fun testOptionalDashboardNativeFormCancelDraftTabsAndOff() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { base, _, _ ->
            val account = Account("fictional", "owner")
            val pairing = Relationship(account, "orchard-mac", "pin", "fixture-phone", "public", "Orchard", true)
            var state by remember { mutableStateOf(base.copy(account = account, relationship = pairing,
                dashboardEnabled = true, panel = PanelState(fresh = true, available = true, feed = optionalPanel()))) }
            var settings by remember { mutableStateOf(false) }
            val tab = state.panel.feed!!.tabs.first()
            val key = state.panelDraftKey(tab.id, tab.form!!.actionId)!!
            Column {
                TextButton(onClick = { settings = !settings }) { Text(if (settings) "Back to dashboard" else "Dashboard settings") }
                if (settings) CustomDashboardSetting(state, { state = state.dashboardSetting(it) }, {})
                else PanelScreen(state, {}, { _, _, text -> state = state.copy(panel = state.panel.edit(key, text)) },
                    {
                        val submission = state.panel.submission(key)!!
                        val confirm = Snapshots.panelPreview(PanelAction(key.actionId, "Review note"),
                            JSONObject().put("ok", true).put("confirm", "Record this fictional idea only.")
                                .put("confirmationToken", "F".repeat(43)), submission)
                        state = state.copy(panel = state.panel.copy(confirm = confirm))
                    },
                    { state = state.copy(panel = state.panel.copy(confirm = null)) },
                    {
                        val confirm = state.panel.confirm!!
                        assertEquals(confirm.submission!!.text, Snapshots.panelAction(confirm).getString("text"))
                        state = state.copy(panel = state.panel.copy(pending = PanelPending("fixture", key.actionId, true,
                            confirm.submission), confirm = null).complete("fixture", PanelResult(true, true, false, false, "")))
                    }, {})
            }
        }, fontScale = if (enlarged) 2f else 1f, width = if (enlarged) 320 else 390) {
            val raw = "  \"Café 🪴\" idea  "
            scrollToText("Add a note")
            enterDashboardText(raw)
            scrollToText("Review note")
            click("Review note")
            bounds("Your exact text")
            scrollToText(raw)
            bounds(raw)
            click("Cancel")
            scrollToText("Calendar", forward = false)
            click("Calendar")
            bounds("Nothing here right now.")
            click("Notes")
            scrollToText("Add a note")
            assertEquals(raw, editableNode().text.toString())
            scrollToText("Review note")
            click("Review note")
            click("Send")
            scrollToText("Add a note", forward = false)
            assertTrue(editableNode().text.isNullOrEmpty())
            enterDashboardText("Keep this unsent idea")
            click("Dashboard settings")
            click("Custom dashboard")
            bounds("Off · optional")
            click("Custom dashboard")
            click("Back to dashboard")
            scrollToText("Add a note")
            assertEquals("Keep this unsent idea", editableNode().text.toString())
        }
    }

    fun testOptionalDashboardNativeStagesDetailsAndPlainRecentLog() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, _ ->
            Dashboard(state.copy(dashboardEnabled = true,
                panel = PanelState(available = true, feed = optionalPanel())), controller)
        }, fontScale = if (enlarged) 2f else 1f, width = if (enlarged) 320 else 390) {
            scrollToText("Plan the seed library")
            click("Plan the seed library")
            scrollToText("Recent activity")
            bounds("Recent activity")
            scrollToText("Waiting for a volunteer.")
            scrollToText("Some recent activity was omitted. This is not a full transcript.")
            scrollToText("Close details")
            click("Close details")
            scrollToText("Collect ideas")
            scrollToText("Volunteer review")
            scrollToText("Choose next step")
        }
    }

    fun testCanonicalAttentionStripUsesNativeThemeAndLocalNavigation() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { _, _, _ ->
            val account = Account("synthetic", "owner")
            val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
            val request = app.notify.android.remote.RemoteRequest("ticket", "orchard", "Orchard",
                app.notify.android.remote.Prompt("first", "permission", "Review synthetic release"))
            val projection = AttentionProjection("11111111-1111-4111-8111-111111111111", 1,
                AttentionItem("orchard", "first", "Orchard", "Review synthetic release"), 2)
            var state by remember { mutableStateOf(ControllerState(account = account, relationship = pairing,
                foreground = true, status = RemoteStatus("connected", "Synthetic"))
                .receiveRequests(RequestSnapshot(true, listOf(request), projection), 100)) }
            Column {
                PendingAttentionStrip(state, { state = state.openAttention() }, {})
                if (state.decisionFocus > 0) Text("Exact local Decision: ${state.inbox.selectedTicket}")
            }
        }) {
            bounds("Review synthetic release")
            bounds("+2 OTHER waiting requests")
            click("Review synthetic release")
            bounds("Exact local Decision: ticket")
        }
    }

    fun testCanonicalAttentionUnknownHasDiscoverableRestartRecovery() {
        fixture(enlarged = true, content = { _, _, _ ->
            val account = Account("synthetic", "owner")
            val pairing = Relationship(account, "mac", "pin", "phone", "public", "Phone", true)
            val current = AttentionItem("orchard", "first", "Orchard", "Review synthetic release")
            val state = ControllerState(account = account, relationship = pairing, attentionOwner = pairing,
                attention = AttentionState(current = current, handover = true))
            PendingAttentionStrip(state, { error("Unknown projection cannot navigate") }, {})
        }) {
            bounds("Browse decisions")
            bounds("Mac restarted · status unconfirmed. Select the current Mac request in Needs you.")
        }
    }

    fun testDashboardTabsAndOfflineActions() {
        val action = PanelAction("status:example", "Status")
        val feed = PanelFeed("Example dashboard", null, true, false, listOf(
            PanelTab("work", "Work", null, listOf(PanelSection("Needs attention", listOf(
                PanelItem("example", "Example task", "Ready for review", actions = listOf(action)))))),
            PanelTab("done", "Done", null, listOf(PanelSection("Finished", emptyList())))))
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, _ ->
            Dashboard(state.copy(dashboardEnabled = true, status = RemoteStatus("disconnected", "Not connected"),
                panel = PanelState(available = true, feed = feed)), controller)
        }) {
            bounds("Example dashboard")
            bounds("Example task")
            var status: AccessibilityNodeInfo? = node("Status")
            var disabled = false
            while (status != null && status.packageName?.toString() == instrumentation.targetContext.packageName) {
                disabled = disabled || !status.isEnabled
                status = status.parent
            }
            assertTrue("Offline dashboard actions must be disabled", disabled)
            click("Done")
            bounds("Nothing here.")
            click("Work")
            bounds("Example task")
        }
    }

    fun testDashboardShowsExactConfirmation() {
        val action = PanelAction("status:example", "Status")
        val prompt = "Report the status of the example task. Do not change files."
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, _ ->
            Dashboard(state.copy(dashboardEnabled = true, panel = PanelState(confirm =
                PanelConfirm(action, "Confirm example status", prompt, "C".repeat(43)))), controller)
        }) {
            bounds("Confirm example status")
            bounds(prompt)
            bounds("Cancel")
            bounds("Send")
        }
    }

    fun testNativeMarkdownKeepsCodeCopyExact() {
        val code = "  val exact = 1  \r\n\r\n"
        val source = "# Fixture heading\n\n**Bold** and *soft*\n\n```kotlin\r\n${code}```\r\n"
        fixture(content = { _, _, _ ->
            Column { MessageMarkdown(source); CopyButton(source) }
        }) {
            var original: android.content.ClipData? = null
            instrumentation.runOnMainSync {
                val clipboard = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java)
                original = clipboard.primaryClip
                original?.let { clip ->
                    // Replacing a URI clip can revoke the grants needed to restore it.
                    assertTrue("Copy fixture needs a text-only or empty clipboard; existing clip was not changed",
                        (0 until clip.itemCount).all { index ->
                            val item = clip.getItemAt(index)
                            item.uri == null && item.intent == null
                        })
                }
            }
            try {
                bounds("Fixture heading")
                bounds("Bold and soft")
                click("Copy code")
                instrumentation.runOnMainSync {
                    val clip = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                    assertTrue("Code copy must match the synthetic fixture exactly",
                        clip?.itemCount == 1 && clip.getItemAt(0).text?.toString() == code)
                }
                click("Copy text")
                instrumentation.runOnMainSync {
                    val clip = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                    assertTrue("Message copy must match the synthetic source exactly",
                        clip?.itemCount == 1 && clip.getItemAt(0).text?.toString() == source)
                }
            } finally {
                instrumentation.runOnMainSync {
                    val clipboard = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java)
                    original?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                }
            }
        }
    }

    fun testNativeSavedReaderNeedsNoAccountAndContainsNoComposer() {
        val history = ConversationHistory(listOf(CachedConversation(DraftTarget("fixture", "helper"),
            "Synthetic session", "Synthetic helper", 123, true,
            listOf(CachedMessage("assistant", "", "Synthetic saved response", "1")))))
        for (enlarged in listOf(false, true)) fixture(enlarged, { _, _, _ ->
            SavedConversations(history) {}
        }) {
            bounds("Saved conversations")
            bounds("Synthetic helper")
            click("Synthetic helper")
            bounds("Synthetic saved response")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName?.toString() != instrumentation.targetContext.packageName) continue
                assertFalse("Saved reader never restores a composer", node.isEditable)
                assertFalse("Saved reader never offers action authority", node.text?.toString() in
                    setOf("Send", "Turn off now", "Enable Dangerous Auto-yes…", "Stop"))
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
        }
    }

    private fun click(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var target: AccessibilityNodeInfo? = node(text)
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.refresh() && target.isVisibleToUser && target.isEnabled) {
                assertTrue("Fixture click failed for '$text'", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        }
        fail("No enabled clickable fixture control for '$text'")
    }

    fun testComposerRemainsEditableWhileDeliveryIsBlockedOrBusy() {
        fixture(content = { state, controller, _ ->
            val workspace = state.workspace
            val command = workspace.prepare("queue", state.now)
            Sessions(state.copy(busy = true, workspace = workspace.sending(command).uncertain(command)),
                controller, mutableMapOf(), mutableMapOf())
        }) {
            var editor: AccessibilityNodeInfo? = node("Layout regression draft")
            while (editor != null && editor.packageName?.toString() == instrumentation.targetContext.packageName) {
                assertTrue("Sending can be blocked without disabling typing and deletion", editor.isEnabled)
                editor = editor.parent
            }
        }
    }

    fun testNativeAgentsLight() = agentsFixture(PhoneAppearance.Light)

    fun testNativeAgentsDark() = agentsFixture(PhoneAppearance.Dark)

    private fun agentsFixture(appearance: PhoneAppearance) {
        fixture(content = { state, _, _ ->
            val session = state.workspace.session!!.copy(name = "Website refresh",
                managementTicket = "fixture-management", subagentTypes = listOf("reviewer"),
                capabilities = state.workspace.session!!.capabilities.copy(
                    subagentCreate = true, subagentCleanup = true))
            val detail = state.workspace.detail!!.copy(agents = listOf(
                Agent("reviewer", "Review helper", "running", "Checking keyboard navigation",
                    parentKnown = true),
                Agent("writer", "Docs helper", "idle", "Waiting for the review", parentKnown = true)))
            val workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(
                sessions = listOf(session), detail = detail))
            val sample = state.copy(workspace = workspace)
            NotifyTheme(appearance) {
                ChatShell(sample, Destination.Chat, session.name, false, {}, {}, {}, {}) {
                    AgentTeam(sample, Modifier.fillMaxSize(), {})
                }
            }
        }) {
            bounds("Your agents")
            bounds("Review helper")
            var add = node("Add subagent")
            while (!add.isClickable) add = checkNotNull(add.parent)
            assertTrue("A supported fresh session must expose creation", add.isEnabled)
            val target = Rect().also(add::getBoundsInScreen)
            assertTrue(target.height() >= 48 * instrumentation.targetContext.resources.displayMetrics.density)
        }
    }

    fun testDangerousPolicyShowsStateAndReachableAction() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, _ ->
            val session = state.workspace.session!!.copy(
                dangerousAutoYes = DangerousAutoYes("fixture-policy", 1, enlarged, false,
                    "00000000-0000-8000-8000-000000000001"),
                capabilities = state.workspace.session!!.capabilities.copy(dangerousAutoYes = true))
            val workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(sessions = listOf(session)))
            Sessions(state.copy(workspace = workspace), controller, mutableMapOf(), mutableMapOf())
        }) {
            bounds("Dangerous Auto-yes · ${if (enlarged) "On" else "Off"}")
            bounds(if (enlarged) "Turn off now" else "Enable Dangerous Auto-yes…")
        }
    }

    fun testPhoneAppearanceAppliesShippingThemeLive() {
        fixture(content = { _, _, _ ->
            var appearance by remember { mutableStateOf(PhoneAppearance.System) }
            NotifyTheme(appearance) {
                Column {
                    AppearanceChoices(appearance) { appearance = it }
                    Text("Phone appearance: ${appearance.name}")
                    Text("Shipping surface: ${MaterialTheme.colorScheme.surface}")
                }
            }
        }) {
            bounds("System"); bounds("Light"); bounds("Dark")
            var dark: AccessibilityNodeInfo? = node("Dark")
            while (dark != null && !dark.isClickable) dark = dark.parent
            assertNotNull(dark)
            dark!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            bounds("Phone appearance: Dark")
        }
    }

    fun testSessionSettingsExposeNamedNativeColorsAndExactActions() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, _, _ ->
            val original = state.workspace.session!!
            val session = original.copy(connected = false, sessionActions = SessionActions(
                "metadata", "original", "saved", 0x010203, true,
                listOf(SessionColor("Sky", 0xA6C8FF)), true, true, true, "available", "",
                supported = true))
            val workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(sessions = listOf(session)))
            SessionSettings(state.copy(workspace = workspace), {}, { _, _, _ -> })
        }) {
            bounds("Session settings"); bounds("Session color"); bounds("Sky")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            var scroll: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (item.isScrollable) { scroll = item; break }
                for (index in 0 until item.childCount) item.getChild(index)?.let(queue::add)
            }
            assertNotNull(scroll)
            repeat(12) { scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); instrumentation.waitForIdleSync() }
            bounds("Resume on Mac…"); bounds("Remove from Notify…")
        }
    }
    fun testAgentsExposeAddEvenWhenTheMacDoesNotSupportIt() {
        fixture(content = { state, _, _ -> AgentTeam(state, Modifier.fillMaxSize(), {}) }) {
            bounds("Add subagent")
            bounds("Stop idle >10 min")
            var add: AccessibilityNodeInfo? = node("Add subagent")
            var disabled = false
            while (add != null) { disabled = disabled || !add.isEnabled; add = add.parent }
            assertTrue("Unsupported management must not dispatch", disabled)
        }
    }

    fun testCreationFormScrollsToExplicitCreateAtLargeFontScale() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, _, _ ->
            val session = state.workspace.session!!.copy(managementTicket = "fixture-management",
                subagentTypes = listOf("fixture-reviewer"), subagentModels = emptyList(),
                capabilities = state.workspace.session!!.capabilities.copy(subagentCreate = true))
            val workspace = state.workspace.copy(snapshot = state.workspace.snapshot!!.copy(sessions = listOf(session)))
                .editCreation(SubagentCreation("Fixture helper", "fixture-reviewer", "Synthetic layout instructions"))
            SubagentForm(state.copy(workspace = workspace), {}, {}, {})
        }) {
            bounds("Add subagent")
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            var scroll: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (item.isScrollable) { scroll = item; break }
                for (index in 0 until item.childCount) item.getChild(index)?.let(queue::add)
            }
            if (scroll != null) {
                repeat(12) {
                    scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    instrumentation.waitForIdleSync()
                }
            }
            bounds("Create subagent")
        }
    }

    fun testComposerAndSendAreBelowConversationRatherThanOverlaid() {
        fixture(content = { state, controller, _ ->
            Sessions(state, controller, mutableMapOf(), mutableMapOf())
        }) {
            val message = bounds("Layout regression conversation")
            val editor = bounds("Layout regression draft")
            val send = bounds("Send")
            assertTrue("Composer overlaps the conversation: $editor / $message", editor.top >= message.bottom)
            assertTrue("Send overlaps the composer: $send / $editor", send.top >= editor.bottom)
        }
    }

    fun testLegacyMainShowsQueueWithUpdateGuidance() {
        fixture(content = { state, controller, _ ->
            val workspace = state.workspace
            val legacy = workspace.snapshot!!.copy(sessions = workspace.snapshot.sessions.map {
                it.copy(capabilities = it.capabilities.copy(steeringImmediate = false))
            })
            Sessions(state.copy(workspace = workspace.receive(legacy, state.now)), controller, mutableMapOf(), mutableMapOf())
        }) {
            bounds("Queue")
            bounds("Queue waits for the current turn. For Send, update the Mac/helper and reload this session’s Notify extension.")
        }
    }

    fun testSettingsTextIsInsideRoundedButtonEdges() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, activity ->
            Settings(state, controller, activity, {}, SettingsSection.Connection, {})
        }) {
            for (text in listOf("Pairing & security", "Complete public keys and account controls",
                "Connection diagnostics", "Capture connection events locally")) {
                val node = node(text)
                val textBounds = Rect().also(node::getBoundsInScreen)
                var button = node.parent
                while (button != null && !button.isClickable) button = button.parent
                assertNotNull("No settings button for $text", button)
                val buttonBounds = Rect().also(button!!::getBoundsInScreen)
                assertTrue("Text touches the rounded clip edge: $textBounds / $buttonBounds",
                    textBounds.left > buttonBounds.left && textBounds.right < buttonBounds.right)
            }
        }
    }

    fun testAssistantContentIsInsetFromRoundedMessageEdge() {
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, _ ->
            Sessions(state, controller, mutableMapOf(), mutableMapOf())
        }) {
            val message = bounds("Layout regression conversation")
            val outerConversationInset = 20 * instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Assistant text touches its rounded surface edge: $message",
                message.left > outerConversationInset)
        }
    }

    fun testLongerVibrationButtonUpdatesVisibleSettings() {
        val context = instrumentation.targetContext
        assertEquals("app.notify.android.layoutcheck", context.packageName)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NotificationSettings.CHANNEL, "Existing fixture alerts",
                NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
        for (enlarged in listOf(false, true)) fixture(enlarged, { state, controller, activity ->
            val live by controller.state.collectAsState()
            Settings(state.copy(notifications = live.notifications), controller, activity, {},
                SettingsSection.Notifications, {})
        }) {
            if (!enlarged) {
                var button = node("Use longer vibration")
                while (!button.isClickable) button = checkNotNull(button.parent)
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            bounds("Vibration: longer two-pulse pattern")
            assertFalse("The local pattern action must not register alerts",
                NotificationSettings(context).state().consent)
        }
    }

    private fun fixture(enlarged: Boolean = false,
        content: @Composable (ControllerState, ControllerViewModel, MainActivity) -> Unit,
        fontScale: Float = if (enlarged) 1.3f else 1f,
        width: Int = if (enlarged) 320 else 448,
        verify: () -> Unit,
    ) {
        assertEquals("Native fixtures must never run against the real Notify app",
            "app.notify.android.layoutcheck", instrumentation.targetContext.packageName)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            val now = SystemClock.elapsedRealtime()
            val session = Session("layout-session", "Layout test", "Notify", "copilot", "working", true,
                "current-ticket", Capabilities(true, true, true, steeringImmediate = true))
            val detail = SessionDetail(session.id, session.ticket, emptyList(), listOf(
                Activity("message", "assistant", "Assistant", "Layout regression conversation", "2026-01-01T00:00:00Z"),
            ), emptyList())
            val workspace = WorkspaceState(WorkspaceSnapshot(true, listOf(session), detail),
                session.id, receivedAt = now).edit("Layout regression draft")
            val state = ControllerState(workspace = workspace, foreground = true, now = now,
                status = RemoteStatus("connected", "Connected"),
                inbox = RequestInbox().receive(0, true, emptyList(), now))
            instrumentation.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = (activity.application as NotifyApplication).controller
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(
                        LocalDensity.current.density, fontScale)) {
                        NotifyTheme(if (enlarged) PhoneAppearance.Dark else PhoneAppearance.Light) {
                            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                                        content(state, controller, activity)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            verify()
            val bitmap = Bitmap.createBitmap(activity.window.decorView.width,
                activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            try {
                val copied = CountDownLatch(1)
                var result = PixelCopy.ERROR_UNKNOWN
                PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue("Fixture window capture timed out", copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                File(instrumentation.targetContext.filesDir, "$name-${if (enlarged) "large-dark" else "light"}.png")
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun bounds(text: String): Rect = node(text).let { node -> Rect().also(node::getBoundsInScreen) }

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.packageName?.toString() != instrumentation.targetContext.packageName) continue
            if (node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return result
    }

    private fun editableNode(): AccessibilityNodeInfo {
        repeat(12) {
            val nodes = visibleNodes()
            nodes.firstOrNull { it.isEditable }?.let { return it }
            nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
        }
        throw AssertionError("No visible dashboard text field")
    }

    private fun enterDashboardText(text: String) {
        assertTrue(editableNode().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun scrollToText(text: String, forward: Boolean = true) {
        repeat(40) {
            val nodes = visibleNodes()
            if (nodes.any { it.text?.toString() == text && !Rect().also(it::getBoundsInScreen).isEmpty }) return
            val scroll = nodes.firstOrNull { it.isScrollable && !it.isEditable } ?: return@repeat
            scroll.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            instrumentation.waitForIdleSync()
        }
        bounds(text)
    }

    private fun node(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        val observed = mutableSetOf<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName?.toString() != instrumentation.targetContext.packageName) continue
                val label = node.text?.toString()
                if (label != null) observed += label
                if (label == text && !Rect().also(node::getBoundsInScreen).isEmpty) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("No visible fixture node for '$text'; fixture labels: $observed")
    }
}
