package app.notify.android.controller

import android.app.Activity
import android.app.Application
import app.notify.android.notifications.NotificationState
import app.notify.android.remote.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual controller, store, HTTPS client and proof code; only Android effects and transport are injected. */
class RememberedControllerTest {
    @Test fun pendingApprovalThenCookieExpiryReplacesAccountTransportInForeground() = runBlocking {
        Harness().use { h ->
            h.start()
            val old = h.channels.single()
            assertNull(old.authorization)
            h.approve()
            h.expireCookie()
            h.until { h.controller.state.value.account == null }
            h.until { h.channels.size == 2 }
            assertTrue(old.closed)
            assertEquals(h.next, h.channels.last().authorization)
            assertTrue(h.channels.last().current())
            assertEquals(1, h.channels.count { !it.closed })
            assertEquals("connected", h.controller.state.value.status.state)
        }
    }

    @Test fun cookieExpiryReplacesTransportUnderExistingBackgroundOwnership() = runBlocking {
        Harness().use { h ->
            h.start()
            h.approve()
            h.onMain {
                h.field("backgroundToken", "existing-consent")
                h.controller.backgroundServiceStarted("existing-consent")
                h.controller.foreground(false)
            }
            assertTrue(h.controller.state.value.keepTransportWhenHidden)
            h.expireCookie()
            h.until { h.controller.state.value.account == null }
            h.until { h.channels.size == 2 }
            assertTrue(h.controller.state.value.backgroundRunning)
            assertTrue(h.controller.isBackgroundSession("existing-consent"))
            assertEquals(h.next, h.channels.last().authorization)
            assertEquals(1, h.channels.count { !it.closed })
            assertEquals(0, h.backgroundStops)
        }
    }

    @Test fun cookieExpiryCannotRestartAfterUserStopOrSignout() = runBlocking {
        for (signout in listOf(false, true)) Harness().use { h ->
            h.start(); h.approve(); h.expireCookie()
            h.onMain { if (signout) h.controller.signOut() else h.controller.stopBackgroundConnection() }
            delay(1300)
            assertEquals(1, h.channels.size)
            assertTrue(h.channels.single().closed)
            assertEquals("disconnected", h.controller.state.value.status.state)
            assertFalse(h.controller.state.value.backgroundRunning)
        }
    }

    @Test fun stopOrSignoutDuringAccountRetirementCancelsRememberedReconnection() = runBlocking {
        for (signout in listOf(false, true)) Harness().use { h ->
            h.start(); h.approve()
            val old = h.channels.single()
            h.holdClose = CountDownLatch(1)
            h.expireCookie()
            h.until { h.controller.state.value.account == null && old.closed }
            h.onMain { if (signout) h.controller.signOut() else h.controller.stopBackgroundConnection() }
            h.holdClose!!.countDown()
            delay(150)
            assertEquals(1, h.channels.size)
            assertEquals("disconnected", h.controller.state.value.status.state)
            assertFalse(h.controller.state.value.backgroundRunning)
        }
    }

    @Test fun grantConfirmationDiskFailureIsReportedAndTransportRetired() = runBlocking {
        Harness(account = false, committed = true).use { h ->
            h.holdGrant = true
            h.start()
            h.disk.fail = true
            h.channels.single().finishGrant()
            h.until { h.controller.state.value.rememberedStorageUnconfirmed }
            assertTrue(h.diskStore.persistenceFailed)
            assertNull(h.controller.state.value.deviceAccess)
            assertTrue(h.channels.single().closed)
            assertTrue(h.alertsDisabled > 0)
            assertTrue(h.controller.state.value.error.orEmpty().contains("storage", ignoreCase = true))
            assertEquals("disconnected", h.controller.state.value.status.state)
        }
    }

    @Test fun delayedConfirmationFailureCannotOverwriteNewLogin() = runBlocking {
        Harness(account = false, committed = true).use { h ->
            h.holdGrant = true
            h.start()
            val old = h.channels.single()
            h.disk.fail = true
            h.onMain {
                old.finishGrant()
                h.disk.fail = false
                h.controller.signOut()
                h.controller.signIn(unused(Activity::class.java), false, "")
            }
            h.until { !h.controller.state.value.busy && h.controller.state.value.account != null }
            assertFalse(h.controller.state.value.rememberedStorageUnconfirmed)
            assertNull(h.controller.state.value.error)
            assertEquals(h.record.account, h.controller.state.value.account)
            assertTrue(h.channels.last().current())
        }
    }

    @Test fun confirmationStorageWarningSurvivesSameUserDisconnect() = runBlocking {
        assertStorageWarningAfterRetirement(background = false)
    }

