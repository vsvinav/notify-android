package app.notify.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState
import app.notify.android.controller.ControllerViewModel

@Composable
internal fun rememberEnableAlerts(controller: ControllerViewModel, activity: Activity): () -> Unit {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.notificationPermissionResult(granted)
    }
    return {
        if (controller.requestNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else controller.notificationPermissionResult(true)
        }
    }
}

@Composable
internal fun EnableAlertsAction(label: String, state: ControllerState, enableAlerts: () -> Unit) {
    Action(label, !state.busy && state.account != null && state.relationship?.enrolled == true &&
        state.relationship.account == state.account && !state.notifications.cleanupPending &&
        !state.notificationPermissionPending, enableAlerts)
}

@Composable
internal fun NotificationSetup(state: ControllerState, controller: ControllerViewModel, enableAlerts: () -> Unit) {
    LaunchedEffect(Unit) { controller.showNotificationSetup() }
    BackHandler { controller.finishNotificationSetup() }
    Page {
        Heading(if (state.notifications.enabled) "Alerts are on" else "Stay notified")
        Supporting(if (state.status.state == "connected") "Your Mac is connected. Notifications are optional."
            else "Your Mac pairing is saved. You can keep using Notify while the connection recovers.")
        Text(if (state.notifications.enabled) "Permission and notification registration are confirmed."
            else "Get a private alert when your Mac needs you, even when Notify isn’t open.",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Supporting("Alerts use Google Firebase delivery and contain no chat, commands or request text. Opening an alert never approves anything.")
        if (!state.notifications.enabled) {
            EnableAlertsAction("Enable alerts", state, enableAlerts)
            Supporting(state.notifications.description)
            Supporting("Android will ask for notification permission where required. If you decline, your Mac connection keeps working.")
            if (!state.notifications.permission)
                Supporting("If Android has already denied permission, allow notifications in Android settings, then return and tap Enable alerts. You can also choose Not now.")
            else if (!state.notifications.channel)
                Supporting("If the Mac attention channel is disabled, turn it on in Android settings before enabling alerts.")
            if (state.busy) Supporting("Enabling alerts… You can continue to chat while registration finishes.")
        }
        Supporting("Sound and vibration use the Mac attention channel. Android’s channel choices, silent mode and Do Not Disturb remain in control. No separate vibration permission dialog is needed.")
        TextButton(onClick = controller::openNotificationSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Android notification & vibration settings")
        }
        TextButton(onClick = controller::finishNotificationSetup, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.notifications.consent || state.busy) "Continue to chat" else "Not now")
        }
        Supporting("You can change alerts later in Notify Settings. This setup step won’t appear on every launch.")
    }
}
