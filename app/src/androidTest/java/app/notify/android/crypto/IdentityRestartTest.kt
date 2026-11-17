@file:Suppress("DEPRECATION")

package app.notify.android.crypto

import android.content.Context
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.util.Base64
import java.util.UUID

/** Opt-in two-invocation test. Never reads or removes the application's enrolled identity. */
@Suppress("DEPRECATION")
class IdentityRestartTest : InstrumentationTestCase() {
    fun testIdentitySurvivesSeparateInstrumentationProcess() {
        val phase = (instrumentation as InstrumentationTestRunner)
            .arguments.getString("notifyIdentityRestartPhase") ?: return
        val store = instrumentation.targetContext
            .getSharedPreferences("notify-crypto-restart-test", Context.MODE_PRIVATE)
        when (phase) {
            "prepare" -> {
                check(!store.contains("id")) { "Verify/clean up the previous restart fixture first" }
                val id = UUID.randomUUID().toString()
                check(store.edit().putString("id", id).commit())
                val identity = DeviceIdentity.create(id)
                check(store.edit()
                    .putString("public", Base64.encodeToString(identity.publicKey, Base64.NO_WRAP))
                    .putInt("pid", android.os.Process.myPid())
                    .commit())
            }
            "verify" -> {
                val id = requireNotNull(store.getString("id", null)) { "Run prepare first" }
                try {
                    assertFalse("Requires a new process", store.getInt("pid", -1) == android.os.Process.myPid())
                    val public = Base64.decode(requireNotNull(store.getString("public", null)), Base64.NO_WRAP)
                    val loaded = DeviceIdentity.load(id, public)
                    assertTrue(public.contentEquals(loaded.publicKey))
                    NativeChannel.open(loaded, "phone").use {
                        assertTrue(it.signAuthorization("restart fixture".toByteArray()).size in 8..72)
                    }
                } finally {
                    DeviceIdentity.delete(id)
                    check(store.edit().clear().commit())
                }
            }
            "cleanup" -> {
                store.getString("id", null)?.let(DeviceIdentity::delete)
                check(store.edit().clear().commit())
            }
            else -> error("Use prepare, verify or cleanup")
        }
    }
}
