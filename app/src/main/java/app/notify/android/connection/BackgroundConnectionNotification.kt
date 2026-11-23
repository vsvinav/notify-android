package app.notify.android.connection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.notify.android.MainActivity
import app.notify.android.R
import app.notify.android.remote.RemoteStatus

class BackgroundConnectionNotification(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background connection",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Connection status and Stop; separate from Mac attention alerts"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
    }

    fun unavailableReason(): String? = when {
        (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled() ->
            "Allow Notify notifications in Android settings, then choose Keep connected in background again. Mac attention alerts stay unchanged."
        manager.getNotificationChannel(CHANNEL)?.let {
            it.importance == NotificationManager.IMPORTANCE_NONE ||
                it.group?.let { group -> manager.getNotificationChannelGroup(group)?.isBlocked } == true
        } != false ->
            "Enable the Background connection channel in Android settings, then try again. Mac attention alerts stay unchanged."
        else -> null
    }

    fun build(token: String, status: RemoteStatus): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 0,
            Intent(context, BackgroundConnectionService::class.java).setAction(BackgroundConnectionService.STOP)
                .setData(Uri.parse("notify-background:$token")).putExtra(BackgroundConnectionService.TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (status.state) {
            "connected" -> "Connected to your Mac"
            "connecting", "disconnected" -> "Reconnecting to your Mac"
            "pending", "greeting" -> "Waiting for your Mac"
            else -> "Connection not ready"
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notify · Background connection")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    fun openSettings() {
        createChannel()
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val CHANNEL = "notify-background-connection-v1"
        const val ID = 2041
    }
}