    @Test fun confirmationStorageWarningSurvivesSameUserBackgrounding() = runBlocking {
        assertStorageWarningAfterRetirement(background = true)
    }

    private suspend fun assertStorageWarningAfterRetirement(background: Boolean) {
        Harness(account = false, committed = true).use { h ->
            h.holdGrant = true
            h.start()
            h.disk.fail = true
            h.onMain {
                h.channels.single().finishGrant()
                if (background) h.controller.foreground(false) else h.controller.disconnect()
            }
            h.until { h.controller.state.value.rememberedStorageUnconfirmed }
            assertTrue(h.diskStore.persistenceFailed)
            assertNull(h.controller.state.value.deviceAccess)
            assertTrue(h.channels.single().closed)
            assertTrue(h.alertsDisabled > 0)
            assertTrue(app.notify.android.notifications.NotificationSettings.alertsBlocked)
            assertTrue(h.controller.state.value.error.orEmpty().contains("storage", ignoreCase = true))
            assertEquals("disconnected", h.controller.state.value.status.state)
        }
    }

    @Test fun delayedStorageWarningDoesNotRetireSameUserAccountRetry() = runBlocking {
        Harness(committed = true).use { h ->
            h.holdGrant = true
            h.start()
            h.disk.fail = true
            h.onMain {
                h.controller.retry()
                h.channels.single().finishGrant()
            }
            h.until { h.controller.state.value.rememberedStorageUnconfirmed }
            h.until { h.channels.size == 2 && !h.controller.state.value.busy }
            assertNull(h.channels.last().authorization)
            assertFalse(h.channels.last().closed)
            assertTrue(h.channels.last().current())
            assertEquals("connected", h.controller.state.value.status.state)
            assertTrue(h.alertsDisabled > 0)
        }
    }

    @Test fun delayedConfirmationFailureCannotOverwriteResolvedOrReplacedStorageOperation() = runBlocking {
        for (failAgain in listOf(false, true)) Harness(account = false, committed = true).use { h ->
            h.holdGrant = true
            h.start()
            h.disk.fail = true
            h.onMain {
                h.channels.single().finishGrant()
                h.disk.fail = failAgain
                try { h.diskStore.off() } catch (_: IllegalStateException) { assertTrue(failAgain) }
            }
            h.onMain {}
            assertEquals(failAgain, h.diskStore.persistenceFailed)
            assertFalse(h.controller.state.value.rememberedStorageUnconfirmed)
            assertNull(h.controller.state.value.error)
            assertEquals(0, h.alertsDisabled)
        }
    }

    @Test fun signoutUsesCapturedPendingGenerationProvenCommittedByRevokeAndOriginalCas() = runBlocking {
        Harness(account = false, committed = true, renewal = true, foreground = false).use { h ->
            h.serverCommitted = h.next
            h.start()
            h.onMain { h.controller.signOut() }
            h.until { h.logoutCalls > 0 }
            h.cleaned()
            assertEquals(listOf(h.old.generation, h.next.generation), h.revokeBegins.toList())
            assertEquals(listOf(h.next.generation), h.pushGenerations.toList())
            assertEquals(listOf(h.guard), h.pushGuards.toList())
            assertEquals(1, h.removedCalls)
            assertTrue(h.controller.state.value.error.orEmpty().contains("Server authorization may remain active"))
            assertFalse(h.controller.state.value.error.orEmpty().contains("Push routing"))
            assertTrue(h.signerCapturedBeforeOff)
        }
    }

    @Test fun cancellingPendingRenewalDoesNotReplaceCapturedCommittedCleanupScope() = runBlocking {
        Harness(account = false, committed = true, renewal = true, foreground = false).use { h ->
            h.start()
            h.onMain { h.controller.signOut() }
            h.until { h.logoutCalls > 0 }
            h.cleaned()
            assertEquals(listOf(h.old.generation, h.next.generation), h.revokeBegins.toList())
            assertEquals(listOf(h.old.generation), h.pushGenerations.toList())
            assertEquals(listOf(h.guard), h.pushGuards.toList())
            assertEquals(1, h.removedCalls)
            assertNull(h.controller.state.value.error)
        }
    }

