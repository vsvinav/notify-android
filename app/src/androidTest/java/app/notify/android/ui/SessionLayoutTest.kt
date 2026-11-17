@file:Suppress("DEPRECATION")

package app.notify.android.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModelProvider
import app.notify.android.MainActivity
import app.notify.android.controller.*
import app.notify.android.remote.RemoteStatus
import app.notify.android.remote.RequestInbox

class SessionLayoutTest : InstrumentationTestCase() {
    fun testComposerAndSendAreBelowConversationRatherThanOverlaid() {
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
                val controller = ViewModelProvider(activity)[ControllerViewModel::class.java]
                activity.setContent {
                    MaterialTheme {
                        Surface { Sessions(state, controller, mutableMapOf()) }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val message = bounds("Layout regression conversation")
            val editor = bounds("Layout regression draft")
            val send = bounds("Send")
            assertTrue("Composer overlaps the conversation: $editor / $message", editor.top >= message.bottom)
            assertTrue("Send overlaps the composer: $send / $editor", send.top >= editor.bottom)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun bounds(text: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            val bounds = nodes.map { node -> Rect().also(node::getBoundsInScreen) }.firstOrNull { !it.isEmpty }
            nodes.forEach { it.recycle() }
            if (bounds != null) return bounds
            SystemClock.sleep(50)
        }
        throw AssertionError("No visible accessibility node for '$text'.")
    }
}
