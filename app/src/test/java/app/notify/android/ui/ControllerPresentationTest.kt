package app.notify.android.ui

import app.notify.android.controller.*
import app.notify.android.notifications.NotificationState
import app.notify.android.remote.RemoteStatus
import org.junit.Assert.*
import org.junit.Test

class ControllerPresentationTest {
    @Test fun primaryComposerUsesSendOnlyForCapableMainAndKeepsHelpersExact() {
        val main = connected().workspace
        assertEquals(ComposerAction("queue", "Queue"), composerAction(main))
        val capable = main.receive(snapshot.copy(sessions = listOf(session.copy(
            capabilities = session.capabilities.copy(steeringImmediate = true)))), 101)
        assertEquals(ComposerAction("send", "Send"), composerAction(capable))
        for (root in listOf(main, capable)) {
            val helper = root.select("s", "helper")
            assertEquals(ComposerAction("queue", "Send"), composerAction(helper))
            assertEquals("helper", helper.agentID)
        }
        assertEquals(ComposerAction("queue", "Queue"), composerAction(capable.receive(snapshot, 102)))
    }

    @Test fun savedReaderIsExplicitAndReachableWithoutServiceSignIn() {
        val history = ConversationHistory(listOf(CachedConversation(DraftTarget("s", ""), "Build", "Main",
            1, false, listOf(CachedMessage("assistant", "", "Saved reply", "1")))))
        val state = ControllerState(relationship = relationship, savedScope = ConversationScope.from(relationship),
            savedHistory = history)
        assertEquals(ControllerRoute.SignIn, controllerRoute(state))
        assertEquals("Offline", controllerRoute(state.copy(offlineReader = true)).name)
        assertEquals("Disconnected", connectionLabel(state.copy(offlineReader = true)))
        assertEquals(ControllerRoute.SignIn, controllerRoute(state.copy(offlineReader = true, account = Account("issuer", "other"))))
        assertEquals(ControllerRoute.Pairing, controllerRoute(state.copy(offlineReader = true, relationship = null)))
    }
    @Test fun shortViewportMustScrollSurroundingsRatherThanStarveTheEditor() {
        assertNull("120dp leaves no editor after the fixed controls", composerHeightLimit(120f, 1f))
        assertNull("A positive but unusably small editor also needs fallback", composerHeightLimit(240f, 1f))
        assertNull("Font scaling must be included in the editor/control budget", composerHeightLimit(360f, 2f))
    }

    @Test fun roomyViewportKeepsTheBoundedBottomComposer() {
        for ((height, scale) in listOf(700f to 1f, 900f to 2f)) {
            val limit = composerHeightLimit(height, scale)
            assertNotNull(limit)
            assertTrue("Leave room for conversation, not an overflowing minimum", limit!! < height)
            assertTrue("Keep room for a useful editor and action row", limit > 80f * scale + 70f)
        }
    }

    @Test fun keyboardReducedDrawerMustScrollChromeAndResultsTogether() {
        assertFalse(drawerKeepsPinnedChrome(120f, 1f))
        assertFalse(drawerKeepsPinnedChrome(300f, 2f))
        assertTrue(drawerKeepsPinnedChrome(800f, 1f))
        assertTrue(drawerKeepsPinnedChrome(1000f, 2f))
    }

    private val account = Account("issuer", "owner")
    private val relationship = Relationship(account, "mac", "mac-key", "phone", "phone-key", "Phone", true)
    private val session = Session("s", "Build", "Project", "copilot", "working", true, "ticket",
        Capabilities(true, true, true))
    private val snapshot = WorkspaceSnapshot(true, listOf(session),
        SessionDetail("s", "ticket", emptyList(), emptyList(), emptyList()))
    private fun connected() = ControllerState(account = account, relationship = relationship, foreground = true,
        status = RemoteStatus("connected", "Encrypted channel connected."),
        notifications = NotificationState(setupOffered = true),
        workspace = WorkspaceState(sessionID = "s").receive(snapshot, 100).edit("Keep my draft"))
        .receiveRequests(RequestSnapshot(true, emptyList()), 100)

    @Test fun reconnectKeepsAuthenticatedEnrolledChatButNeverEnablesStaleActions() {
        val connected = connected()
        for (status in listOf("connecting", "pending", "disconnected", "error", "connected")) {
            val state = connected.retireConnection().copy(status = RemoteStatus(status, "Unavailable"))
            assertEquals("Retain chat during $status", ControllerRoute.Chat, controllerRoute(state))
            assertEquals("Keep my draft", state.workspace.draft)
            assertEquals(snapshot.detail, state.workspace.detail)
            assertFalse(state.live)
            assertNotNull(state.workspace.unavailable("queue", state.now))
        }
    }

    @Test fun setupAndAccountBoundariesCannotBeBypassedByOldHistory() {
        val state = connected()
        assertEquals(ControllerRoute.Pairing, controllerRoute(state.copy(relationship = null)))
        assertEquals(ControllerRoute.SignIn, controllerRoute(state.endAccountSession()))
        assertEquals(ControllerRoute.SignIn, controllerRoute(state.copy(account = Account("issuer", "other"))))
        assertEquals(ControllerRoute.Trust, controllerRoute(state.copy(relationship = relationship.copy(enrolled = false))))
        assertEquals(ControllerRoute.Trust, controllerRoute(state.copy(workspace = WorkspaceState(),
            status = RemoteStatus("pending", "Approve on Mac"))))
    }

    @Test fun selectedChatDoesNotHideExplicitOrFirstNotificationSetup() {
        val state = connected()
        assertEquals(ControllerRoute.Notifications, controllerRoute(state.copy(notificationSetupOpen = true)))
        assertEquals(ControllerRoute.Notifications, controllerRoute(state.copy(
            notifications = state.notifications.copy(setupOffered = false))))
        assertEquals(ControllerRoute.Chat, controllerRoute(state))
    }

    @Test fun connectionLabelSeparatesTransportFromRequestAndWorkspaceFreshness() {
        val state = connected()
        assertEquals("Connected to Mac", connectionLabel(state))
        assertEquals("Connected · updates stale", connectionLabel(state.copy(now = 10_100)))
        assertEquals("Connected · updates stale", connectionLabel(state.copy(
            workspace = state.workspace.copy(receivedAt = null))))
        assertEquals("Connected · updates stale", connectionLabel(state.copy(
            inbox = state.inbox.copy(connected = false))))
        assertEquals("Connecting", connectionLabel(state.copy(status = RemoteStatus("connecting", ""))))
        assertEquals("Disconnected", connectionLabel(state.copy(status = RemoteStatus("disconnected", ""))))
        assertEquals("Connection error", connectionLabel(state.copy(status = RemoteStatus("error", ""))))
        val pending = state.retireConnection().copy(status = RemoteStatus("pending", ""))
        assertEquals("Waiting for Mac", connectionLabel(pending))
        assertEquals("Awaiting Mac approval", connectionLabel(pending.copy(workspace = WorkspaceState())))
    }
}
