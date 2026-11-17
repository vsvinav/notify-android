package app.notify.android.crypto

import java.io.Closeable

/** One fresh, two-member MLS channel. Closing destroys its in-memory ratchet, not its identity. */
class NativeChannel private constructor(private var handle: Long) : Closeable {
    @Synchronized
    fun keyPackage(): ByteArray = call(1)

    @Synchronized
    fun create(groupId: String) {
        require(groupId.length in 1..256) { "Invalid group ID" }
        call(2, groupId.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun addPinned(keyPackage: ByteArray, expectedPublic: ByteArray): ByteArray = call(3, keyPackage, expectedPublic)

    @Synchronized
    fun tree(): ByteArray = call(4)

    @Synchronized
    fun joinPinned(welcome: ByteArray, tree: ByteArray, expectedPublic: ByteArray) {
        call(5, welcome, tree, expectedPublic)
    }

    @Synchronized
    fun signAuthorization(challenge: ByteArray): ByteArray = call(6, challenge)

    @Synchronized
    fun protect(plaintext: ByteArray): ByteArray = call(7, plaintext)

    /** expectedSender is the exact protocol credential (UTF-8 "native" for the Mac), not a display name. */
    @Synchronized
    fun unprotect(frame: ByteArray, expectedSender: ByteArray): ByteArray = call(8, frame, expectedSender)

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            val closing = handle
            handle = 0
            NativeBridge.close(closing)
        }
    }

    private fun call(operation: Int, a: ByteArray = EMPTY, b: ByteArray = EMPTY, c: ByteArray = EMPTY): ByteArray {
        check(handle != 0L) { "Channel is closed" }
        require(a.size <= MAX_FRAME && b.size <= MAX_FRAME && c.size <= MAX_FRAME) { "Channel input too large" }
        return NativeBridge.call(handle, operation, a, b, c)
    }

    companion object {
        private val EMPTY = byteArrayOf()
        private const val MAX_FRAME = 1_048_576 + 16_384

        fun open(identity: DeviceIdentity, label: String): NativeChannel {
            require(label.length in 1..256) { "Invalid credential label" }
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..256) { "Invalid credential label" }
            val handle = NativeBridge.open(identity, bytes, identity.publicKey)
            check(handle != 0L) { "Cannot create native channel" }
            return NativeChannel(handle)
        }
    }
}

internal object NativeBridge {
    init { System.loadLibrary("notify_android") }
    external fun open(signer: DeviceIdentity, label: ByteArray, publicKey: ByteArray): Long
    external fun call(handle: Long, operation: Int, a: ByteArray, b: ByteArray, c: ByteArray): ByteArray
    external fun close(handle: Long)
}
