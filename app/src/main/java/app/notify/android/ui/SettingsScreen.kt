package app.notify.android.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.notify.android.controller.ControllerState
import app.notify.android.controller.ControllerViewModel
import app.notify.android.controller.SERVICE_ORIGIN
import app.notify.android.controller.PhoneAppearance

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Settings(
    state: ControllerState, controller: ControllerViewModel, activity: Activity, enableAlerts: () -> Unit,
    section: SettingsSection, navigate: (SettingsSection) -> Unit,
    enableBackground: () -> Unit = {},
) {
    var forget by remember { mutableStateOf(false) }
    val reconnect = !state.busy && state.connectionAccount != null
    key(section) { Page {
        when (section) {
            SettingsSection.Overview -> {
                SectionTitle("Appearance · this phone")
                AppearanceChoices(state.appearance, controller::appearance)
                Supporting("Only Notify on this phone. System follows Android; your Mac and Android’s global settings are unchanged.")
                HorizontalDivider()
                Supporting("Saved conversations are encrypted on this phone. Signing out or forgetting the pairing clears them; disconnecting does not.")
                OutlinedButton(onClick = controller::clearSavedConversations, enabled = !state.busy) {
                    Text("Clear saved conversations")
                }
                SectionTitle("Connection")
                Text("Your Mac", style = MaterialTheme.typography.titleMedium)
                Supporting(connectionLabel(state))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::retry, enabled = reconnect) { Text("Reconnect") }
                    TextButton(onClick = { navigate(SettingsSection.Connection) }) { Text("Connection details") }
                }
                BackgroundConnectionSetting(state, controller, enableBackground)
                HorizontalDivider()
                CustomDashboardSetting(state, controller::customDashboard, controller::panelMessageShown)
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
                if (state.account != null || state.remembered.authorizations.isNotEmpty() || state.rememberedStorageUnconfirmed) OutlinedButton(onClick = controller::signOut) {
                    Text(if (state.rememberedStorageUnconfirmed) "Retry saving sign-out" else "Sign out and stop remembering")
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
                BackgroundConnectionSetting(state, controller, enableBackground)
                if (state.relationship == null) Supporting("Return to chat to scan your Mac’s pairing QR.")
                else if (state.connectionAccount == null)
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
                Supporting(if (state.account == null && state.deviceAccess != null) "Remembered device access · no passkey account session." else
                    if (state.account == null) "Signed out. Saved Mac trust is unchanged." else
                    if (state.relationship != null && state.account != state.relationship.account) "Signed in to a different account. It cannot access this saved pairing."
                    else "Signed in for this app session.")
                if (state.account == null) Action("Sign in with a passkey", !state.busy) { controller.signIn(activity, false, "") }
                state.relationship?.let { record ->
                    PublicKeys(record)
                    Supporting(if (record.enrolled) "Enrollment recorded. Only an approved encrypted channel grants Mac access."
                        else "Enrollment not confirmed. Do not assume the Mac approved this phone.")
                } ?: Supporting("No saved Mac pairing. Return to chat to scan a Mac QR.")
                if (state.relationship?.enrolled == true) {
                    HorizontalDivider()
                    Text("Remember this phone for 30 days", style = MaterialTheme.typography.titleMedium)
                    Supporting("Optional · off by default. Use a fresh passkey, then approve a new request on your Mac. This phone’s existing key can reconnect without a passkey until the fixed expiry, including after restarting Notify.")
                    Supporting("Anyone able to use this phone may reach your Mac during that time. Pairing, account changes and trust changes still require a passkey. Background connection is a separate choice.")
                    state.remembered.committed?.let {
                        Evidence("Approved until", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.expiresAt)))
                    }
                    state.remembered.pending?.let {
                        Supporting("Pending · not new access. Compare the complete phone key above with the new Mac confirmation.")
                        Evidence("Proposed authorization ends", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.authorization.expiresAt)))
                        Evidence("Mac confirmation deadline", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.expiresAt)))
                    }
                    Supporting(state.rememberMessage ?: if (state.remembered.committed == null) "Off · not remembered." else "On · expiry never extends automatically.")
                    Action(if (state.remembered.committed == null) "Remember with a fresh passkey" else "Request a new 30 days",
                        !state.busy && !state.rememberedStorageUnconfirmed && (state.remembered.pending == null ||
                            state.remembered.pending.expiresAt <= System.currentTimeMillis()) &&
                            (state.account == null || state.account == state.relationship.account)) { controller.rememberPhone(activity) }
                    if (state.remembered.authorizations.isNotEmpty()) TextButton(
                        onClick = controller::checkRemembered, enabled = !state.busy) { Text("Check Mac confirmation") }
                }
                HorizontalDivider()
                if (state.account != null || state.remembered.authorizations.isNotEmpty() || state.rememberedStorageUnconfirmed) OutlinedButton(onClick = controller::signOut) {
                    Text(if (state.rememberedStorageUnconfirmed) "Retry saving sign-out" else "Sign out and stop remembering")
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
        Text("Delete this phone’s saved conversations, relationship and AndroidKeyStore channel identity. This does not delete your account or revoke the phone on the Mac.")
        Text("Revoke the phone in Notify on your Mac too. Connecting again requires a new in-person pairing.")
        Text("Notify first stops remembering locally and tries to revoke remembered access and push routing. Offline cleanup may remain unconfirmed; revoke this phone from your Mac too.")
    }
}

@Composable
internal fun CustomDashboardSetting(state: ControllerState, onChange: (Boolean) -> Unit, dismiss: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(state.dashboardEnabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Custom dashboard", style = MaterialTheme.typography.titleMedium)
            Supporting(if (state.dashboardEnabled) "On · this phone" else "Off · optional")
        }
        Switch(checked = state.dashboardEnabled, onCheckedChange = null)
    }
    Supporting("Use your own trusted local provider on your Mac. Separate setup is required; Notify does not include a work provider. Turning this off leaves pairing, chat and notifications unchanged.")
    if (state.dashboardEnabled && !state.panel.available)
        Supporting(state.panel.reason.ifBlank { "Connect your Mac to discover its configured provider. The Dashboard appears in the drawer when available." })
    if (state.panel.pending?.sending == true)
        Supporting("An earlier Send is awaiting its result. Turning off cannot undo work already dispatched. Nothing will be resent.")
    state.panel.message?.let { message ->
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = { dismiss(message) }) { Text("Dismiss dashboard message") }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceChoices(value: PhoneAppearance, change: (PhoneAppearance) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneAppearance.entries.forEach { mode ->
            FilterChip(selected = value == mode, onClick = { change(mode) }, label = { Text(mode.name) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
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
