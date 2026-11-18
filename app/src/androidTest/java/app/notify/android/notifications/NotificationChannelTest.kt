@file:Suppress("DEPRECATION")

package app.notify.android.notifications

import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.test.InstrumentationTestCase
import android.provider.Settings

// Run each method with fresh fixture app data: Android restores deleted channels' settings.
class NotificationChannelTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    override fun setUp() {
        super.setUp()
        context = instrumentation.targetContext
        assertEquals("Never change the real Notify notification channels",
            "app.notify.android.layoutcheck", context.packageName)
        manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Clear only the isolated fixture's app data before each channel test",
            manager.notificationChannels.isEmpty())
    }

    fun testFreshInstallUsesLongerVibrationWithoutEnablingAlerts() {
        val settings = NotificationSettings(context)
        settings.createChannel()
        val channel = manager.notificationChannels.single()
        assertTrue("New installs need the approved longer two-pulse pattern",
            longArrayOf(0, 300, 180, 450).contentEquals(channel.vibrationPattern))
        assertTrue(channel.shouldVibrate())
        assertFalse("Selecting a pattern must not opt in to alerts", settings.state().consent)
        assertFalse(settings.state().registered)
    }

    fun testUpgradeKeepsLegacyUntilExplicitChoiceAndRetainsRegistration() {
        val legacy = NotificationChannel(NotificationSettings.CHANNEL, "Existing alerts",
            NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 50)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)
        }
        manager.createNotificationChannel(legacy)
        val settings = NotificationSettings(context)
        settings.prefs.edit().putBoolean("consent", true).putString("tokenHash", "fixture")
            .putString("registered", "fixture").commit()
        settings.createChannel()
        assertEquals("Upgrading must not silently switch channels", legacy.id, settings.channelID)
        assertEquals(1, manager.notificationChannels.size)
        settings.useLongerVibration()
        assertFalse(legacy.id == settings.channelID)
        val longer = manager.getNotificationChannel(settings.channelID)
        assertEquals(legacy.importance, longer.importance)
        assertEquals(legacy.sound, longer.sound)
        assertTrue(longArrayOf(0, 300, 180, 450).contentEquals(longer.vibrationPattern))
        assertTrue(longer.shouldVibrate())
        val restored = NotificationSettings(context)
        assertEquals(settings.channelID, restored.channelID)
        assertTrue(restored.state().consent)
        assertTrue(restored.state().registered)
        assertNotNull("Keep the previous user-controlled channel", manager.getNotificationChannel(legacy.id))
    }

    fun testDisabledMutedAndVibrationOffChoicesSurviveMigration() {
        manager.createNotificationChannel(NotificationChannel(NotificationSettings.CHANNEL, "Muted alerts",
            NotificationManager.IMPORTANCE_NONE).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val settings = NotificationSettings(context)
        settings.useLongerVibration()
        val channel = manager.getNotificationChannel(settings.channelID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(settings.state().enabled)
    }

    fun testExistingLongerChannelChoicesAreNotOverwritten() {
        val settings = NotificationSettings(context)
        val longerID = settings.channelID
        manager.createNotificationChannel(NotificationChannel(longerID, "Customized alerts",
            NotificationManager.IMPORTANCE_NONE).apply {
            setSound(null, null)
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(NotificationSettings.CHANNEL, "Existing alerts",
            NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        assertEquals(NotificationSettings.CHANNEL, settings.channelID)
        settings.useLongerVibration()
        val channel = manager.getNotificationChannel(longerID)
        assertEquals(longerID, settings.channelID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    fun testExistingLouderDestinationCannotUnmuteLegacyAlerts() {
        val settings = NotificationSettings(context)
        val longerID = settings.channelID
        manager.createNotificationChannel(NotificationChannel(longerID, "Earlier destination",
            NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        manager.createNotificationChannel(NotificationChannel(NotificationSettings.CHANNEL, "Muted alerts",
            NotificationManager.IMPORTANCE_NONE).apply {
            setSound(null, null)
            enableVibration(false)
        })
        try {
            settings.useLongerVibration()
            fail("A pattern change must not enable an existing louder destination")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Android"))
        }
        assertEquals(NotificationSettings.CHANNEL, settings.channelID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(longerID).importance)
    }
}
