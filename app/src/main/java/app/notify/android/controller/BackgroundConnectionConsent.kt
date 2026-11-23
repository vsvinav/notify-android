package app.notify.android.controller

import java.util.UUID

/** One foreground enable gesture, never persisted across process death. */
internal class BackgroundConnectionConsent {
    private data class Request(val id: String, val account: Account, val pairing: Relationship)
    private var pending: Request? = null
    private var authorized: Request? = null

    fun begin(state: ControllerState): String {
        check(pending == null && authorized == null && eligible(state) && !state.backgroundRunning) {
            "Connect to your paired Mac in Notify before enabling background connection."
        }
        return UUID.randomUUID().toString().also {
            pending = Request(it, checkNotNull(state.connectionAccount), checkNotNull(state.relationship))
        }
    }

    fun permissionResult(id: String, granted: Boolean, state: ControllerState): String? {
        val request = pending?.takeIf { it.id == id } ?: return null
        pending = null
        if (!granted || !valid(request, state)) return null
        authorized = request
        return request.id
    }

    fun consumeStart(id: String, state: ControllerState): Boolean {
        val request = authorized?.takeIf { it.id == id } ?: return false
        authorized = null
        return valid(request, state)
    }

    fun cancel() { pending = null; authorized = null }
    private fun valid(request: Request, state: ControllerState) =
        eligible(state) && state.connectionAccount == request.account && state.relationship == request.pairing
    private fun eligible(state: ControllerState) = state.foreground && state.status.state == "connected" &&
        state.connectionAccount != null
}
