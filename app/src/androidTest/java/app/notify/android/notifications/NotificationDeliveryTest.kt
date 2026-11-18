@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package app.notify.android.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.test.ServiceTestCase
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class NotificationDeliveryTest : ServiceTestCase<NotifyMessagingService>(NotifyMessagingService::class.java) {
    private lateinit var manager: NotificationManager
    private lateinit var settings: NotificationSettings

    override fun setUp() {
        super.setUp()
        assertEquals("Never change production Notify notification preferences",
            "app.notify.android.layoutcheck", context.packageName)
        application = context.applicationContext as Application
        setupService()
        manager = context.getSystemService(NotificationManager::class.java)
        settings = NotificationSettings(context)
        // These posting checks are silent; physical vibration is tested separately.
        manager.createNotificationChannel(NotificationChannel(settings.channelID, "Silent delivery fixture",
            NotificationManager.IMPORTANCE_LOW).apply { enableVibration(false); setSound(null, null) })
        assertTrue(settings.prefs.edit().clear().putBoolean("consent", true)
            .putString("registered", "fixture").putString("tokenHash", "fixture").commit())
        assertTrue("Grant notification permission only to the disposable fixture", settings.state().enabled)
        manager.cancelAll()
    }

    override fun tearDown() {
        NotificationSettings.foreground = false
        manager.cancelAll()
        super.tearDown()
    }

    private fun message(id: String = UUID.randomUUID().toString()) = RemoteMessage.Builder("fixture")
        .setData(mapOf("type" to "attention", "version" to "1", "eventID" to id,
            "issuedAt" to System.currentTimeMillis().toString())).build()

    private fun postedAfter(previous: Long = 0): Long {
        val deadline = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.singleOrNull()?.postTime?.takeIf { it > previous }?.let { return it }
            Thread.sleep(20)
        }
        fail("A valid fresh attention event was dropped instead of being posted")
        return 0
    }

    fun testForegroundAttentionIsPosted() {
        NotificationSettings.foreground = true
        service.onMessageReceived(message())
        postedAfter()
    }

    fun testDistinctDelayedPushesAreNotDroppedByASecondPhoneCooldown() {
        service.onMessageReceived(message())
        val first = postedAfter()
        Thread.sleep(30)
        val second = message()
        service.onMessageReceived(second)
        val latest = postedAfter(first)
        service.onMessageReceived(second)
        Thread.sleep(150)
        assertEquals("Duplicate FCM delivery must not alert again", latest, manager.activeNotifications.single().postTime)
        assertTrue(settings.prefs.edit().putBoolean("consent", false).commit())
        service.onMessageReceived(message())
        Thread.sleep(150)
        assertEquals("Opt-out must still block alerts", latest, manager.activeNotifications.single().postTime)
    }
}