    @Test fun unknownRevokeCannotAdoptGenerationAndDoesNotSkipOldAccountCleanup() = runBlocking {
        for (account in listOf(false, true)) Harness(account, committed = true, renewal = true, foreground = false).use { h ->
            h.serverCommitted = h.next
            h.loseRevoke = true
            h.start()
            h.onMain { h.controller.signOut() }
            h.until { h.logoutCalls > 0 }
            h.cleaned()
            assertEquals(if (account) listOf("account") else emptyList<String>(), h.pushGenerations.toList())
            assertEquals(if (account) listOf(h.guard) else emptyList<String>(), h.pushGuards.toList())
            assertTrue(h.controller.state.value.error.orEmpty().contains("Server authorization may remain active"))
        }
    }

    @Test fun oldCleanupNeverRefreshesCasOrRemovesNewLoginRegistration() = runBlocking {
        Harness(account = false, committed = true, renewal = true).use { h ->
            h.serverCommitted = h.next
            h.holdRevoke = CountDownLatch(1)
            h.start()
            h.onMain { h.controller.signOut() }
            h.until { h.revokeFinishEntered }
            h.onMain { h.controller.signIn(unused(Activity::class.java), false, "") }
            h.until { !h.controller.state.value.busy && h.controller.state.value.account != null }
            h.onMain { h.controller.enableNotifications() }
            h.until { !h.controller.state.value.busy && h.savedRouting.registrationGeneration == h.newGuard }
            h.holdRevoke!!.countDown()
            h.until { h.logoutCalls > 0 }
            h.cleaned()
            assertEquals(listOf("account", h.next.generation), h.pushGenerations.toList())
            assertEquals(listOf(h.guard, h.guard), h.pushGuards.toList())
            assertEquals(1, h.stalePushRejections)
            assertEquals(0, h.removedCalls)
            assertEquals(h.newGuard, h.savedRouting.registrationGeneration)
            assertEquals(h.record.account, h.controller.state.value.account)
            assertNull(h.controller.state.value.error)
        }
    }

    private class Disk : RelationshipStorage {
        var raw: String? = null
        @Volatile var fail = false
        override fun read() = raw
        override fun write(value: String?) { check(!fail) { "Synthetic storage failure" }; raw = value }
    }

    private class Harness(
        account: Boolean = true, committed: Boolean = false, renewal: Boolean = false, foreground: Boolean = true,
    ) : ControllerRuntime, AutoCloseable {
        override val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        override val scope = CoroutineScope(SupervisorJob() + main)
        val server = MockWebServer()
        private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val disk = Disk()
        val diskStore = RememberedStore(disk)
        override val remembered get() = diskStore
        override val cache = ConversationCacheStore(object : ConversationStorage {
            override fun read(): ByteArray? = null
            override fun write(value: ByteArray) {}
            override fun clear() {}
        })
        val clients = CopyOnWriteArrayList<ServiceClient>()
        val channels = CopyOnWriteArrayList<Channel>()
        val revokeBegins = CopyOnWriteArrayList<String>()
        val pushGenerations = CopyOnWriteArrayList<String>()
        val pushGuards = CopyOnWriteArrayList<String>()
        val guard = "A".repeat(43)
        val newGuard = "B".repeat(42) + "A"
        val record: Relationship
        val old: RememberAuthorization
        val next: RememberAuthorization
        @Volatile var serverCommitted: RememberAuthorization? = null
        @Volatile var serverPending: RememberAuthorization? = null
        @Volatile var serverGuard = guard
        @Volatile var savedRouting = PushMetadata(true, guard)
        @Volatile var loseRevoke = false
        @Volatile var holdRevoke: CountDownLatch? = null
        @Volatile var holdClose: CountDownLatch? = null
        @Volatile var revokeFinishEntered = false
        @Volatile var holdGrant = false
        @Volatile var alertsDisabled = 0
        @Volatile var removedCalls = 0
        @Volatile var registered = true
        @Volatile var stalePushRejections = 0
        @Volatile var logoutCalls = 0
        @Volatile var backgroundStops = 0
        @Volatile var signerCapturedBeforeOff = false
        override val initialState: ControllerState
        lateinit var controller: ControllerViewModel
        private val flows = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        private val pendingRevokes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private var flowID = 0

        init {
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start()
            record = rememberedRelationship().copy(account = Account(server.url("/").toString().removeSuffix("/"), "synthetic-account-1"))
            old = RememberAuthorization(record, "A".repeat(43), System.currentTimeMillis() + 120_000)
            next = old.copy(generation = "B".repeat(42) + "A")
            val operation = diskStore.begin(record)
            if (committed) {
                diskStore.propose(operation, RememberProposal(old.generation, old, System.currentTimeMillis() + 60_000))
                diskStore.confirm(operation, old)
                serverCommitted = old
            }
            if (!committed || renewal) {
                diskStore.propose(operation, RememberProposal(next.generation, next, System.currentTimeMillis() + 60_000))
                serverPending = next
            }
            initialState = ControllerState(account = record.account.takeIf { account }, relationship = record,
                foreground = foreground, remembered = diskStore.state)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
            }
        }

