package app.notify.android.connection

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

internal class BackgroundNotificationWatch(
    private val token: String,
    private val isCurrent: (String) -> Boolean,
    private val unavailableReason: () -> String?,
    private val stop: (String, String) -> Unit,
) : AutoCloseable {
    private var active = true

    fun changed() {
        if (!active || !isCurrent(token)) return
        val reason = unavailableReason() ?: return
        if (!active || !isCurrent(token)) return
        active = false
        stop(token, reason)
    }

    override fun close() { active = false }
}

internal class BackgroundNotificationReceiver(private val watch: BackgroundNotificationWatch) :
    BroadcastReceiver(), AutoCloseable {
    private var registeredContext: Context? = null

    fun register(context: Context) {
        check(registeredContext == null)
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(this, filter)
        registeredContext = context
        // Close the race between the initial permission check and receiver registration.
        watch.changed()
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action in actions) watch.changed()
    }

    override fun close() {
        watch.close()
        registeredContext?.unregisterReceiver(this)
        registeredContext = null
    }

    private companion object {
        val actions = setOf(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
            NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
    }
}
