package app.notify.android.remote

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class HttpFailure(val status: Int, message: String) : IOException(message)

class ServiceClient(val origin: HttpUrl, configuration: OkHttpClient = OkHttpClient()) : Closeable {
    private val cookies = SessionCookies(origin)
    private val http = configuration.newBuilder()
        .dispatcher(Dispatcher()).connectionPool(ConnectionPool()).cache(null)
        .cookieJar(cookies).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    init {
        require(origin.isHttps && origin.encodedPath == "/" && origin.query == null && origin.fragment == null &&
            origin.username.isEmpty() && origin.password.isEmpty()) { "An exact HTTPS Notify service address is required." }
    }

    suspend fun post(path: String, data: JSONObject): JSONObject = post(path, data) {}

    // Capture the cookie from this exact response, not a shared jar that a cancelled grant could overwrite.
    internal suspend fun finishRelayGrant(data: JSONObject): Pair<JSONObject, Cookie> {
        var cookie: Cookie? = null
        val value = post("/v1/remote/grant/finish", data) { response ->
            val found = Cookie.parseAll(response.request.url, response.headers).filter { it.name == "__Host-notify-relay" }
            cookie = found.singleOrNull()?.takeIf { validRelayCookie(it) }
        }
        return value to (cookie ?: throw ProtocolException("The service did not provide a valid relay authorization cookie."))
    }

    internal fun openRelay(cookie: Cookie, listener: WebSocketListener): WebSocket {
        if (!validRelayCookie(cookie)) throw ProtocolException("Relay authorization cookie is invalid or expired.")
        val request = Request.Builder().url(origin.newBuilder().encodedPath("/v1/relay").build())
            .header("Origin", origin.toString().removeSuffix("/"))
            .header("Sec-WebSocket-Protocol", "notify-relay-v1")
            .header("Cookie", "${cookie.name}=${cookie.value}").build()
        return http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build().newWebSocket(request, listener)
    }

    private fun validRelayCookie(cookie: Cookie) =
        cookie.name == "__Host-notify-relay" && cookie.secure && cookie.httpOnly && cookie.hostOnly &&
            cookie.domain == origin.host && cookie.path == "/" && cookie.expiresAt > System.currentTimeMillis() &&
            cookie.value.matches(Regex("^[A-Za-z0-9_-]{43}$"))

    private suspend fun post(path: String, data: JSONObject, received: (Response) -> Unit): JSONObject {
        require(path.startsWith("/v1/") && '?' !in path && '#' !in path) { "Invalid service endpoint." }
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32_768) { "The service request is too large." }
        val request = Request.Builder().url(origin.newBuilder().encodedPath(path).build())
            .header("Origin", origin.toString().removeSuffix("/")).header("Accept", "application/json")
            .post(bytes.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { read(it).also { _ -> received(response) } }
                        continuation.resumeWith(Result.success(value))
                    } catch (failure: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private fun read(response: Response): JSONObject {
        val body = response.body ?: throw ProtocolException("The Notify service returned an empty response.")
        if (body.contentLength() > 1_064_960) throw ProtocolException("The Notify service response is too large.")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > 1_064_960) throw ProtocolException("The Notify service response is too large.")
                output.write(buffer, 0, count)
            }
        }
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        val fallback = "Request failed (${response.code})."
        val value = try {
            val parser = JSONTokener(raw)
            val parsed = parser.nextValue()
            if (parsed !is JSONObject || parser.nextClean() != '\u0000') throw JSONException("Expected one JSON object.")
            parsed
        } catch (error: JSONException) {
            if (!response.isSuccessful) throw HttpFailure(response.code, fallback)
            throw ProtocolException("The Notify service returned an invalid response.")
        }
        if (!response.isSuccessful) {
            val message = value.opt("error") as? String
            throw HttpFailure(response.code, message?.takeIf { it.isNotBlank() && it.length <= 500 } ?: fallback)
        }
        return value
    }

    override fun close() {
        cookies.clear()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}

private class SessionCookies(private val origin: HttpUrl) : CookieJar {
    private val values = mutableMapOf<String, Cookie>()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != origin.host || url.port != origin.port || !url.isHttps) return
        for (cookie in cookies) {
            if (cookie.name in setOf("__Host-notify-account", "__Host-notify-account-flow") &&
                cookie.secure && cookie.httpOnly && cookie.hostOnly &&
                cookie.domain == origin.host && cookie.path == "/") values[cookie.name] = cookie
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
        return if (url.host == origin.host && url.port == origin.port && url.isHttps)
            values.values.filter { it.matches(url) } else emptyList()
    }

    @Synchronized fun clear() = values.clear()
}
