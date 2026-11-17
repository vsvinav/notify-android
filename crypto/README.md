# Build native Android crypto

Prerequisites: Rust 1.91.0 with the `aarch64-linux-android` target, Android NDK
28.2.13676358, and JDK 17 for host tests. The current Android build script uses
the NDK's macOS toolchain. It does not install tools.

From the Android repository root, with your Cargo environment configured:

```sh
export ANDROID_NDK_HOME="$PWD/.local-build/android-sdk/ndk/28.2.13676358"
bash crypto/build-android.sh
```

Output: `crypto/jniLibs/arm64-v8a/libnotify_android.so`, targeting Android API 28
with 16KB ELF alignment. The containing APK must also preserve 16KB alignment.

The app packages this library for arm64 Android devices. Build the native library
before assembling the APK, then check the actual package from the repository root:

```sh
python3 crypto/check-apk.py app/build/outputs/apk/debug/app-debug.apk
```

This checks the packaged native build, ABI, ELF/APK alignment, and included notice
files. It does not replace Android device testing. Development APKs use only the
Notify-local key at `.local-build/android/debug.keystore`; preserve that key when
updating a paired development installation.

For Rust and host JVM JNI tests:

```sh
JAVA_HOME=/path/to/jdk17 bash crypto/test-host.sh
```

Host JVM tests are not AndroidKeyStore or device interoperability tests.

## Third-party notices

`THIRD-PARTY-NOTICES.txt` covers the locked Android Rust/JNI dependency graph.
`licenses/android-dependencies.json` records exact versions, registry checksums,
selected licenses, source locations and license-document hashes. Copies for
distribution are in `app/src/main/assets/third-party/`.

Preserve these notices when distributing the native library. MPL-2.0 components
include exact-version Source Code Form locations and license terms. Keep that
source available to recipients and do not restrict the rights granted by MPL-2.0.
Regenerate/review coverage after dependency or target changes.

This inventory is not a whole-application, Android/Gradle dependency, Rust
toolchain/runtime, or system-library license audit, and is not legal certification.
