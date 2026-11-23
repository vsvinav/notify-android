package app.notify.android.controller

import app.notify.android.remote.*
import org.junit.Assert.*
import org.junit.Test

class RememberedStoreTest {
    @Test fun signoutAttemptsBothCapturedGenerationsAndRoutingDespiteIndependentFailures() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val failures = signOutCleanup(
            disableNotificationsLocally = { calls += "local" },
            eraseConversations = { calls += "cache"; throw java.io.IOException("cache") },
            revokeAuthorizations = listOf(
                { calls += "old committed"; throw java.io.IOException("offline") },
                { calls += "new pending" },
            ),
            removeNotificationRouting = { calls += "captured push"; throw java.io.IOException("stale guard") },
            logout = { calls += "old client logout" },
            endSession = { calls += "end" },
        )
        assertEquals(listOf("local", "cache", "old committed", "new pending", "captured push", "old client logout", "end"), calls)
        assertEquals(listOf(SignOutStep.Cache, SignOutStep.Authorization, SignOutStep.NotificationRouting), failures.map { it.first })
    }
    private class Storage : RelationshipStorage {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String?) { check(!fail) { "disk full" }; this.value = value }
    }
    private val record = rememberedRelationship()
    private val fixture = rememberedExamples()
    private fun proposal() = RememberProposal.parse(fixture.getJSONObject("enableFinish").getJSONObject("response"), record)

    @Test fun noMigrationAndPendingIsNotAuthorityAcrossRestart() {
        val disk = Storage()
        val store = RememberedStore(disk)
        assertNull(store.state.committed)
        val operation = store.begin(record)
        store.propose(operation, proposal())
        val restarted = RememberedStore(disk)
        assertNull(restarted.state.committed)
        assertEquals(proposal(), restarted.state.pending)
    }
    @Test fun signedOutAndExpiredLocalRecordsSuppressLatePushesAcrossRestart() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val operation = store.begin(record)
        store.propose(operation, proposal())
        store.confirm(operation, proposal().authorization)
        val expiry = proposal().authorization.expiresAt
        assertTrue(app.notify.android.notifications.notificationAuthorityCurrent(disk.value, operation, false, expiry - 1))
        assertFalse(app.notify.android.notifications.notificationAuthorityCurrent(disk.value, operation, false, expiry))
        assertFalse(app.notify.android.notifications.notificationAuthorityCurrent(disk.value, operation, true, expiry - 1))
        store.off()
        assertFalse(app.notify.android.notifications.notificationAuthorityCurrent(disk.value, operation, false, expiry - 1))
        assertFalse(app.notify.android.notifications.notificationAuthorityCurrent("broken", operation, false, expiry - 1))
    }
    @Test fun localOffSurvivesRestartAndRejectsLateApproval() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val operation = store.begin(record)
        store.propose(operation, proposal())
        store.off()
        assertFalse(store.confirm(operation, proposal().authorization))
        assertNull(RememberedStore(disk).state.pending)
        assertNull(RememberedStore(disk).state.committed)
    }
    @Test fun renewalKeepsOldCommitSeparateAndStaleOperationsCannotReplaceIt() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val first = store.begin(record)
        store.propose(first, proposal())
        assertTrue(store.confirm(first, proposal().authorization))
        val next = store.begin(record)
        val renewal = proposal().copy(id = "A".repeat(43),
            authorization = proposal().authorization.copy(generation = "A".repeat(43)))
        store.propose(next, renewal)
        assertEquals(proposal().authorization, store.state.committed)
        assertEquals(renewal, RememberedStore(disk).state.pending)
        assertFalse(store.confirm(first, proposal().authorization))
        assertTrue(store.confirm(next, renewal.authorization))
        assertNull(store.state.pending)
    }
    @Test fun failedPersistenceStopsRamAuthorityWithoutClaimingDurableOff() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val operation = store.begin(record)
        store.propose(operation, proposal())
        store.confirm(operation, proposal().authorization)
        disk.fail = true
        assertThrows(IllegalStateException::class.java) { store.off() }
        assertNull(store.state.committed)
        assertFalse(store.current(operation, record))
        assertTrue(store.persistenceFailed)
    }
    @Test fun lostFinishReconcilesOnlyExactLocallyProposedGeneration() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val operation = store.begin(record)
        store.propose(operation, proposal())
        val restored = RememberedStore(disk)
        restored.reconcile(restored.state.operation, record, fixture.getJSONObject("committedStatus"))
        assertEquals(proposal().authorization, restored.state.committed)
        store.off()
        store.reconcile(store.state.operation, record, fixture.getJSONObject("committedStatus"))
        assertNull(store.state.committed)
    }
    @Test fun cookieExpiryKeepsMatchingDeviceIdentityButWrongAccountAndSignoutDoNot() {
        val authorization = proposal().authorization.copy(expiresAt = System.currentTimeMillis() + 60_000)
        val state = ControllerState(account = record.account, relationship = record,
            remembered = RememberedState(committed = authorization))
        assertEquals(record.account, state.expireAccountSession().connectionAccount)
        assertNull(state.copy(account = Account(record.account.issuer, "different")).connectionAccount)
        assertNull(state.endAccountSession().connectionAccount)
        assertNull(state.copy(account = null, relationship = record.copy(publicKey = "different")).connectionAccount)
        assertEquals(app.notify.android.ui.ControllerRoute.Chat, app.notify.android.ui.controllerRoute(state.expireAccountSession()))
        assertEquals(record, state.copy(account = null, foreground = true).reconnectable())
        assertTrue(state.copy(account = null, backgroundRunning = true).keepTransportWhenHidden)
        assertNotNull(state.copy(account = null).panelDraftKey("tab", "form"))
        assertNull(state.endAccountSession().panelDraftKey("tab", "form"))
    }
    @Test fun committedStatusAndNewPendingDoNotEraseOldApprovedGeneration() {
        val disk = Storage()
        val store = RememberedStore(disk)
        val first = store.begin(record)
        store.propose(first, proposal())
        store.confirm(first, proposal().authorization)
        val next = store.begin(record)
        val renewal = proposal().copy(id = "A".repeat(43),
            authorization = proposal().authorization.copy(generation = "A".repeat(43)))
        store.propose(next, renewal)
        val status = org.json.JSONObject(fixture.getJSONObject("committedStatus").toString())
        val pending = org.json.JSONObject(fixture.getJSONObject("awaitingMacStatus").getJSONObject("pending").toString())
            .put("generation", renewal.authorization.generation).put("id", renewal.id)
        store.reconcile(next, record, status.put("pending", pending))
        assertEquals(proposal().authorization, store.state.committed)
        assertEquals(renewal, store.state.pending)
        assertEquals(2, store.off().authorizations.size)
        assertTrue(RememberedStore(disk).state.authorizations.isEmpty())
    }
}
