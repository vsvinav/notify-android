package app.notify.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState
import app.notify.android.controller.ControllerViewModel

@Composable
internal fun rememberEnableBackgroundConnection(controller: ControllerViewModel, activity: Activity): () -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { controller.backgroundPermissionResult(it, granted) }
        pending = null
    }
    return {
        controller.requestBackgroundPermission()?.let { id ->
            pending = id
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else {
                controller.backgroundPermissionResult(id, true)
                pending = null
            }
        }
    }
}

@Composable
internal fun BackgroundConnectionSetting(state: ControllerState, controller: ControllerViewModel, enable: () -> Unit) {
    val on = state.backgroundRunning || state.backgroundStarting
    val canEnable = !state.busy && !state.backgroundPermissionPending && state.foreground &&
        state.status.state == "connected" && state.connectionAccount != null
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(value = on, enabled = on || canEnable, role = Role.Switch,
            onValueChange = { if (it) enable() else controller.stopBackgroundConnection() })
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Keep connected in background", style = MaterialTheme.typography.titleMedium)
            Supporting(when {
                state.backgroundStarting -> "Starting…"
                state.backgroundRunning && state.status.state == "connected" -> "On · Connected"
                state.backgroundRunning -> "On · Reconnecting"
                state.backgroundPermissionPending -> "Waiting for notification permission"
                else -> "Off · Connect only while Notify is open"
            })
        }
        Switch(checked = on, onCheckedChange = null, enabled = on || canEnable)
    }
    Supporting("Optional, best effort. Keeps this Mac channel while you switch apps; live views pause until you return. Stop ends the connection, not your account or pairing.")
    if (!on && !canEnable && !state.backgroundPermissionPending)
        Supporting("Connect to your paired Mac first, then enable here.")
    Supporting("This setting does not extend account or remembered-phone authorization. Android stops, network loss and Mac sleep can interrupt the connection. Stop keeps your pairing and remembered authorization.")
    Supporting("Uses a separate quiet notification with Stop. Allowing notification permission here does not enable Mac attention alerts or change their vibration. Enable again after a process restart.")
    TextButton(onClick = controller::openBackgroundSettings, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Android background notification settings")
    }
}
