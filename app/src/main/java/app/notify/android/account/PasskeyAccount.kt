package app.notify.android.account

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import app.notify.android.controller.Account
import app.notify.android.controller.SERVICE_ORIGIN
import app.notify.android.controller.postScoped
import app.notify.android.remote.ServiceClient
import org.json.JSONObject
import org.json.JSONTokener

class PasskeyAccount(private val service: ServiceClient) {
    suspend fun authenticate(activity: Activity, register: Boolean, name: String): Account {
        require(!register || name.isNotBlank() && name.length <= 80) { "Enter an account name, up to 80 characters." }
        val mode = if (register) "register" else "login"
        val options = service.postScoped("/v1/account/$mode/options", if (register) JSONObject().put("name", name.trim()) else JSONObject())
        val rpID = if (register) options.getJSONObject("rp").getString("id") else options.getString("rpId")
        require(rpID == service.origin.host && service.origin.toString().removeSuffix("/") == SERVICE_ORIGIN) {
            "Passkey options belong to another service."
        }
        val manager = CredentialManager.create(activity)
        // No origin override: Credential Manager signs the real APK-key origin.
        val raw = if (register) {
            val response = manager.createCredential(activity, CreatePublicKeyCredentialRequest(options.toString()))
            check(response is CreatePublicKeyCredentialResponse) { "The provider did not create a passkey." }
            response.registrationResponseJson
        } else {
            val response = manager.getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString()))))
            val credential = response.credential
            check(credential is PublicKeyCredential) { "Choose a Notify account passkey." }
            credential.authenticationResponseJson
        }
        require(raw.toByteArray().size <= 32_000) { "The credential response is too large." }
        val parser = JSONTokener(raw)
        val credential = parser.nextValue()
        require(credential is JSONObject && parser.nextClean() == '\u0000' && credential.opt("type") == "public-key") {
            "The credential provider returned an invalid passkey response."
        }
        if (!register) {
            val handle = credential.getJSONObject("response").opt("userHandle")
            require(handle is String && handle.isNotBlank()) {
                "This passkey supplied no account handle. Choose an account passkey."
            }
        }
        return Account.parse(service.postScoped("/v1/account/$mode/verify", JSONObject().put("response", credential)).getJSONObject("account"))
    }
}
