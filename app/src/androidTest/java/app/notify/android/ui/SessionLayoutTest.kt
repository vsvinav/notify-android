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
import androidx.lifecycle.ViewModelProvider
import app.notify.android.MainActivity
import app.notify.android.controller.*
import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.RequestInbox
import app.notify.android.notifications.NotificationSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionLayoutTest : InstrumentationTestCase() {
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
                "current-ticket", Capabilities(true, true, true))
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
                val controller = ViewModelProvider(activity)[ControllerViewModel::class.java]
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(
                        LocalDensity.current.density, if (enlarged) 1.3f else 1f)) {
                        NotifyTheme(if (enlarged) PhoneAppearance.Dark else PhoneAppearance.Light) {
                            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(Modifier.width(if (enlarged) 320.dp else 448.dp).fillMaxHeight()) {
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
