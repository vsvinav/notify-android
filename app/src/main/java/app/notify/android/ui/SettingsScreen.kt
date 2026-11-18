package app.notify.android.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState
import app.notify.android.controller.ControllerViewModel
import app.notify.android.controller.SERVICE_ORIGIN

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Settings(
    state: ControllerState, controller: ControllerViewModel, activity: Activity, enableAlerts: () -> Unit,
    section: SettingsSection, navigate: (SettingsSection) -> Unit,
) {
    var forget by remember { mutableStateOf(false) }
    val reconnect = !state.busy && state.relationship?.enrolled == true && state.account != null &&
        state.relationship.account == state.account
    key(section) { Page {
        when (section) {
            SettingsSection.Overview -> {
                SectionTitle("Connection")
                Text("Your Mac", style = MaterialTheme.typography.titleMedium)
                Supporting(connectionLabel(state))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::retry, enabled = reconnect) { Text("Reconnect") }
                    TextButton(onClick = { navigate(SettingsSection.Connection) }) { Text("Connection details") }
                }
                HorizontalDivider()
                SectionTitle("Notifications")
                SettingsLink("Mac attention alerts", if (state.notifications.enabled) "On · Android controls sound and vibration"
                    else state.notifications.description) { navigate(SettingsSection.Notifications) }
                Supporting("Alerts when an agent needs you. Android’s notification settings and Do Not Disturb remain in control.")
                HorizontalDivider()
                SectionTitle("Account & pairing")
                SettingsLink("Pairing & security", if (state.relationship != null) "Saved Mac identity and device keys"
                    else "No Mac paired") { navigate(SettingsSection.Pairing) }
                SettingsLink("Connection diagnostics", "Optional, local-only capture") { navigate(SettingsSection.Diagnostics) }
                if (state.account != null) OutlinedButton(onClick = controller::signOut, enabled = !state.busy) {
                    Text("Sign out · keep pairing")
                } else Action("Sign in with a passkey", !state.busy) { controller.signIn(activity, false, "") }
                TextButton(onClick = controller::notices, enabled = !state.busy) { Text("Third-party notices") }
            }
            SettingsSection.Connection -> {
                Heading("Your Mac")
                Supporting(state.status.message)
                Supporting(connectionLabel(state))
                Supporting("The encrypted channel and Mac updates are separate. A connected channel does not enable actions until fresh, exact-target state is available.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action("Reconnect", reconnect, controller::retry)
                    OutlinedButton(onClick = controller::disconnect) { Text("Disconnect") }
                }
                if (state.relationship == null) Supporting("Return to chat to scan your Mac’s pairing QR.")
                else if (state.account == null || state.account != state.relationship.account)
                    Supporting("Sign in to the account used for this saved pairing before reconnecting.")
                else if (!state.relationship.enrolled) Supporting("Enrollment is not confirmed. Return to Mac approval to compare complete keys or recover with a fresh QR.")
                Evidence("Service", SERVICE_ORIGIN)
                SettingsLink("Pairing & security", "Complete public keys and account controls") { navigate(SettingsSection.Pairing) }
                SettingsLink("Connection diagnostics", "Capture connection events locally") { navigate(SettingsSection.Diagnostics) }
            }
            SettingsSection.Notifications -> {
                Heading("Mac attention alerts")
                Text(if (state.notifications.enabled) "On" else "Not enabled",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Supporting(state.notifications.description)
                EnableAlertsAction(if (state.notifications.consent) "Refresh notification registration" else "Enable notifications",
                    state, enableAlerts)
                TextButton(onClick = controller::openNotificationSettings) { Text("Android sound, vibration & permission settings") }
                Text(if (state.notifications.longerVibration) "Vibration: longer two-pulse pattern"
                    else "Vibration: original short pattern", style = MaterialTheme.typography.titleSmall)
                if (!state.notifications.longerVibration) OutlinedButton(
                    onClick = controller::useLongerVibration, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Use longer vibration") }
                Supporting("Longer uses two pulses: 300 ms, a short pause, then 450 ms. Switching keeps muted or disabled alerts off. Android controls vibration strength and Do Not Disturb.")
                if (state.notifications.consent || state.notifications.cleanupPending) OutlinedButton(
                    onClick = controller::disableNotifications, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.notifications.cleanupPending) "Finish removing notification routing" else "Turn notifications off")
                }
                HorizontalDivider()
                Supporting("Optional alerts use Google Firebase delivery. Only a generic attention signal is sent—never chat, commands or request text. Tapping opens Notify; it cannot approve anything.")
                Supporting("Notify includes a soft two-note sound. Android’s channel, silent mode and Do Not Disturb remain in control. Enabled does not verify physical delivery, sound or vibration.")
                Supporting("Delivery is best effort; your Mac must be awake and online. If a token rotates while you’re signed out, sign in and refresh registration here.")
            }
            SettingsSection.Pairing -> {
                Heading("Pairing & security")
                Supporting(if (state.account == null) "Signed out. Saved Mac trust is unchanged." else
                    if (state.relationship != null && state.account != state.relationship.account) "Signed in to a different account. It cannot access this saved pairing."
                    else "Signed in for this app session.")
                if (state.account == null) Action("Sign in with a passkey", !state.busy) { controller.signIn(activity, false, "") }
                state.relationship?.let { record ->
                    PublicKeys(record)
                    Supporting(if (record.enrolled) "Enrollment recorded. Only an approved encrypted channel grants Mac access."
                        else "Enrollment not confirmed. Do not assume the Mac approved this phone.")
                } ?: Supporting("No saved Mac pairing. Return to chat to scan a Mac QR.")
                HorizontalDivider()
                if (state.account != null) OutlinedButton(onClick = controller::signOut, enabled = !state.busy) {
                    Text("Sign out · keep pairing")
                }
                if (state.relationship != null) OutlinedButton(onClick = { forget = true }, enabled = !state.busy) {
                    Text("Forget this phone’s pairing", color = MaterialTheme.colorScheme.error)
                }
            }
            SettingsSection.Diagnostics -> {
                Heading("Connection diagnostics")
                Supporting("Optional, local-only capture for 60 seconds. Keeps the latest 64 events in memory. " +
                    "No account or device IDs, addresses, keys, messages, or server error text. Nothing is uploaded or logged.")
                Supporting(if (state.connectionTrace.capturing) "Capturing · stops automatically" else "Not capturing")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action("Start capture", !state.connectionTrace.capturing, controller::startConnectionTrace)
                    OutlinedButton(onClick = controller::stopConnectionTrace, enabled = state.connectionTrace.capturing) { Text("Stop") }
                }
                Supporting("Start, wait for the connection to cycle, then Stop and Copy. Starting again clears the previous capture. " +
                    "Copy places only this diagnostic text on your clipboard.")
                if (state.connectionTrace.entries.isNotEmpty()) {
                    CopyButton(state.connectionTrace.text)
                    Evidence("Diagnostic trace", state.connectionTrace.text, code = true)
                }
            }
        }
    } }
    if (forget) Confirmation("Forget this pairing?", { forget = false }, { forget = false; controller.forget() }, !state.busy) {
        Text("Delete this phone’s saved relationship and AndroidKeyStore channel identity. This does not delete your account or revoke the phone on the Mac.")
        Text("Revoke the phone in Notify on your Mac too. Connecting again requires a new in-person pairing.")
        Text("If notifications were enabled, their routing must be removed while signed in before this key is deleted.")
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
