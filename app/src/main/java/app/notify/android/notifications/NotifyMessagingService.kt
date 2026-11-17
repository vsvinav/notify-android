package app.notify.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import app.notify.android.MainActivity
import app.notify.android.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NotifyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Account cookies are intentionally memory-only. A rotated token waits for
        // a signed-in foreground refresh, never a persisted grant or device ID alone.
        NotificationSettings(this).noteToken(token)
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val settings = NotificationSettings(this)
        if (!settings.state().enabled || message.notification != null) return
        val prefs = settings.prefs
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val id = Attention.accept(message.data, now, seen) ?: return
        val updated = (seen.toList().takeLast(7) + id).toSet()
        if (!prefs.edit().putStringSet("seen", updated).commit()) return
        if (NotificationSettings.foreground || now - prefs.getLong("lastAlert", 0) < 30000) return
        if (!prefs.edit().putLong("lastAlert", now).commit()) return
        settings.createChannel()
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(NotificationSettings.OPEN_ATTENTION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = Notification.Builder(this, NotificationSettings.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Notify")
            .setContentText("Open Notify to check for updates.").build()
        val notification = Notification.Builder(this, NotificationSettings.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Your Mac needs attention")
            .setContentText("Open Notify to check its current state.").setContentIntent(tap)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setTimeoutAfter(120000).build()
        try {
            if (settings.state().enabled)
                getSystemService(NotificationManager::class.java).notify(NotificationSettings.NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { /* Permission can be revoked between checking and posting. */ }
    }
}
