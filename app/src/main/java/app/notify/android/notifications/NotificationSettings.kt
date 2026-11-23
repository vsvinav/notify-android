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
import app.notify.android.controller.Relationship
import app.notify.android.remote.PushMetadata
import org.json.JSONObject

class NotificationSettings(context: Context) {
    private val context = context.applicationContext
    internal val prefs = context.getSharedPreferences("notify-notifications-v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun rememberedState() = context.getSharedPreferences("notify-remembered-v1", Context.MODE_PRIVATE).getString("state", null)
    private fun authorityOperation(): Long = try {
        rememberedState()?.let { JSONObject(it).getLong("operation") } ?: 0
    } catch (_: Exception) { -1 }
    fun routing(record: Relationship): PushMetadata? {
        if (prefs.getString("routingOwner", null) != record.toString()) return null
        return prefs.getString("routing", null)?.let { PushMetadata.parse(JSONObject(it)) }
    }
    fun routing(record: Relationship, metadata: PushMetadata) = save(prefs.edit()
        .putString("routingOwner", record.toString()).putString("routing", JSONObject().put("enabled", metadata.enabled)
            .put("registrationGeneration", metadata.registrationGeneration ?: JSONObject.NULL).toString())
        .putBoolean("routePossible", metadata.enabled))
    fun advanceAuthority(previous: Long) {
        if (!alertsBlocked && prefs.getBoolean("consent", false) && prefs.getLong("authorityOperation", 0) == previous)
            save(prefs.edit().putLong("authorityOperation", authorityOperation()))
    }
    val channelID: String
        get() = if (prefs.getBoolean("longerVibration", manager.getNotificationChannel(CHANNEL) == null))
            LONGER_CHANNEL else CHANNEL

    fun state(): NotificationState = NotificationState(
        consent = prefs.getBoolean("consent", false) && notificationAuthorityCurrent(rememberedState(),
            prefs.getLong("authorityOperation", 0), alertsBlocked, System.currentTimeMillis()),
        permission = permissionGranted() && manager.areNotificationsEnabled(),
        channel = (manager.getNotificationChannel(channelID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >
            NotificationManager.IMPORTANCE_NONE,
        registered = prefs.getString("registered", "")?.let { it.isNotEmpty() && it == prefs.getString("tokenHash", "") } == true,
        cleanupPending = !prefs.getBoolean("consent", false) && prefs.getBoolean("routePossible", false),
        setupOffered = prefs.getBoolean("setupOffered", false),
        longerVibration = channelID == LONGER_CHANNEL,
    )
    fun permissionGranted() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun markSetupOffered() = save(prefs.edit().putBoolean("setupOffered", true))

    fun createChannel() {
        val id = channelID
        if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(newChannel(id))
    }

    fun useLongerVibration() {
        val previous = manager.getNotificationChannel(channelID)
        if (manager.getNotificationChannel(LONGER_CHANNEL) == null) {
            val channel = newChannel(LONGER_CHANNEL, previous?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)
            previous?.let {
                channel.setSound(it.sound, it.audioAttributes)
                channel.enableVibration(it.shouldVibrate())
                channel.lockscreenVisibility = it.lockscreenVisibility
                channel.setShowBadge(it.canShowBadge())
                channel.enableLights(it.shouldShowLights())
                channel.lightColor = it.lightColor
                channel.group = it.group
            }
            manager.createNotificationChannel(channel)
        }
        val destination = checkNotNull(manager.getNotificationChannel(LONGER_CHANNEL)) {
            "Could not create the longer-vibration channel. Your previous selection was kept."
        }
        check(previous == null || (
            destination.importance <= previous.importance &&
            (previous.sound != null || destination.sound == null) &&
            (previous.shouldVibrate() || !destination.shouldVibrate()) &&
            (previous.canBypassDnd() || !destination.canBypassDnd()) &&
            (previous.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } != true ||
                destination.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true)
        )) {
            "The longer-vibration channel has louder Android settings. Your previous selection was kept. " +
                "Open Android notification settings and match its disabled or muted choices before trying again."
        }
        save(prefs.edit().putBoolean("longerVibration", true))
    }

    private fun newChannel(id: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) =
        NotificationChannel(id, if (id == LONGER_CHANNEL) "Mac attention (longer vibration)" else "Mac attention", importance).apply {
            description = "Private reminders to open Notify and check your Mac"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            enableVibration(true)
            vibrationPattern = if (id == LONGER_CHANNEL) longArrayOf(0, 300, 180, 450)
                else longArrayOf(0, 65, 85, 65, 150, 110)
            setSound(Uri.parse("android.resource://${context.packageName}/raw/notify_attention"),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
    suspend fun beginRegistration(): String {
        createChannel()
        check(permissionGranted() && state().permission && state().channel) {
            "Android notifications are blocked. Allow this channel in system settings first."
        }
        alertsBlocked = false
        save(prefs.edit().putBoolean("consent", true).putLong("authorityOperation", authorityOperation()).putString("registered", ""))
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
        synchronized(deliveryLock) { alertsBlocked = true; manager.cancel(NOTIFICATION_ID) }
        val hadConsent = prefs.getBoolean("consent", false)
        save(prefs.edit().putBoolean("consent", false).putString("registered", ""))
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
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun save(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Could not save notification preferences. Check phone storage." }
    }
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CHANNEL = "notify-attention-v1"
        private const val LONGER_CHANNEL = "notify-attention-long-v1"
        const val NOTIFICATION_ID = 1
        const val OPEN_ATTENTION = "app.notify.android.OPEN_ATTENTION"
        @Volatile var foreground = false
        @Volatile var alertsBlocked = false
        internal val deliveryLock = Any()
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