        suspend fun start() {
            onMain { controller = ControllerViewModel(unused(Application::class.java), this) }
            if (initialState.account != null) clients.first().post("/v1/account/login/verify", JSONObject())
            until { !controller.state.value.busy }
            if (initialState.foreground) until { channels.isNotEmpty() }
        }
        suspend fun onMain(block: () -> Unit) = withContext(main) { block() }
        fun field(name: String, value: Any?) =
            ControllerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
        suspend fun until(condition: () -> Boolean) {
            withTimeout(3500) { while (!condition()) delay(10) }
            onMain {}
        }
        suspend fun cleaned() {
            val job = withContext(main) {
                ControllerViewModel::class.java.getDeclaredField("signingOut").apply { isAccessible = true }.get(controller) as Job
            }
            job.join()
        }
        suspend fun approve() {
            serverCommitted = next
            onMain { controller.checkRemembered() }
            until { !controller.state.value.busy && controller.state.value.remembered.committed == next }
        }
        suspend fun expireCookie() {
            clients.first().post("/v1/account/test-expiry", JSONObject())
            onMain {
                ControllerViewModel::class.java.getDeclaredMethod("watchAccountSession", ServiceClient::class.java)
                    .apply { isAccessible = true }.invoke(controller, clients.first())
            }
        }
        override fun service() = ServiceClient(server.url("/"), OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()).also { clients += it }
        override suspend fun authenticate(client: ServiceClient, register: Boolean, name: String): Account {
            client.post("/v1/account/login/verify", JSONObject())
            return record.account
        }
        override fun elapsed() = System.nanoTime() / 1_000_000
        override suspend fun connection(client: ServiceClient, record: Relationship, authorization: RememberAuthorization?,
            current: () -> Boolean, authorized: (RememberAuthorization, PushMetadata) -> Unit,
            status: (RemoteStatus) -> Unit): ControllerConnection =
            Channel(authorization, current, authorized, status).also { channels += it }
        inner class Channel(val authorization: RememberAuthorization?, val current: () -> Boolean,
            private val authorized: (RememberAuthorization, PushMetadata) -> Unit, private val status: (RemoteStatus) -> Unit) : ControllerConnection {
            @Volatile var closed = false
            override fun connect() { if (!holdGrant || authorization == null) finishGrant() }
            fun finishGrant() {
                try {
                    if (authorization != null) authorized(authorization, savedRouting)
                    if (current()) status(RemoteStatus("connected", "Synthetic transport"))
                } catch (error: Exception) {
                    // Same gate as RemoteConnection.fail: invalidated authority cannot report a status.
                    if (current()) status(RemoteStatus("error", error.message.orEmpty()))
                }
            }
            override suspend fun request(path: String, data: JSONObject, id: String, dispatchAllowed: (() -> Boolean)?): JSONObject =
                awaitCancellation()
            override fun close() {
                closed = true
                holdClose?.await(3, TimeUnit.SECONDS)
                status(RemoteStatus("disconnected", "Closed synthetic transport"))
            }
        }
        override fun signer(record: Relationship): (ByteArray) -> ByteArray {
            signerCapturedBeforeOff = diskStore.state.authorizations.isNotEmpty()
            return { byteArrayOf(1) }
        }
        override fun notifications() = NotificationState(consent = alertsDisabled == 0, registered = registered,
            cleanupPending = alertsDisabled > 0 && removedCalls == 0)
        override suspend fun notificationToken(): String {
            alertsDisabled = 0
            registered = false
            return "same-synthetic-token"
        }
        override fun registeredNotifications(token: String) { registered = true }
        override fun routing(record: Relationship) = savedRouting
        override fun routing(record: Relationship, metadata: PushMetadata) { savedRouting = metadata }
        override fun disableNotifications() { alertsDisabled++ }
        override fun removedNotifications() { removedCalls++ }
        override fun stopBackground() { backgroundStops++ }

