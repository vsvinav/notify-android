package app.notify.android.crypto;

final class NativeBridge {
    static { System.loadLibrary("notify_android"); }
    native long open(DeviceIdentity signer, byte[] label, byte[] publicKey);
    native byte[] call(long handle, int operation, byte[] a, byte[] b, byte[] c);
    native void close(long handle);
}
