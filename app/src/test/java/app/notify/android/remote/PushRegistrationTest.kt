package app.notify.android.remote

import app.notify.android.controller.Account
import app.notify.android.controller.Relationship
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PushRegistrationTest {
    private val record = Relationship(Account("https://notify.example", "owner"), "mac", "key", "phone", "key", "Phone", true)
    private val token = "synthetic-routing-token"
    private val now = 1_800_000_000_000L
    private fun flow(change: (JSONObject) -> Unit = {}): JSONObject {
        val challenge = JSONObject().put("purpose", "push").put("origin", record.account.issuer)
            .put("macID", record.macID).put("deviceID", record.deviceID).put("token", token).put("challenge", "n".repeat(43))
        change(challenge)
        return JSONObject().put("id", "i".repeat(43)).put("challenge", challenge.toString()).put("expiresAt", now + 30000)
    }
    @Test fun signsOnlyExactFreshDeviceAndRawTokenBinding() = runBlocking {
        val paths = mutableListOf<String>()
        val begin = flow()
        var signed = ""
        PushRegistration({ path, _ ->
            paths += path
            if (path.endsWith("begin")) begin else JSONObject().put("enabled", true)
        }, { now }).update(record, token) { bytes -> signed = bytes.toString(Charsets.UTF_8); byteArrayOf(1) }
        assertEquals("Notify remote authorization v1\u0000" + begin.getString("challenge"), signed)
        assertEquals(listOf("/v1/remote/push/begin", "/v1/remote/push/finish"), paths)
    }
    @Test fun wrongPurposeOriginDeviceMacOrTokenCannotBeSigned() {
        for (field in listOf("purpose", "origin", "deviceID", "macID", "token")) {
            var signed = false
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    PushRegistration({ _, _ -> flow { it.put(field, "other") } }, { now })
                        .update(record, token) { signed = true; byteArrayOf(1) }
                }
            }
            assertFalse(signed)
        }
    }
    @Test fun expiredOrMalformedChallengesAndProviderTokenAreRejectedWithoutReplay() {
        for (response in listOf(flow().put("expiresAt", now), flow().put("expiresAt", now + 30001),
            flow().put("challenge", "{}"), flow().put("id", "bad"))) {
            var calls = 0
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { PushRegistration({ _, _ -> calls++; response }, { now }).update(record, token) { error("must not sign") } }
            }
            assertEquals(1, calls)
        }
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PushRegistration({ _, _ -> calls++; flow() }, { now }).update(record, "bad\n") { byteArrayOf(1) } }
        }
        assertEquals(0, calls)
    }
    @Test fun removalSignsNullAndUncertainFinishIsNeverRepeated() {
        var calls = 0
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                PushRegistration({ _, _ ->
                    if (++calls == 1) flow { it.put("token", JSONObject.NULL) } else throw java.io.IOException("Lost response")
                }, { now }).update(record, null) { byteArrayOf(1) }
            }
        }
        assertEquals(2, calls)
    }
    @Test fun explicitAlreadyRevokedResponseAllowsLocalCleanupButNeverRegistration() = runBlocking {
        val absent = JSONObject().put("enabled", false).put("absent", true)
            .put("macID", record.macID).put("deviceID", record.deviceID)
        PushRegistration({ _, _ -> absent }, { now }).update(record, null) { error("No route exists to mutate") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PushRegistration({ _, _ -> absent }, { now }).update(record, token) { byteArrayOf(1) } }
        }
        Unit
    }
}
