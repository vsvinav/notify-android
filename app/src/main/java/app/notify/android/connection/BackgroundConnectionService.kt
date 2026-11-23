package app.notify.android.connection

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.notify.android.NotifyApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class BackgroundConnectionService : Service() {
    private val controller get() = (application as NotifyApplication).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token: String? = null
    private var notificationBlocks: BackgroundNotificationReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(TOKEN)
        if (intent?.action == STOP) {
            if (requested != null) controller.stopBackgroundConnection(requested)
            if (token == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (token != null) return START_NOT_STICKY
        val notification = BackgroundConnectionNotification(this)
        if (requested == null || !controller.consumeBackgroundStart(requested)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        token = requested
        try {
            notification.createChannel()
            check(notification.unavailableReason() == null) { notification.unavailableReason().orEmpty() }
            val initial = notification.build(requested, controller.state.value.status)
            if (Build.VERSION.SDK_INT >= 29) startForeground(BackgroundConnectionNotification.ID, initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(BackgroundConnectionNotification.ID, initial)
            controller.backgroundServiceStarted(requested)
            val receiver = BackgroundNotificationReceiver(BackgroundNotificationWatch(requested,
                { token == it && controller.isBackgroundSession(it) }, notification::unavailableReason,
                controller::backgroundServiceFailed))
            notificationBlocks = receiver
            receiver.register(this)
            scope.launch {
                controller.state.map { it.backgroundRunning to it.status }.distinctUntilChanged().collect { (running, status) ->
                    if (!running || !controller.isBackgroundSession(requested)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val unavailable = notification.unavailableReason()
                        if (unavailable != null) controller.backgroundServiceFailed(requested, unavailable)
                        else try {
                            getSystemService(NotificationManager::class.java)
                                .notify(BackgroundConnectionNotification.ID, notification.build(requested, status))
                        } catch (_: SecurityException) {
                            controller.backgroundServiceFailed(requested,
                                "Background notification is unavailable. Check Android notification settings, then enable again.")
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            controller.backgroundServiceFailed(requested,
                notification.unavailableReason() ?: "Android could not start the background connection. Open Notify and try again.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationBlocks?.close()
        notificationBlocks = null
        scope.cancel()
        val stopped = token
        token = null
        stopped?.let(controller::backgroundServiceStopped)
        super.onDestroy()
    }

    companion object {
        const val START = "app.notify.android.START_BACKGROUND_CONNECTION"
        const val STOP = "app.notify.android.STOP_BACKGROUND_CONNECTION"
        const val TOKEN = "background-session"
    }
}
