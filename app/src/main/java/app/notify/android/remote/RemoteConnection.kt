package app.notify.android.remote

import android.os.SystemClock
import app.notify.android.crypto.DeviceIdentity
import app.notify.android.crypto.NativeChannel
import java.io.Closeable
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class RemoteStatus(val state: String, val message: String)

class RemoteRequestFailure(message: String, val uncertain: Boolean, val status: Int? = null) : IOException(message)

internal interface RemoteCrypto : Closeable {
    fun keyPackage(): ByteArray
    fun signAuthorization(challenge: ByteArray): ByteArray
    fun joinPinned(welcome: ByteArray, tree: ByteArray, pinnedKey: ByteArray)
    fun protect(plaintext: ByteArray): ByteArray
    fun unprotect(frame: ByteArray, expectedSender: ByteArray): ByteArray
}

class RemoteConnection internal constructor(
    private val service: ServiceClient,
    private val macID: String,
    private val deviceID: String,
    pinnedMacKey: ByteArray,
    private val onStatus: (RemoteStatus) -> Unit,
    private val openCrypto: () -> RemoteCrypto,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val trace: ConnectionTrace? = null,
) : Closeable {
    constructor(
        service: ServiceClient, identity: DeviceIdentity, macID: String, deviceID: String,
        pinnedMacKey: ByteArray, trace: ConnectionTrace? = null, onStatus: (RemoteStatus) -> Unit,
    ) : this(service, macID, deviceID, pinnedMacKey, onStatus, {
        val channel = NativeChannel.open(identity, "phone")
        object : RemoteCrypto {
            override fun keyPackage() = channel.keyPackage()
            override fun signAuthorization(challenge: ByteArray) = channel.signAuthorization(challenge)
            override fun joinPinned(welcome: ByteArray, tree: ByteArray, pinnedKey: ByteArray) =
                channel.joinPinned(welcome, tree, pinnedKey)
            override fun protect(plaintext: ByteArray) = channel.protect(plaintext)
            override fun unprotect(frame: ByteArray, expectedSender: ByteArray) = channel.unprotect(frame, expectedSender)
            override fun close() = channel.close()
        }
    }, SystemClock::elapsedRealtime, System::currentTimeMillis, trace)

    private val pin = pinnedMacKey.clone()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closed = false
    private var generation = 0L
    private var attempts = 0
    private var active: Attempt? = null
    private var retry: Job? = null

    init {
        require(UUID_PATTERN.matches(macID) && UUID_PATTERN.matches(deviceID)) { "Invalid paired device identifier." }
        require(pin.size == 65 && pin[0] == 4.toByte()) { "A complete pinned Mac P256 public key is required." }
    }

    private class Attempt(val generation: Long, parent: Job?, val traceAttempt: Int) {
        val job = SupervisorJob(parent)
        val scope = CoroutineScope(job + Dispatchers.IO)
        val events = Channel<Event>(8)
        var peer: RemoteCrypto? = null
        var socket: WebSocket? = null
        var stage = "connecting"
        var epoch: String? = null
        var lease: Lease? = null
        var expiry: Job? = null
        var handshake: Job? = null
        val pending = mutableMapOf<String, PendingCall>()
        val usedIDs = mutableSetOf<String>()
    }

    private data class Lease(val expiresAt: Long, val deadline: Long, val id: String, val challenge: String, val approved: Boolean)
    private data class PendingCall(
        val result: CompletableDeferred<JSONObject>,
        val deadline: Long,
        val expiresAt: Long,
    )
    private sealed interface Event {
        data class Open(val protocol: String?) : Event
        data class Text(val text: String) : Event
        data class Binary(val bytes: ByteString) : Event
        data class Failed(val error: Throwable, val httpCode: Int? = null, val wsCode: Int? = null) : Event
    }

    fun connect() = synchronized(lock) {
        if (!closed) {
            attempts = 0
            start()
        }
    }

    private fun current(a: Attempt) = !closed && active === a && generation == a.generation
    private fun live(a: Attempt): Boolean = a.lease?.let { wall() < it.expiresAt && elapsed() < it.deadline } == true

    private fun start() {
        dispose()
        val a = Attempt(generation, scope.coroutineContext[Job], trace?.newAttempt() ?: 0)
        active = a
        status("connecting", "Connecting to your Mac…")
        a.scope.launch {
            try {
                synchronized(lock) {
                    if (!current(a)) return@launch
                    a.peer = crypto { openCrypto() }
                }
                note(a, TraceEvent.GRANT_BEGIN)
                val grant = service.post("/v1/remote/grant/begin",
                    JSONObject().put("role", "phone").put("macID", macID).put("deviceID", deviceID))
                val signature = synchronized(lock) {
                    if (!current(a)) return@launch
                    val id = grant.opt("id") as? String
                    val challenge = grant.opt("challenge") as? String
                    requireProtocol(id != null && GRANT_PATTERN.matches(id) && validChallenge(challenge), "Invalid channel authorization challenge.")
                    sign(a, challenge!!)
                }
                val started = LeaseStart(wall(), elapsed())
                val (result, cookie) = service.finishRelayGrant(JSONObject().put("id", grant.getString("id")).put("signature", signature))
                synchronized(lock) {
                    if (!current(a)) return@launch
                    applyLease(a, validateLease(a, result, grant.getString("id"), started))
                    note(a, TraceEvent.GRANT_FINISHED)
                    a.stage = "greeting"
                    requireLive(a)
                    a.socket = service.openRelay(cookie, listener(a))
                    a.handshake = a.scope.launch {
                        delay(120_000)
                        synchronized(lock) {
                            if (current(a)) note(a, TraceEvent.HANDSHAKE_TIMEOUT, TraceCategory.TIMEOUT)
                            fail(a, IOException("Pairing or connection timed out. Check your Mac."))
                        }
                    }
                }
                for (event in a.events) {
                    synchronized(lock) {
                        if (!current(a)) return@launch
                        receive(a, event)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                synchronized(lock) { fail(a, error) }
            }
        }
    }

    private fun listener(a: Attempt) = object : WebSocketListener() {
        private fun offer(event: Event) {
            synchronized(lock) {
                if (!current(a)) return
                if (!a.events.trySend(event).isSuccess) fail(a, ProtocolException("Relay receive buffer exceeded."))
            }
        }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            offer(Event.Open(response.header("Sec-WebSocket-Protocol")))
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > MAX_FRAME) offer(Event.Failed(ProtocolException("Relay frame is too large.")))
            else offer(Event.Text(text))
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size !in 1..MAX_FRAME) offer(Event.Failed(ProtocolException("Invalid relay frame size.")))
            else offer(Event.Binary(bytes))
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = response?.let {
                if (it.code == 409) IOException("Previous relay connection is still closing.")
                else HttpFailure(it.code, "Relay authorization or upgrade failed (${it.code}).")
            } ?: t
            response?.close()
            offer(Event.Failed(error, httpCode = response?.code))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            offer(Event.Failed(if (code in setOf(1002, 1003, 1007, 1008, 1009))
                ProtocolException("Relay rejected the channel ($code). Check authorization and the paired Mac.")
            else IOException("Mac connection lost. No actions were retried."), wsCode = code))
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            offer(Event.Failed(IOException("Mac connection closed. No actions were retried."), wsCode = code))
        }
    }

    private fun receive(a: Attempt, event: Event) {
        if (event is Event.Failed) {
            note(a, if (event.wsCode != null) TraceEvent.RELAY_CLOSED else TraceEvent.RELAY_FAILURE,
                if (event.httpCode == 409) TraceCategory.RELAY_CONFLICT
                else if (event.wsCode != null) TraceCategory.PEER_CLOSE else TraceCategory.from(event.error),
                httpCode = event.httpCode, wsCode = event.wsCode)
            throw event.error
        }
        requireLive(a)
        if (event is Event.Open) {
            note(a, TraceEvent.RELAY_OPENED)
            requireProtocol(event.protocol == "notify-relay-v1", "Invalid relay subprotocol.")
            a.scope.launch { renew(a) }
            return
        }
        if (a.stage == "greeting") {
            requireProtocol(event is Event.Text && event.text == """{"type":"ready","version":1}""", "Invalid relay greeting.")
            val keyPackage = crypto { a.peer!!.keyPackage() }
            requireProtocol(keyPackage.size in 1..MAX_FRAME, "Invalid phone key package.")
            send(a, JSONObject().put("type", "key_package").put("bytes", JSONArray(keyPackage.map { it.toInt() and 255 }))
                .toString().toByteArray())
            a.stage = "welcome"
            return
        }
        requireProtocol(event is Event.Binary, "Binary relay frames required.")
        val bytes = (event as Event.Binary).bytes.toByteArray()
        if (a.stage == "welcome") {
            note(a, TraceEvent.WELCOME_RECEIVED)
            val value = json(bytes)
            requireProtocol(shape(value, "type", "welcome", "tree") && value.opt("type") == "welcome", "Invalid encrypted-channel welcome.")
            crypto { a.peer!!.joinPinned(byteArray(value.opt("welcome")), byteArray(value.opt("tree")), pin.clone()) }
            a.stage = "approval"
            note(a, TraceEvent.AWAITING_READY)
            encrypted(a, JSONObject().put("type", "hello"))
            status("pending", "Compare the complete public key on your Mac, then approve there.")
            return
        }
        val value = json(crypto { a.peer!!.unprotect(bytes, "native".toByteArray()) })
        requireLive(a)
        if (a.stage == "approval") {
            if (shape(value, "type") && value.opt("type") == "pending") return
            requireProtocol(shape(value, "type", "epoch") && value.opt("type") == "ready" && uuid(value.opt("epoch")),
                "Invalid encrypted ready response.")
            a.epoch = value.getString("epoch")
            a.stage = "ready"
            note(a, TraceEvent.CONNECTED)
            attempts = 0
            a.handshake?.cancel()
            status("connected", "Encrypted channel connected.")
            return
        }
        val status = integer(value.opt("status"))
        requireProtocol(a.stage == "ready" && shape(value, "id", "status", "data") &&
            uuid(value.opt("id")) && status != null && status in 100..599 && value.opt("data") is JSONObject,
            "Invalid encrypted RPC response.")
        val call = a.pending.remove(value.getString("id").lowercase()) ?: return
        if (elapsed() >= call.deadline || wall() >= call.expiresAt) {
            call.result.completeExceptionally(uncertainty())
            return
        }
        val data = value.getJSONObject("data")
        note(a, TraceEvent.RPC_RESULT, httpCode = status!!.toInt())
        if (status in 200..299) call.result.complete(data)
        else {
            val message = (data.opt("error") as? String)?.takeIf { it.isNotBlank() && it.length <= 500 }
                ?: "Mac request failed ($status)."
            call.result.completeExceptionally(RemoteRequestFailure(message, false, status.toInt()))
        }
    }

    suspend fun request(path: String, data: JSONObject, id: String = UUID.randomUUID().toString()): JSONObject {
        // Fence before dispatching to IO: a queued caller must not migrate to a newly connected channel.
        val expected = synchronized(lock) {
            active?.takeIf { it.stage == "ready" }
                ?: throw RemoteRequestFailure("An approved, connected Mac is required. No actions are queued.", false)
        }
        return withContext(Dispatchers.IO) {
            require(path in setOf("state", "reply", "workspace", "control") && UUID_PATTERN.matches(id)) { "Unsupported Mac request or invalid request ID." }
            val payload = JSONObject().put("path", path).put("data", data)
            require(payload.toString().toByteArray().size <= 131_072) { "The Mac request is too large." }
            val key = id.lowercase()
            val timeout = if (path == "reply" || path == "control") 25_000L else 12_000L
            val call = PendingCall(CompletableDeferred(), elapsed() + timeout, wall() + timeout)
            val context = currentCoroutineContext()
            val a = synchronized(lock) {
                context.ensureActive()
                val a = expected
                if (!current(a)) throw uncertainty()
                require(!a.usedIDs.contains(key)) { "Request ID was already used. Nothing was resent." }
                if (a.pending.size >= 8) throw RemoteRequestFailure("Connection is busy. Wait for the current requests.", false)
                if (a.usedIDs.size >= 4096) {
                    fail(a, IOException("Connection request history is full. Reconnecting without replay."))
                    throw RemoteRequestFailure("Connection is refreshing. Nothing was sent.", false)
                }
                try {
                    requireLive(a)
                    a.usedIDs.add(key)
                    a.pending[key] = call
                    val bytes = crypto { a.peer!!.protect(JSONObject().put("id", id).put("epoch", a.epoch)
                        .put("payload", payload).toString().toByteArray()) }
                    context.ensureActive()
                    if (elapsed() >= call.deadline || wall() >= call.expiresAt) throw uncertainty()
                    send(a, bytes)
                    if (path == "state" || path == "workspace")
                        note(a, if (path == "state") TraceEvent.STATE_SENT else TraceEvent.WORKSPACE_SENT)
                } catch (error: Exception) {
                    fail(a, error)
                    throw uncertainty()
                }
                a
            }
            try {
                withTimeout(minOf(call.deadline - elapsed(), call.expiresAt - wall()).coerceAtLeast(1)) { call.result.await() }
            } catch (_: TimeoutCancellationException) {
                synchronized(lock) { if (current(a)) note(a, TraceEvent.RPC_TIMEOUT, TraceCategory.TIMEOUT) }
                throw uncertainty()
            } catch (error: CancellationException) {
                throw CancellationException("Request cancelled; delivery is uncertain. Nothing was retried.").also { it.initCause(error) }
            } finally {
                synchronized(lock) { if (a.pending[key] === call) a.pending.remove(key) }
                call.result.cancel()
            }
        }
    }

    private fun sign(a: Attempt, challenge: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(crypto { a.peer!!.signAuthorization(challenge.toByteArray()) })

    private suspend fun renew(a: Attempt) {
        var nextRenewal = elapsed() + 10_000
        while (true) {
            delay((nextRenewal - elapsed()).coerceAtLeast(0))
            nextRenewal = elapsed() + 10_000
            try {
                val previous = synchronized(lock) {
                    if (!current(a)) return
                    requireLive(a)
                    a.lease!!
                }
                val input = synchronized(lock) {
                    if (!current(a)) return
                    requireLive(a)
                    val signature = sign(a, previous.challenge)
                    requireLive(a)
                    JSONObject().put("id", previous.id).put("signature", signature)
                }
                val started = LeaseStart(wall(), elapsed())
                synchronized(lock) { if (current(a)) note(a, TraceEvent.RENEW_BEGIN) }
                val result = withTimeout(10_000) { service.post("/v1/remote/grant/renew", input) }
                synchronized(lock) {
                    if (!current(a)) return
                    requireLive(a)
                    applyLease(a, validateLease(a, result, previous.id, started, previous))
                    note(a, TraceEvent.RENEW_SUCCESS)
                }
            } catch (error: Exception) {
                // A lost response may have consumed the nonce. Retire the entire channel, never retry renewal.
                synchronized(lock) {
                    if (current(a)) {
                        // A 401 here can denote a retired route; a fresh grant still checks account, enrollment and signature.
                        val renewalRejected = error is HttpFailure && error.status == 401
                        note(a, TraceEvent.RENEW_FAILURE,
                            if (renewalRejected) TraceCategory.RENEWAL_REJECTED else TraceCategory.from(error),
                            httpCode = (error as? HttpFailure)?.status)
                        fail(a, when {
                            renewalRejected -> IOException("Channel renewal was rejected. Fresh authorization is required.", error)
                            error is TimeoutCancellationException ->
                                IOException("Channel authorization renewal timed out. No actions were retried.")
                            else -> error
                        })
                    }
                }
                return
            }
        }
    }

    private fun validateLease(a: Attempt, value: JSONObject, id: String, started: LeaseStart, previous: Lease? = null): Lease {
        val renewal = value.opt("renewal") as? JSONObject
        val challenge = renewal?.opt("challenge") as? String
        val approved = value.opt("approved") as? Boolean
        requireProtocol(current(a) && value.opt("role") == "phone" && value.opt("macID") == macID &&
            value.opt("deviceID") == deviceID && approved != null && !(previous?.approved == true && !approved) &&
            GRANT_PATTERN.matches(id) && renewal?.opt("id") == id && validChallenge(challenge) &&
            challenge != previous?.challenge, "Channel authorization does not match this phone or has expired.")
        val timing = leaseTiming(value, started, LeaseStart(wall(), elapsed()))
        return Lease(timing.expiresAt, timing.deadline, id, challenge!!, approved!!)
    }

    private fun applyLease(a: Attempt, lease: Lease) {
        a.lease = lease
        a.expiry?.cancel()
        a.expiry = a.scope.launch {
            delay(minOf(lease.expiresAt - wall(), lease.deadline - elapsed()).coerceAtLeast(0))
            synchronized(lock) {
                if (current(a) && a.lease === lease) {
                    note(a, TraceEvent.LEASE_EXPIRED, TraceCategory.TIMEOUT)
                    fail(a, IOException("Channel authorization expired. No actions were retried."))
                }
            }
        }
    }

    private fun requireLive(a: Attempt) {
        if (!current(a) || !live(a)) throw uncertainty()
    }

    private fun encrypted(a: Attempt, value: JSONObject) {
        requireLive(a)
        val plaintext = value.toString().toByteArray()
        requireProtocol(plaintext.size in 1..MAX_FRAME, "Encrypted request is too large.")
        send(a, crypto { a.peer!!.protect(plaintext) })
    }

    private fun send(a: Attempt, bytes: ByteArray) {
        requireLive(a)
        requireProtocol(bytes.size in 1..MAX_FRAME, "Invalid encrypted frame size.")
        val socket = a.socket ?: throw uncertainty()
        if (socket.queueSize() + bytes.size > MAX_FRAME || !socket.send(bytes.toByteString())) throw uncertainty()
    }

    private fun fail(a: Attempt, error: Throwable) {
        if (!current(a)) return
        note(a, TraceEvent.CHANNEL_FAILURE, TraceCategory.from(error), httpCode = (error as? HttpFailure)?.status)
        val transient = when (error) {
            is HttpFailure -> error.status == 429 || error.status >= 500
            is ProtocolException, is CharacterCodingException, is SSLException -> false
            is IOException -> true
            else -> false
        }
        dispose()
        if (!transient) {
            status("error", if (error is HttpFailure || error is ProtocolException) error.message ?: "Channel authorization failed."
                else "Encrypted channel validation failed. Check the saved paired Mac; trust was not changed.")
            return
        }
        val wait = if (attempts < 5) 1000L shl attempts++ else 30_000L
        note(a, TraceEvent.RETRY_SCHEDULED, retryMs = wait)
        val fence = generation
        status("disconnected", "${error.message ?: "Mac connection lost."} Reconnecting safely in ${wait / 1000}s; no actions will be retried.")
        retry = scope.launch {
            delay(wait)
            synchronized(lock) { if (!closed && generation == fence) start() }
        }
    }

    private fun dispose() {
        generation++
        retry?.cancel()
        retry = null
        val a = active
        active = null
        if (a != null) {
            note(a, TraceEvent.CHANNEL_RETIRED)
            a.job.cancel()
            a.events.cancel()
            a.socket?.cancel()
            try { a.peer?.close() } finally {
                a.pending.values.forEach { it.result.completeExceptionally(uncertainty()) }
            }
            a.pending.clear()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            dispose()
            scope.cancel()
            status("disconnected", "Disconnected. No actions are queued.")
        }
    }

    private fun status(state: String, message: String) {
        try { onStatus(RemoteStatus(state, message)) } catch (_: Exception) { /* UI observers do not own the channel. */ }
    }

    private fun note(a: Attempt, event: TraceEvent, category: TraceCategory = TraceCategory.NONE,
        httpCode: Int? = null, wsCode: Int? = null, retryMs: Long? = null) {
        trace?.record(event, a.traceAttempt, TracePhase.from(a.stage), category, httpCode, wsCode, retryMs,
            a.lease?.let { minOf(it.expiresAt - wall(), it.deadline - elapsed()) })
    }

    private companion object {
        const val MAX_FRAME = 1_064_960
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
        val GRANT_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        fun uuid(value: Any?) = value is String && UUID_PATTERN.matches(value)
        fun integer(value: Any?): Long? = when (value) {
            is Int -> value.toLong()
            is Long -> value.takeIf { it in -9_007_199_254_740_991L..9_007_199_254_740_991L }
            else -> null
        }
        fun validChallenge(value: String?) = value != null && value.isNotEmpty() && value.toByteArray().size <= 4096
        fun shape(value: JSONObject, vararg fields: String) = value.length() == fields.size && fields.all(value::has)
        fun requireProtocol(valid: Boolean, message: String) { if (!valid) throw ProtocolException(message) }
        fun uncertainty() = RemoteRequestFailure("Connection lost; delivery is uncertain. Check the Mac before sending again. Nothing was retried.", true)
        fun <T> crypto(operation: () -> T): T {
            try { return operation() }
            catch (error: Exception) { throw ProtocolException("Encrypted channel validation failed. Check the saved paired Mac.").also { it.initCause(error) } }
            catch (error: LinkageError) { throw ProtocolException("Native encrypted channel is unavailable on this device.").also { it.initCause(error) } }
        }
        fun json(bytes: ByteArray): JSONObject {
            requireProtocol(bytes.size in 1..MAX_FRAME, "Invalid encrypted message size.")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) { throw ProtocolException("Invalid encrypted UTF-8 message.") }
            val parser = JSONTokener(text)
            val value = parser.nextValue()
            requireProtocol(value is JSONObject && parser.nextClean() == '\u0000', "Invalid encrypted JSON message.")
            return value as JSONObject
        }
        fun byteArray(value: Any?): ByteArray {
            requireProtocol(value is JSONArray && value.length() in 1..MAX_FRAME, "Invalid welcome bytes.")
            val array = value as JSONArray
            return ByteArray(array.length()) {
                val byte = integer(array.get(it))
                requireProtocol(byte != null && byte in 0..255, "Invalid welcome byte value.")
                byte!!.toByte()
            }
        }
    }
}
