package app.notify.android.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class JniRoundtrip {
    private static final NativeBridge BRIDGE = new NativeBridge();
    private static final byte[] EMPTY = new byte[0];
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] call(long handle, int operation, byte[] a, byte[] b, byte[] c) {
        return BRIDGE.call(handle, operation, a, b, c);
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected native rejection");
    }

    public static void main(String[] args) throws Exception {
        var macKey = new DeviceIdentity();
        var phoneKey = new DeviceIdentity();
        long mac = BRIDGE.open(macKey, bytes("mac"), macKey.publicKey());
        long phone = BRIDGE.open(phoneKey, bytes("android"), phoneKey.publicKey());
        try {
            call(mac, 2, bytes("jni"), EMPTY, EMPTY);
            byte[] keyPackage = call(phone, 1, EMPTY, EMPTY, EMPTY);
            rejected(() -> call(phone, 1, EMPTY, EMPTY, EMPTY));
            byte[] welcome = call(mac, 3, keyPackage, phoneKey.publicKey(), EMPTY);
            call(phone, 5, welcome, call(mac, 4, EMPTY, EMPTY, EMPTY), macKey.publicKey());
            byte[] encrypted = call(mac, 7, bytes("hello"), EMPTY, EMPTY);
            byte[] tampered = encrypted.clone();
            tampered[tampered.length - 1] ^= 1;
            rejected(() -> call(phone, 8, tampered, bytes("mac"), EMPTY));
            if (!Arrays.equals(bytes("hello"), call(phone, 8, encrypted, bytes("mac"), EMPTY))) throw new AssertionError();
            rejected(() -> call(phone, 8, encrypted, bytes("mac"), EMPTY));
            rejected(() -> call(phone, 8, call(mac, 7, bytes("wrong sender"), EMPTY, EMPTY), bytes("Mac"), EMPTY));
            rejected(() -> call(mac, 7, new byte[1_048_577], EMPTY, EMPTY));
            rejected(() -> call(mac, 7, null, EMPTY, EMPTY));
            rejected(() -> call(mac, 7, new byte[1_064_961], EMPTY, EMPTY));
            rejected(() -> BRIDGE.open(phoneKey, bytes("bad"), macKey.publicKey()));
            phoneKey.oversize = true;
            rejected(() -> call(phone, 6, bytes("challenge"), EMPTY, EMPTY));
            phoneKey.oversize = false;
            phoneKey.fail = true;
            try {
                call(phone, 6, bytes("challenge"), EMPTY, EMPTY);
                throw new AssertionError("Java signing failure swallowed");
            } catch (Throwable error) {
                if (!(error instanceof GeneralSecurityException)
                    || !"fixture signing failure".equals(error.getMessage())) throw error;
            }
            phoneKey.fail = false;
            if (call(phone, 6, bytes("challenge"), EMPTY, EMPTY).length > 72) throw new AssertionError();
        } finally {
            BRIDGE.close(mac);
            BRIDGE.close(phone);
        }
        BRIDGE.close(phone);
        rejected(() -> call(phone, 7, bytes("closed"), EMPTY, EMPTY));
        for (int i = 0; i < 100; i++) {
            long handle = BRIDGE.open(phoneKey, bytes("lifetime"), phoneKey.publicKey());
            BRIDGE.close(handle);
            rejected(() -> call(phone, 1, EMPTY, EMPTY, EMPTY));
        }
        System.out.println("Host JVM JNI roundtrip, bounds, exception propagation, replay, sender and lifetime checks passed.");
    }
}
