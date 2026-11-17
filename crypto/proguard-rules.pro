-keep class app.notify.android.crypto.NativeBridge { *; }
-keepclassmembers class app.notify.android.crypto.DeviceIdentity {
    public byte[] sign(byte[]);
}
