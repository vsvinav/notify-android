package app.notify.android.controller

import kotlinx.coroutines.CancellationException

internal const val REMOTE_SIGNOUT_WARNING = "Signed out on this phone. Server authorization may remain active until it expires. Revoke this phone from your Mac to remove its authorization."

internal enum class SignOutStep(val recovery: String) {
    LocalNotifications("Could not confirm notifications are off on this phone. Disable Notify alerts in Android settings, check phone storage, then retry sign-out."),
    Cache("Saved conversations could not be cleared. Check device storage/security, then use Clear saved conversations in Settings."),
    Authorization(REMOTE_SIGNOUT_WARNING),
    NotificationRouting("Notification routing cleanup could not be confirmed. Sign in to the paired account and finish removing notification routing, or revoke this phone on your Mac."),
    Logout("Service logout could not be confirmed. Local sign-in has ended. Sign in again before reconnecting."),
}

internal suspend fun signOutCleanup(
    disableNotificationsLocally: () -> Unit,
    eraseConversations: suspend () -> Unit,
    removeNotificationRouting: suspend () -> Unit,
    logout: suspend () -> Unit,
    endSession: () -> Unit,
    revokeAuthorizations: List<suspend () -> Unit> = emptyList(),
): List<Pair<SignOutStep, Exception>> {
    val failures = mutableListOf<Pair<SignOutStep, Exception>>()
    val steps: List<Pair<SignOutStep, suspend () -> Unit>> = listOf(
        SignOutStep.LocalNotifications to suspend { disableNotificationsLocally() },
        SignOutStep.Cache to eraseConversations,
    ) + revokeAuthorizations.map { SignOutStep.Authorization to it } + listOf(
        SignOutStep.NotificationRouting to removeNotificationRouting,
        SignOutStep.Logout to logout,
    )
    try {
        for ((step, action) in steps) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failures += step to error }
        }
    } finally {
        endSession()
    }
    return failures
}
