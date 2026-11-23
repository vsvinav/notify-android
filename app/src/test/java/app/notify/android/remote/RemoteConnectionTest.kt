package app.notify.android.remote

import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import app.notify.android.controller.ControllerState
import app.notify.android.controller.Snapshots
import app.notify.android.controller.WorkspaceState
import app.notify.android.controller.SubagentCreation
import app.notify.android.controller.SessionActions
import app.notify.android.controller.SessionColor
import app.notify.android.controller.sessionActionStatuses
import app.notify.android.controller.PanelPending
import app.notify.android.controller.PanelState

/** Real HTTPS/WSS with a deterministic plaintext crypto fixture, NOT an MLS interoperability test. */
class RemoteConnectionTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    private lateinit var service: ServiceClient
    private lateinit var connection: RemoteConnection
    private val statuses = LinkedBlockingQueue<RemoteStatus>()
    private val trace = ConnectionTrace { System.nanoTime() / 1_000_000 }
    private val peers = mutableListOf<FixtureCrypto>()
    private val elapsed = AtomicLong(0)
    private val wallOffset = AtomicLong(0)
    private val macID = UUID.randomUUID().toString()
    private val deviceID = UUID.randomUUID().toString()
    private val epoch = UUID.randomUUID().toString()
    private val grantID = "g".repeat(43)
    private val pin = ByteArray(65) { if (it == 0) 4 else it.toByte() }

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        service = ServiceClient(server.url("/"), OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
        connection = newConnection()
    }

    private fun newConnection(failure: String? = null, clockWall: (() -> Long)? = null) = RemoteConnection(
        service, macID, deviceID, pin,
        { statuses.add(it) },
        { FixtureCrypto(failure).also { synchronized(peers) { peers.add(it) } } },
        { elapsed.get() }, { clockWall?.invoke() ?: (System.currentTimeMillis() + wallOffset.get()) }, trace
    )

    @After fun stop() {
        connection.close()
        service.close()
        server.shutdown()
    }

    private fun lease(challenge: String = "renew-1", expiresAt: Long = System.currentTimeMillis() + 29_000) =
        JSONObject().put("role", "phone").put("macID", macID).put("deviceID", deviceID)
            .put("approved", true).put("expiresAt", expiresAt)
            .put("renewal", JSONObject().put("id", grantID).put("challenge", challenge))

    private fun grant(value: JSONObject = lease(), cookie: String = "__Host-notify-relay=${"r".repeat(43)}; Path=/; Secure; HttpOnly; Max-Age=30") {
        server.enqueue(MockResponse().setBody(JSONObject().put("id", grantID).put("challenge", "authorize").toString()))
        server.enqueue(MockResponse().setBody(value.toString()).addHeader("Set-Cookie", cookie))
    }

    private inner class Relay(
        private val greeting: String = """{"type":"ready","version":1}""",
        private val sender: String = "native",
        private val selectedProtocol: String = "notify-relay-v1",
        private val welcomeBytes: String = "[1,2]",
        private val autoReady: Boolean = true,
        private val autoAckPanel: Boolean = false,
    ) : WebSocketListener() {
        lateinit var socket: WebSocket
        val frames = LinkedBlockingQueue<JSONObject>()
        fun enqueue() {
            server.enqueue(MockResponse().withWebSocketUpgrade(this)
                .addHeader("Sec-WebSocket-Protocol", selectedProtocol))
        }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            socket.send(greeting)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val value = JSONObject(bytes.utf8())
            when (value.optString("type")) {
                "key_package" -> {
                    assertEquals(7, value.getJSONArray("bytes").getInt(0))
                    socket.send(JSONObject().put("type", "welcome").put("welcome", JSONArray(welcomeBytes))
                        .put("tree", JSONArray("[3,4]")).toString().toByteArray().toByteString())
                }
                "hello" -> send(if (autoReady) JSONObject().put("type", "ready").put("epoch", epoch)
                    else JSONObject().put("type", "pending"), sender)
                else -> {
                    frames.add(value)
                    if (autoAckPanel && value.optJSONObject("payload")?.optString("path") == "panel_action")
                        ack(value.getString("id"))
                }
            }
        }
        fun send(value: JSONObject, sender: String = "native") {
            socket.send(JSONObject().put("sender", sender).put("body", value).toString().toByteArray().toByteString())
        }
        fun ack(id: String, status: Int = 200) = send(JSONObject().put("id", id).put("status", status)
            .put("data", JSONObject().put("ok", true)))
    }

    private fun status(state: String): RemoteStatus {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) {
            val next = statuses.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (next.state == state) return next
        }
        fail("No $state status; got ${statuses.toList()}")
        error("unreachable")
    }

    private fun policyWorkspace(token: String = "00000000-0000-8000-8000-000000000001") =
        WorkspaceState(sessionID = "s").receive(Snapshots.workspace(JSONObject("""
            {"connected":true,"dangerousAutoYesControl":true,"dangerousAutoYesOffEpoch":true,"sessions":[{
             "id":"s","ticket":"t","name":"Synthetic","project":"","agent":"copilot","state":"working","connected":true,
             "capabilities":{"steering":true,"subagentChat":false,"subagentStop":false,"dangerousAutoYes":true},
             "dangerousAutoYes":{"ticket":"p","offTicket":"$token","revision":1,"enabled":true,"normalEnabled":false}
            }],"detail":null}
        """)), 0)

    @Test fun savedResumeTraversesHttpsRpcWithoutClaimingConnectedOrReplayingItsUuid() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val base = policyWorkspace()
        var state = base.copy(snapshot = base.snapshot!!.copy(sessions = listOf(base.session!!.copy(connected = false,
            sessionActions = SessionActions("m", "original", "saved", 0x123456, true,
                listOf(SessionColor("Sky", 0xA6C8FF)), true, true, true, "available", "", supported = true)))))
        val command = state.prepareSessionAction("resume_session", 0)
        state.validate(command, 0)
        state = state.sending(command)
        val request = async { connection.request("control", command.json(), command.id) }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        val data = frame.getJSONObject("payload").getJSONObject("data")
        assertEquals("resume_session", data.getString("action"))
        assertEquals(setOf("id", "sessionID", "ticket", "action", "text", "sessionTicket"), data.keys().asSequence().toSet())
        relay.send(JSONObject().put("id", command.id).put("status", 200).put("data",
            JSONObject().put("ok", true).put("commandID", "native-command").put("status", "launched")
                .put("sessionID", "s").put("sessionTicket", "m").put("action", "resume_session").put("detail", "Launch completed")))
        state = state.acknowledgeSessionAction(command, request.await())
        assertFalse(state.session!!.connected)
        assertTrue(state.sessionResults.single().status.contains("connection is not confirmed"))
        assertTrue(runCatching { connection.request("control", command.json(), command.id) }.isFailure)
        assertTrue(runCatching { state.prepareSessionAction("resume_session", 0) }.isFailure)
        assertTrue(relay.frames.isEmpty())
    }

    @Test fun staleSessionReceiptAcrossHttpsSettlesWithoutReplayAndAllowsFreshExplicitReview() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val base = policyWorkspace()
        val saved = base.session!!.copy(connected = false,
            sessionActions = SessionActions("m", "original", "saved", 0x123456, true,
                emptyList(), true, true, true, "available", "", moveDown = "peer", supported = true))
        val peer = saved.copy(id = "peer", ticket = "peer-work",
            sessionActions = saved.sessionActions!!.copy(ticket = "peer-meta", sourceID = "other", moveDown = null, moveUp = "s"))
        for (action in listOf("resume_session", "reset_session_color", "move_session")) {
            var state = base.copy(snapshot = base.snapshot!!.copy(sessions = listOf(saved, peer)))
                .edit("keep draft").select("peer").edit("keep other draft").select("s")
            val target = if (action == "move_session") "peer" else null
            val command = state.prepareSessionAction(action, 0, moveTarget = target)
            state = state.sending(command)
            val request = async { connection.request("control", command.json(), command.id) }
            val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
            assertEquals(command.id, frame.getString("id"))
            val freshSnapshot = state.snapshot!!.copy(sessions = listOf(
                saved.copy(sessionActions = saved.sessionActions.copy(ticket = "fresh-$action")), peer))
            state = state.receive(freshSnapshot, 0)
            relay.send(JSONObject().put("id", command.id).put("status", 200).put("data",
                JSONObject().put("ok", false).put("status", "failed").put("sessionID", command.sessionID)
                    .put("sessionTicket", command.sessionTicket).put("action", action)
                    .put("detail", "Session actions are stale. Refresh the Mac workspace; nothing was dispatched.")))
            val receipt = request.await()
            assertFalse(receipt.has("notDispatched"))
            state = state.acknowledgeSessionAction(command, receipt)
            assertFalse(state.sessionResults.single().blocked)
            assertEquals(freshSnapshot, state.snapshot)
            assertEquals("keep draft", state.draft)
            assertEquals("keep other draft", state.select("peer").draft)
            assertTrue(runCatching { connection.request("control", command.json(), command.id) }.isFailure)
            assertTrue(runCatching {
                connection.request("control", command.json().put("sessionTicket", "fresh-$action"), command.id)
            }.isFailure)
            assertTrue("A failed receipt must not replay or automatically create another command", relay.frames.isEmpty())
            val reviewed = state.prepareSessionAction(action, 0, moveTarget = target)
            assertNotEquals(command.id, reviewed.id)
            assertEquals("fresh-$action", reviewed.sessionTicket)
            state.validate(reviewed, 0)
            state = state.sending(reviewed)
            val freshRequest = async { connection.request("control", reviewed.json(), reviewed.id) }
            val freshFrame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
            assertEquals(reviewed.id, freshFrame.getString("id"))
            relay.send(JSONObject().put("id", reviewed.id).put("status", 200).put("data",
                JSONObject().put("ok", true).put("status", sessionActionStatuses[action]).put("commandID", "native-id")
                    .put("sessionID", reviewed.sessionID).put("sessionTicket", reviewed.sessionTicket)
                    .put("action", action).put("detail", "Exact native result")))
            state = state.acknowledgeSessionAction(reviewed, freshRequest.await())
            assertEquals(freshSnapshot, state.snapshot)
            assertFalse(state.session!!.connected)
        }
    }

    @Test fun typedCreationTraversesHttpsRpcAndOnlyCreatedClearsItsUneditedSessionForm() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val base = policyWorkspace()
        var state = base.copy(snapshot = base.snapshot!!.copy(sessions = listOf(base.session!!.copy(
            managementTicket = "m", subagentTypes = listOf("reviewer"), subagentModels = listOf("live-model"),
            capabilities = base.session!!.capabilities.copy(subagentCreate = true)))))
            .editCreation(SubagentCreation("Exact name", "reviewer", "  instructions\n", "live-model"))
        val command = state.prepareManagement("create_subagent", 0)
        state.validate(command, 0)
        state = state.sending(command)
        val request = async { connection.request("control", command.json(), command.id) }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        val data = frame.getJSONObject("payload").getJSONObject("data")
        assertEquals("create_subagent", data.getString("action"))
        assertEquals("s", data.getString("sessionID"))
        assertFalse(data.has("agentID"))
        assertEquals("live-model", data.getJSONObject("creation").getString("model"))
        assertEquals("  instructions\n", data.getJSONObject("creation").getString("prompt"))
        relay.send(JSONObject().put("id", command.id).put("status", 200).put("data",
            JSONObject().put("ok", true).put("commandID", "native-command").put("status", "created").put("detail", "Created exact helper")))
        state = state.acknowledgeManagement(command, request.await())
        assertEquals(SubagentCreation(), state.creationDraft.value)
        assertNull(state.delivery)
        assertTrue(runCatching { connection.request("control", command.json(), command.id) }.isFailure)
        assertTrue(relay.frames.isEmpty())
    }

    @Test fun workspaceOffRejectedBeforeDispatchCanBeExplicitlyRequestedAgainOnSameConnection() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        var workspace = policyWorkspace()
        val first = workspace.preparePolicy(false, 0)
        workspace = workspace.sending(first)
        val rejected = async { runCatching { connection.request("control", first.json(), first.id) } }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        relay.send(JSONObject().put("id", frame.getString("id")).put("status", 503)
            .put("data", JSONObject().put("error", "Workspace is stale.").put("notDispatched", true)))
        val failure = rejected.await().exceptionOrNull() as RemoteRequestFailure
        assertEquals(503, failure.status)
        assertTrue(failure.notDispatched)
        assertFalse(failure.uncertain)
        workspace = workspace.rejectPolicyBeforeDispatch(first, failure.message!!)
        assertFalse(workspace.policyDelivery!!.blocked)
        assertTrue(workspace.session!!.dangerousAutoYes!!.enabled)
        assertFalse(workspace.policyDelivery!!.status.contains("unknown", ignoreCase = true))
        workspace = workspace.receive(workspace.snapshot!!, 1)
        val second = workspace.preparePolicy(false, 1)
        val result = async { runCatching { connection.request("control", second.json(), second.id) } }
        withTimeout(3000) { while (relay.frames.isEmpty() && !result.isCompleted) delay(10) }
        assertFalse("A fresh explicit Off must reach the helper, not fail in the RPC UUID ledger", relay.frames.isEmpty())
        val retry = relay.frames.take()
        assertEquals(first.id, retry.getJSONObject("payload").getJSONObject("data").getString("id"))
        relay.ack(retry.getString("id"))
        assertTrue(result.await().isSuccess)
    }

    @Test fun workspaceOffCanUseNinthRpcSlotWithoutGivingOrdinaryRequestsAnotherSlot() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val ordinary = List(8) { async { connection.request("state", JSONObject()) } }
        withTimeout(3000) { while (relay.frames.size < 8) delay(10) }
        val off = policyWorkspace().preparePolicy(false, 0)
        val result = async { runCatching { connection.request("control", off.json(), off.id) } }
        withTimeout(3000) { while (relay.frames.size < 9 && !result.isCompleted) delay(10) }
        assertEquals(9, relay.frames.size)
        assertThrows(RemoteRequestFailure::class.java) { runBlocking { connection.request("state", JSONObject()) } }
        val other = policyWorkspace("00000000-0000-8000-8000-000000000002").preparePolicy(false, 0)
        val busy = runCatching { connection.request("control", other.json(), other.id) }.exceptionOrNull()
        assertTrue(busy is RemoteRequestFailure && busy.notDispatched)
        repeat(9) { relay.ack(relay.frames.take().getString("id")) }
        assertTrue(result.await().isSuccess)
        ordinary.forEach { it.await() }
    }

    @Test fun workspaceOffUnknownAckCannotReleaseActionOrBeRepurposedAsOrdinaryWork() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val off = policyWorkspace().preparePolicy(false, 0)
        val result = async { runCatching { connection.request("control", off.json(), off.id) } }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(10) }
        relay.frames.take()
        relay.send(JSONObject().put("id", off.id).put("status", 503).put("data",
            JSONObject().put("error", "Lost native connection").put("status", "unknown").put("notDispatched", true)))
        val failure = result.await().exceptionOrNull() as RemoteRequestFailure
        assertTrue(failure.uncertain)
        assertFalse(failure.notDispatched)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("control", off.json(), off.id) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("control", off.json().put("policyTicket", "mutated"), off.id) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("reply", JSONObject(), off.id) }
        }
        assertTrue(relay.frames.isEmpty())
        val ordinaryID = UUID.randomUUID().toString()
        val ordinary = async { runCatching { connection.request("reply", JSONObject(), ordinaryID) } }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(1) }
        relay.frames.take()
        relay.send(JSONObject().put("id", ordinaryID).put("status", 503).put("data",
            JSONObject().put("error", "Rejected").put("notDispatched", true)))
        assertFalse((ordinary.await().exceptionOrNull() as RemoteRequestFailure).notDispatched)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("reply", JSONObject(), ordinaryID) }
        }
        assertTrue(relay.frames.isEmpty())
    }

    @Test fun workspaceOffSupports300FreshEpochsAfterOrdinaryRpcHistoryIsFull() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val ordinaryID = UUID.randomUUID().toString()
        val ordinary = async { connection.request("state", JSONObject(), ordinaryID) }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(1) }
        relay.ack(relay.frames.take().getString("id")); ordinary.await()
        repeat(4095) {
            val read = async { connection.request("state", JSONObject()) }
            withTimeout(3000) { while (relay.frames.isEmpty()) delay(1) }
            relay.ack(relay.frames.take().getString("id")); read.await()
        }
        repeat(300) { cycle ->
            val token = "00000000-0000-8000-8000-${(cycle + 1).toString(16).padStart(12, '0')}"
            val workspace = policyWorkspace(token)
            val off = workspace.preparePolicy(false, 0)
            val result = async { connection.request("control", off.json(), off.id) }
            withTimeout(3000) { while (relay.frames.isEmpty()) delay(1) }
            val frame = relay.frames.take()
            assertEquals(off.id, frame.getString("id"))
            assertEquals(token, frame.getJSONObject("payload").getJSONObject("data").getString("offTicket"))
            relay.ack(frame.getString("id")); result.await()
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("state", JSONObject(), ordinaryID) }
        }
        assertTrue(relay.frames.isEmpty())
    }
    @Test fun handshakeUsesCookieExactOriginPinnedKeyAndMatchingNativeAck() = runBlocking {
        grant()
        val relay = Relay().also { it.enqueue() }
        connection.connect()
        status("connected")
        val begin = server.takeRequest()
        assertEquals("/v1/remote/grant/begin", begin.path)
        assertEquals("phone", JSONObject(begin.body.readUtf8()).getString("role"))
        val finish = server.takeRequest()
        assertEquals("c2lnbmF0dXJl", JSONObject(finish.body.readUtf8()).getString("signature"))
        val upgrade = server.takeRequest()
        assertEquals("/v1/relay", upgrade.path)
        assertEquals(server.url("/").toString().removeSuffix("/"), upgrade.getHeader("Origin"))
        assertEquals("notify-relay-v1", upgrade.getHeader("Sec-WebSocket-Protocol"))
        assertEquals("__Host-notify-relay=${"r".repeat(43)}", upgrade.getHeader("Cookie"))
        assertNull(upgrade.getHeader("Authorization"))
        val peer = synchronized(peers) { peers.single() }
        assertArrayEquals(pin, peer.joinedPin)
        assertEquals("authorize", peer.signed.single())
        val id = UUID.randomUUID().toString()
        val result = async { connection.request("reply", JSONObject().put("text", "yes"), id) }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        assertEquals(id, frame.getString("id"))
        assertEquals(epoch, frame.getString("epoch"))
        assertFalse(frame.getJSONObject("payload").getJSONObject("data").has("id"))
        relay.ack(UUID.randomUUID().toString())
        delay(100)
        assertFalse(result.isCompleted)
        relay.ack(id)
        assertTrue(result.await().getBoolean("ok"))
        assertArrayEquals("native".toByteArray(), peer.expectedSender)
    }

    @Test fun unsupportedPathAndDuplicateIdNeverDispatch() = runBlocking {
        grant()
        val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { connection.request("/state", JSONObject()) } }
        val id = UUID.randomUUID().toString()
        val first = async { connection.request("state", JSONObject(), id) }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(10) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { connection.request("state", JSONObject(), id) } }
        relay.ack(id)
        first.await()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { connection.request("state", JSONObject(), id) } }
        assertEquals(1, relay.frames.size)
    }

    @Test fun panelPathsAreAllowedWithReadAndMutationTimeouts() = runBlocking {
        assertEquals(12_000L, requestTimeout("panel"))
        assertEquals(12_000L, requestTimeout("state"))
        assertEquals(25_000L, requestTimeout("panel_action"))
        assertEquals(25_000L, requestTimeout("control"))
        assertNull(requestTimeout("panel/action"))
        grant()
        val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        for (path in listOf("panel", "panel_action")) {
            val response = async { connection.request(path, JSONObject().put("actionId", "status:APA-1").put("preview", true)) }
            val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
            assertEquals(path, frame.getJSONObject("payload").getString("path"))
            relay.ack(frame.getString("id"))
            assertTrue(response.await().getBoolean("ok"))
        }
        val rejected = async { runCatching { connection.request("panel_action", JSONObject()) } }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        relay.send(JSONObject().put("id", frame.getString("id")).put("status", 503)
            .put("data", JSONObject().put("ok", false).put("error", "Session not connected")))
        val failure = rejected.await().exceptionOrNull() as RemoteRequestFailure
        assertEquals("Session not connected", failure.message)
        assertEquals(503, failure.status)
    }

    @Test fun dashboardOffRejectsSendWaitingAtTransportLock() =
        dashboardWaitingAtTransportLock(preview = false)

    @Test fun dashboardOffThenOnCannotResurrectWaitingSend() =
        dashboardWaitingAtTransportLock(preview = false, enableAgain = true)

    @Test fun dashboardPreviewOffCannotDispatchAfterTransportLockWait() =
        dashboardWaitingAtTransportLock(preview = true)

    @Test fun dashboardReplacedPendingCannotDispatchAfterTransportLockWait() =
        dashboardWaitingAtTransportLock(preview = false, replacePending = true)

    private fun dashboardPending(id: String, preview: Boolean = false) =
        ControllerState(foreground = true, status = RemoteStatus("connected", ""), now = 100,
            inbox = RequestInbox().receive(0, true, emptyList(), 100),
            panel = PanelState(fresh = true, pending = PanelPending(id, "fictional-action", !preview)))
            .dashboardSetting(true)

    private fun dashboardWaitingAtTransportLock(preview: Boolean, enableAgain: Boolean = false,
        replacePending: Boolean = false) = runBlocking {
        val caller = ThreadLocal<Boolean>()
        val atClock = CountDownLatch(1)
        val resumeClock = CountDownLatch(1)
        val dispatchThread = AtomicReference<Thread>()
        val clockHook = AtomicReference<(() -> Unit)?>({
            dispatchThread.set(Thread.currentThread())
            atClock.countDown()
            check(resumeClock.await(5, TimeUnit.SECONDS)) { "Fixture did not release dispatch clock" }
        })
        connection.close()
        connection = newConnection(clockWall = {
            if (caller.get() == true) clockHook.getAndSet(null)?.invoke()
            System.currentTimeMillis()
        })
        grant(); val relay = Relay(autoAckPanel = true).also { it.enqueue() }
        connection.connect(); status("connected")
        val id = UUID.randomUUID().toString()
        val state = AtomicReference(dashboardPending(id, preview))
        val consent = state.get().dashboardGeneration
        val lock = checkNotNull(RemoteConnection::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(connection))
        val result = async(Dispatchers.IO + caller.asContextElement(true)) {
            runCatching {
                assertTrue("The original controller IO guard passed", state.get().panelMayDispatch(id, consent))
                connection.request("panel_action", JSONObject().put("actionId", "fictional-action").put("preview", preview), id,
                    dispatchAllowed = { state.get().panelMayDispatch(id, consent) })
            }
        }
        try {
            assertTrue("Request must pass the first transport lock", atClock.await(5, TimeUnit.SECONDS))
            synchronized(lock) {
                resumeClock.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var blocked = false
                while (System.nanoTime() < deadline) {
                    val thread = dispatchThread.get()
                    if (thread.state == Thread.State.BLOCKED &&
                        thread.stackTrace.any { it.className.startsWith(RemoteConnection::class.java.name) }) {
                        blocked = true
                        break
                    }
                    Thread.yield()
                }
                assertTrue("Request must really wait for the final transport dispatch lock", blocked)
                state.set(if (replacePending) state.get().let {
                    it.copy(panel = it.panel.copy(pending = it.panel.pending!!.copy(id = UUID.randomUUID().toString())))
                } else state.get().dashboardSetting(false))
                if (enableAgain) state.set(state.get().dashboardSetting(true).let {
                    it.copy(panel = it.panel.copy(fresh = true))
                })
            }
        } finally { resumeClock.countDown() }
        val outcome = withTimeout(3_000) { result.await() }
        val failure = outcome.exceptionOrNull()
        assertTrue("Retired consent must reject before encryption, not succeed: $outcome", failure is RemoteRequestFailure)
        failure as RemoteRequestFailure
        assertFalse(failure.uncertain)
        assertTrue(failure.notDispatched)
        assertTrue(relay.frames.isEmpty())
        assertTrue(synchronized(peers) { peers.single().protectedRequests.isEmpty() })
        assertTrue(statuses.isEmpty())
        assertFalse(synchronized(peers) { peers.single().closed })
        assertSuccessfulRead(relay)
        assertEquals(3, server.requestCount)
        assertEquals(1, synchronized(peers) { peers.size })
    }

    @Test fun dashboardOffAfterDispatchKeepsAcknowledgementAndSharedChannel() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val id = UUID.randomUUID().toString()
        val state = AtomicReference(dashboardPending(id))
        val consent = state.get().dashboardGeneration
        val result = async {
            assertTrue(state.get().panelMayDispatch(id, consent))
            connection.request("panel_action", JSONObject().put("actionId", "fictional-action").put("preview", false), id,
                dispatchAllowed = { state.get().panelMayDispatch(id, consent) })
        }
        withTimeout(3_000) { while (relay.frames.isEmpty()) delay(10) }
        assertEquals(id, relay.frames.take().getString("id"))
        state.set(state.get().dashboardSetting(false))
        relay.send(JSONObject().put("id", id).put("status", 200).put("data",
            JSONObject().put("ok", true).put("sent", true)))
        val reply = withTimeout(3_000) { result.await() }
        val completed = state.get().panel.complete(id, Snapshots.panelResult(reply))
        assertEquals("Sent to Copilot", completed.message)
        assertNull(completed.pending)
        assertTrue(relay.frames.isEmpty())
        assertEquals(1, synchronized(peers) { peers.single().protectedRequests.size })
        assertSuccessfulRead(relay)
        assertTrue(statuses.isEmpty())
        assertFalse(synchronized(peers) { peers.single().closed })
        assertEquals(3, server.requestCount)
    }

    @Test fun workspaceFailureCanToggleUiLivenessWithoutReconnectingRelay() = runBlocking {
        grant()
        val relay = Relay().also { it.enqueue() }
        connection.connect()
        val connected = status("connected")
        var ui = ControllerState(status = connected, foreground = true, now = 100)

        suspend fun receiveState(now: Long) {
            val result = async { connection.request("state", JSONObject()) }
            val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
            assertEquals(epoch, frame.getString("epoch"))
            relay.send(JSONObject().put("id", frame.getString("id")).put("status", 200)
                .put("data", JSONObject("""{"connected":true,"requests":[]}""")))
            val snapshot = Snapshots.requests(result.await())
            ui = ui.receiveRequests(snapshot, now)
        }

        receiveState(100)
        assertTrue(ui.live)
        val workspace = async { runCatching { connection.request("workspace", JSONObject().put("sessionID", JSONObject.NULL)) } }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        relay.send(JSONObject().put("id", frame.getString("id")).put("status", 503)
            .put("data", JSONObject().put("error", "Synthetic workspace unavailable")))
        assertTrue(workspace.await().exceptionOrNull() is IOException)
        // ControllerViewModel.guarded handles this IOException by invalidating snapshots, not the relay.
        ui = ui.staleSnapshots()
        assertFalse(ui.live)
        assertEquals("connected", ui.status.state)
        receiveState(2100)
        assertTrue(ui.live)
        assertEquals("connected", ui.status.state)
        assertEquals(3, server.requestCount)
        assertEquals(1, synchronized(peers) { peers.size })
        assertFalse(synchronized(peers) { peers.single().closed })
        assertTrue(statuses.isEmpty())
    }

    @Test fun peerClosureDuringPollingRepeatsApprovedHandshakeAndDiscardsCloseReason() = runBlocking {
        trace.start()
        grant()
        var relay = Relay().also { it.enqueue() }
        connection.connect()
        repeat(3) { cycle ->
            status("connecting")
            status("pending")
            status("connected")
            for (path in listOf("state", "workspace")) {
                val response = async { connection.request(path, JSONObject()) }
                val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
                assertEquals(path, frame.getJSONObject("payload").getString("path"))
                relay.ack(frame.getString("id"))
                assertTrue(response.await().getBoolean("ok"))
            }
            if (cycle < 2) {
                grant()
                val next = Relay().also { it.enqueue() }
                delay(2000)
                relay.socket.close(1001, "synthetic peer-side reason")
                val disconnected = status("disconnected")
                assertTrue(disconnected.message.contains("Reconnecting safely in 1s"))
                assertFalse(disconnected.message.contains("synthetic peer-side reason"))
                relay = next
            }
        }
        assertEquals(9, server.requestCount)
        assertEquals(3, synchronized(peers) { peers.size })
        assertTrue(synchronized(peers) { peers.dropLast(1).all { it.closed } })
        val captured = trace.snapshot()
        val closure = captured.entries.first { it.event == TraceEvent.RELAY_CLOSED }
        assertEquals(1001, closure.wsCode)
        assertEquals(TracePhase.READY, closure.phase)
        assertTrue(captured.entries.any { it.event == TraceEvent.RETRY_SCHEDULED && it.retryMs == 1000L })
        assertTrue(captured.entries.any { it.event == TraceEvent.CONNECTED && it.attempt == 3 })
        assertFalse(captured.text.contains("synthetic peer-side reason"))
        for (privateValue in listOf(macID, deviceID, epoch, grantID, server.url("/").toString())) {
            assertFalse(captured.text.contains(privateValue))
        }
    }

    @Test fun wrongSenderIsTerminalAndPreservesTrust() {
        grant(); Relay(sender = "phone").enqueue()
        connection.connect()
        status("error")
        assertTrue(synchronized(peers) { peers.single().closed })
        Thread.sleep(1200)
        assertEquals(3, server.requestCount)
    }

    @Test fun pinRejectionIsTerminal() {
        connection.close(); connection = newConnection("pin")
        grant(); Relay().enqueue()
        connection.connect(); status("error")
        assertTrue(synchronized(peers) { peers.single().closed })
    }

    @Test fun nonExactGreetingAndWrongNegotiatedProtocolAreTerminal() {
        grant(); Relay(greeting = """{"version":1,"type":"ready"}""").enqueue()
        connection.connect(); status("error")
        grant(); Relay(selectedProtocol = "other").enqueue()
        connection.connect(); status("error")
        assertEquals(6, server.requestCount)
    }

    @Test fun terminalGrantAuthorizationDoesNotReconnect() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Account does not own this device."}"""))
        connection.connect()
        assertTrue(status("error").message.contains("Account does not own"))
        Thread.sleep(1200)
        assertEquals(1, server.requestCount)
    }

    @Test fun relayConflictRetriesWithFreshAuthorizationAndCryptoWithoutActions() {
        grant()
        server.enqueue(MockResponse().setResponseCode(409))
        grant()
        val relay = Relay().also { it.enqueue() }
        connection.connect()
        status("disconnected")
        status("connected")
        assertEquals(6, server.requestCount)
        assertEquals(2, synchronized(peers) { peers.size })
        assertTrue(synchronized(peers) { peers.first().closed })
        assertTrue(relay.frames.isEmpty())
    }

    @Test fun grantConflictAndRelayUnauthorizedRemainTerminal() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Grant conflict."}"""))
        connection.connect(); status("error")
        Thread.sleep(1200)
        assertEquals(1, server.requestCount)
        grant()
        server.enqueue(MockResponse().setResponseCode(401))
        connection.connect(); status("error")
        Thread.sleep(1200)
        assertEquals(4, server.requestCount)
    }

    @Test fun expiredOrMismatchedLeaseNeverOpensRelay() {
        grant(lease(expiresAt = System.currentTimeMillis() - 1))
        connection.connect(); status("error")
        assertEquals(2, server.requestCount)
        grant(lease().put("deviceID", UUID.randomUUID().toString()))
        connection.connect(); status("error")
        assertEquals(4, server.requestCount)
    }

    @Test fun serverClockNineteenMillisAheadDoesNotRejectBoundedLeaseOrExtendLocalExpiry() {
        val now = 1_800_000_000_000L
        connection.close(); connection = newConnection(clockWall = { now })
        grant(lease(expiresAt = now + 30_019).put("validForMs", 30_000))
        val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        elapsed.set(30_000)
        assertThrows(IOException::class.java) { runBlocking { connection.request("reply", JSONObject()) } }
        assertTrue(relay.frames.isEmpty())
    }

    @Test fun fullResponseDelayConsumesValidityWithoutOpeningRelayOrRetrying() {
        server.enqueue(MockResponse().setBody(JSONObject().put("id", grantID).put("challenge", "authorize").toString()))
        server.enqueue(MockResponse().setBody(lease().put("validForMs", 200).toString())
            .setBodyDelay(350, TimeUnit.MILLISECONDS)
            .addHeader("Set-Cookie", "__Host-notify-relay=${"r".repeat(43)}; Path=/; Secure; HttpOnly; Max-Age=30"))
        connection.connect(); status("error")
        Thread.sleep(1100)
        assertEquals(2, server.requestCount)
    }

    @Test fun explicitInvalidDurationIsTerminalAndNeverUsesTimestampFallback() {
        for (invalid in listOf(JSONObject.NULL, 0, -1, 30001, 1.5, "30000")) {
            grant(lease().put("validForMs", invalid))
            connection.connect(); status("error")
        }
        assertEquals(12, server.requestCount)
    }

    @Test fun insecureOrMissingRelayCookieFailsBeforeUpgrade() {
        grant(cookie = "__Host-notify-relay=fixture; Path=/; HttpOnly")
        connection.connect(); status("error")
        assertEquals(2, server.requestCount)
    }

    @Test fun sleepingPastLeaseRejectsActionsWithoutDispatch() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        elapsed.set(30_000)
        val failure = assertThrows(IOException::class.java) { runBlocking { connection.request("reply", JSONObject()) } }
        assertTrue(failure.message!!.contains("uncertain"))
        assertTrue(relay.frames.isEmpty())
        connection.close()
    }

    @Test fun wallExpiryAlsoRejectsActions() {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        wallOffset.set(30_000)
        assertThrows(IOException::class.java) { runBlocking { connection.request("control", JSONObject()) } }
        assertTrue(relay.frames.isEmpty())
        connection.close()
    }

    @Test fun expiryDuringProtectionDoesNotQueueAction() {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        synchronized(peers) { peers.single().afterProtect = { elapsed.set(30_000) } }
        assertThrows(IOException::class.java) { runBlocking { connection.request("reply", JSONObject()) } }
        assertTrue(relay.frames.isEmpty())
        connection.close()
    }

    @Test fun disconnectRejectsMutationWithoutReplayAndExplicitRestartUsesFreshCrypto() = runBlocking {
        grant(); val old = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val result = async { runCatching { connection.request("reply", JSONObject()) } }
        withTimeout(3000) { while (old.frames.isEmpty()) delay(10) }
        old.socket.close(1001, "gone")
        val error = result.await().exceptionOrNull()
        assertTrue(error is IOException && error.message!!.contains("uncertain"))
        grant(); val fresh = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        assertTrue(fresh.frames.isEmpty())
        assertEquals(2, synchronized(peers) { peers.size })
        assertTrue(synchronized(peers) { peers.first().closed })
    }

    @Test fun cancelledCallAndLateAckCannotResolveAnotherRequest() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val oldID = UUID.randomUUID().toString()
        val old = async { connection.request("state", JSONObject(), oldID) }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(10) }
        old.cancelAndJoin()
        val freshID = UUID.randomUUID().toString()
        val fresh = async { connection.request("state", JSONObject(), freshID) }
        withTimeout(3000) { while (relay.frames.size < 2) delay(10) }
        relay.ack(oldID); delay(100)
        assertFalse(fresh.isCompleted)
        relay.ack(freshID); assertTrue(fresh.await().getBoolean("ok"))
    }

    @Test fun cancellationBeforeProtectionKeepsTheExistingChannelUsable() = runBlocking {
        val caller = ThreadLocal<Boolean>()
        val cancelAtClock = AtomicReference<(() -> Unit)?>(null)
        connection.close()
        connection = newConnection(clockWall = {
            if (caller.get() == true) cancelAtClock.getAndSet(null)?.invoke()
            System.currentTimeMillis()
        })
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val cancelled = async(caller.asContextElement(true), start = CoroutineStart.LAZY) {
            connection.request("state", JSONObject())
        }
        cancelAtClock.set { cancelled.cancel() }
        cancelled.start(); cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(synchronized(peers) { peers.single().protectedRequests.isEmpty() })
        assertTrue(statuses.isEmpty())
        assertSuccessfulRead(relay)
        assertEquals(3, server.requestCount)
        assertFalse(synchronized(peers) { peers.single().closed })
    }

    @Test fun cancellationAfterProtectionRecoversFreshChannelWithoutTerminalError() = runBlocking {
        grant(); val old = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        grant(); val fresh = Relay().also { it.enqueue() }
        val cancelled = async(start = CoroutineStart.LAZY) { runCatching { connection.request("workspace", JSONObject()) } }
        synchronized(peers) { peers.single().afterProtect = { cancelled.cancel() } }
        cancelled.start(); cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals("disconnected", statuses.poll(3, TimeUnit.SECONDS)?.state)
        assertTrue(old.frames.isEmpty())
        assertTrue(synchronized(peers) { peers.first().closed })
        assertEquals(1, synchronized(peers) { peers.first().protectedRequests.size })
        status("connected")
        assertEquals(2, synchronized(peers) { peers.size })
        assertTrue(fresh.frames.isEmpty())
        assertSuccessfulRead(fresh)
        assertEquals(6, server.requestCount)
    }

    @Test fun cancelledPollRetiresPendingMutationAsUncertainWithoutReplay() = runBlocking {
        grant(); val old = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val mutationID = UUID.randomUUID().toString()
        val mutation = async { runCatching { connection.request("control", JSONObject(), mutationID) } }
        val sent = withTimeout(3000) { while (old.frames.isEmpty()) delay(10); old.frames.take() }
        assertEquals(mutationID, sent.getString("id"))
        grant(); val fresh = Relay().also { it.enqueue() }
        val cancelled = async(start = CoroutineStart.LAZY) { runCatching { connection.request("state", JSONObject()) } }
        synchronized(peers) { peers.single().afterProtect = { cancelled.cancel() } }
        cancelled.start(); cancelled.join()
        val failure = mutation.await().exceptionOrNull() as RemoteRequestFailure
        assertTrue(failure.uncertain)
        assertFalse(failure.notDispatched)
        assertEquals("disconnected", statuses.poll(3, TimeUnit.SECONDS)?.state)
        status("connected")
        assertTrue(fresh.frames.isEmpty())
        assertSuccessfulRead(fresh)
        assertFalse(synchronized(peers) {
            peers.last().protectedRequests.any { it.getString("id") == mutationID }
        })
    }

    @Test fun cancelledMutationAfterProtectionIsNotSentOrAutomaticallyReplayed() = runBlocking {
        grant(); val old = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        grant(); val fresh = Relay().also { it.enqueue() }
        val id = UUID.randomUUID().toString()
        val outcome = AtomicReference<Throwable?>()
        val cancelled = async(start = CoroutineStart.LAZY) {
            runCatching { connection.request("reply", JSONObject(), id) }.also { outcome.set(it.exceptionOrNull()) }
        }
        synchronized(peers) { peers.single().afterProtect = { cancelled.cancel() } }
        cancelled.start(); cancelled.join()
        assertTrue(cancelled.isCancelled)
        val failure = outcome.get()
        assertTrue(failure is CancellationException || failure is RemoteRequestFailure && failure.uncertain && !failure.notDispatched)
        assertEquals(id, synchronized(peers) { peers.first().protectedRequests.single().getString("id") })
        assertTrue(old.frames.isEmpty())
        assertEquals("disconnected", statuses.poll(3, TimeUnit.SECONDS)?.state)
        status("connected")
        assertTrue(fresh.frames.isEmpty())
        assertTrue(synchronized(peers) { peers.last().protectedRequests.isEmpty() })
        assertSuccessfulRead(fresh)
    }

    @Test fun ownerCloseCancelsPostProtectionRecoveryWithoutResurrection() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val cancelled = async(start = CoroutineStart.LAZY) { runCatching { connection.request("state", JSONObject()) } }
        synchronized(peers) { peers.single().afterProtect = { cancelled.cancel() } }
        cancelled.start(); cancelled.join()
        assertEquals("disconnected", statuses.poll(3, TimeUnit.SECONDS)?.state)
        connection.close()
        connection.connect()
        delay(1200)
        assertEquals(3, server.requestCount)
        assertEquals(1, synchronized(peers) { peers.size })
        assertTrue(synchronized(peers) { peers.single().closed })
        assertTrue(relay.frames.isEmpty())
        assertFalse(statuses.any { it.state == "connecting" || it.state == "connected" })
    }

    @Test fun cancellationThrownByCryptoRemainsTerminalValidationFailure() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        synchronized(peers) { peers.single().afterProtect = { throw CancellationException("Synthetic crypto failure") } }
        val failure = runCatching { connection.request("state", JSONObject()) }.exceptionOrNull()
        assertTrue(failure is RemoteRequestFailure && failure.uncertain)
        assertEquals("error", statuses.poll(3, TimeUnit.SECONDS)?.state)
        delay(1200)
        assertEquals(3, server.requestCount)
        assertTrue(relay.frames.isEmpty())
    }

    private suspend fun assertSuccessfulRead(relay: Relay) = coroutineScope {
        val result = async { connection.request("state", JSONObject()) }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        assertEquals("state", frame.getJSONObject("payload").getString("path"))
        relay.ack(frame.getString("id"))
        assertTrue(result.await().getBoolean("ok"))
    }

    @Test fun closeCancelsHandshakeAndDoesNotCloseAccountService() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"${grantID}","challenge":"old"}""").setBodyDelay(1, TimeUnit.SECONDS))
        connection.connect()
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        connection.close()
        connection.connect()
        delay(1200)
        assertEquals(1, server.requestCount)
        assertTrue(synchronized(peers) { peers.single().closed })
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(service.post("/v1/account/session", JSONObject()).getBoolean("ok"))
    }

    @Test fun malformedUtf8IsTerminalNotANetworkRetry() {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        synchronized(peers) { peers.single().decodedOverride = byteArrayOf(0xc3.toByte(), 0x28) }
        relay.ack(UUID.randomUUID().toString())
        status("error")
        Thread.sleep(1100)
        assertEquals(3, server.requestCount)
    }

    @Test fun lateReadAckAfterSleepIsUncertainEvenBeforeLeaseExpires() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val id = UUID.randomUUID().toString()
        val result = async { runCatching { connection.request("state", JSONObject(), id) } }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(10) }
        elapsed.set(12_001)
        relay.ack(id)
        val failure = result.await().exceptionOrNull()
        assertTrue(failure is RemoteRequestFailure && failure.uncertain)
    }

    @Test fun staleGrantCompletionCannotOpenSocketInNewGeneration() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"${grantID}","challenge":"stale"}""").setBodyDelay(1, TimeUnit.SECONDS))
        connection.connect()
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        grant(); val fresh = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        delay(1200)
        assertEquals(4, server.requestCount)
        assertTrue(synchronized(peers) { peers.first().closed })
        assertTrue(fresh.frames.isEmpty())
    }

    @Test fun renewalSignsOnceAndKeepsEpochAndCryptoState() = runBlocking {
        val now = 1_800_000_000_000L
        val clock = AtomicLong(now)
        connection.close(); connection = newConnection(clockWall = clock::get)
        grant(lease(expiresAt = now + 30_019).put("validForMs", 30_000))
        val relay = Relay().also { it.enqueue() }
        server.enqueue(MockResponse().setBody(lease("renew-2", now + 40_019).put("validForMs", 30_000).toString()))
        connection.connect(); status("connected")
        elapsed.set(10_000); clock.set(now + 10_000)
        repeat(3) { server.takeRequest() }
        val renewal = server.takeRequest(12, TimeUnit.SECONDS) ?: error("Renewal was not sent")
        assertEquals("/v1/remote/grant/renew", renewal.path)
        assertEquals(grantID, JSONObject(renewal.body.readUtf8()).getString("id"))
        assertNull(renewal.getHeader("Cookie"))
        assertEquals(listOf("authorize", "renew-1"), synchronized(peers) { peers.single().signed.toList() })
        delay(150)
        elapsed.set(30_000); clock.set(now + 30_000)
        val result = async { connection.request("state", JSONObject()) }
        val frame = withTimeout(3000) { while (relay.frames.isEmpty()) delay(10); relay.frames.take() }
        assertEquals(epoch, frame.getString("epoch"))
        relay.ack(frame.getString("id"))
        assertTrue(result.await().getBoolean("ok"))
        assertEquals(1, synchronized(peers) { peers.size })
    }

    @Test fun consumedRenewalIsNeverRetriedAfterLostResponse() {
        grant(); Relay().enqueue()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // A fresh begin, not the consumed renewal, is the only allowed reconnect request.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Account authorization required."}"""))
        connection.connect(); status("connected")
        repeat(3) { server.takeRequest() }
        val renewal = server.takeRequest(12, TimeUnit.SECONDS) ?: error("Renewal was not sent")
        assertEquals("/v1/remote/grant/renew", renewal.path)
        val fresh = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Fresh grant was not requested")
        assertEquals("/v1/remote/grant/begin", fresh.path)
        status("error")
        assertEquals(5, server.requestCount)
    }

    @Test fun renewal401UsesFreshGrantAndCryptoWithoutReplayingPendingControl() = runBlocking {
        trace.start()
        grant()
        val oldRelay = Relay().also { it.enqueue() }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Connected routing authorization required."}"""))
        grant()
        val freshRelay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val pending = async { runCatching { connection.request("control", JSONObject().put("synthetic", true)) } }
        withTimeout(3000) { while (oldRelay.frames.isEmpty()) delay(10) }
        assertEquals("control", oldRelay.frames.take().getJSONObject("payload").getString("path"))
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/remote/grant/renew", server.takeRequest(12, TimeUnit.SECONDS)?.path)
        status("disconnected")
        status("connected")
        assertEquals(listOf("/v1/remote/grant/begin", "/v1/remote/grant/finish", "/v1/relay"),
            (1..3).map { server.takeRequest(3, TimeUnit.SECONDS)?.path })
        val failure = pending.await().exceptionOrNull()
        assertTrue(failure is RemoteRequestFailure && failure.uncertain)
        assertEquals(7, server.requestCount)
        assertTrue(freshRelay.frames.isEmpty())
        assertEquals(2, synchronized(peers) { peers.size })
        assertTrue(synchronized(peers) { peers.first().closed })
        assertEquals(listOf("authorize", "renew-1"), synchronized(peers) { peers.first().signed.toList() })
        assertEquals(listOf("authorize"), synchronized(peers) { peers.last().signed.toList() })
        assertTrue(trace.snapshot().entries.any {
            it.event == TraceEvent.RENEW_FAILURE && it.category == TraceCategory.RENEWAL_REJECTED && it.httpCode == 401
        })
        assertTrue(trace.snapshot().entries.any { it.event == TraceEvent.RETRY_SCHEDULED && it.retryMs == 1000L })
        val read = async { connection.request("state", JSONObject()) }
        val frame = withTimeout(3000) { while (freshRelay.frames.isEmpty()) delay(10); freshRelay.frames.take() }
        assertEquals("state", frame.getJSONObject("payload").getString("path"))
        freshRelay.ack(frame.getString("id"))
        assertTrue(read.await().getBoolean("ok"))
        assertTrue(freshRelay.frames.isEmpty())
    }

    @Test fun renewal401FreshBeginRejectionIsTerminalWithoutLooping() {
        grant(); Relay().enqueue()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Connected routing authorization required."}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Account or device authorization required."}"""))
        connection.connect(); status("connected")
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/remote/grant/renew", server.takeRequest(12, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/remote/grant/begin", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertTrue(status("error").message.contains("Account or device"))
        assertNull(server.takeRequest(1500, TimeUnit.MILLISECONDS))
        assertEquals(5, server.requestCount)
        assertTrue(synchronized(peers) { peers.all { it.closed } })
    }

    @Test fun renewal401FreshFinishRejectionIsTerminalWithoutLooping() {
        grant(); Relay().enqueue()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Connected routing authorization required."}"""))
        server.enqueue(MockResponse().setBody(JSONObject().put("id", grantID).put("challenge", "reauthorize").toString()))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Signature or account authorization rejected."}"""))
        connection.connect(); status("connected")
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/remote/grant/renew", server.takeRequest(12, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/remote/grant/begin", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/v1/remote/grant/finish", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertTrue(status("error").message.contains("Signature or account"))
        assertNull(server.takeRequest(1500, TimeUnit.MILLISECONDS))
        assertEquals(6, server.requestCount)
        assertTrue(synchronized(peers) { peers.all { it.closed } })
    }

    @Test fun renewal403IsTerminal() {
        grant(); Relay().enqueue()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Connected routing authorization forbidden."}"""))
        connection.connect(); status("connected")
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/remote/grant/renew", server.takeRequest(12, TimeUnit.SECONDS)?.path)
        assertTrue(status("error").message.contains("Connected routing"))
        Thread.sleep(1100)
        assertEquals(4, server.requestCount)
    }

    @Test fun pendingLimitAndPayloadLimitNeverDispatchExtraActions() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connection.request("reply", JSONObject().put("text", "a".repeat(131_072))) }
        }
        val calls = (1..8).map { async { connection.request("state", JSONObject()) } }
        withTimeout(3000) { while (relay.frames.size < 8) delay(10) }
        assertThrows(RemoteRequestFailure::class.java) { runBlocking { connection.request("state", JSONObject()) } }
        assertEquals(8, relay.frames.size)
        calls.forEach { it.cancelAndJoin() }
    }

    @Test fun relay401IsTerminalAndDoesNotExposeCookieToOtherPaths() = runBlocking {
        grant()
        server.enqueue(MockResponse().setResponseCode(401))
        connection.connect(); status("error")
        repeat(3) { server.takeRequest() }
        server.enqueue(MockResponse().setBody("{}"))
        service.post("/v1/account/session", JSONObject())
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun invalidWelcomeBytesAreRejectedBeforeJoining() {
        for (value in listOf("[256]", "[-1]", "[1.5]", """["1"]""", "[]")) {
            grant(); Relay(welcomeBytes = value).enqueue()
            connection.connect(); status("error")
        }
        assertTrue(synchronized(peers) { peers.all { it.joinedPin == null && it.closed } })
    }

    @Test fun pendingApprovalDoesNotAdmitRequestsUntilNativeReady() = runBlocking {
        grant(lease().put("approved", false)); val relay = Relay(autoReady = false).also { it.enqueue() }
        connection.connect(); status("pending")
        assertThrows(RemoteRequestFailure::class.java) { runBlocking { connection.request("state", JSONObject()) } }
        assertTrue(relay.frames.isEmpty())
        relay.send(JSONObject().put("type", "ready").put("epoch", epoch))
        assertEquals("connected", status("connected").state)
    }

    @Test fun invalidAckStatusAndNonObjectDataAreTerminal() {
        for (value in listOf(JSONObject().put("status", "200").put("data", JSONObject()),
            JSONObject().put("status", 600).put("data", JSONObject()),
            JSONObject().put("status", 200).put("data", JSONArray()))) {
            grant(); val relay = Relay().also { it.enqueue() }
            connection.connect(); status("connected")
            relay.send(value.put("id", UUID.randomUUID().toString()))
            status("error")
        }
    }

    @Test fun renewalWithRepeatedNonceIsTerminal() {
        grant(); Relay().enqueue()
        server.enqueue(MockResponse().setBody(lease().toString()))
        connection.connect(); status("connected")
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/remote/grant/renew", server.takeRequest(12, TimeUnit.SECONDS)?.path)
        status("error")
        Thread.sleep(1100)
        assertEquals(4, server.requestCount)
    }

    @Test fun readTimeoutDoesNotReplayAndLateAckCannotResurrectIt() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        server.enqueue(MockResponse().setBody(lease("renew-2").toString()))
        connection.connect(); status("connected")
        val id = UUID.randomUUID().toString()
        val start = System.nanoTime()
        val result = runCatching { connection.request("state", JSONObject(), id) }
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.exceptionOrNull() is RemoteRequestFailure)
        assertTrue("Read waited $took ms", took in 11_500..14_000)
        assertEquals(1, relay.frames.size)
        relay.ack(id)
        delay(100)
        assertEquals(1, relay.frames.size)
    }

    @Test fun mutationAckAfterIts25SecondDeadlineIsUncertain() = runBlocking {
        grant(); val relay = Relay().also { it.enqueue() }
        connection.connect(); status("connected")
        val id = UUID.randomUUID().toString()
        val result = async { runCatching { connection.request("reply", JSONObject(), id) } }
        withTimeout(3000) { while (relay.frames.isEmpty()) delay(10) }
        elapsed.set(25_001)
        relay.ack(id)
        assertTrue((result.await().exceptionOrNull() as RemoteRequestFailure).uncertain)
    }

    @Test fun malformedGrantUtf8DoesNotAutoRetry() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        connection.connect(); status("error")
        Thread.sleep(1100)
        assertEquals(1, server.requestCount)
    }

    @Test fun rememberedGrantUsesExactScopedEndpointAndNativeSignerWithoutDoublePrefix() {
        connection.close()
        val record = rememberedRelationship().copy(account = app.notify.android.controller.Account(
            service.origin.toString().removeSuffix("/"), "synthetic-account-1"), macID = macID, deviceID = deviceID,
            macPublicKey = app.notify.android.controller.encodeKey(pin))
        val examples = rememberedExamples()
        val original = RememberProposal.parse(examples.getJSONObject("enableFinish").getJSONObject("response"),
            rememberedRelationship()).authorization
        val authorization = original.copy(relationship = record, expiresAt = System.currentTimeMillis() + 60000)
        val id = "A".repeat(43)
        val expiry = System.currentTimeMillis() + 29000
        fun challenge(kind: String, connection: Boolean = false): String {
            val value = JSONObject(examples.getJSONObject("phoneGrantBegin").getJSONObject("response").getString("challenge"))
                .put("purpose", "remember-$kind").put("operation", kind).put("origin", record.account.issuer)
                .put("account", record.account.json()).put("macID", macID).put("deviceID", deviceID)
                .put("macPublicKey", record.macPublicKey).put("authorizationExpiresAt", authorization.expiresAt)
                .put("challengeExpiresAt", expiry)
            if (connection) value.put("connectionID", id)
            return value.toString()
        }
        var accepted: RememberAuthorization? = null
        connection = RemoteConnection(service, macID, deviceID, pin, { statuses.add(it) },
            { FixtureCrypto(null).also { peers.add(it) } }, { elapsed.get() }, System::currentTimeMillis,
            remembered = authorization, authorized = { proof, _ -> accepted = proof })
        val raw = challenge("grant")
        server.enqueue(MockResponse().setBody(JSONObject().put("id", id).put("expiresAt", expiry).put("challenge", raw).toString()))
        val result = lease(challenge("renew", true), expiry).put("scope", "remembered")
            .put("generation", authorization.generation).put("authorizationExpiresAt", authorization.expiresAt)
            .put("validForMs", 29000).put("push", JSONObject().put("enabled", false).put("registrationGeneration", JSONObject.NULL))
        result.getJSONObject("renewal").put("id", id)
        server.enqueue(MockResponse().setBody(result.toString()).addHeader("Set-Cookie",
            "__Host-notify-relay=${"A".repeat(43)}; Path=/; Secure; HttpOnly; Max-Age=30"))
        val relay = Relay().also { it.enqueue() }
        connection.connect()
        status("connected")
        assertEquals(authorization, accepted)
        assertEquals(raw, peers.last().signed.single())
        assertEquals("/v1/remote/remember/grant/begin", server.takeRequest().path)
        assertEquals("/v1/remote/remember/grant/finish", server.takeRequest().path)
        assertNotNull(relay.socket)
    }

    @Test fun rememberedWrongKeyCannotInvokeSignerOrDispatchFinish() {
        connection.close()
        val record = rememberedRelationship().copy(account = app.notify.android.controller.Account(
            service.origin.toString().removeSuffix("/"), "synthetic-account-1"), macID = macID, deviceID = deviceID,
            macPublicKey = app.notify.android.controller.encodeKey(pin))
        val authorization = RememberProposal.parse(rememberedExamples().getJSONObject("enableFinish").getJSONObject("response"),
            rememberedRelationship()).authorization.copy(relationship = record)
        connection = RemoteConnection(service, macID, deviceID, pin, { statuses.add(it) },
            { FixtureCrypto(null).also { peers.add(it) } }, { elapsed.get() }, System::currentTimeMillis,
            remembered = authorization)
        server.enqueue(MockResponse().setBody(rememberedExamples().getJSONObject("phoneGrantBegin").getJSONObject("response").toString()))
        connection.connect()
        status("error")
        assertTrue(peers.last().signed.isEmpty())
        assertEquals(1, server.requestCount)
    }

    private class FixtureCrypto(private val failure: String?) : RemoteCrypto {
        var closed = false
        var joinedPin: ByteArray? = null
        var expectedSender: ByteArray? = null
        var afterProtect: (() -> Unit)? = null
        var decodedOverride: ByteArray? = null
        val signed = mutableListOf<String>()
        val protectedRequests = LinkedBlockingQueue<JSONObject>()
        override fun keyPackage() = byteArrayOf(7)
        override fun signAuthorization(challenge: ByteArray): ByteArray {
            signed.add(challenge.toString(Charsets.UTF_8))
            return "signature".toByteArray()
        }
        override fun joinPinned(welcome: ByteArray, tree: ByteArray, pinnedKey: ByteArray) {
            check(failure != "pin") { "Pin mismatch" }
            joinedPin = pinnedKey.clone()
        }
        override fun protect(plaintext: ByteArray): ByteArray = plaintext.also {
            val value = JSONObject(it.toString(Charsets.UTF_8))
            if (value.has("payload")) protectedRequests.add(value)
            afterProtect?.invoke()
        }
        override fun unprotect(frame: ByteArray, expectedSender: ByteArray): ByteArray {
            this.expectedSender = expectedSender.clone()
            decodedOverride?.let { return it }
            val value = JSONObject(frame.toString(Charsets.UTF_8))
            check(value.getString("sender") == expectedSender.toString(Charsets.UTF_8)) { "Wrong sender" }
            return value.getJSONObject("body").toString().toByteArray()
        }
        override fun close() { closed = true }
    }
}
