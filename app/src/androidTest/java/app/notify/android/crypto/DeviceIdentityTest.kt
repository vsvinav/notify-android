@file:Suppress("DEPRECATION")

package app.notify.android.crypto

import android.test.AndroidTestCase
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.AlgorithmParameters
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.math.BigInteger
import java.util.UUID

@Suppress("DEPRECATION")
class DeviceIdentityTest : AndroidTestCase() {
    fun testPersistentIdentityReloadAndMissingKeyFailClosed() {
        val id = UUID.randomUUID().toString()
        val identity = DeviceIdentity.create(id)
        try {
            val public = identity.publicKey
            val loaded = DeviceIdentity.load(id, public)
            assertTrue(public.contentEquals(loaded.publicKey))
            assertEquals(identity.securityLevel, loaded.securityLevel)
            val parameters = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, public.copyOfRange(1, 33)), BigInteger(1, public.copyOfRange(33, 65)))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
            val message = "android identity agreement".toByteArray()
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(message); verify(loaded.sign(message))
            })
            expectFailure { DeviceIdentity.create(id) }
            expectFailure { DeviceIdentity.load(id, public.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }) }
            DeviceIdentity.delete(id)
            expectFailure { DeviceIdentity.load(id, public) }
            expectFailure { loaded.sign(message) }
        } finally {
            DeviceIdentity.delete(id)
        }
    }

    fun testJniRoundtripReplayWrongSenderAndClose() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        try {
            val macIdentity = DeviceIdentity.create(first)
            val phoneIdentity = DeviceIdentity.create(second)
            NativeChannel.open(macIdentity, "mac").use { mac ->
                val phone = NativeChannel.open(phoneIdentity, "android")
                phone.use {
                    mac.create("instrumentation")
                    val welcome = mac.addPinned(phone.keyPackage(), phoneIdentity.publicKey)
                    phone.joinPinned(welcome, mac.tree(), macIdentity.publicKey)
                    val frame = mac.protect("hello".toByteArray())
                    assertEquals("hello", String(phone.unprotect(frame, "mac".toByteArray())))
                    expectFailure { phone.unprotect(frame, "mac".toByteArray()) }
                    expectFailure { phone.unprotect(mac.protect(byteArrayOf(1)), "Mac".toByteArray()) }
                    expectFailure { phone.unprotect(byteArrayOf(1, 2), "mac".toByteArray()) }
                    assertEquals("reply", String(mac.unprotect(phone.protect("reply".toByteArray()), "android".toByteArray())))
                }
                phone.close()
                expectFailure { phone.protect(byteArrayOf(1)) }
            }
        } finally {
            DeviceIdentity.delete(first)
            DeviceIdentity.delete(second)
        }
    }

    private fun expectFailure(action: () -> Unit) {
        try {
            action()
            fail("Expected fail-closed rejection")
        } catch (_: Exception) {
        }
    }
}
