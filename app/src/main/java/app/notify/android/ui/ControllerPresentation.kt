package app.notify.android.ui

import app.notify.android.controller.ControllerState

internal enum class ControllerRoute { Pairing, SignIn, Trust, Notifications, Chat, Offline }

internal fun composerHeightLimit(availableHeightDp: Float, fontScale: Float): Float? {
    val limit = availableHeightDp * if (availableHeightDp < 420f || fontScale > 1.3f) .55f else .45f
    val scale = fontScale.coerceAtLeast(1f)
    val editor = 80f * scale
    val actions = maxOf(48f, 32f * scale + 16f)
    // Otherwise the action row and padding can leave the weighted editor with no viewport.
    return limit.takeIf { it >= editor + actions + 22f }
}

internal fun drawerKeepsPinnedChrome(availableHeightDp: Float, fontScale: Float): Boolean =
    availableHeightDp >= 360f * fontScale.coerceAtLeast(1f)

internal fun controllerRoute(state: ControllerState): ControllerRoute = when {
    state.relationship == null -> ControllerRoute.Pairing
    state.offlineReader && state.visibleSavedHistory.conversations.isNotEmpty() -> ControllerRoute.Offline
    state.account == null || state.account != state.relationship.account -> ControllerRoute.SignIn
    // Every handshake reports pending; authenticated history must not be replaced by setup.
    !state.relationship.enrolled || state.status.state == "pending" && state.workspace.snapshot == null -> ControllerRoute.Trust
    state.notificationSetupOpen || state.shouldOfferNotificationSetup() -> ControllerRoute.Notifications
    else -> ControllerRoute.Chat
}

internal fun connectionLabel(state: ControllerState): String = when (state.status.state) {
    "connected" -> {
        val workspace = state.workspace
        val received = workspace.receivedAt
        val workspaceFresh = received != null && state.now >= received && state.now - received < 10_000 &&
            workspace.snapshot?.connected == true &&
            (workspace.sessionID == null || workspace.detail != null && workspace.session?.connected == true)
        if (state.live && workspaceFresh) "Connected to Mac" else "Connected · updates stale"
    }
    "pending" -> if (state.workspace.snapshot == null) "Awaiting Mac approval" else "Waiting for Mac"
    "connecting" -> "Connecting"
    "error" -> "Connection error"
    else -> "Disconnected"
}
