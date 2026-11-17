package app.notify.android.crypto;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

/** Host test fixture only: exercises the actual JNI callback using the JDK P256 provider. */
public final class DeviceIdentity {
    final KeyPair key;
    boolean fail;
    boolean oversize;
    DeviceIdentity() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        key = generator.generateKeyPair();
    }
    public byte[] sign(byte[] message) throws Exception {
        if (fail) throw new GeneralSecurityException("fixture signing failure");
        if (oversize) return new byte[73];
        var signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key.getPrivate());
        signature.update(message);
        return signature.sign();
    }
    byte[] publicKey() {
        var point = ((ECPublicKey) key.getPublic()).getW();
        byte[] bytes = new byte[65];
        bytes[0] = 4;
        int offset = 1;
        for (var coordinate : new java.math.BigInteger[] {point.getAffineX(), point.getAffineY()}) {
            byte[] value = coordinate.toByteArray();
            int count = Math.min(value.length, 32);
            System.arraycopy(value, value.length - count, bytes, offset + 32 - count, count);
            offset += 32;
        }
        return bytes;
    }
}
