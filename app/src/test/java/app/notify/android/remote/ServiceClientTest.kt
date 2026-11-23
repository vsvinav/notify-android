package app.notify.android.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServiceClientTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    private lateinit var client: ServiceClient

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = ServiceClient(server.url("/"), OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    }

    @After fun stop() {
        client.close()
        server.shutdown()
    }

    @Test fun nativeRequestsKeepTheBrowserFlowCookiesAndExactHttpsOrigin() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"challenge":"test"}""")
            .addHeader("Set-Cookie", "__Host-notify-account-flow=synthetic; Path=/; Secure; HttpOnly; SameSite=Strict"))
        server.enqueue(MockResponse().setBody("""{"scope":"account"}"""))
        client.post("/v1/account/login/options", JSONObject())
        client.post("/v1/account/login/verify", JSONObject())
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(server.url("/").toString().removeSuffix("/"), first.getHeader("Origin"))
        assertEquals("application/json", first.getHeader("Content-Type"))
        assertNull(first.getHeader("Cookie"))
        assertEquals("__Host-notify-account-flow=synthetic", second.getHeader("Cookie"))
    }

    @Test fun enrollment401RetainsItsActualMessageInsteadOfClaimingAccountExpiry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Mac enrollment expired."}"""))
        val failure = assertThrows(HttpFailure::class.java) {
            runBlocking { client.post("/v1/remote/grant/start", JSONObject()) }
        }
        assertEquals(401, failure.status)
        assertEquals("Mac enrollment expired.", failure.message)
    }

    @Test fun redirectDoesNotForwardAnAccountRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirected")))
        assertThrows(HttpFailure::class.java) {
            runBlocking { client.post("/v1/account/session", JSONObject()) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun droppedResponseNeverAutomaticallyRepeatsAPost() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(IOException::class.java) {
            runBlocking { client.post("/v1/account/register/verify", JSONObject()) }
        }
        assertEquals(1, server.requestCount)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    }

    @Test fun untrustedCertificateFailsClosed() {
        val untrusted = ServiceClient(server.url("/"))
        try {
            server.enqueue(MockResponse().setBody("{}"))
            assertThrows(IOException::class.java) {
                runBlocking { untrusted.post("/v1/account/session", JSONObject()) }
            }
        } finally { untrusted.close() }
    }

    @Test fun oversizedAndTrailingResponsesAreRejected() {
        server.enqueue(MockResponse().setBody(" ".repeat(1_064_961)))
        server.enqueue(MockResponse().setBody("{} trailing"))
        repeat(2) {
            assertThrows(IOException::class.java) {
                runBlocking { client.post("/v1/account/session", JSONObject()) }
            }
        }
    }

    @Test fun rememberedFinishCapturesOnlyItsOwnRelayCookie() = runBlocking {
        val relay = "A".repeat(43)
        server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie",
            "__Host-notify-relay=$relay; Path=/; Secure; HttpOnly; Max-Age=30"))
        val (_, cookie) = client.finishRelayGrant(JSONObject(), remembered = true)
        assertEquals(relay, cookie.value)
        assertEquals("/v1/remote/remember/grant/finish", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("{}"))
        assertThrows(IOException::class.java) { runBlocking { client.finishRelayGrant(JSONObject(), remembered = true) } }
        Unit
    }
    @Test fun retiredLogoutCannotOverwriteNewLoginCookies() = runBlocking {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val fresh = ServiceClient(server.url("/"), OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
        try {
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(300, TimeUnit.MILLISECONDS)
                .addHeader("Set-Cookie", "__Host-notify-account=; Path=/; Secure; HttpOnly; Max-Age=0"))
            val logout = async { client.post("/v1/account/logout", JSONObject()) }
            kotlinx.coroutines.delay(100)
            server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie",
                "__Host-notify-account=new-session; Path=/; Secure; HttpOnly"))
            fresh.post("/v1/account/login/verify", JSONObject())
            logout.await()
            client.close()
            server.enqueue(MockResponse().setBody("{}"))
            fresh.post("/v1/account/session", JSONObject())
            server.takeRequest(); server.takeRequest()
            assertEquals("__Host-notify-account=new-session", server.takeRequest().getHeader("Cookie"))
        } finally { fresh.close() }
    }
}
