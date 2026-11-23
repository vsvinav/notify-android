package app.notify.android.controller

import app.notify.android.remote.HttpFailure
import app.notify.android.remote.ServiceClient
import java.io.IOException
import org.json.JSONObject

class ServiceEndpointFailure(val endpoint: String, val failure: HttpFailure) : IOException(failure.message, failure) {
    val endsAccountSession get() = failure.status == 401 &&
        endpoint in setOf("/v1/account/session", "/v1/account/logout")
}

internal suspend fun ServiceClient.postScoped(endpoint: String, body: JSONObject): JSONObject =
    try { post(endpoint, body) }
    catch (error: HttpFailure) { throw ServiceEndpointFailure(endpoint, error) }

internal fun ControllerState.withServiceFailure(error: ServiceEndpointFailure): ControllerState {
    val updated = if (error.endsAccountSession) expireAccountSession() else this
    val message = when {
        error.endpoint == "/v1/account/logout" && error.failure.status == 401 ->
            "Already signed out. Your Mac pairing is still saved."
        error.endsAccountSession ->
            if (deviceAccess != null) "Passkey session expired. Remembered device access remains valid until its fixed expiry."
            else "Account session expired. Sign in again; your Mac pairing is still saved."
        error.endpoint == "/v1/remote/pair" && error.failure.status == 401 ->
            "Mac enrollment is unavailable or its authorization expired. Check Remote Access on your Mac and get a fresh pairing link. " +
                "The unconfirmed identity was kept; explicitly forget it before pairing again. Nothing was resent."
        error.endpoint.startsWith("/v1/account/") && error.failure.status == 401 ->
            "Passkey verification failed or the ceremony expired. Start passkey sign-in again. ${error.failure.message}"
        error.endpoint == "/v1/remote/pair" && error.failure.status == 404 ->
            "Mac enrollment is missing. Check Remote Access on your Mac. Pairing was kept. ${error.failure.message}"
        error.failure.status >= 500 ->
            "Notify service is temporarily unavailable. Retry without re-pairing. ${error.failure.message}"
        else -> error.failure.message ?: "The service request failed. Nothing was resent."
    }
    return updated.copy(error = message)
}
