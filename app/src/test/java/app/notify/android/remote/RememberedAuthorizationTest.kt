package app.notify.android.remote

import app.notify.android.controller.Account
import app.notify.android.controller.Relationship
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun rememberedExamples() = JSONObject(checkNotNull(
    RememberedAuthorizationTest::class.java.getResource("/remembered-auth.json")).readText())
internal fun rememberedRelationship(): Relationship {
    val p = rememberedExamples().getJSONObject("enableFinish").getJSONObject("response")
    return Relationship(Account.parse(p.getJSONObject("account")), p.getString("macID"),
        p.getString("macPublicKey"), p.getString("deviceID"), p.getString("publicKey"), p.getString("name"), true)
}

class RememberedAuthorizationTest {
    @Test fun proposalIsDurableBeforeLostFinishAndIsNeverReplayed() = runBlocking {
        val examples = rememberedExamples()
        var persisted: RememberProposal? = null
        var calls = 0
        assertThrows(java.io.IOException::class.java) {
            runBlocking {
                RememberApi({ path, _ ->
                    calls++
                    if (path.endsWith("/begin")) examples.getJSONObject("enableBegin").getJSONObject("response")
                    else {
                        assertNotNull(persisted)
                        throw java.io.IOException("Response lost after dispatch")
                    }
                }, { 0 }).enable(rememberedRelationship(), { true }, { persisted = it }) { byteArrayOf(1) }
            }
        }
        assertEquals(2, calls)
        assertEquals(RememberProposal.parse(examples.getJSONObject("enableFinish").getJSONObject("response"),
            rememberedRelationship()), persisted)
    }
    @Test fun failedProposalPersistenceNeverDispatchesFinish() = runBlocking {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RememberApi({ _, _ -> calls++; rememberedExamples().getJSONObject("enableBegin").getJSONObject("response") },
                    { 0 }).enable(rememberedRelationship(), { true }, { error("disk full") }) { byteArrayOf(1) }
            }
        }
        assertEquals(1, calls)
    }
    @Test fun everyNewProofRejectsWrongContextBeforeSigner() = runBlocking {
        val examples = rememberedExamples()
        val record = rememberedRelationship()
        val authorization = RememberProposal.parse(examples.getJSONObject("enableFinish").getJSONObject("response"), record).authorization
        for ((fixture, operation, args) in listOf(
            Triple("enableBegin", "enable", JSONObject().put("optIn", true)),
            Triple("phoneGrantBegin", "grant", JSONObject().put("role", "phone")),
            Triple("rememberedPushBegin", "push", examples.getJSONObject("rememberedPushBegin").getJSONObject("request")
                .let { JSONObject().put("token", it.get("token")).put("registrationGeneration", it.get("registrationGeneration")) }),
            Triple("revokeBegin", "revoke", JSONObject()),
        )) {
            val flow = examples.getJSONObject(fixture).getJSONObject("response")
            for (field in listOf("purpose", "scope", "version", "operation", "origin", "account", "macID",
                "macPublicKey", "deviceID", "publicKey", "generation", "authorizationExpiresAt",
                "challengeExpiresAt", "challenge") + args.keys().asSequence().toList()) {
                val changed = JSONObject(flow.toString()).put("challenge",
                    JSONObject(flow.getString("challenge")).put(field, "wrong").toString())
                var signed = false
                assertThrows(IllegalArgumentException::class.java) {
                    RememberProof.sign(changed, authorization, operation, args, 0, { 1 }, {
                        signed = true; byteArrayOf(1)
                    })
                }
                assertFalse("$fixture $field", signed)
            }
            var raw = ""
            RememberProof.sign(flow, authorization, operation, args, 0, { 1 }, {
                raw = it.toString(Charsets.UTF_8); byteArrayOf(1)
            })
            assertEquals("Notify remote authorization v1\u0000" + flow.getString("challenge"), raw)
        }
    }
    @Test fun elapsedProofExpiryAndCancellationBeforeFinishNeverDispatchMutation() = runBlocking {
        val examples = rememberedExamples()
        val record = rememberedRelationship()
        val authorization = RememberProposal.parse(examples.getJSONObject("enableFinish").getJSONObject("response"), record).authorization
        val flow = examples.getJSONObject("revokeBegin").getJSONObject("response")
        var signs = 0
        assertThrows(IllegalArgumentException::class.java) {
            RememberProof.sign(flow, authorization, "revoke", JSONObject(), 0, { 30000 }, { signs++; byteArrayOf(1) })
        }
        assertEquals(0, signs)
        var current = true
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RememberApi({ _, _ -> calls++; flow }, { 0 }).revoke(authorization, { current }) {
                    current = false; byteArrayOf(1)
                }
            }
        }
        assertEquals(1, calls)
    }
    @Test fun renewalPinsRawServerExpiryConnectionAndArgumentsWithoutClockSkewGuessing() {
        val examples = rememberedExamples()
        val authorization = RememberProposal.parse(examples.getJSONObject("enableFinish").getJSONObject("response"),
            rememberedRelationship()).authorization
        val lease = examples.getJSONObject("phoneGrantFinish").getJSONObject("response")
        val renewal = lease.getJSONObject("renewal")
        val args = JSONObject().put("role", "phone").put("connectionID", renewal.getString("id"))
        val raw = renewal.getString("challenge")
        assertArrayEquals(raw.toByteArray(), RememberProof.validate(raw, authorization, "renew", args, lease.getLong("expiresAt")))
        assertThrows(IllegalArgumentException::class.java) {
            RememberProof.validate(raw, authorization, "renew", args, lease.getLong("expiresAt") + 1)
        }
        args.put("connectionID", "A".repeat(43))
        assertThrows(IllegalArgumentException::class.java) { RememberProof.validate(raw, authorization, "renew", args, lease.getLong("expiresAt")) }
    }
    @Test fun sameTokenRegistrationUsesCapturedCasAndStaleCleanupDoesNotRefreshOrReplay() {
        val examples = rememberedExamples()
        val begin = examples.getJSONObject("accountPushBegin")
        val request = begin.getJSONObject("request")
        val oldGuard = request.getString("registrationGeneration")
        var calls = 0
        assertThrows(HttpFailure::class.java) {
            runBlocking {
                PushRegistration({ _, input ->
                    calls++
                    assertEquals(oldGuard, input.getString("registrationGeneration"))
                    throw HttpFailure(409, "stale guard")
                }, { 0 }).update(rememberedRelationship(), null, oldGuard) { error("Never sign stale cleanup") }
            }
        }
        assertEquals(1, calls)
    }
    @Test fun actualGuardedAccountProducerProofSignsOriginalBytes() = runBlocking {
        val examples = rememberedExamples()
        val begin = examples.getJSONObject("accountPushBegin")
        val token = begin.getJSONObject("request").getString("token")
        var signed = ""
        PushRegistration({ path, _ ->
            examples.getJSONObject(if (path.endsWith("begin")) "accountPushBegin" else "accountPushFinish")
                .getJSONObject("response")
        }, { examples.getLong("now") }).update(rememberedRelationship(), token,
            begin.getJSONObject("request").getString("registrationGeneration")) {
            signed = it.toString(Charsets.UTF_8); byteArrayOf(1)
        }
        assertEquals("Notify remote authorization v1\u0000" + begin.getJSONObject("response").getString("challenge"), signed)
    }
}
