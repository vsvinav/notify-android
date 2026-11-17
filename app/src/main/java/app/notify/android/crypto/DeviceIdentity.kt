package app.notify.android.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Persistent AndroidKeyStore identity; no private-key export or software fallback. */
class DeviceIdentity private constructor(
    private val alias: String,
    publicKey: ByteArray,
    val securityLevel: SecurityLevel,
) {
    enum class SecurityLevel {
        SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, HARDWARE_UNSPECIFIED, UNKNOWN
    }

    private val pinnedPublic = publicKey.clone()
    val publicKey: ByteArray get() = pinnedPublic.clone()

    // Called synchronously by JNI. Keep this method name/signature in shrinking rules.
    fun sign(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_SIGNING_BYTES) { "Signing input too large" }
        val store = keyStore()
        val entry = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Trusted device key is missing; no replacement was created")
        check(MessageDigest.isEqual(publicBytes(entry.certificate.publicKey as ECPublicKey), pinnedPublic)) {
            "Trusted device key changed; no replacement was created"
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(payload)
            sign().also { check(it.size in 8..72) { "Invalid P256 signature" } }
        }
    }

    companion object {
        private const val PREFIX = "app.notify.android.identity."
        private const val MAX_SIGNING_BYTES = 1_048_576 + 16_384

        /** Only call after explicit new-device enrollment. Never replaces an existing key. */
        @Synchronized
        fun create(id: String): DeviceIdentity {
            val alias = alias(id)
            check(!keyStore().containsAlias(alias)) { "Device identity already exists" }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            val pair = generator.generateKeyPair()
            return load(id, publicBytes(pair.public as ECPublicKey))
        }

        /** Missing, invalidated, or mismatched trusted keys are errors, never new enrollments. */
        @Synchronized
        fun load(id: String, expectedPublic: ByteArray): DeviceIdentity {
            require(expectedPublic.size == 65 && expectedPublic[0] == 4.toByte()) { "Invalid pinned P256 key" }
            val alias = alias(id)
            val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: error("Trusted device key is missing; no replacement was created")
            val public = publicBytes(entry.certificate.publicKey as ECPublicKey)
            check(MessageDigest.isEqual(public, expectedPublic)) { "Trusted device key does not match" }
            val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .getKeySpec(entry.privateKey, KeyInfo::class.java)
            check(info.keySize == 256 && entry.privateKey.encoded == null) { "Invalid AndroidKeyStore identity" }
            val level = if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
                    else -> SecurityLevel.UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) SecurityLevel.HARDWARE_UNSPECIFIED else SecurityLevel.SOFTWARE
            }
            return DeviceIdentity(alias, public, level).also { identity ->
                val proof = "Notify Android identity check v1".toByteArray(Charsets.UTF_8)
                check(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(entry.certificate.publicKey)
                    update(proof)
                    verify(identity.sign(proof))
                }) { "Device signing key is unavailable" }
            }
        }

        /** Call only after the user explicitly forgets this device. */
        @Synchronized
        fun delete(id: String) {
            keyStore().deleteEntry(alias(id))
        }

        private fun alias(id: String): String {
            require(id.length == 36) { "Identity ID must be a canonical UUID" }
            require(UUID.fromString(id).toString() == id) { "Identity ID must be a canonical UUID" }
            return PREFIX + id
        }

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun publicBytes(key: ECPublicKey): ByteArray {
            check(key.params.curve.field.fieldSize == 256) { "Expected P256 public key" }
            return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
        }

        private fun coordinate(number: BigInteger): ByteArray {
            require(number.signum() >= 0 && number.bitLength() <= 256)
            val bytes = number.toByteArray()
            return ByteArray(32).also { output ->
                val count = minOf(bytes.size, 32)
                bytes.copyInto(output, 32 - count, bytes.size - count)
            }
        }
    }
}
