package app.notify.android.controller

import app.notify.android.remote.HttpFailure
import org.junit.Assert.*
import org.junit.Test

class ServiceFailuresTest {
    private val account = Account("issuer", "subject")
    private val relationship = Relationship(account, "mac", "pin", "device", "public", "Phone")
    private val state = ControllerState(account = account, relationship = relationship)

    @Test fun expiredPairingLinkDoesNotSignOutAValidAccountOrDeleteUnconfirmedIdentity() {
        val result = state.withServiceFailure(ServiceEndpointFailure("/v1/remote/pair",
            HttpFailure(401, "Mac enrollment unavailable.")))
        assertEquals(account, result.account)
        assertEquals(relationship, result.relationship)
        assertTrue(result.error!!.contains("pairing link"))
        assertFalse(result.error!!.contains("Account session expired"))
    }
    @Test fun onlySessionAndLogoutUnauthorizedResponsesEndTheAccountSession() {
        for (path in listOf("/v1/account/session", "/v1/account/logout")) {
            val result = state.withServiceFailure(ServiceEndpointFailure(path, HttpFailure(401, "Account sign-in required.")))
            assertNull(result.account)
            assertEquals(relationship, result.relationship)
        }
        for (path in listOf("/v1/account/login/verify", "/v1/account/register/verify", "/v1/remote/pair")) {
            assertEquals(account, state.withServiceFailure(ServiceEndpointFailure(path,
                HttpFailure(401, "Unavailable."))).account)
        }
    }
    @Test fun temporaryServiceFailureKeepsAccountAndPairing() {
        val result = state.withServiceFailure(ServiceEndpointFailure("/v1/account/session", HttpFailure(503, "Unavailable.")))
        assertEquals(account, result.account)
        assertEquals(relationship, result.relationship)
    }
}
