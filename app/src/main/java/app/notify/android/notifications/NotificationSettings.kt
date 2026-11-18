package app.notify.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NotificationSettings(context: Context) {
    private val context = context.applicationContext
    internal val prefs = context.getSharedPreferences("notify-notifications-v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun state(): NotificationState = NotificationState(
        consent = prefs.getBoolean("consent", false),
        permission = permissionGranted() && manager.areNotificationsEnabled(),
        channel = (manager.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) >
            NotificationManager.IMPORTANCE_NONE,
        registered = prefs.getString("registered", "")?.let { it.isNotEmpty() && it == prefs.getString("tokenHash", "") } == true,
        cleanupPending = !prefs.getBoolean("consent", false) && prefs.getBoolean("routePossible", false),
        setupOffered = prefs.getBoolean("setupOffered", false),
    )
    fun permissionGranted() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun markSetupOffered() = save(prefs.edit().putBoolean("setupOffered", true))

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Mac attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Private reminders to open Notify and check your Mac"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 65, 85, 65, 150, 110)
            setSound(Uri.parse("android.resource://${context.packageName}/raw/notify_attention"),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        manager.createNotificationChannel(channel)
    }
    suspend fun beginRegistration(): String {
        createChannel()
        check(permissionGranted() && state().permission && state().channel) {
            "Android notifications are blocked. Allow this channel in system settings first."
        }
        save(prefs.edit().putBoolean("consent", true).putString("registered", ""))
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        val token = messaging.token.awaitPrivate()
        noteToken(token)
        check(state().consent && state().permission && state().channel) { "Notifications were turned off. Registration stopped." }
        save(prefs.edit().putBoolean("routePossible", true))
        return token
    }
    fun noteToken(token: String) {
        if (!prefs.getBoolean("consent", false)) return
        val hash = digest(token)
        if (prefs.getString("tokenHash", "") != hash)
            save(prefs.edit().putString("tokenHash", hash).putString("registered", ""))
    }
    fun registered(token: String) {
        check(state().consent && state().permission && state().channel && prefs.getString("tokenHash", "") == digest(token)) {
            "Notification settings or token changed. Refresh registration explicitly."
        }
        save(prefs.edit().putString("registered", digest(token)))
    }
    fun disableLocally() {
        val hadConsent = prefs.getBoolean("consent", false)
        save(prefs.edit().putBoolean("consent", false).putString("registered", ""))
        manager.cancel(NOTIFICATION_ID)
        if (hadConsent || prefs.getBoolean("routePossible", false)) {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            messaging.deleteToken().addOnCompleteListener { /* Server removal remains a separate signed operation. */ }
        }
    }
    fun removed() = save(prefs.edit().putBoolean("routePossible", false).remove("tokenHash").remove("registered"))
    fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun save(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Could not save notification preferences. Check phone storage." }
    }
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CHANNEL = "notify-attention-v1"
        const val NOTIFICATION_ID = 1
        const val OPEN_ATTENTION = "app.notify.android.OPEN_ATTENTION"
        @Volatile var foreground = false
    }
}

private suspend fun <T> Task<T>.awaitPrivate(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(IOException("Google notification registration is unavailable. Try again explicitly."))
        }
    }
}
