package app.notify.android.controller

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.KeyStoreException
import app.notify.android.notifications.NotificationState

class AccountLifetimeTest {
    @Test fun signOutCacheFailureCannotSkipNotificationOptOutRoutingOrLogout() = runBlocking {
        val account = Account("issuer", "subject")
        var state = ControllerState(account = account,
            notifications = NotificationState(consent = true, registered = true))
        val events = mutableListOf<String>()
        val cacheFailure = ConversationCacheException(KeyStoreException("Synthetic key deletion failure"))
        var failures: List<Pair<SignOutStep, Exception>> = emptyList()
        runCatching {
            failures = signOutCleanup(
                disableNotificationsLocally = {
                    events += "notifications-off"
                    state = state.copy(notifications = state.notifications.copy(consent = false, registered = false, cleanupPending = true))
                },
                eraseConversations = { events += "cache-clear"; throw cacheFailure },
                removeNotificationRouting = {
                    events += "routing-remove"
                    assertEquals(account, state.account)
                    state = state.copy(notifications = state.notifications.copy(cleanupPending = false))
                },
                logout = { events += "logout" },
                endSession = { events += "end-session"; state = state.endAccountSession() },
            )
        }
        assertEquals(listOf("notifications-off", "cache-clear", "routing-remove", "logout", "end-session"), events)
        assertFalse(state.notifications.consent)
        assertFalse(state.notifications.registered)
        assertFalse(state.notifications.cleanupPending)
        assertNull(state.account)
        assertEquals(listOf(SignOutStep.Cache to cacheFailure), failures)
    }

    @Test fun signOutAttemptsLogoutAfterRoutingFailureAndRetainsPendingCleanup() = runBlocking {
        val account = Account("issuer", "subject")
        var state = ControllerState(account = account,
            notifications = NotificationState(consent = true, registered = true))
        val events = mutableListOf<String>()
        val routeFailure = IOException("Synthetic routing failure")
        val logoutFailure = IOException("Synthetic logout failure")
        var failures: List<Pair<SignOutStep, Exception>> = emptyList()
        runCatching {
            failures = signOutCleanup(
                disableNotificationsLocally = {
                    events += "notifications-off"
                    state = state.copy(notifications = state.notifications.copy(consent = false, registered = false, cleanupPending = true))
                },
                eraseConversations = { events += "cache-clear" },
                removeNotificationRouting = {
                    events += "routing-remove"
                    assertEquals(account, state.account)
                    throw routeFailure
                },
                logout = { events += "logout"; throw logoutFailure },
                endSession = { events += "end-session"; state = state.endAccountSession() },
            )
        }
        assertEquals(listOf("notifications-off", "cache-clear", "routing-remove", "logout", "end-session"), events)
        assertFalse(state.notifications.consent)
        assertFalse(state.notifications.registered)
        assertTrue(state.notifications.cleanupPending)
        assertNull(state.account)
        assertEquals(listOf(SignOutStep.NotificationRouting to routeFailure, SignOutStep.Logout to logoutFailure), failures)
    }

    @Test fun signOutReportsLocalOptOutFailureWithoutSkippingRemainingCleanup() = runBlocking {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("Synthetic preference failure")
        var failures: List<Pair<SignOutStep, Exception>> = emptyList()
        runCatching {
            failures = signOutCleanup(
                disableNotificationsLocally = { events += "notifications-off"; throw failure },
                eraseConversations = { events += "cache-clear" },
                removeNotificationRouting = { events += "routing-remove" },
                logout = { events += "logout" },
                endSession = { events += "end-session" },
            )
        }
        assertEquals(listOf("notifications-off", "cache-clear", "routing-remove", "logout", "end-session"), events)
        assertEquals(listOf(SignOutStep.LocalNotifications to failure), failures)
    }

    @Test fun restartedReaderNeedsOnlySavedPairingAndNeverRestoresAuthority() {
        val record = Relationship(Account("issuer", "subject"), "mac", "pin", "device", "public", "Phone", true)
        val history = ConversationHistory(listOf(CachedConversation(DraftTarget("s", "helper"), "Build", "Reviewer",
            123, true, listOf(CachedMessage("assistant", "", "Saved reply", "now")))))
        val state = ControllerState(relationship = record, savedHistory = history,
            savedScope = ConversationScope.from(record), offlineReader = true)
        assertEquals(history, state.visibleSavedHistory)
        assertFalse(state.live)
        assertNull(state.account)
        assertNull(state.workspace.target)
        assertTrue(state.inbox.cards.isEmpty())
        assertTrue(state.workspace.deliveries.isEmpty())
        assertTrue(state.workspace.drafts.isEmpty())
        assertNotNull(state.workspace.unavailable("queue", 1))
        assertTrue(state.copy(account = Account("issuer", "other")).visibleSavedHistory.conversations.isEmpty())
        assertTrue(state.copy(relationship = record.copy(macID = "other")).visibleSavedHistory.conversations.isEmpty())
        assertTrue(state.copy(relationship = null).visibleSavedHistory.conversations.isEmpty())
        assertEquals(history, state.endAccountSession().visibleSavedHistory)
        assertTrue(state.clearSavedHistory().visibleSavedHistory.conversations.isEmpty())
        assertFalse(state.clearSavedHistory().offlineReader)
    }

    @Test fun freshWorkspaceLeavesSavedReaderAndRetainsAllReportedMessages() {
        val session = Session("s", "Build", "", "copilot", "working", true, "ticket", Capabilities(true, true, true))
        val snapshot = WorkspaceSnapshot(true, listOf(session), SessionDetail("s", "ticket", emptyList(),
            (1..20).map { Activity("$it", "assistant", "", "message $it", "$it") }, emptyList()))
        val state = ControllerState(offlineReader = true).receiveWorkspace(snapshot, 123)
        assertFalse(state.offlineReader)
        assertEquals(20, state.workspace.snapshot!!.detail!!.activity.size)
    }
    @Test fun reconnectRequiresMatchingEnrolledAccountAndForeground() {
        val account = Account("issuer", "subject")
        val record = Relationship(account, "mac", "pin", "device", "public", "Phone", true)
        val state = ControllerState(account = account, relationship = record, foreground = true)
        assertEquals(record, state.reconnectable())
        assertNull(state.copy(account = null).reconnectable())
        assertNull(state.copy(account = Account("issuer", "other")).reconnectable())
        assertNull(state.copy(relationship = record.copy(enrolled = false)).reconnectable())
        assertNull(state.copy(foreground = false).reconnectable())
    }

    @Test fun expiredAccountClearsPrivateViewsButKeepsPairingAndDisablesAuthorization() {
        val account = Account("issuer", "subject")
        val record = Relationship(account, "mac", "pin", "device", "public", "Phone", true)
        val session = Session("s", "Build", "project", "copilot", "working", true, "t", Capabilities(true, true, true))
        val workspace = WorkspaceState(WorkspaceSnapshot(true, listOf(session),
            SessionDetail("s", "t", emptyList(), emptyList(), emptyList())), "s", receivedAt = 100).edit("private draft")
        val expired = ControllerState(account = account, relationship = record, workspace = workspace,
            foreground = true, notificationSetupOpen = true).endAccountSession()
        assertEquals(record, expired.relationship)
        assertNull(expired.account)
        assertTrue(expired.workspace.drafts.isEmpty())
        assertNull(expired.workspace.snapshot)
        assertFalse(expired.live)
        assertFalse(expired.notificationSetupOpen)
    }
}
