package app.notify.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.notify.android.remote.PairingLink
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
internal fun PairingSetup(state: ControllerState, controller: ControllerViewModel, activity: Activity) {
    var scanning by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var phoneName by remember { mutableStateOf("Android phone") }
    var createAccount by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf("") }
    var afterSignIn by remember { mutableStateOf(false) }
    fun accept(raw: String) {
        try {
            PairingLink.parse(raw, SERVICE_ORIGIN.toHttpUrl())
            invitation = raw
            paste = false
            problem = null
            controller.clearError()
        } catch (invalid: IllegalArgumentException) { problem = invalid.message }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        denied = !allowed
        scanning = allowed
    }
    LaunchedEffect(afterSignIn, state.account, state.busy, state.foreground, state.error) {
        if (afterSignIn && !state.busy) {
            if (state.error != null) afterSignIn = false
            else if (state.account != null && state.foreground) {
                afterSignIn = false
                invitation?.let { controller.pair(it, phoneName) }
            }
        }
    }
    BackHandler(enabled = !scanning && (invitation != null || paste)) {
        if (!state.busy) { invitation = null; paste = false; afterSignIn = false; problem = null }
    }
    if (scanning) {
        QrScanner(onScanned = { scanning = false; accept(it) }, onCancel = { scanning = false })
        return
    }
    Page {
        Heading(if (invitation == null) "Connect to your Mac" else "Your Mac is ready to pair")
        Supporting("On your Mac, open Notify → Internet access and enable internet. Scan the pairing QR shown there.")
        if (invitation == null) {
            Action("Scan Mac QR", !state.busy) {
                problem = null
                if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                else permission.launch(Manifest.permission.CAMERA)
            }
            Supporting("The camera is used only while scanning. Images stay on this phone.")
            if (denied) Supporting("Camera permission was not granted. Tap Scan Mac QR to try again, allow Camera in Android settings, or paste the pairing link below.")
            TextButton(onClick = { paste = !paste; problem = null }, enabled = !state.busy) { Text("Paste pairing link instead") }
            if (paste) {
                OutlinedTextField(pasted, { if (it.length <= 2048) pasted = it }, label = { Text("Complete pairing link") },
                    enabled = !state.busy, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { accept(pasted) }, enabled = !state.busy && pasted.isNotBlank()) { Text("Use pairing link") }
            }
        } else {
            Evidence("Service", SERVICE_ORIGIN)
            Supporting("Use your Notify account passkey, then compare this phone’s complete public key on your Mac. Scanning alone never approves a phone.")
            if (state.account != null) Supporting("You’re signed in. Your existing account will be used.")
            if (rename) OutlinedTextField(phoneName, { if (it.length <= 80) phoneName = it },
                label = { Text("Phone name") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            else TextButton(onClick = { rename = true }, enabled = !state.busy) { Text("Rename Android phone (optional)") }
            Action(if (state.account == null) "Continue with a passkey" else "Connect to this Mac",
                !state.busy && phoneName.isNotBlank()) {
                if (state.account == null) { afterSignIn = true; controller.signIn(activity, false, "") }
                else controller.pair(invitation!!, phoneName)
            }
            if (state.account != null) TextButton(onClick = {
                afterSignIn = true; controller.signIn(activity, false, "")
            }, enabled = !state.busy && phoneName.isNotBlank()) { Text("Verify passkey again") }
            if (state.account == null) {
                TextButton(onClick = { createAccount = !createAccount }, enabled = !state.busy) { Text("New to Notify? Create an account") }
                if (createAccount) {
                    OutlinedTextField(accountName, { if (it.length <= 80) accountName = it }, label = { Text("Account name") },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { afterSignIn = true; controller.signIn(activity, true, accountName) },
                        enabled = !state.busy && accountName.isNotBlank() && phoneName.isNotBlank()) { Text("Create account with a passkey") }
                }
            }
            TextButton(onClick = { invitation = null; afterSignIn = false; problem = null }, enabled = !state.busy) { Text("Scan a different QR") }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Supporting("This app connects to $SERVICE_ORIGIN. A QR for another service cannot change your account or saved Mac trust.")
    }
}