        private fun respond(request: RecordedRequest): MockResponse {
            val input = JSONObject(request.body.readUtf8())
            val path = request.path.orEmpty()
            fun response(value: JSONObject = JSONObject(), code: Int = 200) =
                MockResponse().setResponseCode(code).setBody(value.toString())
            if (path == "/v1/account/test-expiry" || path == "/v1/account/login/verify")
                return response().addHeader("Set-Cookie", "__Host-notify-account=synthetic; Path=/; Secure; HttpOnly; Max-Age=${if (path.endsWith("test-expiry")) 1 else 60}")
            if (path == "/v1/account/session") return response(JSONObject().put("scope", "account").put("fresh", true).put("account", record.account.json()))
            if (path == "/v1/account/logout") { logoutCalls++; return response() }
            if (path.endsWith("remember/status")) return response(JSONObject().put("macID", record.macID).put("deviceID", record.deviceID)
                .put("committed", serverCommitted?.let { JSONObject().put("generation", it.generation)
                    .put("authorizationExpiresAt", it.expiresAt).put("state", "active") } ?: JSONObject.NULL)
                .put("pending", JSONObject.NULL).put("push", JSONObject().put("enabled", true).put("registrationGeneration", serverGuard)))
            if (path.endsWith("/begin")) {
                val revoke = path.contains("/revoke/")
                val accountPush = path == "/v1/remote/push/begin"
                val generation = input.optString("generation")
                if (revoke) revokeBegins += generation else {
                    pushGenerations += if (accountPush) "account" else generation
                    pushGuards += input.getString("registrationGeneration")
                }
                val authorization = serverCommitted?.takeIf { it.generation == generation }
                    ?: serverPending?.takeIf { revoke && it.generation == generation }
                if (accountPush && request.getHeader("Cookie")?.contains("__Host-notify-account=") != true)
                    return response(JSONObject().put("error", "No captured account cookie"), 401)
                if (!accountPush && authorization == null) return response(JSONObject().put("error", "Unauthorized"), 401)
                if (!revoke && input.getString("registrationGeneration") != serverGuard) {
                    stalePushRejections++
                    return response(JSONObject().put("error", "stale guard"), 409)
                }
                val id = encodeKey(ByteArray(32).also { it[0] = (++flowID).toByte() })
                val expiry = System.currentTimeMillis() + 30_000
                val challenge = JSONObject().put("purpose", if (accountPush) "push" else if (revoke) "remember-revoke" else "remember-push")
                    .put("origin", record.account.issuer).put("scope", if (accountPush) "account" else "remembered")
                    .put("operation", if (revoke) "revoke" else "push").put("version", 1).put("account", record.account.json())
                    .put("macID", record.macID).put("macPublicKey", record.macPublicKey).put("deviceID", record.deviceID)
                    .put("publicKey", record.publicKey).put("challengeExpiresAt", expiry).put("challenge", id)
                if (!accountPush) challenge.put("generation", generation).put("authorizationExpiresAt", authorization!!.expiresAt)
                if (!revoke) challenge.put("token", input.get("token")).put("registrationGeneration", input.get("registrationGeneration"))
                flows[id] = challenge
                if (revoke) pendingRevokes[id] = generation != serverCommitted?.generation
                return response(JSONObject().put("id", id).put("challenge", challenge.toString()).put("expiresAt", expiry))
            }
            if (path.endsWith("/finish")) {
                val challenge = flows.remove(input.getString("id"))!!
                if (path.contains("/revoke/")) {
                    revokeFinishEntered = true
                    holdRevoke?.await(3, TimeUnit.SECONDS)
                    if (loseRevoke) return response(JSONObject().put("error", "Lost acknowledgement"), 503)
                    return response(JSONObject().put("generation", challenge.getString("generation")).put("revoked", true)
                        .put("cancelledPending", pendingRevokes.remove(input.getString("id"))))
                }
                serverGuard = newGuard
                return response(JSONObject().put("enabled", !challenge.isNull("token")).put("registrationGeneration", newGuard))
            }
            return response(JSONObject().put("error", "Unexpected test endpoint"), 404)
        }
        override fun close() {
            holdRevoke?.countDown()
            holdClose?.countDown()
            runBlocking(main) {
                if (::controller.isInitialized) controller.disconnect()
                scope.cancel()
            }
            if (::controller.isInitialized) {
                val cleanup = ControllerViewModel::class.java.getDeclaredField("cleanup").apply { isAccessible = true }
                    .get(controller) as CoroutineScope
                runBlocking { cleanup.coroutineContext[Job]?.cancelAndJoin() }
            }
            channels.forEach { it.close() }
            clients.forEach { it.close() }
            server.shutdown()
            app.notify.android.notifications.NotificationSettings.alertsBlocked = false
            main.close()
        }
    }

    private companion object {
        // An unused handle only: every Android effect is injected, so stub constructors are never invoked.
        fun <T> unused(type: Class<T>): T {
            val allocator = Class.forName("sun.misc.Unsafe")
            val unsafe = allocator.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return type.cast(allocator.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }
    }
}
