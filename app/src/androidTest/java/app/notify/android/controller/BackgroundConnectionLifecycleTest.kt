@file:Suppress("DEPRECATION")

package app.notify.android.controller

import android.app.NotificationManager
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import app.notify.android.MainActivity
import app.notify.android.NotifyApplication
import app.notify.android.connection.BackgroundConnectionService
import app.notify.android.connection.BackgroundConnectionNotification
import app.notify.android.connection.BackgroundNotificationWatch
import app.notify.android.connection.BackgroundNotificationReceiver
import app.notify.android.notifications.NotificationSettings
import app.notify.android.remote.RemoteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.notify.android.ui.Dashboard
import app.notify.android.ui.NotifyTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated acceptance only; never run against the installed account-bearing app. */
class BackgroundConnectionLifecycleTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        assertEquals("app.notify.android.layoutcheck", instrumentation.targetContext.packageName)
    }

    fun testActivityFinishAndReopenShareOneApplicationController() {
        val context = instrumentation.targetContext
        val application = context.applicationContext as NotifyApplication
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val first = instrumentation.startActivitySync(intent) as MainActivity
        lateinit var owner: ControllerViewModel
        instrumentation.runOnMainSync {
            owner = application.controller
            assertTrue(owner.state.value.foreground)
            assertFalse(owner.state.value.backgroundRunning)
            first.finish()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertFalse(owner.state.value.foreground) }
        val second = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.runOnMainSync {
            assertSame(owner, application.controller)
            assertTrue(owner.state.value.foreground)
            second.finish()
        }
        instrumentation.waitForIdleSync()
    }

    fun testQuietChannelDoesNotOptIntoOrModifyAttentionAlerts() {
        val context = instrumentation.targetContext
        val alerts = NotificationSettings(context)
        val before = alerts.state()
        val background = BackgroundConnectionNotification(context)
        background.createChannel()
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(BackgroundConnectionNotification.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(before, alerts.state())
    }

    fun testUnsolicitedServiceStartCannotCreateConnectionOrEnableMode() {
        val context = instrumentation.targetContext
        val application = context.applicationContext as NotifyApplication
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            context.startService(Intent(context, BackgroundConnectionService::class.java))
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse(application.controller.state.value.backgroundRunning)
            assertFalse(application.controller.state.value.backgroundStarting)
            assertNull(application.controller.state.value.account)
            activity.finish()
        }
    }

    fun testPermissionDenialExplainsRecoveryWithoutEnablingAlerts() = fixture { controller ->
        assertTrue("Run this method with the isolated fixture's notification permission denied",
            Build.VERSION.SDK_INT >= 33 && instrumentation.targetContext.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val before = NotificationSettings(instrumentation.targetContext).state()
        instrumentation.runOnMainSync {
            val id = controller.requestBackgroundPermission()!!
            controller.backgroundPermissionResult(id, false)
            assertFalse(controller.state.value.backgroundStarting)
            assertFalse(controller.state.value.backgroundRunning)
            assertTrue(controller.state.value.error!!.contains("Android settings"))
        }
        assertEquals(before, NotificationSettings(instrumentation.targetContext).state())
    }

    fun testDisabledChannelFailsEnableSafely() = fixture { controller ->
        val notification = BackgroundConnectionNotification(instrumentation.targetContext)
        notification.createChannel()
        assertEquals("Disable only the isolated fixture's Background connection channel before this test",
            NotificationManager.IMPORTANCE_NONE, instrumentation.targetContext
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(BackgroundConnectionNotification.CHANNEL).importance)
        instrumentation.runOnMainSync {
            val id = controller.requestBackgroundPermission()!!
            controller.backgroundPermissionResult(id, true)
            assertFalse(controller.state.value.backgroundStarting)
            assertFalse(controller.state.value.backgroundRunning)
            assertNotNull(controller.state.value.error)
        }
    }

    fun testRealServiceOutlivesFinishedActivityAndNotificationStopKeepsPairing() = fixture { controller ->
        val context = instrumentation.targetContext
        val notification = BackgroundConnectionNotification(context)
        notification.createChannel()
        assertNull("Grant permission to this isolated fixture and enable its background channel first",
            notification.unavailableReason())
        val before = NotificationSettings(context).state()
        var id = ""
        instrumentation.runOnMainSync {
            id = controller.requestBackgroundPermission()!!
            controller.backgroundPermissionResult(id, true)
        }
        await { controller.state.value.backgroundRunning }
        val pairing = controller.state.value.relationship
        instrumentation.runOnMainSync { activeActivity!!.finish() }
        await { !controller.state.value.foreground }
        instrumentation.runOnMainSync {
            assertTrue(controller.state.value.backgroundRunning)
            notification.build(id, controller.state.value.status).actions.single().actionIntent.send()
        }
        await { !controller.state.value.backgroundRunning }
        assertEquals(pairing, controller.state.value.relationship)
        assertNotNull(controller.state.value.account)
        assertEquals("disconnected", controller.state.value.status.state)
        assertEquals(before, NotificationSettings(context).state())
    }

    fun testHiddenDashboardDoesNotConsumeAnInflightActionOutcome() = fixture { controller ->
        val rendered = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        val state = ControllerViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<ControllerState>
        instrumentation.runOnMainSync {
            state.value = state.value.copy(foreground = false,
                panel = PanelState(message = "Fixture delivery is uncertain"))
            (activeActivity as MainActivity).setContent {
                val current by controller.state.collectAsState()
                NotifyTheme { Dashboard(current, controller) }
                SideEffect { if (!current.foreground && current.panel.message != null) rendered.countDown() }
            }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals("Fixture delivery is uncertain", controller.state.value.panel.message)
    }

    fun testBlockReceiverReadsAndroidStateInsteadOfBroadcastExtrasAndCloses() {
        val context = instrumentation.targetContext
        val notification = BackgroundConnectionNotification(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        notification.createChannel()
        assertNull("Use fresh isolated fixture data with notification permission granted", notification.unavailableReason())
        val before = NotificationSettings(context).state()
        var reads = 0
        var stopped: String? = null
        val watch = BackgroundNotificationWatch("fixture", { it == "fixture" },
            { reads++; notification.unavailableReason() }, { _, reason -> stopped = reason })
        val receiver = BackgroundNotificationReceiver(watch)
        instrumentation.runOnMainSync {
            receiver.register(context)
            try {
                for (action in listOf(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
                    NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
                    NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)) {
                    receiver.onReceive(context, Intent(action).putExtra(NotificationManager.EXTRA_BLOCKED_STATE, true))
                }
                assertNull("A claimed block is not Android's actual state", stopped)
                assertEquals(4, reads)
                receiver.onReceive(context, Intent("fixture.unrelated").putExtra(NotificationManager.EXTRA_BLOCKED_STATE, true))
                assertEquals(4, reads)
                // Only this isolated test channel is removed; no live service or account exists here.
                manager.deleteNotificationChannel(BackgroundConnectionNotification.CHANNEL)
                receiver.onReceive(context, Intent(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                    .putExtra(NotificationManager.EXTRA_BLOCKED_STATE, false))
                assertNotNull("Actual unavailability wins over a claimed unblock", stopped)
            } finally { receiver.close(); notification.createChannel() }
            val afterClose = reads
            receiver.onReceive(context, Intent(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED))
            assertEquals("Already queued callbacks cannot act after service cleanup", afterClose, reads)
        }
        assertEquals(before, NotificationSettings(context).state())
    }

    private var activeActivity: android.app.Activity? = null

    // Synthetic identity/status only: never enroll, sign in, open a socket or call the real service.
    private fun fixture(block: (ControllerViewModel) -> Unit) {
        val context = instrumentation.targetContext
        activeActivity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val controller = (context.applicationContext as NotifyApplication).controller
        await { !controller.state.value.busy }
        val original = controller.state.value
        assertNull("Use fresh isolated app data; never an account-bearing fixture", original.account)
        assertNull(original.relationship)
        @Suppress("UNCHECKED_CAST")
        val state = ControllerViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(controller) as MutableStateFlow<ControllerState>
        val account = Account("fixture", "owner")
        instrumentation.runOnMainSync {
            state.value = original.copy(account = account,
                relationship = Relationship(account, "fixture-mac", "pin", "fixture-phone", "public", "Fixture", true),
                status = RemoteStatus("connected", "Synthetic transport; no socket"))
        }
        try { block(controller) } finally {
            instrumentation.runOnMainSync {
                controller.disconnect()
                state.value = original.copy(foreground = controller.state.value.foreground)
                activeActivity?.finish()
                activeActivity = null
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(20)
        }
        assertTrue("Lifecycle transition timed out", condition())
    }
}
